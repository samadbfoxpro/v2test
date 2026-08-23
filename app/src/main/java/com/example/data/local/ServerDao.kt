package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY addedAt DESC")
    fun getAllServers(): Flow<List<ServerConfig>>

    @Query("SELECT * FROM servers")
    suspend fun getAllServersList(): List<ServerConfig>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY addedAt DESC")
    fun getServersBySubscription(subId: Long): Flow<List<ServerConfig>>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId")
    suspend fun getServersBySubscriptionList(subId: Long): List<ServerConfig>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: Long): ServerConfig?

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    fun getSelectedServer(): Flow<ServerConfig?>

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedServerSync(): ServerConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerConfig): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<ServerConfig>): List<Long>

    @Update
    suspend fun updateServer(server: ServerConfig)

    @Query("UPDATE servers SET isSelected = CASE WHEN id = :selectedId THEN 1 ELSE 0 END")
    suspend fun selectServer(selectedId: Long)

    @Query("UPDATE servers SET latencyMs = :latencyMs, lastTested = :timestamp WHERE id = :id")
    suspend fun updateLatency(id: Long, latencyMs: Long, timestamp: Long)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteServer(id: Long)

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteServersBySubscription(subId: Long)

    @Query("DELETE FROM servers WHERE latencyMs = -2")
    suspend fun deleteTimeoutServers()

    @Query("DELETE FROM servers WHERE subscriptionId = :subId AND latencyMs = -2")
    suspend fun deleteTimeoutServersBySubscription(subId: Long)

    @Query("DELETE FROM servers")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun getCount(): Int
}
