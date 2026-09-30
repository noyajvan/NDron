#!/data/data/com.termux/files/usr/bin/python3
\"\"\"MAVLink UDP Forwarder — ESP32 -> ПК через телефон\"\"\"
import socket, select

GCS_IP = \"100.104.253.54\"  # Tailscale IP ПК
GCS_PORT = 14550
LISTEN_PORT = 14550

def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((\"0.0.0.0\", LISTEN_PORT))
    sock.setblocking(False)

    esp_addr = None  # узнаём динамически

    print(f\"MAVLink Forwarder ready. Listening 0.0.0.0:{LISTEN_PORT}\")
    print(f\"Forwarding to GCS: {GCS_IP}:{GCS_PORT}\")

    while True:
        try:
            r = select.select([sock], [], [], 1.0)
            if r[0]:
                data, addr = sock.recvfrom(65535)
                if esp_addr is None:
                    esp_addr = addr
                    print(f\"ESP32 registered: {addr[0]}:{addr[1]}\")
                if addr == esp_addr:
                    # Данные от ESP32 -> шлём на ПК
                    try:
                        sock.sendto(data, (GCS_IP, GCS_PORT))
                    except Exception as e:
                        print(f\"Send to GCS failed: {e}\")
                elif addr == (GCS_IP, GCS_PORT) and esp_addr:
                    # Команда от ПК -> шлём на ESP32
                    try:
                        sock.sendto(data, esp_addr)
                    except Exception as e:
                        print(f\"Send to ESP32 failed: {e}\")
                elif addr[0] != GCS_IP:
                    # Неизвестный — считаем ESP32
                    print(f\"New source: {addr[0]}:{addr[1]}\")
                    esp_addr = addr
        except KeyboardInterrupt:
            break
        except Exception:
            pass
    sock.close()

if __name__ == \"__main__\":
    main()
