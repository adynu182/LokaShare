package com.lokashare.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lokashare.service.TrackingForegroundService
import com.lokashare.util.PrefsManager
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Timber.d("BootReceiver menerima aksi: $action")
        
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = PrefsManager(context)
            if (prefs.isTrackingEnabled()) {
                Timber.d("Mencoba me-restart tracking service setelah boot...")
                try {
                    val serviceIntent = Intent(context, TrackingForegroundService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Timber.e(e, "Gagal menjalankan service setelah boot")
                }
            }
        }
    }
}
