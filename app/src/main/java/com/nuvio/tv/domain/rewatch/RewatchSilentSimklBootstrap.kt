package com.nuvio.tv.domain.rewatch

import kotlinx.coroutines.CancellationException

/**
 * Client-side, silent upgrade bridge for an ALREADY signed-in Simkl profile.
 *
 * It never starts PIN authorization, modifies existing Simkl credentials,
 * or writes Simkl watched/scrobble history.
 *
 * The application will provide a captured profile-scoped SimklAuthorization,
 * a persisted random UUID per Nuvio profile, an HTTPS replay-only exchange
 * and a dedicated replay credential store. This is an isolated component:
 * network enablement is intentionally not wired to Home/player yet.
 */
internal data class ExistingSimklRewatchAuthorization(
    val profileId: Int,
    val accessToken: String,
    val accountId: Long?
)

internal data class ReplayBootstrapCredential(
    val token: String,
    val deviceId: String,
    val expiresAtEpochMs: Long
)

internal enum class ReplayBootstrapOutcome {
    NO_EXISTING_SIMKL_LOGIN,
    INVALID_LOCAL_DEVICE_ID,
    UNAVAILABLE,
    STALE_PROFILE_OR_AUTHORIZATION,
    INVALID_SERVER_CREDENTIAL,
    CONNECTED
}

internal fun isValidRewatchInstallationUuid(value: String?): Boolean =
    value != null && Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    ).matches(value)

private fun isValidReplayBootstrapCredential(
    credential: ReplayBootstrapCredential,
    nowEpochMs: Long
): Boolean =
    Regex("[a-f0-9]{64}").matches(credential.token) &&
        credential.deviceId.startsWith("simkl-device:") &&
        credential.deviceId.length in 20..160 &&
        credential.expiresAtEpochMs > nowEpochMs

internal class RewatchSilentSimklBootstrap(
    private val currentAuthorization: () -> ExistingSimklRewatchAuthorization?,
    private val installationIdForProfile: (Int) -> String?,
    private val exchange: suspend (
        simklAccessToken: String,
        installationId: String
    ) -> ReplayBootstrapCredential?,
    private val persist: suspend (
        profileId: Int,
        receipt: ReplayBootstrapCredential
    ) -> Unit,
    private val nowEpochMs: () -> Long
) {
    suspend fun connectExistingProfile(): ReplayBootstrapOutcome {
        val initial = currentAuthorization()?.takeIf {
            it.profileId > 0 && it.accessToken.isNotBlank() &&
                (it.accountId == null || it.accountId > 0)
        } ?: return ReplayBootstrapOutcome.NO_EXISTING_SIMKL_LOGIN

        val installationId = installationIdForProfile(initial.profileId)
            ?.takeIf(::isValidRewatchInstallationUuid)
            ?: return ReplayBootstrapOutcome.INVALID_LOCAL_DEVICE_ID

        val receipt = try {
            exchange(initial.accessToken, installationId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }

        // New profile, new Simkl token or changed account? Do not publish
        // an outdated credential. Device-local integer profile IDs cannot
        // be used as the VPS authorization principal.
        if (currentAuthorization() != initial) {
            return ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION
        }
        if (receipt == null) return ReplayBootstrapOutcome.UNAVAILABLE
        if (!isValidReplayBootstrapCredential(receipt, nowEpochMs())) {
            return ReplayBootstrapOutcome.INVALID_SERVER_CREDENTIAL
        }

        persist(initial.profileId, receipt)
        return ReplayBootstrapOutcome.CONNECTED
    }
}
