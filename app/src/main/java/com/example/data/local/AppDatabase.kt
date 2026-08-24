package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.ServerConfig
import com.example.data.model.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [ServerConfig::class, Subscription::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao

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
                            initDefaultData()
                        }
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }

        private suspend fun initDefaultData() {
            val db = INSTANCE ?: return
            if (db.subscriptionDao().getCount() == 0) {
                val defaultSub = Subscription(
                    id = 1L,
                    title = "پیش‌فرض",
                    url = "",
                    lastUpdated = System.currentTimeMillis()
                )
                db.subscriptionDao().insertSubscription(defaultSub)
            }
        }
    }
}
