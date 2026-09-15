package cz.suku.rokidglass.device

import android.app.Activity
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var connectionStatus: TextView
    private lateinit var profileLabel: TextView
    private lateinit var profileName: TextView
    private lateinit var selectionNeed: TextView
    private lateinit var questions: TextView
    private lateinit var objections: TextView
    private lateinit var hint: TextView

    private var cxrConnected = false
    @Volatile private var navigationPending = false
    @Volatile private var lastProfileId: Int? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val keyReceiver = KeyReceiver(::handleGlassInput)

    private val sessionListener = object : RokidSession.Listener {
        override fun onConnectionChanged(connected: Boolean) {
            cxrConnected = connected
            if (connected) RokidSession.sendEvent(ProfileContract.READY_EVENT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())

        val cachedProfile = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE)
            .getString(PROFILE_JSON_KEY, null)
            ?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
        cachedProfile?.let(::showProfile)

        ContextCompat.registerReceiver(
            this,
            keyReceiver,
            IntentFilter().apply {
                KeyReceiver.ACTIONS.forEach(::addAction)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        loadProfile(
            direction = if (cachedProfile == null) null else ProfileDirection.CURRENT,
        )
    }

    override fun onStart() {
        super.onStart()
        RokidSession.attach(sessionListener)
    }

    override fun onStop() {
        RokidSession.detach(sessionListener)
        super.onStop()
    }

    private fun createContentView(): ScrollView {
        val density = resources.displayMetrics.density
        val horizontalPadding = (28 * density).toInt()
        val verticalPadding = (18 * density).toInt()

        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }

        connectionStatus = text(11f, Color.rgb(105, 217, 154)).apply {
            setText(R.string.standalone_mode)
        }
        profileLabel = text(12f, Color.rgb(105, 217, 154), bold = true).apply {
            setText(R.string.profile_label)
        }
        profileName = text(22f, Color.WHITE, bold = true).apply {
            setText(R.string.waiting_for_phone)
        }
        selectionNeed = text(16f, Color.rgb(232, 245, 236))
        questions = text(14f, Color.rgb(185, 200, 190))
        objections = text(13f, Color.rgb(255, 209, 102))
        hint = text(11f, Color.rgb(105, 217, 154)).apply {
            setText(R.string.swipe_hint)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            setBackgroundColor(Color.rgb(7, 19, 12))
            addView(connectionStatus)
            addView(profileLabel)
            addView(profileName)
            addView(selectionNeed)
            addView(questions)
            addView(objections)
            addView(hint)
        }

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(7, 19, 12))
            addView(content)
        }
    }

    private fun showProfile(profile: JSONObject) {
        profile.optInt("id").takeIf { it in FANN_PROFILE_IDS }?.let { lastProfileId = it }
        runOnUiThread {
            navigationPending = false
            val number = profile.optInt("profileNumber")
            profileLabel.text = if (number > 0) {
                getString(R.string.profile_label_number, number)
            } else {
                getString(R.string.profile_label)
            }
            profileName.text = profile.optString("name", "FAnn zákazník")
            selectionNeed.text = profile.optString("selectionNeed")
            questions.text = profile.optJSONArray("questions")
                ?.let { values ->
                    buildList {
                        for (index in 0 until values.length()) {
                            values.optString(index)
                                .takeIf { it.isNotBlank() }
                                ?.let { add("${index + 1}. $it") }
                        }
                    }
                }
                .orEmpty()
                .joinToString("\n")
            objections.text = profile.optJSONArray("objections")
                ?.let { values ->
                    buildList {
                        for (index in 0 until values.length()) {
                            values.optString(index)
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }
                .orEmpty()
                .joinToString(" • ")
                .let { if (it.isBlank()) "" else "Námitky: $it" }
            hint.setText(R.string.swipe_hint)
        }
    }

    private fun handleGlassInput(input: GlassInput) {
        when (input) {
            GlassInput.NEXT_PROFILE -> loadProfile(ProfileDirection.NEXT)
            GlassInput.PREVIOUS_PROFILE -> loadProfile(ProfileDirection.PREVIOUS)
            GlassInput.EXIT_APP -> runOnUiThread { finishAndRemoveTask() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    handleGlassInput(GlassInput.NEXT_PROFILE)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    handleGlassInput(GlassInput.PREVIOUS_PROFILE)
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun loadProfile(direction: ProfileDirection?) {
        if (navigationPending) return
        navigationPending = true
        runOnUiThread { hint.setText(R.string.loading_profile) }

        ioExecutor.execute {
            runCatching { fetchProfileInDirection(direction) }
                .onSuccess { profile ->
                    val json = profile.toJson()
                    val transport = activeNetworkTransport()
                    lastProfileId = profile.id
                    getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(PROFILE_JSON_KEY, json.toString())
                        .apply()
                    showProfile(json)
                    showConnectionStatus(
                        getString(R.string.direct_api_ok, transport),
                    )
                    sendDiagnosticEvent(
                        "${ProfileContract.NETWORK_TEST_OK_EVENT}:$transport",
                    )
                }
                .onFailure { error ->
                    navigationPending = false
                    val reason = error.message ?: error.javaClass.simpleName
                    val transport = activeNetworkTransport()
                    showConnectionStatus(
                        getString(
                            R.string.direct_api_failed,
                            transport,
                            reason,
                        ),
                    )
                    runOnUiThread {
                        if (lastProfileId == null) profileName.setText(R.string.profile_load_failed)
                        hint.setText(R.string.retry_hint)
                    }
                    sendDiagnosticEvent(
                        "${ProfileContract.NETWORK_TEST_FAILED_EVENT}:$transport:$reason",
                    )
                }
        }
    }

    private fun fetchProfileInDirection(direction: ProfileDirection?): FannProfile {
        var lastError: Exception? = null
        val ids = FANN_PROFILE_IDS.toList()
        val currentIndex = ids.indexOf(lastProfileId)
        val candidateIds = when {
            direction == ProfileDirection.CURRENT && currentIndex >= 0 ->
                (0 until ids.size).map { distance ->
                    ids[(currentIndex + distance).mod(ids.size)]
                }
            direction == null || currentIndex < 0 -> ids
            else -> (1..ids.size).map { distance ->
                val index = (currentIndex + direction.step * distance).mod(ids.size)
                ids[index]
            }
        }

        for (profileId in candidateIds) {
            try {
                return fetchProfile(profileId)
            } catch (error: ProfileUnavailableException) {
                lastError = error
            } catch (error: IOException) {
                throw error
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
                throw ProfileUnavailableException("FAnn API vrátilo HTTP $responseCode")
            }

            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (!root.optBoolean("success")) {
                throw ProfileUnavailableException("FAnn API nevrátilo úspěšnou odpověď")
            }
            val data = root.optJSONObject("data")
                ?: throw ProfileUnavailableException("FAnn API neobsahuje profil")
            if (data.optInt("published", 1) != 1) {
                throw ProfileUnavailableException("FAnn profil není publikovaný")
            }
            val returnedId = data.optInt("id")
            if (returnedId != profileId) {
                throw ProfileUnavailableException("FAnn API vrátilo jiný profil")
            }

            fun stringList(name: String): List<String> {
                val values = data.optJSONArray(name) ?: return emptyList()
                return buildList {
                    for (index in 0 until values.length()) {
                        values.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }

            return FannProfile(
                id = returnedId,
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

    private fun showConnectionStatus(message: String) {
        runOnUiThread { connectionStatus.text = message }
    }

    private fun sendDiagnosticEvent(event: String) {
        if (cxrConnected) RokidSession.sendEvent(event)
    }

    private fun activeNetworkTransport(): String {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return "bez sítě"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobilní síť"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "jiná síť"
        }
    }

    override fun onDestroy() {
        unregisterReceiver(keyReceiver)
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val PROFILE_PREFS = "rokid_profile"
        const val PROFILE_JSON_KEY = "last_profile_json"
        const val PROFILE_URL = "https://fann-crm.netlify.app/api/admin/profile"
        const val NETWORK_TIMEOUT_MS = 10_000
        val FANN_PROFILE_IDS = 11..20
    }

    private enum class ProfileDirection(val step: Int) {
        CURRENT(0),
        NEXT(1),
        PREVIOUS(-1),
    }

    private class ProfileUnavailableException(message: String) : Exception(message)

    private data class FannProfile(
        val id: Int,
        val profileNumber: Int,
        val name: String,
        val selectionNeed: String,
        val questions: List<String>,
        val objections: List<String>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("profileNumber", profileNumber)
            .put("name", clean(name.ifBlank { "FAnn zákazník" }))
            .put("selectionNeed", clean(selectionNeed, 180))
            .put("questions", JSONArray(questions.take(3).map { clean(it, 130) }))
            .put("objections", JSONArray(objections.take(2).map { clean(it, 100) }))

        private fun clean(text: String, maxLength: Int = Int.MAX_VALUE): String =
            text.replace(Regex("\\s+"), " ").trim().take(maxLength)
    }
}
