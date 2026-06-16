---
description: Подключить Mission Planner к дрону ESP32 через Tailscale subnet routing
agent: code
---

Подключи Mission Planner к дрону ESP32 через Tailscale subnet routing (UDP).

**IP дрона:** `$1` (по умолчанию `172.24.80.237`)
**Порт:** `$2` (по умолчанию `14550`)

## Как работает

ESP32-дрон — MAVLink bridge: FC <-> UART <-> ESP32 <-> WiFi.
Дрон НЕ может сам отправлять данные на Tailscale IP (локальная сеть не знает маршрута 100.x.y.z).
Он регистрирует клиента ТОЛЬКО когда клиент ПЕРВЫМ присылает пакет на порт дрона.

## Алгоритм

### 1. Убить старый MP
```powershell
taskkill /f /im MissionPlanner.exe 2>$null; Start-Sleep -Seconds 2
```

### 2. Найти/поправить config.xml MP
Файл: `$env:USERPROFILE\OneDrive\Документы\Mission Planner\config.xml`
Убедиться что `<UDP_host>` содержит IP дрона.
Если нет — заменить/создать этот тег внутри `<Config>`.

### 3. Проверить Tailscale
```powershell
Test-Connection -ComputerName $DRONE_IP -Count 1 -Quiet
```

### 4. Отправить heartbeat С ПОРТА 14550 на дрон (КЛЮЧЕВОЙ ШАГ)
ESP32 запоминает IP:port отправителя и начинает слать MAVLink поток.
```powershell
python -c "
import socket, time
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(('0.0.0.0', 14550))
hb = bytes([0xFD,0x09,0x00,0xFF,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x03,0x05,0x79])
sock.sendto(hb, ('$DRONE_IP', 14550))
sock.settimeout(2.0)
try:
    data, addr = sock.recvfrom(4096)
    count = 1; t0 = time.time()
    while time.time() - t0 < 1.2:
        try: data, addr = sock.recvfrom(4096); count += 1
        except socket.timeout: break
    print(f'OK: {count} packets from drone')
except socket.timeout:
    print('WARNING: no response - FC may be offline')
finally:
    sock.close()
"
```

### 5. Подождать 2 секунды
Порт 14550 должен освободиться (Windows).

### 6. Запустить Mission Planner
```powershell
Start-Process 'C:\Program Files (x86)\Mission Planner\MissionPlanner.exe'
```

### 7. Проверить через 15 секунд
```powershell
netstat -ano | findstr :14550
```
Должен быть процесс с `UDP 0.0.0.0:14550` — MP слушает и получает MAVLink.

## Примечания
- ESP32 не шлёт данные пока не получит пакет ПЕРВЫМ
- После ребута ESP32 список клиентов сбрасывается — повтори шаг 4
- Tailscale должен быть активен: `tailscale status`
- Если нет ответа на шаге 4 — проверь питание FC и UART подключение
