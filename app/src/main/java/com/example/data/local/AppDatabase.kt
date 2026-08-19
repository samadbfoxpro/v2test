package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [ServerConfig::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "v2rayng_database.db"
                ).fallbackToDestructiveMigration()
                 .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            INSTANCE?.serverDao()?.insertServers(getDefaultServers())
                        }
                    }
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        super.onDestructiveMigration(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            INSTANCE?.serverDao()?.insertServers(getDefaultServers())
                        }
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }

        fun getDefaultServers(): List<ServerConfig> {
            val now = System.currentTimeMillis()
            return listOf(
                ServerConfig(
                    name = "💦 10 - VLESS - Domain : 2096",
                    protocol = "VLESS",
                    address = "www.speedtest.net",
                    port = 2096,
                    uuid = "9fb12a38-9b39-49d9-8d45-b643ca5a2c1a",
                    flow = "",
                    transportType = "ws",
                    path = "/eyJqdW5rIjoiQWhxcm56ZVMiLCJwcm90b2NvbCI6InZsIiwibW9kZSI6InByb3h5aXAiLCJwYW5lbElQcyI6W119?ed=2560",
                    host = "eshqemamone.samadbfoxi.workers.dev",
                    security = "tls",
                    sni = "eShqEmamonE.SaMadBfoxI.woRKerS.dEV",
                    publicKey = "",
                    shortId = "",
                    fingerprint = "chrome",
                    countryCode = "US",
                    latencyMs = 0,
                    lastTested = now,
                    isSelected = true,
                    rawUri = "vless://9fb12a38-9b39-49d9-8d45-b643ca5a2c1a@www.speedtest.net:2096?encryption=none&security=tls&sni=eShqEmamonE.SaMadBfoxI.woRKerS.dEV&fp=chrome&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=eshqemamone.samadbfoxi.workers.dev&path=%2FeyJqdW5rIjoiQWhxcm56ZVMiLCJwcm90b2NvbCI6InZsIiwibW9kZSI6InByb3h5aXAiLCJwYW5lbElQcyI6W119%3Fed%3D2560#%F0%9F%92%A6%2010%20-%20VLESS%20-%20Domain%20%3A%202096"
                )
            )
        }
    }
}
