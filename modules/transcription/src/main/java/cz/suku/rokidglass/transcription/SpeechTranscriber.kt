package cz.suku.rokidglass.transcription

interface SpeechTranscriber {
    interface Listener {
        fun onStateChanged(state: TranscriptionState)
        fun onTranscriptChanged(text: String, isFinal: Boolean)
        fun onError(message: String)
        fun onAudioInputChanged(state: AudioInputState) = Unit
    }

    fun start()
    fun stop()
    fun reset()
    fun destroy()
}

enum class AudioInputState {
    STREAMING,
    SIGNAL_DETECTED,
    NO_SIGNAL,
}

enum class TranscriptionState {
    STARTING,
    LISTENING,
    PAUSED,
    UNAVAILABLE,
}
