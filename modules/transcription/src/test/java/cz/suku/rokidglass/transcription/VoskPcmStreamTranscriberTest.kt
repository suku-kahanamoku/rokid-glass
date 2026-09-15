package cz.suku.rokidglass.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class VoskPcmStreamTranscriberTest {
    @Test
    fun removesUnknownTokensFromVisibleTranscript() {
        assertEquals(
            "hledám lehkou vůni",
            sanitizeRecognizerText("[unk] hledám  <unk> lehkou vůni [UNK]"),
        )
    }

    @Test
    fun unknownOnlyResultBecomesEmpty() {
        assertEquals("", sanitizeRecognizerText("[unk] <unk>"))
    }
}
