package com.lokashare.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.location.Location
import android.os.IBinder
import com.lokashare.data.LocationDataModel
import com.lokashare.data.remote.FirestoreRepository
import com.lokashare.location.GnssStatusManager
import com.lokashare.location.LocationRepository
import com.lokashare.receiver.RestartReceiver
import com.lokashare.util.BatteryHelper
import com.lokashare.util.DistanceCalculator
import com.lokashare.util.NetworkMonitor
import com.lokashare.util.NotifHelper
import com.lokashare.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackingForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationRepo: LocationRepository
    private lateinit var firestoreRepo: FirestoreRepository
    private lateinit var prefs: PrefsManager
    private lateinit var gnssManager: GnssStatusManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Timber.d("Koneksi internet tersedia, memulai sync pending...")
            scope.launch { firestoreRepo.syncPendingFromRoom() }
        }
    }

    companion object {
        const val THRESHOLD_METERS = 200f
        const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 menit
        const val MANDATORY_INTERVAL_MS = 60 * 60 * 1000L // 60 menit
        const val MIN_TIME_DELTA_MS = 5 * 60 * 1000L // 5 menit deduplikasi
        
        @Volatile var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        locationRepo = LocationRepository(applicationContext)
        firestoreRepo = FirestoreRepository(applicationContext)
        prefs = PrefsManager(applicationContext)
        gnssManager = GnssStatusManager(applicationContext)

        gnssManager.register()
        NotifHelper.createNotificationChannel(applicationContext)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("TrackingForegroundService dijalankan...")
        startForeground(NotifHelper.NOTIF_ID, NotifHelper.build(this))

        scope.launch {
            startTrackingLoop()
        }

        return START_STICKY
    }

    private suspend fun startTrackingLoop() {
        while (isRunning) {
            kotlin.coroutines.coroutineContext.ensureActive()
            try {
                checkAndSend()
            } catch (e: Exception) {
                Timber.e(e, "Error di dalam loop pelacakan")
            }
            delay(CHECK_INTERVAL_MS)
        }
    }

    /**
     * Logika pengambilan lokasi yang lebih cerdas dan efisien.
     */
    private suspend fun checkAndSend() {
        val userName = prefs.getUserName() ?: "Unknown User"
        val deviceId = prefs.getDeviceId()
        val deviceModel = prefs.getDeviceModel()

        Timber.d("Mengambil lokasi terbaru untuk $userName ($deviceModel)...")
        
        val allAttempts = mutableListOf<Location>()
        var selectedLoc: Location? = null
        var source = "UNKNOWN"

        // Strategi: Coba GPS strict 1x, lalu Fused 2x
        // Total budget waktu pengambilan lokasi dikurangi agar tidak memblokir thread terlalu lama
        
        // 1. Coba GPS Ketat
        val gpsLoc = locationRepo.getCurrentLocationFromGps()
        if (gpsLoc != null) {
            allAttempts.add(gpsLoc)
            if (locationRepo.meetsStrictCriteria(gpsLoc, gnssManager.satellitesUsed)) {
                selectedLoc = gpsLoc
                source = "GPS"
                Timber.d("✓ Lokasi GPS memenuhi kriteria ketat")
            }
        }

        // 2. Fallback Fused if needed
        if (selectedLoc == null) {
            for (i in 1..2) {
                val fusedLoc = locationRepo.getCurrentLocation()
                if (fusedLoc != null) {
                    allAttempts.add(fusedLoc)
                    if (fusedLoc.accuracy < 25f) {
                        selectedLoc = fusedLoc
                        source = "FUSED"
                        Timber.d("✓ Lokasi Fused akurat (<25m)")
                        break
                    }
                }
                if (i < 2) delay(5000L)
            }
        }

        // 3. Best effort fallback
        if (selectedLoc == null && allAttempts.isNotEmpty()) {
            selectedLoc = allAttempts.minByOrNull { it.accuracy }
            source = selectedLoc?.provider?.uppercase(Locale.getDefault()) ?: "FALLBACK"
            Timber.w("✗ Semua kriteria gagal. FALLBACK: Akurasi terbaik (${selectedLoc?.accuracy}m)")
        }
        
        if (selectedLoc == null) {
            Timber.w("✗ Gagal mendapatkan lokasi apapun.")
            NotifHelper.updateNotification(this, "Terakhir cek: Gagal mendapatkan lokasi (${getCurrentTimeString()})")
            return
        }

        val loc: Location = selectedLoc
        val lastSent = prefs.getLastSentLocation()
        val timeNow = System.currentTimeMillis()
        
        var distance = 0f
        var isMoving = false

        if (lastSent == null) {
            isMoving = true
        } else {
            val (lastLat, lastLng, _) = lastSent
            distance = DistanceCalculator.distanceBetween(lastLat, lastLng, loc.latitude, loc.longitude)
            if (distance > THRESHOLD_METERS) isMoving = true
        }

        val lastMandatory = prefs.getLastMandatorySent()
        val mandatoryDue = (timeNow - lastMandatory) >= MANDATORY_INTERVAL_MS

        // Deduplikasi: SELALU skip jika < 5 menit dari pengiriman terakhir,
        // termasuk saat mandatory — tidak ada gunanya update data yang baru dikirim.
        if (lastSent != null) {
            val (_, _, lastTs) = lastSent
            if (timeNow - lastTs < MIN_TIME_DELTA_MS) {
                Timber.d("Data sudah dikirim <5 menit lalu. Skip (mandatory=$mandatoryDue).")
                return
            }
        }

        if (isMoving || mandatoryDue) {
            val battery = BatteryHelper.getBatteryStatus(this)

            // Untuk update stasioner (mandatory, tidak bergerak): gunakan kembali
            // doc ID terakhir agar Firestore OVERWRITE, bukan buat dokumen baru.
            // Untuk pergerakan: selalu buat doc baru dengan eventId unik.
            val eventId = if (!isMoving && mandatoryDue) {
                prefs.getLastSentDocId() ?: "${deviceId}_${timeNow}"
            } else {
                "${deviceId}_${timeNow}"
            }
            val payload = LocationDataModel(
                deviceId = deviceId,
                userName = userName,
                deviceModel = deviceModel,
                latitude = loc.latitude,
                longitude = loc.longitude,
                accuracy = loc.accuracy,
                battery = battery.percentage,
                isCharging = battery.isCharging,
                localTimestamp = timeNow,
                ageMs = timeNow - loc.time,
                satellitesUsed = gnssManager.satellitesUsed,
                source = source,
                isStationary = !isMoving && mandatoryDue,
                eventId = eventId
            )

            val online = NetworkMonitor.isOnline(this)
            var success = false

            if (online) {
                success = firestoreRepo.sendDirect(payload, isStationary = !isMoving && mandatoryDue)
                if (success) {
                    firestoreRepo.syncPendingFromRoom()
                } else {
                    // Gagal kirim walau online -> simpan ke Room
                    firestoreRepo.saveToRoom(payload)
                }
            } else {
                // Offline -> simpan ke Room
                firestoreRepo.saveToRoom(payload)
                success = true // Dianggap sukses antri
            }

            if (success) {
                prefs.saveLastSentLocation(loc.latitude, loc.longitude, timeNow)
                if (mandatoryDue) prefs.saveLastMandatorySent(timeNow)
            }

            val statusText = if (online && success) "Online" else if (!online) "Offline (Queued)" else "Retry Queued"
            NotifHelper.updateNotification(
                this,
                "Lokasi $statusText | Akurasi: ${String.format("%.1f", loc.accuracy)}m | Bat: ${battery.percentage}% | ${getCurrentTimeString()}"
            )
        } else {
            NotifHelper.updateNotification(this, "Posisi stasioner | Cek terakhir: ${getCurrentTimeString()}")
        }
    }

    private fun getCurrentTimeString(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (prefs.isTrackingEnabled()) {
            sendBroadcast(Intent(this, RestartReceiver::class.java).apply { action = "ACTION_RESTART_TRACKING" })
        }
    }

    override fun onDestroy() {
        isRunning = false
        gnssManager.unregister()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
