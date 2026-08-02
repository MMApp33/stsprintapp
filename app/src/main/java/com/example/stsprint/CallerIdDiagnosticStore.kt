package com.example.stsprint

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe Caller ID diagnostic state shared by the USB listener,
 * foreground service, UI, and localhost HTTP server.
 *
 * Memory limits: last [MAX_PACKETS] packets and roughly [MAX_RAW_BYTES] of rolling raw data.
 */
class CallerIdDiagnosticStore {

    companion object {
        const val MAX_PACKETS = 500
        const val MAX_RAW_BYTES = 1 * 1024 * 1024 // 1 MB
        const val MAX_LOG_CHARS = 64 * 1024
        const val HTTP_RECENT_PACKETS = 50
        const val LOG_RETENTION_DAYS = 7
        const val LOG_RETENTION_MS = LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000L
    }

    private val lock = Any()
    private val statusRef = AtomicReference(CallerIdStatus.NOT_DETECTED)
    private val statusMessageRef = AtomicReference("Not detected")
    private val devicesRef = AtomicReference<List<UsbDeviceInfo>>(emptyList())
    private val selectedDeviceIdRef = AtomicReference<Int?>(null)
    private val listeningRef = AtomicReference(false)
    private val errorMessageRef = AtomicReference<String?>(null)
    private val lastPacketAtIsoRef = AtomicReference<String?>(null)
    private val candidateEndpointsRef = AtomicReference<List<String>>(emptyList())

    private val packets = ArrayDeque<RawUsbPacket>()
    private val rollingRaw = ArrayDeque<Byte>()
    private val asciiLogBuilder = StringBuilder()
    private val hexLogBuilder = StringBuilder()

    private val listeners = CopyOnWriteArrayList<(CallerIdSnapshot) -> Unit>()

    @Volatile
    private var latestPhone: String? = null

    @Volatile
    private var hasNewCall = false

    fun addListener(listener: (CallerIdSnapshot) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (CallerIdSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun setDevices(devices: List<UsbDeviceInfo>) {
        synchronized(lock) {
            devicesRef.set(devices)
            val selectedId = selectedDeviceIdRef.get()
            val selected = devices.firstOrNull { it.deviceId == selectedId }
            if (selectedId != null && selected == null) {
                // Selected device disappeared from scan; keep id until detach handler clears it
            }
            if (devices.isEmpty() && !listeningRef.get()) {
                statusRef.set(CallerIdStatus.NOT_DETECTED)
                statusMessageRef.set("Not detected")
            } else if (selected != null) {
                refreshStatusLocked(selected)
            } else if (devices.isNotEmpty() && selectedId == null) {
                statusRef.set(CallerIdStatus.DETECTED)
                statusMessageRef.set("Detected (${devices.size} USB device(s)) — select Caller ID device")
            }
        }
        notifyListeners()
    }

    fun selectDevice(deviceId: Int?) {
        synchronized(lock) {
            selectedDeviceIdRef.set(deviceId)
            val selected = devicesRef.get().firstOrNull { it.deviceId == deviceId }
            if (selected == null) {
                if (deviceId == null) {
                    statusRef.set(
                        if (devicesRef.get().isEmpty()) CallerIdStatus.NOT_DETECTED
                        else CallerIdStatus.DETECTED
                    )
                    statusMessageRef.set(
                        if (devicesRef.get().isEmpty()) "Not detected"
                        else "Detected — select Caller ID device"
                    )
                }
            } else {
                refreshStatusLocked(selected)
            }
        }
        notifyListeners()
    }

    fun setPermissionGranted(granted: Boolean, message: String) {
        synchronized(lock) {
            statusMessageRef.set(message)
            val selected = currentSelectedLocked()
            if (selected != null) {
                // Refresh hasPermission from latest scan is caller's job; update status here
                statusRef.set(
                    when {
                        listeningRef.get() -> CallerIdStatus.LISTENING
                        granted -> CallerIdStatus.CONNECTED
                        else -> CallerIdStatus.PERMISSION_REQUIRED
                    }
                )
            } else if (!granted) {
                statusRef.set(CallerIdStatus.PERMISSION_REQUIRED)
            }
            if (!granted) {
                errorMessageRef.set(message)
            } else {
                errorMessageRef.set(null)
            }
        }
        notifyListeners()
    }

    fun setListening(listening: Boolean, message: String? = null, candidates: List<String> = emptyList()) {
        synchronized(lock) {
            listeningRef.set(listening)
            if (candidates.isNotEmpty()) {
                candidateEndpointsRef.set(candidates)
            }
            if (message != null) statusMessageRef.set(message)
            val selected = currentSelectedLocked()
            statusRef.set(
                when {
                    listening -> CallerIdStatus.LISTENING
                    selected?.hasPermission == true -> CallerIdStatus.CONNECTED
                    selected != null -> CallerIdStatus.PERMISSION_REQUIRED
                    devicesRef.get().isNotEmpty() -> CallerIdStatus.DETECTED
                    else -> CallerIdStatus.NOT_DETECTED
                }
            )
            if (listening) errorMessageRef.set(null)
        }
        notifyListeners()
    }

    fun setDisconnected(message: String = "Caller ID Device: Disconnected") {
        synchronized(lock) {
            listeningRef.set(false)
            selectedDeviceIdRef.set(null)
            statusRef.set(CallerIdStatus.DISCONNECTED)
            statusMessageRef.set(message)
            candidateEndpointsRef.set(emptyList())
        }
        notifyListeners()
    }

    fun setError(message: String) {
        synchronized(lock) {
            listeningRef.set(false)
            statusRef.set(CallerIdStatus.ERROR)
            statusMessageRef.set(message)
            errorMessageRef.set(message)
        }
        notifyListeners()
    }

    fun addPacket(packet: RawUsbPacket, rawBytes: ByteArray) {
        synchronized(lock) {
            packets.addLast(packet)
            while (packets.size > MAX_PACKETS) {
                packets.removeFirst()
            }
            cleanupOldPackets()

            for (b in rawBytes) {
                rollingRaw.addLast(b)
            }
            while (rollingRaw.size > MAX_RAW_BYTES) {
                rollingRaw.removeFirst()
            }
            lastPacketAtIsoRef.set(packet.timestampIso)

            appendLogLine(
                asciiLogBuilder,
                buildString {
                    append(packet.timestampIso)
                    append("  EP=0x%02X bytes=%d\n".format(packet.endpointAddress, packet.byteCount))
                    append(packet.ascii)
                    append('\n')
                }
            )
            appendLogLine(
                hexLogBuilder,
                buildString {
                    append(packet.timestampIso)
                    append("  EP=0x%02X bytes=%d\n".format(packet.endpointAddress, packet.byteCount))
                    append(packet.hex)
                    append('\n')
                }
            )

            // Extract phone number from NMBR field if present
            val phone = extractPhoneNumber(packet.ascii)
            if (phone != null && phone.isNotEmpty()) {
                latestPhone = phone
                hasNewCall = true
            }

            statusRef.set(CallerIdStatus.LISTENING)
            statusMessageRef.set("Listening — packet received (${packet.byteCount} bytes)")
        }
        notifyListeners()
    }

    fun clearLogs() {
        synchronized(lock) {
            packets.clear()
            rollingRaw.clear()
            asciiLogBuilder.setLength(0)
            hexLogBuilder.setLength(0)
            lastPacketAtIsoRef.set(null)
        }
        notifyListeners()
    }

    fun snapshot(): CallerIdSnapshot {
        synchronized(lock) {
            val selectedId = selectedDeviceIdRef.get()
            val devices = devicesRef.get()
            val selected = devices.firstOrNull { it.deviceId == selectedId }
            return CallerIdSnapshot(
                status = statusRef.get(),
                statusMessage = statusMessageRef.get(),
                devices = devices,
                selectedDeviceId = selectedId,
                selectedDevice = selected,
                permissionGranted = selected?.hasPermission == true,
                listening = listeningRef.get(),
                lastPacketAtIso = lastPacketAtIsoRef.get(),
                packets = packets.toList(),
                asciiLog = asciiLogBuilder.toString(),
                hexLog = hexLogBuilder.toString(),
                errorMessage = errorMessageRef.get(),
                candidateEndpoints = candidateEndpointsRef.get()
            )
        }
    }

    fun selectedDeviceId(): Int? = selectedDeviceIdRef.get()

    fun isListening(): Boolean = listeningRef.get()

    fun getOldestPacketDate(): String? = synchronized(lock) {
        packets.firstOrNull()?.timestampIso
    }

    fun getLatestCall(): Pair<Boolean, String?> {
        return Pair(hasNewCall, latestPhone)
    }

    fun markCallAsRead() {
        hasNewCall = false
    }

    private fun extractPhoneNumber(ascii: String): String? {
        val nmbrMatch = Regex("\\[NMBR=([^\\]]+)\\]").find(ascii)
        return nmbrMatch?.groupValues?.get(1)?.trim()
    }

    private fun cleanupOldPackets() {
        val now = System.currentTimeMillis()
        val cutoff = now - LOG_RETENTION_MS
        while (packets.isNotEmpty() && packets.first().timestampMs < cutoff) {
            packets.removeFirst()
        }
    }

    private fun currentSelectedLocked(): UsbDeviceInfo? {
        val id = selectedDeviceIdRef.get() ?: return null
        return devicesRef.get().firstOrNull { it.deviceId == id }
    }

    private fun refreshStatusLocked(selected: UsbDeviceInfo) {
        when {
            listeningRef.get() -> {
                statusRef.set(CallerIdStatus.LISTENING)
                statusMessageRef.set("Listening")
            }
            selected.hasPermission -> {
                statusRef.set(CallerIdStatus.CONNECTED)
                statusMessageRef.set("Caller ID USB permission granted")
            }
            else -> {
                statusRef.set(CallerIdStatus.PERMISSION_REQUIRED)
                statusMessageRef.set("USB permission required for Caller ID device")
            }
        }
    }

    private fun appendLogLine(builder: StringBuilder, line: String) {
        builder.append(line)
        if (builder.length > MAX_LOG_CHARS) {
            builder.delete(0, builder.length - MAX_LOG_CHARS)
        }
    }

    private fun notifyListeners() {
        val snap = snapshot()
        for (listener in listeners) {
            try {
                listener(snap)
            } catch (_: Exception) {
            }
        }
    }
}

internal object CallerIdFormat {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val localFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun isoNow(ms: Long = System.currentTimeMillis()): String =
        synchronized(isoFormat) { isoFormat.format(Date(ms)) }

    fun localNow(ms: Long = System.currentTimeMillis()): String =
        synchronized(localFormat) { localFormat.format(Date(ms)) }

    fun toHex(bytes: ByteArray, length: Int = bytes.size): String {
        if (length <= 0) return ""
        val sb = StringBuilder(length * 3)
        for (i in 0 until length) {
            if (i > 0) sb.append(' ')
            sb.append("%02X".format(bytes[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun toPrintableAscii(bytes: ByteArray, length: Int = bytes.size): String {
        if (length <= 0) return ""
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val c = bytes[i].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        return sb.toString()
    }
}
