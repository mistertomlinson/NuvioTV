package com.nuvio.tv.core.reminders

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import android.os.PowerManager
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.gson.Gson
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "SOBackgroundRefresh"
const val STREAMING_ORIGINALS_ADDON_ID = "community.streaming.originals.v6"

/** Five distinct local-time slots; schedules survive app-process death. */
internal enum class StreamingOriginalsRefreshSlot(val time: LocalTime) {
    MIDNIGHT(LocalTime.MIDNIGHT),
    NIGHT_RECOVERY(LocalTime.of(0, 45)),
    TOP10_PRIMARY(LocalTime.of(11, 45)),
    TOP10_RECOVERY(LocalTime.of(12, 45)),
    NIGHT_PRECHECK(LocalTime.of(23, 45));

    fun nextRun(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(time).atZone(now.zone)
        return if (today.isAfter(now)) today
        else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    }
}

/** Updated only by Activity lifecycle; no polling on focus/scroll paths. */
object StreamingOriginalsInteractionGate {
    @Volatile var appInteractive: Boolean = false
        private set
    private val activeWorkers = ConcurrentHashMap.newKeySet<Job>()

    fun setInteractive(value: Boolean) {
        appInteractive = value
        // Stop in-flight network work promptly instead of waiting for every
        // catalog request to finish while someone is scrolling.
        if (value) activeWorkers.forEach { it.cancel() }
    }

    fun register(job: Job) {
        activeWorkers.add(job)
        // doWork checks isBusy(context) immediately. A TV may retain the
        // Activity RESUMED while the display is off; don't cancel that job
        // merely because the last remembered Activity state was RESUMED.
    }

    fun unregister(job: Job) {
        activeWorkers.remove(job)
    }

    /** Screen-off devices may remain RESUMED on some Android TV builds. */
    fun isBusy(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return appInteractive && power?.isInteractive != false
    }
}

object StreamingOriginalsRefreshScheduler {
    private const val PREFS = "so_background_refresh_v1"
    private const val PENDING = "pending_slots"
    private const val CATCHUP_WORK = "so_background_catchup_v1"
    private val lock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pending(context: Context): Set<String> =
        prefs(context).getStringSet(PENDING, emptySet()).orEmpty().toSet()

    private fun slotKey(slot: StreamingOriginalsRefreshSlot, date: LocalDate) =
        date.toString() + "|" + slot.name

    private fun markDue(context: Context, slot: StreamingOriginalsRefreshSlot, date: LocalDate) =
        synchronized(lock) {
            val key = slotKey(slot, date)
            if (prefs(context).getString("completed_" + slot.name, null) != date.toString()) {
                // Retain only the newest pending day for each slot, even when
                // the app remains foregrounded for weeks.
                val retained = pending(context).filterNot {
                    it.substringAfter('|', "") == slot.name
                }.toSet()
                prefs(context).edit().putStringSet(PENDING, retained + key).apply()
            }
        }

    /** Cheap, synchronous catch-up marker; never launches network on resume. */
    fun noteMissedSlots(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        StreamingOriginalsRefreshSlot.entries.forEach { slot ->
            if (!now.toLocalTime().isBefore(slot.time)) {
                markDue(context, slot, now.toLocalDate())
            }
        }
    }

    fun hasPending(context: Context): Boolean = synchronized(lock) {
        pending(context).isNotEmpty()
    }

    internal fun pendingSnapshot(context: Context): Set<String> = synchronized(lock) {
        pending(context)
    }

    internal fun complete(context: Context, captured: Set<String>) = synchronized(lock) {
        val edit = prefs(context).edit()
        captured.forEach { key ->
            edit.putString(
                "completed_" + key.substringAfter('|'),
                key.substringBefore('|')
            )
        }
        edit.putStringSet(PENDING, pending(context) - captured).apply()
    }

    private fun request(slot: StreamingOriginalsRefreshSlot?, delayMs: Long) =
        OneTimeWorkRequestBuilder<StreamingOriginalsBackgroundWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().apply {
                if (slot != null) putString("slot", slot.name)
            }.build())
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()

    private fun uniqueName(slot: StreamingOriginalsRefreshSlot) =
        "so_background_" + slot.name.lowercase() + "_v1"

    fun schedule(context: Context) {
        val now = ZonedDateTime.now()
        noteMissedSlots(context, now)
        val manager = WorkManager.getInstance(context)
        StreamingOriginalsRefreshSlot.entries.forEach { slot ->
            manager.enqueueUniqueWork(
                uniqueName(slot), ExistingWorkPolicy.KEEP,
                request(slot, Duration.between(now, slot.nextRun(now)).toMillis())
            )
        }
        // Do not launch a heavyweight catch-up during a foreground cold start.
        // MainActivity.onPause queues it once the app is inactive.
    }

    internal fun scheduleSuccessor(context: Context, slot: StreamingOriginalsRefreshSlot) {
        val now = ZonedDateTime.now()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(slot), ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(slot, Duration.between(now, slot.nextRun(now)).toMillis())
        )
    }

    internal fun noteSlotFired(context: Context, slot: StreamingOriginalsRefreshSlot) {
        markDue(context, slot, LocalDate.now(ZoneId.systemDefault()))
    }

    /** Called onPause; no WorkManager request if there is no overdue slot. */
    fun enqueueCatchupIfNeeded(context: Context) {
        if (!hasPending(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCHUP_WORK, ExistingWorkPolicy.KEEP, request(null, 0)
        )
    }

    internal fun slotFromInput(raw: String?): StreamingOriginalsRefreshSlot? =
        StreamingOriginalsRefreshSlot.entries.firstOrNull { it.name == raw }
}

data class StreamingOriginalsDurableGeneration(
    val profileId: Int,
    val localDate: String,
    val createdAtMillis: Long,
    val addon: Addon,
    val rows: List<CatalogRow>
)

/** Durable raw catalog snapshot; no Compose/ViewModel writes from Workers. */
@Singleton
class StreamingOriginalsGenerationStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonRepository: AddonRepository,
    private val addonPreferences: AddonPreferences,
    private val catalogRepository: CatalogRepository,
    private val profileManager: ProfileManager,
    moshi: Moshi
) {
    private val mutex = Mutex()
    private val gson = Gson()
    private val rowsAdapter = moshi.adapter<List<CatalogRow>>(
        Types.newParameterizedType(List::class.java, CatalogRow::class.java)
    )

    private fun file(profileId: Int) =
        AtomicFile(File(context.filesDir, "so_staged_v1_p" + profileId + ".json"))

    private fun markerKey(profileId: Int) = "snapshot_profile_" + profileId

    /**
     * Home publishes the known SO URL once per profile. Workers can then
     * fetch SO alone without asking getInstalledAddons() to crawl unrelated
     * Watchly/Seasonal manifests in a stale-cache sweep.
     */
    fun rememberSource(profileId: Int, addon: Addon) {
        if (addon.id != STREAMING_ORIGINALS_ADDON_ID) return
        context.getSharedPreferences("so_background_source_v1", Context.MODE_PRIVATE)
            .edit().apply {
                if (addon.enabled) putString("url_p" + profileId, addon.baseUrl)
                else remove("url_p" + profileId)
            }.apply()
    }

    private fun rememberedSource(profileId: Int): String? =
        context.getSharedPreferences("so_background_source_v1", Context.MODE_PRIVATE)
            .getString("url_p" + profileId, null)
            ?.trim()?.takeIf { it.isNotEmpty() }

    private fun canonicalSourceUrl(raw: String): String =
        raw.trim().trimEnd('/')
            .removeSuffix("/manifest.json")
            .trimEnd('/')
            .lowercase()


    fun hasSnapshot(profileId: Int): Boolean =
        context.getSharedPreferences("so_background_snapshot_v1", Context.MODE_PRIVATE)
            .getBoolean(markerKey(profileId), false)

    private fun setMarker(profileId: Int, present: Boolean) {
        context.getSharedPreferences("so_background_snapshot_v1", Context.MODE_PRIVATE)
            .edit().putBoolean(markerKey(profileId), present).apply()
    }

    suspend fun read(profileId: Int): StreamingOriginalsDurableGeneration? =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(
                    file(profileId).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                )
                val addon = gson.fromJson(json.getString("addon"), Addon::class.java)
                val rows = rowsAdapter.fromJson(json.getString("rows"))
                    ?: return@withContext null
                StreamingOriginalsDurableGeneration(
                    json.getInt("profile"), json.getString("localDate"),
                    json.getLong("createdAt"), addon, rows
                ).takeIf { it.profileId == profileId }
            } catch (_: Exception) {
                null
            }
        }

    suspend fun clearBrokenSnapshot(profileId: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // A background Worker could have replaced the unreadable old file
            // while Home was waiting for this lock.
            if (read(profileId) == null) {
                file(profileId).delete()
                setMarker(profileId, false)
            }
        }
    }

    suspend fun clearIfCurrent(profileId: Int, createdAtMillis: Long) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (read(profileId)?.createdAtMillis == createdAtMillis) {
                    file(profileId).delete()
                    setMarker(profileId, false)
                }
            }
        }

    /**
     * Capture entire addon generation while inactive. At most two requests
     * simultaneously; abort on resumed interaction, profile changes or errors.
     */
    suspend fun prepareIfInactive(): Boolean = mutex.withLock {
        if (StreamingOriginalsInteractionGate.isBusy(context) ||
            !StreamingOriginalsRefreshScheduler.hasPending(context)
        ) return@withLock false
        profileManager.activeProfileReady.first { it }
        val profileId = profileManager.activeProfileId.value
        val localDate = LocalDate.now(ZoneId.systemDefault())
        val sourceUrl = rememberedSource(profileId) ?: return@withLock false
        // Honor uninstallation/profile addon inheritance without a manifest
        // crawl. This DataStore lookup is background-only.
        val installedUrls = addonPreferences.installedAddonUrls.first()
        if (installedUrls.none {
            canonicalSourceUrl(it) == canonicalSourceUrl(sourceUrl)
        }) return@withLock false

        val freshAddon = when (val result = addonRepository.fetchAddon(sourceUrl)) {
            is NetworkResult.Success ->
                result.data.takeIf {
                    it.id == STREAMING_ORIGINALS_ADDON_ID
                } ?: return@withLock false
            else -> return@withLock false
        }
        if (StreamingOriginalsInteractionGate.isBusy(context) ||
            profileManager.activeProfileId.value != profileId ||
            freshAddon.catalogs.isEmpty()
        ) return@withLock false

        // A cache hit is not sufficient proof of a new day's catalog.
        catalogRepository.clearAddonInMemoryCache(STREAMING_ORIGINALS_ADDON_ID)
        val rows: List<CatalogRow?> = try {
            coroutineScope {
                val throttle = Semaphore(2)
                freshAddon.catalogs.map { catalog ->
                    async(Dispatchers.IO) {
                        throttle.withPermit {
                            if (StreamingOriginalsInteractionGate.isBusy(context)) {
                                return@withPermit null
                            }
                            var row: CatalogRow? = null
                            var failed = false
                            catalogRepository.getCatalog(
                                addonBaseUrl = freshAddon.baseUrl,
                                addonId = freshAddon.id,
                                addonName = freshAddon.displayName,
                                catalogId = catalog.id,
                                catalogName = catalog.name,
                                type = catalog.apiType,
                                skip = 0,
                                skipStep = catalog.skipStep(),
                                supportsSkip = catalog.supportsExtra("skip")
                            ).collect { result ->
                                when (result) {
                                    is NetworkResult.Success -> row = result.data
                                    is NetworkResult.Error -> failed = true
                                    NetworkResult.Loading -> Unit
                                }
                            }
                            if (failed ||
                                (catalog.id.startsWith("top10_", true) && row?.items.isNullOrEmpty())
                            ) null else row
                        }
                    }
                }.awaitAll()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Background catalog fetch failed", error)
            return@withLock false
        }

        if (StreamingOriginalsInteractionGate.isBusy(context) ||
            profileManager.activeProfileId.value != profileId ||
            LocalDate.now(ZoneId.systemDefault()) != localDate ||
            rows.size != freshAddon.catalogs.size ||
            rows.any { it == null }
        ) return@withLock false

        val generation = StreamingOriginalsDurableGeneration(
            profileId, localDate.toString(), System.currentTimeMillis(),
            freshAddon, rows.filterNotNull()
        )
        return@withLock try {
            withContext(Dispatchers.IO) {
                val json = JSONObject()
                    .put("profile", profileId)
                    .put("localDate", generation.localDate)
                    .put("createdAt", generation.createdAtMillis)
                    .put("addon", gson.toJson(freshAddon))
                    .put("rows", rowsAdapter.toJson(generation.rows))
                val destination = file(profileId)
                val out = destination.startWrite()
                try {
                    out.write(json.toString().toByteArray(Charsets.UTF_8))
                    destination.finishWrite(out)
                } catch (error: Exception) {
                    destination.failWrite(out)
                    throw error
                }
                setMarker(profileId, true)
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "Could not persist SO generation", error)
            false
        }
    }
}

@HiltWorker
class StreamingOriginalsBackgroundWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: StreamingOriginalsGenerationStore
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val slot = StreamingOriginalsRefreshScheduler.slotFromInput(inputData.getString("slot"))
        val activeJob = currentCoroutineContext()[Job]
        if (activeJob != null) StreamingOriginalsInteractionGate.register(activeJob)
        try {
            if (slot != null) {
                StreamingOriginalsRefreshScheduler.noteSlotFired(applicationContext, slot)
            }
            if (StreamingOriginalsInteractionGate.isBusy(applicationContext)) return Result.success()

            val captured = StreamingOriginalsRefreshScheduler.pendingSnapshot(applicationContext)
            if (captured.isEmpty()) return Result.success()

            if (store.prepareIfInactive()) {
                StreamingOriginalsRefreshScheduler.complete(applicationContext, captured)
                Log.i(TAG, "Durable SO refresh completed for " + captured.joinToString())
            }
            // Pending state survives failure; no aggressive retry while browsing.
            return Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Background SO refresh failed", error)
            return Result.success()
        } finally {
            if (activeJob != null) StreamingOriginalsInteractionGate.unregister(activeJob)
            if (slot != null) {
                StreamingOriginalsRefreshScheduler.scheduleSuccessor(applicationContext, slot)
            }
        }
    }
}
