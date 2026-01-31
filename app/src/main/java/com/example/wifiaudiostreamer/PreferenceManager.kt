package com.example.wifiaudiostreamer

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wifi-audio-streamer-prefs", Context.MODE_PRIVATE)

    fun saveIpAddress(ip: String) {
        if (ip.isBlank()) return
        val currentIps = getRecentIpAddresses().toMutableSet()
        currentIps.add(ip)
        prefs.edit().putStringSet("recent_ips", currentIps.take(5).toSet()).apply()
    }

    fun getRecentIpAddresses(): List<String> {
        return prefs.getStringSet("recent_ips", emptySet())?.toList()?.sorted() ?: emptyList()
    }
}