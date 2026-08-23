package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val url: String = "", // Empty if manual/custom subscription group
    val autoUpdate: Boolean = true,
    val lastUpdated: Long = 0,
    val addedAt: Long = System.currentTimeMillis()
) {
    val isRemote: Boolean get() = url.isNotBlank()
}
