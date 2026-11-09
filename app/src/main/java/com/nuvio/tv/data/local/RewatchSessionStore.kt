package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.rewatch.RewatchSession
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

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

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
                    preferences[key] = gson.toJson(updated)
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
