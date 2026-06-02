package com.lokashare.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.lokashare.data.LocationDataModel
import com.lokashare.data.local.PendingLocationDao
import com.lokashare.data.local.PendingLocationEntity
import com.lokashare.data.local.TrackingDatabase
import com.lokashare.util.PrefsManager
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class FirestoreRepository(private val context: Context) {

    private val db: TrackingDatabase = TrackingDatabase.getInstance(context)
    private val dao: PendingLocationDao = db.pendingLocationDao()
    private val prefs = PrefsManager(context)

    private val isFirebaseAvailable: Boolean
        get() = try {
            FirebaseApp.getInstance()
            true
        } catch (e: Exception) {
            false
        }

    private val firestore: FirebaseFirestore?
        get() = if (isFirebaseAvailable) FirebaseFirestore.getInstance() else null

    private val auth: FirebaseAuth?
        get() = if (isFirebaseAvailable) FirebaseAuth.getInstance() else null

    /**
     * Pastikan user terautentikasi secara anonim agar mematuhi Security Rules
     */
    private suspend fun ensureAuthenticated(): Boolean {
        val authInstance = auth ?: return false
        if (authInstance.currentUser != null) return true
        
        return try {
            authInstance.signInAnonymously().await()
            Timber.d("Autentikasi Anonim Berhasil")
            true
        } catch (e: Exception) {
            Timber.e(e, "Gagal Autentikasi Anonim")
            false
        }
    }

    /**
     * Kirim data langsung saat online
     */
    suspend fun sendDirect(payload: LocationDataModel, overwriteLastDoc: Boolean = false): Boolean {
        if (!isFirebaseAvailable) {
            Timber.w("Firebase tidak terinisialisasi. Menyimpan ke Room.")
            saveToRoom(payload)
            return false
        }

        return try {
            val authenticated = ensureAuthenticated()
            if (!authenticated) {
                Timber.w("Gagal autentikasi ke Firebase. Menyimpan ke Room.")
                saveToRoom(payload)
                return false
            }

            val fs = firestore ?: throw IllegalStateException("Firestore unavailable")

            if (overwriteLastDoc) {
                val lastDocId = prefs.getLastSentDocId()
                if (!lastDocId.isNullOrEmpty()) {
                    fs.collection("locations")
                        .document(lastDocId)
                        .set(payload.toFirestoreMap())
                        .await()
                    Timber.d("Data lokasi menimpa dokumen Firestore: $lastDocId")
                    // keep last doc id
                    return true
                }
            }

            val docRef = fs.collection("locations")
                .add(payload.toFirestoreMap())
                .await()
            try {
                prefs.saveLastSentDocId(docRef.id)
            } catch (e: Exception) {
                Timber.w(e, "Gagal menyimpan lastDocId ke prefs")
            }
            Timber.d("Data lokasi terkirim langsung ke Firestore (docId=${docRef.id})")
            true
        } catch (e: Exception) {
            Timber.e(e, "Gagal mengirim data langsung ke Firestore. Menyimpan ke Room.")
            saveToRoom(payload)
            false
        }
    }

    /**
     * Simpan data ke antrian lokal (offline queue)
     */
    suspend fun saveToRoom(payload: LocationDataModel) {
        try {
            val entity = PendingLocationEntity(
                deviceId = payload.deviceId,
                userName = payload.userName,
                latitude = payload.latitude,
                longitude = payload.longitude,
                accuracy = payload.accuracy,
                battery = payload.battery,
                isCharging = payload.isCharging,
                localTimestamp = payload.localTimestamp,
                status = "PENDING"
            )
            dao.insert(entity)
            dao.trimOldestIfOverLimit()
            Timber.d("Data lokasi disimpan di Room DB")
        } catch (e: Exception) {
            Timber.e(e, "Gagal menyimpan ke Room DB")
        }
    }

    /**
     * Sinkronisasi data pending dari Room ke Firestore
     */
    suspend fun syncPendingFromRoom() {
        if (!isFirebaseAvailable) return
        
        val pending = dao.getAllPending()
        if (pending.isEmpty()) {
            Timber.d("Tidak ada data pending di Room DB")
            return
        }

        Timber.d("Memulai sinkronisasi ${pending.size} data pending dari Room...")

        val authenticated = ensureAuthenticated()
        if (!authenticated) {
            Timber.w("Gagal autentikasi untuk sinkronisasi. Dibatalkan.")
            return
        }

        val fs = firestore ?: return

        for (item in pending) {
            try {
                val payload = LocationDataModel(
                    deviceId = item.deviceId,
                    userName = item.userName,
                    deviceModel = prefs.getDeviceModel(),
                    latitude = item.latitude,
                    longitude = item.longitude,
                    accuracy = item.accuracy,
                    battery = item.battery,
                    isCharging = item.isCharging,
                    localTimestamp = item.localTimestamp,
                    source = "offline_sync"
                )
                
                fs.collection("locations")
                    .add(payload.toFirestoreMap())
                    .await()
                
                dao.markAsSent(item.id)
                Timber.d("Sync berhasil untuk item ID ${item.id}")
            } catch (e: Exception) {
                Timber.e(e, "Gagal sync item ID ${item.id}. Menghentikan loop.")
                break // Hentikan jika terjadi error koneksi di tengah jalan
            }
        }

        // Bersihkan data yang sudah terkirim
        try {
            dao.deleteSent()
        } catch (e: Exception) {
            Timber.e(e, "Gagal menghapus data tersinkronisasi dari Room")
        }
    }
}
