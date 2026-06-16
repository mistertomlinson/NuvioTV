package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorboxFileSelector @Inject constructor() {
    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): TorboxTorrentFileDto? {
        val fileNames = files.map { it.displayName() }
        val isBluray = fileNames.isBlurayDiscStructure()
        val playable = files.filter { it.isPlayableVideo(isBluray) }.sortedByDescending { it.size ?: 0L }
        if (playable.isEmpty()) return null

        val episodePatterns = buildDebridEpisodePatterns(
            season = season ?: resolve.season,
            episode = episode ?: resolve.episode
        )
        val names = resolve.specificDebridFileNames(episodePatterns)
        if (names.isNotEmpty()) {
            playable.firstDebridNameMatch(names) { it.displayName() }?.let { return it }
        }

        if (episodePatterns.isNotEmpty()) {
            playable.firstOrNull { file ->
                val fileName = file.displayName().lowercase()
                episodePatterns.any { pattern -> fileName.contains(pattern) }
            }?.let { return it }
        }

        resolve.fileIdx?.let { fileIdx ->
            playable.firstOrNull { it.id == fileIdx }?.let { return it }
        }

        return playable.maxByOrNull { it.size ?: 0L }
    }

    private fun TorboxTorrentFileDto.isPlayableVideo(isBluray: Boolean = false): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        if (mime.startsWith("video/")) {
            if (isBluray && displayName().lowercase().endsWith(".m2ts")) return false
            return true
        }
        val name = displayName().lowercase()
        if (isBluray && name.endsWith(".m2ts")) return false
        return name.hasDebridVideoExtension()
    }
}
