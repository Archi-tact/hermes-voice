#!/usr/bin/env python3
"""Hermes Voice relay: Fold7 app -> Hermes API Server.

Every request must carry the paired device token. The relay binds only to loopback (reached
over USB `adb reverse`, or over the tailnet through `tailscale serve`) or a Tailscale address,
and logs request id, status, error class and duration only -- never transcripts, replies or
credentials.

Endpoints (all POST, all require `Authorization: Bearer <pairing token>`):
  /v1/voice-requests              {requestId, conversationId?, text} -> NDJSON event stream
  /v1/voice-requests/<id>/cancel  stop the agent run for that request
  /v1/voice-approvals             {requestId, approvalId, approve} -> answer a Hermes approval
  /v1/tts                         {text, voice} -> audio/mpeg (Microsoft Edge neural voices)

The agent run belongs to the relay, not to the HTTP connection: if the phone drops off, the
run continues, and resending the same requestId re-attaches and replays its events.

Environment:
  API_SERVER_KEY      Hermes API Server key (required)
  HERMES_API_URL      default http://127.0.0.1:8642 (base URL)
  RELAY_TOKEN_FILE    default ~/.config/hermes-voice/relay_token (created by pair_device.sh)
  RELAY_HOST          default 127.0.0.1; must be loopback or Tailscale 100.64.0.0/10
  RELAY_PORT          default 8765
"""
import asyncio
import hmac
import ipaddress
import json
import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MAX_BODY = 8192
MAX_TEXT = 2000
MAX_TTS_TEXT = 1000
AGENT_TIMEOUT = 900        # whole agent run, including tool work and approvals
UPSTREAM_IDLE_TIMEOUT = 90  # Hermes sends SSE keepalives every 10 s
PING_INTERVAL = 10          # NDJSON keepalive to the phone
JOB_TTL = 15 * 60           # finished runs stay attachable this long
ID_PATTERN = re.compile(r"^[A-Za-z0-9-]{8,64}$")
RUN_ID_PATTERN = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
CANCEL_PATH = re.compile(r"^/v1/voice-requests/([A-Za-z0-9-]{8,64})/cancel$")
TAILSCALE = ipaddress.ip_network("100.64.0.0/10")
SESSION_PREFIX = "hermes-voice-"
TTS_VOICES = {"ko-KR-SunHiNeural", "ko-KR-InJoonNeural", "ko-KR-HyunsuMultilingualNeural"}
SYSTEM_PROMPT = (
    "지금 사용자는 휴대폰으로 당신과 음성 대화를 하고 있고, 당신의 답변은 TTS로 소리 내어 읽힙니다. "
    "여유 있고 다정한 구어체 한국어로, 질문에 필요한 만큼 충분히 설명하세요. "
    "보통 서너 문장에서 여섯 문장 정도가 적당하고, 사용자가 자세히 원하면 더 길게 답해도 됩니다. "
    "작업을 요청받으면 실제로 수행한 뒤, 무엇을 했고 결과가 어떤지 말로 보고하세요. "
    "파일 삭제, 결제, 외부 전송처럼 되돌리기 어렵거나 위험한 작업은 실행하기 전에 먼저 확인을 받으세요. "
    "이 대화는 이어지므로 앞선 내용을 기억해서 자연스럽게 이어 가고, 필요하면 되물어도 됩니다. "
    "마크다운, 표, 코드 블록, URL은 쓰지 말고 말로 풀어서 설명하세요."
)

# Spoken progress for tool activity; matched against the tool name.
TOOL_PROGRESS = [
    (("search", "web", "google", "lookup"), "검색하고 있어요."),
    (("browser", "navigate", "page", "fetch", "url"), "웹 페이지를 살펴보고 있어요."),
    (("write", "edit", "patch", "create", "save"), "파일을 작성하고 있어요."),
    (("read", "file", "grep", "glob", "list", "find"), "파일을 살펴보고 있어요."),
    (("terminal", "shell", "bash", "exec", "command", "run"), "명령을 실행하고 있어요."),
    (("calendar", "mail", "gmail", "slack", "message", "send"), "연결된 서비스를 확인하고 있어요."),
    (("delegate", "agent", "task"), "보조 에이전트에게 작업을 맡겼어요."),
    (("memory", "remember"), "기억을 정리하고 있어요."),
]


def progress_phrase(tool):
    name = (tool or "").lower()
    for keys, phrase in TOOL_PROGRESS:
        if any(key in name for key in keys):
            return phrase
    return "작업을 진행하고 있어요."


class AgentTimeout(Exception):
    pass


class AgentUnavailable(Exception):
    pass


class SentenceSplitter:
    """Cuts streamed text into speakable pieces at sentence ends, never inside a code fence."""

    END = re.compile(r"(?<=[.!?。…])\s+|\n+")

    def __init__(self, min_length=12):
        self.buffer = ""
        self.min_length = min_length

    def feed(self, delta):
        self.buffer += delta
        pieces = []
        while True:
            cut = None
            for match in self.END.finditer(self.buffer):
                # An odd number of fences before the boundary means it sits inside a code block.
                if match.start() >= self.min_length and self.buffer[:match.start()].count("```") % 2 == 0:
                    cut = match.end()
                    break
            if cut is None:
                return pieces
            pieces.append(self.buffer[:cut])
            self.buffer = self.buffer[cut:]

    def flush(self):
        rest, self.buffer = self.buffer, ""
        return [rest] if rest.strip() else []


class Job:
    """One agent run. Events are kept so a reconnecting phone can replay them."""

    def __init__(self, request_id):
        self.request_id = request_id
        self.events = []
        self.finished_at = None
        self.failed = False
        self.cancelled = False
        self.approval_ids = set()
        self._cancel_hooks = []
        self._cond = threading.Condition()

    @property
    def finished(self):
        return self.finished_at is not None

    def emit(self, event):
        with self._cond:
            if self.finished:
                return
            if event.get("type") == "approval":
                self.approval_ids.add(event["approvalId"])
            self.events.append(event)
            if event.get("type") in ("done", "error"):
                self.failed = event["type"] == "error"
                self.finished_at = time.monotonic()
            self._cond.notify_all()

    def on_cancel(self, hook):
        self._cancel_hooks.append(hook)

    def cancel(self):
        self.cancelled = True
        for hook in list(self._cancel_hooks):
            try:
                hook()
            except Exception:
                pass
        self.emit({"type": "error", "error": "cancelled"})

    def stream(self, ping_interval=PING_INTERVAL):
        """Yields every event from the start, then live ones; pings while idle."""
        index = 0
        while True:
            with self._cond:
                if index >= len(self.events) and not self.finished:
                    self._cond.wait(timeout=ping_interval)
                fresh = self.events[index:]
                index = len(self.events)
                finished = self.finished
            if fresh:
                yield from fresh
            elif not finished:
                yield {"type": "ping"}
            if finished and index >= len(self.events):
                return


class Jobs:
    def __init__(self):
        self._jobs = {}
        self._lock = threading.Lock()

    def get(self, request_id):
        with self._lock:
            return self._jobs.get(request_id)

    def attach_or_start(self, request_id, start):
        """Returns the live (or recently finished) job for request_id, starting one if needed."""
        with self._lock:
            now = time.monotonic()
            for key, job in list(self._jobs.items()):
                if job.finished and now - job.finished_at > JOB_TTL:
                    del self._jobs[key]
            job = self._jobs.get(request_id)
            if job is not None and not job.failed:
                return job, False
            job = Job(request_id)
            self._jobs[request_id] = job
        start(job)
        return job, True


class HermesBridge:
    """Talks to the Hermes API Server. Hermes keeps the conversation: `X-Hermes-Session-Id`
    continues the stored session for every turn of one phone conversation."""

    def __init__(self, base_url, api_key):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key

    def _headers(self, **extra):
        return {"Authorization": "Bearer " + self.api_key, "Content-Type": "application/json", **extra}

    def run(self, job, text, conversation_id=None):
        """Streams one agent turn into job events; runs on its own thread."""
        payload = json.dumps({
            "model": "hermes-agent",
            "stream": True,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": text},
            ],
        }, ensure_ascii=False).encode("utf-8")
        headers = self._headers(Accept="text/event-stream")
        if conversation_id:
            headers["X-Hermes-Session-Id"] = SESSION_PREFIX + conversation_id
        request = Request(self.base_url + "/v1/chat/completions", data=payload, headers=headers)
        deadline = time.monotonic() + AGENT_TIMEOUT
        try:
            with urlopen(request, timeout=UPSTREAM_IDLE_TIMEOUT) as response:
                job.on_cancel(lambda: _abort(response))
                self._consume(job, response, deadline)
        except AgentTimeout:
            job.emit({"type": "error", "error": "agent_timeout"})
        except (TimeoutError, socket.timeout):
            job.emit({"type": "error", "error": "agent_timeout"})
        except URLError as e:
            reason = getattr(e, "reason", None)
            error = "agent_timeout" if isinstance(reason, (TimeoutError, socket.timeout)) else "agent_unavailable"
            job.emit({"type": "error", "error": error})
        except Exception:
            job.emit({"type": "error", "error": "agent_unavailable"})

    def _consume(self, job, response, deadline):
        splitter = SentenceSplitter()
        reply = []
        last_progress = None
        failed = False
        event_name, data_lines = None, []
        for raw in response:
            if job.cancelled:
                return
            if time.monotonic() > deadline:
                _abort(response)
                raise AgentTimeout()
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            if line.startswith(":"):
                continue
            if line.startswith("event:"):
                event_name = line[6:].strip()
                continue
            if line.startswith("data:"):
                data_lines.append(line[5:].lstrip())
                continue
            if line or not data_lines:
                continue
            data, name = "\n".join(data_lines), event_name
            event_name, data_lines = None, []
            if data == "[DONE]":
                break
            try:
                body = json.loads(data)
            except ValueError:
                continue
            if name == "hermes.tool.progress":
                if body.get("status") == "running":
                    phrase = progress_phrase(body.get("tool"))
                    if phrase != last_progress:
                        last_progress = phrase
                        job.emit({"type": "progress", "text": phrase, "detail": str(body.get("label") or "")[:200]})
            elif name == "approval.request":
                run_id = str(body.get("run_id") or "")
                if RUN_ID_PATTERN.match(run_id):
                    job.emit({
                        "type": "approval", "approvalId": run_id,
                        "description": str(body.get("description") or "")[:500],
                        "command": str(body.get("command") or "")[:500],
                    })
            elif name is None:
                choice = (body.get("choices") or [{}])[0]
                content = (choice.get("delta") or {}).get("content")
                if content:
                    reply.append(content)
                    for piece in splitter.feed(content):
                        job.emit({"type": "text", "text": piece})
                if choice.get("finish_reason") not in (None, "stop"):
                    failed = True
        for piece in splitter.flush():
            job.emit({"type": "text", "text": piece})
        text = "".join(reply).strip()
        if text:
            job.emit({"type": "done", "reply": text})
        else:
            job.emit({"type": "error", "error": "agent_unavailable" if not failed else "agent_failed"})

    def approve(self, approval_id, approve):
        body = json.dumps({"choice": "once" if approve else "deny"}).encode("utf-8")
        request = Request(f"{self.base_url}/v1/runs/{approval_id}/approval", data=body, headers=self._headers())
        try:
            with urlopen(request, timeout=15) as response:
                return 200 <= response.status < 300
        except (HTTPError, URLError, OSError):
            return False

    def synthesize(self, text, voice):
        import edge_tts  # available in the Hermes venv; imported lazily so tests do not need it

        async def collect():
            audio = bytearray()
            async for chunk in edge_tts.Communicate(text, voice, rate="-8%", pitch="-3Hz").stream():
                if chunk.get("type") == "audio":
                    audio.extend(chunk["data"])
            return bytes(audio)

        return asyncio.run(collect())


def _abort(response):
    """Closes the upstream SSE socket; Hermes treats a disconnect as an interrupt."""
    try:
        response.fp.raw._sock.shutdown(socket.SHUT_RDWR)
    except Exception:
        pass
    try:
        response.close()
    except Exception:
        pass


def make_handler(token, bridge, jobs=None, log=print):
    expected = ("Bearer " + token).encode("utf-8")
    jobs = jobs or Jobs()

    class Handler(BaseHTTPRequestHandler):
        server_version = "HermesVoiceRelay"
        sys_version = ""

        def log_message(self, fmt, *args):
            pass  # default access log is replaced by redacted lines

        def send_json(self, status, body):
            encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self):
            self.send_json(404, {"error": "not_found"})

        def do_POST(self):
            started = time.monotonic()
            fields = {"path": "-", "request_id": "-", "status": 500, "error": "internal"}
            try:
                self.route(fields)
            except Exception as e:
                fields.update(status=500, error=f"internal:{type(e).__name__}")
                try:
                    self.send_json(500, {"error": "internal"})
                except Exception:
                    pass
            finally:
                log(f"relay path={fields['path']} request_id={fields['request_id']} status={fields['status']} "
                    f"error={fields['error'] or '-'} ms={int((time.monotonic() - started) * 1000)}", flush=True)

        def reject(self, fields, status, error):
            fields.update(status=status, error=error)
            self.send_json(status, {"error": error})

        def read_json(self, fields):
            try:
                size = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                size = -1
            if size > MAX_BODY:
                self.reject(fields, 413, "too_large")
                return None
            if size <= 0:
                self.reject(fields, 400, "invalid_body")
                return None
            try:
                data = json.loads(self.rfile.read(size))
            except ValueError:
                data = None
            if not isinstance(data, dict):
                self.reject(fields, 400, "invalid_json")
                return None
            return data

        def route(self, fields):
            cancel = CANCEL_PATH.match(self.path)
            known = self.path in ("/v1/voice-requests", "/v1/voice-approvals", "/v1/tts") or cancel
            fields["path"] = "/v1/voice-requests/<id>/cancel" if cancel else (self.path if known else "other")
            if not known:
                return self.reject(fields, 404, "not_found")
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode("utf-8"), expected):
                return self.reject(fields, 401, "unauthorized")
            if cancel:
                return self.handle_cancel(fields, cancel.group(1))
            data = self.read_json(fields)
            if data is None:
                return None
            if self.path == "/v1/voice-requests":
                return self.handle_voice_request(fields, data)
            if self.path == "/v1/voice-approvals":
                return self.handle_approval(fields, data)
            return self.handle_tts(fields, data)

        def handle_voice_request(self, fields, data):
            request_id, text, conversation_id = data.get("requestId"), data.get("text"), data.get("conversationId")
            if not isinstance(request_id, str) or not ID_PATTERN.match(request_id):
                return self.reject(fields, 400, "invalid_request_id")
            fields["request_id"] = request_id
            if conversation_id is not None and (not isinstance(conversation_id, str) or not ID_PATTERN.match(conversation_id)):
                return self.reject(fields, 400, "invalid_conversation_id")
            if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT:
                return self.reject(fields, 400, "invalid_text")

            def start(job):
                threading.Thread(target=bridge.run, args=(job, text.strip(), conversation_id), daemon=True).start()

            job, created = jobs.attach_or_start(request_id, start)
            fields["error"] = None if created else "reattached"
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            last = None
            try:
                for event in job.stream():
                    self.wfile.write((json.dumps(event, ensure_ascii=False) + "\n").encode("utf-8"))
                    self.wfile.flush()
                    last = event
            except OSError:
                # The phone dropped off; the run keeps going and can be re-attached with the same id.
                fields.update(status=200, error="client_gone")
                return None
            fields["status"] = 200
            if last and last.get("type") == "error":
                fields["error"] = last.get("error")
            return None

        def handle_cancel(self, fields, request_id):
            fields["request_id"] = request_id
            job = jobs.get(request_id)
            if job is not None and not job.finished:
                job.cancel()
            fields.update(status=200, error=None)
            self.send_json(200, {"cancelled": job is not None})

        def handle_approval(self, fields, data):
            request_id, approval_id, approve = data.get("requestId"), data.get("approvalId"), data.get("approve")
            if not isinstance(request_id, str) or not ID_PATTERN.match(request_id):
                return self.reject(fields, 400, "invalid_request_id")
            fields["request_id"] = request_id
            job = jobs.get(request_id)
            # Only approvals Hermes actually asked for within this phone's own run may be answered.
            if job is None or not isinstance(approval_id, str) or approval_id not in job.approval_ids:
                return self.reject(fields, 404, "unknown_approval")
            if not isinstance(approve, bool):
                return self.reject(fields, 400, "invalid_approve")
            if not bridge.approve(approval_id, approve):
                return self.reject(fields, 502, "approval_failed")
            fields.update(status=200, error="approved" if approve else "denied")
            self.send_json(200, {"ok": True})

        def handle_tts(self, fields, data):
            text, voice = data.get("text"), data.get("voice")
            if voice not in TTS_VOICES:
                return self.reject(fields, 400, "invalid_voice")
            if not isinstance(text, str) or not text.strip() or len(text) > MAX_TTS_TEXT:
                return self.reject(fields, 400, "invalid_text")
            try:
                audio = bridge.synthesize(text.strip(), voice)
            except Exception as e:
                return self.reject(fields, 502, f"tts_failed:{type(e).__name__}")
            if not audio:
                return self.reject(fields, 502, "tts_empty")
            fields.update(status=200, error=None)
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(audio)))
            self.end_headers()
            self.wfile.write(audio)

    return Handler


def check_bind_host(host):
    """Refuses wildcard or public binds; only loopback and Tailscale CGNAT addresses are allowed."""
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        raise SystemExit(f"RELAY_HOST must be an IP address, got {host!r}")
    if not (address.is_loopback or address in TAILSCALE):
        raise SystemExit(f"refusing to bind {host}: use 127.0.0.1 or a Tailscale 100.x address")


def read_token(path):
    token = Path(path).expanduser().read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", token):
        raise SystemExit(f"invalid pairing token in {path}; run pair_device.sh")
    return token


def main():
    host = os.environ.get("RELAY_HOST", "127.0.0.1")
    port = int(os.environ.get("RELAY_PORT", "8765"))
    check_bind_host(host)
    api_key = os.environ.get("API_SERVER_KEY")
    if not api_key:
        raise SystemExit("API_SERVER_KEY is not set")
    token = read_token(os.environ.get("RELAY_TOKEN_FILE", "~/.config/hermes-voice/relay_token"))
    base_url = os.environ.get("HERMES_API_URL", "http://127.0.0.1:8642")
    base_url = re.sub(r"/v1/chat/completions/?$", "", base_url)
    server = ThreadingHTTPServer((host, port), make_handler(token, HermesBridge(base_url, api_key)))
    server.daemon_threads = True
    print(f"relay listening on {host}:{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
