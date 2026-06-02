package com.lokashare.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.location.Location
import android.os.IBinder
import com.lokashare.data.LocationDataModel
import com.lokashare.data.remote.FirestoreRepository
import com.lokashare.location.LocationRepository
import com.lokashare.receiver.NetworkChangeReceiver
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
    private var networkReceiver: NetworkChangeReceiver? = null

    companion object {
        const val THRESHOLD_METERS = 200f
        const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 menit
        const val MANDATORY_INTERVAL_MS = 60 * 60 * 1000L // 60 menit
        const val ACCURACY_THRESHOLD = 20f // kirim jika akurasi <= 20m
        const val MIN_TIME_DELTA_MS = 5 * 60 * 1000L // 5 menit deduplikasi
    }

    override fun onCreate() {
        super.onCreate()
        locationRepo = LocationRepository(applicationContext)
        firestoreRepo = FirestoreRepository(applicationContext)
        prefs = PrefsManager(applicationContext)

        // Inisialisasi Notification Channel
        NotifHelper.createNotificationChannel(applicationContext)

        // Daftarkan NetworkChangeReceiver secara programatik (API 24+)
        try {
            networkReceiver = NetworkChangeReceiver()
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            registerReceiver(networkReceiver, filter)
            Timber.d("NetworkChangeReceiver terdaftar di Service")
        } catch (e: Exception) {
            Timber.e(e, "Gagal mendaftarkan NetworkChangeReceiver")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("TrackingForegroundService dijalankan...")

        // Mulai Foreground dengan Notifikasi
        startForeground(NotifHelper.NOTIF_ID, NotifHelper.build(this))

        // Jalankan loop pelacakan di coroutine
        scope.launch {
            startTrackingLoop()
        }

        return START_STICKY // OS akan me-restart service jika ter-kill
    }

    private suspend fun startTrackingLoop() {
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            try {
                checkAndSend()
            } catch (e: Exception) {
                Timber.e(e, "Error di dalam loop pelacakan")
            }
            delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun checkAndSend() {
        val userName = prefs.getUserName() ?: "Unknown User"
        val deviceId = prefs.getDeviceId()
        val deviceModel = prefs.getDeviceModel()

        Timber.d("Mengambil lokasi terbaru untuk $userName ($deviceModel)...")
        
        // Penyimpanan sementara untuk semua percobaan
        val allAttempts = mutableListOf<Location>()
        var selectedLoc: Location? = null
        
        // ===== TAHAP 1: 10 percobaan dengan target akurasi <= 20m =====
        Timber.d("=== TAHAP 1: Mencari akurasi <= 20m (10 percobaan) ===")
        for (attempt in 1..10) {
            val currentLoc = locationRepo.getCurrentLocation()
            if (currentLoc != null) {
                allAttempts.add(currentLoc)
                Timber.d("Tahap 1 - Percobaan $attempt: akurasi = ${currentLoc.accuracy}m")
                
                // Target utama tahap 1: akurasi <= 20m
                if (currentLoc.accuracy <= ACCURACY_THRESHOLD) {
                    selectedLoc = currentLoc
                    Timber.d("✓ Tahap 1 - BERHASIL! Ditemukan akurasi <= 20m pada percobaan ke-$attempt")
                    break
                }
            } else {
                Timber.w("Tahap 1 - Percobaan $attempt: gagal mendapatkan lokasi")
            }
            
            if (attempt < 10) {
                delay(10_000L) // 10 detik sebelum percobaan berikutnya
            }
        }
        
        // Jika tahap 1 tidak menemukan <= 20m, cari yang < 100m dari percobaan tahap 1
        if (selectedLoc == null && allAttempts.isNotEmpty()) {
            val candidate = allAttempts.firstOrNull { it.accuracy < 100f }
            if (candidate != null) {
                selectedLoc = candidate
                Timber.d("✓ Tahap 1 - Fallback: Ditemukan akurasi < 100m (${candidate.accuracy}m)")
            } else {
                Timber.w("✗ Tahap 1 - Tidak ditemukan akurasi < 100m. Lanjut Tahap 2...")
            }
        }
        
        // ===== TAHAP 2: 10 percobaan lagi dengan target akurasi <= 100m =====
        if (selectedLoc == null) {
            Timber.d("=== TAHAP 2: Mencari akurasi <= 100m (10 percobaan) ===")
            for (attempt in 1..10) {
                val currentLoc = locationRepo.getCurrentLocation()
                if (currentLoc != null) {
                    allAttempts.add(currentLoc)
                    Timber.d("Tahap 2 - Percobaan $attempt: akurasi = ${currentLoc.accuracy}m")
                    
                    // Target tahap 2: akurasi <= 100m
                    if (currentLoc.accuracy <= 100f) {
                        selectedLoc = currentLoc
                        Timber.d("✓ Tahap 2 - BERHASIL! Ditemukan akurasi <= 100m pada percobaan ke-$attempt")
                        break
                    }
                } else {
                    Timber.w("Tahap 2 - Percobaan $attempt: gagal mendapatkan lokasi")
                }
                
                if (attempt < 10) {
                    delay(10_000L) // 10 detik sebelum percobaan berikutnya
                }
            }
        }
        
        // ===== FALLBACK: Pilih akurasi terbaik dari semua percobaan (tahap 1 + 2) =====
        if (selectedLoc == null && allAttempts.isNotEmpty()) {
            selectedLoc = allAttempts.minByOrNull { it.accuracy }
            Timber.w("✗ Semua tahap gagal. FALLBACK: Menggunakan akurasi terbaik (${selectedLoc?.accuracy}m)")
        }
        
        // Jika tidak ada lokasi sama sekali
        if (selectedLoc == null) {
            Timber.w("✗ FATAL: Tidak ada lokasi yang berhasil diambil dalam 20 percobaan total.")
            NotifHelper.updateNotification(
                this,
                "Terakhir cek: Gagal mendapatkan lokasi yang memadai pada ${getCurrentTimeString()}"
            )
            return
        }

        val loc: Location = selectedLoc
        Timber.d("Lokasi terpilih: akurasi = ${loc.accuracy}m, lat = ${loc.latitude}, lng = ${loc.longitude}")

        val lastSent = prefs.getLastSentLocation()
        var distance = 0f
        var shouldSend = false
        val timeNow = System.currentTimeMillis()

        if (lastSent == null) {
            shouldSend = true
            Timber.d("Lokasi pertama kali dideteksi, langsung kirim.")
        } else {
            val (lastLat, lastLng, _) = lastSent
            distance = DistanceCalculator.distanceBetween(
                lastLat, lastLng,
                loc.latitude, loc.longitude
            )
            Timber.d("Jarak bergeser: ${distance}m dari posisi sebelumnya.")
            if (distance > THRESHOLD_METERS) {
                shouldSend = true
            }
        }

        // Cek apakah sudah waktunya pengiriman wajib 60 menit
        val lastMandatory = prefs.getLastMandatorySent()
        val mandatoryDue = (timeNow - lastMandatory) >= MANDATORY_INTERVAL_MS

        // Cek deduplikasi waktu: jika data sebelumnya dikirim kurang dari 5 menit lalu, skip kecuali pengiriman wajib
        if (lastSent != null) {
            val (_, _, lastTs) = lastSent
            val delta = timeNow - lastTs
            if (delta < MIN_TIME_DELTA_MS && !mandatoryDue) {
                Timber.d("Data terakhir dikirim $delta ms lalu (<5 menit). Skip untuk menghindari double.")
                NotifHelper.updateNotification(
                    this,
                    "Posisi tidak berubah dalam 5 menit terakhir | Cek terakhir: ${getCurrentTimeString()}"
                )
                return
            }
        }

        if (shouldSend || mandatoryDue) {
            val battery = BatteryHelper.getBatteryStatus(this)
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
                source = "online"
            )

            val online = NetworkMonitor.isOnline(this)
            // Jika pengiriman wajib dan jarak dari posisi terakhir < THRESHOLD_METERS,
            // kita pilih untuk menimpa (overwrite) dokumen Firestore terakhir jika tersedia.
            var overwriteLastDoc = false
            if (mandatoryDue && lastSent != null) {
                val (lastLat, lastLng, _) = lastSent
                distance = DistanceCalculator.distanceBetween(
                    lastLat, lastLng,
                    loc.latitude, loc.longitude
                )
                if (distance < THRESHOLD_METERS) {
                    overwriteLastDoc = true
                }
            }

            val success = if (online) {
                // Kirim langsung ke Firestore (opsional menimpa dokumen terakhir)
                val ok = firestoreRepo.sendDirect(payload, overwriteLastDoc)
                if (ok) {
                    // Coba sync pending data sekalian
                    firestoreRepo.syncPendingFromRoom()
                }
                ok
            } else {
                // Simpan ke Room DB
                firestoreRepo.saveToRoom(payload)
                true
            }

            if (success) {
                // Simpan state koordinat terkirim HANYA jika aman terkirim/tersimpan
                prefs.saveLastSentLocation(loc.latitude, loc.longitude, timeNow)
                if (mandatoryDue) {
                    prefs.saveLastMandatorySent(timeNow)
                }
                Timber.d("State lastSentLocation diperbarui.")
            }

            val statusText = if (online) "Online (Firestore)" else "Offline (Room DB)"
            NotifHelper.updateNotification(
                this,
                "Lokasi dikirim ($statusText) | Akurasi: ${String.format("%.1f", loc.accuracy)}m | Bat: ${battery.percentage}% | ${getCurrentTimeString()}"
            )
        } else {
            Timber.d("Posisi tidak bergerak signifikan (< 200m). Jarak: ${distance}m. Skip kirim.")
            NotifHelper.updateNotification(
                this,
                "Posisi tidak bergerak signifikan | Cek terakhir: ${getCurrentTimeString()}"
            )
        }
    }

    private fun getCurrentTimeString(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.w("Aplikasi di-swipe dari Recents Menu!")
        
        // Kirim siaran untuk me-restart service jika pelacakan diaktifkan
        if (prefs.isTrackingEnabled()) {
            val restartIntent = Intent(this, RestartReceiver::class.java).apply {
                action = "ACTION_RESTART_TRACKING"
            }
            sendBroadcast(restartIntent)
        }
    }

    override fun onDestroy() {
        Timber.d("TrackingForegroundService dihancurkan.")
        
        // Batalkan coroutine
        scope.cancel()

        // Unregister NetworkChangeReceiver
        try {
            networkReceiver?.let {
                unregisterReceiver(it)
            }
            Timber.d("NetworkChangeReceiver dinonaktifkan")
        } catch (e: Exception) {
            Timber.e(e, "Gagal me-unregister NetworkChangeReceiver")
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
