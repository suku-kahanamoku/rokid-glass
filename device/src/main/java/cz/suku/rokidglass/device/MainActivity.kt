package cz.suku.rokidglass.device

import android.Manifest
import android.app.Activity
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import cz.suku.rokidglass.device.ui.FannAssistantView
import cz.suku.rokidglass.platform.GlassInput
import cz.suku.rokidglass.platform.GlassInputReceiver
import cz.suku.rokidglass.platform.RokidContract
import cz.suku.rokidglass.platform.RokidSession
import cz.suku.rokidglass.products.FannProduct
import cz.suku.rokidglass.products.FannProductRepository
import cz.suku.rokidglass.products.ProductCache
import cz.suku.rokidglass.products.ProductRepository
import cz.suku.rokidglass.transcription.HttpTranscriptSink
import cz.suku.rokidglass.transcription.AudioInputState
import cz.suku.rokidglass.transcription.SpeechTranscriber
import cz.suku.rokidglass.transcription.TranscriptSink
import cz.suku.rokidglass.transcription.TranscriptionState
import cz.suku.rokidglass.transcription.VoskSpeechTranscriber
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var screen: FannAssistantView
    private lateinit var speechTranscriber: SpeechTranscriber
    private lateinit var productCache: ProductCache
    private val productRepository: ProductRepository = FannProductRepository()
    private val transcriptSink: TranscriptSink = HttpTranscriptSink()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val inputReceiver = GlassInputReceiver(::handleGlassInput)

    @Volatile private var productPending = false
    @Volatile private var lastProductId: Int? = null
    private var currentTranscript = ""
    private var cxrConnected = false
    private var lastSubmitAt = 0L

    private val sessionListener = object : RokidSession.Listener {
        override fun onConnectionChanged(connected: Boolean) {
            cxrConnected = connected
            if (connected) RokidSession.sendEvent(RokidContract.READY_EVENT)
        }
    }

    private val transcriptionListener = object : SpeechTranscriber.Listener {
        override fun onStateChanged(state: TranscriptionState) {
            runOnUiThread { screen.showTranscriptionState(state) }
            if (state == TranscriptionState.LISTENING && cxrConnected) {
                RokidSession.sendEvent(RokidContract.TRANSCRIPTION_READY_EVENT)
            }
        }

        override fun onTranscriptChanged(text: String, isFinal: Boolean) {
            runOnUiThread {
                currentTranscript = text
                screen.showTranscript(text, isFinal)
            }
        }

        override fun onError(message: String) {
            runOnUiThread { screen.showTranscriptionError(message) }
            sendDiagnosticEvent("${RokidContract.TRANSCRIPTION_ERROR_EVENT}:$message")
        }

        override fun onAudioInputChanged(state: AudioInputState) {
            val event = when (state) {
                AudioInputState.STREAMING -> RokidContract.AUDIO_STREAM_EVENT
                AudioInputState.SIGNAL_DETECTED -> RokidContract.AUDIO_SIGNAL_EVENT
                AudioInputState.NO_SIGNAL -> RokidContract.AUDIO_NO_SIGNAL_EVENT
            }
            sendDiagnosticEvent(event)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = FannAssistantView(this)
        setContentView(screen)

        productCache = ProductCache(this)
        lastProductId = productCache.load()?.id
        speechTranscriber = VoskSpeechTranscriber(this, transcriptionListener)

        ContextCompat.registerReceiver(
            this,
            inputReceiver,
            IntentFilter().apply {
                GlassInputReceiver.actions.forEach(::addAction)
                priority = 100
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        ensureMicrophonePermission()
    }

    override fun onStart() {
        super.onStart()
        RokidSession.attach(sessionListener)
    }

    override fun onResume() {
        super.onResume()
        if (hasMicrophonePermission()) speechTranscriber.start()
    }

    override fun onPause() {
        speechTranscriber.stop()
        super.onPause()
    }

    override fun onStop() {
        RokidSession.detach(sessionListener)
        super.onStop()
    }

    private fun ensureMicrophonePermission() {
        if (hasMicrophonePermission()) {
            speechTranscriber.start()
        } else {
            screen.showMicrophonePermissionRequired()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            speechTranscriber.start()
        } else {
            screen.showMicrophonePermissionRequired()
        }
    }

    private fun handleGlassInput(input: GlassInput) {
        when (input) {
            GlassInput.SUBMIT_TRANSCRIPT -> submitTranscriptAndLoadProduct()
            GlassInput.EXIT_APP -> runOnUiThread {
                speechTranscriber.stop()
                finishAndRemoveTask()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_LEFT,
                -> {
                    submitTranscriptAndLoadProduct()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun submitTranscriptAndLoadProduct() {
        val now = SystemClock.elapsedRealtime()
        if (productPending || now - lastSubmitAt < INPUT_DEBOUNCE_MS) return

        val submittedTranscript = currentTranscript.trim()
        if (submittedTranscript.isBlank()) return

        lastSubmitAt = now
        productPending = true

        speechTranscriber.stop()
        screen.setHint(R.string.loading_product)

        ioExecutor.execute {
            val submission = runCatching { transcriptSink.submit(submittedTranscript) }
                .fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        cz.suku.rokidglass.transcription.TranscriptSubmission(
                            sent = false,
                            message = error.message ?: "Transkripci se nepodařilo odeslat",
                        )
                    },
                )

            runCatching { productRepository.getRandomProduct(lastProductId) }
                .onSuccess { product ->
                    lastProductId = product.id
                    productCache.save(product)
                    runOnUiThread {
                        showProduct(product)
                        speechTranscriber.reset()
                        speechTranscriber.start()
                        screen.setHint(if (submission.sent) {
                            submission.message
                        } else {
                            getString(R.string.test_sink_disabled)
                        })
                    }
                    showConnectionStatus(getString(R.string.direct_api_ok, activeNetworkTransport()))
                    sendDiagnosticEvent("${RokidContract.PRODUCT_LOADED_EVENT}:${product.id}")
                }
                .onFailure { error ->
                    productPending = false
                    val reason = error.message ?: error.javaClass.simpleName
                    showConnectionStatus(
                        getString(R.string.direct_api_failed, activeNetworkTransport(), reason),
                    )
                    runOnUiThread {
                        screen.showProductError()
                        screen.setHint(R.string.retry_hint)
                        speechTranscriber.start()
                    }
                    sendDiagnosticEvent(
                        "${RokidContract.NETWORK_TEST_FAILED_EVENT}:${activeNetworkTransport()}:$reason",
                    )
                }
        }
    }

    private fun showProduct(product: FannProduct) {
        runOnUiThread {
            productPending = false
            lastProductId = product.id
            screen.showProduct(product)
        }
    }

    private fun showConnectionStatus(message: String) {
        runOnUiThread { screen.showConnectionStatus(message) }
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
        unregisterReceiver(inputReceiver)
        speechTranscriber.destroy()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 1001
        const val INPUT_DEBOUNCE_MS = 700L
    }
}
