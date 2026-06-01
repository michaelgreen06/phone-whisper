package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque

class BackendTranscriptClientTest {
    private val tempDirectories = mutableListOf<File>()
    private val config = BackendTranscriptClient.Config(
        baseUrl = "https://example.com/",
        apiToken = "secret-token",
    )

    @After
    fun tearDown() {
        tempDirectories.forEach(File::deleteRecursively)
    }

    @Test
    fun `queue atomically persists capture-only payload before disabled upload`() {
        val filesDir = newFilesDir()
        val captureId = "00000000-0000-0000-0000-000000000001"

        val result = BackendTranscriptClient.enqueueAndFlush(
            filesDir = filesDir,
            text = "  buy milk  ",
            capturedAt = "2026-05-31T12:00:00Z",
            metadata = metadata(),
            config = config.copy(enabled = false),
            clientCaptureId = captureId,
        )

        val outbox = File(filesDir, "pending_transcript_uploads")
        val files = outbox.listFiles()!!.toList()
        val payload = JSONObject(File(outbox, "$captureId.json").readText())
        assertEquals(BackendTranscriptClient.Status.ENQUEUED, result.status)
        assertEquals(listOf("$captureId.json"), files.map(File::getName))
        assertEquals(
            setOf("client_capture_id", "text", "captured_at", "metadata"),
            payload.keys().asSequence().toSet(),
        )
        assertEquals(captureId, payload.getString("client_capture_id"))
        assertEquals("buy milk", payload.getString("text"))
        assertEquals("2026-05-31T12:00:00Z", payload.getString("captured_at"))
        assertEquals(
            setOf("platform", "app_version", "android_sdk", "transcription_engine"),
            payload.getJSONObject("metadata").keys().asSequence().toSet(),
        )
        assertFalse(payload.has("status"))
        assertFalse(payload.has("kind"))
        assertFalse(payload.has("rank"))
        assertFalse(payload.has("routing"))
        assertFalse(payload.has("routing_instructions"))
        assertFalse(payload.has("transcript"))
    }

    @Test
    fun `flush sends auth capture payload and deletes file after valid 201 response`() {
        val filesDir = newFilesDir()
        val captureId = "00000000-0000-0000-0000-000000000002"
        val callFactory = ScriptedCallFactory(ResponseOutcome(201, successResponse()))

        BackendTranscriptClient.enqueueAndFlush(
            filesDir = filesDir,
            text = "call dentist",
            capturedAt = "2026-05-31T12:00:00Z",
            metadata = metadata(),
            config = config,
            clientCaptureId = captureId,
            callFactory = callFactory,
        )

        val request = callFactory.requests.single()
        val payload = requestBody(request)
        assertEquals("https://example.com/items", request.url.toString())
        assertEquals("Bearer secret-token", request.header("Authorization"))
        assertEquals(captureId, payload.getString("client_capture_id"))
        assertFalse(File(filesDir, "pending_transcript_uploads/$captureId.json").exists())
    }

    @Test
    fun `flush retains file on timeout http error and malformed success response`() {
        val outcomes = listOf(
            FailureOutcome(IOException("timeout")),
            ResponseOutcome(503, """{"message":"try later"}"""),
            ResponseOutcome(
                201,
                """{"capture_id":"capture-1","routed_item_id":"todo-1","duplicate":"false"}""",
            ),
        )

        outcomes.forEachIndexed { index, outcome ->
            val filesDir = newFilesDir()
            val captureId = "00000000-0000-0000-0000-00000000001${index + 1}"
            val callFactory = ScriptedCallFactory(outcome)

            BackendTranscriptClient.enqueueAndFlush(
                filesDir = filesDir,
                text = "retain me",
                capturedAt = "2026-05-31T12:00:00Z",
                metadata = metadata(),
                config = config,
                clientCaptureId = captureId,
                callFactory = callFactory,
            )

            assertTrue(File(filesDir, "pending_transcript_uploads/$captureId.json").exists())
        }
    }

    @Test
    fun `flush drains valid captures oldest first`() {
        val filesDir = newFilesDir()
        val olderId = "00000000-0000-0000-0000-000000000021"
        val newerId = "00000000-0000-0000-0000-000000000022"
        val older = persist(filesDir, olderId, "older")
        val newer = persist(filesDir, newerId, "newer")
        older.setLastModified(1000)
        newer.setLastModified(2000)
        val callFactory = ScriptedCallFactory(
            ResponseOutcome(201, successResponse(captureId = "capture-older", routedItemId = "todo-older")),
            ResponseOutcome(201, successResponse(captureId = "capture-newer", routedItemId = "todo-newer")),
        )

        BackendTranscriptClient.flush(filesDir, config, callFactory)

        assertEquals(
            listOf(olderId, newerId),
            callFactory.requests.map { requestBody(it).getString("client_capture_id") },
        )
        assertTrue(BackendTranscriptClient.pendingFiles(filesDir).isEmpty())
    }

    @Test
    fun `malformed local file is retained logged and does not block later captures`() {
        val filesDir = newFilesDir()
        val outbox = File(filesDir, "pending_transcript_uploads").apply { mkdirs() }
        val malformed = File(outbox, "00000000-0000-0000-0000-000000000031.json")
            .apply {
                writeText("not json")
                setLastModified(1000)
            }
        val valid = persist(filesDir, "00000000-0000-0000-0000-000000000032", "valid")
            .apply { setLastModified(2000) }
        val logs = mutableListOf<String>()
        val callFactory = ScriptedCallFactory(ResponseOutcome(201, successResponse()))

        BackendTranscriptClient.flush(filesDir, config, callFactory, logs::add)

        assertTrue(malformed.exists())
        assertFalse(valid.exists())
        assertTrue(logs.any { it.contains("Retaining malformed local transcript") })
        assertEquals(1, callFactory.requests.size)
    }

    @Test
    fun `overlapping flush does not send duplicate request`() {
        val filesDir = newFilesDir()
        val captureId = "00000000-0000-0000-0000-000000000041"
        persist(filesDir, captureId, "only once")
        val callFactory = DelayedCallFactory()

        val first = BackendTranscriptClient.flush(filesDir, config, callFactory)
        val overlapping = BackendTranscriptClient.flush(filesDir, config, callFactory)

        assertEquals(BackendTranscriptClient.Status.ENQUEUED, first.status)
        assertEquals(BackendTranscriptClient.Status.SKIPPED, overlapping.status)
        assertEquals(1, callFactory.requests.size)
        callFactory.respondNext(201, successResponse())
        assertFalse(File(filesDir, "pending_transcript_uploads/$captureId.json").exists())
    }

    @Test
    fun `duplicate 201 response deletes retried outbox file`() {
        val filesDir = newFilesDir()
        val captureId = "00000000-0000-0000-0000-000000000042"
        val pending = persist(filesDir, captureId, "already ingested")
        val callFactory = ScriptedCallFactory(
            ResponseOutcome(201, successResponse(duplicate = true)),
        )

        BackendTranscriptClient.flush(filesDir, config, callFactory)

        assertFalse(pending.exists())
    }

    @Test
    fun `duplicate 200 response retains retried outbox file`() {
        val filesDir = newFilesDir()
        val captureId = "00000000-0000-0000-0000-000000000043"
        val pending = persist(filesDir, captureId, "already ingested")
        val callFactory = ScriptedCallFactory(
            ResponseOutcome(200, successResponse(duplicate = true)),
        )

        BackendTranscriptClient.flush(filesDir, config, callFactory)

        assertTrue(pending.exists())
    }

    @Test
    fun `later transcript queued during active flush is drained without duplicate send`() {
        val filesDir = newFilesDir()
        val firstId = "00000000-0000-0000-0000-000000000051"
        val laterId = "00000000-0000-0000-0000-000000000052"
        persist(filesDir, firstId, "first")
        val callFactory = DelayedCallFactory()

        BackendTranscriptClient.flush(filesDir, config, callFactory)
        BackendTranscriptClient.enqueueAndFlush(
            filesDir = filesDir,
            text = "later",
            capturedAt = "2026-05-31T12:00:01Z",
            metadata = metadata(),
            config = config,
            clientCaptureId = laterId,
            callFactory = callFactory,
        )
        assertEquals(1, callFactory.requests.size)

        callFactory.respondNext(201, successResponse(captureId = "capture-first", routedItemId = "todo-first"))
        assertEquals(2, callFactory.requests.size)
        callFactory.respondNext(201, successResponse(captureId = "capture-later", routedItemId = "todo-later"))

        assertEquals(
            listOf(firstId, laterId),
            callFactory.requests.map { requestBody(it).getString("client_capture_id") },
        )
        assertTrue(BackendTranscriptClient.pendingFiles(filesDir).isEmpty())
    }

    @Test
    fun `success response requires routed capture envelope with sensible types`() {
        assertEquals(
            BackendTranscriptClient.Status.SUCCESS,
            BackendTranscriptClient.parseHttpResult(201, successResponse()).status,
        )
        assertEquals(
            BackendTranscriptClient.Status.SUCCESS,
            BackendTranscriptClient.parseHttpResult(201, successResponse(duplicate = true)).status,
        )
        listOf(
            null,
            "",
            "not json",
            "[]",
            """{"id":"todo-1"}""",
            """{"capture_id":1,"routed_item_id":"todo-1","duplicate":false}""",
            """{"capture_id":"capture-1","routed_item_id":true,"duplicate":false}""",
            """{"capture_id":"capture-1","routed_item_id":"todo-1","duplicate":"false"}""",
        ).forEach { body ->
            assertEquals(
                BackendTranscriptClient.Status.ERROR,
                BackendTranscriptClient.parseHttpResult(201, body).status,
            )
        }
        assertEquals(
            BackendTranscriptClient.Status.ERROR,
            BackendTranscriptClient.parseHttpResult(200, successResponse()).status,
        )
        assertEquals(
            BackendTranscriptClient.Status.ERROR,
            BackendTranscriptClient.parseHttpResult(200, successResponse(duplicate = true)).status,
        )
    }

    @Test
    fun `blank transcript is skipped without outbox file`() {
        val filesDir = newFilesDir()

        val result = BackendTranscriptClient.enqueueAndFlush(
            filesDir = filesDir,
            text = "   ",
            capturedAt = "2026-05-31T12:00:00Z",
            metadata = metadata(),
            config = config,
        )

        assertEquals(BackendTranscriptClient.Status.SKIPPED, result.status)
        assertTrue(BackendTranscriptClient.pendingFiles(filesDir).isEmpty())
    }

    private fun newFilesDir(): File =
        Files.createTempDirectory("phone-whisper-test").toFile().also(tempDirectories::add)

    private fun metadata(): JSONObject =
        JSONObject().apply {
            put("platform", "android")
            put("app_version", "0.3.0")
            put("android_sdk", 34)
            put("transcription_engine", "local")
        }

    private fun persist(filesDir: File, captureId: String, text: String): File =
        BackendTranscriptClient.persistPayload(
            filesDir,
            BackendTranscriptClient.buildPayload(
                clientCaptureId = captureId,
                text = text,
                capturedAt = "2026-05-31T12:00:00Z",
                metadata = metadata(),
            ),
        )

    private fun requestBody(request: Request): JSONObject {
        val buffer = Buffer()
        assertNotNull(request.body)
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private sealed interface Outcome
    private data class ResponseOutcome(val code: Int, val body: String) : Outcome
    private data class FailureOutcome(val exception: IOException) : Outcome

    private class ScriptedCallFactory(vararg outcomes: Outcome) : Call.Factory {
        val requests = mutableListOf<Request>()
        private val outcomes = ArrayDeque(outcomes.toList())

        override fun newCall(request: Request): Call {
            requests += request
            val outcome = outcomes.removeFirst()
            return CallbackCall(request) { call, callback ->
                when (outcome) {
                    is FailureOutcome -> callback.onFailure(call, outcome.exception)
                    is ResponseOutcome -> callback.onResponse(
                        call,
                        response(request, outcome.code, outcome.body),
                    )
                }
            }
        }
    }

    private class DelayedCallFactory : Call.Factory {
        val requests = mutableListOf<Request>()
        private val pending = ArrayDeque<Pair<Call, Callback>>()

        override fun newCall(request: Request): Call {
            requests += request
            return CallbackCall(request) { call, callback -> pending += call to callback }
        }

        fun respondNext(code: Int, body: String) {
            val (call, callback) = pending.removeFirst()
            callback.onResponse(call, response(call.request(), code, body))
        }
    }

    private class CallbackCall(
        private val request: Request,
        private val onEnqueue: (Call, Callback) -> Unit,
    ) : Call {
        private var executed = false

        override fun request(): Request = request
        override fun execute(): Response = error("Not used")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            onEnqueue(this, responseCallback)
        }
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = CallbackCall(request, onEnqueue)
    }

    private companion object {
        fun successResponse(
            captureId: String = "capture-1",
            routedItemId: String = "todo-1",
            duplicate: Boolean = false,
        ): String =
            """{"capture_id":"$captureId","routed_item_id":"$routedItemId","duplicate":$duplicate}"""

        fun response(request: Request, code: Int, body: String): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody())
                .build()
    }
}
