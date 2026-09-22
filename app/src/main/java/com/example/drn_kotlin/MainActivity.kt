package com.example.drn_kotlin

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var zoomLabel: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var videoStats: TextView
    private lateinit var usbStatus: TextView
    private lateinit var tailscaleStatus: TextView

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelemetryBridgeService.STATS_UPDATE) {
                val size = intent.getIntExtra(TelemetryBridgeService.EXTRA_FRAME_SIZE, 0)
                val kb = size / 1024
                val kbps = (kb * 5 * 8) // Bitrate at 5 FPS
                videoStats.text = "MJPEG TX: $kbps kbps | Frame: $kb KB"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        zoomLabel = findViewById(R.id.zoomLabel)
        qualityLabel = findViewById(R.id.qualityLabel)
        videoStats = findViewById(R.id.videoStats)
        usbStatus = findViewById(R.id.usbStatus)
        tailscaleStatus = findViewById(R.id.tailscaleStatus)

        // Показуємо реальну Tailscale IP телефону та URL для перегляду
        updateStatusText()
        updateTailscaleStatus()

        // Кнопка "Стоп" — зупиняє сервіс
        findViewById<android.widget.Button>(R.id.stopButton).setOnClickListener {
            val intent = Intent(this, TelemetryBridgeService::class.java)
                .setAction(TelemetryBridgeService.ACTION_STOP)
            startService(intent)
            usbStatus.text = "Сервіс зупинено"
            usbStatus.setTextColor(0xFFFF9800.toInt())
        }

        // Кнопка "Відкрити в браузері" — відкриває MJPEG-стрім у браузері на телефоні
        findViewById<android.widget.Button>(R.id.openBrowserButton).setOnClickListener {
            val ip = getTailscaleIp()
            val url = "http://$ip:8888/"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                usbStatus.text = "Не вдалося відкрити браузер: ${e.message}"
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

        handleIntent(intent)
    }

    private fun updateStatusText() {
        val ip = getTailscaleIp()
        val statusText = findViewById<TextView>(R.id.status)
        statusText.text = "MJPEG: http://$ip:8888/stream\n" +
            "Перегляд: http://$ip:8888/\n" +
            "Health: http://$ip:8888/health\n" +
            "SDP (H.264/RTP): http://$ip:8888/sdp\n" +
            "Telemetry: UDP 14550 -> ${TelemetryBridgeService.DEFAULT_GCS_IP}\n" +
            "VLC: vlc http://$ip:8888/stream"
    }

    private fun updateTailscaleStatus() {
        val up = NetworkUtils.isTailscaleUp()
        val ip = NetworkUtils.findTailscaleIp() ?: "<немає>"
        if (up) {
            tailscaleStatus.text = "Tailscale: ПІДКЛЮЧЕНО ($ip)"
            tailscaleStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            tailscaleStatus.text = "Tailscale: НЕ ПІДКЛЮЧЕНО (fallback $ip)"
            tailscaleStatus.setTextColor(0xFFF44336.toInt())
        }
    }

    private fun setupSeekBars() {
        val zoomSeekBar = findViewById<SeekBar>(R.id.zoomSeekBar)
        val qualitySeekBar = findViewById<SeekBar>(R.id.qualitySeekBar)

        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = 1.0f + (progress / 10.0f)
                zoomLabel.text = "Zoom: ${String.format("%.1f", zoom)}x"
                if (fromUser) sendIntent(TelemetryBridgeService.ACTION_SET_ZOOM, TelemetryBridgeService.EXTRA_ZOOM, zoom)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityLabel.text = "Video Quality: $progress%"
                if (fromUser) sendIntent(TelemetryBridgeService.ACTION_SET_QUALITY, TelemetryBridgeService.EXTRA_QUALITY, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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
        // Оновлюємо IP при поверненні в застосунок (Tailscale міг піднятися пізніше)
        updateStatusText()
        updateTailscaleStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statsReceiver)
        } catch (e: IllegalArgumentException) {
            // Ресивер не був зареєстрований — ігноруємо
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            usbStatus.text = "USB FC: Connected!"
            usbStatus.setTextColor(0xFF4CAF50.toInt())
            startBridgeService()
        }
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
                } catch (e: Exception) {
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

    /**
     * Повертає IP-адресу інтерфейсу Tailscale (100.x.x.x), якщо він піднятий.
     * Інакше — першу non-loopback IPv4 адресу.
     */
    private fun getTailscaleIp(): String {
        return NetworkUtils.findTailscaleIp() ?: "<IP_ТЕЛЕФОНА>"
    }
}
