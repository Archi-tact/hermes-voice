package com.architact.hermesvoice.relay

import com.architact.hermesvoice.session.ErrorKind
import kotlinx.coroutines.flow.Flow

sealed interface RelayEvent {
    /** Spoken progress while Hermes works ("검색하고 있어요."), with the raw tool label for the screen. */
    data class Progress(val text: String, val detail: String = "") : RelayEvent

    /** A speakable piece of the reply (usually one or more sentences). */
    data class Text(val text: String) : RelayEvent

    /** Hermes wants explicit permission before a risky action. */
    data class Approval(val approvalId: String, val description: String, val command: String = "") : RelayEvent

    data class Done(val reply: String) : RelayEvent
}

class RelayException(val kind: ErrorKind, cause: Throwable? = null) : Exception(kind.name, cause)

interface VoiceRelayClient {
    /**
     * Streams one agent turn within the Hermes session [conversationId]. Never resends on its own;
     * calling again with the same [requestId] re-attaches to the same run on the relay instead of
     * starting the task twice. Errors are thrown as [RelayException].
     */
    fun stream(requestId: String, conversationId: String, text: String): Flow<RelayEvent>

    suspend fun answerApproval(requestId: String, approvalId: String, approve: Boolean)

    /** Stops the agent run for [requestId]; best effort. */
    suspend fun cancel(requestId: String)

    /** MP3 audio for [text] spoken by an Edge neural [voice], synthesized on the PC. */
    suspend fun synthesize(text: String, voice: String): ByteArray
}
