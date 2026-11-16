package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream

/**
 * Opt-in exclusions for codecs advertised in stream/torrent metadata.
 *
 * A filename or stream label is only a hint, not authoritative media inspection.
 * Unknown codecs remain eligible. Re-check the resolved episode filename before playback.
 */
enum class VideoCodecOption(val id: String, val displayName: String) {
    VC1("vc1", "VC-1 / WMV3"),
    MPEG2("mpeg2", "MPEG-2"),
    MPEG4_PART2("mpeg4_part2", "MPEG-4 Part 2 / DivX / Xvid"),
    H264("h264", "H.264 / AVC"),
    HEVC("hevc", "H.265 / HEVC"),
    AV1("av1", "AV1"),
    VP9("vp9", "VP9"),
    VP8("vp8", "VP8"),
    VVC("vvc", "H.266 / VVC"),
    AVS2("avs2", "AVS2"),
    WMV_LEGACY("wmv_legacy", "WMV1 / WMV2"),
    DOLBY_VISION("dolby_vision", "Dolby Vision (video format)")
}

object VideoCodecFilter {
    val DEFAULT_HIDDEN_CODEC_IDS: Set<String> = setOf(VideoCodecOption.VC1.id)

    private val separator = "[\\s._-]*"
    private fun alias(vararg names: String): Regex =
        Regex("(?i)(?<![a-z0-9])(?:${names.joinToString("|")})(?![a-z0-9])")

    // Token boundaries prevent accidental matches inside episode names or unrelated words.
    private val signatures = mapOf(
        VideoCodecOption.VC1 to alias("vc${separator}1", "wvc1", "wmv3", "wmv9", "smpte${separator}421m"),
        VideoCodecOption.MPEG2 to alias("mpeg${separator}2", "mpg2", "h${separator}262", "m2v"),
        VideoCodecOption.MPEG4_PART2 to alias("mpeg${separator}4${separator}(?:part${separator}2|asp)", "xvid", "divx", "dx50", "mp4v"),
        VideoCodecOption.H264 to alias("h${separator}264", "x264", "avc", "avc1"),
        VideoCodecOption.HEVC to alias("h${separator}265", "x265", "hevc", "hvc1", "hev1"),
        VideoCodecOption.AV1 to alias("av1", "av01"),
        VideoCodecOption.VP9 to alias("vp9", "vp09"),
        VideoCodecOption.VP8 to alias("vp8", "vp08"),
        VideoCodecOption.VVC to alias("h${separator}266", "vvc"),
        VideoCodecOption.AVS2 to alias("avs${separator}2"),
        VideoCodecOption.WMV_LEGACY to alias("wmv1", "wmv2"),
        VideoCodecOption.DOLBY_VISION to alias("dolby${separator}vision", "dovi", "dv${separator}p?[578]")
    )

    fun excludedText(text: String?, hiddenCodecIds: Set<String>): Boolean {
        if (text.isNullOrBlank() || hiddenCodecIds.isEmpty()) return false
        return VideoCodecOption.entries.any { codec ->
            codec.id in hiddenCodecIds && signatures.getValue(codec).containsMatchIn(text)
        }
    }

    /**
     * Look at descriptive metadata only. Never search URLs, info hashes, or media titles
     * unrelated to the candidate: those can contain incidental codec-like strings.
     */
    fun excludedStream(stream: Stream, hiddenCodecIds: Set<String>): Boolean {
        if (hiddenCodecIds.isEmpty()) return false
        val hints = sequenceOf(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            stream.clientResolve?.filename,
            stream.clientResolve?.torrentName,
            stream.clientResolve?.stream?.raw?.filename,
            stream.clientResolve?.stream?.raw?.torrentName,
            stream.clientResolve?.stream?.raw?.parsed?.codec,
            stream.debridCacheStatus?.cachedName
        )
        return hints.any { excludedText(it, hiddenCodecIds) }
    }

    fun visibleStreams(streams: List<Stream>, hiddenCodecIds: Set<String>): List<Stream> =
        if (hiddenCodecIds.isEmpty()) streams
        else streams.filterNot { excludedStream(it, hiddenCodecIds) }
}
