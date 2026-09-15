package cz.suku.rokidglass

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import cz.suku.rokidglass.assistant.RokidController

/** Phone UI only. The foreground service owns the long-running Rokid session. */
class PhoneActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var actionButton: Button
    private lateinit var stopButton: Button
    private lateinit var uninstallButton: Button
    private var service: RokidConnectionService? = null
    private var bound = false

    private val stateListener = RokidController.Listener(::renderState)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? RokidConnectionService.LocalBinder)?.service
            bound = service != null
            service?.controller?.attach(stateListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.controller?.detach(stateListener)
            service = null
            bound = false
            renderState(disconnectedState())
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            authorizeAndConnect()
        } else {
            service?.controller?.reportStatus(getString(R.string.rokid_bluetooth_permission_required), true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
        val serviceIntent = Intent(this, RokidConnectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun createUi() {
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
            setOnClickListener { service?.controller?.stopDeviceApp() }
        }
        uninstallButton = Button(this).apply {
            text = getString(R.string.uninstall_device_app)
            isEnabled = false
            setOnClickListener { confirmUninstallDeviceApp() }
        }
        val spacing = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(spacing, spacing, spacing, spacing)
                setBackgroundColor(Color.BLACK)
                addView(status)
                addView(actionButton)
                addView(stopButton)
                addView(uninstallButton)
            },
        )
    }

    private fun launchDeviceApp() {
        val controller = service?.controller ?: return
        if (controller.isConnected) {
            controller.launchDeviceApp()
        } else if (missingBluetoothPermissions().isNotEmpty()) {
            bluetoothPermissionLauncher.launch(missingBluetoothPermissions().toTypedArray())
        } else {
            authorizeAndConnect()
        }
    }

    private fun missingBluetoothPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
    }

    private fun authorizeAndConnect() {
        val controller = service?.controller ?: return
        val rokidAppInstalled =
            AuthorizationHelper.isRequiredRokidAppInstalled(this) ||
                AuthorizationHelper.isRequiredHiRokidInstalled(this)
        if (!rokidAppInstalled) {
            controller.reportStatus(getString(R.string.rokid_missing_app), true)
            return
        }
        controller.reportStatus(getString(R.string.rokid_authorizing), false)
        try {
            val immediateResult = AuthorizationHelper.requestAuthorization(
                this,
                arrayOf(GlassPermission.DEVICE_MANAGE, GlassPermission.MICROPHONE),
                AUTH_REQUEST_CODE,
            )
            if (immediateResult != null) {
                handleAuthorizationResult(immediateResult.first, immediateResult.second)
            }
        } catch (error: Exception) {
            controller.reportStatus(
                error.message ?: getString(R.string.rokid_auth_failed),
                true,
            )
        }
    }

    @Deprecated("Required by the current Rokid authorization SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AUTH_REQUEST_CODE) handleAuthorizationResult(resultCode, data)
    }

    private fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        val controller = service?.controller ?: return
        when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> controller.connect(result.token)
            is AuthResult.AuthCancel ->
                controller.reportStatus(getString(R.string.rokid_auth_cancelled), true)
            is AuthResult.AuthFail ->
                controller.reportStatus(getString(R.string.rokid_auth_failed), true)
        }
    }

    private fun confirmUninstallDeviceApp() {
        if (service?.controller?.isConnected != true) return
        AlertDialog.Builder(this)
            .setTitle(R.string.uninstall_device_app)
            .setMessage(R.string.uninstall_device_app_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.uninstall_confirm) { _, _ ->
                service?.controller?.uninstallDeviceApp()
            }
            .show()
    }

    private fun renderState(state: RokidController.UiState) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            status.text = state.message
            actionButton.isEnabled = state.actionEnabled
            stopButton.isEnabled = state.stopEnabled
            uninstallButton.isEnabled = state.uninstallEnabled
        }
    }

    private fun disconnectedState() = RokidController.UiState(
        getString(R.string.rokid_disconnected),
        actionEnabled = true,
        stopEnabled = false,
        uninstallEnabled = false,
    )

    override fun onDestroy() {
        service?.controller?.detach(stateListener)
        if (bound) unbindService(serviceConnection)
        bound = false
        service = null
        super.onDestroy()
    }

    private companion object {
        const val AUTH_REQUEST_CODE = 1001
    }
}
