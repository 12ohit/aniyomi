package eu.kanade.tachiyomi.data.track.job

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.database.AnimeDatabaseHelper
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val db = Injekt.get<DatabaseHelper>()
        val animedb = Injekt.get<AnimeDatabaseHelper>()
        val trackManager = Injekt.get<TrackManager>()
        val delayedTrackingStore = Injekt.get<DelayedTrackingStore>()

        withContext(Dispatchers.IO) {
            val tracks = delayedTrackingStore.getItems().mapNotNull {
                val manga = db.getManga(it.mangaId).executeAsBlocking() ?: return@withContext
                db.getTracks(manga).executeAsBlocking()
                    .find { track -> track.id == it.trackId }
                    ?.also { track ->
                        track.last_chapter_read = it.lastChapterRead
                    }
            }

            tracks.forEach { track ->
                try {
                    val service = trackManager.getService(track.sync_id)
                    if (service != null && service.isLogged) {
                        val manga = db.getManga(track.manga_id).executeAsBlocking() ?: return@withContext
                        service.update(track, true, manga.status)
                        db.insertTrack(track).executeAsBlocking()
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }

            val animeTracks = delayedTrackingStore.getAnimeItems().mapNotNull {
                val anime = animedb.getAnime(it.animeId).executeAsBlocking() ?: return@withContext
                animedb.getTracks(anime).executeAsBlocking()
                    .find { track -> track.id == it.trackId }
                    ?.also { track ->
                        track.last_episode_seen = it.lastEpisodeSeen
                    }
            }

            animeTracks.forEach { track ->
                try {
                    val service = trackManager.getService(track.sync_id)
                    if (service != null && service.isLogged) {
                        val anime = animedb.getAnime(track.anime_id).executeAsBlocking() ?: return@withContext
                        service.update(track, true, anime.status)
                        animedb.insertTrack(track).executeAsBlocking()
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }

            delayedTrackingStore.clear()
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "DelayedTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
