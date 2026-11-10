package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.rewatch.RewatchOutboxStorage
import com.nuvio.tv.domain.rewatch.isValidRewatchInstallationUuid
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Isolated offline replay storage; does not change Simkl auth, watch history,
 * Continue Watching caches, or the existing local RewatchSessionStore.
 * Never store replay bearer credentials in DataStore (use encrypted storage).
 */
@Singleton
internal class AndroidRewatchOutboxStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : RewatchOutboxStorage {
    private companion object {
        const val FEATURE = "rewatch_vps_outbox_v1"
        val PAYLOAD = stringPreferencesKey("pending_events_json")
        val INSTALLATION = stringPreferencesKey("installation_uuid_v4")
    }

    override suspend fun read(profileId: Int): String? {
        require(profileId > 0)
        return factory.get(profileId, FEATURE).data.first()[PAYLOAD]
    }

    override suspend fun write(profileId: Int, payload: String) {
        require(profileId > 0)
        factory.get(profileId, FEATURE).edit { it[PAYLOAD] = payload }
    }

    /** Stable for this Nuvio profile across compatible app upgrades. */
    suspend fun installationId(profileId: Int): String {
        require(profileId > 0)
        var result: String? = null
        factory.get(profileId, FEATURE).edit { preferences ->
            val old = preferences[INSTALLATION]
            if (old != null) {
                require(isValidRewatchInstallationUuid(old)) {
                    "Corrupt stored replay installation identity"
                }
                result = old
            } else {
                val newId = UUID.randomUUID().toString()
                preferences[INSTALLATION] = newId
                result = newId
            }
        }
        return requireNotNull(result)
    }
}
