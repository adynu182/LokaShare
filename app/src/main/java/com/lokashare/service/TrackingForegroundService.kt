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
     * Logika pelacakan berdasarkan jarak dan mode (MOVING/STATIONARY).
     * Alur:
     * 1. Tentukan motion berdasarkan jarak dari posisi terakhir (>200m = moving)
     * 2. Jika MOVING: catat setiap 5 menit jika distance >200m
     * 3. Jika STATIONARY: kirim satu record pertama kali, update setiap 60 menit
     * 4. Gunakan eventId sama per periode stasioner untuk overwrite (hemat storage)
     */
    private suspend fun checkAndSend() {
        val userName = prefs.getUserName() ?: "Unknown User"
        val deviceId = prefs.getDeviceId()
        val deviceModel = prefs.getDeviceModel()

        Timber.d("Mengambil lokasi terbaru untuk $userName ($deviceModel)...")
        
        val lastSent = prefs.getLastSentLocation()
        val timeNow = System.currentTimeMillis()
        val lastMode = prefs.getLastMode() // "MOVING" atau "STATIONARY"
        val lastStationarySentTime = prefs.getLastStationarySentTime()
        val lastStationaryDocId = prefs.getLastStationaryDocId()

        val allAttempts = mutableListOf<Location>()
        var selectedLoc: Location? = null
        var source = "UNKNOWN"

        // Ambil low-power fused dulu untuk hemat baterai
        val lowPowerLoc = locationRepo.getLowPowerLocation()
        if (lowPowerLoc != null) {
            allAttempts.add(lowPowerLoc)
            if (lowPowerLoc.accuracy < 35f) {
                selectedLoc = lowPowerLoc
                source = "LOW_POWER"
                Timber.d("✓ Lokasi Low-Power akurat (<35m)")
            }
        }

        // Jika low-power belum cukup, coba balanced fused
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

        // Tentukan motion berdasarkan jarak dari lokasi terakhir
        val candidateLocForMotion = selectedLoc ?: fusedLoc ?: lowPowerLoc
        var distance = 0f
        var isMoving = false
        
        if (lastSent == null) {
            // First send - tentukan dari akurasi lokasi dan perubahan yang ada
            isMoving = true
            Timber.d("First location send - initialized as MOVING")
        } else if (candidateLocForMotion != null) {
            val (lastLat, lastLng, _) = lastSent
            distance = DistanceCalculator.distanceBetween(lastLat, lastLng, candidateLocForMotion.latitude, candidateLocForMotion.longitude)
            isMoving = distance > THRESHOLD_METERS
            if (isMoving) {
                Timber.d("Motion detected: distance=${String.format("%.1f", distance)}m > threshold=$THRESHOLD_METERS")
            }
        }

        // Hanya gunakan GPS strict untuk first send atau saat bergerak jika diperlukan
        val needsGpsStrict = selectedLoc == null && (lastSent == null || isMoving || allAttempts.isEmpty())
        if (needsGpsStrict) {
            Timber.d("GPS strict diperlukan: selectedLoc=null, lastSent=$lastSent, isMoving=$isMoving")
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

        // Best effort fallback jika tidak ada lokasi yang memenuhi threshold
        if (selectedLoc == null && allAttempts.isNotEmpty()) {
            selectedLoc = allAttempts.minByOrNull { it.accuracy }
            source = selectedLoc?.provider?.uppercase(Locale.getDefault()) ?: "FALLBACK"
            Timber.w("✗ Semua kriteria gagal. FALLBACK: Akurasi terbaik (${selectedLoc?.accuracy}m)")
            
            // Recalculate motion jika fallback location digunakan
            val fallbackLoc = selectedLoc
            if (lastSent != null && fallbackLoc != null) {
                val (lastLat, lastLng, _) = lastSent
                distance = DistanceCalculator.distanceBetween(lastLat, lastLng, fallbackLoc.latitude, fallbackLoc.longitude)
                isMoving = distance > THRESHOLD_METERS
            }
        }

        if (selectedLoc == null) {
            Timber.w("✗ Gagal mendapatkan lokasi apapun.")
            NotifHelper.updateNotification(this, "Terakhir cek: Gagal mendapatkan lokasi (${getCurrentTimeString()})")
            return
        }

        val loc: Location = selectedLoc

        // Deteksi transisi mode
        val currentMode = if (isMoving) "MOVING" else "STATIONARY"
        val justTransitioned = currentMode != lastMode

        val battery = BatteryHelper.getBatteryStatus(this)

        if (currentMode == "MOVING") {
            // MODE MOVING: Catat setiap 5 menit jika distance > 200m
            if (justTransitioned) {
                // Transisi dari STATIONARY ke MOVING
                Timber.d("Transisi: STATIONARY → MOVING")
                prefs.clearLastStationaryDocId()
                prefs.saveLastMode("MOVING")
            }
            
            // First-ever send: lastSent == null -> send immediately (no distance check)
            if (lastSent == null) {
                Timber.d("First-ever MOVING send (no previous lastSent). Sending initial record.")
                val eventId = "${deviceId}_$timeNow"
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
                    isStationary = false,
                    eventId = eventId
                )

                sendAndPersist(payload)
                prefs.saveLastMode("MOVING")
                NotifHelper.updateNotification(this, "Bergerak (initial) | Akurasi: ${String.format("%.1f", loc.accuracy)}m | Bat: ${battery.percentage}% | ${getCurrentTimeString()}")
                return
            }

            // Cek 5-menit cadence
            if (lastSent != null) {
                val (_, _, lastTs) = lastSent
                if (timeNow - lastTs < MIN_TIME_DELTA_MS) {
                    Timber.d("Skip: belum 5 menit sejak pengiriman terakhir (delta=${timeNow - lastTs}ms)")
                    NotifHelper.updateNotification(this, "Bergerak | Akurasi: ${String.format("%.1f", loc.accuracy)}m | Bat: ${battery.percentage}% | ${getCurrentTimeString()}")
                    return
                }
            }

            // Cek jarak threshold
            if (distance <= THRESHOLD_METERS) {
                Timber.d("Skip: jarak <200m (distance=$distance)")
                NotifHelper.updateNotification(this, "Bergerak (dekat) | Akurasi: ${String.format("%.1f", loc.accuracy)}m | Bat: ${battery.percentage}% | ${getCurrentTimeString()}")
                return
            }

            // Kirim movement record
            val eventId = "${deviceId}_${timeNow}"
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
                isStationary = false,
                eventId = eventId
            )

            sendAndPersist(payload)
            NotifHelper.updateNotification(this, "Bergerak (kirim) | Akurasi: ${String.format("%.1f", loc.accuracy)}m | Bat: ${battery.percentage}% | ${getCurrentTimeString()}")

        } else {
            // MODE STATIONARY: Kirim pertama kali, update setiap 60 menit
            if (justTransitioned) {
                // Transisi dari MOVING ke STATIONARY: kirim satu record segera
                Timber.d("Transisi: MOVING → STATIONARY")
                val newStationaryDocId = "${deviceId}_STATIONARY_${timeNow}"
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
                    isStationary = true,
                    eventId = newStationaryDocId
                )

                sendAndPersist(payload)
                prefs.saveLastStationaryDocId(newStationaryDocId)
                prefs.saveLastStationarySentTime(timeNow)
                prefs.saveLastMode("STATIONARY")
                
                NotifHelper.updateNotification(this, "Diam (tercatat) | Akurasi: ${String.format("%.1f", loc.accuracy)}m | Bat: ${battery.percentage}% | ${getCurrentTimeString()}")

            } else {
                // Sudah STATIONARY: cek apakah perlu update setelah 60 menit
                if (timeNow - lastStationarySentTime >= MANDATORY_INTERVAL_MS) {
                    // Update dengan eventId yang sama (overwrite)
                    Timber.d("Update stationary setelah 60 menit")
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
                        isStationary = true,
                        eventId = lastStationaryDocId ?: "${deviceId}_STATIONARY_${timeNow}"
                    )

                    sendAndPersist(payload)
                    prefs.saveLastStationarySentTime(timeNow)
                    
                    NotifHelper.updateNotification(this, "Diam (update 60m) | Akurasi: ${String.format("%.1f", loc.accuracy)}m | Bat: ${battery.percentage}% | ${getCurrentTimeString()}")
                } else {
                    // Skip: belum 60 menit
                    Timber.d("Skip: masih diam, belum 60 menit (delta=${timeNow - lastStationarySentTime}ms)")
                    NotifHelper.updateNotification(this, "Diam | Cek terakhir: ${getCurrentTimeString()}")
                }
            }
        }
    }

    private suspend fun sendAndPersist(payload: LocationDataModel) {
        val online = NetworkMonitor.isOnline(this)
        var success = false

        if (online) {
            success = firestoreRepo.sendDirect(payload, isStationary = payload.isStationary)
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
            prefs.saveLastSentLocation(payload.latitude, payload.longitude, System.currentTimeMillis())
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
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
