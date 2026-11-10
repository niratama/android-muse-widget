package jp.poi.widget.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.poi.widget.drive.DriveSyncManager
import java.util.concurrent.TimeUnit

class GomaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return when (DriveSyncManager.sync(applicationContext)) {
            is DriveSyncManager.SyncResult.Success -> Result.success()
            is DriveSyncManager.SyncResult.Error -> {
                // リトライ上限（2回目以降は失敗終了して次回定期実行を待つ）
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK_NAME = "goma_periodic_sync_work"
        private const val UNIQUE_ONE_TIME_WORK_NAME = "goma_immediate_sync_work"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // 1時間間隔（バッテリー消費を抑えつつ朝の更新を逃さない）
            val periodicWork = PeriodicWorkRequestBuilder<GomaSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
        }

        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediateWork = OneTimeWorkRequestBuilder<GomaSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                immediateWork
            )
        }
    }
}
