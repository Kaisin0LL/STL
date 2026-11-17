package com.screentranslator.gemini

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GeminiApiClient(
    private val key: suspend () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: HttpUrl = GeminiModel.ENDPOINT.toHttpUrl()
) : AutoCloseable {
    suspend fun generate(body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val payload = body.toString()
        if (payload.toByteArray().size > 3 * 1024 * 1024) throw GeminiException(GeminiFailure.TOO_LARGE)
        val apiKey = key()?.takeIf { it.isNotBlank() } ?: throw GeminiException(GeminiFailure.MISSING_KEY)
        if (apiKey.length > 512 || apiKey.any { it.code !in 33..126 }) throw GeminiException(GeminiFailure.KEY_ACCESS)
        for (attempt in 0..1) {
            ensureActive()
            val request = Request.Builder().url(endpoint).header("x-goog-api-key", apiKey)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val response = try { http.newCall(request).awaitBody() }
            catch (e: CancellationException) { throw e }
            catch (_: SocketTimeoutException) { throw GeminiException(GeminiFailure.TIMEOUT) }
            catch (e: IOException) {
                ensureActive()
                throw GeminiException(if (e is java.io.InterruptedIOException) GeminiFailure.TIMEOUT else GeminiFailure.NETWORK)
            }
            if (response.code in 500..599 && attempt == 0) { delay(500); continue }
            if (response.code !in 200..299) throw GeminiException(when (response.code) {
                401, 403 -> GeminiFailure.KEY_ACCESS
                404 -> GeminiFailure.MODEL_ACCESS
                429 -> GeminiFailure.QUOTA
                in 500..599 -> GeminiFailure.SERVER
                400 -> if (response.body.contains("API_KEY_INVALID") || response.body.contains("API_KEY_EXPIRED") || response.body.contains("UNAUTHENTICATED")) GeminiFailure.KEY_ACCESS else GeminiFailure.REQUEST
                else -> GeminiFailure.REQUEST
            })
            return@withContext GeminiProtocol.candidate(response.body)
        }
        throw GeminiException(GeminiFailure.SERVER)
    }
    private data class HttpResult(val code: Int, val body: String)
    private suspend fun Call.awaitBody(): HttpResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val source = it.body?.source() ?: throw GeminiException(GeminiFailure.INVALID_RESPONSE)
                        if (source.request(2L * 1024 * 1024 + 1)) throw GeminiException(GeminiFailure.TOO_LARGE)
                        val result = HttpResult(it.code, source.readUtf8())
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        })
    }
    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}
