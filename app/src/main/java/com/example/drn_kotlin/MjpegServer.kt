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

    override fun serve(session: IHTTPSession): Response {
        Log.i(TAG, "New request from ${session.remoteIpAddress}: ${session.uri}")
        if (session.uri == "/sdp") {
            // SDP-опис H.264/RTP потоку для Mission Planner (GStreamer)
            val sdp = buildString {
                append("v=0\r\n")
                append("o=- 0 0 IN IP4 0.0.0.0\r\n")
                append("s=DRN H264 Stream\r\n")
                append("c=IN IP4 0.0.0.0\r\n")
                append("t=0 0\r\n")
                append("m=video ${MjpegServer.VIDEO_PORT} RTP/AVP 96\r\n")
                append("a=rtpmap:96 H264/90000\r\n")
                append("a=fmtp:96 packetization-mode=1\r\n")
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/sdp",
                sdp
            )
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
        return newFixedLengthResponse("Connect to /stream")
    }

    private inner class MjpegStream : InputStream() {
        private var buffer: ByteArrayInputStream? = null

        override fun read(): Int {
            if (buffer == null || buffer!!.available() <= 0) {
                // Чекаємо на новий кадр
                var frame = currentFrame.get()
                while (frame == null) {
                    Thread.sleep(10)
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
                if (read() == -1) return -1
                // read() ініціалізує buffer, тепер читаємо з нього
            }
            return buffer!!.read(b, off, len)
        }

        override fun available(): Int {
            return buffer?.available() ?: 0
        }
    }
}
