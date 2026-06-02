package com.lokashare.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PendingLocationDao {

    @Insert
    suspend fun insert(entity: PendingLocationEntity)

    @Query("SELECT * FROM pending_locations WHERE status = 'PENDING' ORDER BY localTimestamp ASC")
    suspend fun getAllPending(): List<PendingLocationEntity>

    @Query("UPDATE pending_locations SET status = 'SENT' WHERE id = :id")
    suspend fun markAsSent(id: Long)

    @Query("DELETE FROM pending_locations WHERE status = 'SENT'")
    suspend fun deleteSent()

    // Batas antrian: pertahankan hanya 500 entri pending terbaru, hapus sisanya
    @Query("DELETE FROM pending_locations WHERE status = 'PENDING' AND id NOT IN (SELECT id FROM pending_locations WHERE status = 'PENDING' ORDER BY localTimestamp DESC LIMIT 500)")
    suspend fun trimOldestIfOverLimit()

    @Query("SELECT COUNT(*) FROM pending_locations WHERE status = 'PENDING'")
    fun getPendingCountFlow(): kotlinx.coroutines.flow.Flow<Int>
}
