package com.architact.hermesvoice.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architact.hermesvoice.BuildConfig
import com.architact.hermesvoice.relay.HttpVoiceRelayClient
import com.architact.hermesvoice.security.PairingTokenStore
import com.architact.hermesvoice.speech.AndroidSpeaker
import com.architact.hermesvoice.speech.AndroidSpeechInput
import com.architact.hermesvoice.speech.EdgeSpeaker
import com.architact.hermesvoice.speech.VoiceOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Owns speech, TTS and the in-flight request so they survive fold/unfold configuration changes. */
class VoiceSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val input = AndroidSpeechInput(application)
    private val tokens = PairingTokenStore(application)
    private val relay = HttpVoiceRelayClient(BuildConfig.RELAY_URLS.split(',').map { it.trim() }.filter { it.isNotEmpty() }, tokens::read)
    private val deviceVoice = AndroidSpeaker(application)
    private val voice = VoiceOutput(application, deviceVoice, EdgeSpeaker(application, relay, viewModelScope, deviceVoice))

    val controller = VoiceSessionController(speaker = voice, input = input, relay = relay, scope = viewModelScope)

    val edgeVoiceEnabled get() = voice.edgeEnabled

    private val mutableMicLevel = MutableStateFlow(0f)

    /** Microphone level 0..1 while listening; drives the orb. */
    val micLevel: StateFlow<Float> = mutableMicLevel.asStateFlow()

    private val mutableLiveTranscript = MutableStateFlow("")

    /** What the user is saying right now (live captions while listening). */
    val liveTranscript: StateFlow<String> = mutableLiveTranscript.asStateFlow()

    /** How long a pause may last before the question is sent. */
    val listeningPatience get() = input.patience

    fun cycleListeningPatience() = input.patience.next().also { input.patience = it }

    /** "다 말했어요": send what was said without waiting for the pause. */
    fun finishSpeaking() = input.finishNow()

    init {
        input.onLevel = { mutableMicLevel.value = it }
        input.onPartial = { mutableLiveTranscript.value = it }
        // A foreground notification keeps long tasks (and the reply being read) alive with the screen off.
        viewModelScope.launch {
            controller.state
                .map { state ->
                    when (state) {
                        is VoiceState.Waiting -> state.progress?.trimEnd('.') ?: "작업 중입니다"
                        is VoiceState.Speaking -> "답변을 읽고 있습니다"
                        is VoiceState.Approving -> "확인이 필요합니다: 앱을 열어 주세요"
                        else -> null
                    }
                }
                .distinctUntilChanged()
                .collect { text ->
                    if (text != null) VoiceTaskService.update(application, text) else VoiceTaskService.stop(application)
                }
        }
    }

    fun changeVoice(onChanged: (String) -> Unit) = voice.nextVoice { label -> onChanged(label); voice.speak(VoiceMessages.VOICE_SAMPLE) {} }

    fun toggleEdgeVoice(onChanged: (String) -> Unit) = voice.toggleEdge { label -> onChanged(label); voice.speak(VoiceMessages.VOICE_SAMPLE) {} }

    override fun onCleared() {
        VoiceTaskService.stop(getApplication())
        input.release()
        voice.shutdown()
    }
}
