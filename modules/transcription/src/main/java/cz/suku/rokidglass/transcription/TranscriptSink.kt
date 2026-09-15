package cz.suku.rokidglass.transcription

interface TranscriptSink {
    fun submit(transcript: String): TranscriptSubmission
}

data class TranscriptSubmission(
    val sent: Boolean,
    val message: String,
)
