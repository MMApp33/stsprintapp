package com.example.stsprint

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream

/**
 * Lightweight HTTP server that accepts POST /print with raw ESC/POS data in the body
 * and forwards it to [UsbPrinterManager.print].
 * Runs on http://localhost:12345
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
    private val printerManager: UsbPrinterManager
) : NanoHTTPD(port) {

    companion object {
        /** Max body size when Content-Length is missing (read until EOF). Prevents OOM. */
        private const val MAX_BODY_SIZE = 2 * 1024 * 1024 // 2 MB
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // CORS preflight: browser sends OPTIONS when request has custom headers or non-simple method
        if (uri == "/print" && method == Method.OPTIONS) {
            return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }

        if (uri == "/print" && method == Method.POST) {
            if (!printerManager.isConnected()) {
                return addCorsHeaders(newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"error","message":"printer not connected"}"""
                ))
            }

            val bodyBytes = try {
                readRequestBodyAsBinary(session)
            } catch (e: Exception) {
                return addCorsHeaders(newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"error","message":"failed to read request body"}"""
                ))
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
            })
        }

        return addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found"))
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
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
