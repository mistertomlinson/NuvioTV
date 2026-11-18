package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.diagnostics.PlaybackTrace
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
        episode: Int?,
        selectionContext: DebridEpisodeSelectionContext? = null
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().torboxApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"
        PlaybackTrace.stream("torbox_resolve_start", stream,
            "requestedSeason" to season,
            "requestedEpisode" to episode,
            "requestedTitle" to selectionContext?.title,
            "requireTitleMatch" to selectionContext?.requireTitleMatch,
            "resolveFilename" to resolve.filename,
            "resolveFileIdx" to resolve.fileIdx
        )

        return try {
            val create = api.createTorrent(
                authorization = authorization,
                magnet = magnet.toTextPart(),
                addOnlyIfCached = "true".toTextPart(),
                allowZip = "false".toTextPart()
            )
            android.util.Log.d("NuvioDebrid", "TB createTorrent code=${create.code()} success=${create.body()?.success} error=${create.body()?.error} detail=${create.body()?.detail}")
            val torrentId = create.extractTorrentId()
            PlaybackTrace.record("torbox_create_result",
                "requestedSeason" to season, "requestedEpisode" to episode,
                "requestedTitle" to selectionContext?.title,
                "torrentHash" to PlaybackTrace.fingerprint(resolve.infoHash),
                "returnedTorrentHash" to PlaybackTrace.fingerprint(create.body()?.data?.hash?.trim()?.lowercase()),
                "torrentId" to torrentId,
                "status" to create.code(),
                "success" to create.body()?.success,
                "details" to create.body()?.detail
            )
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
            PlaybackTrace.record("torbox_file_list",
                "torrentId" to torrentId,
                "requestedSeason" to season,
                "requestedEpisode" to episode,
                "requestedTorrentHash" to PlaybackTrace.fingerprint(resolve.infoHash?.trim()?.lowercase()),
                "returnedTorrentHash" to PlaybackTrace.fingerprint(torrent.body()?.data?.hash?.trim()?.lowercase()),
                "returnedTorrentName" to torrent.body()?.data?.name,
                "fileCount" to files.size
            )
            files.filter { f -> f.displayName().lowercase().let {
                name -> listOf(".mkv", ".mp4", ".avi", ".webm", ".m4v", ".ts", ".mov").any { ext -> name.endsWith(ext) }
            } }.take(25).forEachIndexed { index, candidate ->
                PlaybackTrace.record("torbox_file_candidate",
                    "torrentId" to torrentId,
                    "candidatePosition" to index,
                    "fileId" to candidate.id,
                    "fileName" to candidate.displayName(),
                    "bytes" to candidate.size
                )
            }
            val file = fileSelector.selectFile(
                files = files,
                resolve = resolve,
                season = season,
                episode = episode,
                selectionContext = selectionContext
            )
            if (file == null) {
                PlaybackTrace.record("torbox_file_rejected",
                    "torrentId" to torrentId,
                    "requestedSeason" to season,
                    "requestedEpisode" to episode,
                    "requestedTitle" to selectionContext?.title,
                    "requireTitleMatch" to selectionContext?.requireTitleMatch,
                    "fileCount" to files.size
                )
                android.util.Log.w("NuvioDebrid", "TB fileSelector returned null from ${files.size} files for season=$season episode=$episode")
                return DirectDebridResolveResult.Stale
            }
            PlaybackTrace.record("torbox_file_selected",
                "torrentId" to torrentId,
                "requestedSeason" to season,
                "requestedEpisode" to episode,
                "requestedTitle" to selectionContext?.title,
                "fileId" to file.id,
                "filename" to file.displayName(),
                "bytes" to file.size,
                "resolveFileIdx" to resolve.fileIdx
            )
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

            PlaybackTrace.record("torbox_link_resolved",
                "torrentId" to torrentId,
                "fileId" to fileId,
                "filename" to file.displayName(),
                "linkHash" to PlaybackTrace.fingerprint(url),
                "requestedSeason" to season,
                "requestedEpisode" to episode
            )
            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() },
                videoSize = file.size
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            PlaybackTrace.record("torbox_resolve_exception",
                "requestedSeason" to season,
                "requestedEpisode" to episode,
                "exceptionType" to error.javaClass.simpleName
            )
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
