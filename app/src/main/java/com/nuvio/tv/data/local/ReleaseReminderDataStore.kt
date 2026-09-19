package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

enum class ReleaseReminderBadge {
    AVAILABLE_NOW,
    NEW_SEASON
}

enum class ReleaseReminderStatus {
    ARMED,
    FULFILLED
}

data class ReleaseReminderRecord(
    val itemId: String,
    val itemType: String,
    val title: String,
    val year: Int? = null,
    val traktId: Int? = null,
    val simklId: Long? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val addonBaseUrl: String? = null,
    val releaseDate: String? = null,
    val seasonNumber: Int? = null,
    val badge: ReleaseReminderBadge = ReleaseReminderBadge.AVAILABLE_NOW,
    val status: ReleaseReminderStatus = ReleaseReminderStatus.ARMED,
    val fulfilledAtMillis: Long? = null
) {
    val key: String
        get() = checkNotNull(releaseReminderKey(itemId, itemType))
}

fun releaseReminderKey(itemId: String, itemType: String): String? {
    val normalizedId = itemId.trim().lowercase(Locale.ROOT)
    val normalizedType = when (itemType.trim().lowercase(Locale.ROOT)) {
        "film" -> "movie"
        "show", "tv" -> "series"
        else -> itemType.trim().lowercase(Locale.ROOT)
    }
    if (normalizedId.isBlank() || normalizedType.isBlank()) return null
    return "$normalizedType|$normalizedId"
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseReminderDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "release_reminders"
    }

    /* Kept for reading/removing reminders created by the original boolean-only implementation. */
    private val reminderKeys = stringSetPreferencesKey("reminder_keys")
    private val reminderRecords = stringSetPreferencesKey("reminder_records_v2")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val reminders: Flow<List<ReleaseReminderRecord>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { preferences ->
                preferences[reminderRecords]
                    .orEmpty()
                    .mapNotNull(::decodeRecord)
                    .sortedBy { it.key }
            }
        }

    fun isReminderSet(itemId: String, itemType: String): Flow<Boolean> {
        val key = releaseReminderKey(itemId, itemType) ?: return flowOf(false)
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { preferences ->
                key in preferences[reminderKeys].orEmpty() ||
                    preferences[reminderRecords]
                        .orEmpty()
                        .asSequence()
                        .mapNotNull(::decodeRecord)
                        .any { it.key == key }
            }
        }
    }

    suspend fun setReminder(record: ReleaseReminderRecord, enabled: Boolean) {
        store().edit { preferences ->
            val current = decodeRecords(preferences[reminderRecords].orEmpty())
                .filterNot { it.key == record.key }
                .toMutableList()
            if (enabled) current += record
            preferences[reminderRecords] = current.map(::encodeRecord).toSet()
            preferences[reminderKeys] = preferences[reminderKeys].orEmpty() - record.key
        }
    }

    /* Source-compatible legacy entry point. New callers should persist a full record. */
    suspend fun setReminder(itemId: String, itemType: String, enabled: Boolean) {
        val key = releaseReminderKey(itemId, itemType) ?: return
        store().edit { preferences ->
            preferences[reminderKeys] = if (enabled) {
                preferences[reminderKeys].orEmpty() + key
            } else {
                preferences[reminderKeys].orEmpty() - key
            }
            if (!enabled) {
                preferences[reminderRecords] =
                    decodeRecords(preferences[reminderRecords].orEmpty())
                        .filterNot { it.key == key }
                        .map(::encodeRecord)
                        .toSet()
            }
        }
    }

    suspend fun upsert(record: ReleaseReminderRecord) {
        setReminder(record, enabled = true)
    }

    suspend fun markFulfilled(key: String, fulfilledAtMillis: Long) {
        store().edit { preferences ->
            val updated = decodeRecords(preferences[reminderRecords].orEmpty()).map { record ->
                if (record.key == key) {
                    record.copy(
                        status = ReleaseReminderStatus.FULFILLED,
                        fulfilledAtMillis = fulfilledAtMillis
                    )
                } else {
                    record
                }
            }
            preferences[reminderRecords] = updated.map(::encodeRecord).toSet()
            preferences[reminderKeys] = preferences[reminderKeys].orEmpty() - key
        }
    }

    suspend fun remove(itemId: String, itemType: String) {
        releaseReminderKey(itemId, itemType)?.let { remove(it) }
    }

    suspend fun remove(key: String) {
        store().edit { preferences ->
            preferences[reminderKeys] = preferences[reminderKeys].orEmpty() - key
            preferences[reminderRecords] =
                decodeRecords(preferences[reminderRecords].orEmpty())
                    .filterNot { it.key == key }
                    .map(::encodeRecord)
                    .toSet()
        }
    }

    private fun decodeRecords(values: Set<String>): List<ReleaseReminderRecord> =
        values.mapNotNull(::decodeRecord)

    private fun encodeRecord(record: ReleaseReminderRecord): String =
        JSONObject().apply {
            put("itemId", record.itemId)
            put("itemType", record.itemType)
            put("title", record.title)
            putNullable("year", record.year)
            putNullable("traktId", record.traktId)
            putNullable("simklId", record.simklId)
            putNullable("imdbId", record.imdbId)
            putNullable("tmdbId", record.tmdbId)
            putNullable("poster", record.poster)
            putNullable("background", record.background)
            putNullable("logo", record.logo)
            putNullable("description", record.description)
            putNullable("releaseInfo", record.releaseInfo)
            putNullable("imdbRating", record.imdbRating)
            put("genres", JSONArray(record.genres))
            putNullable("addonBaseUrl", record.addonBaseUrl)
            putNullable("releaseDate", record.releaseDate)
            putNullable("seasonNumber", record.seasonNumber)
            put("badge", record.badge.name)
            put("status", record.status.name)
            putNullable("fulfilledAtMillis", record.fulfilledAtMillis)
        }.toString()

    private fun decodeRecord(raw: String): ReleaseReminderRecord? = runCatching {
        val json = JSONObject(raw)
        val itemId = json.stringOrNull("itemId") ?: return@runCatching null
        val itemType = json.stringOrNull("itemType") ?: return@runCatching null
        ReleaseReminderRecord(
            itemId = itemId,
            itemType = itemType,
            title = json.stringOrNull("title").orEmpty(),
            year = json.intOrNull("year"),
            traktId = json.intOrNull("traktId"),
            simklId = json.longOrNull("simklId"),
            imdbId = json.stringOrNull("imdbId"),
            tmdbId = json.intOrNull("tmdbId"),
            poster = json.stringOrNull("poster"),
            background = json.stringOrNull("background"),
            logo = json.stringOrNull("logo"),
            description = json.stringOrNull("description"),
            releaseInfo = json.stringOrNull("releaseInfo"),
            imdbRating = json.doubleOrNull("imdbRating")?.toFloat(),
            genres = json.optJSONArray("genres")?.let { array ->
                buildList {
                    repeat(array.length()) { index ->
                        array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.orEmpty(),
            addonBaseUrl = json.stringOrNull("addonBaseUrl"),
            releaseDate = json.stringOrNull("releaseDate"),
            seasonNumber = json.intOrNull("seasonNumber"),
            badge = enumValueOrDefault(
                json.stringOrNull("badge"),
                ReleaseReminderBadge.AVAILABLE_NOW
            ),
            status = enumValueOrDefault(
                json.stringOrNull("status"),
                ReleaseReminderStatus.ARMED
            ),
            fulfilledAtMillis = json.longOrNull("fulfilledAtMillis")
        )
    }.getOrNull()

    private fun JSONObject.putNullable(name: String, value: Any?) {
        if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.intOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name)

    private fun JSONObject.longOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    private fun JSONObject.doubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: fallback
}
