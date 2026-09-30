package com.example.drn_kotlin

import android.Manifest
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.net.wifi.WifiManager
import android.os.Build
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TelemetryBridgeService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFrameTime = 0L
    private var mjpegServer: MjpegServer? = null
    private var h264Streamer: H264RtpStreamer? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraStarted = false

    private var jpegQuality = 25
    private var currentZoom = 1.0f

    @Volatile private var targetWidth = 640
    @Volatile private var targetHeight = 480
    @Volatile private var targetFps = 8
    @Volatile private var frameIntervalMs = 125L

    // Reusable buffer to prevent GC thrashing
    private var cachedNv21: ByteArray? = null

    // Traffic tracking & Oracle Cloud Free Tier limits
    private var totalSessionBytes = 0L
    private var bytesThisMinute = 0L
    private var lastTrafficWindowTime = System.currentTimeMillis()
    private var trafficMbPerMin = 0.0
    private var sessionMb = 0.0
    private var oracleLimitPercent = 0.0
    private val ORACLE_MONTHLY_LIMIT_BYTES = 10L * 1024L * 1024L * 1024L * 1024L // 10 TB

    @Synchronized
    private fun recordBytes(bytes: Long) {
        totalSessionBytes += bytes
        bytesThisMinute += bytes
        val now = System.currentTimeMillis()
        val elapsed = now - lastTrafficWindowTime
        if (elapsed >= 60_000L) {
            trafficMbPerMin = bytesThisMinute.toDouble() / (1024.0 * 1024.0)
            bytesThisMinute = 0L
            lastTrafficWindowTime = now
        } else if (elapsed >= 1000L) {
            trafficMbPerMin = (bytesThisMinute.toDouble() / elapsed * 60_000.0) / (1024.0 * 1024.0)
        }
        sessionMb = totalSessionBytes.toDouble() / (1024.0 * 1024.0)
        oracleLimitPercent = (totalSessionBytes.toDouble() / ORACLE_MONTHLY_LIMIT_BYTES) * 100.0
    }

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
        const val ACTION_SET_RESOLUTION = "ACTION_SET_RESOLUTION"
        const val ACTION_SET_FPS = "ACTION_SET_FPS"
        
        const val EXTRA_ZOOM = "EXTRA_ZOOM"
        const val EXTRA_QUALITY = "EXTRA_QUALITY"
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"
        const val EXTRA_FPS = "EXTRA_FPS"
        
        const val STATS_UPDATE = "com.example.drn_kotlin.STATS_UPDATE"
        const val EXTRA_FRAME_SIZE = "EXTRA_FRAME_SIZE"
        const val EXTRA_TRAFFIC_MB_MIN = "EXTRA_TRAFFIC_MB_MIN"
        const val EXTRA_SESSION_MB = "EXTRA_SESSION_MB"
        const val EXTRA_ORACLE_PERCENT = "EXTRA_ORACLE_PERCENT"
        
        @Volatile var previewSurfaceProvider: Preview.SurfaceProvider? = null
        
        const val SERVER_PORT = 8888
        const val VIDEO_UDP_PORT = 5600
        // Порт RTP-відео для Mission Planner (GStreamer)
        const val VIDEO_RTP_PORT = 5600

        // Fallback GCS IP (Oracle VPS)
        const val DEFAULT_GCS_IP = "152.70.51.224"

        // Як часто логувати статистику кадрів
        private const val FRAME_LOG_INTERVAL_MS = 5000L
    }

    /**
     * Повертає актуальний GCS IP: Tailscale-адреса комп'ютера, якщо відома,
     * інакше — DEFAULT_GCS_IP.
     */
    private fun resolveGcsIp(): String {
        val tsIp = NetworkUtils.findTailscaleIp()
        if (tsIp == null) {
            Log.w(TAG, "Tailscale не піднятий, використовуємо DEFAULT_GCS_IP=$DEFAULT_GCS_IP")
        } else {
            Log.i(TAG, "Tailscale IP телефону: $tsIp, GCS=$DEFAULT_GCS_IP")
        }
        return DEFAULT_GCS_IP
    }

    private fun ensureServerRunning() {
        try {
            if (mjpegServer == null || !mjpegServer!!.isRunning() || !mjpegServer!!.isAlive) {
                try { mjpegServer?.stop() } catch (_: Exception) {}
                mjpegServer = MjpegServer(SERVER_PORT).apply {
                    healthProvider = { getHealthSnapshot() }
                    sdpProvider = { h264Streamer?.buildSdp() ?: "" }
                    controlHandler = { zoom, quality, fps, width, height ->
                        mainHandler.post {
                            if (zoom != null) setZoom(zoom)
                            if (quality != null) jpegQuality = quality.coerceIn(10, 100)
                            if (fps != null) setFps(fps)
                            if (width != null && height != null) setResolution(width, height)
                        }
                    }
                    start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
                Log.i(TAG, "MJPEG Server started on 0.0.0.0:$SERVER_PORT")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure MJPEG Server running", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        ensureServerRunning()

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
                } else {
                    bindCameraUseCases()
                }
                startH264Streamer()
            }
            ACTION_STOP -> {
                h264Streamer?.stop()
                h264Streamer = null
                mjpegServer?.stop()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
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
            ACTION_SET_RESOLUTION -> {
                startForegroundServiceInternal()
                val w = intent.getIntExtra(EXTRA_WIDTH, 640)
                val h = intent.getIntExtra(EXTRA_HEIGHT, 480)
                setResolution(w, h)
            }
            ACTION_SET_FPS -> {
                startForegroundServiceInternal()
                val fps = intent.getIntExtra(EXTRA_FPS, 15)
                setFps(fps)
            }
        }
        return START_STICKY
    }

    private fun setZoom(zoom: Float) {
        currentZoom = zoom
        camera?.cameraControl?.setZoomRatio(zoom)
    }

    private fun setFps(fps: Int) {
        targetFps = fps.coerceIn(5, 60)
        frameIntervalMs = 1000L / targetFps
        Log.i(TAG, "Target FPS updated to $targetFps ($frameIntervalMs ms)")
    }

    private fun setResolution(width: Int, height: Int) {
        if (targetWidth == width && targetHeight == height) return
        targetWidth = width
        targetHeight = height
        Log.i(TAG, "Target Resolution updated to ${targetWidth}x${targetHeight}")
        if (cameraStarted) {
            rebuildImageAnalysis()
            bindCameraUseCases()
        }
    }

    private fun startH264Streamer() {
        if (h264Streamer?.isRunning() == true) return
        val gcsIp = resolveGcsIp()
        h264Streamer = H264RtpStreamer(
            gcsIp = gcsIp,
            gcsPort = VIDEO_RTP_PORT,
            width = targetWidth,
            height = targetHeight,
            fps = targetFps,
            bitrate = 1_500_000
        ).also { it.start() }
        Log.i(TAG, "H.264/RTP streamer -> $gcsIp:$VIDEO_RTP_PORT")
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalGetImage::class)
    private fun rebuildImageAnalysis() {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(targetWidth, targetHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val imageAnalysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(Surface.ROTATION_0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        val camera2Config = Camera2Interop.Extender(imageAnalysisBuilder)
        try {
            camera2Config.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            camera2Config.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Manual focus not supported by this device camera: ${e.message}")
        }

        imageAnalysis = imageAnalysisBuilder.build().also { analysis ->
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastFrameTime >= frameIntervalMs) {
                        val image = imageProxy.image
                        if (image != null) {
                            val nv21 = yuv420ToNv21(image)
                            
                            val out = ByteArrayOutputStream(nv21.size / 4)
                            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, out)
                            val jpeg = out.toByteArray()

                            mjpegServer?.updateFrame(jpeg)
                            publishJpegToVps(jpeg)
                            recordBytes(jpeg.size.toLong())

                            if (h264Streamer?.isRunning() == true) {
                                h264Streamer?.ensureConfigured(image.width, image.height)
                                h264Streamer?.pushFrame(nv21, currentTime * 1000L)
                                val h264Bytes = (1_500_000 / targetFps / 8).toLong()
                                recordBytes(h264Bytes)
                            }

                            val broadcastIntent = Intent(STATS_UPDATE).apply {
                                putExtra(EXTRA_FRAME_SIZE, jpeg.size)
                                putExtra(EXTRA_TRAFFIC_MB_MIN, trafficMbPerMin)
                                putExtra(EXTRA_SESSION_MB, sessionMb)
                                putExtra(EXTRA_ORACLE_PERCENT, oracleLimitPercent)
                            }
                            sendBroadcast(broadcastIntent)
                            lastFrameTime = currentTime

                            frameCount++
                            lastFrameSize = jpeg.size
                            framesInWindow++
                            val windowElapsed = currentTime - lastFpsWindowTime
                            if (windowElapsed >= 1000L) {
                                currentFps = framesInWindow * 1000.0 / windowElapsed
                                framesInWindow = 0
                                lastFpsWindowTime = currentTime
                            }

                            if (currentTime - lastFrameLogTime >= FRAME_LOG_INTERVAL_MS) {
                                Log.i(
                                    TAG,
                                    "Frames=$frameCount, FPS=${String.format("%.1f", currentFps)}, " +
                                        "lastFrame=${lastFrameSize / 1024}KB, quality=$jpegQuality, zoom=$currentZoom, res=${image.width}x${image.height}"
                                )
                                lastFrameLogTime = currentTime
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing frame in analyzer", e)
                } finally {
                    imageProxy.close()
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                rebuildImageAnalysis()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val analysis = imageAnalysis ?: return
        try {
            provider.unbindAll()
            val surfaceProvider = previewSurfaceProvider
            if (surfaceProvider != null) {
                val preview = Preview.Builder().setTargetRotation(Surface.ROTATION_0).build()
                preview.setSurfaceProvider(surfaceProvider)
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, preview)
            } else {
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }

            camera?.cameraInfo?.cameraState?.observe(this) { state ->
                if (state.type == CameraState.Type.CLOSED && cameraStarted) {
                    Log.w(TAG, "Camera state CLOSED unexpectedly, re-binding camera...")
                    mainHandler.postDelayed({
                        if (cameraStarted) bindCameraUseCases()
                    }, 1000L)
                }
            }

            Log.d(TAG, "Camera use cases bound (preview=${surfaceProvider != null})")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    private val latestVpsFrame = AtomicReference<ByteArray?>()
    private val isUploading = AtomicBoolean(false)

    private fun publishJpegToVps(jpeg: ByteArray) {
        latestVpsFrame.set(jpeg)
        if (isUploading.compareAndSet(false, true)) {
            netExecutor.execute {
                uploadLoop()
            }
        }
    }

    private fun uploadLoop() {
        try {
            while (true) {
                val frame = latestVpsFrame.getAndSet(null) ?: break
                try {
                    val url = URL("http://152.70.51.224:8888/publish")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.setRequestProperty("Content-Type", "image/jpeg")
                    conn.outputStream.use { os ->
                        os.write(frame)
                    }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        } finally {
            isUploading.set(false)
            if (latestVpsFrame.get() != null && isUploading.compareAndSet(false, true)) {
                netExecutor.execute { uploadLoop() }
            }
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val requiredSize = ySize + ySize / 2

        var nv21 = cachedNv21
        if (nv21 == null || nv21.size != requiredSize) {
            nv21 = ByteArray(requiredSize)
            cachedNv21 = nv21
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                val rowOffset = row * yRowStride
                for (col in 0 until width) {
                    nv21[pos++] = yBuffer.get(rowOffset + col * yPixelStride)
                }
            }
        }

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        for (row in 0 until height / 2) {
            val vRowOffset = row * vRowStride
            val uRowOffset = row * uRowStride
            for (col in 0 until width / 2) {
                val vIndex = vRowOffset + col * vPixelStride
                val uIndex = uRowOffset + col * uPixelStride
                nv21[pos++] = if (vIndex < vBuffer.limit()) vBuffer.get(vIndex) else 0
                nv21[pos++] = if (uIndex < uBuffer.limit()) uBuffer.get(uIndex) else 0
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
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            targetFps = targetFps,
            cameraStarted = cameraStarted,
            h264Running = h264Streamer?.isRunning() ?: false,
            tailscaleUp = NetworkUtils.isTailscaleUp(),
            addresses = NetworkUtils.listIpv4Addresses(),
            trafficMbPerMin = trafficMbPerMin,
            sessionMb = sessionMb,
            oracleLimitPercent = oracleLimitPercent,
            batteryTempC = getBatteryTemperature(),
            memoryUsageMb = getMemoryUsageMb()
        )
    }

    private fun getBatteryTemperature(): Double {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun getMemoryUsageMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    override fun onDestroy() {
        cameraStarted = false
        h264Streamer?.stop()
        h264Streamer = null
        mjpegServer?.stop()
        releaseWakeLock()
        cameraExecutor.shutdown()
        netExecutor.shutdown()
        cachedNv21 = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DRN:TelemetryWakeLock"
            )
            wakeLock?.acquire()
        }
        if (wifiLock == null) {
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DRN:WifiLock")
                wifiLock?.acquire()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire WifiLock", e)
            }
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
        }
        if (wifiLock?.isHeld == true) {
            try { wifiLock?.release() } catch (_: Exception) {}
            wifiLock = null
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
            .setSmallIcon(R.drawable.stat_notify_sync)
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
    val targetWidth: Int,
    val targetHeight: Int,
    val targetFps: Int,
    val cameraStarted: Boolean,
    val h264Running: Boolean,
    val tailscaleUp: Boolean,
    val addresses: List<String>,
    val trafficMbPerMin: Double,
    val sessionMb: Double,
    val oracleLimitPercent: Double,
    val batteryTempC: Double,
    val memoryUsageMb: Long
)
