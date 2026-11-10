package com.nuvio.tv.domain.rewatch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchSilentSimklBootstrapTest {
    private val uuid = "e6371301-aab6-44c8-9f19-be7c20a50ea1"
    private val existing = ExistingSimklRewatchAuthorization(
        profileId = 2,
        accessToken = "previously-saved-simkl-token",
        accountId = 12003L
    )
    private val credential = ReplayBootstrapCredential(
        simklAccountId = "12003",
        token = "a".repeat(64),
        deviceId = "simkl-device:fb4bd6a3-507c-4ae7-92ce-a9f32e28286c",
        expiresAtEpochMs = 50000L
    )

    @Test
    fun upgradedAndroidTVReusesAlreadySignedInSimklWithoutPINPrompt() = runBlocking {
        var account: ExistingSimklRewatchAuthorization? = existing
        var tokenPassed: String? = null
        var idPassed: String? = null
        var savedProfile = 0
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { account },
            installationIdForProfile = { profile ->
                assertEquals(2, profile)
                uuid
            },
            exchange = { token, id ->
                tokenPassed = token
                idPassed = id
                credential
            },
            persist = { profile, _ -> savedProfile = profile },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.CONNECTED, bootstrap.connectExistingProfile())
        assertEquals(existing.accessToken, tokenPassed)
        assertEquals(uuid, idPassed)
        assertEquals(2, savedProfile)
        assertEquals(existing, account)
    }

    @Test
    fun disconnectedSimklDoesNotStartAnotherLogin() = runBlocking {
        var calls = 0
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { null },
            installationIdForProfile = { uuid },
            exchange = { _, _ -> calls++; credential },
            persist = { _, _ -> error("Must not persist") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.NO_EXISTING_SIMKL_LOGIN,
            bootstrap.connectExistingProfile())
        assertEquals(0, calls)
    }

    @Test
    fun profileSwitchDuringNetworkRequestDropsOldCredential() = runBlocking {
        var current: ExistingSimklRewatchAuthorization? = existing
        var writes = 0
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { current },
            installationIdForProfile = { uuid },
            exchange = { _, _ ->
                current = existing.copy(profileId = 3, accountId = 99221L)
                credential
            },
            persist = { _, _ -> writes++ },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION,
            bootstrap.connectExistingProfile())
        assertEquals(0, writes)
    }

    @Test
    fun switchedSimklTokenCannotAdoptStaleBootstrap() = runBlocking {
        var current = existing
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { current },
            installationIdForProfile = { uuid },
            exchange = { _, _ ->
                current = existing.copy(accessToken = "new-token")
                credential
            },
            persist = { _, _ -> error("Old token receipt must not persist") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION,
            bootstrap.connectExistingProfile())
    }

    @Test
    fun vpsUnavailableDoesNotModifyLocalReplayState() = runBlocking {
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { existing },
            installationIdForProfile = { uuid },
            exchange = { _, _ -> throw IllegalStateException("offline") },
            persist = { _, _ -> error("Must not save during outage") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.UNAVAILABLE, bootstrap.connectExistingProfile())
    }

    @Test
    fun rejectsInvalidServerCredentialWithoutSaving() = runBlocking {
        for (bad in listOf(
            credential.copy(token = "bad"),
            credential.copy(deviceId = "not-server-bound"),
            credential.copy(expiresAtEpochMs = 1000L)
        )) {
            val bootstrap = RewatchSilentSimklBootstrap(
                currentAuthorization = { existing },
                installationIdForProfile = { uuid },
                exchange = { _, _ -> bad },
                persist = { _, _ -> error("Invalid credential saved") },
                nowEpochMs = { 1000L }
            )
            assertEquals(ReplayBootstrapOutcome.INVALID_SERVER_CREDENTIAL,
                bootstrap.connectExistingProfile())
        }
    }

    @Test
    fun installationUuidMustBeV4AndUniqueToProfile() = runBlocking {
        assertTrue(isValidRewatchInstallationUuid(uuid))
        assertFalse(isValidRewatchInstallationUuid(null))
        assertFalse(isValidRewatchInstallationUuid("local-profile:2"))
        assertFalse(isValidRewatchInstallationUuid("00000000-0000-1000-8000-000000000001"))
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { existing },
            installationIdForProfile = { "wrong" },
            exchange = { _, _ -> error("Should not call VPS") },
            persist = { _, _ -> error("Should not save") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.INVALID_LOCAL_DEVICE_ID,
            bootstrap.connectExistingProfile())
    }

    @Test
    fun cancelledBootstrapNeverCommitsCredential() = runBlocking {
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { existing },
            installationIdForProfile = { uuid },
            exchange = { _, _ -> throw CancellationException("profile disposed") },
            persist = { _, _ -> error("Must not save") },
            nowEpochMs = { 1000L }
        )
        var cancelled = false
        try {
            bootstrap.connectExistingProfile()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun differentVerifiedSimklAccountNeverBindsToExistingProfile() = runBlocking {
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { existing },
            installationIdForProfile = { uuid },
            exchange = { _, _ -> credential.copy(simklAccountId = "99221") },
            persist = { _, _ -> error("Wrong account must not be committed") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.VERIFIED_ACCOUNT_MISMATCH,
            bootstrap.connectExistingProfile())
    }

    @Test
    fun legacyConnectedProfileWithoutCachedAccountIdCanStillInitialize() = runBlocking {
        var saved = false
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { existing.copy(accountId = null) },
            installationIdForProfile = { uuid },
            exchange = { _, _ -> credential },
            persist = { _, _ -> saved = true },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.CONNECTED, bootstrap.connectExistingProfile())
        assertTrue(saved)
    }
}
