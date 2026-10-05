package com.architact.hermesvoice.speech

import com.architact.hermesvoice.session.ErrorKind

interface Speaker {
    /** Speaks [text], replacing anything in progress. [onDone] runs on the main thread only if it finishes. */
    fun speak(text: String, onDone: () -> Unit)

    /** Speaks [text] after whatever is already queued. [onDone] runs on the main thread only if it finishes. */
    fun enqueue(text: String, onDone: () -> Unit)

    fun stop()
}

interface SpeechInput {
    /**
     * Listens for one utterance, playing a short [cue] tone first when true. Exactly one callback
     * runs, on the main thread, unless [stop] is called first.
     */
    fun start(cue: Boolean, onResult: (String) -> Unit, onError: (ErrorKind) -> Unit)
    fun stop()
}
