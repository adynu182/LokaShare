package com.lokashare.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.lokashare.util.NetworkMonitor
import com.lokashare.worker.SyncWorker
import timber.log.Timber

class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("NetworkChangeReceiver mendeteksi perubahan jaringan")
        if (NetworkMonitor.isOnline(context)) {
            Timber.d("Koneksi internet terdeteksi ONLINE. Memicu SyncWorker...")
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync_work_network_change",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
