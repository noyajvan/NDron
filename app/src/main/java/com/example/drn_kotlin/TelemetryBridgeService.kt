package com.example.drn_kotlin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class TelemetryBridgeService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastFrameTime = 0L
    private var mjpegServer: MjpegServer? = null
    private var mavlinkBridge: UsbMavlinkBridge? = null
    private var camera: Camera? = null
    private var cameraStarted = false

    private var jpegQuality = 40
    private var currentZoom = 1.0f

    // Статистика для /health та логування
    private var startTimeMs = 0L
    private var frameCount = 0L
    private var lastFrameSize = 0
    private var lastFrameLogTime = 0L
    private var lastFpsWindowTime = 0L
    private var framesInWindow = 0
    private var currentFps = 0.0

    companion object {
        private const val TAG = "TelemetryBridgeService"
        const val CHANNEL_ID = "DroneBridgeServiceChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SET_ZOOM = "ACTION_SET_ZOOM"
        const val ACTION_SET_QUALITY = "ACTION_SET_QUALITY"
        
        const val EXTRA_ZOOM = "EXTRA_ZOOM"
        const val EXTRA_QUALITY = "EXTRA_QUALITY"
        
        const val STATS_UPDATE = "com.example.drn_kotlin.STATS_UPDATE"
        const val EXTRA_FRAME_SIZE = "EXTRA_FRAME_SIZE"
        
        const val SERVER_PORT = 8888
        const val MAVLINK_UDP_PORT = 14550
        const val VIDEO_UDP_PORT = 5600

        // Fallback GCS IP, якщо Tailscale не піднятий
        const val DEFAULT_GCS_IP = "100.104.253.54"

        // Скільки мс між кадрами (5 FPS)
        private const val FRAME_INTERVAL_MS = 200L
        // Як часто логувати статистику кадрів
        private const val FRAME_LOG_INTERVAL_MS = 5000L
    }

    /**
     * Повертає актуальний GCS IP: Tailscale-адреса комп'ютера, якщо відома,
     * інакше — DEFAULT_GCS_IP.
     */
    private fun resolveGcsIp(): String {
        // Якщо Tailscale піднятий, використовуємо його як джерело для GCS.
        // Реальний GCS IP задається константою, але якщо він недоступний —
        // логуємо попередження.
        val tsIp = NetworkUtils.findTailscaleIp()
        if (tsIp == null) {
            Log.w(TAG, "Tailscale не піднятий, використовуємо DEFAULT_GCS_IP=$DEFAULT_GCS_IP")
        } else {
            Log.i(TAG, "Tailscale IP телефону: $tsIp, GCS=$DEFAULT_GCS_IP")
        }
        return DEFAULT_GCS_IP
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        try {
            // NanoHTTPD(port) binds to all interfaces by default (0.0.0.0)
            mjpegServer = MjpegServer(SERVER_PORT)
            mjpegServer?.healthProvider = { getHealthSnapshot() }
            mjpegServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "MJPEG Server started on 0.0.0.0:$SERVER_PORT (running=${mjpegServer?.isRunning()})")
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG Server start FAILED on port $SERVER_PORT", e)
        }
        try {
            val gcsIp = resolveGcsIp()
            mavlinkBridge = UsbMavlinkBridge(this, gcsIp, MAVLINK_UDP_PORT)
            Log.d(TAG, "MAVLink Bridge created (GCS=$gcsIp)")
        } catch (e: Exception) {
            Log.e(TAG, "MAVLink Bridge init FAILED", e)
        }
        startTimeMs = System.currentTimeMillis()
        lastFpsWindowTime = startTimeMs
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                acquireWakeLock()
                startForegroundServiceInternal()
                if (!cameraStarted) {
                    cameraStarted = true
                    startCamera()
                }
                mavlinkBridge?.start()
            }
            ACTION_STOP -> {
                mavlinkBridge?.stop()
                mjpegServer?.stop()
                releaseWakeLock()
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SET_ZOOM -> {
                startForegroundServiceInternal()
                setZoom(intent.getFloatExtra(EXTRA_ZOOM, 1.0f))
            }
            ACTION_SET_QUALITY -> {
                startForegroundServiceInternal()
                jpegQuality = intent.getIntExtra(EXTRA_QUALITY, 40).coerceIn(1, 100)
            }
        }
        return START_STICKY
    }

    private fun setZoom(zoom: Float) {
        currentZoom = zoom
        camera?.cameraControl?.setZoomRatio(zoom)
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val imageAnalysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            val camera2Config = Camera2Interop.Extender(imageAnalysisBuilder)
            camera2Config.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            camera2Config.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) 

            val imageAnalysis = imageAnalysisBuilder.build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFrameTime >= FRAME_INTERVAL_MS) { // 5 FPS
                    val image = imageProxy.image
                    if (image != null) {
                        val jpeg = imageToJpeg(image, jpegQuality)
                        mjpegServer?.updateFrame(jpeg)
                        sendBroadcast(Intent(STATS_UPDATE).putExtra(EXTRA_FRAME_SIZE, jpeg.size))
                        lastFrameTime = currentTime

                        // Оновлюємо статистику
                        frameCount++
                        lastFrameSize = jpeg.size
                        framesInWindow++
                        val windowElapsed = currentTime - lastFpsWindowTime
                        if (windowElapsed >= 1000L) {
                            currentFps = framesInWindow * 1000.0 / windowElapsed
                            framesInWindow = 0
                            lastFpsWindowTime = currentTime
                        }

                        // Періодичне логування
                        if (currentTime - lastFrameLogTime >= FRAME_LOG_INTERVAL_MS) {
                            Log.i(
                                TAG,
                                "Frames=$frameCount, FPS=${String.format("%.1f", currentFps)}, " +
                                    "lastFrame=${lastFrameSize / 1024}KB, quality=$jpegQuality, zoom=$currentZoom"
                            )
                            lastFrameLogTime = currentTime
                        }
                    }
                }
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
                Log.d(TAG, "Camera active")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageToJpeg(image: Image, quality: Int): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val out = ByteArrayOutputStream()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // Копіюємо Y канал (яскравість) попіксельно, ігноруючи системне вирівнювання (strides)
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
            }
        }

        // Копіюємо UV канали (колір) з правильним чергуванням V-U-V-U (формат NV21)
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        pos = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                // V канал йде першим у парі для NV21
                nv21[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                // U канал йде другим
                nv21[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }
        return nv21
    }

    /**
     * Повертає знімок статистики для /health.
     */
    fun getHealthSnapshot(): HealthSnapshot {
        val uptimeMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        return HealthSnapshot(
            uptimeMs = uptimeMs,
            frameCount = frameCount,
            fps = currentFps,
            lastFrameSize = lastFrameSize,
            jpegQuality = jpegQuality,
            zoom = currentZoom,
            cameraStarted = cameraStarted,
            mavlinkRunning = mavlinkBridge?.isRunning() ?: false,
            tailscaleUp = NetworkUtils.isTailscaleUp(),
            addresses = NetworkUtils.listIpv4Addresses()
        )
    }

    override fun onDestroy() {
        cameraStarted = false
        mavlinkBridge?.stop()
        mjpegServer?.stop()
        releaseWakeLock()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Не даємо системі вбити сервіс при свайпі зі списку задач
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DRN:TelemetryWakeLock")
            wakeLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
        }
    }

    private fun startForegroundServiceInternal() {
        val notification = createNotification("Бортовий агент активний")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            val cameraGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cameraGranted) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: SecurityException) {
                Log.e(TAG, "startForeground with type=$type failed, retry without camera", e)
                val fallback = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification, fallback)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drone Bridge Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Drone Telemetry Channel", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}

/**
 * Знімок стану сервісу для /health.
 */
data class HealthSnapshot(
    val uptimeMs: Long,
    val frameCount: Long,
    val fps: Double,
    val lastFrameSize: Int,
    val jpegQuality: Int,
    val zoom: Float,
    val cameraStarted: Boolean,
    val mavlinkRunning: Boolean,
    val tailscaleUp: Boolean,
    val addresses: List<String>
)
