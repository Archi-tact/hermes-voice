package com.architact.hermesvoice.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Korean TTS with a calm default (slower, lower pitch than the device-wide setting) and a
 * user-selectable voice across every installed engine. Speech requested before the engine is
 * ready is queued; if no Korean voice exists the callback still runs so the flow never stalls.
 */
class AndroidSpeaker(context: Context) : Speaker {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private var ready: Boolean? = null
    private val pending = mutableListOf<Triple<String, () -> Unit, Boolean>>()
    private var afterInit: (() -> Unit)? = null
    private var counter = 0
    private var generation = 0

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { main.post { callbacks.remove(utteranceId)?.invoke() } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { callbacks.remove(utteranceId) } }
        override fun onError(utteranceId: String?, errorCode: Int) = onDone(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onDone(utteranceId)
    }

    private var engine: String? = prefs.getString(KEY_ENGINE, null) ?: GOOGLE_TTS
    private var tts: TextToSpeech = create(engine)

    private fun create(engineName: String?): TextToSpeech {
        ready = null
        val current = ++generation
        return TextToSpeech(app, { status -> main.post { if (current == generation) onInit(status) } }, engineName)
            .also { it.setOnUtteranceProgressListener(listener) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS && engine != null) {
            // Preferred engine missing: fall back to the system default engine.
            switchEngine(null)
            return
        }
        ready = status == TextToSpeech.SUCCESS && applyVoice(prefs.getString(KEY_VOICE, null))
        tts.setSpeechRate(SPEECH_RATE)
        tts.setPitch(PITCH)
        afterInit?.let { afterInit = null; it() }
        val queued = pending.toList()
        pending.clear()
        queued.forEach { (text, onDone, flush) -> say(text, onDone, flush) }
    }

    private fun koreanVoices(): List<Voice> = try {
        tts.voices.orEmpty()
            .filter { it.locale.language == "ko" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
    } catch (e: Exception) {
        emptyList()
    }

    private fun applyVoice(name: String?): Boolean {
        val voice = koreanVoices().let { voices -> voices.firstOrNull { it.name == name } ?: voices.firstOrNull() }
        return if (voice != null) tts.setVoice(voice) == TextToSpeech.SUCCESS
        else tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
    }

    private fun switchEngine(name: String?) {
        tts.shutdown()
        engine = name
        tts = create(name)
    }

    /** Moves to the next Korean voice (then the next engine) and reports e.g. "Google 목소리 2/4". */
    fun nextVoice(onChanged: (String) -> Unit) {
        if (ready != true) return
        val voices = koreanVoices()
        val index = voices.indexOfFirst { it.name == tts.voice?.name }
        val engines = tts.engines.map { it.name }
        val nextEngine = engines.getOrNull(engines.indexOf(tts.defaultEngine.takeIf { engine == null } ?: engine) + 1)
            ?: engines.firstOrNull()
        val choose: (Int) -> Unit = { position ->
            val list = koreanVoices()
            list.getOrNull(position)?.let { tts.setVoice(it) }
            tts.setSpeechRate(SPEECH_RATE)
            tts.setPitch(PITCH)
            prefs.edit().putString(KEY_ENGINE, engine).putString(KEY_VOICE, tts.voice?.name).apply()
            onChanged(label(list.size, position))
        }
        when {
            index + 1 < voices.size -> choose(index + 1)
            nextEngine != null && nextEngine != engine && engines.size > 1 -> {
                afterInit = { choose(0) }
                switchEngine(nextEngine)
            }
            else -> choose(0)
        }
    }

    private fun label(count: Int, position: Int): String {
        val engineLabel = tts.engines.firstOrNull { it.name == (engine ?: tts.defaultEngine) }?.label ?: "TTS"
        return if (count == 0) "$engineLabel 기본 목소리" else "$engineLabel 목소리 ${position + 1}/$count"
    }

    override fun speak(text: String, onDone: () -> Unit) = say(text, onDone, flush = true)

    override fun enqueue(text: String, onDone: () -> Unit) = say(text, onDone, flush = false)

    private fun say(text: String, onDone: () -> Unit, flush: Boolean) {
        when (ready) {
            null -> {
                if (flush) pending.clear()
                pending += Triple(text, onDone, flush)
                return
            }
            false -> { main.post(onDone); return }
            true -> Unit
        }
        if (flush) callbacks.clear()
        val pieces = SpeechText.chunks(text, TextToSpeech.getMaxSpeechInputLength() - 1)
        if (pieces.isEmpty()) { main.post(onDone); return }
        pieces.forEachIndexed { index, piece ->
            val id = "hermes-voice-${++counter}"
            if (index == pieces.lastIndex) callbacks[id] = onDone
            val mode = if (index == 0 && flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(piece, mode, null, id) == TextToSpeech.ERROR) {
                callbacks.remove(id)
                main.post(onDone)
                return
            }
        }
    }

    override fun stop() {
        pending.clear()
        callbacks.clear()
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        const val KEY_ENGINE = "engine"
        const val KEY_VOICE = "voice"

        // The device default on this Fold7 is 2.31x rate / 1.55 pitch, which sounds sharp; override it.
        const val SPEECH_RATE = 0.9f
        const val PITCH = 0.9f
    }
}
