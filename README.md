# 헤르메스 · Hermes Voice

휴대폰에서 측면 버튼(또는 빅스비)으로 불러, PC에서 돌아가는 [Hermes Agent](https://github.com/NousResearch/hermes-agent)와
음성으로 대화하고 작업을 맡기는 Android 앱과 작은 중계 서버입니다. Galaxy Z Fold7에서 만들고 썼지만 Android 8.0(API 26)
이상이면 동작합니다.

> A voice front-end for a self-hosted Hermes Agent: press and hold the side button, talk, and hear the
> answer — including follow-up questions, long-running PC tasks, and spoken approvals for risky actions.

```
휴대폰 앱 ─┬─ USB: adb reverse → 127.0.0.1:8765 ──────────────┐
           └─ 무선: Tailscale → <your-pc>.<tailnet>.ts.net:8765 ─┤ (tailscale serve, tailnet 전용)
                                                  relay/hermes_voice_relay.py   ← Bearer 페어링 토큰
                                                  └─ Hermes API Server :8642   ← SSE, 세션, 승인
```

## 특징

- **바로 부르기**: 기본 디지털 어시스턴트로 지정하면 측면 버튼 길게 누르기 → 신호음 → 바로 듣기.
- **이어지는 대화**: 같은 대화는 같은 Hermes 세션(`X-Hermes-Session-Id`)이라 앞 내용을 기억합니다.
- **스트리밍 답변**: 문장 단위로 바로 읽고, 작업 중에는 "검색하고 있어요" 같은 진행 상황을 말합니다.
- **음성 승인**: Hermes가 위험한 작업 전에 허락을 구하면 소리로 묻고, 짧고 분명한 "네"만 승인합니다.
- **끊겨도 안전**: 작업은 중계 서버가 붙잡고 있어 휴대폰 연결이 끊겨도 계속되고, 다시 붙으면 결과를 받아 옵니다(중복 실행 없음).
- **보안**: 앱에는 Hermes 키가 없습니다. 페어링 토큰은 Android Keystore로 암호화되고, 중계 서버는 loopback/Tailscale에만 열리며
  질문·답변 내용을 로그에 남기지 않습니다.
- **목소리**: 휴대폰 TTS(차분한 속도·음높이) 또는 PC에서 만든 Edge 신경망 음성(선택, 온라인 서비스 사용).

## 준비물

- Hermes Agent와 API Server(`API_SERVER_KEY` 설정, 기본 `127.0.0.1:8642`)
- JDK 17, Android SDK(API 35), Python 3.10+ (`edge-tts`는 선택)
- 무선으로 쓰려면 PC와 휴대폰에 Tailscale

## 설치

```bash
# 1) 앱 빌드·설치 (휴대폰 USB 디버깅 연결)
./gradlew :app:installDebug

# 2) 페어링: 토큰을 만들어(~/.config/hermes-voice/relay_token, 권한 600) 휴대폰 앱에 전달
relay/pair_device.sh

# 3) 중계 서버 실행 (tmux 감시 스크립트가 재시작과 adb reverse를 관리)
relay/start_relay.sh
```

- 중계 서버는 `~/.hermes/.env`에서 `API_SERVER_KEY`를 읽습니다. 다른 위치라면 환경 변수로 넘기세요.
- WSL에서는 `relay/find_adb.sh`가 Windows SDK의 `adb.exe`를 자동으로 찾습니다(휴대폰은 Windows adb 서버에 붙기 때문). `ADB=`로 바꿀 수 있습니다.
- Windows 로그인 때 자동 시작: `relay/windows/start-hermes-voice-relay.vbs`를 `shell:startup`에 복사하세요(저장소 경로가 다르면 파일 안 경로를 고치세요).

### 무선(Tailscale)

```bash
tailscale serve --bg --http 8765 http://127.0.0.1:8765   # tailnet 안에서만 열립니다 (Funnel 아님)
```

앱이 시도할 주소는 `~/.gradle/gradle.properties`에 넣습니다(저장소의 `gradle.properties`보다 우선하고 git에 올라가지 않습니다).

```properties
hermesRelayUrls=http://127.0.0.1:8765,http://<your-pc>.<your-tailnet>.ts.net:8765
```

평문 HTTP는 debug 빌드에서만 허용됩니다. Tailscale 구간은 WireGuard로 암호화됩니다.

### 측면 버튼 (Samsung)

1. 설정 → 애플리케이션 → 기본 앱 선택 → **디지털 어시스턴트 앱** → **헤르메스**
2. 설정 → 유용한 기능 → **측면 버튼** → 길게 누르기 → **디지털 어시스턴트 호출**

이어폰 음성 명령도 같은 방식으로 열립니다. 잠금 상태에서는 잠금을 풀어야 열립니다(의도된 동작).
`VoiceInteractionService` 대신 `ACTION_ASSIST` 액티비티를 쓰는 이유: 전자는 시스템 음성 인식기를 바꿔 이 앱의 음성 인식이 깨질 수 있습니다.

## 사용법

- 답변을 다 읽으면 신호음 뒤 다시 듣습니다. 말이 없으면 잠시 멈추고 **이어서 말하기**로 계속합니다.
- **끊고 말하기**(또는 측면 버튼)로 답변 도중 바로 말할 수 있습니다. "그만", "고마워", "대화 종료"로 끝냅니다.
- **작업 멈추기**는 PC의 작업도 실제로 중단합니다. 연결이 끊기면 **다시 보내기**로 같은 작업에 다시 붙습니다.
- 화면이 꺼져도 작업과 답변 읽기는 알림을 띄운 채 계속됩니다. 마이크는 화면을 켜야 다시 열립니다.
- **PC 고품질 음성**은 답변 문장을 Microsoft Edge 온라인 TTS로 보냅니다. 실패하면 휴대폰 음성으로 바뀝니다.

## 개발

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
python3 -m unittest discover relay
```

| 경로 | 역할 |
|---|---|
| `session/VoiceSessionReducer.kt` | 순수 상태 머신: Prompting → Listening → Waiting → Speaking(스트리밍) / Approving → … |
| `session/VoiceSessionController.kt` | 상태별 부수효과: TTS 큐, 음성 인식, 중계 스트림, 승인, 진행 안내, 대화 기록 |
| `session/VoiceSessionViewModel.kt`, `VoiceTaskService.kt` | 접기/펴기 유지, 작업 중 포그라운드 알림 |
| `relay/HttpVoiceRelayClient.kt` | NDJSON 스트림, 여러 중계 주소 대체 접속, 승인·취소·TTS 요청 |
| `speech/` | 휴대폰 TTS(엔진·음성 선택), Edge 음성 재생, 음성 인식, 마크다운 정리 |
| `ui/` | 상태를 보여 주는 음성 구체(OrbView), 버튼·레이아웃 |
| `AssistActivity.kt` | 기본 디지털 어시스턴트 진입점(측면 버튼) |
| `relay/hermes_voice_relay.py` | 인증, Hermes SSE → 문장 단위 이벤트, 작업 유지·재연결·취소, 승인 전달, Edge TTS |
| `design/icon.svg` | 앱 아이콘 원본 |

## 중계 서버 API

모든 요청은 `Authorization: Bearer <페어링 토큰>`이 필요합니다.

| 요청 | 응답 |
|---|---|
| `POST /v1/voice-requests` `{requestId, conversationId?, text}` | NDJSON: `progress` · `text` · `approval` · `done` · `error` · `ping` |
| `POST /v1/voice-requests/<id>/cancel` | 해당 작업 중단 |
| `POST /v1/voice-approvals` `{requestId, approvalId, approve}` | 그 작업이 요청한 승인에만 응답 |
| `POST /v1/tts` `{text, voice}` | `audio/mpeg` (ko-KR SunHi / InJoon / Hyunsu) |

## 라이선스

[MIT](LICENSE) © 2026 Archi-tact

- UI 아이콘(`res/drawable/ic_mic.xml` 등)은 [Material Icons](https://github.com/google/material-design-icons)의 경로이며 Apache License 2.0을 따릅니다.
- Hermes Agent, Edge TTS(`edge-tts`), Tailscale은 각자의 라이선스와 이용 약관을 따릅니다.
