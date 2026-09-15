package cz.suku.rokidglass

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var actionButton: Button
    private lateinit var stopButton: Button
    private lateinit var uninstallButton: Button

    private var cxrConnected = false
    private var glassesConnected = false
    private var appInstalled = false
    private var appRunning = false
    private var deviceReady = false
    private var appStatusQueryInProgress = false
    private var appInstallInProgress = false
    private var appStartInProgress = false
    private var pendingDeviceVersion: Long? = null

    private val cxrLink: CXRLink by lazy {
        CXRLink(applicationContext).apply {
            configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    DEVICE_APP_PACKAGE,
                ),
            )
            setCXRLinkCbk(linkCallback)
            setCXRCustomCmdCbk(customCommandCallback)
            setCXRGlassAppCbk(glassAppCallback)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(
            this,
            Intent(this, RokidConnectionService::class.java),
        )

        status = TextView(this).apply {
            text = getString(R.string.rokid_disconnected)
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        actionButton = Button(this).apply {
            text = getString(R.string.connect_rokid)
            setOnClickListener { launchDeviceApp() }
        }
        stopButton = Button(this).apply {
            text = getString(R.string.stop_device_app)
            isEnabled = false
            setOnClickListener { stopDeviceApp() }
        }
        uninstallButton = Button(this).apply {
            text = getString(R.string.uninstall_device_app)
            isEnabled = false
            setOnClickListener { confirmUninstallDeviceApp() }
        }

        val spacing = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(spacing, spacing, spacing, spacing)
            setBackgroundColor(Color.BLACK)
            addView(status)
            addView(actionButton)
            addView(stopButton)
            addView(uninstallButton)
        }

        setContentView(layout)
    }

    private fun launchDeviceApp() {
        if (cxrConnected && glassesConnected) {
            ensureDeviceAppReady()
        } else {
            authorizeAndConnect()
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
            val immediateResult = AuthorizationHelper.requestAuthorization(
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
        appRunning = false
        showStatus(getString(R.string.rokid_connecting), enableButton = false)

        if (!cxrLink.connect(token)) {
            showStatus(getString(R.string.rokid_connection_failed), enableButton = true)
        }
    }

    private fun ensureDeviceAppReady() {
        if (!cxrConnected || !glassesConnected) return

        when {
            appRunning && deviceReady ->
                showStatus(getString(R.string.rokid_opened), enableButton = true)
            appRunning ->
                showStatus(getString(R.string.rokid_waiting_device_app), enableButton = false)
            appInstalled -> startDeviceApp()
            !appStatusQueryInProgress && !appInstallInProgress -> {
                appStatusQueryInProgress = true
                showStatus(getString(R.string.rokid_checking_device_app), enableButton = false)
                cxrLink.appIsInstalled(glassAppCallback)
            }
        }
    }

    private fun installDeviceApp() {
        if (appInstallInProgress) return
        appInstallInProgress = true
        showStatus(getString(R.string.rokid_installing_device_app), enableButton = false)

        try {
            val apk = prepareDeviceApk()
            val packageInfo = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                ?: throw IllegalStateException("APK nemá platné informace o balíčku")
            if (packageInfo.packageName != DEVICE_APP_PACKAGE) {
                throw IllegalStateException("APK obsahuje jiný balíček: ${packageInfo.packageName}")
            }
            pendingDeviceVersion = packageInfo.longVersionCode
            cxrLink.appUploadAndInstall(apk.absolutePath, glassAppCallback)
        } catch (error: Exception) {
            appInstallInProgress = false
            showStatus(
                getString(
                    R.string.rokid_device_app_install_failed,
                    error.message ?: getString(R.string.unknown_error),
                ),
                enableButton = true,
            )
        }
    }

    private fun prepareDeviceApk(): File {
        val target = File(filesDir, DEVICE_APK_FILENAME)
        assets.open(DEVICE_APK_ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun startDeviceApp() {
        if (appStartInProgress) return
        appStartInProgress = true
        deviceReady = false
        showStatus(getString(R.string.rokid_starting_device_app), enableButton = false)
        cxrLink.appStart(DEVICE_APP_ACTIVITY, glassAppCallback)
    }

    private fun stopDeviceApp() {
        if (!cxrConnected || !glassesConnected || !appRunning) return
        showStatus(getString(R.string.rokid_stopping_device_app), enableButton = false)
        cxrLink.appStop(glassAppCallback)
    }

    private fun confirmUninstallDeviceApp() {
        if (!cxrConnected || !glassesConnected || !appInstalled) return
        AlertDialog.Builder(this)
            .setTitle(R.string.uninstall_device_app)
            .setMessage(R.string.uninstall_device_app_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.uninstall_confirm) { _, _ ->
                showStatus(getString(R.string.rokid_uninstalling_device_app), enableButton = false)
                cxrLink.appUninstall(glassAppCallback)
            }
            .show()
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(isConnected: Boolean) {
            cxrConnected = isConnected
            if (isConnected) {
                if (!glassesConnected) {
                    showStatus(getString(R.string.rokid_bluetooth_waiting))
                }
                runOnUiThread(::ensureDeviceAppReady)
            } else {
                appRunning = false
                deviceReady = false
                showStatus(getString(R.string.rokid_connection_failed), enableButton = true)
            }
        }

        override fun onGlassBtConnected(isConnected: Boolean) {
            glassesConnected = isConnected
            if (isConnected) {
                runOnUiThread(::ensureDeviceAppReady)
            } else {
                appRunning = false
                deviceReady = false
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

    private val glassAppCallback = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            runOnUiThread {
                appInstallInProgress = false
                appInstalled = success
                if (success) {
                    pendingDeviceVersion?.let {
                        getSharedPreferences(DEVICE_PREFS, MODE_PRIVATE)
                            .edit()
                            .putLong(DEVICE_VERSION_KEY, it)
                            .apply()
                    }
                    startDeviceApp()
                } else {
                    showStatus(
                        getString(R.string.rokid_device_app_install_failed, "CXR-L"),
                        enableButton = true,
                    )
                }
            }
        }

        override fun onUnInstallAppResult(success: Boolean) {
            if (success) {
                appInstalled = false
                appRunning = false
                deviceReady = false
                getSharedPreferences(DEVICE_PREFS, MODE_PRIVATE)
                    .edit()
                    .remove(DEVICE_VERSION_KEY)
                    .apply()
                showStatus(getString(R.string.rokid_device_app_uninstalled), enableButton = true)
                updateDeviceControls()
            }
        }

        override fun onOpenAppResult(success: Boolean) {
            runOnUiThread {
                appStartInProgress = false
                appRunning = success
                updateDeviceControls()
                if (success) {
                    showStatus(getString(R.string.rokid_waiting_device_app), enableButton = false)
                } else {
                    showStatus(getString(R.string.rokid_device_app_start_failed), enableButton = true)
                }
            }
        }

        override fun onStopAppResult(success: Boolean) {
            if (success) {
                appRunning = false
                deviceReady = false
                showStatus(getString(R.string.rokid_device_app_stopped), enableButton = true)
                updateDeviceControls()
            }
        }

        override fun onGlassAppResume(resumed: Boolean) {
            runOnUiThread {
                appRunning = resumed
                deviceReady = resumed
                if (resumed) {
                    showStatus(getString(R.string.rokid_opened), enableButton = true)
                } else {
                    showStatus(getString(R.string.rokid_device_app_closed), enableButton = true)
                }
                updateDeviceControls()
            }
        }

        override fun onQueryAppResult(installed: Boolean) {
            runOnUiThread {
                appStatusQueryInProgress = false
                appInstalled = installed
                updateDeviceControls()
                val embeddedVersion = runCatching {
                    val apk = prepareDeviceApk()
                    packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.longVersionCode
                }.getOrNull()
                val lastInstalledVersion = getSharedPreferences(DEVICE_PREFS, MODE_PRIVATE)
                    .getLong(DEVICE_VERSION_KEY, -1L)

                if (installed && embeddedVersion != null && embeddedVersion == lastInstalledVersion) {
                    startDeviceApp()
                } else {
                    installDeviceApp()
                }
            }
        }
    }

    private val customCommandCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key != EVENT_COMMAND || payload == null) return
            val caps = runCatching { Caps.fromBytes(payload) }.getOrNull() ?: return
            if (caps.size() == 0 || caps.at(0).type() != Caps.Value.TYPE_STRING) return

            when (caps.at(0).string) {
                READY_EVENT -> runOnUiThread {
                    appRunning = true
                    deviceReady = true
                    updateDeviceControls()
                    showStatus(getString(R.string.rokid_opened), enableButton = true)
                }
                else -> runOnUiThread {
                    when {
                        caps.at(0).string == TRANSCRIPTION_READY_EVENT -> {
                            showStatus(
                                getString(R.string.rokid_transcription_ready),
                                enableButton = true,
                            )
                        }
                        caps.at(0).string == AUDIO_STREAM_EVENT -> {
                            showStatus(getString(R.string.rokid_audio_stream), enableButton = true)
                        }
                        caps.at(0).string == AUDIO_SIGNAL_EVENT -> {
                            showStatus(getString(R.string.rokid_audio_signal), enableButton = true)
                        }
                        caps.at(0).string == AUDIO_NO_SIGNAL_EVENT -> {
                            showStatus(getString(R.string.rokid_audio_no_signal), enableButton = true)
                        }
                        caps.at(0).string.startsWith("$TRANSCRIPTION_ERROR_EVENT:") -> {
                            showStatus(
                                caps.at(0).string.substringAfter(':'),
                                enableButton = true,
                            )
                        }
                        caps.at(0).string.startsWith(NETWORK_TEST_OK_EVENT) -> {
                            showStatus(
                                getString(
                                    R.string.glasses_direct_api_ok,
                                    caps.at(0).string.substringAfter(':', "neznámá síť"),
                                ),
                                enableButton = true,
                            )
                        }
                        caps.at(0).string.startsWith(NETWORK_TEST_FAILED_EVENT) -> {
                            showStatus(getString(R.string.glasses_direct_api_failed), enableButton = true)
                        }
                    }
                }
            }
        }
    }

    private fun showStatus(message: String, enableButton: Boolean = false) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            status.text = message
            actionButton.isEnabled = enableButton
            updateDeviceControls()
        }
    }

    private fun updateDeviceControls() {
        if (!::stopButton.isInitialized || !::uninstallButton.isInitialized) return
        stopButton.isEnabled = cxrConnected && glassesConnected && appRunning
        uninstallButton.isEnabled = cxrConnected && glassesConnected && appInstalled
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private companion object {
        const val AUTH_REQUEST_CODE = 1001
        const val DEVICE_APP_PACKAGE = "cz.suku.rokidglass.device"
        const val DEVICE_APP_ACTIVITY = "cz.suku.rokidglass.device.MainActivity"
        const val DEVICE_APK_ASSET = "rokid-glass-device.apk"
        const val DEVICE_APK_FILENAME = "rokid-glass-device.apk"
        const val DEVICE_PREFS = "rokid_device_app"
        const val DEVICE_VERSION_KEY = "installed_version"

        const val EVENT_COMMAND = "cz.suku.rokidglass.event"
        const val READY_EVENT = "ready"
        const val TRANSCRIPTION_READY_EVENT = "transcription_ready"
        const val AUDIO_STREAM_EVENT = "audio_stream"
        const val AUDIO_SIGNAL_EVENT = "audio_signal"
        const val AUDIO_NO_SIGNAL_EVENT = "audio_no_signal"
        const val TRANSCRIPTION_ERROR_EVENT = "transcription_error"
        const val NETWORK_TEST_OK_EVENT = "network_test_ok"
        const val NETWORK_TEST_FAILED_EVENT = "network_test_failed"
    }
}
