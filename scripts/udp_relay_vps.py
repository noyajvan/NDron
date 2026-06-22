#!/usr/bin/env python3
"""
VPS UDP Relay: Drone 14550 <-> GCS 14551
Deployed on DigitalOcean (134.209.206.127) as systemd service.

Drone timeout: 10s (no packets -> Drone gone)
GCS timeout: 120s (no heartbeat -> GCS gone)
Drone data forwarded to ALL registered GCS.
GCS commands forwarded to current drone only.
"""
import socket, sys, time

DRONE_PORT = 14550
GCS_PORT = 14551
DT = 10       # Drone timeout: 10s
GT = 120      # GCS timeout: 120s
LOG_PERIOD = 60  # Health log every 60s

ds = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
ds.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
ds.bind(('0.0.0.0', DRONE_PORT))

gs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
gs.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
gs.bind(('0.0.0.0', GCS_PORT))

ds.settimeout(0.3)
gs.settimeout(0.3)

drone = None
drone_t = 0
gcs_list = {}
gcs_t = {}
last_log = 0

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

sys.stderr.write(f"Relay {DRONE_PORT}<->{GCS_PORT}\n"); sys.stderr.flush()

while True:
    now = time.time()
    try:
        data, addr = ds.recvfrom(2048)
        was_new = drone is None
        drone = addr
        drone_t = now
        clean()
        if was_new:
            sys.stderr.write(f"Drone: {addr[0]}\n"); sys.stderr.flush()
        for ga in list(gcs_list.keys()):
            if now - gcs_t[ga] <= GT:
                try:
                    gs.sendto(data, ga)
                except:
                    pass
    except socket.timeout:
        pass

    try:
        data, addr = gs.recvfrom(2048)
        now2 = time.time()
        clean()
        if addr not in gcs_list:
            sys.stderr.write(f"GCS: {addr[0]}:{addr[1]}\n"); sys.stderr.flush()
        gcs_list[addr] = True
        gcs_t[addr] = now2
        if drone and (now2 - drone_t <= DT):
            try:
                gs.sendto(data, drone)
            except:
                pass
    except socket.timeout:
        pass

    changed = clean()

    if drone and now - last_log >= LOG_PERIOD:
        gcs_cnt = len([a for a in gcs_list if now - gcs_t[a] <= GT])
        sys.stderr.write(f"alive: drone={drone[0]} gcs={gcs_cnt}\n"); sys.stderr.flush()
        last_log = now
    if not drone and changed and now - last_log >= LOG_PERIOD:
        sys.stderr.write(f"waiting: drone=None gcs={len(gcs_list)}\n"); sys.stderr.flush()
        last_log = now
