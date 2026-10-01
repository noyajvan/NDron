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
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var videoStats: TextView
    private lateinit var tailscaleStatus: TextView
    private lateinit var statusText: TextView
    private lateinit var ecoOverlay: View
    private lateinit var viewFinder: PreviewView

    private var isEco = false
    private var serviceRunning = true

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
                if (!serviceRunning) {
                    serviceRunning = true
                    findViewById<Button>(R.id.stopButton)?.text = "Зупинити сервіс"
                }
                val size = intent.getIntExtra(TelemetryBridgeService.EXTRA_FRAME_SIZE, 0)
                val kb = size / 1024
                val fps = intent.getDoubleExtra(TelemetryBridgeService.EXTRA_STREAM_FPS, 0.0)
                val kbps = intent.getDoubleExtra(TelemetryBridgeService.EXTRA_STREAM_KBPS, 0.0)
                val clients = intent.getIntExtra(TelemetryBridgeService.EXTRA_CLIENTS, 0)
                val thermal = intent.getIntExtra(TelemetryBridgeService.EXTRA_THERMAL, 0)
                videoStats.text = String.format(
                    "Frame: %d KB | FPS: %.1f | Speed: %.2f Mbps | Viewers: %d | Thermal: %s",
                    kb, fps, kbps / 1000.0, clients, thermalLabel(thermal)
                )
            }
        }
    }

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelemetryBridgeService.SNAPSHOT_SAVED) {
                val path = intent.getStringExtra(TelemetryBridgeService.EXTRA_SNAPSHOT_PATH) ?: return
                Toast.makeText(this@MainActivity, "Снапшот: $path", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Екран НЕ утримуємо примусово: для бортового режиму це зайвий нагрів.
        setContentView(R.layout.activity_main)

        videoStats = findViewById(R.id.videoStats)
        tailscaleStatus = findViewById(R.id.tailscaleStatus)
        statusText = findViewById(R.id.status)
        ecoOverlay = findViewById(R.id.ecoOverlay)
        viewFinder = findViewById(R.id.viewFinder)

        // Локальне прев'ю камери увімкнене за замовчуванням.
        TelemetryBridgeService.previewSurfaceProvider = viewFinder.surfaceProvider

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.snapshotButton).setOnClickListener {
            startServiceAction(TelemetryBridgeService.ACTION_SNAPSHOT)
        }
        findViewById<Button>(R.id.ecoButton).setOnClickListener {
            enterEco()
        }
        findViewById<Button>(R.id.stopButton).apply {
            text = "Зупинити сервіс"
            setOnClickListener { toggleService() }
        }
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

        refreshNetworkStatusAsync()

        ContextCompat.registerReceiver(
            this,
            statsReceiver,
            IntentFilter(TelemetryBridgeService.STATS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            snapshotReceiver,
            IntentFilter(TelemetryBridgeService.SNAPSHOT_SAVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (arePermissionsGranted()) {
            startBridgeService()
        } else {
            requestRequiredPermissions()
        }
    }

    /**
     * Керування еко-режимом з апаратних клавіш:
     *   Гучність «вниз» — увійти, гучність «вгору» — вийти.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (!isEco) {
                    enterEco()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> if (isEco) {
                    exitEco()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleService() {
        val btn = findViewById<Button>(R.id.stopButton)
        if (serviceRunning) {
            stopService(Intent(this, TelemetryBridgeService::class.java))
            serviceRunning = false
            btn.text = "Продовжити сервіс"
        } else {
            startBridgeService()
            serviceRunning = true
            btn.text = "Зупинити сервіс"
        }
    }

    private fun enterEco() {
        if (isEco) return
        isEco = true
        // В еко-режимі не смикаємо мережу/UI — менше нагріву.
        handler.removeCallbacks(networkUpdateRunnable)
        // Тримаємо екран «увімкненим», але повністю чорним на мінімальній яскравості:
        // так клавіші гучності далі доходять до застосунку. На OLED чорне ≈ вимкнено.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 0f
        window.attributes = lp
        ecoOverlay.visibility = View.VISIBLE
        // Прибираємо локальне прев'ю (відв'язуємо від камери) — менше нагріву.
        viewFinder.visibility = View.GONE
        sendPreviewState(false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        sendEco(true)
    }

    private fun exitEco() {
        if (!isEco) return
        isEco = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        ecoOverlay.visibility = View.GONE
        // Повертаємо локальне прев'ю.
        viewFinder.visibility = View.VISIBLE
        TelemetryBridgeService.previewSurfaceProvider = viewFinder.surfaceProvider
        sendPreviewState(true)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        handler.removeCallbacks(networkUpdateRunnable)
        handler.post(networkUpdateRunnable)
        sendEco(false)
    }

    private fun sendEco(on: Boolean) {
        startServiceAction(TelemetryBridgeService.ACTION_SET_ECO) {
            putExtra(TelemetryBridgeService.EXTRA_ECO, on)
        }
    }

    private fun sendPreviewState(on: Boolean) {
        startServiceAction(TelemetryBridgeService.ACTION_SET_PREVIEW) {
            putExtra(TelemetryBridgeService.EXTRA_PREVIEW, on)
        }
    }

    private fun thermalLabel(status: Int): String = when (status) {
        0 -> "OK"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "?"
    }

    private fun startServiceAction(action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, TelemetryBridgeService::class.java).apply {
            this.action = action
            extras?.invoke(this)
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
                    append("🌐 Tailscale: http://$tsIp:8888/\n")
                } else {
                    append("🌐 Tailscale: <немає>\n")
                }
                if (localIp != null) {
                    append("📡 Hotspot / Wi-Fi: http://$localIp:8888/\n")
                }
                append("🎬 Stream: http://$primaryIp:8888/stream\n")
                append("📊 Health: http://$primaryIp:8888/health")
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
        // Прев'ю вмикаємо лише коли активність видима (і не в еко).
        if (!isEco) {
            TelemetryBridgeService.previewSurfaceProvider = viewFinder.surfaceProvider
            sendPreviewState(true)
        }
        handler.removeCallbacks(networkUpdateRunnable)
        handler.post(networkUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Екран/активність невидимі — вимикаємо прев'ю: менше нагріву від камери/GPU.
        TelemetryBridgeService.previewSurfaceProvider = null
        sendPreviewState(false)
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
        try {
            unregisterReceiver(snapshotReceiver)
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
