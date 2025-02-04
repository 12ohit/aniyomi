package eu.kanade.tachiyomi.data.library.anime

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import eu.kanade.domain.library.service.LibraryPreferences
import eu.kanade.tachiyomi.data.preference.DEVICE_BATTERY_NOT_LOW
import eu.kanade.tachiyomi.data.preference.DEVICE_CHARGING
import eu.kanade.tachiyomi.data.preference.DEVICE_NETWORK_NOT_METERED
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class AnimeLibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    override fun doWork(): Result {
        val preferences = Injekt.get<LibraryPreferences>()
        val restrictions = preferences.libraryUpdateDeviceRestriction().get()
        if ((DEVICE_ONLY_ON_WIFI in restrictions) && !context.isConnectedToWifi()) {
            return Result.failure()
        }

        return if (AnimeLibraryUpdateService.start(context)) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "AnimelibUpdate"

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = Injekt.get<LibraryPreferences>()
            val interval = prefInterval ?: preferences.libraryUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.libraryUpdateDeviceRestriction().get()
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (DEVICE_NETWORK_NOT_METERED in restrictions) { NetworkType.UNMETERED } else { NetworkType.CONNECTED })
                    .setRequiresCharging(DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(DEVICE_BATTERY_NOT_LOW in restrictions)
                    .build()

                val request = PeriodicWorkRequestBuilder<AnimeLibraryUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .setConstraints(constraints)
                    .build()

                // Re-enqueue work because of common support suggestion to change
                // the settings on the desired time to schedule it at that time
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
            } else {
                WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            }
        }
    }
}
