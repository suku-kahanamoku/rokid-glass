package cz.suku.rokidglass.transcription

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.math.abs
import kotlin.math.max

/** Fully offline Czech speech recognition running directly on the glasses. */
class VoskSpeechTranscriber(
    context: Context,
    private val listener: SpeechTranscriber.Listener,
) : SpeechTranscriber {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recordingThread: Thread? = null
    @Volatile private var active = false
    private var loading = false
    @Volatile private var destroyed = false
    private var finalizedText = ""

    override fun start() = onMain {
        if (destroyed || active) return@onMain
        active = true
        listener.onStateChanged(TranscriptionState.STARTING)
        if (recognizer == null) loadModel() else startListening()
    }

    override fun stop() = onMain {
        active = false
        stopRecording()
        listener.onStateChanged(TranscriptionState.PAUSED)
    }

    override fun reset() = onMain {
        finalizedText = ""
        runCatching { recognizer?.reset() }
        listener.onTranscriptChanged("", true)
    }

    override fun destroy() = onMain {
        if (destroyed) return@onMain
        active = false
        destroyed = true
        stopRecording()
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
    }

    private fun loadModel() {
        if (loading || destroyed) return
        loading = true
        StorageService.unpack(
            applicationContext,
            MODEL_ASSET_PATH,
            MODEL_STORAGE_PATH,
            { loadedModel ->
                loading = false
                if (destroyed) {
                    loadedModel.close()
                    return@unpack
                }
                model = loadedModel
                runCatching {
                    Recognizer(loadedModel, SAMPLE_RATE).also { loadedRecognizer ->
                        recognizer = loadedRecognizer
                    }
                }.onSuccess {
                    if (active) startListening()
                }.onFailure(::fail)
            },
            { error ->
                loading = false
                fail(error)
            },
        )
    }

    private fun startListening() {
        if (!active || destroyed || recordingThread?.isAlive == true) return
        val loadedRecognizer = recognizer ?: return
        runCatching {
            check(
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
            ) { "Aplikace nemá oprávnění používat mikrofon" }
            val minimumBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBufferSize > 0) { "Mikrofon nevrátil platnou velikost bufferu" }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minimumBufferSize, BUFFER_SAMPLES * 2),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Mikrofon v brýlích se nepodařilo inicializovat"
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Mikrofon v brýlích nezačal nahrávat"
            }
            audioRecord = recorder
            recordingThread = Thread(
                { recordLoop(recorder, loadedRecognizer) },
                "fann-vosk-microphone",
            ).also(Thread::start)
            listener.onStateChanged(TranscriptionState.LISTENING)
        }.onFailure(::fail)
    }

    private fun recordLoop(recorder: AudioRecord, loadedRecognizer: Recognizer) {
        val samples = ShortArray(BUFFER_SAMPLES)
        var samplesRead = 0L
        var signalReported = false
        var noSignalReported = false
        var streamReported = false

        try {
            while (active && !destroyed && !Thread.currentThread().isInterrupted) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("Čtení mikrofonu selhalo ($count)")
                if (count == 0) continue

                if (!streamReported) {
                    streamReported = true
                    onMain { listener.onAudioInputChanged(AudioInputState.STREAMING) }
                }

                samplesRead += count
                var peak = 0
                for (index in 0 until count) {
                    peak = max(peak, abs(samples[index].toInt()))
                }
                if (!signalReported && peak >= SIGNAL_PEAK_THRESHOLD) {
                    signalReported = true
                    onMain { listener.onAudioInputChanged(AudioInputState.SIGNAL_DETECTED) }
                } else if (
                    !signalReported &&
                    !noSignalReported &&
                    samplesRead >= SAMPLE_RATE.toLong() * NO_SIGNAL_SECONDS
                ) {
                    noSignalReported = true
                    onMain { listener.onAudioInputChanged(AudioInputState.NO_SIGNAL) }
                }

                if (loadedRecognizer.acceptWaveForm(samples, count)) {
                    onRecognitionResult(loadedRecognizer.result)
                } else {
                    onPartialRecognitionResult(loadedRecognizer.partialResult)
                }
            }
        } catch (error: Throwable) {
            if (active && !destroyed) onMain { fail(error) }
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            recorder.release()
            if (audioRecord === recorder) audioRecord = null
            if (recordingThread === Thread.currentThread()) recordingThread = null
        }
    }

    private fun onPartialRecognitionResult(hypothesis: String?) = onMain {
        if (!active || destroyed) return@onMain
        listener.onTranscriptChanged(
            combine(finalizedText, hypothesis.value("partial")),
            false,
        )
    }

    private fun onRecognitionResult(hypothesis: String?) = onMain {
        if (!active || destroyed) return@onMain
        commit(hypothesis.value("text"))
    }

    private fun stopRecording() {
        val recorder = audioRecord
        val worker = recordingThread
        runCatching { recorder?.stop() }
        worker?.interrupt()
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(STOP_TIMEOUT_MS) }
        }
        if (recordingThread === worker) recordingThread = null
        if (audioRecord === recorder) {
            runCatching { recorder?.release() }
            audioRecord = null
        }
    }

    private fun commit(result: String) {
        if (result.isNotBlank()) finalizedText = combine(finalizedText, result)
        listener.onTranscriptChanged(finalizedText, true)
    }

    private fun fail(error: Throwable) {
        active = false
        listener.onStateChanged(TranscriptionState.UNAVAILABLE)
        listener.onError(error.message ?: "Offline přepis řeči se nepodařilo spustit")
    }

    private fun String?.value(key: String): String = runCatching {
        JSONObject(orEmpty()).optString(key).trim()
    }.getOrDefault("")

    private fun combine(first: String, second: String): String =
        listOf(first.trim(), second.trim()).filter(String::isNotBlank).joinToString(" ")

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val BUFFER_SAMPLES = 4_000
        const val SIGNAL_PEAK_THRESHOLD = 160
        const val NO_SIGNAL_SECONDS = 5
        const val STOP_TIMEOUT_MS = 1_000L
        const val MODEL_ASSET_PATH = "model-cs"
        const val MODEL_STORAGE_PATH = "vosk"
    }
}
