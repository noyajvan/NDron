#!/usr/bin/env python3
"""
DroneBridge VPS relay.

Транспорти:
  Drone  TCP 14553  (новий ESP32: вихідний TCP, надійний)  \
  Drone  UDP 14550  (legacy UDP-прошивки)                   } -> GCS TCP 14552 (Mission Planner)
                                                              -> GCS UDP 14551
Напрямок до дрона: якщо є живий TCP-дрон - шлемо йому (без дублів, TCP сам
гарантує доставку). Інакше - legacy UDP-дрону, 2 копії (4G губить UDP).
"""
import socket, sys, time, threading

DRONE_UDP_PORT = 14550
UDP_GCS_PORT   = 14551
TCP_GCS_PORT   = 14552
DRONE_TCP_PORT = 14553
DT  = 20     # legacy UDP-дрон: timeout, s
GT  = 300    # UDP GCS timeout, s
LOG_PERIOD = 60
POKE = bytes([0xFE,9,1,0,0,0,1,0,0,0,0,0,0,0,0,0,0])

lock = threading.Lock()
drone_udp = None
drone_udp_t = 0.0
drone_tcp = None       # socket активного TCP-дрона
drone_tcp_t = 0.0
tcp_clients = {}       # GCS TCP socket -> addr
udp_gcs = {}           # GCS UDP addr -> last_seen

def log(msg):
    sys.stderr.write(msg + "\n"); sys.stderr.flush()

def close_sock(s):
    try: s.close()
    except Exception: pass

def send_to_drone(data):
    """GCS -> drone. TCP-дрон пріоритетний, інакше legacy UDP (2 копії)."""
    global drone_tcp
    with lock:
        t = drone_tcp
        if t is not None:
            try:
                t.sendall(data)
                return
            except Exception:
                log("Drone TCP send fail, dropping drone TCP")
                close_sock(t)
                if drone_tcp is t: drone_tcp = None
        d = drone_udp
        if d is not None and time.time() - drone_udp_t <= DT:
            try:
                ds.sendto(data, d)
                ds.sendto(data, d)
            except Exception:
                pass

def drop_tcp_client(s):
    with lock:
        addr = tcp_clients.pop(s, None)
    close_sock(s)
    if addr:
        log("TCP GCS send error, dropping: %s" % addr[0])

def broadcast_to_gcs(data):
    """Drone -> всі GCS (TCP + свіжі UDP)."""
    now = time.time()
    dead = []
    for s in list(tcp_clients.keys()):
        try:
            s.sendall(data)
        except socket.timeout:
            log("TCP GCS send timeout, dropping")
            dead.append(s)
        except Exception:
            dead.append(s)
    for s in dead:
        drop_tcp_client(s)
    for a in list(udp_gcs.keys()):
        if now - udp_gcs[a] <= GT:
            try:
                gs.sendto(data, a)
            except Exception:
                pass

def tcp_gcs_loop(conn, addr):
    try:
        while True:
            try:
                data = conn.recv(4096)
            except socket.timeout:
                continue
            except Exception:
                break
            if not data:
                break
            send_to_drone(data)
    finally:
        with lock:
            tcp_clients.pop(conn, None)
        close_sock(conn)
        log("TCP GCS closed: %s" % addr[0])

def tcp_server(port, handler, name):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', port))
    srv.listen(8)
    srv.settimeout(0.5)
    log("%s server: %d" % (name, port))
    while True:
        try:
            conn, addr = srv.accept()
        except socket.timeout:
            continue
        except Exception:
            continue
        conn.settimeout(2.0)
        try:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except Exception:
            pass
        handler(conn, addr)

def gcs_accept(conn, addr):
    with lock:
        tcp_clients[conn] = addr
    log("TCP GCS connected: %s" % addr[0])
    threading.Thread(target=tcp_gcs_loop, args=(conn, addr), daemon=True).start()

def drone_tcp_accept(conn, addr):
    """Приймаємо TCP-дрон у окремому потоці. Новий дрон витісняє старого."""
    global drone_tcp, drone_tcp_t
    with lock:
        if drone_tcp is not None and drone_tcp is not conn:
            close_sock(drone_tcp)
        drone_tcp = conn
        drone_tcp_t = time.time()
    log("Drone TCP connected: %s" % addr[0])
    threading.Thread(target=drone_tcp_loop, args=(conn, addr), daemon=True).start()

def drone_tcp_loop(conn, addr):
    global drone_tcp, drone_tcp_t
    try:
        while True:
            try:
                data = conn.recv(4096)
            except socket.timeout:
                continue
            except Exception:
                break
            if not data:
                break
            with lock:
                drone_tcp_t = time.time()
            broadcast_to_gcs(data)
    finally:
        with lock:
            if drone_tcp is conn:
                drone_tcp = None
        close_sock(conn)
        log("Drone TCP closed: %s" % addr[0])

# UDP сокети
ds = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
ds.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
ds.bind(('0.0.0.0', DRONE_UDP_PORT))
ds.setblocking(0)

gs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
gs.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
gs.bind(('0.0.0.0', UDP_GCS_PORT))
gs.setblocking(0)

threading.Thread(target=tcp_server, args=(TCP_GCS_PORT, gcs_accept, "TCP GCS"), daemon=True).start()
threading.Thread(target=tcp_server, args=(DRONE_TCP_PORT, drone_tcp_accept, "Drone TCP"), daemon=True).start()

log("Relay: drone UDP %d / drone TCP %d <-> GCS UDP %d / GCS TCP %d" %
    (DRONE_UDP_PORT, DRONE_TCP_PORT, UDP_GCS_PORT, TCP_GCS_PORT))

import select
last_log = 0.0
last_poke = 0.0
while True:
    now = time.time()
    try:
        # Poke лише legacy UDP-дрону (стара прошивка вмикає телеметрію по hasServer).
        if drone_udp is not None and (now - last_poke) > 5.0:
            last_poke = now
            if now - drone_udp_t <= DT:
                try:
                    ds.sendto(POKE, drone_udp)
                except Exception:
                    pass
        readable, _, _ = select.select([ds, gs], [], [], 0.05)

        if ds in readable:
            try:
                data, addr = ds.recvfrom(4096)
                with lock:
                    was_new = drone_udp is None or (addr[0] != drone_udp[0])
                    drone_udp = addr
                    drone_udp_t = now
                    if was_new:
                        log("Drone UDP: %s" % addr[0])
                broadcast_to_gcs(data)
            except Exception:
                pass

        if gs in readable:
            try:
                data, addr = gs.recvfrom(4096)
                if addr not in udp_gcs:
                    log("UDP GCS: %s:%d" % (addr[0], addr[1]))
                udp_gcs[addr] = now
                send_to_drone(data)
            except Exception:
                pass

        # Cleanup
        with lock:
            if drone_tcp is not None and now - drone_tcp_t > 15:
                log("Drone TCP silent 15s, dropping")
                close_sock(drone_tcp)
                drone_tcp = None
        if drone_udp is not None and now - drone_udp_t > DT:
            log("Drone UDP gone (timeout)")
            drone_udp = None
        for a in [a for a in udp_gcs if now - udp_gcs[a] > GT]:
            del udp_gcs[a]

        if now - last_log >= LOG_PERIOD:
            with lock:
                n_tcp = len(tcp_clients)
                d_tcp = drone_tcp is not None
            n_udp = len([a for a in udp_gcs if now - udp_gcs[a] <= GT])
            if d_tcp:
                log("alive: drone=TCP udp_gcs=%d tcp_gcs=%d" % (n_udp, n_tcp))
            elif drone_udp is not None:
                log("alive: drone=UDP:%s udp_gcs=%d tcp_gcs=%d" % (drone_udp[0], n_udp, n_tcp))
            else:
                log("waiting: drone=None udp_gcs=%d tcp_gcs=%d" % (n_udp, n_tcp))
            last_log = now

    except KeyboardInterrupt:
        break
    except Exception as e:
        log("Error: %s" % e)
        time.sleep(1)
