package com.architact.hermesvoice.session

import com.architact.hermesvoice.relay.RelayEvent
import com.architact.hermesvoice.relay.RelayException
import com.architact.hermesvoice.relay.VoiceRelayClient
import com.architact.hermesvoice.speech.Speaker
import com.architact.hermesvoice.speech.SpeechInput
import com.architact.hermesvoice.speech.SpeechText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class Turn(val fromUser: Boolean, val text: String)

/**
 * Runs the side effects for each state the reducer enters. Must be driven from a single
 * (main) thread; every callback from speech, TTS and the relay is funneled through [dispatch].
 */
class VoiceSessionController(
    private val speaker: Speaker,
    private val input: SpeechInput,
    private val relay: VoiceRelayClient,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val reminderIntervalMs: Long = 40_000,
) {
    private val mutableState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()

    private val mutableTurns = MutableStateFlow<List<Turn>>(emptyList())

    /** Finished turns of the current conversation, for the on-screen history. */
    val turns: StateFlow<List<Turn>> = mutableTurns.asStateFlow()

    /** Identifies one Hermes session; follow-up questions reuse it so the agent keeps context. */
    var conversationId: String = newId()
        private set

    private var foreground = true
    private var activeRequest: String? = null
    private var stream: Job? = null
    private var streamFinished = false
    private var queuedSpeech = 0
    private var spokeRecently = false

    fun start() = dispatch(VoiceEvent.Start)

    /** Side button / assistant launch: listen right away; while a reply is being read, cut in instead. */
    fun quickStart() = dispatch(if (state.value is VoiceState.Speaking) VoiceEvent.Interrupt else VoiceEvent.QuickStart)
    fun interrupt() = dispatch(VoiceEvent.Interrupt)
    fun continueConversation() = dispatch(VoiceEvent.Continue)
    fun retry() = dispatch(VoiceEvent.Retry)
    fun decideApproval(approve: Boolean) = dispatch(VoiceEvent.ApprovalDecided(approve))
    fun cancel() = dispatch(VoiceEvent.Cancel)

    fun onForeground() { foreground = true }

    /** The microphone must not stay open once the app leaves the foreground; a running task continues. */
    fun onBackground() {
        foreground = false
        dispatch(VoiceEvent.Pause)
    }

    private fun dispatch(event: VoiceEvent) {
        val previous = mutableState.value
        val next = VoiceSessionReducer.reduce(previous, event)
        if (next == previous) return
        if (event == VoiceEvent.Start || event == VoiceEvent.QuickStart) {
            conversationId = newId()
            mutableTurns.value = emptyList()
        }
        mutableState.value = next

        if (previous.usesMicrophone && !next.usesMicrophone) input.stop()
        updateRequest(next)

        when (next) {
            VoiceState.Idle -> speaker.stop()
            VoiceState.Prompting -> speaker.speak(VoiceMessages.PROMPT) { dispatch(VoiceEvent.PromptFinished) }
            is VoiceState.Listening -> listen(cue = next.cue) {
                onResult = { dispatch(VoiceEvent.Recognized(it, newId())) }
                onError = { dispatch(VoiceEvent.RecognitionFailed(it)) }
            }
            is VoiceState.Waiting -> when (event) {
                is VoiceEvent.Recognized -> {
                    addTurn(Turn(fromUser = true, text = next.transcript))
                    say(VoiceMessages.SENT)
                }
                VoiceEvent.Retry -> say(VoiceMessages.SENT)
                is VoiceEvent.Progress -> say(event.text)
                is VoiceEvent.ApprovalDecided -> {
                    say(if (event.approve) VoiceMessages.APPROVED else VoiceMessages.DENIED)
                    val approving = previous as VoiceState.Approving
                    scope.launch {
                        // If this fails Hermes times the approval out and treats it as denied.
                        runCatching { relay.answerApproval(approving.requestId, approving.approvalId, event.approve) }
                    }
                }
                else -> Unit
            }
            is VoiceState.Speaking -> when (event) {
                is VoiceEvent.ReplyText -> queueReply(event.text, first = previous !is VoiceState.Speaking)
                is VoiceEvent.ReplyDone -> {
                    addTurn(Turn(fromUser = false, text = next.reply))
                    if (previous !is VoiceState.Speaking) queueReply(next.reply, first = true)
                    else if (queuedSpeech == 0) dispatch(VoiceEvent.SpeechFinished)
                }
                else -> Unit
            }
            is VoiceState.Approving -> when {
                event is VoiceEvent.ApprovalRequested -> {
                    queuedSpeech = 0
                    speaker.speak(VoiceMessages.approvalQuestion(next.description)) { dispatch(VoiceEvent.ApprovalPromptFinished) }
                }
                next.listening -> listen(cue = true) {
                    onResult = { dispatch(VoiceEvent.ApprovalDecided(VoiceCommands.isApproval(it))) }
                    // Silence or a recognizer failure never approves; the buttons stay available.
                    onError = { dispatch(VoiceEvent.Pause) }
                }
            }
            is VoiceState.Ended -> if (next.farewell) speaker.speak(VoiceMessages.FAREWELL) {}
            is VoiceState.Error -> speaker.speak(VoiceMessages.forError(next.kind)) {}
        }
    }

    private val VoiceState.usesMicrophone
        get() = this is VoiceState.Listening || (this is VoiceState.Approving && listening)

    private class ListenCallbacks {
        var onResult: (String) -> Unit = {}
        var onError: (ErrorKind) -> Unit = {}
    }

    private fun listen(cue: Boolean, configure: ListenCallbacks.() -> Unit) {
        speaker.stop()
        queuedSpeech = 0
        if (!foreground) { dispatch(VoiceEvent.Pause); return }
        val callbacks = ListenCallbacks().apply(configure)
        input.start(cue, callbacks.onResult, callbacks.onError)
    }

    private fun say(text: String) {
        spokeRecently = true
        queuedSpeech = 0
        speaker.speak(text) {}
    }

    /** Reads reply pieces in order; once the reply is complete and everything was read, listen again. */
    private fun queueReply(text: String, first: Boolean) {
        val speech = SpeechText.forSpeech(text)
        if (speech.isEmpty()) {
            if (queuedSpeech == 0) maybeFinishSpeaking()
            return
        }
        val onDone = {
            queuedSpeech = (queuedSpeech - 1).coerceAtLeast(0)
            if (queuedSpeech == 0) maybeFinishSpeaking()
        }
        if (first) {
            queuedSpeech = 1
            speaker.speak(speech, onDone)
        } else {
            queuedSpeech++
            speaker.enqueue(speech, onDone)
        }
    }

    private fun maybeFinishSpeaking() {
        val current = state.value
        if (current is VoiceState.Speaking && current.complete) dispatch(VoiceEvent.SpeechFinished)
    }

    private fun addTurn(turn: Turn) {
        mutableTurns.value = mutableTurns.value + turn
    }

    /** Keeps exactly one relay stream open for the request the current state is attached to. */
    private fun updateRequest(next: VoiceState) {
        val wanted = next.requestId
        val current = activeRequest
        if (current != null && current != wanted) {
            stream?.cancel()
            stream = null
            activeRequest = null
            if (!streamFinished) scope.launch { relay.cancel(current) }
        }
        if (wanted != null && wanted != activeRequest && next is VoiceState.Waiting) openStream(next)
    }

    private fun openStream(waiting: VoiceState.Waiting) {
        val id = waiting.requestId
        val conversation = conversationId
        activeRequest = id
        streamFinished = false
        spokeRecently = true
        stream = scope.launch {
            val reminders = launch {
                while (true) {
                    delay(reminderIntervalMs)
                    if (state.value is VoiceState.Waiting && !spokeRecently) say(VoiceMessages.STILL_WORKING)
                    spokeRecently = false
                }
            }
            val failure = try {
                relay.stream(id, conversation, waiting.transcript).collect { event ->
                    when (event) {
                        is RelayEvent.Progress -> dispatch(VoiceEvent.Progress(id, event.text))
                        is RelayEvent.Text -> { reminders.cancel(); dispatch(VoiceEvent.ReplyText(id, event.text)) }
                        is RelayEvent.Approval ->
                            dispatch(VoiceEvent.ApprovalRequested(id, event.approvalId, event.description, event.command))
                        is RelayEvent.Done -> { streamFinished = true; dispatch(VoiceEvent.ReplyDone(id, event.reply)) }
                    }
                }
                if (streamFinished) null else ErrorKind.AgentUnavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                e.kind
            } catch (e: Exception) {
                ErrorKind.AgentUnavailable
            } finally {
                reminders.cancel()
            }
            if (failure != null) {
                streamFinished = true
                dispatch(VoiceEvent.RequestFailed(id, failure))
            }
        }
    }
}
