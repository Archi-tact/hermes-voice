package com.architact.hermesvoice.speech

import android.content.Context

/**
 * Chooses between on-device TTS and the PC's Edge neural voices, remembering the choice.
 * The session controller only ever sees this one [Speaker].
 */
class VoiceOutput(context: Context, private val device: AndroidSpeaker, private val edge: EdgeSpeaker) : Speaker {
    private val prefs = context.applicationContext.getSharedPreferences("voice", Context.MODE_PRIVATE)

    var edgeEnabled: Boolean = prefs.getBoolean(KEY_EDGE, false)
        private set

    init {
        prefs.getString(KEY_EDGE_VOICE, null)
            ?.takeIf { id -> EdgeSpeaker.VOICES.any { it.id == id } }
            ?.let { edge.voice = it }
    }

    private val active: Speaker get() = if (edgeEnabled) edge else device

    override fun speak(text: String, onDone: () -> Unit) = active.speak(text, onDone)
    override fun enqueue(text: String, onDone: () -> Unit) = active.enqueue(text, onDone)

    override fun stop() {
        edge.stop()
        device.stop()
    }

    /** Toggles PC voices and returns a label describing the voice now in use. */
    fun toggleEdge(onChanged: (String) -> Unit) {
        stop()
        edgeEnabled = !edgeEnabled
        prefs.edit().putBoolean(KEY_EDGE, edgeEnabled).apply()
        if (edgeEnabled) onChanged("PC 고품질 음성: ${edgeLabel()}") else onChanged("휴대폰 음성으로 전환했어요")
    }

    /** Next voice within the current mode. */
    fun nextVoice(onChanged: (String) -> Unit) {
        stop()
        if (!edgeEnabled) return device.nextVoice(onChanged)
        val voices = EdgeSpeaker.VOICES
        val next = voices[(voices.indexOfFirst { it.id == edge.voice } + 1) % voices.size]
        edge.voice = next.id
        prefs.edit().putString(KEY_EDGE_VOICE, next.id).apply()
        onChanged("PC 고품질 음성: ${next.label}")
    }

    private fun edgeLabel() = EdgeSpeaker.VOICES.firstOrNull { it.id == edge.voice }?.label ?: edge.voice

    fun shutdown() {
        stop()
        device.shutdown()
    }

    private companion object {
        const val KEY_EDGE = "edge_enabled"
        const val KEY_EDGE_VOICE = "edge_voice"
    }
}
