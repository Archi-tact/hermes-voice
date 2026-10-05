package com.architact.hermesvoice.session

enum class ErrorKind(val retryable: Boolean) {
    NoSpeech(false),
    Microphone(false),
    Recognizer(false),
    Unpaired(false),
    Unauthorized(false),
    BadRequest(false),
    Cancelled(false),
    NoRelay(true),
    AgentUnavailable(true),
    Timeout(true),
}

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Prompting : VoiceState

    /**
     * [followUp] is true when listening continues an ongoing conversation; [cue] plays a short tone
     * first (follow-ups, and quick starts from the side button that skip the spoken prompt).
     */
    data class Listening(val followUp: Boolean = false, val cue: Boolean = followUp) : VoiceState

    /** Hermes is working; [progress] is the latest spoken progress line. */
    data class Waiting(val requestId: String, val transcript: String, val progress: String? = null) : VoiceState

    /** Reading the reply while it streams in; [complete] once the whole reply has arrived. */
    data class Speaking(val requestId: String, val transcript: String, val reply: String, val complete: Boolean) : VoiceState

    /** Hermes paused the task for permission. [listening] while the microphone waits for yes/no. */
    data class Approving(
        val requestId: String,
        val transcript: String,
        val approvalId: String,
        val description: String,
        val command: String = "",
        val listening: Boolean = false,
    ) : VoiceState

    /** The conversation is paused; the user can continue it or start a new one. */
    data class Ended(val farewell: Boolean = false) : VoiceState

    /** [retry] is set only when the failed request may be resent (same request id) by an explicit tap. */
    data class Error(val kind: ErrorKind, val retry: Waiting? = null) : VoiceState
}

/** The request a state is attached to, if any. */
val VoiceState.requestId: String?
    get() = when (this) {
        is VoiceState.Waiting -> requestId
        is VoiceState.Speaking -> requestId
        is VoiceState.Approving -> requestId
        else -> null
    }

sealed interface VoiceEvent {
    /** Starts a new conversation. */
    data object Start : VoiceEvent

    /** Starts a new conversation and listens at once, skipping the spoken prompt (side button). */
    data object QuickStart : VoiceEvent
    data object PromptFinished : VoiceEvent
    data class Recognized(val text: String, val requestId: String) : VoiceEvent
    data class RecognitionFailed(val kind: ErrorKind) : VoiceEvent
    data class Progress(val requestId: String, val text: String) : VoiceEvent
    data class ReplyText(val requestId: String, val text: String) : VoiceEvent
    data class ReplyDone(val requestId: String, val reply: String) : VoiceEvent
    data class RequestFailed(val requestId: String, val kind: ErrorKind) : VoiceEvent
    data class ApprovalRequested(val requestId: String, val approvalId: String, val description: String, val command: String) : VoiceEvent
    data object ApprovalPromptFinished : VoiceEvent
    data class ApprovalDecided(val approve: Boolean) : VoiceEvent
    data object Retry : VoiceEvent

    /** Every queued piece of the reply has been read aloud. */
    data object SpeechFinished : VoiceEvent

    /** User taps while a reply is being read: stop reading and listen right away. */
    data object Interrupt : VoiceEvent

    /** Continue the current conversation after it was paused or failed. */
    data object Continue : VoiceEvent

    /** App left the foreground: close the microphone but keep the conversation and any running task. */
    data object Pause : VoiceEvent
    data object Cancel : VoiceEvent
}

object VoiceSessionReducer {
    fun reduce(state: VoiceState, event: VoiceEvent): VoiceState = when (event) {
        VoiceEvent.Start -> VoiceState.Prompting
        VoiceEvent.QuickStart -> VoiceState.Listening(followUp = false, cue = true)
        VoiceEvent.PromptFinished -> if (state is VoiceState.Prompting) VoiceState.Listening() else state
        is VoiceEvent.Recognized -> {
            val transcript = event.text.trim()
            when {
                state !is VoiceState.Listening -> state
                transcript.isEmpty() -> if (state.followUp) VoiceState.Ended() else VoiceState.Error(ErrorKind.NoSpeech)
                VoiceCommands.isEndOfConversation(transcript) -> VoiceState.Ended(farewell = true)
                else -> VoiceState.Waiting(event.requestId, transcript)
            }
        }
        is VoiceEvent.RecognitionFailed -> when {
            state !is VoiceState.Listening -> state
            // Silence after an answer just means the user is done for now.
            state.followUp && event.kind == ErrorKind.NoSpeech -> VoiceState.Ended()
            else -> VoiceState.Error(event.kind)
        }
        is VoiceEvent.Progress ->
            if (state is VoiceState.Waiting && state.requestId == event.requestId) state.copy(progress = event.text) else state
        is VoiceEvent.ReplyText -> when {
            state.requestId != event.requestId -> state
            state is VoiceState.Waiting -> VoiceState.Speaking(state.requestId, state.transcript, event.text, complete = false)
            state is VoiceState.Speaking && !state.complete -> state.copy(reply = state.reply + event.text)
            else -> state
        }
        is VoiceEvent.ReplyDone -> when {
            state.requestId != event.requestId -> state
            state is VoiceState.Waiting -> VoiceState.Speaking(state.requestId, state.transcript, event.reply, complete = true)
            state is VoiceState.Speaking -> state.copy(reply = event.reply, complete = true)
            state is VoiceState.Approving -> VoiceState.Speaking(state.requestId, state.transcript, event.reply, complete = true)
            else -> state
        }
        is VoiceEvent.RequestFailed -> {
            val transcript = when (state) {
                is VoiceState.Waiting -> state.transcript
                is VoiceState.Speaking -> state.transcript
                is VoiceState.Approving -> state.transcript
                else -> null
            }
            if (transcript != null && state.requestId == event.requestId) {
                VoiceState.Error(event.kind, VoiceState.Waiting(event.requestId, transcript).takeIf { event.kind.retryable })
            } else {
                state
            }
        }
        is VoiceEvent.ApprovalRequested -> when {
            state.requestId != event.requestId -> state
            state is VoiceState.Waiting || state is VoiceState.Speaking -> VoiceState.Approving(
                event.requestId, transcriptOf(state), event.approvalId, event.description, event.command,
            )
            else -> state
        }
        VoiceEvent.ApprovalPromptFinished ->
            if (state is VoiceState.Approving && !state.listening) state.copy(listening = true) else state
        is VoiceEvent.ApprovalDecided -> if (state is VoiceState.Approving) {
            VoiceState.Waiting(state.requestId, state.transcript, if (event.approve) "승인했어요" else "거부했어요")
        } else {
            state
        }
        VoiceEvent.Retry -> (state as? VoiceState.Error)?.retry ?: state
        VoiceEvent.SpeechFinished ->
            if (state is VoiceState.Speaking && state.complete) VoiceState.Listening(followUp = true) else state
        VoiceEvent.Interrupt -> if (state is VoiceState.Speaking) VoiceState.Listening(followUp = true) else state
        VoiceEvent.Continue ->
            if (state is VoiceState.Ended || state is VoiceState.Error) VoiceState.Listening(followUp = true) else state
        VoiceEvent.Pause -> when (state) {
            VoiceState.Prompting, is VoiceState.Listening -> VoiceState.Ended()
            is VoiceState.Approving -> state.copy(listening = false)
            else -> state
        }
        VoiceEvent.Cancel -> VoiceState.Idle
    }

    private fun transcriptOf(state: VoiceState) = when (state) {
        is VoiceState.Waiting -> state.transcript
        is VoiceState.Speaking -> state.transcript
        is VoiceState.Approving -> state.transcript
        else -> ""
    }
}

object VoiceCommands {
    private val endPhrases = setOf(
        "그만", "그만해", "그만할게", "끝", "끝내", "끝내자", "종료", "대화종료", "됐어", "이제됐어",
        "고마워", "고마워요", "감사합니다", "수고했어", "수고했어요", "잘했어",
    )
    private val approvals = listOf("응", "어", "네", "예", "좋아", "그래", "진행", "허용", "승인", "해줘", "해도돼", "오케이", "ok", "yes")
    private val refusals = listOf("아니", "안돼", "안되", "하지마", "취소", "거부", "멈춰", "싫어", "잠깐", "no")
    private val ignored = Regex("[\\s.,!?~。]")

    fun isEndOfConversation(text: String): Boolean = normalize(text) in endPhrases

    /**
     * True only for a short, clearly positive answer. Anything unclear counts as "no": an
     * unintended approval of a risky action is far worse than asking again.
     */
    fun isApproval(text: String): Boolean {
        val answer = normalize(text).lowercase()
        if (answer.isEmpty() || answer.length > 12) return false
        if (refusals.any { it in answer }) return false
        return approvals.any { answer.startsWith(it) || answer.endsWith(it) }
    }

    private fun normalize(text: String) = text.replace(ignored, "")
}
