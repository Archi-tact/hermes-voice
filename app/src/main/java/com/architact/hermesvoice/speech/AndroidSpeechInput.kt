package com.architact.hermesvoice.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.architact.hermesvoice.session.ErrorKind

class AndroidSpeechInput(private val context: Context) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** Microphone level 0..1 while listening, for the orb. Called on the main thread. */
    var onLevel: (Float) -> Unit = {}
    private var pendingStart: Runnable? = null
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME) }.getOrNull() }

    override fun start(cue: Boolean, onResult: (String) -> Unit, onError: (ErrorKind) -> Unit) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            main.post { onError(ErrorKind.Recognizer) }
            return
        }
        if (!cue) { listen(onResult, onError, attempt = 0); return }
        // A soft tone tells the user the microphone is open again; wait for it so it is not transcribed.
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, CUE_MS)
        later(CUE_MS + 150L) { listen(onResult, onError, attempt = 0) }
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        pendingStart = Runnable { pendingStart = null; block() }.also { main.postDelayed(it, delayMs) }
    }

    private fun listen(onResult: (String) -> Unit, onError: (ErrorKind) -> Unit, attempt: Int) {
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                if (recognizer !== speech) return
                // Right after Bixby launches the app it may still hold the recognizer; wait and try again.
                if (error in BUSY_ERRORS && attempt < MAX_BUSY_RETRIES) {
                    speech.destroy()
                    recognizer = null
                    later(BUSY_RETRY_MS) { listen(onResult, onError, attempt + 1) }
                    return
                }
                onError(errorKind(error))
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== speech) return
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
        })
        speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Task instructions are longer than quick questions; allow natural pauses (honored by some engines).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        })
    }

    override fun stop() {
        pendingStart?.let { main.removeCallbacks(it) }
        pendingStart = null
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
        onLevel(0f)
    }

    fun release() {
        stop()
        tone?.release()
    }

    companion object {
        private const val CUE_MS = 120
        private const val CUE_VOLUME = 35
        private const val MAX_BUSY_RETRIES = 3
        private const val BUSY_RETRY_MS = 700L
        private val BUSY_ERRORS = setOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT)

        fun errorKind(error: Int): ErrorKind = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorKind.NoSpeech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_AUDIO -> ErrorKind.Microphone
            else -> ErrorKind.Recognizer
        }
    }
}
