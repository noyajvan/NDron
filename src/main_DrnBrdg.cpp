#include <Arduino.h>
#include <WiFi.h>

#include "fsm_types.h"
#include "config.h"
#include "led.h"
#include "wifi_mgr.h"
#include "mavlink_util.h"
#include "terminal.h"
#include "state_machine.h"

// ========== ГЛОБАЛЬНІ ЗМІННІ ==========
Config cfg = { "", "", 921600, 1 };

Adafruit_NeoPixel pixels(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);
WiFiUDP udp;
IPAddress gcsIP = IPAddress(152, 70, 51, 224);
uint16_t gcsPort = UDP_PORT;

bool hasWifi = false;
bool hasServer = false;
int  mdfly  = 0;

bool wifiOn = false;
bool wifiActivating = false;
unsigned long wifiTryStart = 0;
bool staWasConnected = false;
int last_sta_status = -1;

mavlink_message_t mavMsg;
mavlink_status_t  mavStatus;
uint8_t bridgeBuf[BRIDGE_BUF_SIZE];
mavlink_message_t txMsg;
uint8_t txBuf[MAVLINK_MAX_PACKET_LEN];

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
float roll_deg = 0.0f;
float pitch_deg = 0.0f;
float vfr_alt = 0.0f;
float vfr_climb = 0.0f;
uint8_t fc_sys_id = 1;

uint16_t ekf_flags = 0;
float mag_test_ratio = 0.0f;
bool ekf_report_received = false;

float rot_snap_roll = 0.0f;
float rot_snap_pitch = 0.0f;
bool rot_detected = false;

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

uint16_t mission_count = 0;
bool mission_loaded = false;
bool missionFirstParsed = false;
unsigned long lastMissionReq = 0;

unsigned long start_time = 0;
unsigned long last_wifi_hb = 0;
unsigned long last_serial_log = 0;
unsigned long state_entry_ms = 0;
unsigned long last_arm_retry_ms   = 0;
unsigned long last_mode_retry_ms  = 0;
unsigned long last_reason_report_ms = 0;
unsigned long last_server_pkt_ms  = 0;

char status_queue[STATUS_QUEUE_SIZE][72];
uint8_t q_write = 0, q_read = 0;

char reason_queue[REASON_QUEUE_SIZE][72];
uint8_t rq_write = 0, rq_read = 0;

String inputBuffer = "";

bool crash_triggered = false;
uint32_t fc_bytes = 0, fc_msgs = 0;

// ========== SETUP ==========
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

  if (strlen(cfg.sta_ssid) > 0) {
    wifiActivate();
  }

  start_time = millis();
  state_entry_ms = start_time;

  Serial.println("\n=== ESP32-S3 DroneBridge ===");
  Serial.print("> ");
  queue_statustext("Bridge Ready");
}

// ========== LOOP ==========
void queue_statustext(const char* text) {
  uint8_t next = (q_write + 1) % STATUS_QUEUE_SIZE;
  if (next == q_read) q_read = (q_read + 1) % STATUS_QUEUE_SIZE;
  strncpy(status_queue[q_write], text, 71);
  status_queue[q_write][71] = '\0';
  q_write = next;
}

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
    wifiActivating = false;
    if (strlen(cfg.sta_ssid) > 0) {
      WiFi.begin(cfg.sta_ssid, cfg.sta_pass);
      wifiActivating = true;
      wifiTryStart = now;
    }
  }

  static unsigned long wifi_last_ok_ms = 0;
  static unsigned long wifi_restart_timer = 0;
  if (wifiOn) {
    if (st == WL_CONNECTED) {
      wifi_last_ok_ms = now;
      wifi_restart_timer = 0;
    } else if (st == 255) {
      if (wifi_restart_timer == 0) wifi_restart_timer = now;
      if (now - wifi_restart_timer > 5000) {
        queue_statustext("WiFi radio dead, restarting");
        wifiFullRestart();
        wifi_restart_timer = 0;
      }
    } else if (staWasConnected) {
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
