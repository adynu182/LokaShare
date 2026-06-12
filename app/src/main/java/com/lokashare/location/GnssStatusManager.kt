package com.lokashare.location

import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import timber.log.Timber

class GnssStatusManager(private val context: Context) {

    @Volatile var satellitesUsed: Int = 0
        private set
    @Volatile var satellitesTotal: Int = 0
        private set

    private val gnssThread = HandlerThread("GnssCallbackThread").also { it.start() }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var total = 0
            for (i in 0 until status.satelliteCount) {
                total++
                if (status.usedInFix(i)) used++
            }
            satellitesUsed = used
            satellitesTotal = total
            Timber.v("GNSS: $used/$total satelit dipakai untuk fix")
        }
    }

    fun register() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            try {
                lm.registerGnssStatusCallback(gnssCallback, android.os.Handler(gnssThread.looper))
                Timber.d("GnssStatusManager terdaftar")
            } catch (e: SecurityException) {
                Timber.e(e, "Gagal mendaftarkan GnssStatus callback")
            }
        }
    }

    fun unregister() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.unregisterGnssStatusCallback(gnssCallback)
            gnssThread.quitSafely()
        } catch (e: Exception) {
            Timber.e(e, "Gagal unregister GnssStatus callback")
        }
    }
}
