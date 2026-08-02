package com.example.stsprint

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages USB connection to an ESC/POS thermal receipt printer.
 * Detects the first available USB printer, claims the interface with a BULK OUT endpoint,
 * and sends raw bytes via bulkTransfer().
 */
class UsbPrinterManager(private val usbManager: UsbManager) {

    private val connectionRef = AtomicReference<UsbDeviceConnection?>(null)
    private val endpointRef = AtomicReference<UsbEndpoint?>(null)
    private val interfaceRef = AtomicReference<UsbInterface?>(null)

    /** Device IDs to skip (e.g. selected Caller ID hardware) so printing never claims them. */
    @Volatile
    var excludedDeviceIds: Set<Int> = emptySet()

    companion object {
        private const val TAG = "UsbPrinterManager"
        private const val USB_CLASS_PRINTER = 7
        private const val TIMEOUT_MS = 5000
    }

    /**
     * Tries to connect to the first available USB printer.
     * Call this when the app has permission to access the device (e.g. after user grants permission).
     * @return true if connected to a printer, false otherwise
     */
    fun connect(): Boolean {
        disconnect()

        val device = findFirstPrinterDevice() ?: run {
            Log.w(TAG, "No USB printer device found")
            return false
        }

        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "No permission for USB printer device")
            return false
        }

        val connection = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open USB device")
            return false
        }

        val (usbInterface, outEndpoint) = findPrinterInterfaceAndEndpoint(device)
            ?: run {
                connection.close()
                Log.e(TAG, "No printer interface with BULK OUT endpoint found")
                return false
            }

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            Log.e(TAG, "Failed to claim printer interface")
            return false
        }

        connectionRef.set(connection)
        endpointRef.set(outEndpoint)
        interfaceRef.set(usbInterface)
        Log.i(TAG, "Connected to USB printer: ${device.productName}")
        return true
    }

    /**
     * Disconnects from the current printer and releases the interface.
     */
    fun disconnect() {
        val connection = connectionRef.getAndSet(null)
        val usbInterface = interfaceRef.getAndSet(null)
        endpointRef.set(null)

        usbInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        Log.i(TAG, "Disconnected from USB printer")
    }

    /**
     * Sends raw ESC/POS data to the printer. Bytes are sent unchanged (no encoding or conversion).
     * Use this for full ESC/POS streams including binary (e.g. GS v 0 raster logo, GS ( k QR code).
     *
     * @param data raw bytes (ESC/POS init, raster, QR commands, text, etc.)
     * @return true if all data was sent successfully, false if not connected or transfer failed
     */
    fun print(data: ByteArray): Boolean {
        val connection = connectionRef.get() ?: return false
        val endpoint = endpointRef.get() ?: return false
        if (data.isEmpty()) return true

        var offset = 0
        while (offset < data.size) {
            val chunkSize = minOf(endpoint.maxPacketSize, data.size - offset)
            val written = connection.bulkTransfer(
                endpoint,
                data,
                offset,
                chunkSize,
                TIMEOUT_MS
            )
            if (written < 0) {
                Log.e(TAG, "bulkTransfer failed: $written")
                return false
            }
            offset += written
        }
        return true
    }

    /**
     * Returns whether a printer is currently connected and ready to receive data.
     */
    fun isConnected(): Boolean =
        connectionRef.get() != null && endpointRef.get() != null

    /**
     * Returns the first USB device that looks like a printer (printer class or has bulk OUT).
     * Used so the Activity can request permission for it.
     */
    fun getFirstPrinterDevice(): UsbDevice? = findFirstPrinterDevice()

    private fun findFirstPrinterDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values.filter { it.deviceId !in excludedDeviceIds }

        // Prefer true USB printer class so Caller ID / serial bridges with BULK OUT are not grabbed.
        devices.forEach { device ->
            if (deviceHasPrinterClass(device)) return device
        }
        // Fallback: vendor-class printers that only expose BULK OUT
        devices.forEach { device ->
            if (deviceHasBulkOut(device)) return device
        }
        return null
    }

    private fun deviceHasPrinterClass(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_PRINTER) return true
        }
        return false
    }

    private fun deviceHasBulkOut(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (j in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun findPrinterInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            // Prefer printer class interface
            if (usbInterface.interfaceClass != USB_CLASS_PRINTER) {
                var outEp: UsbEndpoint? = null
                for (j in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        outEp = ep
                        break
                    }
                }
                if (outEp != null) return usbInterface to outEp
                else continue
            }
            for (j in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) {
                    return usbInterface to ep
                }
            }
        }
        return null
    }
}
