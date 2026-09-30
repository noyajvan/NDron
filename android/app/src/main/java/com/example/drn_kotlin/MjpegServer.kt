package com.example.drn_kotlin

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(port: Int) : NanoHTTPD(port) {
    private val currentFrame = AtomicReference<ByteArray?>(null)
    private val frameQueue = ArrayBlockingQueue<ByteArray>(2)
    private val TAG = "MjpegServer"

    /**
     * Провайдер статистики від сервісу. Встановлюється ззовні.
     */
    var healthProvider: (() -> HealthSnapshot)? = null

    /**
     * Провайдер SDP для H.264/RTP-стріму. Встановлюється ззовні.
     */
    var sdpProvider: (() -> String)? = null

    /**
     * Обробник віддалених команд керування з веб-інтерфейсу (Zoom, Quality, FPS, Resolution).
     */
    var controlHandler: ((zoom: Float?, quality: Int?, fps: Int?, width: Int?, height: Int?) -> Unit)? = null

    companion object {
        const val VIDEO_PORT = TelemetryBridgeService.VIDEO_UDP_PORT
    }

    /**
     * Повністю lock-free оновлення кадру через ArrayBlockingQueue.
     * Усуває ривки та затримки.
     */
    fun updateFrame(jpegData: ByteArray) {
        currentFrame.set(jpegData)
        if (!frameQueue.offer(jpegData)) {
            frameQueue.poll() // скидаємо найстаріший кадр якщо черга заповнена
            frameQueue.offer(jpegData)
        }
    }

    fun isRunning(): Boolean = wasStarted()

    override fun serve(session: IHTTPSession): Response {
        Log.i(TAG, "New request from ${session.remoteIpAddress}: ${session.uri}")

        // Remote Control Endpoint for GCS Browser Interface
        if (session.uri == "/control") {
            val params = session.parameters
            val zoom = params["zoom"]?.firstOrNull()?.toFloatOrNull()
            val quality = params["quality"]?.firstOrNull()?.toIntOrNull()
            val fps = params["fps"]?.firstOrNull()?.toIntOrNull()
            
            var width: Int? = params["width"]?.firstOrNull()?.toIntOrNull()
            var height: Int? = params["height"]?.firstOrNull()?.toIntOrNull()
            
            val res = params["res"]?.firstOrNull() ?: params["resolution"]?.firstOrNull()
            if (res != null && res.contains("x")) {
                val parts = res.split("x")
                if (parts.size == 2) {
                    width = parts[0].toIntOrNull()
                    height = parts[1].toIntOrNull()
                }
            }

            controlHandler?.invoke(zoom, quality, fps, width, height)
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
        }

        if (session.uri == "/sdp") {
            val sdp = sdpProvider?.invoke()
            if (sdp.isNullOrEmpty()) {
                val body = "H.264/RTP not started yet.\n" +
                    "Use MJPEG stream: http://<IP>:8888/stream\n"
                return newFixedLengthResponse(Response.Status.OK, "text/plain", body)
            }
            return newFixedLengthResponse(Response.Status.OK, "application/sdp", sdp)
        }

        if (session.uri == "/" || session.uri == "/index.html") {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>DRN Ground Control Station — Live Stream & Remote Control</title>
                    <style>
                        * { box-sizing: border-box; }
                        body { margin:0; background:#0f111a; color:#e1e4ed; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; display: flex; flex-direction: column; min-height: 100vh; }
                        header { padding:14px 24px; background:#181b29; font-size:18px; font-weight:700; display:flex; justify-content:space-between; align-items:center; border-bottom: 1px solid #2a2e42; }
                        .status-badge { background:#2e7d32; color:#fff; padding:5px 12px; border-radius:14px; font-size:12px; font-weight:bold; letter-spacing:0.5px; }
                        .status-badge.reconnecting { background:#ef6c00; }
                        
                        main { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:flex-start; padding:16px; gap:16px; max-width:1200px; width:100%; margin:0 auto; }
                        
                        .video-container { width:100%; background:#000; border-radius:12px; overflow:hidden; box-shadow:0 8px 32px rgba(0,0,0,0.6); position:relative; min-height:360px; display:flex; align-items:center; justify-content:center; border: 1px solid #2a2e42; }
                        img { display:block; width:100%; height:auto; max-height:75vh; object-fit:contain; }
                        
                        .controls-panel { width:100%; background:#181b29; border-radius:12px; padding:20px; border: 1px solid #2a2e42; display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap:20px; }
                        
                        .control-group { display:flex; flex-direction:column; gap:8px; background:#0f111a; padding:14px; border-radius:8px; border:1px solid #23273a; }
                        .control-label { font-size:13px; font-weight:600; color:#8e95b0; display:flex; justify-content:space-between; align-items:center; }
                        .control-value { color:#64b5f6; font-size:14px; font-weight:bold; }
                        
                        input[type=range] { width:100%; accent-color:#2196f3; cursor:pointer; height:6px; background:#23273a; border-radius:3px; }
                        
                        .btn-group { display:flex; gap:8px; flex-wrap:wrap; }
                        .btn { flex:1; min-width:60px; padding:8px 12px; background:#23273a; color:#e1e4ed; border:1px solid #343a52; border-radius:6px; cursor:pointer; font-weight:600; font-size:12px; text-align:center; transition:all 0.15s ease; }
                        .btn:hover { background:#343a52; border-color:#64b5f6; }
                        .btn.active { background:#1976d2; color:#fff; border-color:#2196f3; box-shadow:0 0 10px rgba(33,150,243,0.4); }
                        
                        .stats-bar { width:100%; background:#181b29; border-radius:8px; padding:12px 20px; border:1px solid #2a2e42; display:flex; justify-content:space-around; flex-wrap:wrap; gap:12px; font-size:13px; color:#8e95b0; }
                        .stat-item span { color:#4caf50; font-weight:bold; }
                        
                        footer { padding:14px 20px; background:#181b29; font-size:12px; color:#6c738f; text-align:center; border-top: 1px solid #2a2e42; }
                        a { color:#64b5f6; text-decoration:none; }
                        a:hover { text-decoration:underline; }
                    </style>
                </head>
                <body>
                    <header>
                        <span>🛸 DRN Ground Control Station</span>
                        <span id="badge" class="status-badge">LIVE MJPEG</span>
                    </header>
                    <main>
                        <div class="video-container">
                            <img id="streamImg" src="/latest.jpg" alt="DRN Camera Live Stream"/>
                        </div>
                        
                        <div class="stats-bar">
                            <div class="stat-item">FPS: <span id="statFps">0</span></div>
                            <div class="stat-item">Frame: <span id="statFrame">0 KB</span></div>
                            <div class="stat-item">Quality: <span id="statQuality">40%</span></div>
                            <div class="stat-item">Zoom: <span id="statZoom">1.0x</span></div>
                            <div class="stat-item">Oracle TX: <span id="statTraffic">0 MB/min</span></div>
                            <div class="stat-item">Oracle Free: <span id="statOracle">0%</span></div>
                            <div class="stat-item">Temp: <span id="statTemp">0°C</span></div>
                            <div class="stat-item">RAM: <span id="statRam">0 MB</span></div>
                            <div class="stat-item">Tailscale: <span id="statTs">OK</span></div>
                        </div>

                        <div class="controls-panel">
                            <!-- Zoom Control -->
                            <div class="control-group">
                                <div class="control-label">
                                    <span>ZOOM (ОПТИКА / ЦИФРА)</span>
                                    <span id="zoomVal" class="control-value">1.0x</span>
                                </div>
                                <input type="range" id="zoomRange" min="1.0" max="5.0" step="0.1" value="1.0" oninput="sendZoom(this.value)"/>
                                <div class="btn-group">
                                    <button class="btn" onclick="setZoom(1.0)">1.0x</button>
                                    <button class="btn" onclick="setZoom(2.0)">2.0x</button>
                                    <button class="btn" onclick="setZoom(3.0)">3.0x</button>
                                    <button class="btn" onclick="setZoom(5.0)">5.0x</button>
                                </div>
                            </div>

                            <!-- Resolution Control -->
                            <div class="control-group">
                                <div class="control-label">
                                    <span>РОЗДІЛЬНА ЗДАТНІСТЬ (RESOLUTION)</span>
                                </div>
                                <div class="btn-group">
                                    <button id="res640" class="btn active" onclick="setRes('640x480')">640x480</button>
                                    <button id="res720" class="btn" onclick="setRes('1280x720')">1280x720</button>
                                    <button id="res1080" class="btn" onclick="setRes('1920x1080')">1920x1080</button>
                                </div>
                            </div>

                            <!-- FPS Control -->
                            <div class="control-group">
                                <div class="control-label">
                                    <span>ЧАСТОТА КАДРІВ (FPS)</span>
                                </div>
                                <div class="btn-group">
                                    <button id="fps10" class="btn" onclick="setFps(10)">10 FPS</button>
                                    <button id="fps15" class="btn active" onclick="setFps(15)">15 FPS</button>
                                    <button id="fps24" class="btn" onclick="setFps(24)">24 FPS</button>
                                    <button id="fps30" class="btn" onclick="setFps(30)">30 FPS</button>
                                </div>
                            </div>

                            <!-- Quality Control -->
                            <div class="control-group">
                                <div class="control-label">
                                    <span>ЯКІСТЬ ДЖПЕГ (JPEG QUALITY)</span>
                                    <span id="qualityVal" class="control-value">40%</span>
                                </div>
                                <input type="range" id="qualityRange" min="10" max="100" step="5" value="40" oninput="sendQuality(this.value)"/>
                                <div class="btn-group">
                                    <button class="btn" onclick="setQuality(25)">Low (25%)</button>
                                    <button class="btn" onclick="setQuality(40)">Med (40%)</button>
                                    <button class="btn" onclick="setQuality(70)">High (70%)</button>
                                </div>
                            </div>
                        </div>
                    </main>
                    <footer>
                        Endpoints: <a href="/stream" target="_blank">/stream (MJPEG)</a> | <a href="/health" target="_blank">/health (Stats)</a> | <a href="/sdp" target="_blank">/sdp (H.264)</a>
                    </footer>
                    <script>
                        const img = document.getElementById('streamImg');
                        const badge = document.getElementById('badge');

                        // Fast polling of /latest.jpg for zero-lag real-time video (8 FPS)
                        setInterval(() => {
                            img.src = '/latest.jpg?t=' + Date.now();
                        }, 125);
                        
                        function sendControl(param, val) {
                            fetch('/control?' + param + '=' + encodeURIComponent(val))
                                .catch(e => console.error('Control error', e));
                        }

                        function setZoom(val) {
                            document.getElementById('zoomRange').value = val;
                            sendZoom(val);
                        }

                        function sendZoom(val) {
                            document.getElementById('zoomVal').textContent = parseFloat(val).toFixed(1) + 'x';
                            sendControl('zoom', val);
                        }

                        function setQuality(val) {
                            document.getElementById('qualityRange').value = val;
                            sendQuality(val);
                        }

                        function sendQuality(val) {
                            document.getElementById('qualityVal').textContent = val + '%';
                            sendControl('quality', val);
                        }

                        function setRes(val) {
                            document.querySelectorAll('#res640, #res720, #res1080').forEach(b => b.classList.remove('active'));
                            if (val === '640x480') document.getElementById('res640').classList.add('active');
                            if (val === '1280x720') document.getElementById('res720').classList.add('active');
                            if (val === '1920x1080') document.getElementById('res1080').classList.add('active');
                            sendControl('res', val);
                        }

                        function setFps(val) {
                            document.querySelectorAll('#fps10, #fps15, #fps24, #fps30').forEach(b => b.classList.remove('active'));
                            if (val === 10) document.getElementById('fps10').classList.add('active');
                            if (val === 15) document.getElementById('fps15').classList.add('active');
                            if (val === 24) document.getElementById('fps24').classList.add('active');
                            if (val === 30) document.getElementById('fps30').classList.add('active');
                            sendControl('fps', val);
                        }

                        // Sync stats from /health & stream watchdog
                        let lastFrameCount = -1;
                        let frozenCount = 0;
                        setInterval(() => {
                            fetch('/health')
                                .then(r => r.text())
                                .then(txt => {
                                    const lines = txt.split('\n');
                                    let currentFc = 0;
                                    lines.forEach(l => {
                                        const [k, v] = l.split('=');
                                        if (k === 'frameCount') currentFc = parseInt(v) || 0;
                                        if (k === 'fps') document.getElementById('statFps').textContent = parseFloat(v).toFixed(1);
                                        if (k === 'lastFrameBytes') document.getElementById('statFrame').textContent = Math.round(parseInt(v)/1024) + ' KB';
                                        if (k === 'jpegQuality') document.getElementById('statQuality').textContent = v + '%';
                                        if (k === 'zoom') document.getElementById('statZoom').textContent = parseFloat(v).toFixed(1) + 'x';
                                        if (k === 'trafficMbPerMin') document.getElementById('statTraffic').textContent = parseFloat(v).toFixed(1) + ' MB/min';
                                        if (k === 'oracleLimitPercent') document.getElementById('statOracle').textContent = parseFloat(v).toFixed(4) + '%';
                                        if (k === 'batteryTempC') document.getElementById('statTemp').textContent = parseFloat(v).toFixed(1) + '°C';
                                        if (k === 'memoryUsageMb') document.getElementById('statRam').textContent = v + ' MB';
                                        if (k === 'tailscaleUp') document.getElementById('statTs').textContent = (v === 'true' ? 'CONNECTED' : 'DISCONNECTED');
                                    });

                                    if (currentFc === lastFrameCount) {
                                        frozenCount++;
                                        if (frozenCount >= 2) {
                                            badge.textContent = 'RECONNECTING...';
                                            badge.className = 'status-badge reconnecting';
                                            img.src = '/stream?t=' + Date.now();
                                            frozenCount = 0;
                                        }
                                    } else {
                                        lastFrameCount = currentFc;
                                        frozenCount = 0;
                                        badge.textContent = 'LIVE MJPEG';
                                        badge.className = 'status-badge';
                                    }
                                })
                                .catch(() => {});
                        }, 2000);

                        img.onerror = () => {
                            badge.textContent = 'RECONNECTING...';
                            badge.className = 'status-badge reconnecting';
                            setTimeout(() => {
                                img.src = '/stream?t=' + Date.now();
                            }, 2000);
                        };

                        img.onload = () => {
                            badge.textContent = 'LIVE MJPEG';
                            badge.className = 'status-badge';
                        };
                    </script>
                </body>
                </html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        if (session.uri == "/health") {
            val frame = currentFrame.get()
            val snapshot = healthProvider?.invoke()
            val body = buildString {
                append("ok\n")
                append("frameBytes=${frame?.size ?: 0}\n")
                if (snapshot != null) {
                    append("uptimeMs=${snapshot.uptimeMs}\n")
                    append("frameCount=${snapshot.frameCount}\n")
                    append("fps=${String.format("%.1f", snapshot.fps)}\n")
                    append("lastFrameBytes=${snapshot.lastFrameSize}\n")
                    append("jpegQuality=${snapshot.jpegQuality}\n")
                    append("zoom=${snapshot.zoom}\n")
                    append("targetWidth=${snapshot.targetWidth}\n")
                    append("targetHeight=${snapshot.targetHeight}\n")
                    append("targetFps=${snapshot.targetFps}\n")
                    append("cameraStarted=${snapshot.cameraStarted}\n")
                    append("h264Running=${snapshot.h264Running}\n")
                    append("tailscaleUp=${snapshot.tailscaleUp}\n")
                    append("addresses=${snapshot.addresses.joinToString(",")}\n")
                    append("trafficMbPerMin=${snapshot.trafficMbPerMin}\n")
                    append("sessionMb=${snapshot.sessionMb}\n")
                    append("oracleLimitPercent=${snapshot.oracleLimitPercent}\n")
                    append("batteryTempC=${snapshot.batteryTempC}\n")
                    append("memoryUsageMb=${snapshot.memoryUsageMb}\n")
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain", body)
        }

        if (session.uri == "/latest.jpg" || session.uri.startsWith("/latest.jpg")) {
            val frame = currentFrame.get()
            if (frame != null) {
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(frame),
                    frame.size.toLong()
                )
                response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                return response
            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Frame not ready")
            }
        }

        if (session.uri == "/stream") {
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame",
                MjpegStream(),
                -1L
            )
            response.addHeader("Cache-Control", "no-cache, private")
            response.addHeader("Pragma", "no-cache")
            return response
        }
        return newFixedLengthResponse("Connect to /stream (MJPEG) or / (HTML viewer)")
    }

    private inner class MjpegStream : InputStream() {
        private var currentStream: ByteArrayInputStream? = null

        override fun read(): Int {
            return try {
                val stream = getValidStream() ?: return -1
                stream.read()
            } catch (_: Exception) {
                -1
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return try {
                val stream = getValidStream() ?: return -1
                stream.read(b, off, len)
            } catch (_: Exception) {
                -1
            }
        }

        private fun getValidStream(): ByteArrayInputStream? {
            if (currentStream != null && currentStream!!.available() > 0) {
                return currentStream
            }
            if (!isRunning()) return null

            val frame = frameQueue.poll(300, TimeUnit.MILLISECONDS) ?: currentFrame.get() ?: return null

            val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
            val footer = "\r\n"
            val data = header.toByteArray() + frame + footer.toByteArray()
            currentStream = ByteArrayInputStream(data)
            return currentStream
        }

        override fun available(): Int {
            return currentStream?.available() ?: if (currentFrame.get() != null) 1 else 0
        }

        override fun close() {
            super.close()
            currentStream = null
        }
    }
}
