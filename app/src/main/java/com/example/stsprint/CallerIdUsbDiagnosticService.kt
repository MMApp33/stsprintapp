package com.example.stsprint

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 1 diagnostic USB listener for CID Easy (or any selected USB device).
 *
 * Opens a [UsbDeviceConnection], claims non-printer interfaces that expose BULK/INTERRUPT IN
 * endpoints, and reads raw packets on a background thread.
 *
 * Does NOT parse caller-ID protocol. Does NOT use a USB-serial library.
 * Completely separate from [UsbPrinterManager] — failures here must not affect printing.
 */
class CallerIdUsbDiagnosticService(
    private val usbManager: UsbManager,
    private val store: CallerIdDiagnosticStore
) {
    companion object {
        private const val TAG = "STS-CALLER-ID"
        private const val READ_TIMEOUT_MS = 500
        private const val USB_CLASS_PRINTER = 7
    }

    private val listening = AtomicBoolean(false)
    private var workerThread: Thread? = null

    @Volatile
    private var connection: UsbDeviceConnection? = null

    @Volatile
    private var claimedInterfaces: List<UsbInterface> = emptyList()

    @Volatile
    private var activeDeviceId: Int? = null

    fun isListening(): Boolean = listening.get()

    fun activeDeviceId(): Int? = activeDeviceId

    /**
     * Start raw USB listening on candidate IN endpoints of [device].
     * Requires USB permission already granted.
     */
    fun startListening(device: UsbDevice): Boolean {
        stopListening(updateStore = false)

        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "Permission denied — cannot open Caller ID device")
            store.setError("USB permission required for Caller ID device")
            return false
        }

        val deviceInfo = UsbDeviceInspector.inspectDevice(usbManager, device)
        val candidates = UsbDeviceInspector.candidateInEndpoints(deviceInfo)
        if (candidates.isEmpty()) {
            val msg = "No BULK/INTERRUPT IN endpoints found on non-printer interfaces"
            Log.e(TAG, msg)
            store.setError(msg)
            return false
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open USB device for Caller ID diagnostics")
            store.setError("Failed to open Caller ID USB device")
            return false
        }

        val claimed = mutableListOf<UsbInterface>()
        val readTargets = mutableListOf<ReadTarget>()

        try {
            val interfacesToClaim = candidates.map { it.first.index }.toSet()
            for (index in interfacesToClaim) {
                val usbInterface = device.getInterface(index)
                if (usbInterface.interfaceClass == USB_CLASS_PRINTER) {
                    Log.i(TAG, "Skipping printer-class interface $index")
                    continue
                }
                if (!conn.claimInterface(usbInterface, true)) {
                    Log.e(TAG, "Failed to claim interface $index")
                    releaseAll(conn, claimed)
                    store.setError("Failed to claim USB interface $index")
                    return false
                }
                claimed.add(usbInterface)
                Log.i(TAG, "Interface claimed: index=$index id=${usbInterface.id}")
            }

            for ((ifaceInfo, epInfo) in candidates) {
                val usbInterface = device.getInterface(ifaceInfo.index)
                if (!claimed.contains(usbInterface)) continue
                val endpoint = findEndpoint(usbInterface, epInfo.address) ?: continue
                readTargets.add(
                    ReadTarget(
                        interfaceIndex = ifaceInfo.index,
                        endpoint = endpoint,
                        typeLabel = epInfo.type
                    )
                )
                Log.i(
                    TAG,
                    "Candidate IN endpoint: iface=%d addr=0x%02X type=%s maxPacket=%d".format(
                        ifaceInfo.index, epInfo.address, epInfo.type, epInfo.maxPacketSize
                    )
                )
            }

            if (readTargets.isEmpty()) {
                releaseAll(conn, claimed)
                store.setError("Could not resolve any IN endpoints to read")
                return false
            }

            connection = conn
            claimedInterfaces = claimed
            activeDeviceId = device.deviceId
            listening.set(true)

            val candidateLabels = readTargets.map {
                "IF${it.interfaceIndex} EP=0x%02X ${it.typeLabel}".format(it.endpoint.address)
            }
            store.setListening(
                listening = true,
                message = "Listening on ${readTargets.size} IN endpoint(s)",
                candidates = candidateLabels
            )
            Log.i(TAG, "Listening started on deviceId=${device.deviceId}")

            val thread = Thread({
                readLoop(conn, readTargets)
            }, "CallerIdUsbListener").apply {
                isDaemon = true
                start()
            }
            workerThread = thread
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Caller ID listener", e)
            releaseAll(conn, claimed)
            connection = null
            claimedInterfaces = emptyList()
            activeDeviceId = null
            listening.set(false)
            store.setError("Listener error: ${e.message}")
            return false
        }
    }

    fun stopListening(updateStore: Boolean = true) {
        val wasListening = listening.getAndSet(false)
        val thread = workerThread
        workerThread = null

        if (thread != null && thread.isAlive) {
            try {
                thread.interrupt()
                thread.join(1500)
            } catch (_: Exception) {
            }
        }

        val conn = connection
        val interfaces = claimedInterfaces
        connection = null
        claimedInterfaces = emptyList()
        val deviceId = activeDeviceId
        activeDeviceId = null

        releaseAll(conn, interfaces)

        if (wasListening) {
            Log.i(TAG, "Listening stopped (deviceId=$deviceId)")
        }
        if (updateStore && wasListening) {
            store.setListening(false, "Listening stopped")
        }
    }

    fun onDeviceDetached(deviceId: Int) {
        if (activeDeviceId == deviceId || store.selectedDeviceId() == deviceId) {
            Log.i(TAG, "Device detached: deviceId=$deviceId")
            stopListening(updateStore = false)
            store.setDisconnected("Caller ID Device: Disconnected")
        }
    }

    private fun readLoop(conn: UsbDeviceConnection, targets: List<ReadTarget>) {
        Log.i(TAG, "Raw USB read loop running (${targets.size} endpoint(s))")
        val buffers = targets.associateWith { ByteArray(it.endpoint.maxPacketSize.coerceAtLeast(64)) }

        while (listening.get() && !Thread.currentThread().isInterrupted) {
            var anyRead = false
            for (target in targets) {
                if (!listening.get()) break
                val buffer = buffers[target] ?: continue
                try {
                    // bulkTransfer works for both BULK and INTERRUPT endpoints on Android.
                    val n = conn.bulkTransfer(
                        target.endpoint,
                        buffer,
                        buffer.size,
                        READ_TIMEOUT_MS
                    )
                    if (n > 0) {
                        anyRead = true
                        val data = buffer.copyOf(n)
                        val now = System.currentTimeMillis()
                        val packet = RawUsbPacket(
                            timestampMs = now,
                            timestampIso = CallerIdFormat.localNow(now),
                            endpointAddress = target.endpoint.address,
                            interfaceIndex = target.interfaceIndex,
                            byteCount = n,
                            hex = CallerIdFormat.toHex(data),
                            ascii = CallerIdFormat.toPrintableAscii(data)
                        )
                        Log.i(
                            TAG,
                            "Packet received: EP=0x%02X bytes=%d hex=%s ascii=%s"
                                .format(
                                    target.endpoint.address,
                                    n,
                                    packet.hex.take(96),
                                    packet.ascii.take(64)
                                )
                        )
                        store.addPacket(packet, data)
                    }
                } catch (e: Exception) {
                    if (listening.get()) {
                        Log.e(TAG, "USB read exception on EP=0x%02X".format(target.endpoint.address), e)
                        store.setError("USB read error: ${e.message}")
                        listening.set(false)
                        break
                    }
                }
            }
            if (!anyRead) {
                // Avoid tight spin when all endpoints time out
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        Log.i(TAG, "Raw USB read loop exited")
    }

    private fun findEndpoint(usbInterface: UsbInterface, address: Int): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.address == address) return ep
        }
        return null
    }

    private fun releaseAll(conn: UsbDeviceConnection?, interfaces: List<UsbInterface>) {
        if (conn == null) return
        for (iface in interfaces) {
            try {
                conn.releaseInterface(iface)
                Log.i(TAG, "Interface released: id=${iface.id}")
            } catch (e: Exception) {
                Log.w(TAG, "releaseInterface failed", e)
            }
        }
        try {
            conn.close()
            Log.i(TAG, "Device opened connection closed")
        } catch (e: Exception) {
            Log.w(TAG, "connection.close failed", e)
        }
    }

    private data class ReadTarget(
        val interfaceIndex: Int,
        val endpoint: UsbEndpoint,
        val typeLabel: String
    )
}
