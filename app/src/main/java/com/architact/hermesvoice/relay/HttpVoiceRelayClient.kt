package com.architact.hermesvoice.relay

import com.architact.hermesvoice.session.ErrorKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Talks to the relay over the first reachable base URL: USB (`adb reverse` to 127.0.0.1) first,
 * then the tailnet address, remembering whichever worked last.
 */
class HttpVoiceRelayClient(
    private val baseUrls: List<String>,
    private val tokenProvider: () -> String?,
    private val connectTimeoutMs: Int = 5_000,
    // The relay pings every 10 s, so a minute of silence means the connection is gone.
    private val readTimeoutMs: Int = 60_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VoiceRelayClient {
    init {
        require(baseUrls.isNotEmpty())
    }

    @Volatile
    private var preferred = 0

    override fun stream(requestId: String, conversationId: String, text: String): Flow<RelayEvent> = flow {
        val body = JSONObject().put("requestId", requestId).put("conversationId", conversationId).put("text", text)
        val connection = post("/v1/voice-requests", body)
        try {
            expectOk(connection)
            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    throw RelayException(ErrorKind.Timeout, e)
                } catch (e: IOException) {
                    throw RelayException(ErrorKind.AgentUnavailable, e)
                } ?: throw RelayException(ErrorKind.AgentUnavailable) // stream ended without "done"
                if (line.isBlank()) continue
                val event = try { JSONObject(line) } catch (e: JSONException) { continue }
                when (event.optString("type")) {
                    "progress" -> emit(RelayEvent.Progress(event.optString("text"), event.optString("detail")))
                    "text" -> event.optString("text").takeIf { it.isNotBlank() }?.let { emit(RelayEvent.Text(it)) }
                    "approval" -> emit(
                        RelayEvent.Approval(event.getString("approvalId"), event.optString("description"), event.optString("command")),
                    )
                    "done" -> {
                        val reply = event.optString("reply").trim()
                        if (reply.isEmpty()) throw RelayException(ErrorKind.AgentUnavailable)
                        emit(RelayEvent.Done(reply))
                        return@flow
                    }
                    "error" -> throw RelayException(
                        when (event.optString("error")) {
                            "agent_timeout" -> ErrorKind.Timeout
                            "cancelled" -> ErrorKind.Cancelled
                            else -> ErrorKind.AgentUnavailable
                        },
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(dispatcher)

    override suspend fun answerApproval(requestId: String, approvalId: String, approve: Boolean) = withContext(dispatcher) {
        val body = JSONObject().put("requestId", requestId).put("approvalId", approvalId).put("approve", approve)
        val connection = post("/v1/voice-approvals", body)
        try { expectOk(connection) } finally { connection.disconnect() }
    }

    override suspend fun cancel(requestId: String) = withContext(dispatcher) {
        try {
            val connection = post("/v1/voice-requests/$requestId/cancel", JSONObject())
            try { connection.responseCode } finally { connection.disconnect() }
        } catch (e: Exception) {
            // Best effort: the run also stops on the relay's own timeout.
        }
        Unit
    }

    override suspend fun synthesize(text: String, voice: String): ByteArray = withContext(dispatcher) {
        val connection = post("/v1/tts", JSONObject().put("text", text).put("voice", voice), accept = "audio/mpeg")
        try {
            expectOk(connection)
            connection.inputStream.use { it.readBytes() }
        } catch (e: SocketTimeoutException) {
            throw RelayException(ErrorKind.Timeout, e)
        } catch (e: IOException) {
            throw RelayException(ErrorKind.AgentUnavailable, e)
        } finally {
            connection.disconnect()
        }
    }

    /** Connects to the first reachable relay and sends [body]; failures after connecting are not retried elsewhere. */
    private fun post(path: String, body: JSONObject, accept: String = "application/x-ndjson, application/json"): HttpURLConnection {
        val token = tokenProvider() ?: throw RelayException(ErrorKind.Unpaired)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        var lastError: IOException? = null
        val order = baseUrls.indices.sortedBy { if (it == preferred) -1 else it }
        for (index in order) {
            val connection = URL(baseUrls[index].trimEnd('/') + path).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            // Fixed-length streaming also stops HttpURLConnection from silently re-sending the POST.
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Authorization", "Bearer $token")
            try {
                connection.connect()
            } catch (e: IOException) {
                connection.disconnect()
                lastError = e
                continue
            }
            preferred = index
            try {
                connection.outputStream.use { it.write(bytes) }
            } catch (e: IOException) {
                connection.disconnect()
                throw RelayException(ErrorKind.NoRelay, e)
            }
            return connection
        }
        throw RelayException(ErrorKind.NoRelay, lastError)
    }

    private fun expectOk(connection: HttpURLConnection) {
        val code = try {
            connection.responseCode
        } catch (e: SocketTimeoutException) {
            throw RelayException(ErrorKind.Timeout, e)
        } catch (e: IOException) {
            throw RelayException(ErrorKind.NoRelay, e)
        }
        when (code) {
            HttpURLConnection.HTTP_OK -> Unit
            HttpURLConnection.HTTP_BAD_REQUEST, HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> throw RelayException(ErrorKind.BadRequest)
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> throw RelayException(ErrorKind.Unauthorized)
            HttpURLConnection.HTTP_GATEWAY_TIMEOUT -> throw RelayException(ErrorKind.Timeout)
            else -> throw RelayException(ErrorKind.AgentUnavailable)
        }
    }
}
