package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackendTranscriptClientTest {

    private val config = BackendTranscriptClient.Config(
        baseUrl = "https://example.com/",
        apiToken = "secret-token",
    )

    @Test
    fun `parses 201 with json body as success`() {
        val result = BackendTranscriptClient.parseHttpResult(201, """{"id":"abc123"}""")

        assertEquals(BackendTranscriptClient.Status.SUCCESS, result.status)
        assertEquals(201, result.code)
        assertNull(result.error)
    }

    @Test
    fun `parses non-201 as error`() {
        val result = BackendTranscriptClient.parseHttpResult(
            401,
            """{"error":{"message":"Invalid token"}}""",
        )

        assertEquals(BackendTranscriptClient.Status.ERROR, result.status)
        assertEquals(401, result.code)
        assertEquals("Invalid token", result.error)
    }

    @Test
    fun `parses malformed 201 body as error`() {
        val result = BackendTranscriptClient.parseHttpResult(201, "not json")

        assertEquals(BackendTranscriptClient.Status.ERROR, result.status)
        assertEquals(201, result.code)
        assertNotNull(result.error)
    }

    @Test
    fun `skips blank text before network call`() {
        var callbackResult: BackendTranscriptClient.Result? = null
        var createdCalls = 0
        val callFactory = Call.Factory {
            createdCalls += 1
            error("Should not create network call for blank text")
        }

        val immediate = BackendTranscriptClient.enqueueUpload(
            text = "   ",
            capturedAt = "2026-05-29T12:00:00Z",
            metadata = JSONObject(),
            config = config,
            callFactory = callFactory,
        ) {
            callbackResult = it
        }

        assertEquals(BackendTranscriptClient.Status.SKIPPED, immediate.status)
        assertEquals(BackendTranscriptClient.Status.SKIPPED, callbackResult?.status)
        assertEquals(0, createdCalls)
    }

    @Test
    fun `builds request with trimmed base url auth header and payload`() {
        val capturedCallFactory = CapturingCallFactory(
            responseCode = 201,
            responseBody = """{"ok":true}""",
        )
        var callbackResult: BackendTranscriptClient.Result? = null

        val immediate = BackendTranscriptClient.enqueueUpload(
            text = "hello from village",
            capturedAt = "2026-05-29T12:34:56Z",
            metadata = JSONObject().put("source", "test"),
            config = config,
            callFactory = capturedCallFactory,
        ) {
            callbackResult = it
        }

        val request = capturedCallFactory.lastRequest
        val bodyBuffer = Buffer()
        request!!.body!!.writeTo(bodyBuffer)
        val payload = JSONObject(bodyBuffer.readUtf8())

        assertEquals(BackendTranscriptClient.Status.ENQUEUED, immediate.status)
        assertEquals(BackendTranscriptClient.Status.SUCCESS, callbackResult?.status)
        assertEquals("https://example.com/items", request.url.toString())
        assertEquals("Bearer secret-token", request.header("Authorization"))
        assertEquals("hello from village", payload.getString("text"))
        assertEquals("2026-05-29T12:34:56Z", payload.getString("captured_at"))
        assertEquals("test", payload.getJSONObject("metadata").getString("source"))
    }

    @Test
    fun `returns network failure as error`() {
        var callbackResult: BackendTranscriptClient.Result? = null
        val callFactory = FailingCallFactory(IOException("socket exploded"))

        BackendTranscriptClient.enqueueUpload(
            text = "hello",
            capturedAt = "2026-05-29T12:00:00Z",
            metadata = JSONObject(),
            config = config,
            callFactory = callFactory,
        ) {
            callbackResult = it
        }

        assertEquals(BackendTranscriptClient.Status.ERROR, callbackResult?.status)
        assertTrue(callbackResult?.error?.contains("socket exploded") == true)
    }

    private class CapturingCallFactory(
        private val responseCode: Int,
        private val responseBody: String,
    ) : Call.Factory {
        var lastRequest: Request? = null

        override fun newCall(request: Request): Call {
            lastRequest = request
            return FakeCall(
                request = request,
                responseCode = responseCode,
                responseBody = responseBody,
            )
        }
    }

    private class FailingCallFactory(
        private val exception: IOException,
    ) : Call.Factory {
        override fun newCall(request: Request): Call = object : Call {
            private var executed = false

            override fun request(): Request = request

            override fun execute(): Response {
                throw exception
            }

            override fun enqueue(responseCallback: okhttp3.Callback) {
                executed = true
                responseCallback.onFailure(this, exception)
            }

            override fun cancel() = Unit

            override fun isExecuted(): Boolean = executed

            override fun isCanceled(): Boolean = false

            override fun timeout(): Timeout = Timeout.NONE

            override fun clone(): Call = this
        }
    }

    private class FakeCall(
        private val request: Request,
        private val responseCode: Int,
        private val responseBody: String,
    ) : Call {
        private var executed = false

        override fun request(): Request = request

        override fun execute(): Response = buildResponse()

        override fun enqueue(responseCallback: okhttp3.Callback) {
            executed = true
            responseCallback.onResponse(this, buildResponse())
        }

        override fun cancel() = Unit

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = this

        private fun buildResponse(): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("test")
                .body(responseBody.toResponseBody())
                .build()
    }
}
