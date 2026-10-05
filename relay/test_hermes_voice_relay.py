import io
import json
import threading
import time
import unittest
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import hermes_voice_relay as relay

TOKEN = "a" * 40
SECRET = "비밀 질문 내용"


class FakeBridge:
    """Stands in for Hermes; each test scripts what a run emits."""

    def __init__(self):
        self.runs = []
        self.approvals = []
        self.script = lambda job, text: (job.emit({"type": "text", "text": "확인했습니다."}),
                                         job.emit({"type": "done", "reply": "확인했습니다."}))
        self.approve_result = True

    def run(self, job, text, conversation_id=None):
        self.runs.append((job.request_id, text, conversation_id))
        self.script(job, text)

    def approve(self, approval_id, approve):
        self.approvals.append((approval_id, approve))
        return self.approve_result

    def synthesize(self, text, voice):
        return b"ID3-fake-mp3"


def serve(handler):
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.bridge = FakeBridge()
        self.jobs = relay.Jobs()
        self.log = io.StringIO()

        def log(line, flush=False):
            self.log.write(line + "\n")

        self.server = serve(relay.make_handler(TOKEN, self.bridge, self.jobs, log))

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def post(self, path, body, token=TOKEN):
        connection = HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=10)
        raw = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        connection.request("POST", path, body=raw, headers=headers)
        response = connection.getresponse()
        payload = response.read()
        connection.close()
        return response.status, response.getheader("Content-Type", ""), payload

    def ask(self, body, token=TOKEN):
        status, kind, payload = self.post("/v1/voice-requests", body, token)
        if "ndjson" in kind:
            events = [json.loads(line) for line in payload.decode("utf-8").splitlines() if line]
            return status, [e for e in events if e["type"] != "ping"]
        return status, json.loads(payload)

    def test_valid_request_streams_text_then_done(self):
        status, events = self.ask({"requestId": "r-12345678", "conversationId": "c-12345678", "text": " 오늘 일정 "})
        self.assertEqual(200, status)
        self.assertEqual([{"type": "text", "text": "확인했습니다."}, {"type": "done", "reply": "확인했습니다."}], events)
        self.assertEqual([("r-12345678", "오늘 일정", "c-12345678")], self.bridge.runs)

    def test_missing_or_wrong_token_is_rejected_before_calling_agent(self):
        self.assertEqual(401, self.ask({"requestId": "r-12345678", "text": "x"}, token=None)[0])
        self.assertEqual(401, self.ask({"requestId": "r-12345678", "text": "x"}, token="b" * 40)[0])
        self.assertEqual(401, self.post("/v1/tts", {"text": "x", "voice": "ko-KR-SunHiNeural"}, token=None)[0])
        self.assertEqual([], self.bridge.runs)

    def test_invalid_input_is_400(self):
        self.assertEqual(400, self.ask(b"not json")[0])
        self.assertEqual(400, self.ask({"requestId": "r-12345678", "text": 3})[0])
        self.assertEqual(400, self.ask({"requestId": "r-12345678", "text": "   "})[0])
        self.assertEqual(400, self.ask({"requestId": "bad id!", "text": "x"})[0])
        self.assertEqual(400, self.ask({"requestId": "r-12345678", "conversationId": "../x", "text": "x"})[0])
        self.assertEqual(400, self.ask({"requestId": "r-12345678", "text": "가" * 2001})[0])
        self.assertEqual([], self.bridge.runs)

    def test_oversized_body_is_413_and_unknown_path_404(self):
        self.assertEqual(413, self.ask(b"x" * (relay.MAX_BODY + 1))[0])
        self.assertEqual(404, self.post("/other", {"x": 1})[0])

    def test_same_request_id_reattaches_instead_of_running_twice(self):
        self.ask({"requestId": "r-12345678", "text": "작업해줘"})
        status, events = self.ask({"requestId": "r-12345678", "text": "작업해줘"})
        self.assertEqual(200, status)
        self.assertEqual("done", events[-1]["type"])
        self.assertEqual(1, len(self.bridge.runs))

    def test_failed_run_is_started_again_on_retry(self):
        self.bridge.script = lambda job, text: job.emit({"type": "error", "error": "agent_unavailable"})
        self.assertEqual([{"type": "error", "error": "agent_unavailable"}], self.ask({"requestId": "r-12345678", "text": "x"})[1])
        self.ask({"requestId": "r-12345678", "text": "x"})
        self.assertEqual(2, len(self.bridge.runs))

    def test_run_survives_the_phone_dropping_off(self):
        gate = threading.Event()

        def slow(job, text):
            job.emit({"type": "progress", "text": "검색하고 있어요.", "detail": ""})
            gate.wait(5)
            job.emit({"type": "done", "reply": "끝났어요."})

        self.bridge.script = lambda job, text: threading.Thread(target=slow, args=(job, text), daemon=True).start()
        connection = HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=5)
        connection.request("POST", "/v1/voice-requests", body=json.dumps({"requestId": "r-12345678", "text": "x"}),
                           headers={"Authorization": "Bearer " + TOKEN})
        response = connection.getresponse()
        self.assertEqual("progress", json.loads(response.readline())["type"])
        connection.close()  # phone goes away mid-run

        gate.set()
        status, events = self.ask({"requestId": "r-12345678", "text": "x"})
        self.assertEqual(["progress", "done"], [e["type"] for e in events])
        self.assertEqual(1, len(self.bridge.runs))

    def test_cancel_stops_the_run(self):
        hooked = threading.Event()

        def waiting(job, text):
            job.on_cancel(hooked.set)

        self.bridge.script = waiting
        threading.Thread(target=self.ask, args=({"requestId": "r-12345678", "text": "x"},), daemon=True).start()
        for _ in range(50):
            if self.jobs.get("r-12345678"):
                break
            time.sleep(0.05)
        status, _, body = self.post("/v1/voice-requests/r-12345678/cancel", b"")
        self.assertEqual(200, status)
        self.assertTrue(hooked.wait(2))
        self.assertTrue(self.jobs.get("r-12345678").cancelled)

    def test_only_approvals_from_the_phones_own_run_can_be_answered(self):
        self.bridge.script = lambda job, text: (
            job.emit({"type": "approval", "approvalId": "chatcmpl-1", "description": "파일 삭제", "command": "rm x"}),
            job.emit({"type": "done", "reply": "삭제했습니다."}))
        self.ask({"requestId": "r-12345678", "text": "지워줘"})

        self.assertEqual(404, self.post("/v1/voice-approvals",
                                        {"requestId": "r-12345678", "approvalId": "chatcmpl-other", "approve": True})[0])
        self.assertEqual(400, self.post("/v1/voice-approvals",
                                        {"requestId": "r-12345678", "approvalId": "chatcmpl-1", "approve": "yes"})[0])
        self.assertEqual(200, self.post("/v1/voice-approvals",
                                        {"requestId": "r-12345678", "approvalId": "chatcmpl-1", "approve": False})[0])
        self.assertEqual([("chatcmpl-1", False)], self.bridge.approvals)

    def test_tts_returns_audio_for_allowed_voices_only(self):
        status, kind, body = self.post("/v1/tts", {"text": "안녕하세요", "voice": "ko-KR-SunHiNeural"})
        self.assertEqual((200, "audio/mpeg", b"ID3-fake-mp3"), (status, kind, body))
        self.assertEqual(400, self.post("/v1/tts", {"text": "안녕하세요", "voice": "en-US-AriaNeural"})[0])
        self.assertEqual(400, self.post("/v1/tts", {"text": "가" * 1001, "voice": "ko-KR-SunHiNeural"})[0])

    def test_log_contains_status_but_never_content_or_token(self):
        self.bridge.script = lambda job, text: job.emit({"type": "done", "reply": "답변 " + SECRET})
        self.ask({"requestId": "r-12345678", "text": SECRET})
        self.ask({"requestId": "r-12345678", "text": SECRET}, token="b" * 40)
        logged = self.log.getvalue()
        self.assertIn("request_id=r-12345678 status=200", logged)
        self.assertIn("status=401 error=unauthorized", logged)
        self.assertNotIn(SECRET, logged)
        self.assertNotIn(TOKEN, logged)


class SentenceSplitterTest(unittest.TestCase):
    def test_splits_at_sentence_ends_across_deltas(self):
        splitter = relay.SentenceSplitter()
        pieces = splitter.feed("오늘 일정은 세 건입니다. 첫 번째는 ")
        pieces += splitter.feed("열 시 회의예요. 두 번째는")
        self.assertEqual(["오늘 일정은 세 건입니다. ", "첫 번째는 열 시 회의예요. "], pieces)
        self.assertEqual(["두 번째는"], splitter.flush())

    def test_does_not_split_decimals_or_inside_code(self):
        splitter = relay.SentenceSplitter()
        self.assertEqual([], splitter.feed("버전은 3.5 입니다 그리고 ```\na. b. c.\n"))
        self.assertEqual(["버전은 3.5 입니다 그리고 ```\na. b. c.\n```\n"], splitter.feed("```\n"))


class HermesBridgeTest(unittest.TestCase):
    """Runs the real bridge against a fake Hermes API Server speaking SSE."""

    SSE = (
        ": keepalive\n\n"
        'data: {"choices":[{"delta":{"role":"assistant"}}]}\n\n'
        'event: hermes.tool.progress\ndata: {"tool":"web_search","label":"search x","status":"running"}\n\n'
        'event: hermes.tool.progress\ndata: {"tool":"web_search","status":"completed"}\n\n'
        'event: approval.request\ndata: {"event":"approval.request","run_id":"chatcmpl-9","description":"delete","command":"rm -rf x"}\n\n'
        'data: {"choices":[{"delta":{"content":"찾아봤어요. 결과는 "}}]}\n\n'
        'data: {"choices":[{"delta":{"content":"두 건입니다."},"finish_reason":null}]}\n\n'
        'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'
        "data: [DONE]\n\n"
    )

    def setUp(self):
        self.requests = []
        test = self

        class FakeHermes(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                test.requests.append((self.path, dict(self.headers), body))
                encoded = (test.SSE if self.path.endswith("/completions") else '{"ok":true}').encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

        self.server = serve(FakeHermes)
        self.bridge = relay.HermesBridge(f"http://127.0.0.1:{self.server.server_address[1]}/", "server-key")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_stream_becomes_progress_approval_text_and_done(self):
        job = relay.Job("r-12345678")
        self.bridge.run(job, "질문", "c-12345678")

        # "찾아봤어요." alone is shorter than the minimum piece, so it is spoken together with the next sentence.
        self.assertEqual(["progress", "approval", "text", "done"], [e["type"] for e in job.events])
        self.assertEqual("찾아봤어요. 결과는 두 건입니다.", job.events[2]["text"])
        self.assertEqual("검색하고 있어요.", job.events[0]["text"])
        self.assertEqual({"type": "approval", "approvalId": "chatcmpl-9", "description": "delete", "command": "rm -rf x"},
                         job.events[1])
        self.assertEqual("찾아봤어요. 결과는 두 건입니다.", job.events[-1]["reply"])

        [(path, headers, body)] = self.requests
        self.assertEqual("/v1/chat/completions", path)
        self.assertEqual("Bearer server-key", headers["Authorization"])
        self.assertEqual("hermes-voice-c-12345678", headers["X-Hermes-Session-Id"])
        self.assertTrue(body["stream"])
        self.assertEqual(["system", "user"], [m["role"] for m in body["messages"]])

    def test_without_conversation_no_session_header_is_sent(self):
        self.bridge.run(relay.Job("r-12345678"), "질문")
        self.assertNotIn("X-Hermes-Session-Id", self.requests[0][1])

    def test_approve_posts_choice_to_the_run(self):
        self.assertTrue(self.bridge.approve("chatcmpl-9", True))
        self.assertTrue(self.bridge.approve("chatcmpl-9", False))
        self.assertEqual([("/v1/runs/chatcmpl-9/approval", {"choice": "once"}),
                          ("/v1/runs/chatcmpl-9/approval", {"choice": "deny"})],
                         [(p, b) for p, _, b in self.requests])

    def test_unreachable_hermes_becomes_an_error_event(self):
        job = relay.Job("r-12345678")
        relay.HermesBridge("http://127.0.0.1:9", "k").run(job, "질문")
        self.assertEqual({"type": "error", "error": "agent_unavailable"}, job.events[-1])


class BindHostTest(unittest.TestCase):
    def test_loopback_and_tailscale_are_allowed(self):
        relay.check_bind_host("127.0.0.1")
        relay.check_bind_host("100.101.102.103")

    def test_wildcard_and_lan_are_refused(self):
        for host in ("0.0.0.0", "::", "192.168.0.10", "localhost"):
            with self.assertRaises(SystemExit, msg=host):
                relay.check_bind_host(host)


if __name__ == "__main__":
    unittest.main()
