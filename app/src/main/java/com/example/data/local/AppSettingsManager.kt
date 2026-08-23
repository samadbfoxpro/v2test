package com.example.data.local

import android.content.Context
import com.example.data.model.RoutingMode
import com.example.data.model.SmartConnectMode

/**
 * Manages persistent storage for all user settings, connection modes, and active choices.
 * Ensures state is preserved across app closures, reboots, and process recreation.
 */
object AppSettingsManager {
    private const val PREFS_NAME = "app_settings_prefs"

    private const val KEY_SMART_CONNECT_MODE = "key_smart_connect_mode"
    private const val KEY_SELECTED_SUB_ID = "key_selected_subscription_id"
    private const val KEY_SELECTED_SERVER_ID = "key_selected_server_id"
    private const val KEY_ROUTING_MODE = "key_routing_mode"
    private const val KEY_DNS_PROVIDER = "key_dns_provider"
    private const val KEY_MUX_ENABLED = "key_mux_enabled"
    private const val KEY_FRAGMENT_ENABLED = "key_fragment_enabled"
    private const val KEY_TEST_URL = "key_test_url"
    private const val KEY_LAN_SHARING_ENABLED = "key_lan_sharing_enabled"
    private const val KEY_LAN_HTTP_ENABLED = "key_lan_http_enabled"
    private const val KEY_LAN_HTTP_PORT = "key_lan_http_port"
    private const val KEY_LAN_SOCKS_ENABLED = "key_lan_socks_enabled"
    private const val KEY_LAN_SOCKS_PORT = "key_lan_socks_port"
    private const val KEY_PERSIST_SERVER_SECTION = "key_persist_server_section"
    private const val KEY_SERVER_SECTION_ENABLED = "key_server_section_enabled"
    private const val KEY_HIDE_CONFIG_SHARING = "key_hide_config_sharing"

    const val DEFAULT_TEST_URL = "https://www.google.com/generate_204"

    fun isHideConfigSharingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HIDE_CONFIG_SHARING, false)
    }

    fun setHideConfigSharingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HIDE_CONFIG_SHARING, enabled).apply()
    }

    fun isPersistServerSectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PERSIST_SERVER_SECTION, false)
    }

    fun setPersistServerSectionEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PERSIST_SERVER_SECTION, enabled).apply()
    }

    fun isServerSectionPersisted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SERVER_SECTION_ENABLED, false)
    }

    fun setServerSectionPersisted(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SERVER_SECTION_ENABLED, enabled).apply()
    }

    fun getTestUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TEST_URL, DEFAULT_TEST_URL) ?: DEFAULT_TEST_URL
    }

    fun saveTestUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TEST_URL, url).apply()
    }

    fun isLanSharingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LAN_SHARING_ENABLED, false)
    }

    fun setLanSharingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_LAN_SHARING_ENABLED, enabled).apply()
    }

    fun isLanHttpEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LAN_HTTP_ENABLED, true)
    }

    fun setLanHttpEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_LAN_HTTP_ENABLED, enabled).apply()
    }

    fun getLanHttpPort(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LAN_HTTP_PORT, 8881)
    }

    fun setLanHttpPort(context: Context, port: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LAN_HTTP_PORT, port).apply()
    }

    fun isLanSocksEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LAN_SOCKS_ENABLED, true)
    }

    fun setLanSocksEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_LAN_SOCKS_ENABLED, enabled).apply()
    }

    fun getLanSocksPort(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LAN_SOCKS_PORT, 10808)
    }

    fun setLanSocksPort(context: Context, port: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LAN_SOCKS_PORT, port).apply()
    }

    fun getSmartConnectMode(context: Context): SmartConnectMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_SMART_CONNECT_MODE, SmartConnectMode.ALL_SUBS.name)
        return try {
            SmartConnectMode.valueOf(name ?: SmartConnectMode.ALL_SUBS.name)
        } catch (_: Exception) {
            SmartConnectMode.ALL_SUBS
        }
    }

    fun saveSmartConnectMode(context: Context, mode: SmartConnectMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SMART_CONNECT_MODE, mode.name).apply()
    }

    fun getSelectedSubscriptionId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_SELECTED_SUB_ID, 1L)
    }

    fun saveSelectedSubscriptionId(context: Context, subId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_SELECTED_SUB_ID, subId).apply()
    }

    fun getSelectedServerId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_SELECTED_SERVER_ID, -1L)
    }

    fun saveSelectedServerId(context: Context, serverId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_SELECTED_SERVER_ID, serverId).apply()
    }

    fun getRoutingMode(context: Context): RoutingMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_ROUTING_MODE, RoutingMode.BYPASS_LAN_AND_IRAN.name)
        return try {
            RoutingMode.valueOf(name ?: RoutingMode.BYPASS_LAN_AND_IRAN.name)
        } catch (_: Exception) {
            RoutingMode.BYPASS_LAN_AND_IRAN
        }
    }

    fun saveRoutingMode(context: Context, mode: RoutingMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ROUTING_MODE, mode.name).apply()
    }

    private const val KEY_DNS_MODE = "key_dns_mode"
    private const val KEY_FAKE_DNS_ENABLED = "key_fake_dns_enabled"

    fun getDnsMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DNS_MODE, "Automatic") ?: "Automatic"
    }

    fun saveDnsMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DNS_MODE, mode).apply()
    }

    fun isFakeDnsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FAKE_DNS_ENABLED, false)
    }

    fun saveFakeDnsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FAKE_DNS_ENABLED, enabled).apply()
    }

    fun getDnsProvider(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DNS_PROVIDER, "Cloudflare (1.1.1.1)") ?: "Cloudflare (1.1.1.1)"
    }

    fun saveDnsProvider(context: Context, dns: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DNS_PROVIDER, dns).apply()
    }

    fun isMuxEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MUX_ENABLED, true)
    }

    fun saveMuxEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MUX_ENABLED, enabled).apply()
    }

    fun isFragmentEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FRAGMENT_ENABLED, true)
    }

    fun saveFragmentEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FRAGMENT_ENABLED, enabled).apply()
    }
}
