package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorboxDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: TorboxApi,
    private val fileSelector: TorboxFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().torboxApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            val create = api.createTorrent(
                authorization = authorization,
                magnet = magnet.toTextPart(),
                addOnlyIfCached = "true".toTextPart(),
                allowZip = "false".toTextPart()
            )
            android.util.Log.d("NuvioDebrid", "TB createTorrent code=${create.code()} success=${create.body()?.success} error=${create.body()?.error} detail=${create.body()?.detail}")
            val torrentId = create.extractTorrentId()
            if (torrentId == null) {
                android.util.Log.w("NuvioDebrid", "TB createTorrent no torrentId — returning ${create.toFailureForCreate()}")
                return create.toFailureForCreate()
            }

            val torrent = api.getTorrent(
                authorization = authorization,
                id = torrentId,
                bypassCache = true
            )
            android.util.Log.d("NuvioDebrid", "TB getTorrent code=${torrent.code()} files=${torrent.body()?.data?.files?.size}")
            torrent.body()?.data?.files?.take(5)?.forEach { f ->
                android.util.Log.d("NuvioDebrid", "TB file: id=${f.id} name=${f.displayName()} size=${f.size}")
            }
            if (!torrent.isSuccessful) {
                android.util.Log.w("NuvioDebrid", "TB getTorrent failed code=${torrent.code()}")
                return DirectDebridResolveResult.Stale
            }
            val files = torrent.body()?.data?.files.orEmpty()
            val file = fileSelector.selectFile(files, resolve, season, episode)
            if (file == null) {
                android.util.Log.w("NuvioDebrid", "TB fileSelector returned null from ${files.size} files for season=$season episode=$episode")
                return DirectDebridResolveResult.Stale
            }
            android.util.Log.d("NuvioDebrid", "TB selected file: id=${file.id} name=${file.displayName()} size=${file.size}")
            val fileId = file.id
            if (fileId == null) {
                android.util.Log.w("NuvioDebrid", "TB file has no id: ${file.displayName()}")
                return DirectDebridResolveResult.Stale
            }

            val link = api.requestDownloadLink(
                authorization = authorization,
                token = apiKey,
                torrentId = torrentId,
                fileId = fileId,
                zipLink = false,
                redirect = false,
                appendName = false
            )
            android.util.Log.d("NuvioDebrid", "TB requestDownloadLink code=${link.code()} hasUrl=${!link.body()?.data.isNullOrBlank()}")
            if (!link.isSuccessful) {
                android.util.Log.w("NuvioDebrid", "TB requestDownloadLink failed code=${link.code()}")
                return DirectDebridResolveResult.Stale
            }
            val url = link.body()?.data?.takeIf { it.isNotBlank() }
            if (url == null) {
                android.util.Log.w("NuvioDebrid", "TB requestDownloadLink returned blank url")
                return DirectDebridResolveResult.Stale
            }

            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() },
                videoSize = file.size
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }

    private fun Response<com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.extractTorrentId(): Int? {
        if (!isSuccessful) return null
        val body = body()
        if (body?.success == false) return null
        return body?.data?.resolvedTorrentId()
    }

    private fun Response<com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.toFailureForCreate(): DirectDebridResolveResult {
        return when (code()) {
            401, 403 -> DirectDebridResolveResult.Error
            409 -> DirectDebridResolveResult.NotCached
            else -> DirectDebridResolveResult.Stale
        }
    }

    private fun buildMagnetUri(resolve: StreamClientResolve): String? {
        val hash = resolve.infoHash?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            resolve.sources
                ?.filter { it.isNotBlank() }
                ?.forEach { source ->
                    append("&tr=")
                    append(java.net.URLEncoder.encode(source, "UTF-8"))
                }
        }
    }

    private fun String.toTextPart(): RequestBody =
        toRequestBody("text/plain".toMediaType())
}

sealed class DirectDebridResolveResult {
    data class Success(
        val url: String,
        val filename: String?,
        val videoSize: Long?
    ) : DirectDebridResolveResult()

    data object MissingApiKey : DirectDebridResolveResult()
    data object NotCached : DirectDebridResolveResult()
    data object Stale : DirectDebridResolveResult()
    data object Error : DirectDebridResolveResult()
}
