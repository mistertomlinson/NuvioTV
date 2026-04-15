package com.nuvio.tv.core.homechannel

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

private const val TAG = "HomeScreenChannelWorker"
private const val PERIODIC_WORK_NAME = "home_screen_channel_periodic"

@HiltWorker
class HomeScreenChannelWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val channelManager: HomeScreenChannelManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // Channel is refreshed directly from the ViewModel via refreshFromItems()
        // after enrichment completes, ensuring fully hydrated data. The worker
        // is kept as infrastructure in case a background refresh path is needed
        // in the future.
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<HomeScreenChannelWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }
}
