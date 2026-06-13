package com.lokashare.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.lokashare.receiver.ActivityRecognitionReceiver
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class ActivityRecognitionManager(private val context: Context) {

    companion object {
        const val DETECTION_INTERVAL_MS = 120_000L // 2 menit
    }

    private val client by lazy { ActivityRecognition.getClient(context) }

    private val pendingIntent by lazy {
        val intent = Intent(context, ActivityRecognitionReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    suspend fun start() {
        try {
            client.requestActivityUpdates(DETECTION_INTERVAL_MS, pendingIntent).await()
            Timber.d("ActivityRecognition update dimulai")
        } catch (e: Exception) {
            Timber.e(e, "Gagal memulai ActivityRecognition")
        }
    }

    suspend fun stop() {
        try {
            client.removeActivityUpdates(pendingIntent).await()
            Timber.d("ActivityRecognition update dihentikan")
        } catch (e: Exception) {
            Timber.e(e, "Gagal menghentikan ActivityRecognition")
        }
    }
}
