#!/bin/bash
# Скрипт автоматичного розгортання UDP реле на VPS (Oracle Cloud / Ubuntu)

echo "=== Початок розгортання ==="

# 1. Оновлення пакетів
sudo apt-get update -y
sudo apt-get install -y python3 python3-pip iptables-persistent

# 2. Відкриття портів у локальному фаєрволі Ubuntu (Oracle блокує все по дефолту!)
echo "Налаштування фаєрволу..."
sudo iptables -I INPUT 6 -p udp --dport 14550 -j ACCEPT
sudo iptables -I INPUT 6 -p udp --dport 14551 -j ACCEPT
sudo iptables -I INPUT 6 -p tcp --dport 14552 -j ACCEPT

# Збереження правил iptables, щоб не злетіли після перезавантаження
sudo netfilter-persistent save

# 3. Створення директорії для реле
sudo mkdir -p /opt/drone-relay
sudo chown -R $USER:$USER /opt/drone-relay

# 4. Копіювання скрипта реле (якщо запускається локально на сервері, файл вже має бути перенесений)
# Створимо файл реле безпосередньо, якщо його ще немає
cat << 'EOF' > /opt/drone-relay/udp_relay_vps.py
import socket, sys, time, select, threading

DRONE_PORT = 14550
GCS_PORT = 14551
CMD_PORT = 14552
DT = 60       # Drone timeout: 60s
GT = 300      # GCS timeout: 300s
LOG_PERIOD = 60  # Health log every 60s

ds = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
ds.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
ds.bind(('0.0.0.0', DRONE_PORT))

gs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
gs.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
gs.bind(('0.0.0.0', GCS_PORT))

drone = None
gcs = None
ld = 0.0
lg = 0.0
last_log = 0.0

def clean():
    global drone, gcs
    changed = False
    now = time.time()
    if drone and now - ld > DT:
        sys.stderr.write(f"Drone timeout {drone[0]}\n"); sys.stderr.flush()
        drone = None
        changed = True
    if gcs and now - lg > GT:
        sys.stderr.write(f"GCS timeout {gcs[0]}\n"); sys.stderr.flush()
        gcs = None
        changed = True
    return changed

def tcp_cmd_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', CMD_PORT))
    srv.listen(5)
    srv.settimeout(1.0)
    sys.stderr.write(f"CMD TCP:{CMD_PORT}\n"); sys.stderr.flush()
    while True:
        try:
            conn, addr = srv.accept()
            conn.settimeout(5.0)
            sys.stderr.write(f"CMD conn {addr[0]}\n"); sys.stderr.flush()
            try:
                data = conn.recv(4096)
                if data and drone:
                    ds.sendto(data.strip(), drone)
                    sys.stderr.write(f"CMD fwd {len(data)}B\n"); sys.stderr.flush()
            except: pass
            conn.close()
        except: pass

threading.Thread(target=tcp_cmd_server, daemon=True).start()

sys.stderr.write(f"Relay {DRONE_PORT}<->{GCS_PORT}\n"); sys.stderr.flush()

while True:
    try:
        r, _, _ = select.select([ds, gs], [], [], 1.0)
        now = time.time()
        
        if ds in r:
            data, addr = ds.recvfrom(4096)
            if drone != addr:
                drone = addr
                sys.stderr.write(f"Drone active: {addr[0]}:{addr[1]}\n"); sys.stderr.flush()
            ld = now
            if gcs:
                gs.sendto(data, gcs)
                
        if gs in r:
            data, addr = gs.recvfrom(4096)
            if gcs != addr:
                gcs = addr
                sys.stderr.write(f"GCS active: {addr[0]}:{addr[1]}\n"); sys.stderr.flush()
            lg = now
            if drone:
                ds.sendto(data, drone)
                
        if clean() or now - last_log > LOG_PERIOD:
            last_log = now
            sys.stderr.write(f"Status: Drone={drone}, GCS={gcs}\n"); sys.stderr.flush()
            
    except KeyboardInterrupt:
        break
    except Exception as e:
        sys.stderr.write(f"Error: {e}\n"); sys.stderr.flush()
        time.sleep(1)
EOF

# 5. Створення автозапуску через systemd сервіс
echo "Створення системного сервісу..."
sudo tee /etc/systemd/system/drone-relay.service > /dev/null << 'EOF'
[Unit]
Description=Drone Bridge UDP Relay Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/drone-relay
ExecStart=/usr/bin/python3 /opt/drone-relay/udp_relay_vps.py
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# 6. Запуск та активація сервісу
echo "Запуск сервісу..."
sudo systemctl daemon-reload
sudo systemctl enable drone-relay
sudo systemctl restart drone-relay

echo "=== Розгортання завершено успішно ==="
echo "Сервіс працює. Перевірити статус: sudo systemctl status drone-relay"
