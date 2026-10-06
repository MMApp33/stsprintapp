package com.example.stsprint

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket

/**
 * Lightweight HTTP server that accepts POST /print with raw ESC/POS data in the body
 * and forwards it to [UsbPrinterManager.print].
 * Runs on http://localhost:12345
 *
 * Also exposes Phase 1 Caller ID diagnostic endpoints (read-only). These must never
 * interfere with the existing /print path.
 *
 * Important: The request body is read and forwarded as raw binary (byte array) only.
 * No string decoding (e.g. UTF-8) is applied. This preserves ESC/POS binary commands
 * (e.g. GS v 0 raster logo, GS ( k QR code) so the printer receives the exact bytes.
 *
 * CORS: Responds to OPTIONS (preflight) and adds CORS headers to all responses so
 * browsers can call from web apps (e.g. when using mode: 'cors' and Content-Type:
 * application/octet-stream for binary receipts with logo).
 */
class PrintServer(
    private val port: Int = 12345,
    private val printerManager: UsbPrinterManager,
    private val callerIdStore: CallerIdDiagnosticStore? = null
) : NanoHTTPD(port) {

    companion object {
        /** Max body size when Content-Length is missing (read until EOF). Prevents OOM. */
        private const val MAX_BODY_SIZE = 2 * 1024 * 1024 // 2 MB

        // Exact CORS origins (local dev / known apps). Origin never has a trailing slash.
        private val ALLOWED_ORIGINS = setOf(
            "https://scantoserve.com",
            "https://retailpos.scantoserve.com",
            "http://localhost:12345",
            "http://localhost:3000"
        )

        /** Apex + any subdomain, e.g. https://retailpos.scantoserve.com */
        private const val ALLOWED_HOST_SUFFIX = "scantoserve.com"

        fun isPortAvailable(port: Int): Boolean {
            return try {
                val socket = java.net.ServerSocket(port)
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun findAvailablePort(startPort: Int = 12345, maxTries: Int = 10): Int {
            for (port in startPort until startPort + maxTries) {
                if (isPortAvailable(port)) return port
            }
            return startPort // fallback to default even if unavailable
        }

        /**
         * Allows exact local origins, https://scantoserve.com, and any https subdomain
         * of scantoserve.com (for example retailpos.scantoserve.com).
         */
        fun isOriginAllowed(origin: String?): Boolean {
            if (origin.isNullOrBlank()) return false
            if (ALLOWED_ORIGINS.contains(origin)) return true
            return try {
                val uri = java.net.URI(origin)
                val host = uri.host?.lowercase() ?: return false
                val scheme = uri.scheme?.lowercase()
                scheme == "https" &&
                    (host == ALLOWED_HOST_SUFFIX || host.endsWith(".$ALLOWED_HOST_SUFFIX"))
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val requestOrigin = session.headers["origin"]

        // CORS preflight
        if (method == Method.OPTIONS && (
                uri == "/print" ||
                    uri == "/api/caller-id/status" ||
                    uri == "/api/caller-id/diagnostics" ||
                    uri == "/api/status" ||
                    uri == "/api/latest-call"
                )
        ) {
            return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", ""), requestOrigin)
        }

        // Existing print endpoint — unchanged behavior
        if (uri == "/print" && method == Method.POST) {
            if (!printerManager.isConnected()) {
                return addCorsHeaders(newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"error","message":"printer not connected"}"""
                ), requestOrigin)
            }

            val bodyBytes = try {
                readRequestBodyAsBinary(session)
            } catch (e: Exception) {
                return addCorsHeaders(newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"error","message":"failed to read request body"}"""
                ), requestOrigin)
            }

            val success = printerManager.print(bodyBytes)
            return addCorsHeaders(if (success) {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"printed"}"""
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"error","message":"print failed"}"""
                )
            }, requestOrigin)
        }

        // Caller ID diagnostics (Phase 1) — isolated from printing
        if (uri == "/api/caller-id/status" && method == Method.GET) {
            return addCorsHeaders(
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    buildCallerIdStatusJson()
                ), requestOrigin
            )
        }

        if (uri == "/api/caller-id/diagnostics" && method == Method.GET) {
            return addCorsHeaders(
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    buildCallerIdDiagnosticsJson()
                ), requestOrigin
            )
        }

        // API: Get caller ID status
        if (uri == "/api/status" && method == Method.GET) {
            val snap = callerIdStore?.snapshot()
            return addCorsHeaders(
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    JSONObject().apply {
                        put("printerConnected", printerManager.isConnected())
                        put("callerIdListening", snap?.listening ?: false)
                        put("callerIdStatus", snap?.status?.name ?: "NOT_DETECTED")
                        put("serverPort", 12345)
                    }.toString()
                ), requestOrigin
            )
        }

        // API: Get latest call
        if (uri == "/api/latest-call" && method == Method.GET) {
            val (hasNewCall, phone) = callerIdStore?.getLatestCall() ?: Pair(false, null)
            callerIdStore?.markCallAsRead()

            return addCorsHeaders(
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    if (hasNewCall && phone != null) {
                        JSONObject().apply {
                            put("hasNewCall", true)
                            put("phone", phone)
                        }.toString()
                    } else {
                        JSONObject().apply {
                            put("hasNewCall", false)
                        }.toString()
                    }
                ), requestOrigin
            )
        }

        return addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found"), requestOrigin)
    }

    private fun buildCallerIdStatusJson(): String {
        val snap = callerIdStore?.snapshot()
        val selected = snap?.selectedDevice
        return JSONObject().apply {
            put("supported", true)
            put("deviceDetected", selected != null || (snap?.devices?.isNotEmpty() == true))
            put("permissionGranted", snap?.permissionGranted == true)
            put("listening", snap?.listening == true)
            put("status", snap?.status?.name ?: "NOT_DETECTED")
            put("statusMessage", snap?.statusMessage ?: "Not detected")
            if (selected != null) {
                put("vendorId", selected.vendorId)
                put("productId", selected.productId)
                put("vendorIdHex", selected.vendorIdHex)
                put("productIdHex", selected.productIdHex)
                put("deviceId", selected.deviceId)
                put("productName", selected.productName)
            } else {
                put("vendorId", JSONObject.NULL)
                put("productId", JSONObject.NULL)
            }
            put("lastPacketAt", snap?.lastPacketAtIso ?: JSONObject.NULL)
        }.toString()
    }

    private fun buildCallerIdDiagnosticsJson(): String {
        val snap = callerIdStore?.snapshot()
        val selected = snap?.selectedDevice
        val recent = snap?.packets?.takeLast(CallerIdDiagnosticStore.HTTP_RECENT_PACKETS).orEmpty()

        val deviceJson = if (selected != null) {
            JSONObject().apply {
                put("deviceName", selected.deviceName)
                put("deviceId", selected.deviceId)
                put("vendorId", selected.vendorId)
                put("productId", selected.productId)
                put("vendorIdHex", selected.vendorIdHex)
                put("productIdHex", selected.productIdHex)
                put("deviceClass", selected.deviceClass)
                put("deviceClassLabel", selected.deviceClassLabel)
                put("manufacturerName", selected.manufacturerName)
                put("productName", selected.productName)
                put("serialNumber", selected.serialNumber)
                put("suspectedChipHint", selected.suspectedChipHint)
                put("hasPermission", selected.hasPermission)
                put("looksLikePrinter", selected.looksLikePrinter)
                put("interfaces", interfacesToJson(selected.interfaces))
            }
        } else {
            JSONObject.NULL
        }

        val packetsJson = JSONArray()
        for (p in recent) {
            packetsJson.put(JSONObject().apply {
                put("timestamp", p.timestampIso)
                put("endpoint", "0x%02X".format(p.endpointAddress))
                put("interfaceIndex", p.interfaceIndex)
                put("bytes", p.byteCount)
                put("hex", p.hex)
                put("ascii", p.ascii)
            })
        }

        return JSONObject().apply {
            put("status", snap?.status?.name ?: "NOT_DETECTED")
            put("statusMessage", snap?.statusMessage ?: "Not detected")
            put("listening", snap?.listening == true)
            put("permissionGranted", snap?.permissionGranted == true)
            put("candidateEndpoints", JSONArray(snap?.candidateEndpoints.orEmpty()))
            put("device", deviceJson)
            put("scannedDeviceCount", snap?.devices?.size ?: 0)
            put("recentPacketCount", recent.size)
            put("recentPackets", packetsJson)
            put("errorMessage", snap?.errorMessage ?: JSONObject.NULL)
        }.toString()
    }

    private fun interfacesToJson(interfaces: List<InterfaceInfo>): JSONArray {
        val arr = JSONArray()
        for (iface in interfaces) {
            val eps = JSONArray()
            for (ep in iface.endpoints) {
                eps.put(JSONObject().apply {
                    put("address", "0x%02X".format(ep.address))
                    put("number", ep.number)
                    put("direction", ep.direction)
                    put("type", ep.type)
                    put("maxPacketSize", ep.maxPacketSize)
                    put("interval", ep.interval)
                })
            }
            arr.put(JSONObject().apply {
                put("index", iface.index)
                put("id", iface.id)
                put("class", iface.interfaceClass)
                put("classLabel", iface.interfaceClassLabel)
                put("subclass", iface.interfaceSubclass)
                put("protocol", iface.interfaceProtocol)
                put("endpointCount", iface.endpointCount)
                put("endpoints", eps)
            })
        }
        return arr
    }

    private fun addCorsHeaders(response: Response, requestOrigin: String? = null): Response {
        val origin = if (isOriginAllowed(requestOrigin)) requestOrigin else "https://scantoserve.com"
        response.addHeader("Access-Control-Allow-Origin", origin)
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Access-Control-Allow-Credentials", "true")
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    /**
     * Reads the POST body as raw binary. No charset or string decoding is used.
     * The exact bytes (ESC/POS init, GS v 0 raster, GS ( k QR, text, etc.) are returned unchanged.
     */
    private fun readRequestBodyAsBinary(session: IHTTPSession): ByteArray {
        val contentLengthHeader = session.headers["content-length"]?.trim()?.toIntOrNull()

        if (contentLengthHeader != null && contentLengthHeader > 0) {
            if (contentLengthHeader > MAX_BODY_SIZE) {
                throw IllegalArgumentException("Content-Length $contentLengthHeader exceeds max $MAX_BODY_SIZE")
            }
            val buffer = ByteArray(contentLengthHeader)
            val inputStream = session.inputStream
            var totalRead = 0
            while (totalRead < contentLengthHeader) {
                val read = inputStream.read(buffer, totalRead, contentLengthHeader - totalRead)
                if (read == -1) break
                totalRead += read
            }
            return if (totalRead == contentLengthHeader) buffer else buffer.copyOf(totalRead)
        }

        // No Content-Length: read until EOF (e.g. client sent binary without header), cap at MAX_BODY_SIZE
        val out = ByteArrayOutputStream(8192)
        val inputStream = session.inputStream
        val buf = ByteArray(8192)
        var total = 0
        while (total < MAX_BODY_SIZE) {
            val read = inputStream.read(buf)
            if (read == -1) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}
