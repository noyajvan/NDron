#include <driver/uart.h>
#include <math.h>
#include "fsm_types.h"
#include "mavlink_util.h"
#include "wifi_mgr.h"

// ===== Детекція крашу =====
// Гейт: дрон летів (armed && висота > 2 м над базою). Таймери скидаються,
// якщо дрон знову рухається — "упав але полетів" не вважається крашем.
static bool   crash_was_flying     = false;
static float  crash_last_stable_alt = 0.0f;
static unsigned long crash_stuck_timer  = 0;
static unsigned long crash_gyro_timer   = 0;
static unsigned long crash_descend_timer = 0;
static float  crash_gyro_x = 0.0f, crash_gyro_y = 0.0f;
static float  crash_ground_speed = 0.0f;
static uint16_t crash_throttle = 0;
static float  crash_climb = 0.0f;

void checkCrashDetection() {
  if (crash_triggered) return;
  unsigned long now = millis();

  // Гейт "летів": тільки в MISSION, armed і піднявся вище 1 м над базою.
  if (state != STATE_MISSION || !is_armed) {
    crash_was_flying = false;
    crash_stuck_timer = 0;
    crash_gyro_timer = 0;
    crash_descend_timer = 0;
    crash_last_stable_alt = vfr_alt;
    return;
  }
  if (mission_base_alt >= 0.0f && vfr_alt - mission_base_alt > 1.0f) {
    crash_was_flying = true;
  }
  if (!crash_was_flying) return;

  // 1. NET — застряг у перешкоді: висота стабільна, швидкість ~0, газ великий
  if (fabsf(vfr_alt - crash_last_stable_alt) < 0.15f && crash_ground_speed < 0.15f) {
    if (crash_stuck_timer == 0) crash_stuck_timer = now;
    if (now - crash_stuck_timer > 3000 && crash_throttle > 45) {
      crash_triggered = true;
      Serial.println("[CRASH] Net");
    }
  } else {
    crash_stuck_timer = 0;
    crash_last_stable_alt = vfr_alt;
  }

  // 2. TUMBLE — різке обертання (кувирок)
  if (fabsf(crash_gyro_x) > 4500.0f || fabsf(crash_gyro_y) > 4500.0f) {
    if (crash_gyro_timer == 0) crash_gyro_timer = now;
    if (now - crash_gyro_timer > 150) {
      crash_triggered = true;
      Serial.println("[CRASH] Tumble");
    }
  } else {
    crash_gyro_timer = 0;
  }

  // 3. RAPID DESCENT — різке падіння (>5 м/с вниз)
  if (crash_climb < -5.0f) {
    if (crash_descend_timer == 0) crash_descend_timer = now;
    if (now - crash_descend_timer > 500) {
      crash_triggered = true;
      Serial.println("[CRASH] RapidDescent");
    }
  } else {
    crash_descend_timer = 0;
  }
}

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

static unsigned long last_fwd_fail_ms = 0;
void forwardToWiFi(const uint8_t* data, size_t len) {
  if (!wifiOn || WiFi.status() != WL_CONNECTED) return;
  // TCP-плече (основний транспорт): без дублів, доставку гарантує TCP.
  if (tcpConnected()) {
    if (tcpLink.write(data, len) == 0) tcpLink.stop();
    return;
  }
  // Fallback UDP (старі прошивки): 2 копії, бо 4G губить UDP.
  // Backoff після невдалої відправки: на слабкому 4G стек lwIP переповнюється
  // (ENOMEM) — даємо йому час спорожнитися, замість спамити Serial кожен пакет.
  unsigned long now = millis();
  if (last_fwd_fail_ms && now - last_fwd_fail_ms < 250) return;
  udp.beginPacket(gcsIP, gcsPort);
  udp.write(data, len);
  if (udp.endPacket() == 0) last_fwd_fail_ms = now;
  udp.beginPacket(gcsIP, gcsPort);
  udp.write(data, len);
  if (udp.endPacket() == 0) last_fwd_fail_ms = now;
}

void sendToBoth(const uint8_t* data, uint16_t len) {
  fcWrite(data, len);
  forwardToWiFi(data, len);
}

void send_statustext(const char* text) {
  mavlink_msg_statustext_pack(cfg.sys_id, COMP_ID, &txMsg,
      MAV_SEVERITY_INFO, text, 0, 0);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  fcWrite(txBuf, len);
}

void send_statustext_udp(const char* text) {
  if (!wifiOn || WiFi.status() != WL_CONNECTED) return;
  mavlink_msg_statustext_pack(cfg.sys_id, COMP_ID, &txMsg,
      MAV_SEVERITY_INFO, text, 0, 0);
  uint16_t len = mavlink_msg_to_send_buffer(txBuf, &txMsg);
  forwardToWiFi(txBuf, len);
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
  fcWrite(txBuf, len);
  forwardToWiFi(txBuf, len);
}

void send_mission_request_list() {
  mavlink_msg_mission_request_list_pack(cfg.sys_id, COMP_ID, &txMsg,
      fc_sys_id, MAV_COMP_ID_AUTOPILOT1, MAV_MISSION_TYPE_MISSION);
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
      }
      break;
    }

    case MAVLINK_MSG_ID_STATUSTEXT: {
      mavlink_statustext_t stxt;
      mavlink_msg_statustext_decode(msg, &stxt);
      if (stxt.severity <= MAV_SEVERITY_WARNING) {
        uint8_t next = (rq_write + 1) % REASON_QUEUE_SIZE;
        if (next == rq_read) rq_read = (rq_read + 1) % REASON_QUEUE_SIZE;
        strncpy(reason_queue[rq_write], stxt.text, 71);
        reason_queue[rq_write][71] = '\0';
        rq_write = next;
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

    case MAVLINK_MSG_ID_ATTITUDE: {
      mavlink_attitude_t att;
      mavlink_msg_attitude_decode(msg, &att);
      roll_deg = att.roll * RAD_TO_DEG;
      pitch_deg = att.pitch * RAD_TO_DEG;
      break;
    }

    case MAVLINK_MSG_ID_SYS_STATUS: {
      mavlink_sys_status_t ss;
      mavlink_msg_sys_status_decode(msg, &ss);
      battery_voltage = ss.voltage_battery / 1000.0f;
      battery_remaining = ss.battery_remaining;
      sys_status_received = true;
      break;
    }

    case MAVLINK_MSG_ID_VFR_HUD: {
      mavlink_vfr_hud_t vfr;
      mavlink_msg_vfr_hud_decode(msg, &vfr);
      vfr_alt = vfr.alt;
      vfr_climb = vfr.climb;
      crash_ground_speed = vfr.groundspeed;
      crash_throttle = vfr.throttle;
      crash_climb = vfr.climb;
      checkCrashDetection();
      break;
    }

    case MAVLINK_MSG_ID_EXTENDED_SYS_STATE: {
      mavlink_extended_sys_state_t es;
      mavlink_msg_extended_sys_state_decode(msg, &es);
      landed_state = es.landed_state;
      break;
    }

    case MAVLINK_MSG_ID_RAW_IMU: {
      mavlink_raw_imu_t imu;
      mavlink_msg_raw_imu_decode(msg, &imu);
      crash_gyro_x = imu.xgyro;
      crash_gyro_y = imu.ygyro;
      checkCrashDetection();
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
      mavlink_mag_cal_progress_t cal;
      mavlink_msg_mag_cal_progress_decode(msg, &cal);
      cal_completion_pct = cal.completion_pct;
      // Не друкуємо кожен пакет — тільки при зміні стану, щоб не забивати Serial.
      static uint8_t last_cal_status = 255;
      if (cal.cal_status != last_cal_status || (cal.cal_status == 4 && !cal_success)) {
        last_cal_status = cal.cal_status;
        Serial.printf("[CAL] status=%d pct=%d compass=%d\n", cal.cal_status, cal.completion_pct, cal.compass_id);
      }
      if (cal.cal_status == 4) {
        Serial.println("[CAL] PROGRESS SUCCESS");
        cal_success = true;
      }
      break;
    }

    case MAVLINK_MSG_ID_MAG_CAL_REPORT: {
      mavlink_mag_cal_report_t rep;
      mavlink_msg_mag_cal_report_decode(msg, &rep);
      Serial.printf("[CAL] report status=%d fitness=%.2f ofs=(%.1f,%.1f,%.1f)\n",
        rep.cal_status, rep.fitness, rep.ofs_x, rep.ofs_y, rep.ofs_z);
      if (rep.cal_status == 4) {
        Serial.println("[CAL] REPORT SUCCESS");
        cal_dia_x = rep.diag_x;
        cal_dia_y = rep.diag_y;
        cal_dia_z = rep.diag_z;
        cal_success = true;
        if (!cal_dia_reported) {
          cal_dia_reported = true;
          char buf[72];
          snprintf(buf, sizeof(buf), "DIA X=%.3f Y=%.3f Z=%.3f",
            rep.diag_x, rep.diag_y, rep.diag_z);
          queue_statustext(buf);
        }
      }
      break;
    }

    case MAVLINK_MSG_ID_MISSION_COUNT: {
      mavlink_mission_count_t mc;
      mavlink_msg_mission_count_decode(msg, &mc);
      mission_count = mc.count;
      break;
    }

    case MAVLINK_MSG_ID_MISSION_ITEM_INT: {
      mavlink_mission_item_int_t mi;
      mavlink_msg_mission_item_int_decode(msg, &mi);
      if (mi.command == MAV_CMD_NAV_LAND) {
        mission_has_land = true;
      }
      break;
    }

    case MAVLINK_MSG_ID_MISSION_ACK: {
      mavlink_mission_ack_t ack;
      mavlink_msg_mission_ack_decode(msg, &ack);
      if (ack.type == MAV_MISSION_ACCEPTED) {
        mission_loaded = true;
      }
      break;
    }

    default:
      break;
  }
}

void bridgeFCtoWiFi() {
  for (int pass = 0; pass < 16; pass++) {
    size_t avail = fcAvailable();
    if (avail == 0) break;
    size_t n = min(avail, (size_t)BRIDGE_BUF_SIZE);
    n = uart_read_bytes((uart_port_t)FC_UART_NUM, bridgeBuf, n, 0);
    if (n == 0) break;
    fc_bytes += n;
    for (size_t i = 0; i < n; i++) {
      if (mavlink_parse_char(MAVLINK_COMM_0, bridgeBuf[i], &mavMsg, &mavStatus)) {
        fc_msgs++;
        // Не ретранслюємо спам калібрування компаса — він забиває 4G
        // і гальмує завантаження параметрів в Mission Planner.
        bool spam = (mavMsg.msgid == MAVLINK_MSG_ID_MAG_CAL_REPORT ||
                     mavMsg.msgid == MAVLINK_MSG_ID_MAG_CAL_PROGRESS);
        // Телеметрія йде завжди, не залежно від hasServer: раніше гейт
        // глушив потік через 30 с тиші від GCS і MP «зависав».
        if (!spam) {
          uint16_t len = mavlink_msg_to_send_buffer(txBuf, &mavMsg);
          // Дублі лише для UDP-fallback; по TCP forwardToWiFi шле один раз.
          forwardToWiFi(txBuf, len);
        }
        handle_mavlink_message(&mavMsg);
      }
    }
  }
}

void bridgeWiFiToFC() {
  if (!wifiOn || WiFi.status() != WL_CONNECTED) return;
  // Основний канал — TCP від реле: команди/запити MP -> FC.
  if (tcpConnected()) {
    for (int pass = 0; pass < 16; pass++) {
      int n = tcpLink.read(bridgeBuf, sizeof(bridgeBuf));
      if (n <= 0) break;
      last_server_pkt_ms = millis();
      if (!hasServer) {
        hasServer = true;
        static unsigned long last_do_connect_msg = 0;
        if (millis() - last_do_connect_msg > 60000) {
          queue_statustext("DO connected");
          last_do_connect_msg = millis();
        }
      }
      fcWrite(bridgeBuf, n);
    }
  }
  for (int pass = 0; pass < 16; pass++) {
    int sz = udp.parsePacket();
    if (sz <= 0) break;
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
    if (n <= 0) break;
    fcWrite(bridgeBuf, n);
  }
}
