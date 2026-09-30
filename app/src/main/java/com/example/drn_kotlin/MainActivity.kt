package com.example.drn_kotlin

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var zoomLabel: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var resLabel: TextView
    private lateinit var fpsLabel: TextView
    private lateinit var videoStats: TextView
    private lateinit var tailscaleStatus: TextView
    private lateinit var statusText: TextView
    private lateinit var viewFinder: PreviewView
    private lateinit var dimScreenButton: Button

    private var isDimmed = false

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val networkUpdateRunnable = object : Runnable {
        override fun run() {
            refreshNetworkStatusAsync()
            handler.postDelayed(this, 3000L)
        }
    }

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelemetryBridgeService.STATS_UPDATE) {
                val size = intent.getIntExtra(TelemetryBridgeService.EXTRA_FRAME_SIZE, 0)
                val kb = size / 1024
                val trafficMbMin = intent.getDoubleExtra(TelemetryBridgeService.EXTRA_TRAFFIC_MB_MIN, 0.0)
                val sessionMb = intent.getDoubleExtra(TelemetryBridgeService.EXTRA_SESSION_MB, 0.0)
                val oraclePercent = intent.getDoubleExtra(TelemetryBridgeService.EXTRA_ORACLE_PERCENT, 0.0)
                
                videoStats.text = String.format("Frame: %d KB | TX: %.1f MB/min (Tot: %.1f MB)\nOracle Free: %.4f%% of 10TB", 
                    kb, trafficMbMin, sessionMb, oraclePercent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Утримуємо екран активним, щоб телефон не блокувався під час трансляції
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        zoomLabel = findViewById(R.id.zoomLabel)
        qualityLabel = findViewById(R.id.qualityLabel)
        resLabel = findViewById(R.id.resLabel)
        fpsLabel = findViewById<TextView>(R.id.fpsLabel).apply {
            text = "Частота кадрів: 5 FPS"
        }
        videoStats = findViewById(R.id.videoStats)
        tailscaleStatus = findViewById(R.id.tailscaleStatus)
        statusText = findViewById(R.id.status)
        viewFinder = findViewById(R.id.viewFinder)
        dimScreenButton = findViewById(R.id.dimScreenButton)

        // Передаємо SurfaceProvider у сервіс для локального відображення камери на екрані
        TelemetryBridgeService.previewSurfaceProvider = viewFinder.surfaceProvider

        refreshNetworkStatusAsync()
        setupResolutionAndFpsButtons()

        // Кнопка економії батареї — приглушує екран до 1% для бортового використання
        dimScreenButton.setOnClickListener {
            toggleDimScreen()
        }

        // Кнопка "Стоп" — зупиняє сервіс
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            val intent = Intent(this, TelemetryBridgeService::class.java)
                .setAction(TelemetryBridgeService.ACTION_STOP)
            startService(intent)
        }

        // Кнопка "Відкрити в браузері" — відкриває MJPEG-стрім у браузері на телефоні
        findViewById<Button>(R.id.openBrowserButton).setOnClickListener {
            bgExecutor.execute {
                val tsIp = NetworkUtils.findTailscaleIp()
                val localIp = NetworkUtils.findLocalOrHotspotIp()
                val ip = tsIp ?: localIp ?: "localhost"
                val url = "http://$ip:8888/"
                runOnUiThread {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                }
            }
        }

        setupSeekBars()
        ContextCompat.registerReceiver(
            this,
            statsReceiver,
            IntentFilter(TelemetryBridgeService.STATS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (arePermissionsGranted()) {
            startBridgeService()
        } else {
            requestRequiredPermissions()
        }
    }

    private fun setupResolutionAndFpsButtons() {
        // Resolution
        findViewById<Button>(R.id.res640Btn).setOnClickListener {
            resLabel.text = "Роздільна здатність: 640x480"
            sendResolutionIntent(640, 480)
        }
        findViewById<Button>(R.id.res720Btn).setOnClickListener {
            resLabel.text = "Роздільна здатність: 1280x720 (HD)"
            sendResolutionIntent(1280, 720)
        }
        findViewById<Button>(R.id.res1080Btn).setOnClickListener {
            resLabel.text = "Роздільна здатність: 1920x1080 (FHD)"
            sendResolutionIntent(1920, 1080)
        }

        // FPS
        findViewById<Button>(R.id.fps5Btn).setOnClickListener {
            fpsLabel.text = "Частота кадрів: 5 FPS"
            sendFpsIntent(5)
        }
        findViewById<Button>(R.id.fps10Btn).setOnClickListener {
            fpsLabel.text = "Частота кадрів: 10 FPS"
            sendFpsIntent(10)
        }
        findViewById<Button>(R.id.fps15Btn).setOnClickListener {
            fpsLabel.text = "Частота кадрів: 15 FPS"
            sendFpsIntent(15)
        }
        findViewById<Button>(R.id.fps30Btn).setOnClickListener {
            fpsLabel.text = "Частота кадрів: 30 FPS"
            sendFpsIntent(30)
        }
    }

    private fun sendResolutionIntent(width: Int, height: Int) {
        val intent = Intent(this, TelemetryBridgeService::class.java).apply {
            action = TelemetryBridgeService.ACTION_SET_RESOLUTION
            putExtra(TelemetryBridgeService.EXTRA_WIDTH, width)
            putExtra(TelemetryBridgeService.EXTRA_HEIGHT, height)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendFpsIntent(fps: Int) {
        val intent = Intent(this, TelemetryBridgeService::class.java).apply {
            action = TelemetryBridgeService.ACTION_SET_FPS
            putExtra(TelemetryBridgeService.EXTRA_FPS, fps)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refreshNetworkStatusAsync() {
        bgExecutor.execute {
            val tsIp = NetworkUtils.findTailscaleIp()
            val localIp = NetworkUtils.findLocalOrHotspotIp()
            val primaryIp = tsIp ?: localIp ?: "localhost"

            val statusContent = buildString {
                if (tsIp != null) {
                    append("🌐 Tailscale VPN: http://$tsIp:8888/\n")
                } else {
                    append("🌐 Tailscale VPN: <немає>\n")
                }
                if (localIp != null) {
                    append("📡 Hotspot / Wi-Fi: http://$localIp:8888/\n")
                }
                append("🎬 MJPEG Stream: http://$primaryIp:8888/stream\n")
                append("📊 Health Snapshot: http://$primaryIp:8888/health\n")
                append("📡 Telemetry UDP 14550 -> ${TelemetryBridgeService.DEFAULT_GCS_IP}")
            }

            val tailscaleText: String
            val tailscaleColor: Int
            if (tsIp != null) {
                tailscaleText = "Tailscale: ПІДКЛЮЧЕНО ($tsIp)"
                tailscaleColor = 0xFF4CAF50.toInt()
            } else if (localIp != null) {
                tailscaleText = "Hotspot / Wi-Fi: АКТИВНО ($localIp)"
                tailscaleColor = 0xFF2196F3.toInt()
            } else {
                tailscaleText = "Мережа: НЕМАЄ АКТИВНИХ З'ЄДНАНЬ"
                tailscaleColor = 0xFFF44336.toInt()
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    statusText.text = statusContent
                    tailscaleStatus.text = tailscaleText
                    tailscaleStatus.setTextColor(tailscaleColor)
                }
            }
        }
    }

    private fun toggleDimScreen() {
        isDimmed = !isDimmed
        val lp = window.attributes
        if (isDimmed) {
            lp.screenBrightness = 0.01f // 1% яскравість (мінімум)
            dimScreenButton.text = "Увімкнути екран (100%)"
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            dimScreenButton.text = "Економія батареї (Згасити екран)"
        }
        window.attributes = lp
    }

    private fun setupSeekBars() {
        val zoomSeekBar = findViewById<SeekBar>(R.id.zoomSeekBar)
        val qualitySeekBar = findViewById<SeekBar>(R.id.qualitySeekBar)

        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = 1.0f + (progress / 10.0f)
                zoomLabel.text = "Zoom: ${String.format("%.1f", zoom)}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val zoom = 1.0f + (progress / 10.0f)
                sendIntent(TelemetryBridgeService.ACTION_SET_ZOOM, TelemetryBridgeService.EXTRA_ZOOM, zoom)
            }
        })

        qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityLabel.text = "Якість відео (JPEG): $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 40
                sendIntent(TelemetryBridgeService.ACTION_SET_QUALITY, TelemetryBridgeService.EXTRA_QUALITY, progress)
            }
        })
    }

    private fun sendIntent(action: String, extraKey: String, value: Any) {
        val intent = Intent(this, TelemetryBridgeService::class.java).apply {
            this.action = action
            if (value is Float) putExtra(extraKey, value)
            if (value is Int) putExtra(extraKey, value)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun arePermissionsGranted(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return cameraGranted && notificationGranted
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && arePermissionsGranted()) {
            startBridgeService()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(networkUpdateRunnable)
        handler.post(networkUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(networkUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        TelemetryBridgeService.previewSurfaceProvider = null
        handler.removeCallbacks(networkUpdateRunnable)
        bgExecutor.shutdown()
        try {
            unregisterReceiver(statsReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        }
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }

    private fun startBridgeService() {
        val intent = Intent(this, TelemetryBridgeService::class.java)
            .setAction(TelemetryBridgeService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }
}
