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

// Match complete numeric episode tokens, never substrings (2x1 must not match 2x10).
// Only the basename is checked: a season-pack folder can contain episode tags.
private val debridSeasonEpisodeTag = Regex(
    """(?i)(?<![a-z0-9])s0*(\d{1,3})e0*(\d{1,4})(?!\d)"""
)
private val debridXEpisodeTag = Regex(
    """(?i)(?<![a-z0-9])0*(\d{1,3})x0*(\d{1,4})(?!\d)"""
)

internal fun classifyDebridEpisodeNumber(
    fileName: String?,
    season: Int?,
    episode: Int?
): DebridEpisodeTitleMatch {
    if (fileName.isNullOrBlank() || season == null || episode == null) {
        return DebridEpisodeTitleMatch.UNKNOWN
    }
    val basename = fileName.replace('\\', '/').substringAfterLast('/')
    val numbers = (debridSeasonEpisodeTag.findAll(basename) +
        debridXEpisodeTag.findAll(basename))
        .mapNotNull { match ->
            val fileSeason = match.groupValues[1].toIntOrNull()
            val fileEpisode = match.groupValues[2].toIntOrNull()
            if (fileSeason != null && fileEpisode != null) fileSeason to fileEpisode else null
        }
        .toList()
    if (numbers.isEmpty()) return DebridEpisodeTitleMatch.UNKNOWN
    return if (numbers.all { (fileSeason, fileEpisode) ->
        fileSeason == season && fileEpisode == episode
    }) DebridEpisodeTitleMatch.TARGET else DebridEpisodeTitleMatch.CONFLICT
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
    // Preserve the existing episode-title classification independently of
    // release numbering: some distributors use alternate episode orders.
    val target = selection.title
        ?.normalizedDebridText()
        ?.takeIf { it.isNotBlank() }
        ?: return DebridEpisodeTitleMatch.UNKNOWN
    val normalizedFile = fileName
        ?.replace('\\', '/')
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

    if (matchedTitles.any { it != target }) return DebridEpisodeTitleMatch.CONFLICT
    return if (target in matchedTitles) DebridEpisodeTitleMatch.TARGET else DebridEpisodeTitleMatch.UNKNOWN
}

/**
 * Combine independent title and numbering evidence for file safety.
 * An exact known episode title takes precedence when a release follows a
 * different episode ordering. If the title cannot identify the episode,
 * a complete season/episode token can still match or veto the file.
 */
internal fun classifyDebridEpisodeFileEvidence(
    fileName: String?,
    context: DebridEpisodeSelectionContext?
): DebridEpisodeTitleMatch {
    val titleEvidence = classifyDebridEpisodeFileName(fileName, context)
    if (titleEvidence != DebridEpisodeTitleMatch.UNKNOWN) return titleEvidence
    return classifyDebridEpisodeNumber(fileName, context?.season, context?.episode)
}

internal fun Stream.debridEpisodeTitleMatch(
    context: DebridEpisodeSelectionContext?
): DebridEpisodeTitleMatch {
    val matches = listOfNotNull(
        behaviorHints?.filename,
        clientResolve?.filename,
        clientResolve?.stream?.raw?.filename
    ).map { classifyDebridEpisodeFileEvidence(it, context) }

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
    if (context == null ||
        (context.title.isNullOrBlank() && (context.season == null || context.episode == null))
    ) return this
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
        classifyDebridEpisodeFileEvidence(displayName(it), context) !=
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
    return lower.hasDebridVideoExtension() || episodePatterns.any { pattern ->
        Regex("(?<![a-z0-9])${Regex.escape(pattern)}(?![0-9])")
            .containsMatchIn(lower)
    }
}

internal fun <T> List<T>.firstDebridNameMatch(
    names: List<String>,
    displayName: (T) -> String,
    requireExact: Boolean = false
): T? {
    if (requireExact) {
        // Episode packs must never use broad name-substring selection.
        for (name in names) {
            val matches = filter { displayName(it).normalizedDebridFileName() == name }
            if (matches.size == 1) return matches.single()
        }
        return null
    }
    return firstOrNull { item ->
        val fileName = displayName(item).normalizedDebridFileName()
        names.any { name -> fileName.contains(name) }
    }
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
