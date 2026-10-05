package com.architact.hermesvoice.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.architact.hermesvoice.session.ErrorKind

/**
 * Speech input that does not cut the user off at the first short pause: after each recognizer
 * segment it keeps listening and only finishes once the user has been quiet for [patience]
 * (or taps "다 말했어요"). Short answers such as approvals finish on the first segment.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("listening", Context.MODE_PRIVATE)
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME) }.getOrNull() }
    private var recognizer: SpeechRecognizer? = null
    private var pendingStart: Runnable? = null
    private var finalizer: Runnable? = null
    private var session: Session? = null

    /** Microphone level 0..1 while listening, for the orb. Called on the main thread. */
    var onLevel: (Float) -> Unit = {}

    /** Live transcript while listening (finished segments plus the words being spoken); "" when idle. */
    var onPartial: (String) -> Unit = {}

    var patience: ListeningPatience = ListeningPatience.entries.firstOrNull { it.name == prefs.getString(KEY_PATIENCE, null) } ?: ListeningPatience.Normal
        set(value) {
            field = value
            prefs.edit().putString(KEY_PATIENCE, value.name).apply()
        }

    private class Session(
        val patient: Boolean,
        val onResult: (String) -> Unit,
        val onError: (ErrorKind) -> Unit,
    ) {
        val utterance = Utterance()
        val startedAt = SystemClock.elapsedRealtime()
        var speaking = false
        var finishing = false
    }

    override fun start(cue: Boolean, patient: Boolean, onResult: (String) -> Unit, onError: (ErrorKind) -> Unit) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            main.post { onError(ErrorKind.Recognizer) }
            return
        }
        val current = Session(patient, onResult, onError)
        session = current
        if (!cue) { listen(current, attempt = 0); return }
        // A soft tone tells the user the microphone is open again; wait for it so it is not transcribed.
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, CUE_MS)
        later(CUE_MS + 150L) { listen(current, attempt = 0) }
    }

    /** "다 말했어요": finish now with what was heard, including the words being spoken. */
    fun finishNow() {
        val current = session ?: return
        if (current.speaking || current.utterance.partial.isNotEmpty()) {
            current.finishing = true
            recognizer?.stopListening() // the final words arrive in onResults, which then delivers
        } else {
            deliver(current)
        }
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        pendingStart?.let { main.removeCallbacks(it) }
        pendingStart = Runnable { pendingStart = null; block() }.also { main.postDelayed(it, delayMs) }
    }

    private fun listen(current: Session, attempt: Int) {
        if (session !== current) return
        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        current.speaking = false
        speech.setRecognitionListener(listener(current, speech, attempt))
        speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints only; most recognizers ignore them, which is why segments are stitched below.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, patience.millis)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, patience.millis)
        })
    }

    private fun listener(current: Session, speech: SpeechRecognizer, attempt: Int) = object : RecognitionListener {
        private fun active() = session === current && recognizer === speech

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onRmsChanged(rmsdB: Float) { if (active()) onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }

        override fun onBeginningOfSpeech() {
            if (!active()) return
            current.speaking = true
            cancelFinalizer() // the user started talking again within the pause
        }

        override fun onEndOfSpeech() {
            if (active()) current.speaking = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active()) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) return
            current.utterance.partial = text
            cancelFinalizer()
            onPartial(current.utterance.text())
        }

        override fun onResults(results: Bundle?) {
            if (!active()) return
            current.utterance.addSegment(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            onPartial(current.utterance.text())
            val tooLong = SystemClock.elapsedRealtime() - current.startedAt > MAX_UTTERANCE_MS
            if (!current.patient || current.finishing || tooLong || !current.utterance.hasFinalText) {
                deliver(current)
                return
            }
            // Keep listening: if the user stays quiet for the patience window, the request is done.
            scheduleFinalizer(current)
            main.post { listen(current, attempt = 0) }
        }

        override fun onError(error: Int) {
            if (!active()) return
            // Right after Bixby or the side button the recognizer may still be held; wait and retry.
            if (error in BUSY_ERRORS && attempt < MAX_BUSY_RETRIES) {
                speech.destroy()
                recognizer = null
                later(BUSY_RETRY_MS) { listen(current, attempt + 1) }
                return
            }
            // Silence or noise after earlier segments simply means the user finished.
            if (current.utterance.partial.isNotEmpty()) current.utterance.addSegment(current.utterance.partial)
            if (current.utterance.hasFinalText) deliver(current) else fail(current, errorKind(error))
        }
    }

    private fun scheduleFinalizer(current: Session) {
        cancelFinalizer()
        finalizer = Runnable {
            finalizer = null
            if (session === current && !current.speaking && current.utterance.partial.isEmpty()) deliver(current)
        }.also { main.postDelayed(it, patience.millis) }
    }

    private fun cancelFinalizer() {
        finalizer?.let { main.removeCallbacks(it) }
        finalizer = null
    }

    private fun deliver(current: Session) {
        if (session !== current) return
        val text = current.utterance.text()
        stop()
        current.onResult(text)
    }

    private fun fail(current: Session, kind: ErrorKind) {
        if (session !== current) return
        stop()
        current.onError(kind)
    }

    override fun stop() {
        session = null
        pendingStart?.let { main.removeCallbacks(it) }
        pendingStart = null
        cancelFinalizer()
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
        onLevel(0f)
        onPartial("")
    }

    fun release() {
        stop()
        tone?.release()
    }

    companion object {
        private const val KEY_PATIENCE = "patience"
        private const val CUE_MS = 120
        private const val CUE_VOLUME = 35
        private const val MAX_BUSY_RETRIES = 3
        private const val BUSY_RETRY_MS = 700L
        private const val MAX_UTTERANCE_MS = 90_000L
        private val BUSY_ERRORS = setOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT)

        fun errorKind(error: Int): ErrorKind = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorKind.NoSpeech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_AUDIO -> ErrorKind.Microphone
            else -> ErrorKind.Recognizer
        }
    }
}
