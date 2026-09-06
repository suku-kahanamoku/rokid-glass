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
    private var lastProfileId: Int? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val cxrLink by lazy {
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
        showStatus(getString(R.string.profile_loading), enableButton = false)

        ioExecutor.execute {
            try {
                val profile = fetchRandomProfile()
                pendingProfileView = createProfileView(profile)
                lastProfileId = profile.id

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    if (cxrConnected && glassesConnected && viewRequested) {
                        updateProfileInGlasses()
                    } else {
                        authorizeAndConnect()
                    }
                }
            } catch (error: Exception) {
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

    private fun fetchRandomProfile(): ZooProfile {
        val connection = (URL(PROFILES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Zoo API vrátilo HTTP $responseCode")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(response)
            if (!root.optBoolean("success")) {
                throw IllegalStateException("Zoo API nevrátilo úspěšnou odpověď")
            }

            val data = root.optJSONArray("data")
                ?: throw IllegalStateException("Zoo API neobsahuje seznam profilů")
            val profiles = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val profile = item.optJSONObject("profile") ?: continue
                    add(
                        ZooProfile(
                            id = item.optInt("id"),
                            name = listOf(
                                item.optString("first_name"),
                                item.optString("last_name"),
                            ).filter { it.isNotBlank() }.joinToString(" "),
                            clientType = item.optJSONObject("client_type")
                                ?.optString("label")
                                .orEmpty(),
                            summary = profile.optString("summary"),
                            aura = profile.optString("aura"),
                            behavior = profile.optString("behavior"),
                            businessPotential = profile.optString("business_potential"),
                            preferredAnimals = profile.optJSONArray("preferred_animals")
                                ?.let { animals ->
                                    buildList {
                                        for (animalIndex in 0 until animals.length()) {
                                            animals.optString(animalIndex)
                                                .takeIf { it.isNotBlank() }
                                                ?.let(::add)
                                        }
                                    }
                                }
                                .orEmpty(),
                        ),
                    )
                }
            }

            if (profiles.isEmpty()) {
                throw IllegalStateException("Zoo API neobsahuje vyplněné profily")
            }

            return profiles
                .filter { profiles.size == 1 || it.id != lastProfileId }
                .random()
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
        val profileView = pendingProfileView ?: return
        showStatus(getString(R.string.rokid_sending), enableButton = false)

        if (!cxrLink.customViewUpdate(profileView)) {
            showStatus(getString(R.string.rokid_connection_failed), enableButton = true)
        }
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
            viewRequested = false
            showStatus(getString(R.string.rokid_view_closed), enableButton = true)
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
        const val PROFILES_URL =
            "https://zoo-crm.netlify.app/api/admin/client" +
                "?limit=100&projection=first_name,last_name,profile,client_type"
    }

    private data class ZooProfile(
        val id: Int,
        val name: String,
        val clientType: String,
        val summary: String,
        val aura: String,
        val behavior: String,
        val businessPotential: String,
        val preferredAnimals: List<String>,
    )

    private fun createProfileView(profile: ZooProfile): String {
        val children = mutableListOf<JSONObject>()

        fun addText(id: String, text: String, size: Int, color: String, bold: Boolean = false) {
            if (text.isBlank()) return
            val props = JSONObject()
                .put("id", id)
                .put("layout_width", "match_parent")
                .put("layout_height", "wrap_content")
                .put("text", text.replace(Regex("\\s+"), " ").trim())
                .put("textColor", color)
                .put("textSize", "${size}sp")
                .put("gravity", "center")
            if (bold) props.put("textStyle", "bold")

            children += JSONObject()
                .put("type", "TextView")
                .put("props", props)
        }

        addText("profileLabel", "NÁHODNÝ PROFIL", 12, "#FF69D99A", bold = true)
        addText("profileName", profile.name.ifBlank { "Zoo klient" }, 22, "#FFFFFFFF", bold = true)
        addText("clientType", profile.clientType, 14, "#FF69D99A", bold = true)
        addText("summary", profile.summary.take(180), 16, "#FFE8F5EC")
        addText("aura", profile.aura.take(120), 14, "#FFB9C8BE")
        addText("behavior", profile.behavior.take(150), 14, "#FFD5DED8")
        addText(
            "animals",
            profile.preferredAnimals.take(4).joinToString(", ").let {
                if (it.isBlank()) "" else "Oblíbená zvířata: $it"
            },
            13,
            "#FF69D99A",
        )
        addText("potential", profile.businessPotential.take(100), 13, "#FFFFD166")

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
}
