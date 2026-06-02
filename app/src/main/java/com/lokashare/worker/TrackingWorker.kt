package com.lokashare.worker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lokashare.data.remote.FirestoreRepository
import com.lokashare.service.TrackingForegroundService
import com.lokashare.util.NetworkMonitor
import com.lokashare.util.PrefsManager
import timber.log.Timber

class TrackingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = PrefsManager(context)

        Timber.d("TrackingWorker (Watchdog) berjalan...")

        if (prefs.isTrackingEnabled() && !isServiceRunning(context, TrackingForegroundService::class.java)) {
            Timber.w("Service mati tetapi status tracking AKTIF. Merestart service...")
            try {
                val serviceIntent = Intent(context, TrackingForegroundService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Timber.e(e, "Gagal restart service dari watchdog")
            }
        }

        // Jalankan sinkronisasi data Room jika sedang online
        if (NetworkMonitor.isOnline(context)) {
            try {
                FirestoreRepository(context).syncPendingFromRoom()
            } catch (e: Exception) {
                Timber.e(e, "Gagal sinkronisasi data dari watchdog")
            }
        }

        return Result.success()
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
