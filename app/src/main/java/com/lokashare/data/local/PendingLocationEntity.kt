package com.lokashare.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_locations")
data class PendingLocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val deviceId: String,
    val userName: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val battery: Int,
    val isCharging: Boolean,
    val localTimestamp: Long,         // Waktu lokal saat data dibuat
    val status: String = "PENDING"    // "PENDING" | "SENT"
)
