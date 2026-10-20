package com.nuvio.tv.domain.model

/**
 * Runtime-tunable Home seasonal effects. The intentionally expensive options
 * live in the same model so one lab build can A/B combinations without another
 * install/AOT cycle.
 */
data class SeasonalEffectsConfig(
    val enabled: Boolean = false,
    val startupMode: SnowStartupMode = SnowStartupMode.PRE_FILLED,
    val particleCount: Int = 60,
    val targetFps: Int = 30,
    val snowfallSpeed: SnowfallSpeed = SnowfallSpeed.NORMAL,
    val sizeProfile: SnowSizeProfile = SnowSizeProfile.MIXED,
    val wind: SnowWind = SnowWind.LIGHT,
    val sway: SnowSway = SnowSway.MEDIUM,
    val opacity: SnowOpacity = SnowOpacity.NORMAL,
    val depthLayers: Int = 3,
    val reduceDensityWhileScrolling: Boolean = true,
    val reduceFpsWhileScrolling: Boolean = true,
    val freezeWhileScrolling: Boolean = false,
    val pauseDuringHeroTrailer: Boolean = true,
    val blur: SnowBlur = SnowBlur.OFF
) {
    companion object {
        val Recommended = SeasonalEffectsConfig()
    }
}

enum class SnowStartupMode {
    PRE_FILLED,
    FALL_IN_FROM_TOP,
    SOFT_FADE_IN
}

enum class SnowfallSpeed(val multiplier: Float) {
    SLOW(0.65f),
    NORMAL(1.0f),
    FAST(1.45f),
    BLIZZARD(2.0f)
}

enum class SnowSizeProfile {
    TINY,
    BALANCED,
    LARGE,
    MIXED
}

enum class SnowWind(val pixelsPerSecondAt1080p: Float) {
    OFF(0f),
    LIGHT(18f),
    MEDIUM(42f),
    STRONG(82f)
}

enum class SnowSway(val amplitudeAt1080p: Float) {
    OFF(0f),
    LIGHT(8f),
    MEDIUM(18f),
    STRONG(34f)
}

enum class SnowOpacity(val multiplier: Float) {
    SUBTLE(0.68f),
    NORMAL(1.0f),
    BRIGHT(1.22f)
}

enum class SnowBlur(val radiusDp: Float) {
    OFF(0f),
    LIGHT(0.8f),
    MEDIUM(1.6f),
    HEAVY(3.0f)
}
