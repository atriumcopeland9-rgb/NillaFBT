package com.fbt.app

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("fbt_settings", Context.MODE_PRIVATE)

    var oscHost: String
        get() = prefs.getString("osc_host", "127.0.0.1") ?: "127.0.0.1"
        set(value) = prefs.edit().putString("osc_host", value).apply()

    var oscPort: Int
        get() = prefs.getInt("osc_port", 9000)
        set(value) = prefs.edit().putInt("osc_port", value).apply()
}
