package com.lokashare.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lokashare.service.TrackingForegroundService
import com.lokashare.util.PrefsManager
import timber.log.Timber

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PrefsManager(context)
        Timber.d("RestartReceiver menerima siaran: ${intent.action}")

        if (prefs.isTrackingEnabled()) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val serviceIntent = Intent(context, TrackingForegroundService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Timber.d("Service direstart oleh RestartReceiver")
                } catch (e: Exception) {
                    Timber.e(e, "Gagal merestart service dari RestartReceiver")
                }
            }, 1000L)
        }
    }
}
