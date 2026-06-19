package com.lokashare.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

                // Verifikasi izin runtime sebelum start service.
                // Android 14+: ForegroundService type=location melempar SecurityException
                // jika ACCESS_FINE_LOCATION atau ACCESS_BACKGROUND_LOCATION tidak granted
                // saat service dicoba distart dari background (misalnya setelah reboot).
                val hasFineLocation = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasBgLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                } else true

                if (hasFineLocation && hasBgLocation) {
                    Timber.d("Izin lokasi komplit. Me-restart tracking service setelah boot/update...")
                    try {
                        val serviceIntent = Intent(context, TrackingForegroundService::class.java)
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        Timber.e(e, "Gagal menjalankan service setelah boot")
                    }
                } else {
                    // Izin lokasi dicabut dari Settings saat app tidak aktif.
                    // Reset flag tracking agar UI sinkron dengan kondisi sebenarnya —
                    // user akan diminta grant ulang saat buka app.
                    Timber.w(
                        "Izin lokasi tidak lengkap (fine=$hasFineLocation, bg=$hasBgLocation). " +
                        "Batalkan auto-restart dan reset tracking state."
                    )
                    prefs.setTrackingEnabled(false)
                }
            }
        }
    }
}
