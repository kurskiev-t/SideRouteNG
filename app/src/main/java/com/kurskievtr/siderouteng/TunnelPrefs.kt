package com.kurskievtr.siderouteng

import android.content.Context
import android.content.SharedPreferences

class TunnelPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Share links (`vless://`, `vmess://`, `trojan://`, `ss://`), one per line. */
    var outboundLinks: String
        get() = prefs.getString(LINKS, "") ?: ""
        set(value) = prefs.edit().putString(LINKS, value).apply()

    /** SOCKS5 endpoints, one per line; used when no share link is configured. */
    var proxyList: String
        get() = prefs.getString(PROXY_LIST, "") ?: ""
        set(value) = prefs.edit().putString(PROXY_LIST, value).apply()

    var dnsServers: String
        get() = prefs.getString(DNS, "1.1.1.1, 8.8.8.8") ?: "1.1.1.1, 8.8.8.8"
        set(value) = prefs.edit().putString(DNS, value).apply()

    var global: Boolean
        get() = prefs.getBoolean(GLOBAL, true)
        set(value) = prefs.edit().putBoolean(GLOBAL, value).apply()

    var apps: Set<String>
        get() = prefs.getStringSet(APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(APPS, HashSet(value)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(ENABLE, false)
        set(value) = prefs.edit().putBoolean(ENABLE, value).apply()

    var maxLatencyMs: Int
        get() = prefs.getInt(MAX_LATENCY, DEFAULT_MAX_LATENCY_MS)
        set(value) = prefs.edit().putInt(MAX_LATENCY, value).apply()

    var autoSelectProxies: Boolean
        get() = prefs.getBoolean(AUTO_SELECT, true)
        set(value) = prefs.edit().putBoolean(AUTO_SELECT, value).apply()

    val logLevel: String = "warning"
    val mtu: Int = 1500
    val tunnelIpv4Address: String = "26.26.26.1"
    val tunnelIpv4Prefix: Int = 30

    fun hasUpstream(): Boolean =
        outboundLinks.isNotBlank() || ProxyEndpoint.parseList(proxyList).isNotEmpty()

    fun registerOnChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFS_NAME = "SideRoutePrefs"
        const val LINKS = "OutboundLinks"
        const val PROXY_LIST = "ProxyList"
        const val DNS = "DnsServers"
        const val GLOBAL = "Global"
        const val APPS = "Apps"
        const val ENABLE = "Enable"
        const val MAX_LATENCY = "MaxLatencyMs"
        const val AUTO_SELECT = "AutoSelectProxies"
        const val DEFAULT_MAX_LATENCY_MS = 2_000
    }
}
