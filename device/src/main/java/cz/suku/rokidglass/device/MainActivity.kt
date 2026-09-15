package cz.suku.rokidglass.device

import android.app.Activity
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.content.ContextCompat
import cz.suku.rokidglass.deviceui.FannAssistantView
import cz.suku.rokidglass.platform.DisplayProduct
import cz.suku.rokidglass.platform.GlassInput
import cz.suku.rokidglass.platform.GlassInputReceiver
import cz.suku.rokidglass.platform.RokidContract
import cz.suku.rokidglass.platform.RokidSession

/** Thin device client: renders phone data and forwards physical glass input. */
class MainActivity : Activity() {
    private lateinit var screen: FannAssistantView
    private val inputReceiver = GlassInputReceiver(::handleGlassInput)
    private var lastInputAt = 0L

    private val sessionListener = object : RokidSession.Listener {
        override fun onConnectionChanged(connected: Boolean) {
            if (connected) RokidSession.sendEvent(RokidContract.READY_EVENT)
        }

        override fun onDisplayCommand(action: String, payload: String) {
            runOnUiThread {
                when (action) {
                    RokidContract.DISPLAY_CLEAR -> {
                        keepDisplayAwake(true)
                        screen.clear()
                    }
                    RokidContract.DISPLAY_TRANSCRIPT -> {
                        keepDisplayAwake(true)
                        screen.showTranscript(payload, false)
                    }
                    RokidContract.DISPLAY_PRODUCT -> runCatching {
                        DisplayProduct.fromJson(payload)
                    }.onSuccess {
                        keepDisplayAwake(false)
                        screen.showProduct(it)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = FannAssistantView(this)
        setContentView(screen)
        keepDisplayAwake(true)

        ContextCompat.registerReceiver(
            this,
            inputReceiver,
            IntentFilter().apply {
                GlassInputReceiver.actions.forEach(::addAction)
                priority = 100
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onStart() {
        super.onStart()
        keepDisplayAwake(true)
        RokidSession.attach(sessionListener)
    }

    override fun onStop() {
        keepDisplayAwake(false)
        RokidSession.detach(sessionListener)
        super.onStop()
    }

    private fun keepDisplayAwake(enabled: Boolean) {
        screen.keepScreenOn = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun handleGlassInput(input: GlassInput) {
        when (input) {
            GlassInput.PRIMARY_ACTION -> sendInput(RokidContract.INPUT_SUBMIT_EVENT)
            GlassInput.NEXT_PRODUCT -> sendInput(RokidContract.INPUT_NEXT_EVENT)
            GlassInput.PREVIOUS_PRODUCT -> sendInput(RokidContract.INPUT_PREVIOUS_EVENT)
            GlassInput.EXIT_APP -> {
                RokidSession.sendEvent(RokidContract.INPUT_EXIT_EVENT)
                finishAndRemoveTask()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                -> {
                    sendInput(RokidContract.INPUT_SUBMIT_EVENT)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    sendInput(RokidContract.INPUT_NEXT_EVENT)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    sendInput(RokidContract.INPUT_PREVIOUS_EVENT)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendInput(event: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInputAt < INPUT_DEBOUNCE_MS) return
        lastInputAt = now
        RokidSession.sendEvent(event)
    }

    override fun onDestroy() {
        unregisterReceiver(inputReceiver)
        super.onDestroy()
    }

    private companion object {
        const val INPUT_DEBOUNCE_MS = 700L
    }
}
