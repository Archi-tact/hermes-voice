package com.architact.hermesvoice.speech

import android.speech.SpeechRecognizer
import com.architact.hermesvoice.session.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun `markdown formatting is removed for speech`() {
        val reply = """
            ## 오늘 일정
            - **10시** 회의
            - `report_v2.pdf` 검토
            자세한 내용은 [문서](https://example.com/doc)를 보세요.
        """.trimIndent()

        assertEquals("오늘 일정\n10시 회의\nreport_v2.pdf 검토\n자세한 내용은 문서를 보세요.", SpeechText.forSpeech(reply))
    }

    @Test
    fun `code blocks and bare urls are not read aloud`() {
        val spoken = SpeechText.forSpeech("실행하세요:\n```bash\nrm -rf build\n```\n참고 https://example.com/a?b=c")
        assertTrue(spoken, !spoken.contains("rm -rf") && !spoken.contains("https"))
    }

    @Test
    fun `long text is split at sentence boundaries within the limit`() {
        val text = "첫 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다."
        val chunks = SpeechText.chunks(text, 20)

        assertEquals(listOf("첫 문장입니다. 두 번째 문장입니다.", "세 번째 문장입니다."), chunks)
        assertTrue(chunks.all { it.length <= 20 })
    }

    @Test
    fun `text without boundaries is hard split`() {
        assertEquals(listOf("abcde", "fghij", "k"), SpeechText.chunks("abcdefghijk", 5))
    }

    @Test
    fun `recognizer errors are classified`() {
        assertEquals(ErrorKind.NoSpeech, AndroidSpeechInput.errorKind(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(ErrorKind.NoSpeech, AndroidSpeechInput.errorKind(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(ErrorKind.Microphone, AndroidSpeechInput.errorKind(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(ErrorKind.Recognizer, AndroidSpeechInput.errorKind(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }
}
