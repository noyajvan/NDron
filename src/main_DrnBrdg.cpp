#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Preferences.h>

#include <ardupilotmega/mavlink.h>
#include <Adafruit_NeoPixel.h>
#include <driver/uart.h>
#include <driver/gpio.h>

// ============================================================
//                      ПІНИ ТА КОНСТАНТИ
// ============================================================
#define LED_PIN         48
#define NUM_LEDS        1
#define BOOT_BTN        0
#define FC_UART_NUM     0
#define FC_RX_BUF       16384
#define FC_TX_BUF       4096
#define BRIDGE_BUF_SIZE 2048
#define UDP_PORT        14550

// --- Кольори RGB (NeoPixel GRB) ---
#define LED_OFF         0x000000
#define LED_WHITE       0xFFFFFF
#define LED_CYAN        0x00FFFF   // ГОЛУБОЙ
#define LED_BLUE        0x0000FF   // СИНИЙ
#define LED_PALE_LILAC  0xCC99FF   // БЛЕДНО-СИРЕНЕВЫЙ
#define LED_LILAC       0x8000FF   // СИРЕНЕВЫЙ
#define LED_PURPLE      0x800080   // ФИОЛЕТОВЫЙ
#define LED_MAGENTA     0xFF00FF   // ЯРКО-РОЗОВЫЙ
#define LED_YELLOW      0xFFFF00   // ЖЕЛТЫЙ
#define LED_ORANGE      0xFFA500   // ОРАНЖЕВЫЙ
#define LED_GREEN       0x00FF00   // ЗЕЛЕНЫЙ
#define LED_RED         0xFF0000   // КРАСНЫЙ
#define LED_CHERRY_D    0xFF0040   // ТЕМНО-ВИШНЕВЫЙ
#define LED_CHERRY_L    0xFF4080   // СВЕТЛО-ВИШНЕВЫЙ

// --- EKF флаги ---
#define EKF_ATTITUDE           0x01

// --- MAVLink ---
#define COMP_ID         MAV_COMP_ID_ONBOARD_COMPUTER
#define MODE_STABILIZE  0
#define MODE_AUTO       3

// --- Таймаути (мс) ---
#define WIFI_TIMEOUT_MS         60000
#define ARM_RETRY_INTERVAL_MS   5000
#define MODE_RETRY_INTERVAL_MS  10000
#define REASON_REPORT_MS        30000

// --- Команди ArduPilot (якщо не визначені в заголовках) ---
#ifndef MAV_CMD_DO_START_MAG_CAL
#define MAV_CMD_DO_START_MAG_CAL 42424
#endif

// ============================================================
//                     СТАНИ МАШИНИ (FSM)
// ============================================================
enum SystemState : uint8_t {
  STATE_INIT_WIFI       = 1,
  STATE_INIT_MAVLINK    = 2,
  STATE_MAG_ERROR       = 3,
  STATE_MAG_OK          = 4,
  STATE_CALIBRATION     = 5,
  STATE_CALIBRATION_END = 6,
  STATE_NO_ARM          = 7,
  STATE_ARMING          = 8,
  STATE_ARMED           = 9,
  STATE_START_MISSION   = 10,
  STATE_MISSION         = 11,
  STATE_RELAY_CONTROL   = 12
};

// ============================================================
//                    КОНФІГУРАЦІЯ
// ============================================================
struct Config {
  char     sta_ssid[32]  = "LEO";
  char     sta_pass[64]  = "88888888";
  uint32_t baud          = 921600;
  uint8_t  wifi_boot     = 1;
  uint8_t  sys_id        = 1;
} cfg;

// ============================================================
//                   ГЛОБАЛЬНІ ОБ'ЄКТИ
// ============================================================
Adafruit_NeoPixel pixels(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);
WiFiUDP udp;
IPAddress gcsIP = IPAddress(152, 70, 51, 224);
uint16_t gcsPort = UDP_PORT;
bool gcsIPSet = true;

// --- Глобальні змінні зв'язку ---
bool hasWifi = false;
bool hasServer = false;
int  mdfly  = 0;

// --- WiFi ---
bool wifiOn = false;
bool wifiActivating = false;
uint8_t wifiTryIdx = 0;
unsigned long wifiTryStart = 0;
bool staWasConnected = false;
int last_sta_status = -1;

// --- MAVLink буфери ---
mavlink_message_t mavMsg;
mavlink_status_t  mavStatus;
uint8_t bridgeBuf[BRIDGE_BUF_SIZE];
mavlink_message_t txMsg;
uint8_t txBuf[MAVLINK_MAX_PACKET_LEN];

// --- Стан польотного контролера ---
SystemState state = STATE_INIT_WIFI;
bool heartbeat_received = false;
uint32_t current_custom_mode = 0;
bool is_armed = false;
uint8_t system_status = 0;
uint8_t gps_fix_type = 0;
uint8_t gps_sats = 0;
float   battery_voltage = 0.0f;
int8_t  battery_remaining = -1;
bool    sys_status_received = false;
float roll_deg = 0.0f, pitch_deg = 0.0f;
uint8_t fc_sys_id = 1; // system_id FC, дістаємо з хартбіту

// --- EKF / Компас ---
uint16_t ekf_flags = 0;
float mag_test_ratio = 0.0f;
bool ekf_report_received = false;

// --- Детекція обертання >30° ---
float rot_snap_roll = 0.0f, rot_snap_pitch = 0.0f;
bool rot_detected = false;

// --- Прапорці одноразових дій ---
bool mag_error_msg_sent = false;
bool mag_ok_msg_sent   = false;
bool cal_cmd_sent      = false;
bool cal_success       = false;
bool cal_finalized     = false;
uint8_t cal_completion_pct = 0;
bool no_arm_init       = false;
bool arm_cmd_sent      = false;
bool mode_cmd_sent     = false;
bool mission_start_msg = false;
bool was_in_auto = false;

// --- Місія ---
uint16_t mission_count = 0;
bool mission_loaded = false;
bool missionFirstParsed = false;
unsigned long lastMissionReq = 0;

// --- Таймери ---
unsigned long start_time = 0;
unsigned long last_wifi_hb = 0;
unsigned long last_serial_log = 0;
unsigned long last_sta_reconnect = 0;
unsigned long recon_phase_start = 0;
unsigned long state_entry_ms = 0;
unsigned long last_arm_retry_ms   = 0;
unsigned long last_mode_retry_ms  = 0;
unsigned long last_reason_report_ms = 0;
unsigned long last_server_pkt_ms  = 0;

// --- Статусна черга ---
#define STATUS_QUEUE_SIZE 16
char status_queue[STATUS_QUEUE_SIZE][72];
uint8_t q_write = 0, q_read = 0;

// --- Черга STATUSTEXT від полетника ---
#define REASON_QUEUE_SIZE 12
char reason_queue[REASON_QUEUE_SIZE][72];
uint8_t rq_write = 0, rq_read = 0;

// --- Термінал ---
String inputBuffer = "";

// ============================================================
//               ДОПОМІЖНІ ФУНКЦІЇ LED / КОЛЬОРИ
// ============================================================
void setLed(uint32_t c) {
  pixels.setPixelColor(0, c);
  pixels.show();
}

uint32_t getWifiCoColor() {
  if (!wifiOn) return LED_OFF;
  if (!hasWifi) return LED_WHITE;
  if (!hasServer) return LED_CYAN;
  return LED_BLUE;
}

uint32_t getMavCoColor() {
  switch (state) {
    case STATE_INIT_MAVLINK:
      if (!heartbeat_received) return getWifiCoColor();
      return LED_LILAC;
    case STATE_MAG_ERROR:       return LED_CHERRY_D;
    case STATE_MAG_OK:          return LED_MAGENTA;
    case STATE_CALIBRATION:     return LED_MAGENTA;
    case STATE_CALIBRATION_END: return LED_OFF;
    case STATE_NO_ARM:
    case STATE_ARMING:          return LED_YELLOW;
    case STATE_ARMED:
    case STATE_START_MISSION:   return LED_GREEN;
    case STATE_MISSION:         return LED_GREEN;
    case STATE_RELAY_CONTROL:   return LED_RED;
    default: return LED_OFF;
  }
}

void updateLED() {
  if (mdfly == 60) { setLed(LED_OFF); return; }

  // Стан 11 (MISSION) — зелений постійно
  if (state == STATE_MISSION) { setLed(LED_GREEN); return; }
  // Стан 6 (CALIBRATION_END) — вимкнено
  if (state == STATE_CALIBRATION_END) { setLed(LED_OFF); return; }

  // Стани 3,4 (MAG_ERROR, MAG_OK) — 0.25 с ON / 1.00 с OFF
  if (state == STATE_MAG_ERROR || state == STATE_MAG_OK) {
    setLed((millis() % 1250) < 250 ? getMavCoColor() : LED_OFF);
    return;
  }

  // Стан 5 (CALIBRATION) — 0.25 с ON / 0.25 с OFF
  if (state == STATE_CALIBRATION) {
    setLed((millis() % 500) < 250 ? LED_MAGENTA : LED_OFF);
    return;
  }

  // Усі інші стани: 500 мс ON / 500 мс OFF
  bool onPhase = (millis() % 1000) < 500;

  if (state == STATE_INIT_WIFI) {
    // Стан 1: ON-фаза показує колір wifi_co
    setLed(onPhase ? getWifiCoColor() : LED_OFF);
  } else {
    // Стани 2-12: ON-фаза показує колір mav_co
    setLed(onPhase ? getMavCoColor() : LED_OFF);
  }
}

// ============================================================
//                  WiFi  КЕРУВАННЯ
// ============================================================
void tryNextWifi();
void queue_statustext(const char* text);

void wifiActivate() {
  if (wifiOn) return;
  wifiOn = true;
  hasWifi = false;
  hasServer = false;
  WiFi.disconnect(false);
  delay(50);
  WiFi.mode(WIFI_STA);
  delay(50);
  WiFi.setTxPower(WIFI_POWER_2dBm);
  WiFi.setSleep(true);
  udp.begin(UDP_PORT);
  wifiTryIdx = 0;
  tryNextWifi();
}

void wifiDeactivate() {
  if (!wifiOn) return;
  wifiOn = false;
  wifiActivating = false;
  wifiTryIdx = 0;
  staWasConnected = false;
  hasWifi = false;
  hasServer = false;
  udp.stop();
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  queue_statustext("WiFi OFF");
}

void wifiFullRestart() {
  if (!wifiOn) return;
  queue_statustext("WiFi restart");
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  delay(100);
  WiFi.mode(WIFI_STA);
  delay(50);
  WiFi.setTxPower(WIFI_POWER_2dBm);
  WiFi.setSleep(true);
  udp.begin(UDP_PORT);
  wifiTryIdx = 0;
  staWasConnected = false;
  hasWifi = false;
  tryNextWifi();
}

void tryNextWifi() {
  WiFi.begin(cfg.sta_ssid, cfg.sta_pass);
  wifiActivating = true;
  wifiTryStart = millis();
}

// ============================================================
//                  UART / FC  ЗВ'ЯЗОК
// ============================================================
void fcBegin(int baud, int rx, int tx) {
  uart_config_t uart_config = {
    .baud_rate = (int)baud,
    .data_bits = UART_DATA_8_BITS,
    .parity    = UART_PARITY_DISABLE,
    .stop_bits = UART_STOP_BITS_1,
    .flow_ctrl = UART_HW_FLOWCTRL_DISABLE
  };
  uart_param_config((uart_port_t)FC_UART_NUM, &uart_config);
  uart_set_pin((uart_port_t)FC_UART_NUM, tx, rx, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
  uart_driver_install((uart_port_t)FC_UART_NUM, FC_RX_BUF, FC_TX_BUF, 0, NULL, 0);
}

size_t fcAvailable() {
  size_t len;
  uart_get_buffered_data_len((uart_port_t)FC_UART_NUM, &len);
  return len;
}

void fcWrite(const uint8_t* d, size_t len) {
  uart_write_bytes((uart_port_t)FC_UART_NUM, d, len);
}

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

// ============================================================
//               MAVLink  ВІДПРАВЛЕННЯ
// ============================================================
void send_statustext(const char* text) {
  mavlink_msg_statustext_pack(cfg.sys_id, COMP_ID, &txMsg,
      MAV_SEVERITY_INFO, text, 0, 0);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void queue_statustext(const char* text) {
  uint8_t next = (q_write + 1) % STATUS_QUEUE_SIZE;
  if (next == q_read) q_read = (q_read + 1) % STATUS_QUEUE_SIZE;
  strncpy(status_queue[q_write], text, 71);
  status_queue[q_write][71] = '\0';
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
  mavlink_msg_heartbeat_pack(cfg.sys_id, COMP_ID, &txMsg,
      MAV_TYPE_ONBOARD_CONTROLLER, MAV_AUTOPILOT_INVALID, 0, 0, 0);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  sendToBoth(txBuf, len);
}

void send_mission_request_list() {
  mavlink_msg_mission_request_list_pack(cfg.sys_id, COMP_ID, &txMsg,
      fc_sys_id, MAV_COMP_ID_AUTOPILOT1, MAV_MISSION_TYPE_MISSION);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void send_mission_request_int(uint16_t seq) {
  mavlink_msg_mission_request_int_pack(cfg.sys_id, COMP_ID, &txMsg,
      fc_sys_id, MAV_COMP_ID_AUTOPILOT1, seq, MAV_MISSION_TYPE_MISSION);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void send_command_long(uint16_t cmd, float p1, float p2, float p3,
                       float p4, float p5, float p6, float p7) {
  mavlink_msg_command_long_pack(cfg.sys_id, COMP_ID, &txMsg,
      fc_sys_id, MAV_COMP_ID_AUTOPILOT1, cmd, 0,
      p1, p2, p3, p4, p5, p6, p7);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void sendMavlinkArm() {
  send_command_long(MAV_CMD_COMPONENT_ARM_DISARM, 1.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
}

void sendMavlinkForceDisarm() {
  send_command_long(MAV_CMD_COMPONENT_ARM_DISARM, 0.0f, 21196.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
}

void sendMavlinkSetMode(uint8_t mode) {
  send_command_long(MAV_CMD_DO_SET_MODE,
                    MAV_MODE_FLAG_CUSTOM_MODE_ENABLED, mode,
                    0, 0, 0, 0, 0);
}

void sendMavlinkSetRelay() {
  send_command_long(MAV_CMD_DO_SET_RELAY, 0.0f, 1.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
}

void sendStartMagCal() {
  send_command_long(MAV_CMD_DO_START_MAG_CAL, 0.0f, 0.0f,
                    1.0f, 0.0f, 0.0f, 0.0f, 0.0f);
}

void sendAcceptMagCal() {
  send_command_long(MAV_CMD_DO_ACCEPT_MAG_CAL, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
}

void sendPreflightStorage() {
  send_command_long(MAV_CMD_PREFLIGHT_STORAGE, 1.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
}

// ============================================================
//               MAVLink  ПРИЙОМ / ОБРОБКА
// ============================================================
static bool crash_triggered = false;

void handle_mavlink_message(mavlink_message_t* msg) {
  switch (msg->msgid) {

    case MAVLINK_MSG_ID_HEARTBEAT: {
      mavlink_heartbeat_t hb;
      mavlink_msg_heartbeat_decode(msg, &hb);
      fc_sys_id = msg->sysid;
      heartbeat_received = true;
      current_custom_mode = hb.custom_mode;
      system_status = hb.system_status;
      is_armed = (hb.base_mode & MAV_MODE_FLAG_SAFETY_ARMED) != 0;

      static bool first_hb = true;
      if (first_hb) {
        first_hb = false;
        queue_statustext("Mavlink OK");
        send_command_long(MAV_CMD_SET_MESSAGE_INTERVAL,
                          MAVLINK_MSG_ID_ATTITUDE, 100000, 0, 0, 0, 0, 0);
        send_command_long(MAV_CMD_SET_MESSAGE_INTERVAL,
                          MAVLINK_MSG_ID_RAW_IMU, 100000, 0, 0, 0, 0, 0);
        send_command_long(MAV_CMD_SET_MESSAGE_INTERVAL,
                          MAVLINK_MSG_ID_EKF_STATUS_REPORT, 200000, 0, 0, 0, 0, 0);
        send_command_long(MAV_CMD_SET_MESSAGE_INTERVAL,
                          MAVLINK_MSG_ID_VFR_HUD, 200000, 0, 0, 0, 0, 0);
        send_command_long(MAV_CMD_SET_MESSAGE_INTERVAL,
                          MAVLINK_MSG_ID_GPS_RAW_INT, 200000, 0, 0, 0, 0, 0);
        send_command_long(MAV_CMD_SET_MESSAGE_INTERVAL,
                          MAVLINK_MSG_ID_SYS_STATUS, 200000, 0, 0, 0, 0, 0);
      }
      break;
    }

    case MAVLINK_MSG_ID_ATTITUDE: {
      mavlink_attitude_t att;
      mavlink_msg_attitude_decode(msg, &att);
      roll_deg  = att.roll  * 57.2958f;
      pitch_deg = att.pitch * 57.2958f;
      static uint32_t lastAttDbg = 0;
      if (millis() - lastAttDbg > 5000) {
        lastAttDbg = millis();
        Serial.printf("[ATT] r=%.1f p=%.1f\n", roll_deg, pitch_deg);
      }
      break;
    }

    case MAVLINK_MSG_ID_RAW_IMU: {
      mavlink_raw_imu_t imu;
      mavlink_msg_raw_imu_decode(msg, &imu);
      // Краш-детекція (перекидання)
      static unsigned long gyro_crash_timer = 0;
      if (!crash_triggered && is_armed) {
        if (abs(imu.xgyro) > 4500 || abs(imu.ygyro) > 4500 || abs(imu.zgyro) > 4500) {
          if (gyro_crash_timer == 0) gyro_crash_timer = millis();
          if (millis() - gyro_crash_timer > 150) {
            crash_triggered = true;
            sendMavlinkForceDisarm();
            queue_statustext("Crash: tumble");
          }
        } else {
          gyro_crash_timer = 0;
        }
      }
      break;
    }

    case MAVLINK_MSG_ID_VFR_HUD: {
      mavlink_vfr_hud_t vfr;
      mavlink_msg_vfr_hud_decode(msg, &vfr);
      // Краш-детекція (застрягання)
      static float last_alt = 0.0f;
      static unsigned long stuck_timer = 0;
      static bool wasFlying = false;
      if (is_armed && vfr.alt > 2.0f) wasFlying = true;
      if (!crash_triggered && wasFlying && is_armed) {
        if (fabs(vfr.alt - last_alt) < 0.15f && vfr.groundspeed < 0.15f) {
          if (stuck_timer == 0) stuck_timer = millis();
          if (millis() - stuck_timer > 3000 && vfr.throttle > 45) {
            crash_triggered = true;
            sendMavlinkForceDisarm();
            queue_statustext("Crash: stuck");
          }
        } else {
          stuck_timer = 0;
          last_alt = vfr.alt;
        }
      }
      break;
    }

    case MAVLINK_MSG_ID_EKF_STATUS_REPORT: {
      mavlink_ekf_status_report_t ekf;
      mavlink_msg_ekf_status_report_decode(msg, &ekf);
      ekf_flags = ekf.flags;
      mag_test_ratio = ekf.compass_variance;
      ekf_report_received = true;
      break;
    }

    case MAVLINK_MSG_ID_MISSION_COUNT: {
      mavlink_mission_count_t mc;
      mavlink_msg_mission_count_decode(msg, &mc);
      mission_count = mc.count;
      mission_loaded = false;
      if (mission_count > 0) {
        send_mission_request_int(0);
      } else {
        missionFirstParsed = true;
        lastMissionReq = 0;
      }
      break;
    }

    case MAVLINK_MSG_ID_MISSION_ITEM_INT: {
      mavlink_mission_item_int_t item;
      mavlink_msg_mission_item_int_decode(msg, &item);
      if (item.seq + 1 < mission_count) {
        send_mission_request_int(item.seq + 1);
      } else {
        mission_loaded = true;
        missionFirstParsed = true;
        lastMissionReq = 0;
      }
      break;
    }

    case MAVLINK_MSG_ID_SYS_STATUS: {
      mavlink_sys_status_t st;
      mavlink_msg_sys_status_decode(msg, &st);
      battery_voltage = st.voltage_battery * 0.001f;
      battery_remaining = st.battery_remaining;
      sys_status_received = true;
      break;
    }

    case MAVLINK_MSG_ID_GPS_RAW_INT: {
      mavlink_gps_raw_int_t gps;
      mavlink_msg_gps_raw_int_decode(msg, &gps);
      gps_fix_type = gps.fix_type;
      gps_sats = gps.satellites_visible;
      break;
    }

    case MAVLINK_MSG_ID_MAG_CAL_PROGRESS: {
      mavlink_mag_cal_progress_t c;
      mavlink_msg_mag_cal_progress_decode(msg, &c);
      cal_completion_pct = c.completion_pct;
      // MAG_CAL_STATUS з common.xml: 0=NOT_STARTED,1=WAITING,2=STEP1,3=STEP2,
      //   4=SUCCESS,5=FAILED,6=BAD_ORIENT,7=BAD_RADIUS
      // SUCCESS, BAD_ORIENT, BAD_RADIUS — усі фінальні (autosave зберігає дані)
      cal_success = (c.cal_status == 4 || c.cal_status == 6 || c.cal_status == 7);
      static uint8_t last_status = 255;
      if (c.cal_status != last_status) {
        last_status = c.cal_status;
        Serial.printf("[CAL] pct=%u status=%d succ=%d\n", c.completion_pct, c.cal_status, cal_success);
      }
      break;
    }

    case MAVLINK_MSG_ID_STATUSTEXT: {
      mavlink_statustext_t stxt;
      mavlink_msg_statustext_decode(msg, &stxt);
      // Збираємо повідомлення з важливістю <= WARNING
      if (stxt.severity <= MAV_SEVERITY_WARNING) {
        uint8_t next = (rq_write + 1) % REASON_QUEUE_SIZE;
        if (next == rq_read) rq_read = (rq_read + 1) % REASON_QUEUE_SIZE;
        strncpy(reason_queue[rq_write], stxt.text, 71);
        reason_queue[rq_write][71] = '\0';
        rq_write = next;
      }
      break;
    }

    default:
      break;
  }
}

// ============================================================
//                  МІСТ FC ↔ WiFi
// ============================================================
uint32_t fc_bytes = 0, fc_msgs = 0;

void bridgeFCtoWiFi() {
  for (int pass = 0; pass < 4; pass++) {
    size_t avail = fcAvailable();
    if (avail == 0) break;
    size_t n = min(avail, (size_t)BRIDGE_BUF_SIZE);
    n = uart_read_bytes((uart_port_t)FC_UART_NUM, bridgeBuf, n, 0);
    if (n == 0) break;
    fc_bytes += n;
    forwardToWiFi(bridgeBuf, n);
    for (size_t i = 0; i < n; i++) {
      if (mavlink_parse_char(MAVLINK_COMM_0, bridgeBuf[i], &mavMsg, &mavStatus)) {
        fc_msgs++;
        handle_mavlink_message(&mavMsg);
      }
    }
  }
}

void bridgeWiFiToFC() {
  if (!wifiOn || WiFi.status() != WL_CONNECTED) return;
  int sz = udp.parsePacket();
  if (sz > 0) {
    last_server_pkt_ms = millis();
    if (!hasServer) {
      hasServer = true;
      static unsigned long last_do_connect_msg = 0;
      if (millis() - last_do_connect_msg > 60000) {
        queue_statustext("DO connected");
        last_do_connect_msg = millis();
      }
    }
    int n = udp.read(bridgeBuf, sizeof(bridgeBuf));
    if (n <= 0) return;
    fcWrite(bridgeBuf, n);
  }
}

// ============================================================
//                    ТЕРМІНАЛ (UART0)
// ============================================================
void loadConfig() {
  Preferences p;
  p.begin("dbridge", true);
  p.getString("ssid", cfg.sta_ssid, sizeof(cfg.sta_ssid));
  p.getString("pass", cfg.sta_pass, sizeof(cfg.sta_pass));
  cfg.baud      = p.getUInt("baud", 921600);
  cfg.wifi_boot = p.getUInt("wifi_boot", 1);
  cfg.sys_id    = p.getUInt("sys_id", 1);
  p.end();
}

void saveConfig() {
  Preferences p;
  p.begin("dbridge", false);
  p.putString("ssid", cfg.sta_ssid);
  p.putString("pass", cfg.sta_pass);
  p.putUInt("baud", cfg.baud);
  p.putUInt("wifi_boot", cfg.wifi_boot);
  p.putUInt("sys_id", cfg.sys_id);
  p.end();
}

void handleTerminalConfig() {
  while (Serial.available() > 0) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (inputBuffer.length() > 0) {
        inputBuffer.trim();

        if (inputBuffer.equalsIgnoreCase("STATUS")) {
          Serial.println("\n--- STATUS ---");
          Serial.printf("State: %d  mdfly: %d\n", state, mdfly);
          Serial.printf("WiFi: %s  IP: %s\n",
              (WiFi.status() == WL_CONNECTED) ? "OK" : "NO",
              WiFi.localIP().toString().c_str());
          Serial.printf("hasServer: %d  hb: %d  armed: %d  mode: %u\n",
              hasServer, heartbeat_received, is_armed, current_custom_mode);
          Serial.printf("EKF: 0x%04X  mag: %.3f  mission: %d/%d\n",
              ekf_flags, mag_test_ratio, mission_loaded, mission_count);
          Serial.printf("FC: %u bytes  %u msgs\n", fc_bytes, fc_msgs);
        }
        else if (inputBuffer.startsWith("SSID=")) {
          strncpy(cfg.sta_ssid, inputBuffer.substring(5).c_str(), sizeof(cfg.sta_ssid) - 1);
          Serial.printf("SSID=%s (SAVE to store)\n", cfg.sta_ssid);
        }
        else if (inputBuffer.startsWith("PASS=")) {
          strncpy(cfg.sta_pass, inputBuffer.substring(5).c_str(), sizeof(cfg.sta_pass) - 1);
          Serial.println("PASS set (SAVE to store)");
        }
        else if (inputBuffer.startsWith("BAUD=")) {
          uint32_t b = inputBuffer.substring(5).toInt();
          if (b >= 9600 && b <= 921600) {
            cfg.baud = b;
            Serial.printf("BAUD=%u (SAVE to store)\n", cfg.baud);
          } else Serial.println("Invalid baud");
        }
        else if (inputBuffer.equalsIgnoreCase("WIFI=1")) {
          if (!wifiOn) wifiActivate();
          cfg.wifi_boot = 1;
          Serial.println("WiFi ON");
        }
        else if (inputBuffer.equalsIgnoreCase("WIFI=0")) {
          if (wifiOn) wifiDeactivate();
          cfg.wifi_boot = 0;
          Serial.println("WiFi OFF");
        }
        else if (inputBuffer.equalsIgnoreCase("RELAY")) {
          sendMavlinkSetRelay();
          Serial.println("RELAY sent");
        }
        else if (inputBuffer.equalsIgnoreCase("DISARM")) {
          sendMavlinkForceDisarm();
          Serial.println("DISARM sent");
        }
        else if (inputBuffer.startsWith("SYSID=")) {
          uint8_t sid = inputBuffer.substring(6).toInt();
          if (sid >= 1 && sid <= 255) {
            cfg.sys_id = sid;
            Serial.printf("SYSID=%u (SAVE to store)\n", cfg.sys_id);
          }
        }
        else if (inputBuffer.equalsIgnoreCase("SAVE")) {
          saveConfig();
          Serial.println("Saved. Rebooting...");
          delay(500);
          ESP.restart();
        }
        else {
          Serial.println("CMD: STATUS SSID= PASS= BAUD= SYSID= WIFI=0/1 RELAY DISARM SAVE");
        }
        inputBuffer = "";
      }
    } else {
      inputBuffer += c;
    }
  }
}

// ============================================================
//            ГОЛОВНА ФУНКЦІЯ МАШИНИ СТАНІВ (FSM)
// ============================================================
void updateSystemState() {
  unsigned long now = millis();

  // ====== ОНОВЛЕННЯ hasWifi ======
  hasWifi = wifiOn && (WiFi.status() == WL_CONNECTED);

  // ====== ТАЙМАУТ WiFi: 1 хвилина без STA — вимкнути ======
  if (!staWasConnected && wifiOn && (now - start_time > WIFI_TIMEOUT_MS)) {
    wifiDeactivate();
  }

  // ====== БЛОКУВАННЯ mdfly: заморожує переходи, міст/LED працюють ======
  if (mdfly == 60) return;

  // ====== ЗАПИТ МІСІЇ (фоновий) ======
  if (heartbeat_received && !missionFirstParsed) {
    if (lastMissionReq == 0 && now - start_time > 2000) {
      mission_count = 0;
      mission_loaded = false;
      send_mission_request_list();
      lastMissionReq = now;
    } else if (lastMissionReq && now - lastMissionReq > 5000) {
      missionFirstParsed = true;
      lastMissionReq = 0;
    }
  }

  // ====== СКИДАННЯ ПРАПОРЦІВ ПРИ ЗМІНІ СТАНУ ======
  static SystemState lastState = STATE_INIT_WIFI;
  if (state != lastState) {
    state_entry_ms = now;
    crash_triggered = false;
    Serial.printf("[S] %d->%d\n", lastState, state);

    switch (state) {
      case STATE_MAG_ERROR:
        mag_error_msg_sent = false;
        rot_snap_roll  = roll_deg;
        rot_snap_pitch = pitch_deg;
        rot_detected = false;
        Serial.printf("--> MAG_ERROR snap R=%.1f P=%.1f, mag=%.3f\n", roll_deg, pitch_deg, mag_test_ratio);
        break;
      case STATE_MAG_OK:
        mag_ok_msg_sent = false;
        rot_snap_roll  = roll_deg;
        rot_snap_pitch = pitch_deg;
        rot_detected = false;
        Serial.printf("--> MAG_OK snap R=%.1f P=%.1f, mag=%.3f\n", roll_deg, pitch_deg, mag_test_ratio);
        break;
      case STATE_CALIBRATION:
        cal_cmd_sent = false;
        cal_success = false;
        cal_completion_pct = 0;
        break;
      case STATE_CALIBRATION_END:
        cal_finalized = false;
        break;
      case STATE_NO_ARM:
        no_arm_init = false;
        break;
      case STATE_ARMING:
        arm_cmd_sent = false;
        last_arm_retry_ms = 0;
        break;
      case STATE_START_MISSION:
        mode_cmd_sent = false;
        last_mode_retry_ms = 0;
        break;
      case STATE_MISSION:
        mission_start_msg = false;
        was_in_auto = false;
        break;
      default:
        break;
    }
    lastState = state;
  }

  // ====== МАШИНА СТАНІВ ======
  switch (state) {

    // --------------------------------------------------
    //  STATE 1: INIT_WIFI — Старт (2с показує статус WiFi, потім іде далі)
    // --------------------------------------------------
    case STATE_INIT_WIFI: {
      if (state_entry_ms == 0) state_entry_ms = now;
      if (now - state_entry_ms > 2000) {
        state = STATE_INIT_MAVLINK;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 2: INIT_MAVLINK — Очікування MAVLink та EKF
    // --------------------------------------------------
    case STATE_INIT_MAVLINK: {
      if (!heartbeat_received) break;
      if (!ekf_report_received) break;

      bool ekf_ok = ((ekf_flags & EKF_ATTITUDE) != 0);

      static bool ekf_ok_prev = false;
      static unsigned long ekf_att_stable_ms = 0;
      static bool mag_nonzero_ms = false;
      static unsigned long mag_nonzero_at = 0;
      if (ekf_ok != ekf_ok_prev) {
        ekf_ok_prev = ekf_ok;
        if (ekf_ok) {
          ekf_att_stable_ms = now;
          mag_nonzero_ms = false;
          mag_nonzero_at = 0;
        }
        Serial.printf("[EKF] flags=0x%04X att=%d mag=%.3f\n",
                       ekf_flags, ekf_ok, mag_test_ratio);
      }

      if (!ekf_ok) break;

      // Чекаємо 5с після EKF_ATTITUDE
      if (now - ekf_att_stable_ms < 5000) break;

      // Ловимо момент першого ненульового mag_test_ratio
      if (!mag_nonzero_ms && mag_test_ratio > 0.001f) {
        mag_nonzero_ms = true;
        mag_nonzero_at = now;
        Serial.printf("[MAG] non-zero at %.3f\n", mag_test_ratio);
      }

      if (mag_nonzero_ms && now - mag_nonzero_at >= 20000) {
        if (mag_test_ratio > 0.5f) {
          state = STATE_MAG_ERROR;
        } else {
          state = STATE_MAG_OK;
        }
        break;
      }

      // Таймаут 60с — mag так і не став ненульовим, вважаємо OK
      if (!mag_nonzero_ms && now - ekf_att_stable_ms > 60000) {
        state = STATE_MAG_OK;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 3: MAG_ERROR — Компас поганий
    // --------------------------------------------------
    case STATE_MAG_ERROR: {
      if (!mag_error_msg_sent) {
        char buf[72];
        snprintf(buf, sizeof(buf), "mag_test_ratio = %.3f — калібровка", mag_test_ratio);
        queue_statustext(buf);
        send_statustext(buf);
        mag_error_msg_sent = true;
      }

      state = STATE_CALIBRATION;
      break;
    }

    // --------------------------------------------------
    //  STATE 4: MAG_OK — Компас у нормі
    // --------------------------------------------------
    case STATE_MAG_OK: {
      if (!mag_ok_msg_sent) {
        char buf[72];
        snprintf(buf, sizeof(buf), "mag_test_ratio = %.3f — чекаю обертання", mag_test_ratio);
        queue_statustext(buf);
        send_statustext(buf);
        mag_ok_msg_sent = true;
      }

      if (fabs(roll_deg - rot_snap_roll) > 30.0f ||
          fabs(pitch_deg - rot_snap_pitch) > 30.0f) {
        rot_detected = true;
        Serial.printf("[ROT] MAG_OK dR=%.1f dP=%.1f!\n",
                      roll_deg - rot_snap_roll, pitch_deg - rot_snap_pitch);
      }

      if (rot_detected) {
        state = STATE_CALIBRATION;
        break;
      }

      // Якщо 10 секунд немає обертання — переходимо до NO_ARM
      if (now - state_entry_ms > 10000) {
        queue_statustext("Компас ок, обертання нема -> NO_ARM");
        state = STATE_NO_ARM;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 5: CALIBRATION — Режим калібровки
    // --------------------------------------------------
    case STATE_CALIBRATION: {
      if (!cal_cmd_sent) {
        sendStartMagCal();
        queue_statustext("Калібровка компаса запущена");
        cal_cmd_sent = true;
      }

      if (cal_success) {
        state = STATE_CALIBRATION_END;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 6: CALIBRATION_END — Фінал калібровки
    // --------------------------------------------------
    case STATE_CALIBRATION_END: {
      if (!cal_finalized) {
        sendAcceptMagCal();
        sendPreflightStorage();
        queue_statustext("Калібровка завершена");
        mdfly = 60;
        cal_finalized = true;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 7: NO_ARM — Очікування готовності до ARM
    // --------------------------------------------------
    case STATE_NO_ARM: {
      // Якщо магнітометр погіршився — назад у MAG_ERROR (чекає обертання вічно)
      if (mag_test_ratio > 0.5f) {
        state = STATE_MAG_ERROR;
        break;
      }

      if (!no_arm_init) {
        send_mission_request_list();
        queue_statustext("Перевірка готовності до ARM");
        no_arm_init = true;
      }

      // Звіт про статус — тільки при зміні
      static uint8_t  last_gps_fix = 255;
      static uint8_t  last_sats   = 255;
      static int8_t   last_bat_pct = -2;
      static bool     last_mis_loaded = false;
      if (now - last_reason_report_ms >= REASON_REPORT_MS) {
        last_reason_report_ms = now;
        while (rq_read != rq_write) {
          queue_statustext(reason_queue[rq_read]);
          rq_read = (rq_read + 1) % REASON_QUEUE_SIZE;
        }
        if (mission_loaded != last_mis_loaded) {
          last_mis_loaded = mission_loaded;
          queue_statustext(mission_loaded ? "Місію завантажено" : "Місію НЕ завантажено");
        }
        if (gps_fix_type != last_gps_fix || gps_sats != last_sats || battery_remaining != last_bat_pct) {
          last_gps_fix = gps_fix_type;
          last_sats = gps_sats;
          last_bat_pct = battery_remaining;
          char buf[48];
          snprintf(buf, sizeof(buf), "GPS:%d/%d", gps_fix_type, gps_sats);
          queue_statustext(buf);
          snprintf(buf, sizeof(buf), "Bat:%.1fV %d%%", battery_voltage, battery_remaining);
          queue_statustext(buf);
        }
      }

      bool gps_ok = (gps_fix_type >= 3);
      bool bat_ok = sys_status_received &&
                    (battery_voltage > 10.5f || battery_voltage < 0.01f);
      if (system_status == MAV_STATE_STANDBY && mission_loaded && gps_ok && bat_ok) {
        state = STATE_ARMING;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 8: ARMING — Авто-Армінг
    // --------------------------------------------------
    case STATE_ARMING: {
      // Якщо дрон роззброївся — повертаємось у NO_ARM
      if (!is_armed && now - state_entry_ms > 3000) {
        queue_statustext("Роззброєння — повернення в NO_ARM");
        state = STATE_NO_ARM;
        break;
      }
      if (!arm_cmd_sent || (now - last_arm_retry_ms >= ARM_RETRY_INTERVAL_MS)) {
        sendMavlinkArm();
        queue_statustext("Надсилаю команду ARM");
        arm_cmd_sent = true;
        last_arm_retry_ms = now;
      }

      if (is_armed) {
        state = STATE_ARMED;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 9: ARMED — Дрон заармлено
    // --------------------------------------------------
    case STATE_ARMED: {
      // Якщо дрон роззброївся — повертаємось у NO_ARM
      if (!is_armed) {
        queue_statustext("Роззброєння — повернення в NO_ARM");
        state = STATE_NO_ARM;
        break;
      }
      bool mission_ready = mission_loaded && missionFirstParsed;

      if (mission_ready) {
        state = STATE_START_MISSION;
      } else {
        if (now - last_reason_report_ms >= REASON_REPORT_MS) {
          last_reason_report_ms = now;
          while (rq_read != rq_write) {
            queue_statustext(reason_queue[rq_read]);
            rq_read = (rq_read + 1) % REASON_QUEUE_SIZE;
          }
          if (!mission_loaded) queue_statustext("Місія не завантажена");
        }
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 10: START_MISSION — Запуск місії
    // --------------------------------------------------
    case STATE_START_MISSION: {
      // Якщо дрон роззброївся — повертаємось у NO_ARM
      if (!is_armed) {
        queue_statustext("Роззброєння — повернення в NO_ARM");
        state = STATE_NO_ARM;
        break;
      }
      if (!mode_cmd_sent || (now - last_mode_retry_ms >= MODE_RETRY_INTERVAL_MS)) {
        sendMavlinkSetMode(MODE_AUTO);
        queue_statustext("Запуск місії (AUTO)");
        mode_cmd_sent = true;
        last_mode_retry_ms = now;
      }

      if (current_custom_mode == MODE_AUTO) {
        state = STATE_MISSION;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 11: MISSION — Місія виконується
    // --------------------------------------------------
    case STATE_MISSION: {
      if (!mission_start_msg) {
        queue_statustext("Режим AUTO — місія виконується");
        mission_start_msg = true;
      }

      // Вихід із AUTO під час польоту — місія завершена/перервана
      if (current_custom_mode == MODE_AUTO) was_in_auto = true;
      
      bool mission_ended = (was_in_auto && current_custom_mode != MODE_AUTO) || !is_armed;
      if (mission_ended || crash_triggered) {
        queue_statustext("Місія завершена. Реле.");
        state = STATE_RELAY_CONTROL;
      }
      break;
    }

    // --------------------------------------------------
    //  STATE 12: RELAY_CONTROL — Реле / Краш
    // --------------------------------------------------
    case STATE_RELAY_CONTROL: {
      static bool relay_sent = false;
      if (!relay_sent) {
        sendMavlinkSetRelay();
        queue_statustext("Relay ON");
        relay_sent = true;
      }
      break;
    }

    default:
      break;
  }
}

// ============================================================
//                      SETUP
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(100);

  setCpuFrequencyMhz(80);
  btStop();
  loadConfig();
  fcBegin(cfg.baud, 44, 43);
  delay(50);

  pixels.begin();
  pixels.setBrightness(5);
  pixels.clear();
  setLed(LED_BLUE);
  delay(300);
  setLed(LED_OFF);

  WiFi.persistent(false);
  pinMode(BOOT_BTN, INPUT_PULLUP);

  if (cfg.wifi_boot) {
    wifiActivate();
  } else {
    wifiOn = false;
  }

  start_time = millis();
  state_entry_ms = start_time;

  Serial.println("\n=== ESP32-S3 DroneBridge FSM v3 ===");
  queue_statustext("ESP32-S3 Bridge Ready");
}

// ============================================================
//                       LOOP
// ============================================================
void loop() {
  unsigned long now = millis();

  static unsigned long last_loop_ms = 0;
  if (now - last_loop_ms < 1) return;
  last_loop_ms = now;

  handleTerminalConfig();

  if (wifiOn) bridgeWiFiToFC();
  bridgeFCtoWiFi();

  if (now - last_wifi_hb >= 1000) {
    send_heartbeat();
    send_queued_statustext();
    last_wifi_hb = now;
  }

  static unsigned long btn_time = 0;
  if (!digitalRead(BOOT_BTN) && now - btn_time > 500) {
    delay(50);
    if (!digitalRead(BOOT_BTN)) {
      if (wifiOn) wifiDeactivate(); else wifiActivate();
      btn_time = now;
    }
  }

  updateSystemState();
  updateLED();

  // WiFi: статус підключення
  int st = WiFi.status();

  if (wifiActivating && st == WL_CONNECTED) {
    wifiActivating = false;
    staWasConnected = true;
    hasWifi = true;
    char buf[48];
    snprintf(buf, sizeof(buf), "WiFi: %s", WiFi.SSID().c_str());
    queue_statustext(buf);
  }

  if (wifiActivating && now - wifiTryStart > 20000) {
    tryNextWifi();
  }

  // WiFi: реконект з авторестартом драйвера
  static unsigned long wifi_last_ok_ms = 0;
  static unsigned long wifi_restart_timer = 0;
  static int wifi_disconn_count = 0;
  if (wifiOn) {
    if (st == WL_CONNECTED) {
      wifi_last_ok_ms = now;
      wifi_disconn_count = 0;
      wifi_restart_timer = 0;
    } else if (st == 255) {
      // WL_NO_SHIELD — WiFi радіо здохло, повний рестарт
      if (wifi_restart_timer == 0) wifi_restart_timer = now;
      if (now - wifi_restart_timer > 5000) {
        queue_statustext("WiFi radio dead, restarting");
        wifiFullRestart();
        wifi_restart_timer = 0;
      }
    } else if (staWasConnected) {
      wifi_disconn_count++;
      // 10с без зв'язку — reconnect, 30с — повний рестарт
      if (wifi_last_ok_ms && now - wifi_last_ok_ms > 30000) {
        queue_statustext("WiFi stuck, full restart");
        wifiFullRestart();
        wifi_last_ok_ms = 0;
      } else if (wifi_last_ok_ms && now - wifi_last_ok_ms > 10000) {
        if (wifi_restart_timer == 0) wifi_restart_timer = now;
        if (now - wifi_restart_timer > 5000) {
          WiFi.reconnect();
          wifi_restart_timer = now;
        }
      }
    }
  }

  if (st != last_sta_status && wifiOn) {
    last_sta_status = st;
    if (st == WL_CONNECTED) {
      staWasConnected = true;
      hasWifi = true;
      wifi_disconn_count = 0;
    } else {
      hasWifi = false;
    }
  }

  if (hasServer && now - last_server_pkt_ms > 30000) {
    hasServer = false;
  }

  if (now - last_serial_log >= 10000) {
    Serial.printf("s=%d h=%d a=%d m=%u w=%d f=%d ekf=0x%04X mag=%.2f fc=%u/%u\n",
                  state, heartbeat_received, is_armed, current_custom_mode,
                  st, mdfly, ekf_flags, mag_test_ratio, fc_bytes, fc_msgs);
    last_serial_log = now;
  }
}
