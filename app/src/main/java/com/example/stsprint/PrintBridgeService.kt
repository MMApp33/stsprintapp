package com.example.stsprint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the print server and USB printer connection alive when the app
 * is in the background or idle. Android is much less likely to kill a foreground service, so
 * printing continues to work after long idle or after switching to other apps.
 */
class PrintBridgeService : Service() {

    private val binder = LocalBinder()
    private var usbManager: UsbManager? = null
    private var printerManager: UsbPrinterManager? = null
    private var printServer: PrintServer? = null
    private var receiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == USB_PERMISSION_ACTION) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    printerManager?.connect()
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PrintBridgeService = this@PrintBridgeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        try {
            val usb = getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usb != null) {
                usbManager = usb
                printerManager = UsbPrinterManager(usb)
                ContextCompat.registerReceiver(
                    this,
                    usbPermissionReceiver,
                    IntentFilter(USB_PERMISSION_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
                tryConnectToPrinter()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service setup failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startPrintServer()
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(usbPermissionReceiver)
            } catch (_: Exception) { }
        }
        try {
            printServer?.stop()
        } catch (_: Exception) { }
        try {
            printerManager?.disconnect()
        } catch (_: Exception) { }
        super.onDestroy()
    }

    /**
     * Ensures the HTTP server is listening. Call from Activity onResume so that after long
     * idle or background, the server is restarted if it was stopped (e.g. socket closed).
     */
    fun ensureServerRunning() {
        val pm = printerManager ?: return
        try {
            printServer?.stop()
        } catch (_: Exception) { }
        printServer = null
        try {
            printServer = PrintServer(PRINT_SERVER_PORT, pm).apply { start() }
            Log.i(TAG, "Print server (re)started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start print server", e)
        }
    }

    fun tryConnectToPrinter() {
        val pm = printerManager ?: return
        val um = usbManager ?: return
        if (pm.connect()) return
        val device = pm.getFirstPrinterDevice() ?: return
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(USB_PERMISSION_ACTION).apply { setPackage(packageName) },
            PendingIntent.FLAG_IMMUTABLE
        )
        um.requestPermission(device, pi)
    }

    private fun startPrintServer() {
        val pm = printerManager ?: return
        try {
            printServer = PrintServer(PRINT_SERVER_PORT, pm).apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start print server", e)
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val smallIcon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.R.drawable.ic_menu_share
        } else {
            android.R.drawable.ic_dialog_info
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, PRINT_SERVER_PORT))
            .setSmallIcon(smallIcon)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "PrintBridgeService"
        private const val USB_PERMISSION_ACTION = "com.example.stsprint.USB_PERMISSION"
        private const val PRINT_SERVER_PORT = 12345
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "print_bridge_channel"
    }
}
