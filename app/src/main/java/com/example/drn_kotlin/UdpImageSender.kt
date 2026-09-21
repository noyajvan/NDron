package com.example.drn_kotlin

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpImageSender(host: String, private val port: Int) {
    private val socket = DatagramSocket()
    private val address = InetAddress.getByName(host)
    private val TAG = "UdpImageSender"
    
    // Mission Planner GStreamer udpsrc handles large packets better than browsers
    // but we still cap it at 65KB (max UDP size)
    private val MAX_UDP_SIZE = 65507

    fun sendImage(jpegBytes: ByteArray) {
        try {
            if (jpegBytes.size > MAX_UDP_SIZE) {
                Log.w(TAG, "Frame too large for UDP: ${jpegBytes.size} bytes")
                return
            }
            val packet = DatagramPacket(jpegBytes, jpegBytes.size, address, port)
            socket.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "UDP Send error", e)
        }
    }

    fun close() {
        try { socket.close() } catch (e: Exception) {}
    }
}