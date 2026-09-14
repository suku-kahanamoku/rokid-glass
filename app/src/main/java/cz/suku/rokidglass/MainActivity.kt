package cz.suku.rokidglass

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var connectButton: Button

    private var cxrConnected = false
    private var glassesConnected = false
    private var viewRequested = false
    private var pendingProfileView: String? = null
    private var pendingProfileUpdate: String? = null
    private var lastProfileId: Int? = null
    @Volatile private var profileLoading = false
    private var touchResetInProgress = false
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val cxrLink: CXRLink by lazy {
        CXRLink(applicationContext).apply {
            configCXRSession(
                CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW),
            )
            setCXRLinkCbk(linkCallback)
            setCXRCustomViewCbk(customViewCallback)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = getString(R.string.rokid_disconnected)
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        connectButton = Button(this).apply {
            text = getString(R.string.connect_rokid)
            setOnClickListener { loadRandomProfile() }
        }

        val spacing = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(spacing, spacing, spacing, spacing)
            setBackgroundColor(Color.BLACK)
            addView(status)
            addView(connectButton)
        }

        setContentView(layout)
    }

    private fun loadRandomProfile() {
        if (profileLoading) return
        profileLoading = true
        showStatus(getString(R.string.profile_loading), enableButton = false)

        ioExecutor.execute {
            try {
                val profile = fetchRandomProfile()
                pendingProfileView = createProfileView(profile)
                pendingProfileUpdate = createProfileUpdate(profile)
                lastProfileId = profile.id

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    if (cxrConnected && glassesConnected) {
                        if (viewRequested) {
                            updateProfileInGlasses()
                        } else {
                            openProfileWhenReady()
                        }
                    } else {
                        authorizeAndConnect()
                    }
                    profileLoading = false
                }
            } catch (error: Exception) {
                profileLoading = false
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showStatus(
                        getString(
                            R.string.profile_loading_failed,
                            error.message ?: getString(R.string.unknown_error),
                        ),
                        enableButton = true,
                    )
                }
            }
        }
    }

    private fun fetchRandomProfile(): FannProfile {
        var lastError: Exception? = null
        val candidateIds = FANN_PROFILE_IDS
            .filter { it != lastProfileId }
            .shuffled()

        for (profileId in candidateIds) {
            try {
                return fetchProfile(profileId)
            } catch (error: Exception) {
                lastError = error
            }
        }

        throw lastError ?: IllegalStateException("FAnn API neobsahuje dostupný profil")
    }

    private fun fetchProfile(profileId: Int): FannProfile {
        val connection = (URL("$PROFILE_URL/$profileId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("FAnn API vrátilo HTTP $responseCode")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(response)
            if (!root.optBoolean("success")) {
                throw IllegalStateException("FAnn API nevrátilo úspěšnou odpověď")
            }

            val data = root.optJSONObject("data")
                ?: throw IllegalStateException("FAnn API neobsahuje profil")
            if (data.optInt("published", 1) != 1) {
                throw IllegalStateException("FAnn profil není publikovaný")
            }

            fun stringList(name: String): List<String> {
                val values = data.optJSONArray(name) ?: return emptyList()
                return buildList {
                    for (index in 0 until values.length()) {
                        values.optString(index)
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
            }

            return FannProfile(
                id = data.optInt("id"),
                profileNumber = data.optInt("profile_number"),
                name = data.optString("name"),
                selectionNeed = data.optString("selection_need"),
                questions = stringList("questions"),
                objections = stringList("objections"),
            )
        } finally {
            connection.disconnect()
        }
    }

    @Deprecated("Required by the current Rokid authorization SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AUTH_REQUEST_CODE) {
            handleAuthorizationResult(resultCode, data)
        }
    }

    private fun authorizeAndConnect() {
        val rokidAppInstalled =
            AuthorizationHelper.isRequiredRokidAppInstalled(this) ||
                AuthorizationHelper.isRequiredHiRokidInstalled(this)

        if (!rokidAppInstalled) {
            showStatus(getString(R.string.rokid_missing_app), enableButton = true)
            return
        }

        showStatus(getString(R.string.rokid_authorizing), enableButton = false)

        try {
            val immediateResult =
                AuthorizationHelper.requestAuthorization(
                    this,
                    arrayOf(GlassPermission.DEVICE_MANAGE),
                    AUTH_REQUEST_CODE,
                )
            if (immediateResult != null) {
                handleAuthorizationResult(immediateResult.first, immediateResult.second)
            }
        } catch (error: Exception) {
            showStatus(
                error.message ?: getString(R.string.rokid_auth_failed),
                enableButton = true,
            )
        }
    }

    private fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> connect(result.token)
            is AuthResult.AuthCancel ->
                showStatus(getString(R.string.rokid_auth_cancelled), enableButton = true)
            is AuthResult.AuthFail ->
                showStatus(getString(R.string.rokid_auth_failed), enableButton = true)
        }
    }

    private fun connect(token: String) {
        if (token.isBlank()) {
            showStatus(getString(R.string.rokid_auth_failed), enableButton = true)
            return
        }

        cxrConnected = false
        glassesConnected = false
        viewRequested = false
        showStatus(getString(R.string.rokid_connecting), enableButton = false)

        if (!cxrLink.connect(token)) {
            showStatus(getString(R.string.rokid_connection_failed), enableButton = true)
        }
    }

    private fun openProfileWhenReady() {
        val profileView = pendingProfileView ?: return
        if (!cxrConnected || !glassesConnected || viewRequested) return

        viewRequested = true
        showStatus(getString(R.string.rokid_sending), enableButton = false)

        if (!cxrLink.customViewOpen(profileView)) {
            viewRequested = false
            showStatus(getString(R.string.rokid_connection_failed), enableButton = true)
        }
    }

    private fun updateProfileInGlasses() {
        val profileUpdate = pendingProfileUpdate ?: return
        showStatus(getString(R.string.rokid_sending), enableButton = false)

        if (!cxrLink.customViewUpdate(profileUpdate)) {
            showStatus(getString(R.string.rokid_connection_failed), enableButton = true)
        }
    }

    private fun changeProfileAfterGlassesTouch() {
        if (touchResetInProgress || profileLoading) return

        touchResetInProgress = true
        viewRequested = false
        showStatus(getString(R.string.glasses_profile_gesture), enableButton = false)
        cxrLink.customViewClose()
        waitUntilCustomViewIsClosed(attempt = 0)
    }

    private fun waitUntilCustomViewIsClosed(attempt: Int) {
        status.postDelayed(
            {
                if (isFinishing || isDestroyed) return@postDelayed

                if (!cxrLink.customViewIsOpen()) {
                    touchResetInProgress = false
                    loadRandomProfile()
                } else if (attempt < CUSTOM_VIEW_CLOSE_MAX_ATTEMPTS) {
                    waitUntilCustomViewIsClosed(attempt + 1)
                } else {
                    touchResetInProgress = false
                    viewRequested = true
                    loadRandomProfile()
                }
            },
            CUSTOM_VIEW_CLOSE_POLL_MS,
        )
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(isConnected: Boolean) {
            cxrConnected = isConnected
            if (isConnected) {
                if (!glassesConnected) {
                    showStatus(getString(R.string.rokid_bluetooth_waiting))
                }
                openProfileWhenReady()
            } else {
                viewRequested = false
                showStatus(getString(R.string.rokid_connection_failed), enableButton = true)
            }
        }

        override fun onGlassBtConnected(isConnected: Boolean) {
            glassesConnected = isConnected
            if (isConnected) {
                openProfileWhenReady()
            } else {
                viewRequested = false
                showStatus(getString(R.string.rokid_bluetooth_waiting), enableButton = true)
            }
        }

        override fun onGlassDeviceInfo(deviceInfo: GlassInfo) = Unit
        override fun onGlassWearingStatus(wearing: Boolean) = Unit

        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
        override fun onGlassAiInterrupt(interruptWake: Boolean) = Unit
        override fun onGlassLauncherResume() = Unit
    }

    private val customViewCallback = object : ICustomViewCbk {
        override fun onCustomViewOpened() {
            showStatus(getString(R.string.rokid_opened), enableButton = true)
        }

        override fun onCustomViewUpdated() {
            showStatus(getString(R.string.rokid_opened), enableButton = true)
        }

        override fun onCustomViewClosed() {
            if (touchResetInProgress) return

            val loadNextProfile =
                viewRequested &&
                    cxrConnected &&
                    glassesConnected &&
                    !isFinishing &&
                    !isDestroyed
            if (loadNextProfile) {
                changeProfileAfterGlassesTouch()
            } else {
                viewRequested = false
                showStatus(getString(R.string.rokid_view_closed), enableButton = true)
            }
        }

        override fun onCustomViewIconsSent() = Unit

        override fun onCustomViewError(code: Int, message: String?) {
            viewRequested = false
            showStatus(
                getString(R.string.rokid_view_error, code, message.orEmpty()),
                enableButton = true,
            )
        }
    }

    private fun showStatus(message: String, enableButton: Boolean = false) {
        runOnUiThread {
            status.text = message
            connectButton.isEnabled = enableButton
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        if (isFinishing && ::status.isInitialized) {
            if (viewRequested) cxrLink.customViewClose()
            cxrLink.disconnect()
        }
        super.onDestroy()
    }

    private companion object {
        const val AUTH_REQUEST_CODE = 1001
        const val NETWORK_TIMEOUT_MS = 15_000
        const val CUSTOM_VIEW_CLOSE_POLL_MS = 150L
        const val CUSTOM_VIEW_CLOSE_MAX_ATTEMPTS = 20
        const val PROFILE_URL = "https://fann-crm.netlify.app/api/admin/profile"
        val FANN_PROFILE_IDS = 11..20
    }

    private data class FannProfile(
        val id: Int,
        val profileNumber: Int,
        val name: String,
        val selectionNeed: String,
        val questions: List<String>,
        val objections: List<String>,
    )

    private fun createProfileView(profile: FannProfile): String {
        val children = mutableListOf<JSONObject>()

        fun addText(id: String, text: String, size: Int, color: String, bold: Boolean = false) {
            val props = JSONObject()
                .put("id", id)
                .put("layout_width", "match_parent")
                .put("layout_height", "wrap_content")
                .put("text", text)
                .put("textColor", color)
                .put("textSize", "${size}sp")
                .put("gravity", "center")
            if (bold) props.put("textStyle", "bold")

            children += JSONObject()
                .put("type", "TextView")
                .put("props", props)
        }

        val texts = profileTexts(profile)
        addText("profileLabel", texts.getValue("profileLabel"), 12, "#FF69D99A", bold = true)
        addText("profileName", texts.getValue("profileName"), 22, "#FFFFFFFF", bold = true)
        addText("clientType", texts.getValue("clientType"), 14, "#FF69D99A", bold = true)
        addText("summary", texts.getValue("summary"), 16, "#FFE8F5EC")
        addText("aura", texts.getValue("aura"), 14, "#FFB9C8BE")
        addText("behavior", texts.getValue("behavior"), 14, "#FFD5DED8")
        addText("animals", texts.getValue("animals"), 13, "#FF69D99A")
        addText("potential", texts.getValue("potential"), 13, "#FFFFD166")

        return JSONObject()
            .put("type", "LinearLayout")
            .put(
                "props",
                JSONObject()
                    .put("id", "root")
                    .put("layout_width", "match_parent")
                    .put("layout_height", "match_parent")
                    .put("orientation", "vertical")
                    .put("gravity", "center")
                    .put("backgroundColor", "#FF07130C"),
            )
            .put("children", JSONArray(children))
            .toString()
    }

    private fun createProfileUpdate(profile: FannProfile): String {
        val updates = JSONArray()
        profileTexts(profile).forEach { (id, text) ->
            updates.put(
                JSONObject()
                    .put("action", "update")
                    .put("id", id)
                    .put("props", JSONObject().put("text", text)),
            )
        }
        return updates.toString()
    }

    private fun profileTexts(profile: FannProfile): Map<String, String> =
        linkedMapOf(
            "profileLabel" to "FANN PROFIL ${profile.profileNumber}",
            "profileName" to cleanText(profile.name.ifBlank { "FAnn zákazník" }),
            "clientType" to "POTŘEBA ZÁKAZNÍKA",
            "summary" to cleanText(profile.selectionNeed, 180),
            "aura" to profile.questions.getOrNull(0)
                .orEmpty()
                .let { if (it.isBlank()) "" else "1. $it" }
                .let { cleanText(it, 130) },
            "behavior" to profile.questions.getOrNull(1)
                .orEmpty()
                .let { if (it.isBlank()) "" else "2. $it" }
                .let { cleanText(it, 130) },
            "animals" to profile.questions.getOrNull(2)
                .orEmpty()
                .let { if (it.isBlank()) "" else "3. $it" }
                .let { cleanText(it, 130) },
            "potential" to profile.objections
                .take(2)
                .joinToString(" • ")
                .let { if (it.isBlank()) "" else "Námitky: $it" }
                .let { cleanText(it, 160) },
        )

    private fun cleanText(text: String, maxLength: Int = Int.MAX_VALUE): String =
        text.replace(Regex("\\s+"), " ").trim().take(maxLength)
}
