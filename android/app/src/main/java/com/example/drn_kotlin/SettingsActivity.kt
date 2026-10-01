package com.example.drn_kotlin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var zoomLabel: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var resLabel: TextView
    private lateinit var fpsLabel: TextView

    private val bgExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        zoomLabel = findViewById(R.id.zoomLabel)
        qualityLabel = findViewById(R.id.qualityLabel)
        resLabel = findViewById(R.id.resLabel)
        fpsLabel = findViewById(R.id.fpsLabel)

        val saved = Prefs.load(this)
        resLabel.text = "Роздільна здатність: ${saved.width}x${saved.height}"
        fpsLabel.text = "Частота кадрів: ${saved.fps} FPS"
        qualityLabel.text = "Якість кадру (MJPEG): ${saved.quality}%"
        zoomLabel.text = "Zoom: ${String.format("%.1f", saved.zoom)}x"

        findViewById<SeekBar>(R.id.zoomSeekBar).progress = ((saved.zoom - 1.0f) * 10f).toInt()
        findViewById<SeekBar>(R.id.qualitySeekBar).progress = saved.quality

        setupResolutionAndFpsButtons()
        setupSeekBars()

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        bgExecutor.shutdown()
    }

    private fun setupResolutionAndFpsButtons() {
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

        findViewById<Button>(R.id.fps5Btn).setOnClickListener {
            fpsLabel.text = "Частота кадрів: 5 FPS"
            sendFpsIntent(5)
        }
        findViewById<Button>(R.id.fps8Btn).setOnClickListener {
            fpsLabel.text = "Частота кадрів: 8 FPS"
            sendFpsIntent(8)
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
                sendAction(TelemetryBridgeService.ACTION_SET_ZOOM) {
                    putExtra(TelemetryBridgeService.EXTRA_ZOOM, zoom)
                }
            }
        })

        qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityLabel.text = "Якість кадру (MJPEG): $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 20
                sendAction(TelemetryBridgeService.ACTION_SET_QUALITY) {
                    putExtra(TelemetryBridgeService.EXTRA_QUALITY, progress)
                }
            }
        })
    }

    private fun sendResolutionIntent(width: Int, height: Int) {
        sendAction(TelemetryBridgeService.ACTION_SET_RESOLUTION) {
            putExtra(TelemetryBridgeService.EXTRA_WIDTH, width)
            putExtra(TelemetryBridgeService.EXTRA_HEIGHT, height)
        }
    }

    private fun sendFpsIntent(fps: Int) {
        sendAction(TelemetryBridgeService.ACTION_SET_FPS) {
            putExtra(TelemetryBridgeService.EXTRA_FPS, fps)
        }
    }

    private fun sendAction(action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, TelemetryBridgeService::class.java).apply {
            this.action = action
            extras?.invoke(this)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
