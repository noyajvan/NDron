package com.example.drn_kotlin

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(port: Int) : NanoHTTPD(port) {
    private val currentFrame = AtomicReference<ByteArray?>(null)
    private val TAG = "MjpegServer"

    companion object {
        // Порт RTP-відео (має збігатися з TelemetryBridgeService.VIDEO_UDP_PORT)
        const val VIDEO_PORT = 5600
    }

    fun updateFrame(jpegData: ByteArray) {
        currentFrame.set(jpegData)
    }

    fun isRunning(): Boolean = wasStarted()

    override fun serve(session: IHTTPSession): Response {
        Log.i(TAG, "New request from ${session.remoteIpAddress}: ${session.uri}")
        if (session.uri == "/sdp") {
            // УВАГА: H.264/RTP-енкодер у цьому проекті НЕ реалізовано.
            // Реально доступний лише MJPEG-потік на /stream (порт 8888).
            // Повертаємо SDP для MJPEG-over-RTP (payload 26 = JPEG), щоб клієнти
            // (VLC, GStreamer) могли підключитися до реального потоку.
            val sdp = buildString {
                append("v=0\r\n")
                append("o=- 0 0 IN IP4 0.0.0.0\r\n")
                append("s=DRN MJPEG Stream\r\n")
                append("c=IN IP4 0.0.0.0\r\n")
                append("t=0 0\r\n")
                append("m=video ${MjpegServer.VIDEO_PORT} RTP/AVP 26\r\n")
                append("a=rtpmap:26 JPEG/90000\r\n")
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/sdp",
                sdp
            )
        }
        if (session.uri == "/" || session.uri == "/index.html") {
            val html = """
                <html><body style="margin:0;background:#000">
                <img src="/stream" style="width:100%;height:auto"/>
                </body></html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        if (session.uri == "/health") {
            val frame = currentFrame.get()
            val body = "ok\nframeBytes=${frame?.size ?: 0}\n"
            return newFixedLengthResponse(Response.Status.OK, "text/plain", body)
        }
        if (session.uri == "/stream") {
            // Використовуємо чіткий формат MJPEG
            val response = newChunkedResponse(
                Response.Status.OK, 
                "multipart/x-mixed-replace; boundary=--frame", 
                MjpegStream()
            )
            response.addHeader("Cache-Control", "no-cache, private")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Connection", "close")
            return response
        }
        return newFixedLengthResponse("Connect to /stream (MJPEG) or / (HTML viewer)")
    }

    private inner class MjpegStream : InputStream() {
        private var buffer: ByteArrayInputStream? = null

        override fun read(): Int {
            if (buffer == null || buffer!!.available() <= 0) {
                // Чекаємо на новий кадр
                var frame = currentFrame.get()
                while (frame == null) {
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                    frame = currentFrame.get()
                }

                // Формуємо блок кадру за стандартом MJPEG
                val header = "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                val footer = "\r\n"
                val data = header.toByteArray() + frame + footer.toByteArray()
                buffer = ByteArrayInputStream(data)
            }
            return buffer!!.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (buffer == null || buffer!!.available() <= 0) {
                val first = read()
                if (first == -1) return -1
                b[off] = first.toByte()
                if (len == 1) return 1
                val read = buffer!!.read(b, off + 1, len - 1)
                return if (read <= 0) 1 else read + 1
            }
            return buffer!!.read(b, off, len)
        }

        override fun available(): Int {
            return buffer?.available() ?: 0
        }
    }
}
