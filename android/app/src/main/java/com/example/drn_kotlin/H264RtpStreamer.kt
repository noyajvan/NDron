package com.example.drn_kotlin

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мінімальний H.264/RTP-стрімер для Mission Planner.
 *
 * Приймає YUV420-кадри (NV21 або I420) від CameraX, кодує їх у H.264 через
 * MediaCodec (surface-less, byte-buffer input) і пакетизує NAL-юніти в RTP
 * згідно з RFC 6184 (single NAL + FU-A для великих NAL).
 *
 * Потік надсилається UDP-пакетами на [gcsIp]:[gcsPort].
 * SDP-опис доступний через [buildSdp] — його віддає MjpegServer на /sdp.
 */
class H264RtpStreamer(
    private val gcsIp: String,
    private val gcsPort: Int,
    private var width: Int = 640,
    private var height: Int = 480,
    private val fps: Int = 15,
    private val bitrate: Int = 1_500_000
) {
    private val TAG = "H264RtpStreamer"

    private var codec: MediaCodec? = null
    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null
    private val running = AtomicBoolean(false)

    private var ssrc = 0
    private var seq = 0
    private var timestamp = 0L

    // SPS/PPS, отримані з codec output (для SDP та для повторної відправки)
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null

    fun isRunning(): Boolean = running.get()

    fun ensureConfigured(frameWidth: Int, frameHeight: Int) {
        if (!running.get()) return
        if (width != frameWidth || height != frameHeight || codec == null) {
            Log.i(TAG, "Reconfiguring H264 encoder to ${frameWidth}x${frameHeight}")
            stopCodec()
            width = frameWidth
            height = frameHeight
            startCodec()
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        try {
            target = InetAddress.getByName(gcsIp)
            socket = DatagramSocket()
            ssrc = (Math.random() * Int.MAX_VALUE).toInt()
            startCodec()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            running.set(false)
        }
    }

    private fun startCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            Log.i(TAG, "H.264 encoder started ${width}x$height@$fps, bitrate=$bitrate -> $gcsIp:$gcsPort")
        } catch (e: Exception) {
            Log.e(TAG, "startCodec failed", e)
        }
    }

    private fun stopCodec() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        stopCodec()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        Log.i(TAG, "H.264 encoder stopped")
    }

    /**
     * Подає один YUV420-кадр у енкодер.
     * Викликати з фонового потоку.
     */
    fun pushFrame(i420: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        if (!running.get()) return
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex) ?: return
                buf.clear()
                val bytesToCopy = minOf(i420.size, buf.remaining())
                buf.put(i420, 0, bytesToCopy)
                c.queueInputBuffer(inIndex, 0, bytesToCopy, ptsUs, 0)
            }
            drainOutput()
        } catch (e: Exception) {
            Log.e(TAG, "pushFrame failed", e)
        }
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = c.outputFormat
                sps = fmt.getByteBuffer("csd-0")?.let { toArray(it) }
                pps = fmt.getByteBuffer("csd-1")?.let { toArray(it) }
                Log.i(TAG, "SPS=${sps?.size ?: 0}B PPS=${pps?.size ?: 0}B")
                continue
            }
            if (outIndex < 0) continue
            val buf = c.getOutputBuffer(outIndex) ?: continue
            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                var nal = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(nal)

                // Strip Annex B start codes (0x00000001 or 0x000001) for RFC 6184 RTP
                var nalStart = 0
                if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
                    nalStart = 4
                } else if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
                    nalStart = 3
                }
                if (nalStart > 0) {
                    nal = nal.copyOfRange(nalStart, nal.size)
                }

                if (nal.isNotEmpty()) {
                    sendNalAsRtp(nal, info.presentationTimeUs)
                }
            }
            c.releaseOutputBuffer(outIndex, false)
        }
    }

    private fun sendNalAsRtp(nal: ByteArray, ptsUs: Long) {
        val s = socket ?: return
        val t = target ?: return
        timestamp = ptsUs * 90 / 1000L // 90 kHz clock

        val maxPayload = 1400
        if (nal.size <= maxPayload) {
            val pkt = buildRtpHeader(marker = true, payloadType = PT_H264) + nal
            s.send(DatagramPacket(pkt, pkt.size, t, gcsPort))
        } else {
            // FU-A фрагментація
            val nalHeader = nal[0]
            val nalType = nalHeader.toInt() and 0x1F
            val fuIndicator = ((nalHeader.toInt() and 0xE0) or 28).toByte()
            var offset = 1
            while (offset < nal.size) {
                val remaining = nal.size - offset
                val chunk = minOf(maxPayload - 2, remaining)
                val isLast = offset + chunk >= nal.size
                val fuHeader = ((if (offset == 1) 0x80 else 0x00) or
                        (if (isLast) 0x40 else 0x00) or nalType).toByte()
                val payload = ByteArray(2 + chunk)
                payload[0] = fuIndicator
                payload[1] = fuHeader
                System.arraycopy(nal, offset, payload, 2, chunk)
                val pkt = buildRtpHeader(marker = isLast, payloadType = PT_H264) + payload
                s.send(DatagramPacket(pkt, pkt.size, t, gcsPort))
                offset += chunk
            }
        }
        seq = (seq + 1) and 0xFFFF
    }

    private fun buildRtpHeader(marker: Boolean, payloadType: Int): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte() // V=2
        h[1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
        h[2] = ((seq shr 8) and 0xFF).toByte()
        h[3] = (seq and 0xFF).toByte()
        h[4] = ((timestamp shr 24) and 0xFF).toByte()
        h[5] = ((timestamp shr 16) and 0xFF).toByte()
        h[6] = ((timestamp shr 8) and 0xFF).toByte()
        h[7] = (timestamp and 0xFF).toByte()
        h[8] = ((ssrc shr 24) and 0xFF).toByte()
        h[9] = ((ssrc shr 16) and 0xFF).toByte()
        h[10] = ((ssrc shr 8) and 0xFF).toByte()
        h[11] = (ssrc and 0xFF).toByte()
        return h
    }

    private fun toArray(buf: ByteBuffer): ByteArray {
        val a = ByteArray(buf.remaining())
        buf.get(a)
        return a
    }

    /**
     * SDP-опис для Mission Planner / GStreamer.
     */
    fun buildSdp(): String {
        val s = sps
        val p = pps
        val spsB64 = if (s != null) Base64.encodeToString(s, Base64.NO_WRAP) else ""
        val ppsB64 = if (p != null) Base64.encodeToString(p, Base64.NO_WRAP) else ""
        return buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 0.0.0.0\r\n")
            append("s=DRN H.264 Stream\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("m=video $gcsPort RTP/AVP $PT_H264\r\n")
            append("a=rtpmap:$PT_H264 H264/90000\r\n")
            append("a=fmtp:$PT_H264 packetization-mode=1")
            if (spsB64.isNotEmpty()) append(";sprop-parameter-sets=$spsB64,$ppsB64")
            append("\r\n")
        }
    }

    companion object {
        private const val MIME = "video/avc"
        private const val PT_H264 = 96
    }
}
