package com.example.stsprint

/**
 * Data models for Phase 1 Caller ID USB diagnostics.
 * No protocol parsing — raw device/endpoint/packet inspection only.
 */

enum class CallerIdStatus {
    NOT_DETECTED,
    DETECTED,
    PERMISSION_REQUIRED,
    CONNECTED,
    LISTENING,
    DISCONNECTED,
    ERROR
}

data class EndpointInfo(
    val address: Int,
    val number: Int,
    val direction: String,
    val type: String,
    val maxPacketSize: Int,
    val interval: Int
)

data class InterfaceInfo(
    val index: Int,
    val id: Int,
    val interfaceClass: Int,
    val interfaceClassLabel: String,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpointCount: Int,
    val endpoints: List<EndpointInfo>
)

data class UsbDeviceInfo(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceClassLabel: String,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val interfaceCount: Int,
    val interfaces: List<InterfaceInfo>,
    val looksLikePrinter: Boolean,
    val suspectedChipHint: String?,
    val hasPermission: Boolean
) {
    val vendorIdHex: String get() = "0x%04X".format(vendorId)
    val productIdHex: String get() = "0x%04X".format(productId)

    val displayName: String
        get() {
            val product = productName?.takeIf { it.isNotBlank() }
            val manufacturer = manufacturerName?.takeIf { it.isNotBlank() }
            return when {
                product != null && manufacturer != null -> "$product ($manufacturer)"
                product != null -> product
                manufacturer != null -> manufacturer
                else -> deviceName
            }
        }
}

data class RawUsbPacket(
    val timestampMs: Long,
    val timestampIso: String,
    val endpointAddress: Int,
    val interfaceIndex: Int,
    val byteCount: Int,
    val hex: String,
    val ascii: String
)

data class CallerIdSnapshot(
    val status: CallerIdStatus = CallerIdStatus.NOT_DETECTED,
    val statusMessage: String = "Not detected",
    val devices: List<UsbDeviceInfo> = emptyList(),
    val selectedDeviceId: Int? = null,
    val selectedDevice: UsbDeviceInfo? = null,
    val permissionGranted: Boolean = false,
    val listening: Boolean = false,
    val lastPacketAtIso: String? = null,
    val packets: List<RawUsbPacket> = emptyList(),
    val asciiLog: String = "",
    val hexLog: String = "",
    val errorMessage: String? = null,
    val candidateEndpoints: List<String> = emptyList()
)
