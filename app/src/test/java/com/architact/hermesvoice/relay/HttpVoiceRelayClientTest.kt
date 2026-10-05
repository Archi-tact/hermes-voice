package com.architact.hermesvoice.relay

import com.architact.hermesvoice.session.ErrorKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class HttpVoiceRelayClientTest {
    private val server = MockWebServer().apply { start() }
    private val token = "t".repeat(32)
    private val closedUrl = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}"

    @After
    fun tearDown() = server.shutdown()

    private fun ndjson(vararg lines: String) =
        server.enqueue(MockResponse().setBody(lines.joinToString("\n", postfix = "\n")).setHeader("Content-Type", "application/x-ndjson"))

    private fun client(token: String? = this.token, urls: List<String> = listOf(server.url("/").toString()), readTimeoutMs: Int = 2_000) =
        HttpVoiceRelayClient(urls, { token }, connectTimeoutMs = 1_000, readTimeoutMs = readTimeoutMs)

    private fun events(client: HttpVoiceRelayClient = client()) = runBlocking { client.stream("r-1", "c-1", "질문").toList() }

    private fun assertFailsWith(kind: ErrorKind, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected $kind")
        } catch (e: RelayException) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun `stream posts the request with bearer token and parses events`() {
        ndjson(
            """{"type":"progress","text":"검색하고 있어요.","detail":"search x"}""",
            """{"type":"ping"}""",
            """{"type":"approval","approvalId":"run-1","description":"삭제","command":"rm x"}""",
            """{"type":"text","text":"찾았어요. "}""",
            """{"type":"done","reply":"찾았어요."}""",
        )

        assertEquals(
            listOf(
                RelayEvent.Progress("검색하고 있어요.", "search x"),
                RelayEvent.Approval("run-1", "삭제", "rm x"),
                RelayEvent.Text("찾았어요. "),
                RelayEvent.Done("찾았어요."),
            ),
            events(),
        )
        val request = server.takeRequest()
        assertEquals("/v1/voice-requests", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(listOf("r-1", "c-1", "질문"), listOf(body.getString("requestId"), body.getString("conversationId"), body.getString("text")))
    }

    @Test
    fun `error events map to error kinds`() {
        ndjson("""{"type":"error","error":"agent_timeout"}""")
        ndjson("""{"type":"error","error":"agent_unavailable"}""")
        ndjson("""{"type":"error","error":"cancelled"}""")
        assertFailsWith(ErrorKind.Timeout) { client().stream("r-1", "c-1", "x").toList() }
        assertFailsWith(ErrorKind.AgentUnavailable) { client().stream("r-1", "c-1", "x").toList() }
        assertFailsWith(ErrorKind.Cancelled) { client().stream("r-1", "c-1", "x").toList() }
    }

    @Test
    fun `stream ending without done is a lost connection`() {
        ndjson("""{"type":"text","text":"반쯤 "}""")
        assertFailsWith(ErrorKind.AgentUnavailable) { client().stream("r-1", "c-1", "x").toList() }
    }

    @Test
    fun `http status codes map to error kinds`() {
        mapOf(401 to ErrorKind.Unauthorized, 400 to ErrorKind.BadRequest, 502 to ErrorKind.AgentUnavailable, 504 to ErrorKind.Timeout)
            .forEach { (code, kind) ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
                assertFailsWith(kind) { client().stream("r-1", "c-1", "x").toList() }
            }
    }

    @Test
    fun `missing pairing token fails without contacting the relay`() {
        assertFailsWith(ErrorKind.Unpaired) { client(token = null).stream("r-1", "c-1", "x").toList() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `silent relay times out and the request is sent only once`() {
        server.enqueue(MockResponse().setBody("""{"type":"done","reply":"늦음"}""").setHeadersDelay(1, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("""{"type":"done","reply":"재전송됨"}"""))
        assertFailsWith(ErrorKind.Timeout) { client(readTimeoutMs = 200).stream("r-1", "c-1", "x").toList() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `falls back to the next relay url when the first is unreachable`() {
        ndjson("""{"type":"done","reply":"테일넷으로 왔어요."}""")
        val events = events(client(urls = listOf(closedUrl, server.url("/").toString())))
        assertEquals(listOf(RelayEvent.Done("테일넷으로 왔어요.")), events)
    }

    @Test
    fun `no reachable relay maps to no relay`() {
        assertFailsWith(ErrorKind.NoRelay) { client(urls = listOf(closedUrl)).stream("r-1", "c-1", "x").toList() }
    }

    @Test
    fun `approval answer and cancel hit their endpoints`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setBody("""{"cancelled":true}"""))
        client().answerApproval("r-1", "run-1", approve = true)
        client().cancel("r-1")

        val approval = server.takeRequest()
        assertEquals("/v1/voice-approvals", approval.path)
        val body = JSONObject(approval.body.readUtf8())
        assertEquals(listOf("r-1", "run-1", true), listOf(body.getString("requestId"), body.getString("approvalId"), body.getBoolean("approve")))
        assertEquals("/v1/voice-requests/r-1/cancel", server.takeRequest().path)
    }

    @Test
    fun `cancel never throws`() = runBlocking {
        client(urls = listOf(closedUrl)).cancel("r-1")
    }

    @Test
    fun `synthesize returns audio bytes`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        assertArrayEquals(byteArrayOf(1, 2, 3), client().synthesize("안녕", "ko-KR-SunHiNeural"))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("ko-KR-SunHiNeural", body.getString("voice"))
    }
}
