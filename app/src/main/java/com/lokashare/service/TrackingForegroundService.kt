package com.lokashare.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.lokashare.data.LocationDataModel
import com.lokashare.data.remote.FirestoreRepository
import com.google.android.gms.location.DetectedActivity
import com.lokashare.location.ActivityRecognitionManager
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
    private lateinit var activityRecognitionManager: ActivityRecognitionManager

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
        activityRecognitionManager = ActivityRecognitionManager(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        ) {
            scope.launch { activityRecognitionManager.start() }
        }
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
     * Logika pengambilan lokasi yang lebih cerdas dan efisien dengan ActivityRecognition.
     * Alur:
     * 1. Early exit jika ActivityRecognition deteksi STILL dan tidak ada mandatory update
     * 2. Early exit jika <5 menit dari pengiriman terakhir dan tidak ada mandatory update
     * 3. Ambil low-power location dulu → balanced fused → GPS strict (hanya jika perlu)
     * 4. Tentukan motion berdasarkan jarak dari posisi terakhir
     * 5. Kirim atau antri sesuai koneksi
     */
    private suspend fun checkAndSend() {
        val userName = prefs.getUserName() ?: "Unknown User"
        val deviceId = prefs.getDeviceId()
        val deviceModel = prefs.getDeviceModel()

        Timber.d("Mengambil lokasi terbaru untuk $userName ($deviceModel)...")
        
        val lastSent = prefs.getLastSentLocation()
        val timeNow = System.currentTimeMillis()
        val lastMandatory = prefs.getLastMandatorySent()
        val mandatoryDue = (timeNow - lastMandatory) >= MANDATORY_INTERVAL_MS
        
        // 1. Early exit: ActivityRecognition STILL + tidak ada mandatory update
        val activityState = prefs.getLastDetectedActivity()
        val isProbablyStill = activityState?.let { it.first == DetectedActivity.STILL && it.second >= 75 } == true
        
        if (isProbablyStill && lastSent != null && !mandatoryDue) {
            Timber.d("ActivityRecognition: STILL (confidence >= 75%). Skip lokasi untuk interval ini.")
            return
        }

        // 2. Early exit: deduplikasi 5 menit + tidak ada mandatory update
        if (lastSent != null && !mandatoryDue) {
            val (_, _, lastTs) = lastSent
            if (timeNow - lastTs < MIN_TIME_DELTA_MS) {
                Timber.d("Data sudah dikirim <5 menit lalu dan tidak ada mandatory update. Skip.")
                return
            }
        }

        val allAttempts = mutableListOf<Location>()
        var selectedLoc: Location? = null
        var source = "UNKNOWN"

        // 3. Ambil low-power fused dulu untuk hemat baterai
        val lowPowerLoc = locationRepo.getLowPowerLocation()
        if (lowPowerLoc != null) {
            allAttempts.add(lowPowerLoc)
            if (lowPowerLoc.accuracy < 35f) {
                selectedLoc = lowPowerLoc
                source = "LOW_POWER"
                Timber.d("✓ Lokasi Low-Power akurat (<35m)")
            }
        }

        // 4. Jika low-power belum cukup, coba balanced fused
        val fusedLoc = if (selectedLoc == null) {
            locationRepo.getCurrentLocation().also {
                if (it != null) {
                    allAttempts.add(it)
                    if (it.accuracy < 25f) {
                        selectedLoc = it
                        source = "FUSED"
                        Timber.d("✓ Lokasi Fused akurat (<25m)")
                    }
                }
            }
        } else null

        // 5. Tentukan motion berdasarkan kandidat lokasi terbaik sejauh ini
        val candidateLocForMotion = selectedLoc ?: fusedLoc ?: lowPowerLoc
        var distance = 0f
        var isMoving = false
        
        if (lastSent == null) {
            isMoving = true
            Timber.d("First location send - dianggap bergerak")
        } else if (candidateLocForMotion != null) {
            val (lastLat, lastLng, _) = lastSent
            distance = DistanceCalculator.distanceBetween(lastLat, lastLng, candidateLocForMotion.latitude, candidateLocForMotion.longitude)
            if (distance > THRESHOLD_METERS) {
                isMoving = true
                Timber.d("Motion detected: distance=${String.format("%.1f", distance)}m > threshold=$THRESHOLD_METERS")
            }
        }

        // 6. Hanya gunakan GPS strict jika:
        //    - low-power/fused tidak memadai AND
        //    - (first send OR mandatory update OR motion detected OR allAttempts kosong)
        val needsGpsStrict = selectedLoc == null && (lastSent == null || mandatoryDue || isMoving || allAttempts.isEmpty())
        if (needsGpsStrict) {
            Timber.d("GPS strict diperlukan: selectedLoc=null, lastSent=$lastSent, mandatoryDue=$mandatoryDue, isMoving=$isMoving")
            val gpsLoc = locationRepo.getCurrentLocationFromGps()
            if (gpsLoc != null) {
                allAttempts.add(gpsLoc)
                if (locationRepo.meetsStrictCriteria(gpsLoc, gnssManager.satellitesUsed)) {
                    selectedLoc = gpsLoc
                    source = "GPS"
                    Timber.d("✓ Lokasi GPS memenuhi kriteria ketat")
                }
            }
        }

        // 7. Best effort fallback jika tidak ada lokasi yang memenuhi threshold
        if (selectedLoc == null && allAttempts.isNotEmpty()) {
            selectedLoc = allAttempts.minByOrNull { it.accuracy }
            source = selectedLoc?.provider?.uppercase(Locale.getDefault()) ?: "FALLBACK"
            Timber.w("✗ Semua kriteria gagal. FALLBACK: Akurasi terbaik (${selectedLoc?.accuracy}m)")
            
            // Recalculate motion jika fallback location digunakan
            if (lastSent != null) {
                val (lastLat, lastLng, _) = lastSent
                distance = DistanceCalculator.distanceBetween(lastLat, lastLng, selectedLoc.latitude, selectedLoc.longitude)
                if (distance > THRESHOLD_METERS) {
                    isMoving = true
                }
            }
        }

        if (selectedLoc == null) {
            Timber.w("✗ Gagal mendapatkan lokasi apapun.")
            NotifHelper.updateNotification(this, "Terakhir cek: Gagal mendapatkan lokasi (${getCurrentTimeString()})")
            return
        }

        val loc: Location = selectedLoc

        // 8. Tentukan apakah perlu kirim data
        if (isMoving || mandatoryDue) {
            val battery = BatteryHelper.getBatteryStatus(this)

            // Untuk update stasioner (mandatory, tidak bergerak): gunakan kembali
            // doc ID stationary terakhir agar Firestore OVERWRITE, bukan buat dokumen baru.
            // Pisahkan dari regular movement untuk menghindari overwrite data movement dengan stationary.
            // Untuk pergerakan: selalu buat doc baru dengan eventId unik.
            val eventId = if (!isMoving && mandatoryDue) {
                prefs.getLastStationaryDocId() ?: "${deviceId}_STATIONARY_${timeNow}"
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
                    firestoreRepo.saveToRoom(payload)
                }
            } else {
                firestoreRepo.saveToRoom(payload)
                success = true
            }

            if (success) {
                prefs.saveLastSentLocation(loc.latitude, loc.longitude, timeNow)
                if (mandatoryDue) {
                    prefs.saveLastMandatorySent(timeNow)
                    if (!isMoving) {
                        prefs.saveLastStationaryDocId(eventId)
                    }
                }
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
        locationRepo.cleanup()
        scope.launch { activityRecognitionManager.stop() }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
