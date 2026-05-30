package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

object BackendTranscriptClient {
    data class Config(
        val baseUrl: String,
        val apiToken: String,
        val enabled: Boolean = true,
    )

    enum class Status {
        ENQUEUED,
        SKIPPED,
        DISABLED,
        SUCCESS,
        ERROR,
    }

    data class Result(
        val status: Status,
        val code: Int? = null,
        val error: String? = null,
    ) {
        val isTerminal: Boolean
            get() = status != Status.ENQUEUED
    }

    private val defaultCallFactory: Call.Factory = OkHttpClient()

    fun enqueueUpload(
        text: String,
        capturedAt: String,
        metadata: JSONObject = JSONObject(),
        config: Config,
        callFactory: Call.Factory = defaultCallFactory,
        logger: (String) -> Unit = {},
        callback: (Result) -> Unit,
    ): Result {
        if (!config.enabled || config.baseUrl.isBlank() || config.apiToken.isBlank()) {
            val result = Result(Status.DISABLED, error = "Backend upload disabled")
            logger(result.error!!)
            callback(result)
            return result
        }

        if (text.isBlank()) {
            val result = Result(Status.SKIPPED, error = "Blank transcript skipped")
            logger(result.error!!)
            callback(result)
            return result
        }

        val request = buildRequest(text, capturedAt, metadata, config)
        callFactory.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val result = Result(Status.ERROR, error = e.message ?: "Network error")
                logger(result.error!!)
                callback(result)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = parseHttpResult(it.code, it.body?.string())
                    if (result.status == Status.ERROR && result.error != null) {
                        logger(result.error)
                    }
                    callback(result)
                }
            }
        })

        return Result(Status.ENQUEUED)
    }

    internal fun buildRequest(
        text: String,
        capturedAt: String,
        metadata: JSONObject,
        config: Config,
    ): Request {
        val body = buildPayload(text, capturedAt, metadata)
            .toString()
            .toRequestBody("application/json".toMediaType())

        return Request.Builder()
            .url(buildItemsUrl(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiToken}")
            .post(body)
            .build()
    }

    internal fun buildItemsUrl(baseUrl: String): String =
        "${baseUrl.trim().removeSuffix("/")}/items"

    internal fun buildPayload(text: String, capturedAt: String, metadata: JSONObject): JSONObject =
        JSONObject().apply {
            put("text", text)
            put("captured_at", capturedAt)
            put("metadata", JSONObject(metadata.toString()))
        }

    internal fun parseHttpResult(code: Int, body: String?): Result {
        if (code != 201) {
            return Result(
                status = Status.ERROR,
                code = code,
                error = parseErrorMessage(code, body),
            )
        }

        if (body.isNullOrBlank()) {
            return Result(Status.SUCCESS, code = code)
        }

        return try {
            when (JSONTokener(body).nextValue()) {
                is JSONObject, is JSONArray -> Result(Status.SUCCESS, code = code)
                else -> Result(Status.ERROR, code = code, error = "Malformed success response")
            }
        } catch (e: Exception) {
            Result(Status.ERROR, code = code, error = e.message ?: "Malformed success response")
        }
    }

    internal fun parseErrorMessage(code: Int, body: String?): String {
        if (body.isNullOrBlank()) {
            return "HTTP $code"
        }

        return try {
            val obj = JSONObject(body)
            when {
                obj.has("error") && obj.get("error") is JSONObject ->
                    obj.getJSONObject("error").optString("message", "HTTP $code")
                obj.has("error") -> obj.optString("error", "HTTP $code")
                obj.has("message") -> obj.optString("message", "HTTP $code")
                else -> "HTTP $code"
            }
        } catch (e: Exception) {
            e.message ?: "HTTP $code"
        }
    }
}
