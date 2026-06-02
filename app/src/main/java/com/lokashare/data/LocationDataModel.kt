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
    val source: String,             // "online" | "offline_sync"
    val appVersion: String = "1.0.0"
) {
    fun toFirestoreMap(): Map<String, Any> {
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
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(), // Firestore server-side timestamp
            "source" to source,
            "appVersion" to appVersion
        )
    }
}
