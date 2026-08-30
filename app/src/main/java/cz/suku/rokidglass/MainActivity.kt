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

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var connectButton: Button

    private var cxrConnected = false
    private var glassesConnected = false
    private var viewRequested = false

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
            setOnClickListener { authorizeAndConnect() }
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

    private fun openHelloWorldWhenReady() {
        if (!cxrConnected || !glassesConnected || viewRequested) return

        viewRequested = true
        showStatus(getString(R.string.rokid_sending), enableButton = false)

        if (!cxrLink.customViewOpen(HELLO_WORLD_VIEW)) {
            viewRequested = false
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
                openHelloWorldWhenReady()
            } else {
                viewRequested = false
                showStatus(getString(R.string.rokid_connection_failed), enableButton = true)
            }
        }

        override fun onGlassBtConnected(isConnected: Boolean) {
            glassesConnected = isConnected
            if (isConnected) {
                openHelloWorldWhenReady()
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
            showStatus(getString(R.string.rokid_opened), enableButton = false)
        }

        override fun onCustomViewUpdated() = Unit

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
        if (isFinishing && ::status.isInitialized) {
            if (viewRequested) cxrLink.customViewClose()
            cxrLink.disconnect()
        }
        super.onDestroy()
    }

    private companion object {
        const val AUTH_REQUEST_CODE = 1001

        val HELLO_WORLD_VIEW =
            """
            {
              "type": "LinearLayout",
              "props": {
                "id": "root",
                "layout_width": "match_parent",
                "layout_height": "match_parent",
                "orientation": "vertical",
                "gravity": "center",
                "backgroundColor": "#FF000000"
              },
              "children": [
                {
                  "type": "TextView",
                  "props": {
                    "id": "helloText",
                    "layout_width": "wrap_content",
                    "layout_height": "wrap_content",
                    "text": "Hello world Rokid!",
                    "textColor": "#FF00FF00",
                    "textSize": "24sp",
                    "textStyle": "bold",
                    "gravity": "center"
                  }
                }
              ]
            }
            """.trimIndent()
    }
}
