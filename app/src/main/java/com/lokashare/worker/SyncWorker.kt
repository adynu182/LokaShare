package com.lokashare.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lokashare.data.remote.FirestoreRepository
import timber.log.Timber

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Timber.d("SyncWorker dijalankan...")
        return try {
            val repository = FirestoreRepository(applicationContext)
            repository.syncPendingFromRoom()
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "SyncWorker gagal eksekusi")
            Result.retry()
        }
    }
}
