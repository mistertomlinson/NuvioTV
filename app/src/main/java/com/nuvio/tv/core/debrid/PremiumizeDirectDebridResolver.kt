package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumizeDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: PremiumizeApi,
    private val fileSelector: PremiumizeDirectDownloadFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().premiumizeApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val source = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: stream.getStreamUrl()?.takeIf { it.isNotBlank() }
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            android.util.Log.d("NuvioDebrid", "PM directDownload source=${source.take(60)}")
            val response = api.directDownload(authorization, source)
            android.util.Log.d("NuvioDebrid", "PM directDownload code=${response.code()} status=${response.body()?.status}")
            if (!response.isSuccessful) {
                android.util.Log.w("NuvioDebrid", "PM directDownload failed code=${response.code()}")
                return when (response.code()) {
                    401, 403 -> DirectDebridResolveResult.Error
                    else -> DirectDebridResolveResult.Stale
                }
            }
            val body = response.body()
            if (body == null) {
                android.util.Log.w("NuvioDebrid", "PM directDownload null body")
                return DirectDebridResolveResult.Stale
            }
            if (body.status.equals("error", ignoreCase = true)) {
                val message = listOfNotNull(body.message, body.code).joinToString(" ").lowercase()
                android.util.Log.w("NuvioDebrid", "PM directDownload error status message=$message")
                return if (message.contains("cache") || message.contains("not found")) {
                    DirectDebridResolveResult.NotCached
                } else {
                    DirectDebridResolveResult.Stale
                }
            }
            val file = fileSelector.selectFile(
                files = body.content.orEmpty(),
                resolve = resolve,
                season = season,
                episode = episode
            )
            if (file == null) {
                android.util.Log.w("NuvioDebrid", "PM fileSelector returned null from ${body.content?.size} files")
                return DirectDebridResolveResult.Stale
            }
            val url = file.link?.takeIf { it.isNotBlank() }
            if (url == null) {
                android.util.Log.w("NuvioDebrid", "PM file has no link: ${file.path}")
                return DirectDebridResolveResult.Stale
            }
            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() } ?: stream.behaviorHints?.filename,
                videoSize = file.size ?: stream.behaviorHints?.videoSize
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }

    private fun buildMagnetUri(resolve: StreamClientResolve): String? {
        val hash = resolve.infoHash?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            resolve.sources
                ?.mapNotNull { it.toTrackerUrlOrNull() }
                ?.distinct()
                ?.forEach { source ->
                    append("&tr=")
                    append(java.net.URLEncoder.encode(source, "UTF-8"))
                }
        }
    }

    private fun String.toTrackerUrlOrNull(): String? {
        val value = trim()
        if (value.isBlank() || value.startsWith("dht:", ignoreCase = true)) return null
        return value.removePrefix("tracker:").trim().takeIf { it.isNotBlank() }
    }
}
