package com.nuvio.tv.data.local

import com.nuvio.tv.domain.rewatch.RewatchHttpExchange
import com.nuvio.tv.domain.rewatch.RewatchHttpResponse
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Dedicated replay-only client. No shared Simkl interceptors/caches/loggers.
 *
 * All requests are HTTPS, with redirects disabled. Never log credentials or
 * response bodies. This is not currently wired to Nuvio app startup.
 */
internal object AndroidRewatchVpsHttpExchange : RewatchHttpExchange {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String
    ): RewatchHttpResponse = withContext(Dispatchers.IO) {
        require(url.startsWith("https://nuviorewatch.duckdns.org/"))
        require(method == "GET" || method == "POST")
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = when (method) {
            "GET" -> builder.get().build()
            else -> builder.post(
                body.toRequestBody("application/json; charset=utf-8".toMediaType())
            ).build()
        }
        client.newCall(request).execute().use { response ->
            val bytes = ByteArrayOutputStream()
            response.body?.byteStream()?.use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    if (bytes.size() + count > 64 * 1024) {
                        throw IllegalStateException("Replay HTTP response exceeds limit")
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            RewatchHttpResponse(
                status = response.code,
                body = bytes.toByteArray().toString(Charsets.UTF_8)
            )
        }
    }
}
