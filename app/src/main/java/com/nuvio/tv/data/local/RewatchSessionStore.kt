package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.domain.rewatch.RewatchRemoteLookup
import com.nuvio.tv.domain.rewatch.RewatchRemoteApplyDecision
import com.nuvio.tv.domain.rewatch.decideRewatchRemoteApplication
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Isolated local persistence for future replay integration.
 *
 * This store is not used by the existing player or CW pipeline. In particular,
 * it does not read or write watched history, Simkl snapshots, or CW caches.
 * All writes capture a profile ID and are guarded against profile switches.
 */
/** Atomically observed replay cursor and local write generation. */
internal data class RewatchObservedLocalSync(
    val profileId: Int,
    val sessions: List<RewatchSession>,
    val generation: Long
)

/** Capture the profile ID with its replay data, never as separate emissions. */
data class ProfileRewatchSessions(
    val profileId: Int,
    val sessions: List<RewatchSession>
)

@Singleton
class RewatchSessionStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE = "rewatch_sessions"
        const val SCHEMA_VERSION = 1
    }

    private val gson = Gson()
    private val key = stringPreferencesKey("sessions_v1")
    private val ledgerKey = stringPreferencesKey("rewatch_local_sync_ledger_v1")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    private fun localLedger(preferences: Preferences, profileId: Int) =
        decodeRewatchLocalSyncLedger(
            preferences[ledgerKey] ?: """{"schemaVersion":1,"generation":0,"cleanBySeries":{}}""",
            profileId, gson
        )

    /** One consistent DataStore snapshot for compare-and-swap reconciliation. */
    internal suspend fun observeLocalReplaySync(profileId: Int): RewatchObservedLocalSync {
        require(profileId > 0)
        val preferences = store(profileId).data.first()
        return RewatchObservedLocalSync(
            profileId = profileId,
            sessions = decodeRewatchSessions(preferences[key] ?: "[]", gson),
            generation = localLedger(preferences, profileId).generation
        )
    }

    /**
     * Mark a replay clean only after an authenticated GET and an independently
     * verified empty outbox. The local cursor and its mutation generation are
     * checked within the same DataStore edit.
     */
    internal suspend fun certifyAuthenticatedRemoteMatch(
        expectedProfileId: Int,
        simklAccountId: String,
        deviceId: String,
        expectedLocal: RewatchSession,
        expectedGeneration: Long,
        remoteLookup: RewatchRemoteLookup,
        pendingOrUnpublished: Boolean,
        stillAuthenticated: () -> Boolean
    ): Boolean {
        var accepted = false
        if (!stillAuthenticated()) return false
        profileManager.withActiveProfileMutation(expectedProfileId) {
            store(expectedProfileId).edit { preferences ->
                if (!stillAuthenticated()) return@edit
                val sessions = decodeRewatchSessions(preferences[key] ?: "[]", gson)
                val ledger = localLedger(preferences, expectedProfileId)
                val updated = certifyRewatchLocalClean(
                    sessions, ledger, expectedProfileId, simklAccountId,
                    deviceId, expectedLocal, expectedGeneration,
                    remoteLookup, pendingOrUnpublished
                ) ?: return@edit
                if (!stillAuthenticated()) return@edit
                preferences[ledgerKey] = gson.toJson(updated)
                accepted = true
            }
            true
        }
        return accepted && stillAuthenticated()
    }

    /**
     * Remote replay and clean proof are written together, with a generation
     * compare-and-swap. A late player save invalidates the proof and prevents
     * this write; no provider or watched-history persistence is called.
     */
    internal suspend fun applyAuthenticatedRemoteIfClean(
        expectedProfileId: Int,
        simklAccountId: String,
        deviceId: String,
        canonicalSeriesId: String,
        observed: RewatchObservedLocalSync,
        remoteLookup: RewatchRemoteLookup,
        pendingOrUnpublished: Boolean,
        appliedAtEpochMs: Long,
        stillAuthenticated: () -> Boolean
    ): Boolean {
        if (observed.profileId != expectedProfileId || !stillAuthenticated()) return false
        var accepted = false
        profileManager.withActiveProfileMutation(expectedProfileId) {
            store(expectedProfileId).edit { preferences ->
                if (!stillAuthenticated()) return@edit
                val sessions = decodeRewatchSessions(preferences[key] ?: "[]", gson)
                val ledger = localLedger(preferences, expectedProfileId)
                val local = observed.sessions.firstOrNull {
                    it.canonicalSeriesId.trim().equals(
                        canonicalSeriesId.trim(), ignoreCase = true
                    )
                }
                val result = mergeRewatchRemoteWithLedger(
                    sessions = sessions,
                    ledger = ledger,
                    profileId = expectedProfileId,
                    simklAccountId = simklAccountId,
                    deviceId = deviceId,
                    expectedLocal = local,
                    expectedGeneration = observed.generation,
                    canonicalSeriesId = canonicalSeriesId,
                    remoteLookup = remoteLookup,
                    pendingOrUnpublished = pendingOrUnpublished,
                    appliedAtEpochMs = appliedAtEpochMs
                ) ?: return@edit
                if (!stillAuthenticated()) return@edit
                preferences[key] = gson.toJson(result.first)
                preferences[ledgerKey] = gson.toJson(result.second)
                accepted = true
            }
            true
        }
        return accepted && stillAuthenticated()
    }

    val activeProfileSessions: Flow<ProfileRewatchSessions> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            store(profileId).data.map { preferences ->
                ProfileRewatchSessions(
                    profileId = profileId,
                    sessions = decodeRewatchSessions(preferences[key] ?: "[]", gson)
                )
            }.catch { error ->
                // Never terminate Home's CW pipeline because replay-only
                // storage is corrupt or temporarily inaccessible. Preserve
                // the original payload: neither auto-reset nor overwrite it.
                Log.w("RewatchSessionStore", "Cannot read profile replay state", error)
                emit(ProfileRewatchSessions(profileId, emptyList()))
            }
        }

    /** Compatibility accessor for readers not requiring an atomic profile tag. */
    val activeSessions: Flow<List<RewatchSession>> =
        activeProfileSessions.map { it.sessions }

    suspend fun load(profileId: Int): List<RewatchSession> {
        require(profileId > 0)
        return decodeRewatchSessions(
            store(profileId).data.first()[key] ?: "[]",
            gson
        )
    }

    /**
     * Never implicitly replaces a different replay session for this title.
     * Explicit user-initiated "Start Rewatch Again" may pass replaceSession=true.
     * Returns false for a conflicting or stale update, including a profile switch.
     */
    /**
     * A remote replay cannot overwrite an intervening local player save.
     *
     * This is NOT enabled by the app yet. A future caller must supply a
     * durably verified clean baseline and coordinate the outbox's pending
     * state with this transaction. The expected local cursor is rechecked
     * inside the DataStore edit, not trusted from an earlier GET.
     */
    internal suspend fun applyVerifiedRemoteIfUnchanged(
        expectedProfileId: Int,
        canonicalSeriesId: String,
        expectedLocal: RewatchSession?,
        remoteLookup: RewatchRemoteLookup,
        lastAppliedRevision: Long,
        lastKnownCleanLocalSession: RewatchSession?,
        hasUnacknowledgedLocalEvents: Boolean,
        hasUnpublishedLocalChanges: Boolean,
        appliedAtEpochMs: Long
    ): Boolean {
        require(expectedProfileId > 0)
        require(appliedAtEpochMs >= 0L)
        var applied = false
        profileManager.withActiveProfileMutation(expectedProfileId) {
            store(expectedProfileId).edit { preferences ->
                val existing = decodeRewatchSessions(preferences[key] ?: "[]", gson)
                val updated = mergeVerifiedRemoteRewatchSnapshot(
                    existing = existing,
                    profileId = expectedProfileId,
                    canonicalSeriesId = canonicalSeriesId,
                    expectedLocal = expectedLocal,
                    remoteLookup = remoteLookup,
                    lastAppliedRevision = lastAppliedRevision,
                    lastKnownCleanLocalSession = lastKnownCleanLocalSession,
                    hasUnacknowledgedLocalEvents = hasUnacknowledgedLocalEvents,
                    hasUnpublishedLocalChanges = hasUnpublishedLocalChanges,
                    appliedAtEpochMs = appliedAtEpochMs
                )
                if (updated != null) {
                    preferences[key] = gson.toJson(updated)
                    applied = true
                }
            }
            true
        }
        return applied
    }

    suspend fun upsertForActiveProfile(
        expectedProfileId: Int,
        session: RewatchSession,
        replaceSession: Boolean = false
    ): Boolean {
        require(expectedProfileId > 0)
        var accepted = false
        val profileStillActive = profileManager.withActiveProfileMutation(
            expectedProfileId
        ) {
            store(expectedProfileId).edit { preferences ->
                val existing = decodeRewatchSessions(preferences[key] ?: "[]", gson)
                val updated = mergeRewatchSessionUpdate(
                    existing = existing,
                    incoming = session,
                    replaceSession = replaceSession
                )
                if (updated != null) {
                    val nextLedger = onLocalRewatchCommitted(
                        localLedger(preferences, expectedProfileId), session
                    )
                    // Atomic in the replay DataStore: no local write can
                    // succeed without invalidating its prior clean proof.
                    preferences[key] = gson.toJson(updated)
                    preferences[ledgerKey] = gson.toJson(nextLedger)
                    accepted = true
                }
            }
            true
        }
        return profileStillActive && accepted
    }
}

private val rewatchSessionListType =
    object : TypeToken<List<RewatchSession>>() {}.type

internal fun decodeRewatchSessions(
    raw: String,
    gson: Gson = Gson()
): List<RewatchSession> {
    // Fail closed rather than silently deleting unknown/future/corrupt data.
    val parsed = requireNotNull(
        gson.fromJson<List<RewatchSession>>(raw, rewatchSessionListType)
    ) { "Rewatch sessions payload was null" }

    require(parsed.all { session ->
        session.ownerKey.isNotBlank() &&
            session.canonicalSeriesId.isNotBlank() &&
            session.sessionId.isNotBlank() &&
            session.episode.season > 0 &&
            session.episode.episode > 0 &&
            session.updatedAtEpochMs >= 0L
    }) { "Rewatch sessions payload contained an invalid session" }

    require(parsed
        .map { it.ownerKey to it.canonicalSeriesId.trim().lowercase() }
        .distinct().size == parsed.size
    ) { "Rewatch sessions payload contained duplicate identities" }

    return parsed
}

/**
 * Pure update rule shared by store and tests. Owner + normalized series ID
 * must match for any replacement, and newer session IDs need explicit intent.
 */
internal fun mergeRewatchSessionUpdate(
    existing: List<RewatchSession>,
    incoming: RewatchSession,
    replaceSession: Boolean = false
): List<RewatchSession>? {
    require(incoming.ownerKey.isNotBlank())
    require(incoming.canonicalSeriesId.isNotBlank())
    require(incoming.sessionId.isNotBlank())

    val contentKey = incoming.canonicalSeriesId.trim().lowercase()
    val index = existing.indexOfFirst {
        it.canonicalSeriesId.trim().lowercase() == contentKey
    }

    if (index < 0) return existing + incoming

    val previous = existing[index]
    if (previous.ownerKey != incoming.ownerKey) return null
    if (previous.sessionId != incoming.sessionId && !replaceSession) return null
    if (previous.sessionId == incoming.sessionId &&
        incoming.updatedAtEpochMs < previous.updatedAtEpochMs
    ) return null

    // An explicit replacement intentionally starts another replay session.
    return existing.toMutableList().apply { set(index, incoming) }
}

/**
 * A local replay must still equal the snapshot observed when the authenticated
 * VPS response was read. Reject an intervening progress save, session restart,
 * duplicate identity, or unproven clean baseline.
 *
 * The incoming remote timestamp is zero: it carries no device-clock ordering
 * meaning. The caller supplies a local timestamp on the accepted projection.
 * Only the isolated replay store is changed, never Simkl watched history.
 */
internal fun mergeVerifiedRemoteRewatchSnapshot(
    existing: List<RewatchSession>,
    profileId: Int,
    canonicalSeriesId: String,
    expectedLocal: RewatchSession?,
    remoteLookup: RewatchRemoteLookup,
    lastAppliedRevision: Long,
    lastKnownCleanLocalSession: RewatchSession?,
    hasUnacknowledgedLocalEvents: Boolean,
    hasUnpublishedLocalChanges: Boolean,
    appliedAtEpochMs: Long
): List<RewatchSession>? {
    if (profileId <= 0 || canonicalSeriesId.isBlank() || appliedAtEpochMs < 0L)
        return null
    fun key(s: String) = s.trim().lowercase()
    val matches = existing.withIndex().filter {
        key(it.value.canonicalSeriesId) == key(canonicalSeriesId)
    }
    if (matches.size > 1) return null
    val actual = matches.singleOrNull()?.value
    if (actual != expectedLocal) return null
    val decision = decideRewatchRemoteApplication(
        profileId = profileId,
        canonicalSeriesId = canonicalSeriesId,
        local = actual,
        remoteLookup = remoteLookup,
        lastAppliedRevision = lastAppliedRevision,
        lastKnownCleanLocalSession = lastKnownCleanLocalSession,
        hasUnacknowledgedLocalEvents = hasUnacknowledgedLocalEvents,
        hasUnpublishedLocalChanges = hasUnpublishedLocalChanges
    )
    if (decision !is RewatchRemoteApplyDecision.ApplyRemote) return null
    val remote = decision.session.copy(
        updatedAtEpochMs = maxOf(
            appliedAtEpochMs, actual?.updatedAtEpochMs ?: 0L
        )
    )
    return if (matches.isEmpty()) existing + remote
    else existing.toMutableList().apply {
        set(matches.single().index, remote)
    }
}
