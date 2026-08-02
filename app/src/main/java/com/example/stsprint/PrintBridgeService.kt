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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the print server and USB printer connection alive when the app
 * is in the background or idle. Also hosts Phase 1 Caller ID USB diagnostics (separate module).
 *
 * Printing and Caller ID are isolated: CID failures must not affect the printer path.
 */
class PrintBridgeService : Service() {

    private val binder = LocalBinder()
    private var usbManager: UsbManager? = null
    private var printerManager: UsbPrinterManager? = null
    private var printServer: PrintServer? = null
    private var printerPermissionReceiverRegistered = false
    private var cidPermissionReceiverRegistered = false
    private var usbDetachReceiverRegistered = false
    private var printerReconnectScheduled = false
    private var lastPrinterDisconnectTime = 0L

    private val callerIdStore = CallerIdDiagnosticStore()
    private var callerIdListener: CallerIdUsbDiagnosticService? = null

    private val prefs by lazy {
        getSharedPreferences("stsprint_prefs", Context.MODE_PRIVATE)
    }

    @Volatile
    private var currentServerPort = PRINT_SERVER_PORT

    private val printerPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == USB_PERMISSION_ACTION) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    printerManager?.connect()
                }
            }
        }
    }

    private val cidPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CID_USB_PERMISSION_ACTION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                Log.i(CID_TAG, "Permission granted for deviceId=${device.deviceId}")
                callerIdStore.setPermissionGranted(true, "Caller ID USB permission granted")
                scanCallerIdDevices()
                callerIdStore.selectDevice(device.deviceId)
            } else {
                Log.w(CID_TAG, "Permission denied for Caller ID device")
                callerIdStore.setPermissionGranted(false, "USB permission denied for Caller ID device")
            }
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        Log.i(CID_TAG, "USB device detached: deviceId=${device.deviceId}")
                        callerIdListener?.onDeviceDetached(device.deviceId)
                        // Refresh scan list
                        scanCallerIdDevices()
                        // If printer was detached, disconnect and auto-reconnect
                        if (printerManager?.isConnected() == true) {
                            printerManager?.disconnect()
                            Log.i(TAG, "Printer disconnected (USB detach), scheduling auto-reconnect")
                            schedulePrinterReconnect()
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(CID_TAG, "USB device attached")
                    scanCallerIdDevices()
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
                callerIdListener = CallerIdUsbDiagnosticService(usb, callerIdStore)

                ContextCompat.registerReceiver(
                    this,
                    printerPermissionReceiver,
                    IntentFilter(USB_PERMISSION_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                printerPermissionReceiverRegistered = true

                ContextCompat.registerReceiver(
                    this,
                    cidPermissionReceiver,
                    IntentFilter(CID_USB_PERMISSION_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cidPermissionReceiverRegistered = true

                val detachFilter = IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                }
                ContextCompat.registerReceiver(
                    this,
                    usbDetachReceiver,
                    detachFilter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                usbDetachReceiverRegistered = true

                tryConnectToPrinter()

                // Restore selected Caller ID device from preferences
                val savedDeviceId = prefs.getInt("selected_caller_id_device", -1)
                if (savedDeviceId != -1) {
                    callerIdStore.selectDevice(savedDeviceId)
                    Log.i(CID_TAG, "Restored selected device: $savedDeviceId")
                }

                // Auto-scan USB devices on startup
                scanCallerIdDevices()
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
        try {
            callerIdListener?.stopListening(updateStore = false)
        } catch (_: Exception) {
        }
        if (printerPermissionReceiverRegistered) {
            try {
                unregisterReceiver(printerPermissionReceiver)
            } catch (_: Exception) {
            }
        }
        if (cidPermissionReceiverRegistered) {
            try {
                unregisterReceiver(cidPermissionReceiver)
            } catch (_: Exception) {
            }
        }
        if (usbDetachReceiverRegistered) {
            try {
                unregisterReceiver(usbDetachReceiver)
            } catch (_: Exception) {
            }
        }
        try {
            printServer?.stop()
        } catch (_: Exception) {
        }
        try {
            printerManager?.disconnect()
        } catch (_: Exception) {
        }
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
        } catch (_: Exception) {
        }
        printServer = null
        try {
            val port = PrintServer.findAvailablePort(PRINT_SERVER_PORT)
            currentServerPort = port
            printServer = PrintServer(port, pm, callerIdStore).apply { start() }
            Log.i(TAG, "Print server (re)started on port $port")
            if (port != PRINT_SERVER_PORT) {
                Log.w(TAG, "Port $PRINT_SERVER_PORT taken, using fallback port $port")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start print server", e)
        }
    }

    fun tryConnectToPrinter() {
        val pm = printerManager ?: return
        val um = usbManager ?: return
        // Keep printer from claiming the selected Caller ID device
        pm.excludedDeviceIds = setOfNotNull(callerIdStore.selectedDeviceId())
        if (pm.connect()) {
            lastPrinterDisconnectTime = 0
            printerReconnectScheduled = false
            return
        }
        val device = pm.getFirstPrinterDevice() ?: return
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(USB_PERMISSION_ACTION).apply { setPackage(packageName) },
            PendingIntent.FLAG_IMMUTABLE
        )
        um.requestPermission(device, pi)
    }

    fun schedulePrinterReconnect() {
        if (printerReconnectScheduled) return
        printerReconnectScheduled = true
        lastPrinterDisconnectTime = System.currentTimeMillis()

        Thread {
            try {
                for (attempt in 1..5) {
                    Thread.sleep((attempt * 2000).toLong().coerceAtMost(30000))
                    Log.i(TAG, "Printer reconnect attempt $attempt")
                    tryConnectToPrinter()
                    if (isPrinterConnected()) {
                        Log.i(TAG, "Printer reconnected after $attempt attempts")
                        printerReconnectScheduled = false
                        return@Thread
                    }
                }
                Log.w(TAG, "Printer reconnect failed after 5 attempts")
                printerReconnectScheduled = false
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect error", e)
                printerReconnectScheduled = false
            }
        }.apply { isDaemon = true }.start()
    }

    fun isPrinterConnected(): Boolean = printerManager?.isConnected() == true

    fun hasPrinterCandidate(): Boolean = printerManager?.getFirstPrinterDevice() != null

    fun getServerPort(): Int = currentServerPort

    fun testPrint(): Boolean {
        val testData = byteArrayOf(
            0x1B, 0x40,
            0x1B, 0x45, 0x01,
            'T'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
            0x0A, 0x0A,
            0x1D, 0x56, 0x00
        )
        return printerManager?.print(testData) ?: false
    }

    // region Caller ID diagnostics (separate from printer)

    fun getCallerIdSnapshot(): CallerIdSnapshot = callerIdStore.snapshot()

    fun scanCallerIdDevices(): List<UsbDeviceInfo> {
        val um = usbManager ?: return emptyList()
        return try {
            val devices = UsbDeviceInspector.scanAllDevices(um)
            callerIdStore.setDevices(devices)
            val selectedId = callerIdStore.selectedDeviceId()
            if (selectedId != null) {
                callerIdStore.selectDevice(selectedId)
            }
            devices
        } catch (e: Exception) {
            Log.e(CID_TAG, "USB scan failed", e)
            callerIdStore.setError("USB scan failed: ${e.message}")
            emptyList()
        }
    }

    fun selectCallerIdDevice(deviceId: Int) {
        callerIdStore.selectDevice(deviceId)
        prefs.edit().putInt("selected_caller_id_device", deviceId).apply()
        printerManager?.excludedDeviceIds = setOf(deviceId)
        // Re-ensure printer is on a different device if needed
        tryConnectToPrinter()
    }

    fun requestCallerIdPermission(deviceId: Int): Boolean {
        val um = usbManager ?: return false
        val device = UsbDeviceInspector.findDeviceById(um, deviceId) ?: run {
            callerIdStore.setError("Device not found")
            return false
        }
        callerIdStore.selectDevice(deviceId)
        printerManager?.excludedDeviceIds = setOf(deviceId)

        if (um.hasPermission(device)) {
            Log.i(CID_TAG, "Permission already granted for deviceId=$deviceId")
            callerIdStore.setPermissionGranted(true, "Caller ID USB permission granted")
            scanCallerIdDevices()
            callerIdStore.selectDevice(deviceId)
            return true
        }

        Log.i(CID_TAG, "Permission requested for deviceId=$deviceId")
        callerIdStore.setPermissionGranted(false, "USB permission required for Caller ID device")
        val pi = PendingIntent.getBroadcast(
            this, 1,
            Intent(CID_USB_PERMISSION_ACTION).apply { setPackage(packageName) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        um.requestPermission(device, pi)
        return false
    }

    fun startCallerIdListening(deviceId: Int): Boolean {
        val um = usbManager ?: return false
        val listener = callerIdListener ?: return false
        val device = UsbDeviceInspector.findDeviceById(um, deviceId) ?: run {
            callerIdStore.setError("Device not found")
            return false
        }
        if (!um.hasPermission(device)) {
            requestCallerIdPermission(deviceId)
            return false
        }
        callerIdStore.selectDevice(deviceId)
        printerManager?.excludedDeviceIds = setOf(deviceId)
        return try {
            listener.startListening(device)
        } catch (e: Exception) {
            Log.e(CID_TAG, "startListening failed", e)
            callerIdStore.setError("Failed to start listening: ${e.message}")
            false
        }
    }

    fun stopCallerIdListening() {
        try {
            callerIdListener?.stopListening(updateStore = true)
        } catch (e: Exception) {
            Log.e(CID_TAG, "stopListening failed", e)
        }
    }

    fun clearCallerIdLogs() {
        callerIdStore.clearLogs()
    }

    // endregion

    private fun startPrintServer() {
        val pm = printerManager ?: return
        try {
            val port = PrintServer.findAvailablePort(PRINT_SERVER_PORT)
            currentServerPort = port
            printServer = PrintServer(port, pm, callerIdStore).apply { start() }
            Log.i(TAG, "Print server started on port $port")
            if (port != PRINT_SERVER_PORT) {
                Log.w(TAG, "Port $PRINT_SERVER_PORT taken, using fallback port $port")
            }
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
        private const val CID_TAG = "STS-CALLER-ID"
        private const val USB_PERMISSION_ACTION = "com.example.stsprint.USB_PERMISSION"
        private const val CID_USB_PERMISSION_ACTION = "com.example.stsprint.CID_USB_PERMISSION"
        private const val PRINT_SERVER_PORT = 12345
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "print_bridge_channel"
    }
}
