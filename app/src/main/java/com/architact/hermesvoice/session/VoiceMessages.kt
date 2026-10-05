package com.architact.hermesvoice.session

object VoiceMessages {
    const val PROMPT = "네, 무엇을 도와드릴까요?"
    const val SENT = "네, 확인해 볼게요."
    const val STILL_WORKING = "아직 작업 중이에요. 조금만 기다려 주세요."
    const val FAREWELL = "네, 필요하시면 언제든 다시 불러 주세요."
    const val VOICE_SAMPLE = "안녕하세요. 이 목소리는 어떠세요?"
    const val APPROVED = "네, 진행할게요."
    const val DENIED = "알겠어요. 진행하지 않을게요."

    fun approvalQuestion(description: String): String {
        val what = description.trim().trimEnd('.', '?')
        return if (what.isEmpty()) "이 작업을 진행하려면 허락이 필요해요. 진행할까요?"
        else "진행하기 전에 확인할게요. $what. 진행할까요? 네 또는 아니요로 답해 주세요."
    }

    fun forError(kind: ErrorKind): String = when (kind) {
        ErrorKind.NoSpeech -> "잘 듣지 못했어요. 다시 말씀해 주세요."
        ErrorKind.Microphone -> "마이크를 사용할 수 없습니다. 권한을 확인해 주세요."
        ErrorKind.Recognizer -> "음성 인식을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
        ErrorKind.Unpaired -> "PC와 페어링이 필요합니다."
        ErrorKind.Unauthorized -> "페어링 정보가 맞지 않습니다. 다시 페어링해 주세요."
        ErrorKind.BadRequest -> "요청을 처리할 수 없습니다. 더 짧게 말씀해 주세요."
        ErrorKind.Cancelled -> "작업을 멈췄습니다."
        ErrorKind.NoRelay -> "PC에 연결할 수 없습니다. USB나 Tailscale 연결을 확인해 주세요."
        ErrorKind.AgentUnavailable -> "에이전트와 연결이 끊겼어요. 다시 보내기를 누르면 이어서 받아 올게요."
        ErrorKind.Timeout -> "응답이 너무 늦어지고 있어요. 다시 보내기를 눌러 주세요."
    }

    fun status(state: VoiceState): String = when (state) {
        VoiceState.Idle -> "편하게 말씀하세요"
        VoiceState.Prompting -> "준비 중입니다"
        is VoiceState.Listening -> if (state.followUp) "이어서 말씀하세요" else "듣고 있습니다"
        is VoiceState.Waiting -> state.progress?.trimEnd('.') ?: "작업 중입니다"
        is VoiceState.Speaking -> "답변을 읽어드립니다"
        is VoiceState.Approving -> if (state.listening) "진행할까요? (네 / 아니요)" else "확인이 필요합니다"
        is VoiceState.Ended -> if (state.farewell) "대화를 마쳤습니다" else "대화를 잠시 멈췄어요"
        is VoiceState.Error -> forError(state.kind)
    }
}
