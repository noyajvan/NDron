#!/usr/bin/env python3
import threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import time

latest_frame = b""
frame_lock = threading.Lock()
frame_count = 0
start_time = time.time()

class RelayHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        global latest_frame, frame_count
        if self.path == '/publish':
            content_length = int(self.headers.get('Content-Length', 0))
            data = self.rfile.read(content_length)
            if data:
                with frame_lock:
                    latest_frame = data
                    frame_count += 1
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        if self.path == '/latest.jpg' or self.path.startswith('/latest.jpg?'):
            with frame_lock:
                frame = latest_frame
            if frame:
                self.send_response(200)
                self.send_header('Content-Type', 'image/jpeg')
                self.send_header('Content-Length', str(len(frame)))
                self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
                self.end_headers()
                self.wfile.write(frame)
            else:
                self.send_response(404)
                self.end_headers()
        elif self.path == '/stream':
            # Fallback multipart stream
            self.send_response(200)
            self.send_header('Content-type', 'multipart/x-mixed-replace; boundary=frame')
            self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
            self.end_headers()
            try:
                last_sent = None
                while True:
                    with frame_lock:
                        frame = latest_frame
                    if frame and frame != last_sent:
                        last_sent = frame
                        self.wfile.write(b"--frame\r\n")
                        self.send_header('Content-Type', 'image/jpeg')
                        self.send_header('Content-Length', str(len(frame)))
                        self.end_headers()
                        self.wfile.write(frame + b"\r\n")
                        self.wfile.flush()
                    time.sleep(0.05)
            except Exception:
                pass
        elif self.path == '/health':
            self.send_response(200)
            self.send_header('Content-type', 'text/plain')
            self.end_headers()
            uptime = int(time.time() - start_time)
            with frame_lock:
                fc = frame_count
                has_frame = len(latest_frame) > 0
            msg = f"ok\nuptime_s={uptime}\nframes={fc}\nhas_frame={has_frame}\n"
            self.wfile.write(msg.encode())
        else:
            self.send_response(200)
            self.send_header('Content-type', 'text/html')
            self.end_headers()
            html = b"""<html>
            <head>
                <title>DRN Live Stream - Zero Latency</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
            </head>
            <body style="background:#111;color:#eee;text-align:center;font-family:sans-serif;margin:0;padding:20px;">
                <h2>DRN Live Stream (Zero Latency)</h2>
                <div style="margin-bottom:15px;">
                    <img id="stream" src="/latest.jpg" style="max-width:100%;height:auto;border:2px solid #333;border-radius:8px;background:#000;"/>
                </div>
                <div id="stats" style="color:#888;font-size:13px;">Connecting...</div>
                <script>
                    const img = document.getElementById('stream');
                    const stats = document.getElementById('stats');
                    let count = 0;
                    let lastTime = new Date().getTime();

                    setInterval(() => {
                        const now = new Date().getTime();
                        img.src = '/latest.jpg?t=' + now;
                        count++;
                        if (now - lastTime >= 1000) {
                            stats.innerText = 'Status: Live | Approx FPS: ' + count;
                            count = 0;
                            lastTime = now;
                        }
                    }, 200); // 5 FPS refresh
                </script>
            </body>
            </html>"""
            self.wfile.write(html)

    def log_message(self, format, *args):
        pass

def run():
    server = ThreadingHTTPServer(('0.0.0.0', 8888), RelayHandler)
    print("MJPEG Zero-Latency Relay server running on port 8888")
    server.serve_forever()

if __name__ == '__main__':
    run()
