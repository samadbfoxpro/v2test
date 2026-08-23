package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Subscription
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id ASC")
    fun getAllSubscriptions(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscriptions ORDER BY id ASC")
    suspend fun getAllSubscriptionsList(): List<Subscription>

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getSubscriptionById(id: Long): Subscription?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: Subscription): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptions(subscriptions: List<Subscription>): List<Long>

    @Update
    suspend fun updateSubscription(subscription: Subscription)

    @Query("UPDATE subscriptions SET lastUpdated = :timestamp WHERE id = :id")
    suspend fun updateLastUpdated(id: Long, timestamp: Long)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscription(id: Long)

    @Query("DELETE FROM subscriptions")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun getCount(): Int
}
