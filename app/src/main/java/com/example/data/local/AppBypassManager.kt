package com.example.data.local

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)

object AppBypassManager {
    private const val PREFS_NAME = "vpn_bypass_prefs"
    private const val KEY_BYPASS_ENABLED = "key_bypass_enabled"
    private const val KEY_BYPASSED_PACKAGES = "key_bypassed_packages"

    fun isBypassEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BYPASS_ENABLED, true)
    }

    fun setBypassEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BYPASS_ENABLED, enabled).apply()
    }

    fun getBypassedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_BYPASSED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun saveBypassedPackages(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_BYPASSED_PACKAGES, packages).apply()
    }

    suspend fun getInstalledApps(context: Context): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val myPackage = context.packageName
        val apps = mutableListOf<AppItem>()

        try {
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installed) {
                if (app.packageName == myPackage) continue

                val appName = try {
                    pm.getApplicationLabel(app).toString()
                } catch (_: Exception) {
                    app.packageName
                }

                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                apps.add(
                    AppItem(
                        packageName = app.packageName,
                        appName = appName,
                        isSystemApp = isSystem
                    )
                )
            }
        } catch (_: Exception) {}

        apps.sortedBy { it.appName.lowercase() }
    }
}
