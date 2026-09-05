package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Immutable

internal const val CREDIT_ANALYZER_TRIGGER_POSITION_MS = 5 * 60_000L

enum class CreditTimingStatus {
    NOT_STARTED,
    RUNNING,
    INTRO_DB_AVAILABLE,
    COMPLETE,
    FALLBACK
}

@Immutable
data class CreditTimingUiState(
    val status: CreditTimingStatus = CreditTimingStatus.NOT_STARTED,
    val creditsStartMs: Long? = null,
    val finalCreditsStartMs: Long? = null,
    val hasPostCreditScenes: Boolean = false,
    val confidence: Double? = null
) {
    val isAuthoritative: Boolean
        get() = status == CreditTimingStatus.COMPLETE && finalCreditsStartMs != null
}

@Immutable
data class PostPlayRecommendation(
    val id: String,
    val contentType: String,
    val title: String,
    val backdrop: String?,
    val poster: String?,
    val description: String?,
    val releaseInfo: String?,
    val genres: List<String>
)

/**
 * Returns null when legacy IntroDB/percentage timing should decide. A running
 * analyzer uses a cross-release estimate when one is available and otherwise
 * returns false so fallback UI cannot appear prematurely.
 */
internal fun authoritativeEndActionDecision(
    timing: CreditTimingUiState,
    positionMs: Long
): Boolean? {
    if (timing.status == CreditTimingStatus.RUNNING && timing.finalCreditsStartMs == null) {
        return false
    }
    return timing.finalCreditsStartMs?.let { positionMs >= it }
}
