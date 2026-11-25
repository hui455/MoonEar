package com.hui455.moonear

import android.content.Context

/** First successful pre-write snapshot, isolated by the paired headset address. */
class TouchBackupStore(context: Context, private val address: String) {
    private val preferences = context.applicationContext.getSharedPreferences("touch_backups", 0)
    fun load(): TouchConfiguration? {
        val encoded = preferences.getString(address, null) ?: return null
        return runCatching {
            TouchConfiguration.parse(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
        }.getOrNull()
    }
    fun saveFirst(config: TouchConfiguration) {
        if (load() != null) return
        val encoded = android.util.Base64.encodeToString(config.payload(), android.util.Base64.NO_WRAP)
        check(preferences.edit().putString(address, encoded).commit()) { "Backup could not be saved" }
    }
}
