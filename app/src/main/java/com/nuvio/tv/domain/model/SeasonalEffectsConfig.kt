package com.nuvio.tv.domain.model

/**
 * Production seasonal-effect appearance.
 *
 * The snow lab has been retired. These values are the approved visual
 * configuration and are intentionally fixed so an existing installation and
 * a fresh installation render the same effect.
 *
 * Only [enabled] remains user-configurable.
 */
data class SeasonalEffectsConfig(
    val enabled: Boolean = false,
    val startupMode: SnowStartupMode = SnowStartupMode.FALL_IN_FROM_TOP,
    val particleCount: Int = 120,
    val targetFps: Int = 30,
    val snowfallSpeed: SnowfallSpeed = SnowfallSpeed.FAST,
    val sizeProfile: SnowSizeProfile = SnowSizeProfile.MIXED,
    val wind: SnowWind = SnowWind.MEDIUM,
    val sway: SnowSway = SnowSway.OFF,
    val opacity: SnowOpacity = SnowOpacity.BRIGHT,
    val depthLayers: Int = 3,

    // Approved build uses every performance guardrail OFF.
    val reduceDensityWhileScrolling: Boolean = false,
    val reduceFpsWhileScrolling: Boolean = false,
    val freezeWhileScrolling: Boolean = false,
    val pauseDuringHeroTrailer: Boolean = false,

    // Approved build uses no whole-layer snow blur.
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
