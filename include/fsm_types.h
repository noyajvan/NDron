#pragma once

#include <arduino.h>

#ifndef RAD_TO_DEG
#define RAD_TO_DEG 57.295779513082320876798154814105f
#endif
#include <WiFi.h>
#include <WiFiUdp.h>
#include <ardupilotmega/mavlink.h>
#include <Adafruit_NeoPixel.h>

// Піни
#define LED_PIN         48
#define NUM_LEDS        1
#define FC_UART_NUM     0

// Буфери
#define FC_RX_BUF       16384
#define FC_TX_BUF       4096
#define BRIDGE_BUF_SIZE 2048
#define UDP_PORT        14550

// Кольори
#define LED_OFF         0x000000
#define LED_WHITE       0xFFFFFF
#define LED_CYAN        0x00FFFF
#define LED_BLUE        0x0000FF
#define LED_LILAC       0x8000FF
#define LED_MAGENTA     0xFF00FF
#define LED_YELLOW      0xFFFF00
#define LED_GREEN       0x00FF00
#define LED_RED         0xFF0000
#define LED_CHERRY_D    0xFF0040

// EKF
#define EKF_ATTITUDE    0x01
#define COMP_ID         MAV_COMP_ID_ONBOARD_COMPUTER
#define MODE_STABILIZE  0
#define MODE_AUTO       3

// Таймаути
#define WIFI_TIMEOUT_MS         60000
#define ARM_RETRY_INTERVAL_MS   5000
#define MODE_RETRY_INTERVAL_MS  10000
#define REASON_REPORT_MS        30000

#ifndef MAV_CMD_DO_START_MAG_CAL
#define MAV_CMD_DO_START_MAG_CAL 42424
#endif

// Черги
#define STATUS_QUEUE_SIZE 16
#define REASON_QUEUE_SIZE 12

// ========== СТАНИ ==========
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

// ========== КОНФІГ ==========
struct Config {
  char     sta_ssid[32];
  char     sta_pass[64];
  uint32_t baud;
  uint8_t  sys_id;
};

// ========== ГЛОБАЛЬНІ ЗМІННІ ==========
extern Config cfg;
extern Adafruit_NeoPixel pixels;
extern WiFiUDP udp;
extern IPAddress gcsIP;
extern uint16_t gcsPort;

extern bool hasWifi;
extern bool hasServer;
extern int  mdfly;

extern bool wifiOn;
extern bool wifiActivating;
extern unsigned long wifiTryStart;
extern bool staWasConnected;
extern int last_sta_status;

extern mavlink_message_t mavMsg;
extern mavlink_status_t  mavStatus;
extern uint8_t bridgeBuf[BRIDGE_BUF_SIZE];
extern mavlink_message_t txMsg;
extern uint8_t txBuf[MAVLINK_MAX_PACKET_LEN];

extern SystemState state;
extern bool heartbeat_received;
extern uint32_t current_custom_mode;
extern bool is_armed;
extern uint8_t system_status;
extern uint8_t gps_fix_type;
extern uint8_t gps_sats;
extern float   battery_voltage;
extern int8_t  battery_remaining;
extern bool    sys_status_received;
extern float roll_deg;
extern float pitch_deg;
extern float vfr_alt;
extern float vfr_climb;
extern uint8_t fc_sys_id;

extern uint16_t ekf_flags;
extern float mag_test_ratio;
extern bool ekf_report_received;

extern float rot_snap_roll;
extern float rot_snap_pitch;
extern bool rot_detected;

extern bool mag_error_msg_sent;
extern bool mag_ok_msg_sent;
extern bool cal_cmd_sent;
extern bool cal_success;
extern bool cal_finalized;
extern uint8_t cal_completion_pct;
extern uint8_t last_cal_pct;
extern bool no_arm_init;
extern bool arm_cmd_sent;
extern bool mode_cmd_sent;
extern bool mission_start_msg;
extern bool was_in_auto;
extern bool flew_above_1m;

extern uint16_t mission_count;
extern bool mission_loaded;
extern bool missionFirstParsed;
extern unsigned long lastMissionReq;

extern unsigned long start_time;
extern unsigned long last_wifi_hb;
extern unsigned long last_serial_log;
extern unsigned long state_entry_ms;
extern unsigned long last_arm_retry_ms;
extern unsigned long last_mode_retry_ms;
extern unsigned long last_reason_report_ms;
extern unsigned long last_server_pkt_ms;

extern char status_queue[STATUS_QUEUE_SIZE][72];
extern uint8_t q_write;
extern uint8_t q_read;

extern char reason_queue[REASON_QUEUE_SIZE][72];
extern uint8_t rq_write;
extern uint8_t rq_read;

extern String inputBuffer;
extern bool crash_triggered;
extern uint32_t fc_bytes;
extern uint32_t fc_msgs;

// ========== ФУНКЦІЇ ==========
void queue_statustext(const char* text);
void send_statustext(const char* text);
