package com.example.stsprint

import fi.iki.elonen.NanoHTTPD

/**
 * Lightweight HTTP server that accepts POST /print with raw ESC/POS data in the body
 * and forwards it to [UsbPrinterManager.print].
 * Runs on http://localhost:12345
 */
class PrintServer(
    private val port: Int = 12345,
    private val printerManager: UsbPrinterManager
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (uri == "/print" && method == Method.POST) {
            if (!printerManager.isConnected()) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"error","message":"printer not connected"}"""
                )
            }

            val bodyBytes = try {
                readRequestBody(session)
            } catch (e: Exception) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"error","message":"failed to read request body"}"""
                )
            }

            val success = printerManager.print(bodyBytes)
            return if (success) {
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
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun readRequestBody(session: IHTTPSession): ByteArray {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return ByteArray(0)
        val buffer = ByteArray(contentLength)
        val inputStream = session.inputStream
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == contentLength) buffer else buffer.copyOf(totalRead)
    }
}
