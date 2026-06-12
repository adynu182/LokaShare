package com.lokashare.data

data class LocationDataModel(
    val deviceId: String,
    val userName: String,
    val deviceModel: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val battery: Int,
    val isCharging: Boolean,
    val localTimestamp: Long,
    val ageMs: Long,
    val satellitesUsed: Int,
    val source: String,             // "GPS" | "FUSED" | "OFFLINE_SYNC"
    val isStationary: Boolean = false,
    val appVersion: String = "1.0.1",
    val eventId: String
) {
    fun toMap(): Map<String, Any> {
        return hashMapOf(
            "deviceId" to deviceId,
            "userName" to userName,
            "deviceModel" to deviceModel,
            "latitude" to latitude,
            "longitude" to longitude,
            "accuracy" to accuracy,
            "battery" to battery,
            "isCharging" to isCharging,
            "localTimestamp" to localTimestamp,
            "ageMs" to ageMs,
            "satellitesUsed" to satellitesUsed,
            "source" to source,
            "isStationary" to isStationary,
            "appVersion" to appVersion,
            "eventId" to eventId
        )
    }
}
