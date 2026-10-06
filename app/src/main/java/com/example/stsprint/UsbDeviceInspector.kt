package com.example.stsprint

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Enumerates connected USB devices and builds human-readable diagnostic descriptions.
 * Does not open devices or claim interfaces — inspection only.
 *
 * IMPORTANT: Do not assume CID Easy is USB-serial, HID, or CDC until VID/PID/class
 * and endpoint data from a real device confirm it.
 */
object UsbDeviceInspector {

    private const val TAG = "STS-CALLER-ID"
    private const val USB_CLASS_PRINTER = 7

    fun scanAllDevices(usbManager: UsbManager): List<UsbDeviceInfo> {
        Log.i(TAG, "USB scan started")
        val results = mutableListOf<UsbDeviceInfo>()
        for (device in usbManager.deviceList.values) {
            val info = inspectDevice(usbManager, device)
            results.add(info)
            Log.i(
                TAG,
                "USB device discovered: ${info.displayName} " +
                    "VID=${info.vendorIdHex} PID=${info.productIdHex} " +
                    "class=${info.deviceClassLabel} interfaces=${info.interfaceCount} " +
                    "permission=${info.hasPermission}" +
                    (info.suspectedChipHint?.let { " hint=$it" } ?: "")
            )
            info.interfaces.forEach { iface ->
                Log.i(
                    TAG,
                    "  Interface[${iface.index}] id=${iface.id} class=${iface.interfaceClassLabel} " +
                        "subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol} " +
                        "endpoints=${iface.endpointCount}"
                )
                iface.endpoints.forEach { ep ->
                    Log.i(
                        TAG,
                        "    Endpoint 0x%02X dir=%s type=%s maxPacket=%d interval=%d".format(
                            ep.address, ep.direction, ep.type, ep.maxPacketSize, ep.interval
                        )
                    )
                }
            }
        }
        Log.i(TAG, "USB devices discovered: ${results.size}")
        return results.sortedBy { it.deviceId }
    }

    fun inspectDevice(usbManager: UsbManager, device: UsbDevice): UsbDeviceInfo {
        val hasPermission = usbManager.hasPermission(device)
        val interfaces = mutableListOf<InterfaceInfo>()
        for (i in 0 until device.interfaceCount) {
            interfaces.add(inspectInterface(device.getInterface(i), i))
        }
        val looksLikePrinter = deviceLooksLikePrinter(device)
        return UsbDeviceInfo(
            deviceName = device.deviceName ?: "unknown",
            deviceId = device.deviceId,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            deviceClassLabel = usbClassLabel(device.deviceClass),
            deviceSubclass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            manufacturerName = safeName(device.manufacturerName),
            productName = safeName(device.productName),
            serialNumber = if (hasPermission) safeName(device.serialNumber) else null,
            interfaceCount = device.interfaceCount,
            interfaces = interfaces,
            looksLikePrinter = looksLikePrinter,
            suspectedChipHint = suspectChipHint(device, interfaces),
            hasPermission = hasPermission
        )
    }

    fun findDeviceById(usbManager: UsbManager, deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }

    fun candidateInEndpoints(deviceInfo: UsbDeviceInfo): List<Pair<InterfaceInfo, EndpointInfo>> {
        val candidates = mutableListOf<Pair<InterfaceInfo, EndpointInfo>>()
        for (iface in deviceInfo.interfaces) {
            // Prefer not to claim printer-class interfaces (protect existing print path)
            if (iface.interfaceClass == USB_CLASS_PRINTER) continue
            for (ep in iface.endpoints) {
                if (ep.direction == "IN" && (ep.type == "BULK" || ep.type == "INTERRUPT")) {
                    candidates.add(iface to ep)
                }
            }
        }
        return candidates
    }

    fun deviceLooksLikePrinter(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == USB_CLASS_PRINTER) return true
        }
        return false
    }

    private fun inspectInterface(usbInterface: UsbInterface, index: Int): InterfaceInfo {
        val endpoints = mutableListOf<EndpointInfo>()
        for (j in 0 until usbInterface.endpointCount) {
            endpoints.add(inspectEndpoint(usbInterface.getEndpoint(j)))
        }
        return InterfaceInfo(
            index = index,
            id = usbInterface.id,
            interfaceClass = usbInterface.interfaceClass,
            interfaceClassLabel = usbClassLabel(usbInterface.interfaceClass),
            interfaceSubclass = usbInterface.interfaceSubclass,
            interfaceProtocol = usbInterface.interfaceProtocol,
            endpointCount = usbInterface.endpointCount,
            endpoints = endpoints
        )
    }

    private fun inspectEndpoint(endpoint: UsbEndpoint): EndpointInfo {
        return EndpointInfo(
            address = endpoint.address,
            number = endpoint.endpointNumber,
            direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
            type = transferTypeLabel(endpoint.type),
            maxPacketSize = endpoint.maxPacketSize,
            interval = endpoint.interval
        )
    }

    fun usbClassLabel(usbClass: Int): String = when (usbClass) {
        UsbConstants.USB_CLASS_PER_INTERFACE -> "PER_INTERFACE (0)"
        UsbConstants.USB_CLASS_AUDIO -> "AUDIO (1)"
        UsbConstants.USB_CLASS_COMM -> "COMM/CDC (2)"
        UsbConstants.USB_CLASS_HID -> "HID (3)"
        UsbConstants.USB_CLASS_PHYSICA -> "PHYSICAL (5)"
        UsbConstants.USB_CLASS_STILL_IMAGE -> "STILL_IMAGE (6)"
        USB_CLASS_PRINTER -> "PRINTER (7)"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS_STORAGE (8)"
        UsbConstants.USB_CLASS_HUB -> "HUB (9)"
        UsbConstants.USB_CLASS_CDC_DATA -> "CDC_DATA (10)"
        UsbConstants.USB_CLASS_CSCID -> "SMART_CARD (11)"
        UsbConstants.USB_CLASS_CONTENT_SEC -> "CONTENT_SECURITY (13)"
        UsbConstants.USB_CLASS_VIDEO -> "VIDEO (14)"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "WIRELESS (224)"
        UsbConstants.USB_CLASS_MISC -> "MISC (239)"
        UsbConstants.USB_CLASS_APP_SPEC -> "APP_SPECIFIC (254)"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPECIFIC (255)"
        else -> "UNKNOWN ($usbClass)"
    }

    fun transferTypeLabel(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        else -> "UNKNOWN ($type)"
    }

    /**
     * Soft hints only — never used to select a driver. Helps interpret diagnostics.
     * Known USB-serial bridge VIDs are common but CID Easy may use something else.
     */
    private fun suspectChipHint(device: UsbDevice, interfaces: List<InterfaceInfo>): String? {
        val hints = mutableListOf<String>()
        when (device.vendorId) {
            0x0403 -> hints.add("VID matches FTDI family (common USB-serial bridge)")
            0x1A86 -> hints.add("VID matches WCH/CH340 family (common USB-serial bridge)")
            0x10C4 -> hints.add("VID matches Silicon Labs CP210x family")
            0x067B -> hints.add("VID matches Prolific family")
            0x04E2 -> hints.add("VID matches Exar/MaxLinear family")
        }
        val classes = interfaces.map { it.interfaceClass }.toSet()
        when {
            UsbConstants.USB_CLASS_HID in classes ->
                hints.add("Exposes HID interface — may behave like HID")
            UsbConstants.USB_CLASS_COMM in classes || UsbConstants.USB_CLASS_CDC_DATA in classes ->
                hints.add("Exposes CDC/COMM interface — may behave like USB CDC ACM serial")
            classes.any { it == UsbConstants.USB_CLASS_VENDOR_SPEC } ->
                hints.add("Vendor-specific interface — may need raw USB or a vendor driver")
        }
        val hasBulkIn = interfaces.any { iface ->
            iface.endpoints.any { it.direction == "IN" && it.type == "BULK" }
        }
        val hasInterruptIn = interfaces.any { iface ->
            iface.endpoints.any { it.direction == "IN" && it.type == "INTERRUPT" }
        }
        when {
            hasBulkIn -> hints.add("Has BULK IN endpoint(s)")
            hasInterruptIn -> hints.add("Has INTERRUPT IN endpoint(s)")
        }
        return hints.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun safeName(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
