package cz.suku.rokidglass.transcription

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class AndroidSpeechTranscriber(
    context: Context,
    private val listener: SpeechTranscriber.Listener,
    private val languageTag: String = "cs-CZ",
) : SpeechTranscriber, RecognitionListener {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var listening = false
    private var destroyed = false
    private var finalizedText = ""

    private val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
    }

    override fun start() {
        mainHandler.post {
            if (destroyed || active) return@post
            active = true
            if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
                active = false
                listener.onStateChanged(TranscriptionState.UNAVAILABLE)
                listener.onError("V brýlích není dostupná služba rozpoznávání řeči")
                return@post
            }
            if (recognizer == null) {
                recognizer = runCatching {
                    SpeechRecognizer.createSpeechRecognizer(applicationContext).also {
                        it.setRecognitionListener(this)
                    }
                }.getOrElse { error ->
                    active = false
                    listener.onStateChanged(TranscriptionState.UNAVAILABLE)
                    listener.onError(
                        error.message ?: "Službu rozpoznávání řeči nelze otevřít",
                    )
                    return@post
                }
            }
            startListeningCycle()
        }
    }

    override fun stop() {
        mainHandler.post {
            active = false
            listening = false
            mainHandler.removeCallbacksAndMessages(null)
            recognizer?.cancel()
            listener.onStateChanged(TranscriptionState.PAUSED)
        }
    }

    override fun reset() {
        mainHandler.post {
            finalizedText = ""
            listener.onTranscriptChanged("", true)
        }
    }

    override fun destroy() {
        mainHandler.post {
            if (destroyed) return@post
            active = false
            listening = false
            mainHandler.removeCallbacksAndMessages(null)
            recognizer?.destroy()
            recognizer = null
            destroyed = true
        }
    }

    private fun startListeningCycle() {
        if (!active || destroyed || listening) return
        listening = true
        listener.onStateChanged(TranscriptionState.STARTING)
        runCatching { recognizer?.startListening(recognitionIntent) }
            .onFailure { error ->
                listening = false
                listener.onError(error.message ?: "Rozpoznávání řeči se nepodařilo spustit")
                scheduleRestart(RETRY_DELAY_MS)
            }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!active || destroyed) return
        mainHandler.postDelayed(::startListeningCycle, delayMs)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        listener.onStateChanged(TranscriptionState.LISTENING)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults.bestResult()
        listener.onTranscriptChanged(combine(finalizedText, partial), false)
    }

    override fun onResults(results: Bundle?) {
        val result = results.bestResult()
        if (result.isNotBlank()) finalizedText = combine(finalizedText, result)
        listener.onTranscriptChanged(finalizedText, true)
        listening = false
        scheduleRestart(RESTART_DELAY_MS)
    }

    override fun onError(error: Int) {
        listening = false
        if (!active || destroyed) return

        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> scheduleRestart(RESTART_DELAY_MS)

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                active = false
                listener.onStateChanged(TranscriptionState.UNAVAILABLE)
                listener.onError("Aplikace nemá povolený přístup k mikrofonu")
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(RETRY_DELAY_MS)
            else -> {
                listener.onError(errorMessage(error))
                scheduleRestart(RETRY_DELAY_MS)
            }
        }
    }

    private fun Bundle?.bestResult(): String = this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        .orEmpty()

    private fun combine(first: String, second: String): String =
        listOf(first.trim(), second.trim()).filter(String::isNotBlank).joinToString(" ")

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Chyba mikrofonu"
        SpeechRecognizer.ERROR_CLIENT -> "Rozpoznávání řeči bylo přerušeno"
        SpeechRecognizer.ERROR_NETWORK -> "Síťová chyba rozpoznávání řeči"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Rozpoznávání řeči překročilo časový limit"
        SpeechRecognizer.ERROR_SERVER -> "Server rozpoznávání řeči není dostupný"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Server rozpoznávání řeči se odpojil"
        else -> "Chyba rozpoznávání řeči ($error)"
    }

    private companion object {
        const val RESTART_DELAY_MS = 250L
        const val RETRY_DELAY_MS = 1_500L
    }
}
