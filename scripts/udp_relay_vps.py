#!/usr/bin/env python3
"""
VPS UDP/TCP Relay for DroneBridge:
  Drone (UDP 14550) <-> GCS UDP (14551)
  Drone (UDP 14550) <-> GCS TCP (14552)  <- Mission Planner TCP (persistent bidirectional)
"""
import socket, sys, time, select, threading

DRONE_PORT   = 14550
UDP_GCS_PORT = 14551
TCP_GCS_PORT = 14552
DT  = 20    # Drone timeout, s (быстрее забывает старого дрона при переподключении)
GT  = 300   # GCS timeout, s
LOG_PERIOD = 60

ds = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
ds.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
ds.bind(('0.0.0.0', DRONE_PORT))
ds.setblocking(0)

gs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
gs.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
gs.bind(('0.0.0.0', UDP_GCS_PORT))
gs.setblocking(0)

drone = None
drone_t = 0
udp_gcs = {}          # addr -> last_seen
tcp_clients = {}      # socket -> addr
tcp_lock = threading.Lock()

def broadcast_tcp(data):
    """Forward drone UDP packet to all connected TCP GCS (Mission Planner)."""
    with tcp_lock:
        for s in list(tcp_clients.keys()):
            try:
                s.sendall(data)
            except Exception:
                try:
                    s.close()
                except Exception:
                    pass
                tcp_clients.pop(s, None)

def tcp_client_loop(conn, addr):
    """Bidirectional per-client: TCP -> drone UDP."""
    global drone
    try:
        while True:
            try:
                data = conn.recv(4096)
                if not data:
                    break
                if drone:
                    ds.sendto(data, drone)
                    ds.sendto(data, drone)  # 2 копії: 4G губить UDP у бік дрона
            except socket.timeout:
                continue
            except Exception:
                break
    finally:
        try:
            conn.close()
        except Exception:
            pass
        with tcp_lock:
            tcp_clients.pop(conn, None)
        sys.stderr.write(f"TCP GCS disconnected: {addr[0]}\n"); sys.stderr.flush()

def tcp_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', TCP_GCS_PORT))
    srv.listen(8)
    srv.settimeout(0.5)
    sys.stderr.write(f"TCP GCS server: {TCP_GCS_PORT}\n"); sys.stderr.flush()
    while True:
        try:
            conn, addr = srv.accept()
            conn.settimeout(0.1)
            with tcp_lock:
                tcp_clients[conn] = addr
            sys.stderr.write(f"TCP GCS connected: {addr[0]}\n"); sys.stderr.flush()
            threading.Thread(target=tcp_client_loop, args=(conn, addr), daemon=True).start()
        except socket.timeout:
            continue
        except Exception:
            pass

threading.Thread(target=tcp_server, daemon=True).start()

sys.stderr.write(f"Relay {DRONE_PORT} <-> UDP {UDP_GCS_PORT} / TCP {TCP_GCS_PORT}\n"); sys.stderr.flush()

last_log = 0.0
last_poke = 0.0
POKE = bytes([0xFE,9,1,0,0,0,1,0,0,0,0,0,0,0,0,0,0])
while True:
    now = time.time()
    try:
        # Пінг дрону: ESP32 (навіть стара прошивка) вмикає телеметрію
        # (hasServer) при будь-якому вхідному UDP-пакеті.
        if drone and (now - last_poke) > 5.0:
            last_poke = now
            try:
                ds.sendto(POKE, drone)
            except Exception:
                pass
        readable, _, _ = select.select([ds, gs], [], [], 0.05)

        if ds in readable:
            try:
                data, addr = ds.recvfrom(4096)
                was_new = drone is None
                drone = addr
                drone_t = now
                if was_new:
                    sys.stderr.write(f"Drone: {addr[0]}\n"); sys.stderr.flush()
                for ga in list(udp_gcs.keys()):
                    if now - udp_gcs[ga] <= GT:
                        try:
                            gs.sendto(data, ga)
                        except Exception:
                            pass
                broadcast_tcp(data)
            except Exception:
                pass

        if gs in readable:
            try:
                data, addr = gs.recvfrom(4096)
                if addr not in udp_gcs:
                    sys.stderr.write(f"UDP GCS: {addr[0]}:{addr[1]}\n"); sys.stderr.flush()
                udp_gcs[addr] = now
                if drone and (now - drone_t <= DT):
                    ds.sendto(data, drone)
                    ds.sendto(data, drone)  # 2 копії: 4G губить UDP у бік дрона
            except Exception:
                pass

        # GCS / drone cleanup
        if drone and (now - drone_t > DT):
            sys.stderr.write("Drone gone\n"); sys.stderr.flush()
            drone = None
        for a in [a for a in udp_gcs if now - udp_gcs[a] > GT]:
            del udp_gcs[a]

        if now - last_log >= LOG_PERIOD:
            n_udp = len([a for a in udp_gcs if now - udp_gcs[a] <= GT])
            with tcp_lock:
                n_tcp = len(tcp_clients)
            if drone:
                sys.stderr.write(f"alive: drone={drone[0]} udp_gcs={n_udp} tcp_gcs={n_tcp}\n")
            else:
                sys.stderr.write(f"waiting: drone=None udp_gcs={n_udp} tcp_gcs={n_tcp}\n")
            sys.stderr.flush()
            last_log = now

    except KeyboardInterrupt:
        break
    except Exception as e:
        sys.stderr.write(f"Error: {e}\n"); sys.stderr.flush()
        time.sleep(1)
