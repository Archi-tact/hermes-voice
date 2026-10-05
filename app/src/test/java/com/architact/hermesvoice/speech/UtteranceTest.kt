package com.architact.hermesvoice.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceTest {
    @Test
    fun `segments split by pauses are joined into one request`() {
        val utterance = Utterance()
        utterance.addSegment("내일 오전 회의 자료를")
        utterance.partial = "정리해서 "
        assertEquals("내일 오전 회의 자료를 정리해서", utterance.text())

        utterance.addSegment(" 정리해서 메일로 보내줘 ")
        assertEquals("내일 오전 회의 자료를 정리해서 메일로 보내줘", utterance.text())
    }

    @Test
    fun `blank segments are ignored`() {
        val utterance = Utterance()
        utterance.addSegment("   ")
        assertFalse(utterance.hasFinalText)
        assertEquals("", utterance.text())
        utterance.addSegment("안녕")
        assertTrue(utterance.hasFinalText)
    }

    @Test
    fun `patience cycles short, normal, long`() {
        assertEquals(ListeningPatience.Long, ListeningPatience.Normal.next())
        assertEquals(ListeningPatience.Short, ListeningPatience.Long.next())
        assertTrue(ListeningPatience.Normal.millis > 2_000)
    }
}
