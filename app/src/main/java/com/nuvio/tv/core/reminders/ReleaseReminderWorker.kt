package com.nuvio.tv.core.reminders

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nuvio.tv.data.local.ReleaseReminderRecord
import com.nuvio.tv.data.local.ReleaseReminderStatus
import com.nuvio.tv.ui.screens.home.ReleaseReminderCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

private const val PERIODIC_WORK = "release_reminders_periodic_v2"
private const val DUE_WORK = "release_reminders_due_v2"
private const val MODE_KEY = "mode"
private const val MODE_PERIODIC = "periodic"
private const val MODE_DUE = "due"
private const val DUE_RECHECK_MS = 60L * 60 * 1000

@HiltWorker
class ReleaseReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: ReleaseReminderCoordinator
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result =
        runCatching {
            if (inputData.getString(MODE_KEY) == MODE_DUE) {
                coordinator.runDueMaintenance()
            } else {
                coordinator.runPeriodicMaintenance()
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )

    companion object {
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ReleaseReminderWorker>(
                12,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInputData(
                    Data.Builder()
                        .putString(MODE_KEY, MODE_PERIODIC)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun scheduleDue(
            context: Context,
            reminders: List<ReleaseReminderRecord>
        ) {
            val now = ZonedDateTime.now()
            val today = now.toLocalDate()
            val zone = ZoneId.systemDefault()

            val target = reminders.asSequence()
                .filter { it.status == ReleaseReminderStatus.ARMED }
                .mapNotNull { reminder ->
                    val date = parseDate(reminder.releaseDate)
                        ?: return@mapNotNull null

                    if (date.isAfter(today)) {
                        date.atStartOfDay(zone)
                    } else {
                        val minimum = now.plusSeconds(5)
                        val lastTarget = reminder.lastAuthorityCheckAtMillis?.let {
                            Instant.ofEpochMilli(it + DUE_RECHECK_MS).atZone(zone)
                        }
                        if (lastTarget != null && lastTarget.isAfter(minimum)) {
                            lastTarget
                        } else {
                            minimum
                        }
                    }
                }
                .minOrNull()

            val manager = WorkManager.getInstance(context)

            if (target == null) {
                manager.cancelUniqueWork(DUE_WORK)
                return
            }

            val delayMs = Duration.between(now, target)
                .toMillis()
                .coerceAtLeast(5_000L)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ReleaseReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(
                    Data.Builder()
                        .putString(MODE_KEY, MODE_DUE)
                        .build()
                )
                .build()

            manager.enqueueUniqueWork(
                DUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun parseDate(raw: String?): LocalDate? {
            val value = raw?.trim()?.takeIf { it.length == 10 } ?: return null
            return try {
                LocalDate.parse(value)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
