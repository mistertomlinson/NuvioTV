package com.nuvio.tv.data.trailer

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

private const val SMALL_AUDIO_CHUNK_BYTES = 64L * 1024L

/** Only retry an audio request that returned 403, and only once per open. */
internal fun shouldRetrySmallAudioChunk(
    status: Int,
    mime: String?,
    alreadySmall: Boolean,
    normalChunkSize: Long
): Boolean =
    status == 403 &&
        mime?.startsWith("audio/") == true &&
        !alreadySmall &&
        normalChunkSize > SMALL_AUDIO_CHUNK_BYTES

/**
 * A DataSource.Factory that wraps DefaultHttpDataSource and requests bounded
 * YouTube chunks with HTTP Range headers without modifying signed stream URLs.
 *
 * Only activates for googlevideo.com URLs; all other URLs pass through untouched.
 */
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE
) : DataSource.Factory {

    companion object {
        private const val TAG = "YTChunkedDS"
        /** 10 MB chunks – large enough to avoid too many requests, small enough to dodge throttle. */
        private const val CHUNK_SIZE = 10L * 1024 * 1024
    }

    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            // Match the UA used by the successful extractor CDN reachability probe.
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
            )
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }

    private class YoutubeChunkedDataSource(
        private val upstream: DefaultHttpDataSource,
        private val chunkSize: Long
    ) : DataSource {

        private var currentUri: Uri? = null
        private var isYouTubeStream = false
        private var totalContentLength = C.LENGTH_UNSET.toLong()
        private var streamContentLength: Long? = null
        private var useSmallAudioChunks = false
        private var currentChunkStart = 0L
        private var currentChunkEnd = 0L
        private var bytesReadInChunk = 0L
        private var originalDataSpec: DataSpec? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            val host = uri.host.orEmpty()
            isYouTubeStream = host.contains("googlevideo.com")

            if (!isYouTubeStream) {
                return upstream.open(dataSpec)
            }

            originalDataSpec = dataSpec
            useSmallAudioChunks = false
            currentChunkStart = dataSpec.position
            totalContentLength = dataSpec.length
            streamContentLength = uri.getQueryParameter("clen")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }

            return openNextChunk()
        }

        private fun openNextChunk(): Long {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")
            // Use the normal chunk size for working streams. After a 403 on
            // audio, fall back to the verified 64 KiB range for this stream.
            val effectiveChunkSize = if (useSmallAudioChunks) SMALL_AUDIO_CHUNK_BYTES else chunkSize
            val end = if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                minOf(currentChunkStart + effectiveChunkSize - 1, currentChunkStart + totalContentLength - 1)
            } else {
                currentChunkStart + effectiveChunkSize - 1
            }
            // Some trailers have audio streams shorter than the 10 MB chunk size.
            // Do not ask Googlevideo for bytes past the stream's declared length.
            currentChunkEnd = minOf(end, streamContentLength?.minus(1) ?: end)

            // Keep the signed Googlevideo URL unchanged; request a bounded
            // chunk with an HTTP Range header rather than a URL query parameter.
            val chunkedSpec = spec.buildUpon()
                .setHttpRequestHeaders(
                    spec.httpRequestHeaders + ("Range" to "bytes=$currentChunkStart-$currentChunkEnd")
                )
                .setPosition(0)
                .setLength(C.LENGTH_UNSET.toLong()) // let the server decide
                .build()

            bytesReadInChunk = 0
            try {
                upstream.open(chunkedSpec)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                val mime = spec.uri.getQueryParameter("mime")
                if (shouldRetrySmallAudioChunk(
                        e.responseCode, mime, useSmallAudioChunks, chunkSize
                    )
                ) {
                    Log.w(TAG, "Audio HTTP 403; retrying with 64 KiB chunks")
                    runCatching { upstream.close() }
                    useSmallAudioChunks = true
                    return openNextChunk()
                }
                throw e
            }
            return if (totalContentLength != C.LENGTH_UNSET.toLong()) totalContentLength else C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isYouTubeStream) {
                return upstream.read(buffer, offset, length)
            }

            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                // Current chunk exhausted — open the next one
                val chunkBytesReceived = bytesReadInChunk
                upstream.close()

                // If this chunk returned fewer bytes than requested, the stream is done
                if (chunkBytesReceived < (currentChunkEnd - currentChunkStart + 1)) {
                    return C.RESULT_END_OF_INPUT
                }

                currentChunkStart += chunkBytesReceived
                if (streamContentLength?.let { currentChunkStart >= it } == true) {
                    return C.RESULT_END_OF_INPUT
                }
                if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                    totalContentLength -= chunkBytesReceived
                    if (totalContentLength <= 0) {
                        return C.RESULT_END_OF_INPUT
                    }
                }

                return try {
                    openNextChunk()
                    upstream.read(buffer, offset, length)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open next chunk at $currentChunkStart: ${e.message}")
                    C.RESULT_END_OF_INPUT
                }
            }

            bytesReadInChunk += bytesRead
            return bytesRead
        }

        override fun getUri(): Uri? = upstream.uri ?: currentUri

        override fun close() {
            upstream.close()
            currentUri = null
            originalDataSpec = null
            streamContentLength = null
            useSmallAudioChunks = false
        }
    }
}
