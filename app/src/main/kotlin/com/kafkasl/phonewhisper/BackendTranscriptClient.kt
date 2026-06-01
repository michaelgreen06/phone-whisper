package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

object BackendTranscriptClient {
    private const val OUTBOX_DIRECTORY = "pending_transcript_uploads"
    private val PAYLOAD_FIELDS = setOf("client_capture_id", "text", "captured_at", "metadata")
    private val METADATA_FIELDS = setOf(
        "platform",
        "app_version",
        "android_sdk",
        "transcription_engine",
    )

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
        val clientCaptureId: String? = null,
    ) {
        val isTerminal: Boolean
            get() = status != Status.ENQUEUED
    }

    private val defaultCallFactory: Call.Factory = OkHttpClient()
    private val flushLock = Any()
    private var flushInProgress = false

    fun enqueueAndFlush(
        filesDir: File,
        text: String,
        capturedAt: String,
        metadata: JSONObject,
        config: Config,
        clientCaptureId: String = UUID.randomUUID().toString(),
        callFactory: Call.Factory = defaultCallFactory,
        logger: (String) -> Unit = {},
        callback: (Result) -> Unit = {},
    ): Result {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            return Result(Status.SKIPPED, error = "Blank transcript skipped").also(callback)
        }

        val payload = buildPayload(clientCaptureId, trimmedText, capturedAt, metadata)
        try {
            persistPayload(filesDir, payload)
        } catch (e: Exception) {
            val result = Result(
                status = Status.ERROR,
                error = e.message ?: "Unable to persist transcript",
                clientCaptureId = clientCaptureId,
            )
            logger(result.error!!)
            callback(result)
            return result
        }

        logger("Queued transcript capture $clientCaptureId")
        flush(filesDir, config, callFactory, logger, callback)
        return Result(Status.ENQUEUED, clientCaptureId = clientCaptureId)
    }

    fun flush(
        filesDir: File,
        config: Config,
        callFactory: Call.Factory = defaultCallFactory,
        logger: (String) -> Unit = {},
        callback: (Result) -> Unit = {},
    ): Result {
        if (!config.enabled || config.baseUrl.isBlank() || config.apiToken.isBlank()) {
            return Result(Status.DISABLED, error = "Backend upload disabled").also(callback)
        }

        synchronized(flushLock) {
            if (flushInProgress) {
                return Result(Status.SKIPPED, error = "Backend flush already in progress").also(callback)
            }
            flushInProgress = true
        }

        processNextPendingFile(
            filesDir = filesDir,
            config = config,
            callFactory = callFactory,
            logger = logger,
            malformedFiles = mutableSetOf(),
            callback = callback,
        )
        return Result(Status.ENQUEUED)
    }

    private fun processNextPendingFile(
        filesDir: File,
        config: Config,
        callFactory: Call.Factory,
        logger: (String) -> Unit,
        malformedFiles: MutableSet<String>,
        callback: (Result) -> Unit,
    ) {
        val pendingFile = pendingFiles(filesDir)
            .firstOrNull { it.absolutePath !in malformedFiles }
            ?: return completeFlush(Result(Status.SUCCESS), callback)

        val payload = try {
            readPendingPayload(pendingFile)
        } catch (e: Exception) {
            malformedFiles += pendingFile.absolutePath
            logger("Retaining malformed local transcript ${pendingFile.name}: ${e.message}")
            processNextPendingFile(filesDir, config, callFactory, logger, malformedFiles, callback)
            return
        }

        val request = try {
            buildRequest(payload, config)
        } catch (e: Exception) {
            val result = Result(Status.ERROR, error = e.message ?: "Unable to build upload request")
            logger(result.error!!)
            completeFlush(result, callback)
            return
        }

        try {
            callFactory.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val result = Result(Status.ERROR, error = e.message ?: "Network error")
                    logger("Retaining ${pendingFile.name}: ${result.error}")
                    completeFlush(result, callback)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = parseHttpResult(it.code, it.body?.string())
                        if (result.status != Status.SUCCESS) {
                            logger("Retaining ${pendingFile.name}: ${result.error}")
                            completeFlush(result, callback)
                            return
                        }
                    }

                    if (!pendingFile.delete()) {
                        val result = Result(
                            Status.ERROR,
                            error = "Uploaded ${pendingFile.name} but could not delete local outbox file",
                        )
                        logger(result.error!!)
                        completeFlush(result, callback)
                        return
                    }

                    logger("Uploaded and removed ${pendingFile.name}")
                    processNextPendingFile(filesDir, config, callFactory, logger, malformedFiles, callback)
                }
            })
        } catch (e: Exception) {
            val result = Result(Status.ERROR, error = e.message ?: "Unable to enqueue upload")
            logger("Retaining ${pendingFile.name}: ${result.error}")
            completeFlush(result, callback)
        }
    }

    private fun completeFlush(result: Result, callback: (Result) -> Unit) {
        synchronized(flushLock) {
            flushInProgress = false
        }
        callback(result)
    }

    internal fun buildRequest(payload: JSONObject, config: Config): Request {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(buildItemsUrl(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiToken}")
            .post(body)
            .build()
    }

    internal fun buildItemsUrl(baseUrl: String): String =
        "${baseUrl.trim().removeSuffix("/")}/items"

    internal fun buildPayload(
        clientCaptureId: String,
        text: String,
        capturedAt: String,
        metadata: JSONObject,
    ): JSONObject =
        JSONObject().apply {
            put("client_capture_id", clientCaptureId)
            put("text", text)
            put("captured_at", capturedAt)
            put("metadata", JSONObject(metadata.toString()))
        }

    internal fun persistPayload(filesDir: File, payload: JSONObject): File {
        val directory = outboxDirectory(filesDir)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create transcript outbox")
        }

        val clientCaptureId = requireString(payload, "client_capture_id")
        UUID.fromString(clientCaptureId)
        val target = File(directory, "$clientCaptureId.json")
        if (target.exists()) {
            throw IOException("Transcript capture already exists: $clientCaptureId")
        }

        val temporary = File(directory, ".$clientCaptureId.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.writer(Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                    output.fd.sync()
                }
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Unable to atomically persist transcript capture $clientCaptureId")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return target
    }

    internal fun pendingFiles(filesDir: File): List<File> =
        outboxDirectory(filesDir)
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: emptyList()

    internal fun readPendingPayload(file: File): JSONObject {
        val payload = JSONObject(file.readText())
        if (payload.keys().asSequence().toSet() != PAYLOAD_FIELDS) {
            throw IllegalArgumentException("Unexpected transcript payload fields")
        }

        val clientCaptureId = requireString(payload, "client_capture_id")
        UUID.fromString(clientCaptureId)
        if (file.name != "$clientCaptureId.json") {
            throw IllegalArgumentException("Transcript filename does not match client_capture_id")
        }

        val text = requireString(payload, "text")
        val capturedAt = requireString(payload, "captured_at")
        Instant.parse(capturedAt)
        val metadata = payload.optJSONObject("metadata")
            ?: throw IllegalArgumentException("metadata must be an object")
        if (metadata.keys().asSequence().toSet() != METADATA_FIELDS) {
            throw IllegalArgumentException("Unexpected transcript metadata fields")
        }
        if (requireString(metadata, "platform") != "android") {
            throw IllegalArgumentException("platform must be android")
        }
        requireString(metadata, "app_version")
        if (metadata.opt("android_sdk") !is Number) {
            throw IllegalArgumentException("android_sdk must be a number")
        }
        requireString(metadata, "transcription_engine")

        return buildPayload(clientCaptureId, text, capturedAt, metadata)
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
            return Result(Status.ERROR, code = code, error = "Malformed success response")
        }

        return try {
            val response = JSONObject(body)
            requireString(response, "capture_id")
            requireString(response, "routed_item_id")
            val duplicate = response.opt("duplicate")
            if (duplicate !is Boolean) {
                Result(Status.ERROR, code = code, error = "Malformed success response")
            } else {
                Result(Status.SUCCESS, code = code)
            }
        } catch (e: Exception) {
            Result(Status.ERROR, code = code, error = "Malformed success response")
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
        } catch (_: Exception) {
            "HTTP $code"
        }
    }

    private fun outboxDirectory(filesDir: File): File = File(filesDir, OUTBOX_DIRECTORY)

    private fun requireString(objectValue: JSONObject, name: String): String {
        val value = objectValue.opt(name)
        if (value !is String || value.isBlank()) {
            throw IllegalArgumentException("$name must be a non-blank string")
        }
        return value
    }
}
