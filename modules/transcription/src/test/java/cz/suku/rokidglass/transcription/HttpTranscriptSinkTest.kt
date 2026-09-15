package cz.suku.rokidglass.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTranscriptSinkTest {
    @Test
    fun emptyEndpointIsAnExplicitNoOp() {
        val result = HttpTranscriptSink("").submit("Hledám večerní parfém")

        assertFalse(result.sent)
        assertEquals("Testovací URL zatím není nastavená", result.message)
    }
}
