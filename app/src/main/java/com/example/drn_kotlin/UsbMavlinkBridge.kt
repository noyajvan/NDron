package com.example.drn_kotlin

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class UsbMavlinkBridge(private val context: Context, private val gcsIp: String, private val gcsPort: Int) {
    private val TAG = "UsbMavlinkBridge"
    private var serialPort: UsbSerialPort? = null
    private var udpSocket: DatagramSocket? = null
    private val executor = Executors.newFixedThreadPool(2)
    private var running = false

    fun start() {
        if (running) return
        
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            Log.e(TAG, "No USB Serial devices found")
            return
        }

        val driver = availableDrivers[0]
        val connection = manager.openDevice(driver.device) ?: return

        serialPort = driver.ports[0]
        serialPort?.open(connection)
        serialPort?.setParameters(57600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        udpSocket = DatagramSocket()
        running = true

        // Thread 1: USB -> UDP
        executor.execute {
            val buffer = ByteArray(4096)
            val address = InetAddress.getByName(gcsIp)
            while (running) {
                try {
                    val len = serialPort?.read(buffer, 1000) ?: 0
                    if (len > 0) {
                        val packet = DatagramPacket(buffer, len, address, gcsPort)
                        udpSocket?.send(packet)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "USB to UDP error", e)
                }
            }
        }

        // Thread 2: UDP -> USB (Optional, for commands from GCS)
        // Слухаємо на окремому локальному порту, щоб не конфліктувати з GCS (gcsPort).
        executor.execute {
            val buffer = ByteArray(4096)
            val localPort = gcsPort + 1
            val socket = try {
                DatagramSocket(localPort)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot bind UDP local port $localPort", e)
                return@execute
            }
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    serialPort?.write(packet.data.copyOfRange(0, packet.length), 1000)
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "UDP to USB error", e)
                }
            }
            socket.close()
        }
        
        Log.d(TAG, "Bridge started")
    }

    fun stop() {
        running = false
        serialPort?.close()
        udpSocket?.close()
        Log.d(TAG, "Bridge stopped")
    }
}
