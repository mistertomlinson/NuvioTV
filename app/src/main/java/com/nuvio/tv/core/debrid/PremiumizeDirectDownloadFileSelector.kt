package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumizeDirectDownloadFileSelector @Inject constructor() {
    fun selectFile(
        files: List<PremiumizeDirectDownloadFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
        selectionContext: DebridEpisodeSelectionContext? = null
    ): PremiumizeDirectDownloadFileDto? {
        val fileNames = files.map { it.displayName() }
        val isBluray = fileNames.isBlurayDiscStructure()
        val playable = files.filter { it.isPlayableVideo(isBluray) }
        if (playable.isEmpty()) return null

        val requestedSeason = season ?: resolve.season
        val requestedEpisode = episode ?: resolve.episode
        val isEpisode = requestedSeason != null && requestedEpisode != null
        val targetContext = DebridEpisodeSelectionContext(
            season = requestedSeason,
            episode = requestedEpisode,
            title = selectionContext?.title,
            seasonEpisodeTitles = selectionContext?.seasonEpisodeTitles.orEmpty()
        )
        // Numeric mismatches and known-other episode titles are ineligible,
        // including when a pack's fileIndex or filename metadata points at them.
        val eligible = playable.withoutDebridEpisodeTitleConflicts(targetContext) { it.displayName() }
        if (eligible.isEmpty()) return null

        eligible.firstDebridTargetEpisodeTitleMatch(targetContext) { it.displayName() }
            ?.let { return it }

        val episodePatterns = buildDebridEpisodePatterns(requestedSeason, requestedEpisode)
        val names = resolve.specificDebridFileNames(episodePatterns)
        if (names.isNotEmpty()) {
            eligible.firstDebridNameMatch(
                names, { it.displayName() }, requireExact = isEpisode
            )?.let { return it }
        }

        // A positive, bounded episode-token match is safe even in a season pack.
        // Multiple encodes of the same episode are not ambiguous: prefer the
        // largest matching encode, never the largest file in the whole pack.
        if (isEpisode) {
            eligible
                .filter {
                    classifyDebridEpisodeNumber(it.displayName(), requestedSeason, requestedEpisode) ==
                        DebridEpisodeTitleMatch.TARGET
                }
                .maxByOrNull { it.size ?: 0L }
                ?.let { return it }
        }

        // If a multi-video episode pack has no trustworthy match, do not
        // silently play its largest (often wrong) episode. Try another source.
        if (isEpisode) return if (playable.size == 1) eligible.singleOrNull() else null

        // Retain movie/single-video behavior when no episode was requested.
        return eligible.maxByOrNull { it.size ?: 0L }
    }

    private fun PremiumizeDirectDownloadFileDto.isPlayableVideo(isBluray: Boolean = false): Boolean {
        val name = displayName().lowercase()
        if (isBluray && name.endsWith(".m2ts")) return false
        return name.hasDebridVideoExtension()
    }
}
