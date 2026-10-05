package com.architact.hermesvoice.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.architact.hermesvoice.relay.VoiceRelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Plays Microsoft Edge neural voices synthesized on the PC by the relay. The next piece is
 * fetched while the current one plays. If synthesis fails, this and every later piece in the
 * session fall back to the on-device [fallback] speaker so speech never stops.
 *
 * Note: Edge TTS is an online Microsoft service, so spoken text leaves the PC.
 */
class EdgeSpeaker(
    context: Context,
    private val relay: VoiceRelayClient,
    private val scope: CoroutineScope,
    private val fallback: Speaker,
) : Speaker {
    private class Item(val text: String, val onDone: (() -> Unit)?) {
        var audio: Deferred<ByteArray>? = null
    }

    private val cacheDir = context.applicationContext.cacheDir
    private val items = ArrayDeque<Item>()
    private var worker: Job? = null
    private var player: MediaPlayer? = null
    private var unavailable = false
    private var counter = 0

    var voice: String = VOICES.first().id
        set(value) { field = value; unavailable = false }

    override fun speak(text: String, onDone: () -> Unit) {
        stop()
        enqueue(text, onDone)
    }

    override fun enqueue(text: String, onDone: () -> Unit) {
        if (unavailable) return fallback.enqueue(text, onDone)
        val pieces = SpeechText.chunks(text, MAX_PIECE)
        if (pieces.isEmpty()) { onDone(); return }
        pieces.forEachIndexed { index, piece -> items.addLast(Item(piece, onDone.takeIf { index == pieces.lastIndex })) }
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (items.isNotEmpty() && scope.isActive) {
            val item = items.first()
            val audio = fetch(item)
            items.getOrNull(1)?.let { fetch(it) } // prefetch the next piece while this one plays
            val bytes = try {
                audio.await()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                switchToFallback()
                return
            }
            items.removeFirst()
            play(bytes)
            item.onDone?.invoke()
        }
    }

    private fun fetch(item: Item): Deferred<ByteArray> =
        item.audio ?: scope.async { relay.synthesize(item.text, voice) }.also { item.audio = it }

    private fun switchToFallback() {
        unavailable = true
        val remaining = items.toList()
        items.clear()
        remaining.forEach { item -> item.audio?.cancel(); fallback.enqueue(item.text, item.onDone ?: {}) }
    }

    private suspend fun play(bytes: ByteArray) {
        val file = File(cacheDir, "edge-tts-${++counter % 4}.mp3")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        val media = MediaPlayer()
        player = media
        try {
            suspendCancellableCoroutine { continuation ->
                media.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                media.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                media.setOnErrorListener { _, _, _ -> if (continuation.isActive) continuation.resume(Unit); true }
                continuation.invokeOnCancellation { runCatching { media.stop() } }
                try {
                    media.setDataSource(file.absolutePath)
                    media.prepare()
                    media.start()
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        } finally {
            media.release()
            if (player === media) player = null
        }
    }

    override fun stop() {
        worker?.cancel()
        worker = null
        items.forEach { it.audio?.cancel() }
        items.clear()
        player?.let { runCatching { it.stop() } }
        fallback.stop()
    }

    data class EdgeVoice(val id: String, val label: String)

    companion object {
        private const val MAX_PIECE = 900

        val VOICES = listOf(
            EdgeVoice("ko-KR-SunHiNeural", "선희 (여성, 차분함)"),
            EdgeVoice("ko-KR-InJoonNeural", "인준 (남성, 부드러움)"),
            EdgeVoice("ko-KR-HyunsuMultilingualNeural", "현수 (남성, 자연스러움)"),
        )
    }
}
