package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve

data class DebridEpisodeSelectionContext(
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val seasonEpisodeTitles: List<String> = emptyList()
) {
    fun cacheKeyPart(): String {
        val target = title.orEmpty().normalizedDebridText()
        val known = seasonEpisodeTitles
            .map { it.normalizedDebridText() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("~")
        return "$target|$known"
    }
}

internal enum class DebridEpisodeTitleMatch {
    TARGET,
    CONFLICT,
    UNKNOWN
}

private fun String.normalizedDebridText(): String =
    lowercase()
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

private fun String.containsStrongDebridEpisodeTitle(normalizedTitle: String): Boolean {
    if (normalizedTitle.isBlank()) return false
    val words = normalizedTitle.split(' ').filter { it.isNotBlank() }
    if (words.size == 1 && normalizedTitle.length < 6) return false
    if (words.size > 1 && normalizedTitle.length < 5) return false
    return " $this ".contains(" $normalizedTitle ")
}

internal fun classifyDebridEpisodeFileName(
    fileName: String?,
    context: DebridEpisodeSelectionContext?
): DebridEpisodeTitleMatch {
    val selection = context ?: return DebridEpisodeTitleMatch.UNKNOWN
    val target = selection.title
        ?.normalizedDebridText()
        ?.takeIf { it.isNotBlank() }
        ?: return DebridEpisodeTitleMatch.UNKNOWN

    val normalizedFile = fileName
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.')
        ?.normalizedDebridText()
        ?.takeIf { it.isNotBlank() }
        ?: return DebridEpisodeTitleMatch.UNKNOWN

    val knownTitles = buildList {
        selection.title?.let(::add)
        addAll(selection.seasonEpisodeTitles)
    }
        .map { it.normalizedDebridText() }
        .filter { it.isNotBlank() }
        .distinct()

    val matchedTitles = knownTitles
        .filter { normalizedFile.containsStrongDebridEpisodeTitle(it) }

    if (matchedTitles.isEmpty()) {
        return DebridEpisodeTitleMatch.UNKNOWN
    }

    // If a filename contains a different known episode title, do not let an
    // incidental target-title occurrence override that stronger conflict.
    if (matchedTitles.any { it != target }) {
        return DebridEpisodeTitleMatch.CONFLICT
    }

    return if (target in matchedTitles) {
        DebridEpisodeTitleMatch.TARGET
    } else {
        DebridEpisodeTitleMatch.UNKNOWN
    }
}

internal fun Stream.debridEpisodeTitleMatch(
    context: DebridEpisodeSelectionContext?
): DebridEpisodeTitleMatch {
    val matches = listOfNotNull(
        behaviorHints?.filename,
        clientResolve?.filename,
        clientResolve?.stream?.raw?.filename
    ).map { classifyDebridEpisodeFileName(it, context) }

    return when {
        // Be conservative when different stream filename fields disagree.
        // An explicit known-other-episode title is stronger evidence than
        // another field claiming the requested title.
        DebridEpisodeTitleMatch.CONFLICT in matches -> DebridEpisodeTitleMatch.CONFLICT
        DebridEpisodeTitleMatch.TARGET in matches -> DebridEpisodeTitleMatch.TARGET
        else -> DebridEpisodeTitleMatch.UNKNOWN
    }
}

internal fun List<Stream>.orderByDebridEpisodeTitleEvidence(
    context: DebridEpisodeSelectionContext?
): List<Stream> {
    if (context?.title.isNullOrBlank()) return this
    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<Stream>> {
                when (it.value.debridEpisodeTitleMatch(context)) {
                    DebridEpisodeTitleMatch.TARGET -> 0
                    DebridEpisodeTitleMatch.UNKNOWN -> 1
                    DebridEpisodeTitleMatch.CONFLICT -> 2
                }
            }.thenBy { it.index }
        )
        .map { it.value }
}

internal fun <T> List<T>.firstDebridTargetEpisodeTitleMatch(
    context: DebridEpisodeSelectionContext?,
    displayName: (T) -> String
): T? =
    firstOrNull {
        classifyDebridEpisodeFileName(displayName(it), context) ==
            DebridEpisodeTitleMatch.TARGET
    }

internal fun <T> List<T>.withoutDebridEpisodeTitleConflicts(
    context: DebridEpisodeSelectionContext?,
    displayName: (T) -> String
): List<T> =
    filter {
        classifyDebridEpisodeFileName(displayName(it), context) !=
            DebridEpisodeTitleMatch.CONFLICT
    }

internal fun String.normalizedDebridFileName(): String =
    substringAfterLast('/')
        .substringBeforeLast('.')
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

internal fun StreamClientResolve.specificDebridFileNames(episodePatterns: List<String>): List<String> {
    val raw = stream?.raw
    return listOfNotNull(
        filename,
        raw?.filename,
        raw?.parsed?.rawTitle?.takeIf { it.looksSpecificForDebridSelection(episodePatterns) },
        torrentName?.takeIf { it.looksSpecificForDebridSelection(episodePatterns) }
    )
        .map { it.normalizedDebridFileName() }
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun String.looksSpecificForDebridSelection(episodePatterns: List<String>): Boolean {
    val lower = lowercase()
    return lower.hasDebridVideoExtension() || episodePatterns.any { pattern -> lower.contains(pattern) }
}

internal fun <T> List<T>.firstDebridNameMatch(
    names: List<String>,
    displayName: (T) -> String
): T? =
    firstOrNull { item ->
        val fileName = displayName(item).normalizedDebridFileName()
        names.any { name -> fileName.contains(name) }
    }

internal fun buildDebridEpisodePatterns(season: Int?, episode: Int?): List<String> {
    if (season == null || episode == null) return emptyList()
    val seasonTwo = season.toString().padStart(2, '0')
    val episodeTwo = episode.toString().padStart(2, '0')
    return listOf(
        "s${seasonTwo}e$episodeTwo",
        "${season}x$episodeTwo",
        "${season}x$episode"
    )
}

internal fun String.hasDebridVideoExtension(): Boolean =
    debridVideoExtensions.any { endsWith(it) }

private val debridVideoExtensions = setOf(
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".m4v",
    ".ts",
    ".m2ts",
    ".wmv",
    ".flv"
)

// Blu-ray disc structure file extensions — present when a torrent is a full disc rip.
// If any of these exist alongside .m2ts files, the .m2ts files are disc segments
// that ExoPlayer cannot play directly.
internal val blurayDiscExtensions = setOf(
    ".bdjo", ".clpi", ".mpls", ".ssif"
)

internal fun List<String>.isBlurayDiscStructure(): Boolean =
    any { name -> blurayDiscExtensions.any { ext -> name.lowercase().endsWith(ext) } }
