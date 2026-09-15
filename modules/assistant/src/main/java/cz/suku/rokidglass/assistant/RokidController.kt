package cz.suku.rokidglass.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import cz.suku.rokidglass.platform.DisplayProduct
import cz.suku.rokidglass.platform.RokidContract
import cz.suku.rokidglass.products.FannProduct
import cz.suku.rokidglass.products.FannProductRepository
import cz.suku.rokidglass.products.ProductCache
import cz.suku.rokidglass.products.ProductDirection
import cz.suku.rokidglass.products.ProductNavigator
import cz.suku.rokidglass.products.ProductRepository
import cz.suku.rokidglass.transcription.HttpTranscriptSink
import cz.suku.rokidglass.transcription.TranscriptSink
import cz.suku.rokidglass.transcription.VoskPcmStreamTranscriber
import java.io.File
import java.util.concurrent.Executors

/** Service-owned Rokid session. It keeps running when the phone activity is closed. */
class RokidController(context: Context) {
    data class UiState(
        val message: String,
        val actionEnabled: Boolean,
        val stopEnabled: Boolean,
        val uninstallEnabled: Boolean,
    )

    fun interface Listener {
        fun onStateChanged(state: UiState)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val productRepository: ProductRepository = FannProductRepository()
    private val productNavigator = ProductNavigator(productRepository)
    private val transcriptSink: TranscriptSink = HttpTranscriptSink()
    private val productCache = ProductCache(appContext)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val transcriber: VoskPcmStreamTranscriber by lazy {
        VoskPcmStreamTranscriber(appContext, transcriptionListener)
    }

    @Volatile private var listener: Listener? = null
    @Volatile private var uiState = UiState(
        appContext.getString(R.string.rokid_disconnected),
        actionEnabled = true,
        stopEnabled = false,
        uninstallEnabled = false,
    )
    private var cxrConnected = false
    private var glassesConnected = false
    private var appInstalled = false
    private var appRunning = false
    private var deviceReady = false
    private var appStatusQueryInProgress = false
    private var deviceVersionChecked = false
    private var appInstallInProgress = false
    private var appStartInProgress = false
    private var pendingDeviceVersion: Long? = null
    private var recognizerReady = false
    private var audioStreaming = false
    private var productPending = false
    private var currentTranscript = ""
    private var lastProductId: Int? = productCache.load()?.id
    private var lastTranscriptSentAt = 0L
    private var lastTranscriptSent = ""
    private var audioBytesReceived = 0L
    private var audioDataReported = false
    private var audioSignalReported = false
    private var transcriptReported = false
    @Volatile private var assistantMode = AssistantMode.LISTENING

    val isConnected: Boolean
        get() = cxrConnected && glassesConnected

    private val cxrLink: CXRLink by lazy {
        CXRLink(appContext).apply {
            configCXRSession(
                CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, DEVICE_APP_PACKAGE),
            )
            setCXRLinkCbk(linkCallback)
            setCXRAudioCbk(audioStreamCallback)
            setCXRCustomCmdCbk(customCommandCallback)
            setCXRGlassAppCbk(glassAppCallback)
        }
    }

    private val transcriptionListener = object : VoskPcmStreamTranscriber.Listener {
        override fun onReady() {
            recognizerReady = true
            publish(appContext.getString(R.string.phone_transcription_ready), true)
            startGlassesMicrophoneIfReady()
        }

        override fun onTranscriptChanged(text: String, isFinal: Boolean) {
            if (assistantMode != AssistantMode.LISTENING) return
            currentTranscript = text.trim()
            if (currentTranscript.isNotBlank() && !transcriptReported) {
                transcriptReported = true
                Log.i(AUDIO_LOG_TAG, "Recognized transcript length=${currentTranscript.length}")
            }
            val now = SystemClock.elapsedRealtime()
            if (
                currentTranscript != lastTranscriptSent &&
                (isFinal || now - lastTranscriptSentAt >= TRANSCRIPT_UPDATE_INTERVAL_MS)
            ) {
                lastTranscriptSent = currentTranscript
                lastTranscriptSentAt = now
                sendDisplay(RokidContract.DISPLAY_TRANSCRIPT, currentTranscript)
            }
        }

        override fun onError(message: String) = publish(message, true)
    }

    private val audioStreamCallback = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {
            if (assistantMode != AssistantMode.LISTENING) return
            val chunk = data ?: return
            if (offset < 0 || length <= 0 || offset + length > chunk.size) return
            audioBytesReceived += length
            if (!audioDataReported) {
                audioDataReported = true
                Log.i(AUDIO_LOG_TAG, "First microphone PCM chunk received; length=$length")
                publish(appContext.getString(R.string.rokid_audio_stream), true)
            }
            if (!audioSignalReported && pcmPeak(chunk, offset, length) >= PCM_SIGNAL_THRESHOLD) {
                audioSignalReported = true
                Log.i(AUDIO_LOG_TAG, "Microphone signal detected; bytes=$audioBytesReceived")
                publish(appContext.getString(R.string.rokid_audio_signal), true)
            }
            transcriber.acceptPcm(chunk, offset, length)
        }

        override fun onAudioError(errorCode: Int, errorInfo: String?) {
            audioStreaming = false
            publish(
                appContext.getString(
                    R.string.rokid_audio_error,
                    errorInfo ?: errorCode.toString(),
                ),
                true,
            )
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            audioStreaming = started
            if (assistantMode == AssistantMode.LISTENING) {
                publish(
                    appContext.getString(
                        if (started) R.string.rokid_phone_listening
                        else R.string.rokid_audio_stopped,
                    ),
                    true,
                )
            }
        }
    }

    fun attach(listener: Listener) {
        this.listener = listener
        mainHandler.post { listener.onStateChanged(uiState) }
    }

    fun start() {
        transcriber
    }

    fun detach(listener: Listener) {
        if (this.listener === listener) this.listener = null
    }

    fun reportStatus(message: String, actionEnabled: Boolean) = publish(message, actionEnabled)

    fun connect(token: String) {
        if (token.isBlank()) {
            publish(appContext.getString(R.string.rokid_auth_failed), true)
            return
        }
        cxrConnected = false
        glassesConnected = false
        appRunning = false
        deviceVersionChecked = false
        publish(appContext.getString(R.string.rokid_connecting), false)
        if (!cxrLink.connect(token)) {
            publish(appContext.getString(R.string.rokid_connection_failed), true)
        }
    }

    fun launchDeviceApp() {
        if (isConnected) ensureDeviceAppReady()
    }

    fun stopDeviceApp() {
        if (!isConnected || !appRunning) return
        stopGlassesMicrophone()
        publish(appContext.getString(R.string.rokid_stopping_device_app), false)
        cxrLink.appStop(glassAppCallback)
    }

    fun uninstallDeviceApp() {
        if (!isConnected || !appInstalled) return
        stopGlassesMicrophone()
        publish(appContext.getString(R.string.rokid_uninstalling_device_app), false)
        cxrLink.appUninstall(glassAppCallback)
    }

    private fun ensureDeviceAppReady() {
        if (!isConnected) return
        if (!deviceVersionChecked) {
            if (!appStatusQueryInProgress && !appInstallInProgress) {
                appStatusQueryInProgress = true
                publish(appContext.getString(R.string.rokid_checking_device_app), false)
                cxrLink.appIsInstalled(glassAppCallback)
            }
            return
        }
        when {
            appRunning && deviceReady -> publish(appContext.getString(R.string.rokid_opened), true)
            appRunning -> publish(appContext.getString(R.string.rokid_waiting_device_app), false)
            appInstalled -> startDeviceApp()
        }
    }

    private fun installDeviceApp() {
        if (appInstallInProgress) return
        appInstallInProgress = true
        publish(appContext.getString(R.string.rokid_installing_device_app), false)
        try {
            val apk = prepareDeviceApk()
            val packageInfo = appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                ?: error("APK nemá platné informace o balíčku")
            check(packageInfo.packageName == DEVICE_APP_PACKAGE) {
                "APK obsahuje jiný balíček: ${packageInfo.packageName}"
            }
            pendingDeviceVersion = packageInfo.longVersionCode
            cxrLink.appUploadAndInstall(apk.absolutePath, glassAppCallback)
        } catch (error: Exception) {
            appInstallInProgress = false
            publish(
                appContext.getString(
                    R.string.rokid_device_app_install_failed,
                    error.message ?: appContext.getString(R.string.unknown_error),
                ),
                true,
            )
        }
    }

    private fun prepareDeviceApk(): File {
        val target = File(appContext.filesDir, DEVICE_APK_FILENAME)
        appContext.assets.open(DEVICE_APK_ASSET).use { input ->
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    private fun startDeviceApp() {
        if (appStartInProgress) return
        appStartInProgress = true
        deviceReady = false
        publish(appContext.getString(R.string.rokid_starting_device_app), false)
        cxrLink.appStart(DEVICE_APP_ACTIVITY, glassAppCallback)
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(isConnected: Boolean) {
            cxrConnected = isConnected
            if (isConnected) {
                if (!glassesConnected) publish(appContext.getString(R.string.rokid_bluetooth_waiting), false)
                ensureDeviceAppReady()
            } else {
                stopGlassesMicrophone()
                appRunning = false
                deviceReady = false
                publish(appContext.getString(R.string.rokid_connection_failed), true)
            }
        }

        override fun onGlassBtConnected(isConnected: Boolean) {
            glassesConnected = isConnected
            if (isConnected) {
                ensureDeviceAppReady()
            } else {
                stopGlassesMicrophone()
                appRunning = false
                deviceReady = false
                publish(appContext.getString(R.string.rokid_bluetooth_disconnected), true)
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
            appInstallInProgress = false
            appInstalled = success
            if (success) {
                deviceVersionChecked = true
                pendingDeviceVersion?.let {
                    preferences.edit().putLong(DEVICE_VERSION_KEY, it).apply()
                }
                startDeviceApp()
            } else {
                publish(
                    appContext.getString(R.string.rokid_device_app_install_failed, "CXR-L"),
                    true,
                )
            }
        }

        override fun onUnInstallAppResult(success: Boolean) {
            if (!success) return
            appInstalled = false
            appRunning = false
            deviceReady = false
            deviceVersionChecked = false
            preferences.edit().remove(DEVICE_VERSION_KEY).apply()
            publish(appContext.getString(R.string.rokid_device_app_uninstalled), true)
        }

        override fun onOpenAppResult(success: Boolean) {
            appStartInProgress = false
            appRunning = success
            publish(
                appContext.getString(
                    if (success) R.string.rokid_waiting_device_app
                    else R.string.rokid_device_app_start_failed,
                ),
                !success,
            )
        }

        override fun onStopAppResult(success: Boolean) {
            if (!success) return
            appRunning = false
            deviceReady = false
            publish(appContext.getString(R.string.rokid_device_app_stopped), true)
        }

        override fun onGlassAppResume(resumed: Boolean) {
            appRunning = resumed
            deviceReady = resumed
            if (resumed) {
                ensureDeviceAppReady()
            } else {
                stopGlassesMicrophone()
                publish(appContext.getString(R.string.rokid_device_app_closed), true)
            }
        }

        override fun onQueryAppResult(installed: Boolean) {
            appStatusQueryInProgress = false
            appInstalled = installed
            val embeddedVersion = runCatching {
                val apk = prepareDeviceApk()
                appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.longVersionCode
            }.getOrNull()
            val lastInstalledVersion = preferences.getLong(DEVICE_VERSION_KEY, -1L)
            if (installed && embeddedVersion != null && embeddedVersion == lastInstalledVersion) {
                deviceVersionChecked = true
                if (appRunning && deviceReady) {
                    publish(appContext.getString(R.string.rokid_opened), true)
                    beginListening()
                } else {
                    startDeviceApp()
                }
            } else {
                installDeviceApp()
            }
        }
    }

    private val customCommandCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key != RokidContract.EVENT_COMMAND || payload == null) return
            val caps = runCatching { Caps.fromBytes(payload) }.getOrNull() ?: return
            if (caps.size() == 0 || caps.at(0).type() != Caps.Value.TYPE_STRING) return
            when (caps.at(0).string) {
                RokidContract.READY_EVENT -> {
                    appRunning = true
                    deviceReady = true
                    if (deviceVersionChecked) {
                        publish(appContext.getString(R.string.rokid_opened), true)
                        beginListening()
                    } else {
                        ensureDeviceAppReady()
                    }
                }
                RokidContract.INPUT_SUBMIT_EVENT -> handlePrimaryInput()
                RokidContract.INPUT_NEXT_EVENT -> handleDirectionalInput(ProductDirection.NEXT)
                RokidContract.INPUT_PREVIOUS_EVENT ->
                    handleDirectionalInput(ProductDirection.PREVIOUS)
                RokidContract.INPUT_EXIT_EVENT -> stopGlassesMicrophone()
            }
        }
    }

    private fun handlePrimaryInput() {
        when (assistantMode) {
            AssistantMode.LISTENING -> submitTranscriptAndLoadProduct()
            AssistantMode.LOADING_PRODUCT -> Unit
            AssistantMode.SHOWING_PRODUCT -> beginListening()
        }
    }

    private fun handleDirectionalInput(direction: ProductDirection) {
        when (assistantMode) {
            AssistantMode.LISTENING -> submitTranscriptAndLoadProduct()
            AssistantMode.LOADING_PRODUCT -> Unit
            AssistantMode.SHOWING_PRODUCT -> loadAdjacentProduct(direction)
        }
    }

    private fun beginListening() {
        assistantMode = AssistantMode.LISTENING
        productPending = false
        currentTranscript = ""
        lastTranscriptSent = ""
        transcriptReported = false
        transcriber.reset()
        sendDisplay(RokidContract.DISPLAY_CLEAR)
        publish(appContext.getString(R.string.rokid_phone_listening), true)
        startGlassesMicrophoneIfReady()
    }

    private fun startGlassesMicrophoneIfReady() {
        if (assistantMode != AssistantMode.LISTENING) return
        if (!recognizerReady || !isConnected || !deviceReady || audioStreaming) return
        if (!cxrLink.startAudioStream(PCM_AUDIO_CODEC)) {
            publish(appContext.getString(R.string.rokid_audio_start_failed), true)
        }
    }

    private fun stopGlassesMicrophone() {
        if (audioStreaming) cxrLink.stopAudioStream()
        audioStreaming = false
        audioBytesReceived = 0L
        audioDataReported = false
        audioSignalReported = false
    }

    private fun pcmPeak(data: ByteArray, offset: Int, length: Int): Int {
        var peak = 0
        val end = offset + length - 1
        var index = offset
        while (index < end) {
            val sample = (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
            peak = maxOf(peak, kotlin.math.abs(sample.toShort().toInt()))
            index += 2
        }
        return peak
    }

    private fun sendDisplay(action: String, payload: String = "") {
        if (!isConnected || !deviceReady) return
        cxrLink.sendCustomCmd(
            RokidContract.DISPLAY_COMMAND,
            Caps().apply { write(action) },
            payload.toByteArray(Charsets.UTF_8),
        )
    }

    private fun submitTranscriptAndLoadProduct() {
        val transcript = currentTranscript.trim()
        if (transcript.isBlank() || productPending) return
        assistantMode = AssistantMode.LOADING_PRODUCT
        productPending = true
        stopGlassesMicrophone()
        publish(appContext.getString(R.string.loading_product), false)
        ioExecutor.execute {
            runCatching { transcriptSink.submit(transcript) }
            runCatching { productNavigator.getRandomProduct(lastProductId) }
                .onSuccess { product ->
                    showProduct(product)
                    transcriber.reset()
                    currentTranscript = ""
                    lastTranscriptSent = ""
                    productPending = false
                    publish(appContext.getString(R.string.product_sent_to_glasses), true)
                }
                .onFailure { error ->
                    productPending = false
                    assistantMode = AssistantMode.LISTENING
                    publish(error.message ?: appContext.getString(R.string.unknown_error), true)
                    startGlassesMicrophoneIfReady()
                }
        }
    }

    private fun loadAdjacentProduct(direction: ProductDirection) {
        if (productPending) return
        assistantMode = AssistantMode.LOADING_PRODUCT
        productPending = true
        publish(appContext.getString(R.string.loading_product), false)
        ioExecutor.execute {
            runCatching { productNavigator.getAdjacentProduct(lastProductId, direction) }
                .onSuccess { product ->
                    showProduct(product)
                    productPending = false
                    publish(appContext.getString(R.string.product_sent_to_glasses), true)
                }
                .onFailure { error ->
                    productPending = false
                    assistantMode = AssistantMode.SHOWING_PRODUCT
                    publish(error.message ?: appContext.getString(R.string.unknown_error), true)
                }
        }
    }

    private fun showProduct(product: FannProduct) {
        lastProductId = product.id
        productCache.save(product)
        assistantMode = AssistantMode.SHOWING_PRODUCT
        sendDisplay(RokidContract.DISPLAY_PRODUCT, product.toDisplayProduct().toJson())
    }

    private fun FannProduct.toDisplayProduct() = DisplayProduct(
        sku = sku,
        name = name,
        description = description,
        priceWithVat = priceWithVat,
        currency = currency,
        character = character,
        salesArgument = salesArgument,
        upsellUpgrade = upsellUpgrade,
        basketIfUpgradeDeclined = basketIfUpgradeDeclined,
        categories = categories,
        alternatives = alternatives,
    )

    private fun publish(message: String, actionEnabled: Boolean) {
        uiState = UiState(
            message = message,
            actionEnabled = actionEnabled,
            stopEnabled = isConnected && appRunning,
            uninstallEnabled = isConnected && appInstalled,
        )
        mainHandler.post { listener?.onStateChanged(uiState) }
    }

    fun destroy() {
        stopGlassesMicrophone()
        runCatching { cxrLink.disconnect() }
        transcriber.destroy()
        ioExecutor.shutdownNow()
        listener = null
    }

    private val preferences
        get() = appContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)

    private enum class AssistantMode { LISTENING, LOADING_PRODUCT, SHOWING_PRODUCT }

    private companion object {
        const val DEVICE_APP_PACKAGE = "cz.suku.rokidglass.device"
        const val DEVICE_APP_ACTIVITY = "cz.suku.rokidglass.device.MainActivity"
        const val DEVICE_APK_ASSET = "rokid-glass-device.apk"
        const val DEVICE_APK_FILENAME = "rokid-glass-device.apk"
        const val DEVICE_PREFS = "rokid_device_app"
        const val DEVICE_VERSION_KEY = "installed_version"
        const val PCM_AUDIO_CODEC = 1
        const val PCM_SIGNAL_THRESHOLD = 500
        const val TRANSCRIPT_UPDATE_INTERVAL_MS = 120L
        const val AUDIO_LOG_TAG = "RokidAudio"
    }
}
