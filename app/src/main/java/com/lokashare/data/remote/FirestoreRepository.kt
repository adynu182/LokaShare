package com.lokashare.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
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
     * Pastikan user terautentikasi dan token masih valid.
     */
    private suspend fun ensureAuthenticated(): Boolean {
        val authInstance = auth ?: return false
        val user = authInstance.currentUser
        
        return if (user != null) {
            try {
                user.getIdToken(true).await() // Force refresh token
                true
            } catch (e: Exception) {
                Timber.w("Token refresh gagal, mencoba sign-in ulang")
                try {
                    authInstance.signInAnonymously().await()
                    authInstance.currentUser != null
                } catch (re: Exception) {
                    Timber.e(re, "Gagal re-autentikasi anonim")
                    false
                }
            }
        } else {
            try {
                authInstance.signInAnonymously().await()
                Timber.d("Autentikasi Anonim Berhasil")
                true
            } catch (e: Exception) {
                Timber.e(e, "Gagal Autentikasi Anonim")
                false
            }
        }
    }

    /**
     * Kirim data langsung saat online.
     * Menggunakan eventId secara konsisten sebagai document ID.
     */
    suspend fun sendDirect(
        payload: LocationDataModel,
        isStationary: Boolean = false
    ): Boolean {
        if (!isFirebaseAvailable) return false

        return try {
            val authenticated = ensureAuthenticated()
            if (!authenticated) return false

            val fs = firestore ?: throw IllegalStateException("Firestore unavailable")
            
            // SELALU gunakan eventId sebagai docId untuk menghindari duplikasi/overwrite acak
            val docId = payload.eventId

            // Jika stasioner, kita tetap kirim dokumen baru tetapi dengan flag stationary
            // Ini lebih baik daripada menimpa dokumen lama agar history tetap terjaga
            val finalPayload = if (isStationary) payload.copy(isStationary = true) else payload

            fs.collection("locations")
                .document(docId)
                .set(finalPayload.toFirestoreMap())
                .await()

            prefs.saveLastSentDocId(docId)
            Timber.d("Data lokasi terkirim langsung ke Firestore (docId=$docId)")
            true
        } catch (e: Exception) {
            Timber.e(e, "Gagal mengirim data langsung ke Firestore")
            false
        }
    }

    /**
     * Simpan data ke antrian lokal (offline queue).
     * Juga menyimpan eventId sebagai lastSentDocId agar update stasioner
     * berikutnya bisa meng-overwrite entri ini (bukan membuat baru).
     */
    suspend fun saveToRoom(payload: LocationDataModel): Long {
        return try {
            // Hapus entri PENDING dengan eventId yang sama jika ada
            // (terjadi saat update stasioner mandatory offline berulang kali)
            dao.deletePendingByEventId(payload.eventId)

            val entity = PendingLocationEntity.fromPayload(payload)
            val rowId = dao.insert(entity)
            dao.trimOldestIfOverLimit()

            Timber.d("Data lokasi disimpan di Room DB (rowId=\$rowId, eventId=\${payload.eventId})")
            rowId
        } catch (e: Exception) {
            Timber.e(e, "Gagal menyimpan ke Room DB")
            -1L
        }
    }

    /**
     * Sinkronisasi data pending dari Room ke Firestore menggunakan WriteBatch
     * Terus berusaha meski ada error di satu chunk — jangan hentikan sinkronisasi seluruhnya.
     */
    suspend fun syncPendingFromRoom() {
        if (!isFirebaseAvailable) return

        val pending = dao.getAllPending()
        if (pending.isEmpty()) return

        val authenticated = ensureAuthenticated()
        if (!authenticated) return

        val fs = firestore ?: return
        
        Timber.d("Memulai sinkronisasi ${pending.size} data pending via WriteBatch...")

        var errorCount = 0
        // Proses dalam chunk 400 (limit Firestore adalah 500 per batch)
        pending.chunked(400).forEach { chunk ->
            try {
                val batch = fs.batch()
                chunk.forEach { item ->
                    val docRef = fs.collection("locations").document(item.eventId)
                    batch.set(docRef, item.toPayload().toFirestoreMap())
                }
                
                batch.commit().await()
                
                chunk.forEach { dao.markAsSent(it.id) }
                Timber.d("Batch sync berhasil untuk ${chunk.size} item")
            } catch (e: Exception) {
                errorCount++
                Timber.e(e, "Gagal sync batch #$errorCount. Lanjut ke chunk berikutnya...")
                // Terus loop ke chunk berikutnya, jangan stop sinkronisasi seluruhnya
            }
        }

        try {
            dao.deleteSent()
        } catch (e: Exception) {
            Timber.e(e, "Gagal menghapus data tersinkronisasi dari Room")
        }

        if (errorCount > 0) {
            Timber.w("Sinkronisasi selesai dengan $errorCount batch error")
        }
    }

    /**
     * Extension function untuk konversi LocationDataModel ke Map Firestore.
     * Memasukkan GeoPoint dan serverTimestamp di layer repository.
     */
    private fun LocationDataModel.toFirestoreMap(): Map<String, Any> {
        return this.toMap() + mapOf(
            "geopoint" to GeoPoint(latitude, longitude),
            "timestamp" to FieldValue.serverTimestamp(),
            "source" to if (source == "offline_sync") "OFFLINE_SYNC" else source
        )
    }
}
