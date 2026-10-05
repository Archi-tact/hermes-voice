package com.architact.hermesvoice.session

import com.architact.hermesvoice.relay.RelayEvent
import com.architact.hermesvoice.relay.RelayException
import com.architact.hermesvoice.relay.VoiceRelayClient
import com.architact.hermesvoice.speech.Speaker
import com.architact.hermesvoice.speech.SpeechInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionControllerTest {
    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        val queue = ArrayDeque<() -> Unit>()
        var stopped = 0
        override fun speak(text: String, onDone: () -> Unit) { queue.clear(); spoken += text; queue.addLast(onDone) }
        override fun enqueue(text: String, onDone: () -> Unit) { spoken += text; queue.addLast(onDone) }
        override fun stop() { stopped++; queue.clear() }
        fun finish() = queue.removeFirst().invoke()
        fun finishAll() { while (queue.isNotEmpty()) finish() }
    }

    private class FakeInput : SpeechInput {
        var onResult: ((String) -> Unit)? = null
        var onError: ((ErrorKind) -> Unit)? = null
        var listening = false
        var cue = false
        var patient = false
        override fun start(cue: Boolean, patient: Boolean, onResult: (String) -> Unit, onError: (ErrorKind) -> Unit) {
            listening = true; this.cue = cue; this.patient = patient; this.onResult = onResult; this.onError = onError
        }
        override fun stop() { listening = false }
        fun say(text: String) = onResult!!.invoke(text)
    }

    private data class Call(val requestId: String, val conversationId: String, val text: String)

    private class FakeRelay : VoiceRelayClient {
        val calls = mutableListOf<Call>()
        val streams = mutableListOf<Channel<Any>>()
        val approvals = mutableListOf<Triple<String, String, Boolean>>()
        val cancelled = mutableListOf<String>()

        override fun stream(requestId: String, conversationId: String, text: String): Flow<RelayEvent> {
            calls += Call(requestId, conversationId, text)
            val channel = Channel<Any>(Channel.UNLIMITED).also { streams += it }
            return kotlinx.coroutines.flow.flow {
                for (item in channel) {
                    if (item is Throwable) throw item
                    emit(item as RelayEvent)
                }
            }
        }
        override suspend fun answerApproval(requestId: String, approvalId: String, approve: Boolean) {
            approvals += Triple(requestId, approvalId, approve)
        }
        override suspend fun cancel(requestId: String) { cancelled += requestId }
        override suspend fun synthesize(text: String, voice: String) = ByteArray(0)

        fun send(event: RelayEvent) = streams.last().trySend(event)
        fun fail(kind: ErrorKind) = streams.last().trySend(RelayException(kind))
        fun reply(text: String) { send(RelayEvent.Text(text)); send(RelayEvent.Done(text)) }
    }

    private val speaker = FakeSpeaker()
    private val input = FakeInput()
    private val relay = FakeRelay()
    private var nextId = 0

    private fun TestScope.controller() = VoiceSessionController(
        speaker, input, relay, backgroundScope, newId = { "id-${++nextId}" }, reminderIntervalMs = 40_000,
    )

    /** Starts a conversation and asks [question]; leaves the session waiting on Hermes. */
    private fun VoiceSessionController.ask(question: String) {
        if (state.value == VoiceState.Idle) { start(); speaker.finish() }
        input.say(question)
    }

    @Test
    fun `round trip speaks the prompt, then streamed sentences in order`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.start()
        assertEquals(VoiceMessages.PROMPT, speaker.spoken.last())
        assertFalse("must not listen while the prompt is playing", input.listening)
        speaker.finish()
        assertTrue(input.listening)
        assertFalse(input.cue)
        assertTrue("questions wait out short pauses", input.patient)

        input.say("오늘 일정 알려줘")
        assertEquals(VoiceMessages.SENT, speaker.spoken.last())
        relay.send(RelayEvent.Text("**세 건** 있어요. "))
        relay.send(RelayEvent.Text("첫째는 회의예요."))
        assertEquals(listOf("세 건 있어요.", "첫째는 회의예요."), speaker.spoken.takeLast(2))

        speaker.finishAll()
        assertTrue("not complete yet, so no follow-up listening", session.state.value is VoiceState.Speaking)
        relay.send(RelayEvent.Done("**세 건** 있어요. 첫째는 회의예요."))
        assertEquals(VoiceState.Listening(followUp = true), session.state.value)
        assertTrue(input.cue)
        assertEquals(listOf(Turn(true, "오늘 일정 알려줘"), Turn(false, "**세 건** 있어요. 첫째는 회의예요.")), session.turns.value)
    }

    @Test
    fun `side button quick start listens immediately in a new conversation`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("첫 질문"); relay.reply("답."); speaker.finish()
        input.say("고마워")
        val spokenBefore = speaker.spoken.size

        session.quickStart()
        assertTrue(input.listening)
        assertTrue("quick start plays a cue tone", input.cue)
        assertEquals("no spoken prompt", spokenBefore, speaker.spoken.size)
        assertTrue("a new conversation starts with an empty history", session.turns.value.isEmpty())

        input.say("새 질문")
        assertNotEquals(relay.calls[0].conversationId, relay.calls[1].conversationId)
    }

    @Test
    fun `side button while a reply is read cuts in and keeps the conversation`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("길게 설명해줘")
        relay.send(RelayEvent.Text("첫 문장입니다. "))

        session.quickStart()
        assertEquals(VoiceState.Listening(followUp = true), session.state.value)
        input.say("잠깐, 다른 질문")
        assertEquals(relay.calls[0].conversationId, relay.calls[1].conversationId)
    }

    @Test
    fun `follow-up listening starts after the last sentence is read`()= runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("질문")
        relay.reply("답변입니다.")
        assertTrue(session.state.value is VoiceState.Speaking)
        speaker.finish()
        assertEquals(VoiceState.Listening(followUp = true), session.state.value)
    }

    @Test
    fun `follow-up questions stay in the same Hermes session, new conversations do not`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("첫 질문"); relay.reply("답."); speaker.finish()
        input.say("그건 왜 그래?"); relay.reply("이유."); speaker.finish()
        session.start(); speaker.finish(); input.say("새 질문")

        assertEquals(relay.calls[0].conversationId, relay.calls[1].conversationId)
        assertNotEquals(relay.calls[1].conversationId, relay.calls[2].conversationId)
        assertEquals(listOf(Turn(true, "새 질문")), session.turns.value)
    }

    @Test
    fun `progress is spoken and long silences get a still-working notice`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("보고서 정리해줘")
        relay.send(RelayEvent.Progress("파일을 살펴보고 있어요."))
        assertEquals("파일을 살펴보고 있어요.", speaker.spoken.last())

        advanceTimeBy(41_000) // progress was spoken recently: no reminder yet
        assertEquals(0, speaker.spoken.count { it == VoiceMessages.STILL_WORKING })
        advanceTimeBy(40_000)
        assertEquals(1, speaker.spoken.count { it == VoiceMessages.STILL_WORKING })

        relay.reply("정리했습니다.")
        advanceTimeBy(200_000)
        assertEquals(1, speaker.spoken.count { it == VoiceMessages.STILL_WORKING })
    }

    @Test
    fun `spoken yes approves the pending action`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("임시 파일 지워줘")
        relay.send(RelayEvent.Approval("run-1", "임시 폴더 삭제", "rm -rf tmp"))

        assertEquals(VoiceMessages.approvalQuestion("임시 폴더 삭제"), speaker.spoken.last())
        assertFalse(input.listening)
        speaker.finish()
        assertTrue(input.listening)
        assertFalse("a yes/no answer finishes on the first pause", input.patient)

        input.say("응 진행해")
        assertEquals(listOf(Triple(relay.calls.single().requestId, "run-1", true)), relay.approvals)
        assertEquals(VoiceMessages.APPROVED, speaker.spoken.last())
        assertEquals("approval must not open a second stream", 1, relay.calls.size)

        relay.reply("삭제했습니다.")
        assertTrue(session.state.value is VoiceState.Speaking)
    }

    @Test
    fun `anything unclear denies the action`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("지워줘")
        relay.send(RelayEvent.Approval("run-1", "삭제", ""))
        speaker.finish()
        input.say("글쎄 잘 모르겠는데")
        assertEquals(listOf(Triple(relay.calls.single().requestId, "run-1", false)), relay.approvals)
    }

    @Test
    fun `approval buttons work when listening was not possible`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("지워줘")
        relay.send(RelayEvent.Approval("run-1", "삭제", ""))
        speaker.finish()
        input.onError!!(ErrorKind.NoSpeech)
        assertEquals(false, (session.state.value as VoiceState.Approving).listening)
        assertTrue(relay.approvals.isEmpty())

        session.decideApproval(true)
        assertEquals(listOf(Triple(relay.calls.single().requestId, "run-1", true)), relay.approvals)
    }

    @Test
    fun `interrupting a streaming reply cancels the run and listens`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("길게 설명해줘")
        relay.send(RelayEvent.Text("첫 문장입니다. "))
        session.interrupt()

        assertEquals(VoiceState.Listening(followUp = true), session.state.value)
        assertEquals(listOf(relay.calls.single().requestId), relay.cancelled)
        assertTrue(input.listening)
    }

    @Test
    fun `stopping a task cancels it on the relay`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("작업해줘")
        session.cancel()
        assertEquals(listOf(relay.calls.single().requestId), relay.cancelled)
    }

    @Test
    fun `finished runs are not cancelled when moving on`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("질문"); relay.reply("답."); speaker.finish()
        session.cancel()
        assertTrue(relay.cancelled.isEmpty())
    }

    @Test
    fun `lost connection is retried with the same request id`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("작업해줘")
        relay.fail(ErrorKind.AgentUnavailable)
        assertEquals(VoiceMessages.forError(ErrorKind.AgentUnavailable), speaker.spoken.last())
        assertEquals(1, relay.calls.size)

        session.retry()
        assertEquals(2, relay.calls.size)
        assertEquals(relay.calls[0], relay.calls[1])
    }

    @Test
    fun `background closes the microphone but a reply finishing there does not reopen it`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("질문")
        session.onBackground()
        assertTrue(session.state.value is VoiceState.Waiting)

        relay.reply("답.")
        speaker.finish()
        assertEquals(VoiceState.Ended(), session.state.value)
        assertFalse(input.listening)
    }

    @Test
    fun `end phrase says goodbye without contacting Hermes`() = runTest(UnconfinedTestDispatcher()) {
        val session = controller()
        session.ask("질문"); relay.reply("답."); speaker.finish()
        input.say("고마워")
        assertEquals(VoiceState.Ended(farewell = true), session.state.value)
        assertEquals(VoiceMessages.FAREWELL, speaker.spoken.last())
        assertEquals(1, relay.calls.size)
    }
}
