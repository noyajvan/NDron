#!/data/data/com.termux/files/usr/bin/python3
"""MAVLink UDP Hub - forwards ESP32 MAVLink to one or more GCS"""
import socket, select, sys, threading, time, os, signal

ESP32_IP = "10.45.202.53"
ESP32_PORT = 14550
LISTEN_PORT = 14550
dests = []

def load():
    try:
        with open(os.path.expanduser("~/.mavdests")) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    parts = line.split()
                    dests.append((parts[0], int(parts[1])))
    except Exception: pass

def save():
    with open(os.path.expanduser("~/.mavdests"), "w") as f:
        for ip, port in dests:
            f.write(f"{ip} {port}\n")

def menu():
    while True:
        print("\n=== MAVLink Hub ===")
        print(f"ESP32: {ESP32_IP}:{ESP32_PORT}")
        print(f"Destinations ({len(dests)}):")
        for i, (ip, port) in enumerate(dests):
            print(f"  {i+1}. {ip}:{port}")
        print("\na <ip> <port>  - add")
        print("d <n>          - delete")
        print("s              - save & exit")
        print("q              - quit")
        cmd = input("> ").strip().split()
        if not cmd: continue
        if cmd[0] == "a" and len(cmd) >= 3:
            dests.append((cmd[1], int(cmd[2])))
            save()
            print(f"Added {cmd[1]}:{cmd[2]}")
        elif cmd[0] == "d" and len(cmd) >= 2:
            n = int(cmd[1]) - 1
            if 0 <= n < len(dests):
                print(f"Removed {dests[n][0]}:{dests[n][1]}")
                del dests[n]; save()
        elif cmd[0] == "s":
            save(); os.kill(os.getpid(), signal.SIGTERM)
        elif cmd[0] == "q":
            os.kill(os.getpid(), signal.SIGTERM)

def main():
    load()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", LISTEN_PORT))
    sock.setblocking(False)
    esp_addr = (ESP32_IP, ESP32_PORT)

    threading.Thread(target=menu, daemon=True).start()
    print(f"MAVLink Hub ready. Listening 0.0.0.0:{LISTEN_PORT}")

    while True:
        try:
            r = select.select([sock], [], [], 1.0)
            if r[0]:
                data, addr = sock.recvfrom(65535)
                if addr == esp_addr:
                    for ip, port in dests:
                        try: sock.sendto(data, (ip, port))
                        except: pass
                elif any(addr == (ip, port) for ip, port in dests):
                    sock.sendto(data, esp_addr)
                else:
                    dests.append((addr[0], addr[1]))
                    save()
                    print(f"New client: {addr[0]}:{addr[1]}")
                    sock.sendto(data, esp_addr)
        except: break

if __name__ == "__main__":
    main()
