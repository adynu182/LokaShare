package com.lokashare.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.util.UUID

class PrefsManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("lokashare_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_LAST_LATITUDE = "last_latitude"
        private const val KEY_LAST_LONGITUDE = "last_longitude"
        private const val KEY_LAST_TIMESTAMP = "last_timestamp"
        private const val KEY_LAST_DOC_ID = "last_doc_id"
        private const val KEY_LAST_MANDATORY_SENT = "last_mandatory_sent"
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
            .putFloat(KEY_LAST_LATITUDE, latitude.toFloat())
            .putFloat(KEY_LAST_LONGITUDE, longitude.toFloat())
            .putLong(KEY_LAST_TIMESTAMP, timestamp)
            .apply()
    }

    fun getLastSentLocation(): Triple<Double, Double, Long>? {
        if (!prefs.contains(KEY_LAST_LATITUDE)) return null
        val lat = prefs.getFloat(KEY_LAST_LATITUDE, 0f).toDouble()
        val lng = prefs.getFloat(KEY_LAST_LONGITUDE, 0f).toDouble()
        val time = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
        return Triple(lat, lng, time)
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

    fun saveLastMandatorySent(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_MANDATORY_SENT, timestamp).apply()
    }

    fun getLastMandatorySent(): Long {
        return prefs.getLong(KEY_LAST_MANDATORY_SENT, 0L)
    }
}
