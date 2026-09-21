package com.example.drn_kotlin

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(port: Int) : NanoHTTPD(port) {
    private val currentFrame = AtomicReference<ByteArray?>(null)
    private val TAG = "MjpegServer"

    /**
     * Провайдер статистики від сервісу. Встановлюється ззовні.
     */
    var healthProvider: (() -> HealthSnapshot)? = null

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
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>DRN Telemetry Bridge — MJPEG</title>
                    <style>
                        body { margin:0; background:#000; color:#eee; font-family:sans-serif; }
                        header { padding:8px 12px; background:#111; font-size:14px; }
                        img { display:block; width:100%; height:auto; }
                    </style>
                </head>
                <body>
                    <header>DRN Telemetry Bridge — MJPEG stream (/stream)</header>
                    <img src="/stream" alt="MJPEG stream"/>
                </body>
                </html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        if (session.uri == "/health") {
            val frame = currentFrame.get()
            val snapshot = healthProvider?.invoke()
            val body = buildString {
                append("ok\n")
                append("frameBytes=${frame?.size ?: 0}\n")
                if (snapshot != null) {
                    append("uptimeMs=${snapshot.uptimeMs}\n")
                    append("frameCount=${snapshot.frameCount}\n")
                    append("fps=${String.format("%.1f", snapshot.fps)}\n")
                    append("lastFrameBytes=${snapshot.lastFrameSize}\n")
                    append("jpegQuality=${snapshot.jpegQuality}\n")
                    append("zoom=${snapshot.zoom}\n")
                    append("cameraStarted=${snapshot.cameraStarted}\n")
                    append("mavlinkRunning=${snapshot.mavlinkRunning}\n")
                    append("tailscaleUp=${snapshot.tailscaleUp}\n")
                    append("addresses=${snapshot.addresses.joinToString(",")}\n")
                }
            }
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
                // Чекаємо на новий кадр, але не блокуємося назавжди —
                // якщо сервер зупинено, повертаємо -1, щоб NanoHTTPD закрив з'єднання.
                var frame = currentFrame.get()
                while (frame == null) {
                    if (!isRunning()) return -1
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
            // Повертаємо 1, якщо є кадр, щоб NanoHTTPD не закрив з'єднання передчасно.
            return buffer?.available() ?: if (currentFrame.get() != null) 1 else 0
        }
    }
}
