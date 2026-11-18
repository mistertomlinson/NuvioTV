package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve

data class DebridEpisodeSelectionContext(
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val seasonEpisodeTitles: List<String> = emptyList(),
    val requireTitleMatch: Boolean = false
) {
    // A context is immutable and shared by every candidate in a source batch.
    // Normalize its title evidence once rather than once per filename comparison.
    internal val normalizedTargetTitle: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        title.orEmpty().normalizedDebridText()
    }
    internal val strongTitlePatterns: List<Pair<String, String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        (listOf(normalizedTargetTitle) + seasonEpisodeTitles.map { it.normalizedDebridText() })
            .distinct()
            .filter { it.isStrongDebridEpisodeTitle() }
            .map { it to " $it " }
    }

    fun cacheKeyPart(): String {
        val target = normalizedTargetTitle
        val known = seasonEpisodeTitles
            .map { it.normalizedDebridText() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("~")
        return "$target|$known|titleRequired=$requireTitleMatch"
    }
}

internal enum class DebridEpisodeTitleMatch {
    TARGET,
    CONFLICT,
    UNKNOWN
}

internal fun DebridEpisodeSelectionContext?.withDebridEpisodeTarget(
    season: Int?,
    episode: Int?
): DebridEpisodeSelectionContext = DebridEpisodeSelectionContext(
    season = season ?: this?.season,
    episode = episode ?: this?.episode,
    title = this?.title,
    seasonEpisodeTitles = this?.seasonEpisodeTitles.orEmpty(),
    requireTitleMatch = this?.requireTitleMatch == true &&
        (season == null || season == this.season) && (episode == null || episode == this.episode)
)

private data class DebridEpisodeNumber(val season: Int, val episodes: IntRange)

// Parse complete numeric tokens, including combined episodes. Substring tests
// such as "contains(s06e08)" also match S06E080 and cannot establish identity.
private val debridEpisodeNumberPattern = Regex(
    """(?<![a-z0-9])(?:s(\d{1,3})[ ._-]*e|(\d{1,3})x)(\d{1,4})((?:(?:[ ._]*-[ ._]*e?|[ ._]*e)\d{1,4}(?=$|[^a-z0-9]|e\d))*)(?!\d)""",
    RegexOption.IGNORE_CASE
)
private val debridAdditionalEpisodePattern = Regex(
    """([ ._]*-[ ._]*e?|[ ._]*e)(\d{1,4})""",
    RegexOption.IGNORE_CASE
)
private val debridTextSeparatorPattern = Regex("[^a-z0-9]+")

private fun String.debridEpisodeNumbers(): List<DebridEpisodeNumber> =
    debridEpisodeNumberPattern.findAll(substringAfterLast('/').substringAfterLast('\\'))
        .flatMap { match ->
            val season = (match.groups[1]?.value ?: match.groupValues[2]).toInt()
            var previous = match.groupValues[3].toInt()
            buildList {
                add(DebridEpisodeNumber(season, previous..previous))
                debridAdditionalEpisodePattern.findAll(match.groupValues[4]).forEach { extra ->
                    val next = extra.groupValues[2].toInt()
                    val start = if ('-' in extra.groupValues[1]) previous else next
                    add(DebridEpisodeNumber(season, start..next))
                    previous = next
                }
            }
        }.toList()

internal fun String.matchesDebridEpisodeNumber(season: Int?, episode: Int?): Boolean =
    season != null && episode != null &&
        debridEpisodeNumbers().any { it.season == season && episode in it.episodes }

private fun String.normalizedDebridText(): String =
    lowercase()
        .replace("&", " and ")
        .replace(debridTextSeparatorPattern, " ")
        .trim()

private fun String.isStrongDebridEpisodeTitle(): Boolean =
    isNotBlank() && length >= if (' ' in this) 5 else 6

internal fun classifyDebridEpisodeFileName(
    fileName: String?,
    context: DebridEpisodeSelectionContext?
): DebridEpisodeTitleMatch {
    val titleMatch = classifyDebridEpisodeTitle(fileName, context)
    // An explicit title can establish an alternate episode order. Keep this
    // stronger evidence ahead of numeric matching (e.g. Electric Dreams).
    if (titleMatch != DebridEpisodeTitleMatch.UNKNOWN) return titleMatch

    val season = context?.season ?: return DebridEpisodeTitleMatch.UNKNOWN
    val episode = context.episode ?: return DebridEpisodeTitleMatch.UNKNOWN
    val numbers = fileName?.debridEpisodeNumbers().orEmpty()
    return if (numbers.isNotEmpty() && numbers.none { it.season == season && episode in it.episodes }) {
        DebridEpisodeTitleMatch.CONFLICT
    } else {
        DebridEpisodeTitleMatch.UNKNOWN
    }
}

private fun classifyDebridEpisodeTitle(
    fileName: String?,
    context: DebridEpisodeSelectionContext?
): DebridEpisodeTitleMatch {
    val selection = context ?: return DebridEpisodeTitleMatch.UNKNOWN
    val target = selection.normalizedTargetTitle
        .takeIf { it.isNotBlank() }
        ?: return DebridEpisodeTitleMatch.UNKNOWN

    val normalizedFile = fileName
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.')
        ?.normalizedDebridText()
        ?.takeIf { it.isNotBlank() }
        ?: return DebridEpisodeTitleMatch.UNKNOWN

    val paddedFile = " $normalizedFile "
    var matchesTarget = false

    // If a filename contains a different known episode title, do not let an
    // incidental target-title occurrence override that stronger conflict.
    for ((knownTitle, pattern) in selection.strongTitlePatterns) {
        if (pattern !in paddedFile) continue
        if (knownTitle != target) return DebridEpisodeTitleMatch.CONFLICT
        matchesTarget = true
    }

    return if (matchesTarget) {
        DebridEpisodeTitleMatch.TARGET
    } else {
        DebridEpisodeTitleMatch.UNKNOWN
    }
}

internal fun Stream.debridEpisodeTitleMatch(
    context: DebridEpisodeSelectionContext?
): DebridEpisodeTitleMatch {
    resolvedFilename?.takeIf { it.isNotBlank() }?.let {
        return classifyDebridEpisodeFileName(it, context)
    }
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

internal fun DebridEpisodeSelectionContext.withDebridEpisodeNumberingEvidence(
    streams: List<Stream>
): DebridEpisodeSelectionContext {
    if (requireTitleMatch || season == null || episode == null) return this
    if (!normalizedTargetTitle.isStrongDebridEpisodeTitle()) return this

    // A known other title at the requested number establishes conflicting
    // release numbering. Unknown or translated filenames cannot resolve that
    // conflict just by repeating the same number.
    val conflictingNumber = streams.any { stream ->
        listOfNotNull(
            stream.resolvedFilename,
            stream.behaviorHints?.filename,
            stream.clientResolve?.filename,
            stream.clientResolve?.stream?.raw?.filename
        ).any { filename ->
            filename.matchesDebridEpisodeNumber(season, episode) &&
                classifyDebridEpisodeTitle(filename, this) == DebridEpisodeTitleMatch.CONFLICT
        }
    }
    return if (conflictingNumber) copy(requireTitleMatch = true) else this
}

internal fun Stream.canAttemptDebridEpisodePlayback(
    context: DebridEpisodeSelectionContext?,
    willResolveFile: Boolean
): Boolean = willResolveFile || when (debridEpisodeTitleMatch(context)) {
    DebridEpisodeTitleMatch.TARGET -> true
    DebridEpisodeTitleMatch.UNKNOWN -> context?.requireTitleMatch != true
    DebridEpisodeTitleMatch.CONFLICT -> false
}

internal fun Stream.isDebridEpisodePlaybackCandidate(
    context: DebridEpisodeSelectionContext?
): Boolean = canAttemptDebridEpisodePlayback(
    context,
    willResolveFile = hasDebridFileToResolve()
)

private fun Stream.hasDebridFileToResolve(): Boolean =
    getStreamUrl().isNullOrBlank() && (isTorrent() || isDirectDebrid())

internal fun Stream.acceptsResolvedDebridEpisodeFile(
    fileName: String?,
    context: DebridEpisodeSelectionContext?
): Boolean {
    val resolvedMatch = classifyDebridEpisodeFileName(fileName, context)
    if (resolvedMatch == DebridEpisodeTitleMatch.CONFLICT) return false
    if (context?.requireTitleMatch == true) return resolvedMatch == DebridEpisodeTitleMatch.TARGET
    // A conflicting advertised episode can only be repaired by affirmative
    // target-title evidence from the actual selected file, not by losing its title.
    return debridEpisodeTitleMatch(context) != DebridEpisodeTitleMatch.CONFLICT ||
        resolvedMatch == DebridEpisodeTitleMatch.TARGET
}

internal fun List<Stream>.orderByDebridEpisodeTitleEvidence(
    context: DebridEpisodeSelectionContext?
): List<Stream> {
    if (context?.title.isNullOrBlank()) return this
    // Four stable buckets avoid running filename/title classification repeatedly
    // from a sort comparator. Existing provider/quality order stays intact per rank.
    val ranked = Array(4) { mutableListOf<Stream>() }
    for (stream in this) {
        val rank = when (stream.debridEpisodeTitleMatch(context)) {
            DebridEpisodeTitleMatch.TARGET -> 0
            DebridEpisodeTitleMatch.UNKNOWN -> if (context?.requireTitleMatch == true) 2 else 1
            DebridEpisodeTitleMatch.CONFLICT -> when {
                context?.requireTitleMatch != true -> 2
                stream.hasDebridFileToResolve() -> 1
                else -> 3
            }
        }
        ranked[rank].add(stream)
    }
    return ranked.flatMap { it }
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
        .replace(debridTextSeparatorPattern, " ")
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
