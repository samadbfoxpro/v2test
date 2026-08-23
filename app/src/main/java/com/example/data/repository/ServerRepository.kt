package com.example.data.repository

import com.example.data.local.ServerDao
import com.example.data.local.SubscriptionDao
import com.example.data.model.ServerConfig
import com.example.data.model.Subscription
import com.example.data.parser.ConfigParser
import com.example.data.ping.PingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ServerRepository(
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao
) {
    val allServers: Flow<List<ServerConfig>> = serverDao.getAllServers()
    val allSubscriptions: Flow<List<Subscription>> = subscriptionDao.getAllSubscriptions()
    val selectedServer: Flow<ServerConfig?> = serverDao.getSelectedServer()

    fun getServersBySubscription(subscriptionId: Long): Flow<List<ServerConfig>> {
        return serverDao.getServersBySubscription(subscriptionId)
    }

    suspend fun getServersBySubscriptionList(subscriptionId: Long): List<ServerConfig> = withContext(Dispatchers.IO) {
        serverDao.getServersBySubscriptionList(subscriptionId)
    }

    suspend fun getAllServersList(): List<ServerConfig> = withContext(Dispatchers.IO) {
        serverDao.getAllServersList()
    }

    suspend fun getAllSubscriptionsList(): List<Subscription> = withContext(Dispatchers.IO) {
        subscriptionDao.getAllSubscriptionsList()
    }

    suspend fun selectServer(id: Long) = withContext(Dispatchers.IO) {
        serverDao.selectServer(id)
    }

    suspend fun insertServer(server: ServerConfig): Long = withContext(Dispatchers.IO) {
        serverDao.insertServer(server)
    }

    suspend fun insertServers(servers: List<ServerConfig>): List<Long> = withContext(Dispatchers.IO) {
        serverDao.insertServers(servers)
    }

    suspend fun updateServer(server: ServerConfig) = withContext(Dispatchers.IO) {
        serverDao.updateServer(server)
    }

    suspend fun deleteServer(id: Long) = withContext(Dispatchers.IO) {
        serverDao.deleteServer(id)
    }

    suspend fun deleteTimeoutServers() = withContext(Dispatchers.IO) {
        serverDao.deleteTimeoutServers()
    }

    suspend fun deleteTimeoutServersInSubscription(subscriptionId: Long) = withContext(Dispatchers.IO) {
        serverDao.deleteTimeoutServersBySubscription(subscriptionId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        serverDao.clearAll()
    }

    // Subscription operations
    suspend fun insertSubscription(subscription: Subscription): Long = withContext(Dispatchers.IO) {
        val id = subscriptionDao.insertSubscription(subscription)
        if (subscription.url.isNotBlank()) {
            val subWithId = subscription.copy(id = id)
            updateSubscriptionFromUrl(subWithId)
        }
        id
    }

    suspend fun updateSubscription(subscription: Subscription) = withContext(Dispatchers.IO) {
        subscriptionDao.updateSubscription(subscription)
    }

    suspend fun deleteSubscription(subscriptionId: Long) = withContext(Dispatchers.IO) {
        subscriptionDao.deleteSubscription(subscriptionId)
        serverDao.deleteServersBySubscription(subscriptionId)
    }

    suspend fun updateSubscriptionFromUrl(subscription: Subscription): Result<Int> = withContext(Dispatchers.IO) {
        if (subscription.url.isBlank()) {
            return@withContext Result.failure(Exception("آدرس سابسکریپشن خالی است"))
        }

        val fetchResult = PingManager.fetchSubscription(subscription.url)
        if (fetchResult.isSuccess) {
            val content = fetchResult.getOrNull() ?: ""
            val parsedList = ConfigParser.parseInput(content)
            if (parsedList.isNotEmpty()) {
                // Delete existing servers for this subscription
                serverDao.deleteServersBySubscription(subscription.id)

                val mappedList = parsedList.map { config ->
                    config.copy(
                        subscriptionId = subscription.id,
                        group = subscription.title
                    )
                }

                // If currently no selected server in DB, select first one
                val selectedSync = serverDao.getSelectedServerSync()
                val finalList = if (selectedSync == null) {
                    mappedList.mapIndexed { index, config ->
                        if (index == 0) config.copy(isSelected = true) else config
                    }
                } else {
                    mappedList
                }

                serverDao.insertServers(finalList)
                val now = System.currentTimeMillis()
                subscriptionDao.updateLastUpdated(subscription.id, now)
                Result.success(parsedList.size)
            } else {
                Result.failure(Exception("هیچ کانفیگ معتبری در محتوای سابسکریپشن یافت نشد"))
            }
        } else {
            Result.failure(fetchResult.exceptionOrNull() ?: Exception("خطا در دریافت سابسکریپشن"))
        }
    }

    suspend fun importToSubscription(text: String, subscriptionId: Long, groupTitle: String): Int = withContext(Dispatchers.IO) {
        val parsedList = ConfigParser.parseInput(text)
        if (parsedList.isNotEmpty()) {
            val selectedSync = serverDao.getSelectedServerSync()
            val mappedList = parsedList.mapIndexed { index, config ->
                val shouldSelect = (selectedSync == null && index == 0)
                config.copy(
                    subscriptionId = subscriptionId,
                    group = groupTitle,
                    isSelected = shouldSelect
                )
            }
            serverDao.insertServers(mappedList)
            parsedList.size
        } else {
            0
        }
    }

    suspend fun testServerLatency(server: ServerConfig): ServerConfig = withContext(Dispatchers.IO) {
        val latency = PingManager.measureTcpLatency(server.address, server.port)
        val now = System.currentTimeMillis()
        serverDao.updateLatency(server.id, latency, now)
        server.copy(latencyMs = latency, lastTested = now)
    }
}
