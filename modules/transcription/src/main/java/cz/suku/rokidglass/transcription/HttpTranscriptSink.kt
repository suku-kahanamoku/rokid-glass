package cz.suku.rokidglass.transcription

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpTranscriptSink(
    private val endpoint: String = TranscriptionConfig.TRANSCRIPT_ENDPOINT,
) : TranscriptSink {
    override fun submit(transcript: String): TranscriptSubmission {
        if (endpoint.isBlank()) {
            return TranscriptSubmission(
                sent = false,
                message = "Testovací URL zatím není nastavená",
            )
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            val body = JSONObject().put("transcript", transcript).toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw TranscriptSubmissionException("Testovací URL vrátila HTTP $responseCode")
            }
            return TranscriptSubmission(sent = true, message = "Transkripce byla odeslána")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val NETWORK_TIMEOUT_MS = 10_000
    }
}

class TranscriptSubmissionException(message: String) : Exception(message)
