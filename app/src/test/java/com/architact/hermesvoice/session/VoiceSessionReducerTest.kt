package com.architact.hermesvoice.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionReducerTest {
    private fun reduce(state: VoiceState, event: VoiceEvent) = VoiceSessionReducer.reduce(state, event)
    private val followUp = VoiceState.Listening(followUp = true)
    private val waiting = VoiceState.Waiting("r-1", "질문")
    private val approving = VoiceState.Approving("r-1", "질문", "run-1", "파일 삭제", "rm x")

    @Test
    fun `start always begins with the spoken prompt`() {
        assertEquals(VoiceState.Prompting, reduce(VoiceState.Idle, VoiceEvent.Start))
        assertEquals(VoiceState.Prompting, reduce(waiting, VoiceEvent.Start))
    }

    @Test
    fun `quick start listens at once with a cue tone and no prompt`() {
        assertEquals(VoiceState.Listening(followUp = false, cue = true), reduce(VoiceState.Idle, VoiceEvent.QuickStart))
        assertEquals(VoiceState.Listening(followUp = false, cue = true), reduce(VoiceState.Ended(), VoiceEvent.QuickStart))
    }

    @Test
    fun `listening starts only after the prompt finishes`() {
        assertEquals(VoiceState.Listening(), reduce(VoiceState.Prompting, VoiceEvent.PromptFinished))
        assertEquals(VoiceState.Idle, reduce(VoiceState.Idle, VoiceEvent.PromptFinished))
    }

    @Test
    fun `recognized text starts waiting on a new request`() {
        assertEquals(VoiceState.Waiting("r-1", "오늘 일정"), reduce(VoiceState.Listening(), VoiceEvent.Recognized(" 오늘 일정 ", "r-1")))
    }

    @Test
    fun `empty first utterance asks to repeat but silence after an answer pauses`() {
        assertEquals(VoiceState.Error(ErrorKind.NoSpeech), reduce(VoiceState.Listening(), VoiceEvent.Recognized("  ", "r-1")))
        assertEquals(VoiceState.Ended(), reduce(followUp, VoiceEvent.Recognized("", "r-1")))
        assertEquals(VoiceState.Ended(), reduce(followUp, VoiceEvent.RecognitionFailed(ErrorKind.NoSpeech)))
        assertEquals(VoiceState.Error(ErrorKind.Microphone), reduce(followUp, VoiceEvent.RecognitionFailed(ErrorKind.Microphone)))
    }

    @Test
    fun `end phrase closes the conversation with a farewell`() {
        assertEquals(VoiceState.Ended(farewell = true), reduce(followUp, VoiceEvent.Recognized("고마워!", "r-1")))
    }

    @Test
    fun `progress updates only the matching waiting request`() {
        assertEquals(waiting.copy(progress = "검색하고 있어요."), reduce(waiting, VoiceEvent.Progress("r-1", "검색하고 있어요.")))
        assertEquals(waiting, reduce(waiting, VoiceEvent.Progress("r-2", "검색하고 있어요.")))
    }

    @Test
    fun `streamed text starts speaking and accumulates`() {
        val first = reduce(waiting, VoiceEvent.ReplyText("r-1", "첫 문장. "))
        assertEquals(VoiceState.Speaking("r-1", "질문", "첫 문장. ", complete = false), first)
        assertEquals(
            VoiceState.Speaking("r-1", "질문", "첫 문장. 둘째 문장.", complete = false),
            reduce(first, VoiceEvent.ReplyText("r-1", "둘째 문장.")),
        )
    }

    @Test
    fun `done completes the reply even without streamed text`() {
        assertEquals(VoiceState.Speaking("r-1", "질문", "답", complete = true), reduce(waiting, VoiceEvent.ReplyDone("r-1", "답")))
        val speaking = VoiceState.Speaking("r-1", "질문", "답 일부", complete = false)
        assertEquals(speaking.copy(reply = "답 전체", complete = true), reduce(speaking, VoiceEvent.ReplyDone("r-1", "답 전체")))
    }

    @Test
    fun `events for another or abandoned request are ignored`() {
        assertEquals(waiting, reduce(waiting, VoiceEvent.ReplyText("r-2", "남의 답")))
        assertEquals(VoiceState.Idle, reduce(VoiceState.Idle, VoiceEvent.ReplyDone("r-1", "늦은 답")))
        assertEquals(waiting, reduce(waiting, VoiceEvent.RequestFailed("r-2", ErrorKind.Timeout)))
    }

    @Test
    fun `speech finishing listens again only once the reply is complete`() {
        val partial = VoiceState.Speaking("r-1", "질문", "답", complete = false)
        assertEquals(partial, reduce(partial, VoiceEvent.SpeechFinished))
        assertEquals(followUp, reduce(partial.copy(complete = true), VoiceEvent.SpeechFinished))
    }

    @Test
    fun `interrupt listens immediately`() {
        assertEquals(followUp, reduce(VoiceState.Speaking("r-1", "질문", "답", complete = false), VoiceEvent.Interrupt))
        assertEquals(VoiceState.Idle, reduce(VoiceState.Idle, VoiceEvent.Interrupt))
    }

    @Test
    fun `approval pauses the task and asks before listening`() {
        val asked = reduce(waiting, VoiceEvent.ApprovalRequested("r-1", "run-1", "파일 삭제", "rm x"))
        assertEquals(approving, asked)
        assertEquals(approving.copy(listening = true), reduce(asked, VoiceEvent.ApprovalPromptFinished))
    }

    @Test
    fun `approval decision returns to waiting on the same request`() {
        assertEquals(waiting.copy(progress = "승인했어요"), reduce(approving.copy(listening = true), VoiceEvent.ApprovalDecided(true)))
        assertEquals(waiting.copy(progress = "거부했어요"), reduce(approving, VoiceEvent.ApprovalDecided(false)))
    }

    @Test
    fun `pausing during approval keeps it pending for the buttons`() {
        assertEquals(approving, reduce(approving.copy(listening = true), VoiceEvent.Pause))
    }

    @Test
    fun `retryable failure keeps the same request id for retry`() {
        val speaking = VoiceState.Speaking("r-1", "질문", "답 일부", complete = false)
        val failed = reduce(speaking, VoiceEvent.RequestFailed("r-1", ErrorKind.AgentUnavailable))
        assertEquals(VoiceState.Error(ErrorKind.AgentUnavailable, retry = waiting), failed)
        assertEquals(waiting, reduce(failed, VoiceEvent.Retry))
    }

    @Test
    fun `non-retryable failure cannot be resent`() {
        val failed = reduce(waiting, VoiceEvent.RequestFailed("r-1", ErrorKind.Unauthorized))
        assertEquals(VoiceState.Error(ErrorKind.Unauthorized), failed)
        assertEquals(failed, reduce(failed, VoiceEvent.Retry))
    }

    @Test
    fun `continue and pause`() {
        assertEquals(followUp, reduce(VoiceState.Ended(), VoiceEvent.Continue))
        assertEquals(followUp, reduce(VoiceState.Error(ErrorKind.Timeout), VoiceEvent.Continue))
        assertEquals(VoiceState.Ended(), reduce(followUp, VoiceEvent.Pause))
        assertEquals(waiting, reduce(waiting, VoiceEvent.Pause))
    }

    @Test
    fun `cancel returns to idle from any state`() {
        assertEquals(VoiceState.Idle, reduce(waiting, VoiceEvent.Cancel))
        assertEquals(VoiceState.Idle, reduce(approving, VoiceEvent.Cancel))
    }

    @Test
    fun `end phrases must be the whole utterance`() {
        assertTrue(VoiceCommands.isEndOfConversation("대화 종료."))
        assertFalse(VoiceCommands.isEndOfConversation("고마워 그리고 내일 일정도 알려줘"))
    }

    @Test
    fun `only short clear yes answers approve`() {
        listOf("응", "네", "네 진행해", "응 해줘", "좋아", "오케이", "승인").forEach { assertTrue(it, VoiceCommands.isApproval(it)) }
        listOf("아니", "아니요", "하지 마", "잠깐만", "취소해", "", "음", "글쎄 잘 모르겠는데 일단 해볼까")
            .forEach { assertFalse(it, VoiceCommands.isApproval(it)) }
    }
}
