---
description: Подключить Mission Planner к дрону ESP32 через WiFi hotspot телефона
agent: code
---

Подключи Mission Planner к дрону через телефон (WiFi hotspot) + Tailscale.

## Новая архитектура

`
ESP32 -> gateway (телефон 192.168.43.1):14550
     -> socat/mavhub на телефоне
     -> Tailscale IP ПК (100.104.253.54):14550
     -> Mission Planner

ПК -> Tailscale IP телефона (100.112.147.84):14550
  -> socat/mavhub на телефоне
  -> ESP32
`

## Что нужно

### 1. Телефон раздаёт WiFi (hotspot)
- ESP32 подключается как STA к hotspot телефона
- IP телефона = 192.168.43.1 (всегда)

### 2. На телефоне — socat (Termux)
`ash
pkg install socat
socat UDP-RECVFROM:14550,reuseaddr,fork UDP-SENDTO:100.104.253.54:14550
`

### 3. На ПК — запустить Mission Planner
`powershell
.\scripts\mp_connect.ps1
`

### 4. Команды ПК -> ESP32
Mission Planner шлёт на Tailscale IP телефона:
100.112.147.84:14550

socat на телефоне получает -> форвардит на ESP32.

## Примечания
- ESP32 шлёт MAVLink только на gateway (телефон) — heartbeat не нужен
- IP ESP32 может быть любым (192.168.43.x) — это не важно
- Телефон фиксирует Tailscale IP ПК единожды
