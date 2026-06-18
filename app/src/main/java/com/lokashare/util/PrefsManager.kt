package com.lokashare.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.gms.location.DetectedActivity
import java.util.UUID

class PrefsManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("lokashare_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"

        // Key lama (Float, buggy) — hanya untuk migrasi baca
        private const val KEY_LAST_LATITUDE_LEGACY = "last_latitude"
        private const val KEY_LAST_LONGITUDE_LEGACY = "last_longitude"

        // Key baru (Long bits, presisi penuh Double)
        private const val KEY_LAST_LATITUDE_BITS = "last_latitude_bits"
        private const val KEY_LAST_LONGITUDE_BITS = "last_longitude_bits"

        private const val KEY_LAST_TIMESTAMP = "last_timestamp"
        private const val KEY_LAST_DOC_ID = "last_doc_id"
        private const val KEY_LAST_STATIONARY_DOC_ID = "last_stationary_doc_id"
        private const val KEY_LAST_STATIONARY_SENT_TIME = "last_stationary_sent_time"
        private const val KEY_LAST_MODE = "last_mode"
        private const val KEY_LAST_MANDATORY_SENT = "last_mandatory_sent"
        private const val KEY_LAST_ACTIVITY_TYPE = "last_activity_type"
        private const val KEY_LAST_ACTIVITY_CONFIDENCE = "last_activity_confidence"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_DEVICE_ID = "device_id"
    }

    fun isTrackingEnabled(): Boolean {
        return prefs.getBoolean(KEY_TRACKING_ENABLED, false)
    }

    fun setTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRACKING_ENABLED, enabled).apply()
    }

    fun saveLastSentLocation(latitude: Double, longitude: Double, timestamp: Long) {
        prefs.edit()
            // Simpan sebagai Long bits — presisi penuh Double tanpa kehilangan digit
            .putLong(KEY_LAST_LATITUDE_BITS, java.lang.Double.doubleToRawLongBits(latitude))
            .putLong(KEY_LAST_LONGITUDE_BITS, java.lang.Double.doubleToRawLongBits(longitude))
            .putLong(KEY_LAST_TIMESTAMP, timestamp)
            // Hapus key lama agar tidak membingungkan saat dibaca
            .remove(KEY_LAST_LATITUDE_LEGACY)
            .remove(KEY_LAST_LONGITUDE_LEGACY)
            .apply()
    }

    fun getLastSentLocation(): Triple<Double, Double, Long>? {
        return when {
            // Prioritas 1: baca dari key baru (Long bits, presisi penuh)
            prefs.contains(KEY_LAST_LATITUDE_BITS) -> {
                val latBits = prefs.getLong(KEY_LAST_LATITUDE_BITS, 0L)
                val lngBits = prefs.getLong(KEY_LAST_LONGITUDE_BITS, 0L)
                val time    = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
                Triple(
                    java.lang.Double.longBitsToDouble(latBits),
                    java.lang.Double.longBitsToDouble(lngBits),
                    time
                )
            }
            // Prioritas 2: migrasi dari key lama (Float) — untuk user yang sudah install
            // Data ini kurang presisi, tapi lebih baik daripada tidak ada sama sekali
            prefs.contains(KEY_LAST_LATITUDE_LEGACY) -> {
                val lat  = prefs.getFloat(KEY_LAST_LATITUDE_LEGACY, 0f).toDouble()
                val lng  = prefs.getFloat(KEY_LAST_LONGITUDE_LEGACY, 0f).toDouble()
                val time = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
                Triple(lat, lng, time)
            }
            // Belum pernah simpan lokasi
            else -> null
        }
    }

    fun getUserName(): String? {
        return prefs.getString(KEY_USER_NAME, null)
    }

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
    }

    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            id = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                UUID.randomUUID().toString()
            }
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }
    
    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }
    }

    fun saveLastSentDocId(docId: String) {
        prefs.edit().putString(KEY_LAST_DOC_ID, docId).apply()
    }

    fun getLastSentDocId(): String? {
        return prefs.getString(KEY_LAST_DOC_ID, null)
    }

    /**
     * Simpan docId khusus untuk update stationary.
     * Dipisahkan dari lastSentDocId regular agar tidak ter-overwrite
     * saat ada pergerakan yang diikuti update stationary.
     */
    fun saveLastStationaryDocId(docId: String) {
        prefs.edit().putString(KEY_LAST_STATIONARY_DOC_ID, docId).apply()
    }

    fun getLastStationaryDocId(): String? {
        return prefs.getString(KEY_LAST_STATIONARY_DOC_ID, null)
    }

    fun clearLastStationaryDocId() {
        prefs.edit().remove(KEY_LAST_STATIONARY_DOC_ID).apply()
    }

    fun saveLastStationarySentTime(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_STATIONARY_SENT_TIME, timestamp).apply()
    }

    fun getLastStationarySentTime(): Long {
        return prefs.getLong(KEY_LAST_STATIONARY_SENT_TIME, 0L)
    }

    fun saveLastMode(mode: String) {
        prefs.edit().putString(KEY_LAST_MODE, mode).apply()
    }

    fun getLastMode(): String {
        return prefs.getString(KEY_LAST_MODE, "MOVING") ?: "MOVING"
    }

    fun saveLastMandatorySent(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_MANDATORY_SENT, timestamp).apply()
    }

    fun getLastMandatorySent(): Long {
        return prefs.getLong(KEY_LAST_MANDATORY_SENT, 0L)
    }

    fun saveLastDetectedActivity(type: Int, confidence: Int) {
        prefs.edit()
            .putInt(KEY_LAST_ACTIVITY_TYPE, type)
            .putInt(KEY_LAST_ACTIVITY_CONFIDENCE, confidence)
            .apply()
    }

    fun getLastDetectedActivity(): Pair<Int, Int>? {
        return if (prefs.contains(KEY_LAST_ACTIVITY_TYPE) && prefs.contains(KEY_LAST_ACTIVITY_CONFIDENCE)) {
            prefs.getInt(KEY_LAST_ACTIVITY_TYPE, DetectedActivity.UNKNOWN) to
                prefs.getInt(KEY_LAST_ACTIVITY_CONFIDENCE, 0)
        } else null
    }
}
