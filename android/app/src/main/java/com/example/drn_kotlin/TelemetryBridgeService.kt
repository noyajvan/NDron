package com.example.drn_kotlin

import android.Manifest
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Єдиний відеоконвеєр: камера -> MJPEG -> вбудований HTTP-сервер (MjpegServer).
 *
 * Свідомо НЕ використовує H.264/RTP та публікацію на VPS: реле на Oracle
 * обслуговує лише MAVLink, тому ці шляхи нікуди не доставляли відео й лише
 * гріли телефон. Відео віддається напряму через Tailscale/hotspot.
 */
class TelemetryBridgeService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mjpegServer: MjpegServer? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile private var cameraStarted = false
    @Volatile private var serviceRunning = false

    private var currentZoom = 1.0f

    // Налаштування, які задає користувач. Термоадаптація може їх тимчасово знижувати.
    @Volatile private var userFps = 8
    @Volatile private var userQuality = 25

    // Ефективні значення (після термоадаптації).
    @Volatile private var targetFps = 8
    @Volatile private var frameIntervalMs = 125L
    @Volatile private var jpegQuality = 25

    // Запит користувача і фактична роздільність камери (може знижуватися при нагріві).
    @Volatile private var targetWidth = 640
    @Volatile private var targetHeight = 480
    @Volatile private var camWidth = 640
    @Volatile private var camHeight = 480
    @Volatile private var lowResActive = false
    private var lastResRebind = 0L

    @Volatile private var thermalStatus = PowerManager.THERMAL_STATUS_NONE

    // Еко-режим (екран вимкнено): примусово мінімальні fps/якість.
    @Volatile private var ecoMode = false

    // Локальне прев'ю камери (керується сервісом, щоб не вмикалося випадково).
    @Volatile private var previewEnabled = true

    // Reusable buffer to prevent GC thrashing
    private var cachedNv21: ByteArray? = null
    private var uvRowBuf: ByteArray? = null

    private val snapshotRequest = AtomicBoolean(false)

    // Статистика для /health та логування
    private var lastFrameTime = 0L
    private var startTimeMs = 0L
    private var frameCount = 0L
    private var lastFrameSize = 0
    private var lastFrameLogTime = 0L
    private var lastStatsBroadcastTime = 0L
    private var lastFpsWindowTime = 0L
    private var framesInWindow = 0
    private var currentFps = 0.0
    private var totalProducedBytes = 0L
    private var sessionMb = 0.0

    // Швидкість з'єднання (реально віддані глядачам байти).
    private var lastStreamBytes = 0L
    private var lastStreamBytesTime = 0L
    private var streamKbps = 0.0

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
        const val ACTION_SNAPSHOT = "ACTION_SNAPSHOT"
        const val ACTION_SET_ECO = "ACTION_SET_ECO"
        const val ACTION_SET_PREVIEW = "ACTION_SET_PREVIEW"

        const val EXTRA_ZOOM = "EXTRA_ZOOM"
        const val EXTRA_QUALITY = "EXTRA_QUALITY"
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"
        const val EXTRA_FPS = "EXTRA_FPS"
        const val EXTRA_ECO = "EXTRA_ECO"
        const val EXTRA_PREVIEW = "EXTRA_PREVIEW"

        const val STATS_UPDATE = "com.example.drn_kotlin.STATS_UPDATE"
        const val EXTRA_FRAME_SIZE = "EXTRA_FRAME_SIZE"
        const val EXTRA_SESSION_MB = "EXTRA_SESSION_MB"
        const val EXTRA_STREAM_FPS = "EXTRA_STREAM_FPS"
        const val EXTRA_STREAM_KBPS = "EXTRA_STREAM_KBPS"
        const val EXTRA_CLIENTS = "EXTRA_CLIENTS"
        const val EXTRA_THERMAL = "EXTRA_THERMAL"

        const val SNAPSHOT_SAVED = "com.example.drn_kotlin.SNAPSHOT_SAVED"
        const val EXTRA_SNAPSHOT_PATH = "EXTRA_SNAPSHOT_PATH"

        @Volatile var previewSurfaceProvider: Preview.SurfaceProvider? = null

        const val SERVER_PORT = 8888

        // Fallback GCS IP (Oracle VPS) — використовується лише в /health для довідки
        const val DEFAULT_GCS_IP = "152.70.51.224"

        private const val FRAME_LOG_INTERVAL_MS = 5000L
        private const val STATS_BROADCAST_INTERVAL_MS = 1000L
        private const val SNAPSHOT_QUALITY = 95
    }

    private fun ensureServerRunning() {
        try {
            if (mjpegServer == null || !mjpegServer!!.isRunning() || !mjpegServer!!.isAlive) {
                try { mjpegServer?.stop() } catch (_: Exception) {}
                mjpegServer = MjpegServer(SERVER_PORT).apply {
                    healthProvider = { getHealthSnapshot() }
                    controlHandler = { zoom, quality, fps, width, height ->
                        mainHandler.post {
                            if (zoom != null) setZoom(zoom)
                            if (quality != null) {
                                userQuality = quality.coerceIn(10, 100)
                                applyThermalState()
                            }
                            if (fps != null) {
                                userFps = fps.coerceIn(5, 60)
                                applyThermalState()
                            }
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
        registerThermalListener()

        val saved = Prefs.load(this)
        userFps = saved.fps
        userQuality = saved.quality
        targetWidth = saved.width
        targetHeight = saved.height
        camWidth = saved.width
        camHeight = saved.height
        currentZoom = saved.zoom
        targetFps = userFps
        frameIntervalMs = 1000L / targetFps
        jpegQuality = userQuality

        startTimeMs = System.currentTimeMillis()
        lastFpsWindowTime = startTimeMs
        lastStatsBroadcastTime = startTimeMs
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.d(TAG, "onStartCommand: $action")

        if (action == ACTION_STOP) {
            // На випадок прямого startService(ACTION_STOP) — спершу виконуємо вимогу foreground.
            startForegroundServiceInternal()
            stopEverything()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Будь-яка інша дія гарантує запущений foreground-сервіс.
        startForegroundServiceInternal()
        if (!serviceRunning) {
            serviceRunning = true
            acquireWakeLock()
            applyThermalState()
        }

        // Камера стартує з першою ж дією; повторний bind лише на явний старт
        // (щоб зміна zoom/якості не перезапускала камеру).
        val isStart = action == null || action == ACTION_START
        if (!cameraStarted) {
            cameraStarted = true
            startCamera()
        } else if (isStart) {
            bindCameraUseCases()
        }

        when (action) {
            ACTION_SET_ZOOM -> setZoom(intent.getFloatExtra(EXTRA_ZOOM, 1.0f))
            ACTION_SET_QUALITY -> {
                userQuality = intent.getIntExtra(EXTRA_QUALITY, userQuality).coerceIn(10, 100)
                Prefs.saveQuality(this, userQuality)
                applyThermalState()
            }
            ACTION_SET_RESOLUTION -> {
                setResolution(
                    intent.getIntExtra(EXTRA_WIDTH, targetWidth),
                    intent.getIntExtra(EXTRA_HEIGHT, targetHeight)
                )
            }
            ACTION_SET_FPS -> {
                userFps = intent.getIntExtra(EXTRA_FPS, userFps).coerceIn(5, 60)
                Prefs.saveFps(this, userFps)
                applyThermalState()
            }
            ACTION_SNAPSHOT -> snapshotRequest.set(true)
            ACTION_SET_ECO -> {
                ecoMode = intent.getBooleanExtra(EXTRA_ECO, false)
                applyThermalState()
            }
            ACTION_SET_PREVIEW -> {
                previewEnabled = intent.getBooleanExtra(EXTRA_PREVIEW, true)
                bindCameraUseCases()
            }
        }
        return START_STICKY
    }

    private fun setZoom(zoom: Float) {
        currentZoom = zoom
        Prefs.saveZoom(this, zoom)
        camera?.cameraControl?.setZoomRatio(zoom)
    }

    private fun setResolution(width: Int, height: Int) {
        if (targetWidth == width && targetHeight == height) return
        targetWidth = width
        targetHeight = height
        Prefs.saveResolution(this, width, height)
        Log.i(TAG, "Target Resolution updated to ${targetWidth}x${targetHeight}")
        // Фактичну роздільність камери визначає термоадаптація.
        applyThermalState()
    }

    /**
     * Знижує fps/якість при нагріві та повертає назад, коли телефон охолов.
     */
    private fun applyThermalState() {
        val fps: Int
        val quality: Int
        when {
            ecoMode -> {
                fps = 4
                quality = 20
            }
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                fps = 6
                quality = 20
            }
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
                fps = 8
                quality = 25
            }
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> {
                fps = userFps
                quality = minOf(userQuality, 30)
            }
            else -> {
                fps = userFps
                quality = userQuality
            }
        }
        // Нижня межа 1: термоадаптація має мати право опускатися нижче userFps.
        targetFps = fps.coerceIn(1, 60)
        frameIntervalMs = 1000L / targetFps
        jpegQuality = quality.coerceIn(10, 100)

        // Гістерезис роздільності: вмикаємо low-res лише при CRITICAL, вимикаємо
        // тільки коли впало до MODERATE або нижче. Так перехід 3<->4 не смикає камеру.
        when {
            ecoMode -> lowResActive = false
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> lowResActive = true
            thermalStatus <= PowerManager.THERMAL_STATUS_MODERATE -> lowResActive = false
        }
        val wantW = if (lowResActive) minOf(targetWidth, 640) else targetWidth
        val wantH = if (lowResActive) minOf(targetHeight, 480) else targetHeight
        val resChanged = (wantW != camWidth || wantH != camHeight)
        camWidth = wantW
        camHeight = wantH

        Log.i(TAG, "Thermal=$thermalStatus eco=$ecoMode lowRes=$lowResActive -> fps=$targetFps quality=$jpegQuality res=${camWidth}x${camHeight}")

        // Перебінд лише за зміни роздільності, не частіше ніж раз на 15 с —
        // щоб уникнути «шторму» перебіндингу при коливаннях температури.
        val now = System.currentTimeMillis()
        if (resChanged && cameraStarted && now - lastResRebind > 15_000) {
            lastResRebind = now
            rebuildImageAnalysis()
            bindCameraUseCases()
        }
    }

    /**
     * Підбирає підтримуваний діапазон FPS камери, найближчий до потрібного,
     * щоб ISP не працював на максимальній частоті дарма (це головне джерело нагріву).
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun chooseFpsRange(fps: Int): Range<Int> {
        return try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()
            val ranges = backId?.let {
                cm.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            }
            val picked = if (ranges == null || ranges.isEmpty()) {
                Range(fps, fps)
            } else {
                ranges.firstOrNull { it.upper == fps && it.lower == fps }
                    ?: ranges.filter { it.upper <= fps }.maxByOrNull { it.upper }
                    ?: ranges.minByOrNull { it.upper }
                    ?: Range(fps, fps)
            }
            Log.i(TAG, "AE FPS range for $fps -> [$picked]")
            picked
        } catch (e: Exception) {
            Log.w(TAG, "chooseFpsRange fallback: ${e.message}")
            Range(fps, fps)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalGetImage::class)
    private fun rebuildImageAnalysis() {
        val isWide = camWidth.toFloat() / camHeight.toFloat() > 1.5f
        val ratio = if (isWide) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(camWidth, camHeight),
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
            // Обмежуємо частоту кадрів самої камери (сталий кап 15 к/с), щоб ISP
            // не працював на 30 к/с дарма. Стала величина — без перебіндів.
            camera2Config.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                chooseFpsRange(15)
            )
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

                            if (snapshotRequest.compareAndSet(true, false)) {
                                saveSnapshot(nv21, image.width, image.height)
                            }

                            val out = ByteArrayOutputStream(nv21.size / 4)
                            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, out)
                            val jpeg = out.toByteArray()

                            mjpegServer?.updateFrame(jpeg)

                            totalProducedBytes += jpeg.size
                            sessionMb = totalProducedBytes.toDouble() / (1024.0 * 1024.0)
                            frameCount++
                            lastFrameSize = jpeg.size
                            framesInWindow++
                            val windowElapsed = currentTime - lastFpsWindowTime
                            if (windowElapsed >= 1000L) {
                                currentFps = framesInWindow * 1000.0 / windowElapsed
                                framesInWindow = 0
                                lastFpsWindowTime = currentTime
                            }

                            if (currentTime - lastStatsBroadcastTime >= STATS_BROADCAST_INTERVAL_MS) {
                                lastStatsBroadcastTime = currentTime
                                computeStreamKbps()
                                sendStatsBroadcast()
                            }

                            if (currentTime - lastFrameLogTime >= FRAME_LOG_INTERVAL_MS) {
                                Log.i(
                                    TAG,
                                    "Frames=$frameCount, FPS=${String.format("%.1f", currentFps)}, " +
                                        "lastFrame=${lastFrameSize / 1024}KB, quality=$jpegQuality, " +
                                        "zoom=$currentZoom, res=${image.width}x${image.height}, thermal=$thermalStatus"
                                )
                                lastFrameLogTime = currentTime
                            }

                            lastFrameTime = currentTime
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

    private fun sendStatsBroadcast() {
        val intent = Intent(STATS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_FRAME_SIZE, lastFrameSize)
            putExtra(EXTRA_SESSION_MB, sessionMb)
            putExtra(EXTRA_STREAM_FPS, currentFps)
            putExtra(EXTRA_STREAM_KBPS, streamKbps)
            putExtra(EXTRA_CLIENTS, mjpegServer?.getClientCount() ?: 0)
            putExtra(EXTRA_THERMAL, thermalStatus)
        }
        sendBroadcast(intent)
    }

    /**
     * Реальна швидкість віддачі стріму глядачам (Кбіт/с) за останній інтервал.
     */
    private fun computeStreamKbps(): Double {
        val server = mjpegServer ?: return streamKbps
        val bytes = server.getStreamBytes()
        val now = System.currentTimeMillis()
        if (lastStreamBytesTime == 0L) {
            lastStreamBytes = bytes
            lastStreamBytesTime = now
            return 0.0
        }
        val dt = now - lastStreamBytesTime
        if (dt >= 400) {
            // deltaBytes * 8 / dt[ms] = Кбіт/с
            streamKbps = (bytes - lastStreamBytes) * 8.0 / dt
            lastStreamBytes = bytes
            lastStreamBytesTime = now
        }
        return streamKbps
    }

    /**
     * Зберігає один кадр максимальної якості (JPEG q95) у Pictures застосунку.
     * Виконується в cameraExecutor, тобто синхронно з обробкою кадрів.
     */
    private fun saveSnapshot(nv21: ByteArray, width: Int, height: Int) {
        try {
            val out = ByteArrayOutputStream(nv21.size / 2)
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), SNAPSHOT_QUALITY, out)
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            val file = File(dir, "snap_${System.currentTimeMillis()}.jpg")
            file.writeBytes(out.toByteArray())
            Log.i(TAG, "Snapshot saved: ${file.absolutePath}")
            sendBroadcast(
                Intent(SNAPSHOT_SAVED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_SNAPSHOT_PATH, file.absolutePath)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot failed", e)
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
            val surfaceProvider = if (previewEnabled) previewSurfaceProvider else null
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
        when {
            // Найшвидший шлях: щільний Y без padding.
            yPixelStride == 1 && yRowStride == width -> {
                yBuffer.get(nv21, 0, ySize)
                pos = ySize
            }
            // Копіюємо Y по рядках цілим блоком (padding між рядками пропускаємо).
            yPixelStride == 1 -> {
                for (row in 0 until height) {
                    val srcPos = row * yRowStride
                    if (srcPos >= yBuffer.limit()) break
                    yBuffer.position(srcPos)
                    val n = minOf(width, yBuffer.limit() - srcPos)
                    yBuffer.get(nv21, pos, n)
                    pos += width
                }
            }
            else -> {
                for (row in 0 until height) {
                    var idx = row * yRowStride
                    for (col in 0 until width) {
                        nv21[pos++] = if (idx < yBuffer.limit()) yBuffer.get(idx) else 0
                        idx += yPixelStride
                    }
                }
            }
        }

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val chromaWidth = width / 2

        // Тимчасовий буфер під цілі рядки V і U.
        val needTmp = chromaWidth * (vPixelStride + uPixelStride)
        var tmp = uvRowBuf
        if (tmp == null || tmp.size < needTmp) {
            tmp = ByteArray(needTmp)
            uvRowBuf = tmp
        }

        if (uPixelStride == 1 && vPixelStride == 1) {
            for (row in 0 until height / 2) {
                uBuffer.position(row * uRowStride)
                vBuffer.position(row * vRowStride)
                for (col in 0 until chromaWidth) {
                    nv21[pos++] = vBuffer.get()
                    nv21[pos++] = uBuffer.get()
                }
            }
        } else {
            for (row in 0 until height / 2) {
                val vBase = row * vRowStride
                val vEnd = minOf(vBase + chromaWidth * vPixelStride, vBuffer.limit())
                val vLen = (vEnd - vBase).coerceAtLeast(0)
                if (vLen > 0) {
                    vBuffer.position(vBase)
                    vBuffer.get(tmp, 0, vLen)
                }
                val uBase = row * uRowStride
                val uEnd = minOf(uBase + chromaWidth * uPixelStride, uBuffer.limit())
                val uLen = (uEnd - uBase).coerceAtLeast(0)
                if (uLen > 0) {
                    uBuffer.position(uBase)
                    uBuffer.get(tmp, needTmp - chromaWidth * uPixelStride, uLen)
                }
                val uOff = needTmp - chromaWidth * uPixelStride
                for (col in 0 until chromaWidth) {
                    nv21[pos++] = tmp[col * vPixelStride]
                    nv21[pos++] = tmp[uOff + col * uPixelStride]
                }
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
            tailscaleUp = NetworkUtils.isTailscaleUp(),
            addresses = NetworkUtils.listIpv4Addresses(),
            sessionMb = sessionMb,
            streamKbps = computeStreamKbps(),
            clients = mjpegServer?.getClientCount() ?: 0,
            thermalStatus = thermalStatus,
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
        stopEverything()
        unregisterThermalListener()
        cameraExecutor.shutdown()
        cachedNv21 = null
        super.onDestroy()
    }

    private fun stopEverything() {
        cameraStarted = false
        serviceRunning = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        mjpegServer?.stop()
        mjpegServer = null
        releaseWakeLock()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            powerManager = pm
            thermalStatus = pm.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                thermalStatus = status
                applyThermalState()
            }
            thermalListener = listener
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register thermal listener", e)
        }
    }

    private fun unregisterThermalListener() {
        val pm = powerManager ?: return
        val listener = thermalListener ?: return
        try { pm.removeThermalStatusListener(listener) } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        // Лише CPU wake-lock: інтерфейс роздачі (hotspot/SoftAp) система тримає сама,
        // а зайвий WifiLock лише нагрівав би радіо.
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DRN:TelemetryWakeLock"
            )
            wakeLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
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
            .setContentTitle("NOY_DRN Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "NOY_DRN Bridge", NotificationManager.IMPORTANCE_LOW))
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
    val tailscaleUp: Boolean,
    val addresses: List<String>,
    val sessionMb: Double,
    val streamKbps: Double,
    val clients: Int,
    val thermalStatus: Int,
    val batteryTempC: Double,
    val memoryUsageMb: Long
)
