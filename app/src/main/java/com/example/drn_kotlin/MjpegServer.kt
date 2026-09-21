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
        // Порт RTP-відео зарезервовано, але RTP не реалізовано.
        // Реальний потік — MJPEG на HTTP-порті, переданому в конструктор.
        const val VIDEO_PORT = TelemetryBridgeService.VIDEO_UDP_PORT
    }

    fun updateFrame(jpegData: ByteArray) {
        currentFrame.set(jpegData)
    }

    fun isRunning(): Boolean = wasStarted()

    override fun serve(session: IHTTPSession): Response {
        Log.i(TAG, "New request from ${session.remoteIpAddress}: ${session.uri}")
        if (session.uri == "/sdp") {
            // H.264/RTP-енкодер у цьому проекті НЕ реалізовано.
            // Єдиний реальний потік — MJPEG на /stream (порт 8888).
            // Повертаємо текстову підказку замість фальшивого SDP.
            val body = "H.264/RTP not implemented.\n" +
                "Use MJPEG stream: http://<IP>:8888/stream\n"
            return newFixedLengthResponse(Response.Status.OK, "text/plain", body)
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
            // Використовуємо чіткий формат MJPEG.
            // УВАГА: boundary у заголовку БЕЗ провідних "--" (дефіси додаються у тілі).
            val response = newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame",
                MjpegStream()
            )
            response.addHeader("Cache-Control", "no-cache, private")
            response.addHeader("Pragma", "no-cache")
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
