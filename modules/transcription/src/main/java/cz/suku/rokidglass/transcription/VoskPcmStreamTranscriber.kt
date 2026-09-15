package cz.suku.rokidglass.transcription

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/** Offline Vosk recognizer fed by external 16 kHz mono PCM16 audio chunks. */
class VoskPcmStreamTranscriber(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onTranscriptChanged(text: String, isFinal: Boolean)
        fun onError(message: String)
    }

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizerLock = Any()
    private val enhancer = PcmAudioEnhancer()
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var finalizedText = ""
    @Volatile private var destroyed = false

    init {
        StorageService.unpack(
            applicationContext,
            MODEL_ASSET_PATH,
            MODEL_STORAGE_PATH,
            { loadedModel ->
                if (destroyed) {
                    loadedModel.close()
                    return@unpack
                }
                runCatching {
                    val loadedRecognizer = Recognizer(loadedModel, SAMPLE_RATE)
                    loadedRecognizer.setWords(true)
                    synchronized(recognizerLock) {
                        model = loadedModel
                        recognizer = loadedRecognizer
                    }
                }.onSuccess {
                    onMain(listener::onReady)
                }.onFailure(::fail)
            },
            ::fail,
        )
    }

    fun acceptPcm(data: ByteArray, offset: Int, length: Int) {
        if (destroyed || offset < 0 || length <= 0 || offset + length > data.size) return
        val audio = if (offset == 0) data else data.copyOfRange(offset, offset + length)
        enhancer.process(audio, 0, length)
        val result = runCatching {
            synchronized(recognizerLock) {
                val loadedRecognizer = recognizer ?: return
                if (loadedRecognizer.acceptWaveForm(audio, length)) {
                    RecognitionResult.Final(loadedRecognizer.result.finalText())
                } else {
                    RecognitionResult.Partial(loadedRecognizer.partialResult.value("partial"))
                }
            }
        }.getOrElse {
            fail(it)
            return
        }

        onMain {
            when (result) {
                is RecognitionResult.Final -> {
                    if (result.text.isNotBlank()) {
                        finalizedText = combine(finalizedText, result.text)
                    }
                    listener.onTranscriptChanged(finalizedText, true)
                }
                is RecognitionResult.Partial -> listener.onTranscriptChanged(
                    combine(finalizedText, result.text),
                    false,
                )
            }
        }
    }

    fun reset() {
        synchronized(recognizerLock) { recognizer?.reset() }
        finalizedText = ""
        onMain { listener.onTranscriptChanged("", true) }
    }

    fun destroy() {
        destroyed = true
        synchronized(recognizerLock) {
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            recognizer = null
            model = null
        }
    }

    private fun fail(error: Throwable) {
        if (!destroyed) onMain {
            listener.onError(error.message ?: "Offline přepis se nepodařilo spustit")
        }
    }

    private fun String?.value(key: String): String = runCatching {
        sanitizeRecognizerText(JSONObject(orEmpty()).optString(key))
    }.getOrDefault("")

    /** Drops words below [MIN_WORD_CONFIDENCE], which are usually noise picked up as speech. */
    private fun String?.finalText(): String = runCatching {
        val json = JSONObject(orEmpty())
        val words = json.optJSONArray("result")
        if (words == null || words.length() == 0) return sanitizeRecognizerText(json.optString("text"))
        buildString {
            for (index in 0 until words.length()) {
                val word = words.getJSONObject(index)
                if (word.optDouble("conf", 1.0) < MIN_WORD_CONFIDENCE) continue
                if (isNotEmpty()) append(' ')
                append(word.optString("word"))
            }
        }.let(::sanitizeRecognizerText)
    }.getOrDefault("")

    private fun combine(first: String, second: String): String =
        listOf(first.trim(), second.trim()).filter(String::isNotBlank).joinToString(" ")

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private sealed interface RecognitionResult {
        val text: String
        data class Partial(override val text: String) : RecognitionResult
        data class Final(override val text: String) : RecognitionResult
    }

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val MIN_WORD_CONFIDENCE = 0.35
        const val MODEL_ASSET_PATH = "model-cs"
        const val MODEL_STORAGE_PATH = "vosk-phone"
    }
}

internal fun sanitizeRecognizerText(text: String): String = text
    .replace(Regex("(?i)(?:\\[unk]|<unk>)"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
