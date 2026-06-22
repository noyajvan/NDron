#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Preferences.h>

#include <common/mavlink.h>
#include <Adafruit_NeoPixel.h>
#include <driver/uart.h>
#include <driver/gpio.h>

#define UDP_PORT         14550
#define BRIDGE_BUF_SIZE  2048

#define LED_PIN     48
#define NUM_LEDS    1

#define LED_OFF     0x000000
#define LED_WHITE   0xFFFFFF
#define LED_GREEN   0x00FF00
#define LED_PURPLE  0xFF00FF
#define LED_RED     0xFF0000
#define LED_ORANGE  0xFFA500
#define LED_BLUE    0x0000FF
#define BOOT_BTN    0

// cfg.sys_id now configurable via cfg.sys_id (NVS) — use terminal: SYSID=N + SAVE
#define COMP_ID         MAV_COMP_ID_ONBOARD_COMPUTER
#define TAKEOFF_MODE    13

#define DO_VPS_IP IPAddress(134, 209, 206, 127)

struct Config {
  char     sta_ssid[32]  = "LEO";
  char     sta_pass[64]  = "88888888";
  uint32_t baud          = 921600;
  uint8_t  wifi_boot     = 1;
  uint8_t  sys_id        = 1;
} cfg;

Adafruit_NeoPixel pixels(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);
#define FC_UART_NUM 0
#define FC_RX_BUF 16384
#define FC_TX_BUF 4096
WiFiUDP    udp;

IPAddress gcsIP = DO_VPS_IP;
uint16_t gcsPort = UDP_PORT;
bool     gcsIPSet = true;


bool wifiOn = false;
bool wifiActivating = false;
unsigned long wifiActivateTime = 0;
uint8_t staRetryCount = 0;
bool staWasConnected = false;

// Р‘СѓС„РµСЂ РґР»СЏ РґРёРЅР°РјС–С‡РЅРёС… РєР»С–С”РЅС‚С–РІ (СЏРєС– СЃР°РјС– РЅР°РґС–СЃР»Р°Р»Рё РїР°РєРµС‚)

mavlink_message_t mavMsg;
mavlink_status_t  mavStatus;
uint8_t           bridgeBuf[BRIDGE_BUF_SIZE];
mavlink_message_t txMsg;
uint8_t           txBuf[MAVLINK_MAX_PACKET_LEN];

struct Point { int32_t lat=0; int32_t lon=0; bool relay=false; bool received=false; };
Point pTg;

uint16_t mission_count = 0;
bool     mission_loaded = false, heartbeat_received = false;
uint32_t current_custom_mode = 0;
bool     is_armed = false;
bool     acro_stop = false;
bool     takeoff_detected = false;
bool     takeoff_mode_detected = false;

unsigned long start_time = 0, last_request = 0, state_entry = 0, retry_time = 0,
              last_wifi_hb = 0, last_serial_log = 0, last_sta_reconnect = 0;
int last_sta_status = -1;
uint32_t fc_bytes = 0, fc_msgs = 0;
bool     missionFirstParsed = false;
unsigned long lastMissionReq = 0;
unsigned long lastMissionScan = 0;

// Crash detection & failsafe
bool last_arm_state = false;
float current_alt = 0.0, last_stable_alt = 0.0;
float roll = 0.0, pitch = 0.0;
uint16_t throttle = 0;
float ground_speed = 0.0;
uint8_t system_status = 0;
unsigned long stuck_timer = 0, roof_stuck_timer = 0, gyro_crash_timer = 0;
bool emergency_triggered = false;
bool wasFlying = false;
bool failsafePending = false;
uint8_t failsafeStep = 0;
uint8_t failsafeRepeat = 0;
unsigned long failsafeTime = 0;
bool autoArmDone = false;
unsigned long autoArmTime = 0;
uint8_t autoArmRetries = 0;
uint8_t gps_fix_type = 0;
uint8_t gps_sats = 0;
unsigned long gps_ok_since = 0;

bool bootDone = false;
uint8_t bootState = 0;
unsigned long bootTimer = 0;
uint8_t targetWiFi = 0;
bool tiltInBoot = false;

#define MODE_STABILIZE  0
#define MODE_AUTO       3

#define STATUS_QUEUE_SIZE 12
char status_queue[STATUS_QUEUE_SIZE][64];
uint8_t q_write = 0;
uint8_t q_read = 0;

String inputBuffer = "";

void setLed(uint32_t c) { pixels.setPixelColor(0, c); pixels.show(); }

void loadConfig() {
  Preferences p;
  p.begin("dbridge", true);
  p.getString("sta_ssid", cfg.sta_ssid, sizeof(cfg.sta_ssid));
  p.getString("sta_pass", cfg.sta_pass, sizeof(cfg.sta_pass));
  cfg.baud     = p.getUInt("baud", 921600);
  cfg.wifi_boot = p.getUInt("wifi_boot", 1);
  cfg.sys_id    = p.getUInt("sys_id", 1);
  p.end();

  Serial.printf("NVS LOADED: sta_ssid=%s, baud=%u, wifi_boot=%u, sys_id=%u\n", cfg.sta_ssid, cfg.baud, cfg.wifi_boot, cfg.sys_id);
}


void saveConfig() {
  Preferences p;
  p.begin("dbridge", false);
  p.putString("sta_ssid", cfg.sta_ssid);
  p.putString("sta_pass", cfg.sta_pass);
  p.putUInt("baud",      cfg.baud);
  p.putUInt("wifi_boot", cfg.wifi_boot);
  p.putUInt("sys_id",    cfg.sys_id);
  p.end();
  Serial.printf("NVS SAVED: sta_ssid=%s, baud=%u, wifi_boot=%u, sys_id=%u\n", cfg.sta_ssid, cfg.baud, cfg.wifi_boot, cfg.sys_id);
}


void send_statustext(const char* text);
void queue_statustext(const char* text);
void send_queued_statustext();

void switchToSmartStaticIP() {
  IPAddress ip = WiFi.localIP();
  IPAddress gw = WiFi.gatewayIP();
  IPAddress mask = WiFi.subnetMask();
  if (gw == IPAddress(0,0,0,0) || ip == IPAddress(0,0,0,0)) return;
  uint8_t mac[6];
  WiFi.macAddress(mac);
  uint8_t id = mac[5];
  uint8_t ipTail = 201 + (id % 54);
  IPAddress newIP(gw[0], gw[1], gw[2], ipTail);
  WiFi.config(newIP, gw, mask);
  WiFi.disconnect(false);
  WiFi.reconnect();
  Serial.printf("[IP] Set to %d.%d.%d.%d (MAC 0x%02X)\n", gw[0], gw[1], gw[2], ipTail, id);
}

void wifiActivate() {
  if (wifiOn) return;
  wifiOn = true;
  setLed(LED_ORANGE);
  WiFi.disconnect(false);
  delay(50);
  WiFi.mode(WIFI_STA);
  delay(50);
  WiFi.setTxPower(WIFI_POWER_8_5dBm);
  WiFi.setSleep(WIFI_PS_NONE);
  
  if (strlen(cfg.sta_ssid) > 0) {
    WiFi.begin(cfg.sta_ssid, cfg.sta_pass);
    wifiActivating = true;
    wifiActivateTime = millis();
    Serial.printf("Connecting to STA: %s\n", cfg.sta_ssid);
    queue_statustext("WiFi STA Connecting");
  } else {
    Serial.println("STA SSID is empty. Use terminal to set it.");
  }
  
  udp.begin(UDP_PORT);

  queue_statustext("WiFi ON");
}

void wifiDeactivate() {
  if (!wifiOn) return;
  wifiOn = false;
  wifiActivating = false;
  staRetryCount = 0;
  staWasConnected = false;
  
  udp.stop();

  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  Serial.println("WiFi OFF (Radio Disabled)");
  queue_statustext("WiFi OFF");
}

void fcBegin(int baud, int rx, int tx) {
  uart_config_t uart_config = {
    .baud_rate = baud,
    .data_bits = UART_DATA_8_BITS,
    .parity = UART_PARITY_DISABLE,
    .stop_bits = UART_STOP_BITS_1,
    .flow_ctrl = UART_HW_FLOWCTRL_DISABLE
  };
  uart_param_config((uart_port_t)FC_UART_NUM, &uart_config);
  uart_set_pin((uart_port_t)FC_UART_NUM, tx, rx, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
  uart_driver_install((uart_port_t)FC_UART_NUM, FC_RX_BUF, FC_TX_BUF, 0, NULL, 0);
}

size_t fcAvailable() {
  size_t len; uart_get_buffered_data_len((uart_port_t)FC_UART_NUM, &len); return len;
}

void fcWrite(const uint8_t* d, size_t len) {
  uart_write_bytes((uart_port_t)FC_UART_NUM, d, len);
}

void sendToFC(const uint8_t* data, uint16_t len) { fcWrite(data, len); }

// Otvechaem na IP otpravitelya (sohranyaetsya pri poluchenii paketa).
// S relay — telefonniy NAT ne meshaet, potomu chto relay prorabotal
// soedinenie v obe storony.
void forwardToWiFi(const uint8_t* data, size_t len) {
  if (!wifiOn || WiFi.status() != WL_CONNECTED) return;
  if (!gcsIPSet) return;
  udp.beginPacket(gcsIP, gcsPort);
  udp.write(data, len);
  udp.endPacket();
}


void sendToBoth(const uint8_t* data, uint16_t len) {
  fcWrite(data, len);
  forwardToWiFi(data, len);
}

void send_statustext(const char* text) {
  mavlink_msg_statustext_pack(cfg.sys_id, COMP_ID, &txMsg, MAV_SEVERITY_INFO, text, 0, 0);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void queue_statustext(const char* text) {
  uint8_t next = (q_write + 1) % STATUS_QUEUE_SIZE;
  if (next == q_read) q_read = (q_read + 1) % STATUS_QUEUE_SIZE;
  strncpy(status_queue[q_write], text, 63);
  status_queue[q_write][63] = '\0';
  q_write = next;
}

void send_queued_statustext() {
  for (int i = 0; i < 5 && q_read != q_write; i++) {
    mavlink_msg_statustext_pack(cfg.sys_id, COMP_ID, &txMsg,
                                MAV_SEVERITY_INFO, status_queue[q_read], 0, 0);
    uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
    sendToBoth(txBuf, len);
    q_read = (q_read + 1) % STATUS_QUEUE_SIZE;
  }
}

void send_heartbeat() {
  mavlink_msg_heartbeat_pack(cfg.sys_id, COMP_ID, &txMsg, MAV_TYPE_ONBOARD_CONTROLLER, MAV_AUTOPILOT_INVALID, 0, 0, 0);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void send_mission_request_list() {
  mavlink_msg_mission_request_list_pack(cfg.sys_id, COMP_ID, &txMsg, 1, MAV_COMP_ID_AUTOPILOT1, MAV_MISSION_TYPE_MISSION);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void send_mission_request_int(uint16_t seq) {
  mavlink_msg_mission_request_int_pack(cfg.sys_id, COMP_ID, &txMsg, 1, MAV_COMP_ID_AUTOPILOT1, seq, MAV_MISSION_TYPE_MISSION);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void sendMavlinkForceDisarm() {
  mavlink_message_t msg; uint8_t buf[MAVLINK_MAX_PACKET_LEN];
  mavlink_msg_command_long_pack(cfg.sys_id, COMP_ID, &msg, 1, MAV_COMP_ID_AUTOPILOT1,
    MAV_CMD_COMPONENT_ARM_DISARM, 0, 0.0f, 21196.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
  uint16_t len = mavlink_msg_to_send_buffer(buf, &msg); fcWrite(buf, len);
  Serial.println("[FAILSAFE] DISARM");
}

void sendMavlinkSetRelay() {
  mavlink_message_t msg; uint8_t buf[MAVLINK_MAX_PACKET_LEN];
  mavlink_msg_command_long_pack(cfg.sys_id, COMP_ID, &msg, 1, MAV_COMP_ID_AUTOPILOT1,
    MAV_CMD_DO_SET_RELAY, 0, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
  uint16_t len = mavlink_msg_to_send_buffer(buf, &msg); fcWrite(buf, len);
  Serial.println("[FAILSAFE] RELAY");
  queue_statustext("Relay ON");
}

void sendMavlinkArm() {
  mavlink_message_t msg; uint8_t buf[MAVLINK_MAX_PACKET_LEN];
  mavlink_msg_command_long_pack(cfg.sys_id, COMP_ID, &msg, 1, MAV_COMP_ID_AUTOPILOT1,
    MAV_CMD_COMPONENT_ARM_DISARM, 0, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
  uint16_t len = mavlink_msg_to_send_buffer(buf, &msg); fcWrite(buf, len);
  Serial.println("[AUTO-ARM] Sent");
}

void sendMavlinkSetMode(uint8_t mode) {
  mavlink_message_t msg; uint8_t buf[MAVLINK_MAX_PACKET_LEN];
  mavlink_msg_command_long_pack(cfg.sys_id, COMP_ID, &msg, 1, MAV_COMP_ID_AUTOPILOT1,
    MAV_CMD_DO_SET_MODE, 0, MAV_MODE_FLAG_CUSTOM_MODE_ENABLED, mode, 0, 0, 0, 0, 0);
  uint16_t len = mavlink_msg_to_send_buffer(buf, &msg); fcWrite(buf, len);
  Serial.printf("[AUTO-MODE] Set mode %d\n", mode);
}

void executeFailsafeAsync() {
  failsafePending = true; failsafeStep = 0; failsafeTime = millis();
  Serial.println("[FAILSAFE] PENDING");
}

void handle_mavlink_message(mavlink_message_t* msg) {
  switch (msg->msgid) {
    case MAVLINK_MSG_ID_HEARTBEAT: {
      mavlink_heartbeat_t hb;
      mavlink_msg_heartbeat_decode(msg, &hb);
      heartbeat_received = true;
      static bool first_hb = false;
      if (!first_hb) { first_hb = true; queue_statustext("Mavlink OK");
        mavlink_msg_command_long_pack(cfg.sys_id, COMP_ID, &txMsg, 1, MAV_COMP_ID_AUTOPILOT1,
          MAV_CMD_SET_MESSAGE_INTERVAL, 0, MAVLINK_MSG_ID_ATTITUDE, 100000, 0, 0, 0, 0, 0);
        uint16_t l = mavlink_msg_to_send_buffer(txBuf, &txMsg); fcWrite(txBuf, l);
        mavlink_msg_command_long_pack(cfg.sys_id, COMP_ID, &txMsg, 1, MAV_COMP_ID_AUTOPILOT1,
          MAV_CMD_SET_MESSAGE_INTERVAL, 0, MAVLINK_MSG_ID_RAW_IMU, 100000, 0, 0, 0, 0, 0);
        l = mavlink_msg_to_send_buffer(txBuf, &txMsg); fcWrite(txBuf, l); }
      current_custom_mode = hb.custom_mode;
      system_status = hb.system_status;
      if (hb.custom_mode == TAKEOFF_MODE) takeoff_mode_detected = true;
      is_armed = (hb.base_mode & MAV_MODE_FLAG_SAFETY_ARMED) != 0;
      if (last_arm_state && !is_armed && !emergency_triggered && wasFlying && autoArmDone) {
        emergency_triggered = true;
        sendMavlinkForceDisarm();
        sendMavlinkSetRelay();
        Serial.println("[LAND] Disarm + action");
      }
      last_arm_state = is_armed;
      break;
    }
    case MAVLINK_MSG_ID_MISSION_COUNT: {
      mavlink_mission_count_t mc;
      mavlink_msg_mission_count_decode(msg, &mc);
      mission_count = mc.count; mission_loaded = false; pTg = Point();
      Serial.printf("MISSCOUNT=%d\n", mission_count);
      if (mission_count > 0) send_mission_request_int(0);
      break;
    }
    case MAVLINK_MSG_ID_MISSION_ITEM_INT: {
      mavlink_mission_item_int_t item;
      mavlink_msg_mission_item_int_decode(msg, &item);
      if (item.command == MAV_CMD_NAV_TAKEOFF) takeoff_detected = true;
      if (item.command == MAV_CMD_NAV_LAND && !pTg.received)
        { pTg.lat = item.x; pTg.lon = item.y; pTg.received = true; }
      if (item.command == MAV_CMD_DO_SET_RELAY &&
          item.param2 > 0.9f) pTg.relay = true;
      Serial.printf("ITEM: s=%d c=%d p=%.2f\n", item.seq, item.command, item.param1);
      if (item.seq + 1 < mission_count) send_mission_request_int(item.seq + 1);
      else { mission_loaded = true; missionFirstParsed = true; lastMissionReq = 0; }
      break;
    }
    case MAVLINK_MSG_ID_MISSION_CURRENT:
      break;
    case MAVLINK_MSG_ID_ATTITUDE: {
      mavlink_attitude_t att;
      mavlink_msg_attitude_decode(msg, &att);
      roll = att.roll * 57.2958f; pitch = att.pitch * 57.2958f;
      break;
    }
    case MAVLINK_MSG_ID_VFR_HUD: {
      mavlink_vfr_hud_t vfr;
      mavlink_msg_vfr_hud_decode(msg, &vfr);
      current_alt = vfr.alt; throttle = vfr.throttle; ground_speed = vfr.groundspeed;
      if (is_armed && current_alt > 2.0f) wasFlying = true;
      if (!emergency_triggered && wasFlying && is_armed) {
        if (abs(current_alt - last_stable_alt) < 0.15f && ground_speed < 0.15f) {
          if (stuck_timer == 0) stuck_timer = millis();
          unsigned long dt = millis() - stuck_timer;
          if (dt > 3000 && throttle > 45) { emergency_triggered = true; executeFailsafeAsync(); Serial.println("[CRASH] Net"); }
          if (dt > 2500 && (abs(roll) > 0.18f || abs(pitch) > 0.18f) && throttle > 25) { emergency_triggered = true; executeFailsafeAsync(); Serial.println("[CRASH] Roof"); }
        } else { stuck_timer = 0; last_stable_alt = current_alt; }
      }
      break;
    }
    case MAVLINK_MSG_ID_RAW_IMU: {
      mavlink_raw_imu_t imu;
      mavlink_msg_raw_imu_decode(msg, &imu);
      if (!emergency_triggered && wasFlying && is_armed && autoArmDone) {
        if (abs(imu.xgyro) > 4500 || abs(imu.ygyro) > 4500) {
          if (gyro_crash_timer == 0) gyro_crash_timer = millis();
          if (millis() - gyro_crash_timer > 150) { emergency_triggered = true; executeFailsafeAsync(); Serial.println("[CRASH] Tumble"); }
        } else { gyro_crash_timer = 0; }
      }
      break;
    }
    case MAVLINK_MSG_ID_GPS_RAW_INT: {
      mavlink_gps_raw_int_t gps;
      mavlink_msg_gps_raw_int_decode(msg, &gps);
      gps_fix_type = gps.fix_type;
      gps_sats = gps.satellites_visible;
      if (gps_fix_type >= 3 && gps_sats >= 6 && gps_ok_since == 0) gps_ok_since = millis();
      if (gps_fix_type < 3 || gps_sats < 6) gps_ok_since = 0;
      break;
    }
  }
}



void bridgeFCtoWiFi() {
  size_t avail = fcAvailable();
  if (avail == 0) return;
  size_t n = min(avail, (size_t)BRIDGE_BUF_SIZE);
  n = uart_read_bytes((uart_port_t)FC_UART_NUM, bridgeBuf, n, 1);
  if (n == 0) return;
  fc_bytes += n;
  forwardToWiFi(bridgeBuf, n);
  for (size_t i = 0; i < n; i++)
    if (mavlink_parse_char(MAVLINK_COMM_0, bridgeBuf[i], &mavMsg, &mavStatus)) {
      fc_msgs++;
      handle_mavlink_message(&mavMsg);
    }
}

void bridgeWiFiToFC() {
  if (!wifiOn || WiFi.status() != WL_CONNECTED) return;
  int sz = udp.parsePacket();
  if (sz > 0) {
    IPAddress rip = udp.remoteIP();
    uint16_t rport = udp.remotePort();
    int n = udp.read(bridgeBuf, sizeof(bridgeBuf));
    if (n > 0) {
      fcWrite(bridgeBuf, n);
      Serial.printf("[GCS CMD] %d bytes from %s:%u forwarded to FC\n", n, rip.toString().c_str(), rport);
    }
  }
}


void handleTerminalConfig() {
  while (Serial.available() > 0) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (inputBuffer.length() > 0) {
        inputBuffer.trim();
        
        if (inputBuffer.equalsIgnoreCase("STATUS")) {
          Serial.println("\n--- CURRENT STATUS ---");
          Serial.printf("WiFi Power: %s\n", wifiOn ? "ON (STA Mode)" : "OFF (Sleep)");
          Serial.printf("WiFi Status: %s\n", (WiFi.status() == WL_CONNECTED) ? "CONNECTED" : "DISCONNECTED");
          Serial.printf("IP Address: %s\n", WiFi.localIP().toString().c_str());
          Serial.printf("Config SSID: %s\n", cfg.sta_ssid);
          Serial.printf("Config UART Baud: %u\n", cfg.baud);
          Serial.printf("Auto WiFi at boot: %s\n", cfg.wifi_boot ? "ON" : "OFF");
          Serial.printf("SYS_ID: %u\n", cfg.sys_id);
          Serial.println("----------------------");
        } 
        else if (inputBuffer.startsWith("SSID=")) {
          String val = inputBuffer.substring(5);
          strncpy(cfg.sta_ssid, val.c_str(), sizeof(cfg.sta_ssid) - 1);
          Serial.printf("Set SSID to: %s (Type 'SAVE' to store)\n", cfg.sta_ssid);
        } 
        else if (inputBuffer.startsWith("PASS=")) {
          String val = inputBuffer.substring(5);
          strncpy(cfg.sta_pass, val.c_str(), sizeof(cfg.sta_pass) - 1);
          Serial.println("Set Password successfully (Type 'SAVE' to store)");
        } 
        else if (inputBuffer.startsWith("BAUD=")) {
          uint32_t b = inputBuffer.substring(5).toInt();
          if (b == 9600 || b == 19200 || b == 38400 || b == 57600 || b == 115200 || b == 230400 || b == 460800 || b == 921600) {
            cfg.baud = b;
            Serial.printf("Set UART Baud to: %u (Type 'SAVE' to store)\n", cfg.baud);
          } else {
            Serial.println("Invalid Baudrate!");
          }
        }
        else if (inputBuffer.equalsIgnoreCase("WIFI=1")) {
          if (!wifiOn) wifiActivate();
          cfg.wifi_boot = 1;
          Serial.println("WiFi ON (auto at boot)");
        }
        else if (inputBuffer.equalsIgnoreCase("WIFI=0")) {
          if (wifiOn) wifiDeactivate();
          cfg.wifi_boot = 0;
          Serial.println("WiFi OFF (off at boot)");
        }
        else if (inputBuffer.equalsIgnoreCase("RELAY")) {
          sendMavlinkSetRelay();
          Serial.println("RELAY 0 1 sent to FC");
        }
        else if (inputBuffer.equalsIgnoreCase("DISARM")) {
          sendMavlinkForceDisarm();
          Serial.println("DISARM sent to FC");
        }
        else if (inputBuffer.startsWith("SYSID=")) {
          uint8_t sid = inputBuffer.substring(6).toInt();
          if (sid >= 1 && sid <= 255) {
            cfg.sys_id = sid;
            Serial.printf("Set SYS_ID to: %u (Type 'SAVE' to store)\n", cfg.sys_id);
          } else {
            Serial.println("Invalid SYS_ID (1-255)");
          }
        }
        else if (inputBuffer.equalsIgnoreCase("SAVE")) {
          saveConfig();
          Serial.println("Configuration Saved! Rebooting...");
          delay(500);
          ESP.restart();
        } 
        else {
          Serial.printf("Unknown command: %s. Available: STATUS, SSID=xxx, PASS=xxx, BAUD=xxx, SYSID=N, WIFI=1, WIFI=0, RELAY, DISARM, SAVE\n", inputBuffer.c_str());
        }
        inputBuffer = "";
      }
    } else {
      inputBuffer += c;
    }
  }
}

void handleTiltDetect() {
  if (roll >= 30.0f && roll <= 120.0f)        { targetWiFi = 1; tiltInBoot = true; }
  else if (roll >= -120.0f && roll <= -30.0f)  { targetWiFi = 0; tiltInBoot = true; }
}

void bootSequence() {
  if (bootDone) return;
  unsigned long now = millis();

  switch (bootState) {
    case 0: {
      if (heartbeat_received && (roll != 0.0f || pitch != 0.0f)) {
        if (!bootTimer) bootTimer = now;
        if (now - bootTimer >= 3000) {
          targetWiFi = cfg.wifi_boot;
          tiltInBoot = false;
          bootTimer = now; bootState = 1;
        }
      } else {
        bootTimer = 0;
      }
      break;
    }

    case 1: {
      if (now - bootTimer >= 3000) bootState = 2;
      break;
    }

    case 2: {
      handleTiltDetect();
      if (now - bootTimer > 10000) {
        if (targetWiFi != cfg.wifi_boot) {
          bootTimer = now; bootState = 3;
        } else {
          bootState = 4;
        }
      }
      break;
    }

    case 3: {
      if (roll >= -20.0f && roll <= 20.0f) {
        if (now - bootTimer > 5000) bootState = 4;
      } else {
        bootTimer = now;
      }
      break;
    }

    case 4:
      // Tilt-override temparary — ne sohranyaem v NVS
      if (targetWiFi && !wifiOn) wifiActivate();
      if (!targetWiFi && wifiOn) wifiDeactivate();
      bootDone = true;
      break;
  }
}

void mdFly() {
  if (!bootDone || autoArmDone) return;
  unsigned long now = millis();
  static uint8_t mdState = 0;

  switch (mdState) {
    case 0:
      if (!missionFirstParsed) return;
      if (gps_fix_type >= 3 && gps_sats >= 6 && gps_ok_since > 0 && millis() - gps_ok_since >= 3000) {
        if (current_custom_mode == MODE_STABILIZE && system_status == MAV_STATE_STANDBY && !is_armed && now - autoArmTime > 10000) {
          queue_statustext("Rdy to ARM");
          sendMavlinkArm();
          queue_statustext("ARM");
          autoArmTime = now;
          autoArmRetries++;
          mdState = 1;
        }
      }
      break;

    case 1:
      if (is_armed) {
        queue_statustext("Rdy to AUTO");
        sendMavlinkSetMode(MODE_AUTO);
        autoArmTime = now;
        mdState = 2;
      } else if (now - autoArmTime > 10000) {
        queue_statustext(">ARM");
        sendMavlinkArm();
        autoArmTime = now;
        autoArmRetries++;
      }
      break;

    case 2:
      if (current_custom_mode == MODE_AUTO) {
        autoArmDone = true;
        mdState = 0;
        queue_statustext("AUTO");
      } else if (now - autoArmTime > 10000) {
        queue_statustext(">AUTO");
        sendMavlinkSetMode(MODE_AUTO);
        autoArmTime = now;
      }
      break;
  }
}

void updateLED() {
  unsigned long now = millis();

  if (!bootDone) {
    if (bootState == 0) {
      setLed((now % 1000 < 500) ? (cfg.wifi_boot ? LED_BLUE : LED_WHITE) : LED_OFF);
      return;
    }
    if (bootState == 1) {
      setLed(cfg.wifi_boot ? LED_BLUE : LED_WHITE);
      return;
    }
    if (bootState == 2) {
      if (tiltInBoot) setLed(targetWiFi ? LED_PURPLE : LED_OFF);
      else setLed((now % 500 < 250) ? LED_PURPLE : LED_OFF);
      return;
    }
    if (bootState == 3) {
      setLed(targetWiFi ? LED_PURPLE : LED_OFF);
      return;
    }
    return;
  }

  if (emergency_triggered || failsafePending) { setLed(LED_RED); return; }

  if (!heartbeat_received) { setLed((now % 1000 < 200) ? LED_WHITE : LED_OFF); return; }

  uint32_t c = (current_custom_mode == 3) ? (is_armed ? LED_GREEN : LED_ORANGE) : LED_ORANGE;

  if (!wifiOn) { setLed(c); return; }

  if (WiFi.status() != WL_CONNECTED) {
    setLed((now % 1000 < 500) ? c : LED_OFF);
  } else {
    setLed((now % 800 < 500) ? c : LED_OFF);
  }
}

void setup() {
  Serial.begin(115200);
  delay(100);
  loadConfig();
  fcBegin(cfg.baud, 44, 43);
  delay(50);
  
  pixels.begin(); pixels.setBrightness(10); pixels.clear(); setLed(LED_BLUE);
  
  WiFi.persistent(false);
  pinMode(BOOT_BTN, INPUT_PULLUP);
  
  if (cfg.wifi_boot) wifiActivate();
  
  start_time = millis();
   Serial.println("\n=== ESP32-S3 DropCtrlV3 VPS Relay Ready ===");
  Serial.println("Terminal configuration active. Type 'STATUS' for info.");
  queue_statustext("ESP32-S3 WiFi Bridge Ready");
}

void loop() {
  static unsigned long apAutoStart = 0;
  if (heartbeat_received) { apAutoStart = millis(); }
  else if (!wifiOn && apAutoStart == 0) { apAutoStart = millis(); }
  else if (!heartbeat_received && !wifiOn && millis() - apAutoStart > 10000) {
    Serial.println("No FC heartbeat - starting AP for config");
    wifiActivate();
    apAutoStart = millis();
  }
  handleTerminalConfig();

  if (wifiOn) {
    bridgeWiFiToFC();
  }
  bridgeFCtoWiFi();

  unsigned long now = millis();
  if (now - last_wifi_hb >= 1000)       { send_heartbeat(); send_queued_statustext(); last_wifi_hb = now; }

  static unsigned long lastBootPress = 0;
  if (!digitalRead(BOOT_BTN) && now - lastBootPress > 500) {
    delay(50);
    if (!digitalRead(BOOT_BTN)) {
      if (wifiOn) wifiDeactivate(); else wifiActivate();
      lastBootPress = now;
      Serial.printf("WiFi MANUAL %s\n", wifiOn ? "ON" : "OFF");
    }
  }

  if (heartbeat_received && !missionFirstParsed) {
    if (lastMissionReq == 0 && now - start_time > 2000) {
      mission_count = 0;
      mission_loaded = false;
      send_mission_request_list();
      lastMissionReq = now;
      Serial.println("Mission scan started");
    } else if (lastMissionReq && now - lastMissionReq > 5000) {
      missionFirstParsed = true;
      lastMissionReq = 0;
      Serial.println("Mission scan done");
    }
  }

  if (failsafePending) {
    unsigned long fn = millis();
    if (failsafeStep == 0) {
      sendMavlinkForceDisarm(); failsafeStep = 1; failsafeTime = fn;
    } else if (failsafeStep == 1 && fn - failsafeTime >= 25) {
      sendMavlinkSetRelay(); failsafeStep = 2; failsafeTime = fn;
    } else if (failsafeStep == 2 && fn - failsafeTime >= 50) {
      failsafePending = false;
      Serial.println("[FAILSAFE] Complete");
    }
  }

  bootSequence();
  mdFly();

  if (wifiActivating && WiFi.status() == WL_CONNECTED) {
    wifiActivating = false;
    staRetryCount = 0;
    staWasConnected = true;
    Serial.printf("STA Connected successfully! IP: %s\n", WiFi.localIP().toString().c_str());
    queue_statustext("WiFi STA OK");
  }



  if (wifiActivating && now - wifiActivateTime > 20000) {
    staRetryCount++;
    if (staRetryCount <= 10) {
      Serial.printf("STA connection timeout. Retry %d/10...\n", staRetryCount);
      WiFi.begin(cfg.sta_ssid, cfg.sta_pass);
      wifiActivateTime = now;
    } else {
      wifiActivating = false;
      Serial.println("STA connection failed after 10 retries. Sleeping.");
    }
  }

  if (staWasConnected && WiFi.status() != WL_CONNECTED && wifiOn && now - last_sta_reconnect >= 15000) {
    last_sta_reconnect = now;
    WiFi.reconnect();
    Serial.println("Link lost. Reconnecting to router...");
  }

  updateLED();

  int st = WiFi.status();
  if (st != last_sta_status && wifiOn) {
    last_sta_status = st;
    if (st == WL_CONNECTED) {
      staWasConnected = true;
      queue_statustext(String("STA IP: " + WiFi.localIP().toString()).c_str());
    } else if (st == WL_CONNECT_FAILED)
      queue_statustext("STA FAIL: check password");
    else if (st == WL_NO_SSID_AVAIL)
      queue_statustext("STA FAIL: no SSID found");
  }

  if (now - last_serial_log >= 5000) {
    Serial.printf("hb=%d arm=%d mode=%d wifi_power=%d sta_status=%d wa=%d fc_bytes=%u fc_msgs=%u\n",
                  heartbeat_received, is_armed, current_custom_mode, wifiOn, WiFi.status(), wifiActivating, fc_bytes, fc_msgs);
    last_serial_log = now;
  }

  delay(2);
} 
