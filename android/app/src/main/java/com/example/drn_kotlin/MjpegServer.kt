package com.example.drn_kotlin

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(port: Int) : NanoHTTPD(port) {
    private val currentFrame = AtomicReference<ByteArray?>(null)
    // Черга на 1 кадр: завжди віддаємо найсвіжіший кадр — мінімальна затримка.
    private val frameQueue = ArrayBlockingQueue<ByteArray>(1)
    private val streamBytes = AtomicLong(0)
    private val clientCount = AtomicInteger(0)
    private val TAG = "MjpegServer"

    /** Скільки байтів реально віддано глядачам (для швидкості з'єднання). */
    fun getStreamBytes(): Long = streamBytes.get()

    /** Кількість активних глядачів MJPEG. */
    fun getClientCount(): Int = clientCount.get()

    companion object {
        // Той самий «маленький принц», що й на телефоні, але у SVG для браузера.
        private val PRINCE_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" fill="none"
                 stroke="#aab4d4" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M36,82 a18,18 0 1,0 36,0 a18,18 0 1,0 -36,0"/>
              <path d="M54,52 L54,64"/>
              <path d="M54,56 L62,59 M54,56 L46,59"/>
              <path d="M47,44 a7,7 0 1,0 14,0 a7,7 0 1,0 -14,0"/>
              <path d="M49,37 C51,34 55,33 58,35 M47,40 C46,36 49,32 53,31" stroke-width="2"/>
              <path d="M48,52 C52,55 57,55 61,53"/>
              <path d="M60,53 C70,50 74,59 86,55"/>
              <path d="M71,80 L71,72 M67,72 C67,67 75,67 75,72 M71,76 C74,75 76,77 75,79" stroke-width="2.2"/>
              <path d="M85,27 L85,34 M81.5,30.5 L88.5,30.5" stroke-width="2.2"/>
              <path d="M24,34 L24,39 M21.5,36.5 L26.5,36.5" stroke-width="1.9"/>
            </svg>
        """.trimIndent()
    }

    /**
     * Провайдер статистики від сервісу. Встановлюється ззовні.
     */
    var healthProvider: (() -> HealthSnapshot)? = null

    /**
     * Обробник віддалених команд керування з веб-інтерфейсу (Zoom, Quality, FPS, Resolution).
     */
    var controlHandler: ((zoom: Float?, quality: Int?, fps: Int?, width: Int?, height: Int?) -> Unit)? = null

    /**
     * Повністю lock-free оновлення кадру через ArrayBlockingQueue.
     */
    fun updateFrame(jpegData: ByteArray) {
        currentFrame.set(jpegData)
        // Тримаємо лише найсвіжіший кадр: старий скидаємо, новий одразу кладемо.
        frameQueue.poll()
        frameQueue.offer(jpegData)
    }

    fun isRunning(): Boolean = wasStarted()

    override fun serve(session: IHTTPSession): Response {
        Log.i(TAG, "New request from ${session.remoteIpAddress}: ${session.uri}")

        if (session.uri == "/prince.svg") {
            return newFixedLengthResponse(Response.Status.OK, "image/svg+xml", PRINCE_SVG)
        }

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

        if (session.uri == "/" || session.uri == "/index.html") {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
                    <title>NOY_DRN BRIDGE</title>
                    <style>
                        * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                        html, body { margin:0; padding:0; height:100%; background:#000; overflow:hidden;
                            font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif; color:#e1e4ed; }

                        /* відео на весь екран */
                        #wrap { position:fixed; inset:0; overflow:hidden; background:#000; }
                        #streamImg { position:absolute; left:50%; top:50%; transform-origin:center; width:100vw; height:100vh; object-fit:contain; }

                        /* HUD-оверлеї */
                        .overlay { position:fixed; left:0; right:0; z-index:10; background:rgba(8,10,18,0.62); backdrop-filter:blur(4px); border-color:#2a2e42; }
                        #topbar { top:0; display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; padding:8px 12px; border-bottom:1px solid #2a2e42; }
                        #stats { bottom:0; display:flex; align-items:center; justify-content:center; flex-wrap:wrap; gap:8px 18px; padding:10px 12px; border-top:1px solid #2a2e42; font-size:13px; color:#9aa2bd; }
                        #stats b { color:#4caf50; font-weight:700; }
                        #stats b.hot { color:#ef6c00; }

                        .brand { display:flex; align-items:center; gap:8px; font-size:16px; font-weight:700; white-space:nowrap; }
                        .brand img { display:block; }

                        .actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }

                        .btn { flex:0 0 auto; height:46px; min-width:92px; padding:0 16px; display:inline-flex; align-items:center; justify-content:center;
                            background:#23273a; color:#e1e4ed; border:1px solid #3b425c; border-radius:10px; cursor:pointer;
                            font-weight:700; font-size:14px; line-height:1; }
                        .btn:hover { background:#343a52; border-color:#64b5f6; }
                        .btn.active { background:#1976d2; border-color:#2196f3; color:#fff; }
                        .btn.eye { min-width:60px; font-size:20px; }

                        .status-badge { background:#2e7d32; color:#fff; padding:6px 12px; border-radius:14px; font-size:12px; font-weight:bold; letter-spacing:0.5px; white-space:nowrap; }
                        .status-badge.reconnecting { background:#ef6c00; }

                        /* модалка налаштувань (SET) */
                        #controls { position:fixed; left:50%; top:50%; transform:translate(-50%,-50%); z-index:30;
                            width:min(92vw,560px); max-height:82vh; overflow:auto; padding:18px; border-radius:14px;
                            background:rgba(13,15,24,0.98); border:1px solid #2a2e42; box-shadow:0 16px 48px rgba(0,0,0,0.7);
                            display:grid; grid-template-columns:1fr; gap:16px; }
                        .control-group { display:flex; flex-direction:column; gap:8px; }
                        .control-label { font-size:13px; font-weight:600; color:#8e95b0; display:flex; justify-content:space-between; align-items:center; }
                        .control-value { color:#64b5f6; font-size:14px; font-weight:bold; }
                        input[type=range] { width:100%; accent-color:#2196f3; cursor:pointer; height:8px; }
                        .row { display:flex; gap:8px; flex-wrap:wrap; }
                        .row .btn { flex:1 1 0; min-width:72px; }

                        .hidden { display:none !important; }
                        /* «око» ховає інфо поверх відео, лишаючи кнопки */
                        body.hideInfo #stats, body.hideInfo #brandText, body.hideInfo #badge { display:none !important; }
                    </style>
                </head>
                <body>
                    <div id="wrap">
                        <img id="streamImg" src="/stream" alt="DRN Camera Live Stream"/>
                    </div>

                    <div id="topbar" class="overlay">
                        <span class="brand">
                            <img src="/prince.svg" width="28" height="28" alt="prince"/>
                            <span id="brandText">🛸 NOY_DRN BRIDGE</span>
                            <span id="badge" class="status-badge">LIVE MJPEG</span>
                        </span>
                        <span class="actions">
                            <button id="eyeBtn" class="btn eye" title="Показати/сховати інфо" onclick="toggleInfo()">👁</button>
                            <button class="btn" onclick="rotate90()">⟳ 90°</button>
                            <button id="setBtn" class="btn" onclick="toggleSet()">SET</button>
                        </span>
                    </div>

                    <div id="stats" class="overlay">
                        <span>FPS: <b id="statFps">0</b></span>
                        <span>Speed: <b id="statSpeed">0.0 Mbps</b></span>
                        <span>Clients: <b id="statClients">0</b></span>
                        <span>Frame: <b id="statFrame">0 KB</b></span>
                        <span>Quality: <b id="statQuality">20%</b></span>
                        <span>Zoom: <b id="statZoom">1.0x</b></span>
                        <span>Sent: <b id="statSession">0 MB</b></span>
                        <span>Thermal: <b id="statThermal">OK</b></span>
                        <span>Temp: <b id="statTemp">0°C</b></span>
                        <span>RAM: <b id="statRam">0 MB</b></span>
                        <span>Tailscale: <b id="statTs">OK</b></span>
                    </div>

                    <div id="controls" class="hidden">
                        <div class="control-group">
                            <div class="control-label"><span>ZOOM</span><span id="zoomVal" class="control-value">1.0x</span></div>
                            <input type="range" id="zoomRange" min="1.0" max="5.0" step="0.1" value="1.0" oninput="sendZoom(this.value)"/>
                            <div class="row">
                                <button class="btn" onclick="setZoom(1.0)">1.0x</button>
                                <button class="btn" onclick="setZoom(2.0)">2.0x</button>
                                <button class="btn" onclick="setZoom(3.0)">3.0x</button>
                                <button class="btn" onclick="setZoom(5.0)">5.0x</button>
                            </div>
                        </div>

                        <div class="control-group">
                            <div class="control-label"><span>РОЗДІЛЬНА ЗДАТНІСТЬ</span></div>
                            <div class="row">
                                <button id="res640" class="btn active" onclick="setRes('640x480')">640x480</button>
                                <button id="res720" class="btn" onclick="setRes('1280x720')">1280x720</button>
                                <button id="res1080" class="btn" onclick="setRes('1920x1080')">1920x1080</button>
                            </div>
                        </div>

                        <div class="control-group">
                            <div class="control-label"><span>ЧАСТОТА КАДРІВ</span></div>
                            <div class="row">
                                <button id="fps5" class="btn" onclick="setFps(5)">5</button>
                                <button id="fps8" class="btn active" onclick="setFps(8)">8</button>
                                <button id="fps15" class="btn" onclick="setFps(15)">15</button>
                                <button id="fps30" class="btn" onclick="setFps(30)">30</button>
                            </div>
                        </div>

                        <div class="control-group">
                            <div class="control-label"><span>ЯКІСТЬ КАДРУ (MJPEG)</span><span id="qualityVal" class="control-value">20%</span></div>
                            <input type="range" id="qualityRange" min="10" max="100" step="5" value="20" oninput="sendQuality(this.value)"/>
                            <div class="row">
                                <button class="btn" onclick="setQuality(25)">25%</button>
                                <button class="btn" onclick="setQuality(40)">40%</button>
                                <button class="btn" onclick="setQuality(70)">70%</button>
                            </div>
                        </div>

                        <div class="row">
                            <button class="btn" style="min-width:100%;" onclick="toggleSet()">ЗАКРИТИ</button>
                        </div>
                    </div>

                    <script>
                        const img = document.getElementById('streamImg');
                        const badge = document.getElementById('badge');
                        let rot = 0;

                        function applyRotate() {
                            const swap = (rot % 180) !== 0;
                            img.style.width = swap ? '100vh' : '100vw';
                            img.style.height = swap ? '100vw' : '100vh';
                            img.style.transform = 'translate(-50%,-50%) rotate(' + rot + 'deg)';
                        }
                        function rotate90() {
                            rot = (rot + 90) % 360;
                            applyRotate();
                        }
                        function toggleInfo() {
                            document.body.classList.toggle('hideInfo');
                            document.getElementById('eyeBtn').classList.toggle('active');
                        }
                        function toggleSet() {
                            document.getElementById('controls').classList.toggle('hidden');
                            document.getElementById('setBtn').classList.toggle('active');
                        }

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
                            document.querySelectorAll('#fps5, #fps8, #fps15, #fps30').forEach(b => b.classList.remove('active'));
                            if (val === 5) document.getElementById('fps5').classList.add('active');
                            if (val === 8) document.getElementById('fps8').classList.add('active');
                            if (val === 15) document.getElementById('fps15').classList.add('active');
                            if (val === 30) document.getElementById('fps30').classList.add('active');
                            sendControl('fps', val);
                        }

                        const THERMAL = {0:'OK',1:'LIGHT',2:'MODERATE',3:'SEVERE',4:'CRITICAL',5:'EMERGENCY',6:'SHUTDOWN'};

                        let lastFrameCount = -1;
                        let frozenCount = 0;
                        setInterval(() => {
                            fetch('/health')
                                .then(r => r.text())
                                .then(txt => {
                                    const lines = txt.split('\n');
                                    let currentFc = 0;
                                    lines.forEach(l => {
                                        const kv = l.split('=');
                                        const k = kv[0], v = kv[1];
                                        if (k === 'frameCount') currentFc = parseInt(v) || 0;
                                        if (k === 'fps') document.getElementById('statFps').textContent = parseFloat(v).toFixed(1);
                                        if (k === 'streamKbps') document.getElementById('statSpeed').textContent = (parseFloat(v)/1000).toFixed(2) + ' Mbps';
                                        if (k === 'clients') document.getElementById('statClients').textContent = v;
                                        if (k === 'lastFrameBytes') document.getElementById('statFrame').textContent = Math.round(parseInt(v)/1024) + ' KB';
                                        if (k === 'jpegQuality') document.getElementById('statQuality').textContent = v + '%';
                                        if (k === 'zoom') document.getElementById('statZoom').textContent = parseFloat(v).toFixed(1) + 'x';
                                        if (k === 'sessionMb') document.getElementById('statSession').textContent = parseFloat(v).toFixed(1) + ' MB';
                                        if (k === 'thermalStatus') {
                                            const el = document.getElementById('statThermal');
                                            const n = parseInt(v);
                                            el.textContent = THERMAL[n] || n;
                                            el.className = n >= 2 ? 'hot' : '';
                                        }
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
                            setTimeout(() => { img.src = '/stream?t=' + Date.now(); }, 2000);
                        };
                        img.onload = () => {
                            badge.textContent = 'LIVE MJPEG';
                            badge.className = 'status-badge';
                        };

                        applyRotate();
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
                    append("tailscaleUp=${snapshot.tailscaleUp}\n")
                    append("addresses=${snapshot.addresses.joinToString(",")}\n")
                    append("sessionMb=${snapshot.sessionMb}\n")
                    append("streamKbps=${String.format("%.1f", snapshot.streamKbps)}\n")
                    append("clients=${snapshot.clients}\n")
                    append("thermalStatus=${snapshot.thermalStatus}\n")
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
        private var counted = false

        init {
            clientCount.incrementAndGet()
            counted = true
        }

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

            // Чекаємо саме новий кадр (щоб не дублювати й не додавати затримку).
            val frame = frameQueue.poll(1000, TimeUnit.MILLISECONDS) ?: currentFrame.get() ?: return null

            val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
            val footer = "\r\n"
            val data = header.toByteArray() + frame + footer.toByteArray()
            streamBytes.addAndGet(data.size.toLong())
            currentStream = ByteArrayInputStream(data)
            return currentStream
        }

        override fun available(): Int {
            return currentStream?.available() ?: if (currentFrame.get() != null) 1 else 0
        }

        override fun close() {
            super.close()
            currentStream = null
            if (counted) {
                counted = false
                clientCount.decrementAndGet()
            }
        }
    }
}
