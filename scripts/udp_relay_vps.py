#!/usr/bin/env python3
"""
VPS UDP Relay: Drone 14550 <-> GCS 14551
Uses select() for low-latency non-blocking I/O.
"""
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

ds.setblocking(0)
gs.setblocking(0)

drone = None
drone_t = 0
gcs_list = {}
gcs_t = {}
last_log = 0
pkt_count = 0

def clean():
    global drone, drone_t
    now = time.time()
    changed = False
    if drone and (now - drone_t > DT):
        drone = None
        sys.stderr.write("Drone gone\n"); sys.stderr.flush()
        changed = True
    stale = [a for a in gcs_list if now - gcs_t[a] > GT]
    for a in stale:
        del gcs_list[a]; del gcs_t[a]
        sys.stderr.write("GCS gone\n"); sys.stderr.flush()
        changed = True
    return changed

def tcp_cmd_server():
    """TCP server on CMD_PORT – forwards lines to drone via UDP."""
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
    now = time.time()
    readable, _, _ = select.select([ds, gs], [], [], 0.01)  # 10ms poll

    for sock in readable:
        if sock == ds:
            try:
                data, addr = ds.recvfrom(2048)
                was_new = drone is None
                drone = addr
                drone_t = now
                if was_new:
                    sys.stderr.write(f"Drone: {addr[0]}\n"); sys.stderr.flush()
                # Forward to all valid GCS
                for ga in list(gcs_list.keys()):
                    if now - gcs_t[ga] <= GT:
                        try:
                            gs.sendto(data, ga)
                        except: pass
                pkt_count += 1
            except: pass

        elif sock == gs:
            try:
                data, addr = gs.recvfrom(2048)
                clean()
                if addr not in gcs_list:
                    sys.stderr.write(f"GCS: {addr[0]}:{addr[1]}\n"); sys.stderr.flush()
                gcs_list[addr] = True
                gcs_t[addr] = now
                if drone and (now - drone_t <= DT):
                    try:
                        ds.sendto(data, drone)
                    except: pass
            except: pass

    if not readable:
        clean()
        now = time.time()

    # Periodic health log
    if now - last_log >= LOG_PERIOD:
        gcs_cnt = len([a for a in gcs_list if now - gcs_t[a] <= GT])
        if drone:
            sys.stderr.write(f"alive: drone={drone[0]} gcs={gcs_cnt} pkt/s={pkt_count/LOG_PERIOD:.0f}\n")
        else:
            sys.stderr.write(f"waiting: drone=None gcs={gcs_cnt}\n")
        sys.stderr.flush()
        pkt_count = 0
        last_log = now

