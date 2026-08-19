package com.example.data.repository

import com.example.data.local.ServerDao
import com.example.data.model.ServerConfig
import com.example.data.parser.ConfigParser
import com.example.data.ping.PingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ServerRepository(private val serverDao: ServerDao) {

    val allServers: Flow<List<ServerConfig>> = serverDao.getAllServers()
    val selectedServer: Flow<ServerConfig?> = serverDao.getSelectedServer()

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

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        serverDao.clearAll()
    }

    suspend fun testServerLatency(server: ServerConfig): ServerConfig = withContext(Dispatchers.IO) {
        val latency = PingManager.measureTcpLatency(server.address, server.port)
        val now = System.currentTimeMillis()
        serverDao.updateLatency(server.id, latency, now)
        server.copy(latencyMs = latency, lastTested = now)
    }

    suspend fun importFromText(text: String): Int = withContext(Dispatchers.IO) {
        val parsedList = ConfigParser.parseInput(text)
        if (parsedList.isNotEmpty()) {
            val count = serverDao.getCount()
            // If no server was selected before, select the first imported one
            val listToInsert = if (count == 0) {
                parsedList.mapIndexed { index, config ->
                    if (index == 0) config.copy(isSelected = true) else config
                }
            } else {
                parsedList
            }
            serverDao.insertServers(listToInsert)
            parsedList.size
        } else {
            0
        }
    }

    suspend fun importFromSubscriptionUrl(url: String): Result<Int> = withContext(Dispatchers.IO) {
        val fetchResult = PingManager.fetchSubscription(url)
        if (fetchResult.isSuccess) {
            val content = fetchResult.getOrNull() ?: ""
            val count = importFromText(content)
            Result.success(count)
        } else {
            Result.failure(fetchResult.exceptionOrNull() ?: Exception("Unknown error"))
        }
    }
}
