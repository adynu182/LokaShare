package com.lokashare.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lokashare.data.LocationDataModel

@Entity(tableName = "pending_locations")
data class PendingLocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

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
    val source: String,
    val isStationary: Boolean,
    val appVersion: String,
    val eventId: String,
    val status: String = "PENDING"  // "PENDING" | "SENT"
) {
    fun toPayload(): LocationDataModel {
        return LocationDataModel(
            deviceId = deviceId,
            userName = userName,
            deviceModel = deviceModel,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            battery = battery,
            isCharging = isCharging,
            localTimestamp = localTimestamp,
            ageMs = ageMs,
            satellitesUsed = satellitesUsed,
            source = source,
            isStationary = isStationary,
            appVersion = appVersion,
            eventId = eventId
        )
    }

    companion object {
        fun fromPayload(payload: LocationDataModel): PendingLocationEntity {
            return PendingLocationEntity(
                deviceId = payload.deviceId,
                userName = payload.userName,
                deviceModel = payload.deviceModel,
                latitude = payload.latitude,
                longitude = payload.longitude,
                accuracy = payload.accuracy,
                battery = payload.battery,
                isCharging = payload.isCharging,
                localTimestamp = payload.localTimestamp,
                ageMs = payload.ageMs,
                satellitesUsed = payload.satellitesUsed,
                source = payload.source,
                isStationary = payload.isStationary,
                appVersion = payload.appVersion,
                eventId = payload.eventId
            )
        }
    }
}
