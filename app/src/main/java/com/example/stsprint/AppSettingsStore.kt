package com.example.stsprint

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val usbTimeoutMsRef = AtomicInteger(5000)
    private val logRetentionDaysRef = AtomicInteger(7)
    private val autoReconnectRef = AtomicBoolean(true)
    private val showNotificationsRef = AtomicBoolean(true)

    init {
        loadSettings()
    }

    private fun loadSettings() {
        usbTimeoutMsRef.set(prefs.getInt("usb_timeout_ms", 5000))
        logRetentionDaysRef.set(prefs.getInt("log_retention_days", 7))
        autoReconnectRef.set(prefs.getBoolean("auto_reconnect", true))
        showNotificationsRef.set(prefs.getBoolean("show_notifications", true))
    }

    fun getUsbTimeoutMs(): Int = usbTimeoutMsRef.get()
    fun setUsbTimeoutMs(ms: Int) {
        usbTimeoutMsRef.set(ms)
        prefs.edit().putInt("usb_timeout_ms", ms).apply()
    }

    fun getLogRetentionDays(): Int = logRetentionDaysRef.get()
    fun setLogRetentionDays(days: Int) {
        logRetentionDaysRef.set(days)
        prefs.edit().putInt("log_retention_days", days).apply()
    }

    fun isAutoReconnectEnabled(): Boolean = autoReconnectRef.get()
    fun setAutoReconnect(enabled: Boolean) {
        autoReconnectRef.set(enabled)
        prefs.edit().putBoolean("auto_reconnect", enabled).apply()
    }

    fun isNotificationsEnabled(): Boolean = showNotificationsRef.get()
    fun setNotifications(enabled: Boolean) {
        showNotificationsRef.set(enabled)
        prefs.edit().putBoolean("show_notifications", enabled).apply()
    }
}
