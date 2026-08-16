#include <math.h>
#include "fsm_types.h"
#include "state_machine.h"
#include "mavlink_util.h"
#include "wifi_mgr.h"

extern bool crash_triggered;
uint8_t last_cal_pct = 0;
bool flew_above_1m = false;
float mission_base_alt = 0.0f;

void updateSystemState() {
  unsigned long now = millis();

  hasWifi = wifiOn && (WiFi.status() == WL_CONNECTED);

  if (!staWasConnected && wifiOn && (now - start_time > WIFI_TIMEOUT_MS)) {
    wifiDeactivate();
  }

  if (current_custom_mode != MODE_STABILIZE && current_custom_mode != MODE_AUTO) {
    if (mdfly != 60) {
      mdfly = 60;
      queue_statustext("mode != STAB/AUTO -> stop");
      send_statustext_udp("Bridge: mode != STAB/AUTO -> stop");
    }
  }
  if (mdfly == 60) return;

  if (heartbeat_received && !missionFirstParsed) {
    if (lastMissionReq == 0 && now - start_time > 2000) {
      mission_count = 0;
      mission_loaded = false;
      send_mission_request_list();
      lastMissionReq = now;
    } else if (lastMissionReq && now - lastMissionReq > 5000) {
      missionFirstParsed = true;
      lastMissionReq = 0;
      if (mission_count > 0) mission_loaded = true;
    }
  }

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
        send_statustext_udp("Bridge: MAG_ERROR");
        Serial.printf("--> MAG_ERROR snap R=%.1f P=%.1f, mag=%.3f\n", roll_deg, pitch_deg, mag_test_ratio);
        break;
      case STATE_MAG_OK:
        mag_ok_msg_sent = false;
        rot_snap_roll  = roll_deg;
        rot_snap_pitch = pitch_deg;
        rot_detected = false;
        send_statustext_udp("Bridge: MAG_OK");
        Serial.printf("--> MAG_OK snap R=%.1f P=%.1f, mag=%.3f\n", roll_deg, pitch_deg, mag_test_ratio);
        break;
      case STATE_CALIBRATION:
        cal_cmd_sent = false;
        cal_success = false;
        cal_completion_pct = 0;
        last_cal_pct = 0;
        cal_retries = 0;
        cal_dia_reported = false;
        send_statustext_udp("Bridge: compass calibration");
        break;
      case STATE_CALIBRATION_END:
        cal_finalized = false;
        break;
      case STATE_NO_ARM:
        no_arm_init = false;
        break;
      case STATE_ARMING:
        break;
      case STATE_START_MISSION:
        mode_cmd_sent = false;
        last_mode_retry_ms = 0;
        break;
      case STATE_MISSION:
        mission_start_msg = false;
        was_in_auto = false;
        flew_above_1m = false;
        // База висоти ще не зафіксована: чекаємо перший валідний VFR_HUD.
        mission_base_alt = -1.0f;
        break;
      case STATE_RELAY_CONTROL:
        break;
      default:
        break;
    }
    lastState = state;
  }

  switch (state) {

    case STATE_INIT_WIFI: {
      if (state_entry_ms == 0) state_entry_ms = now;
      if (now - state_entry_ms > 2000) {
        Serial.println("[S] INIT_WIFI -> INIT_MAVLINK");
        state = STATE_INIT_MAVLINK;
      } else {
        static unsigned long last_log_wifi = 0;
        if (now - last_log_wifi > 1000) { last_log_wifi = now; Serial.println("[S] waiting INIT_WIFI 2s"); }
      }
      break;
    }

    case STATE_INIT_MAVLINK: {
      if (!heartbeat_received) {
        static unsigned long last_log_hb = 0;
        if (now - last_log_hb > 2000) { last_log_hb = now; Serial.println("[S] waiting heartbeat..."); }
        break;
      }
      if (!ekf_report_received) {
        static unsigned long last_log_ekf = 0;
        if (now - last_log_ekf > 2000) { last_log_ekf = now; Serial.println("[S] waiting EKF report..."); }
        break;
      }

      bool ekf_ok = ((ekf_flags & EKF_ATTITUDE) != 0);

      static bool ekf_ok_prev = false;
      static unsigned long ekf_att_stable_ms = 0;
      static bool ekf_queued = false;
      if (ekf_ok != ekf_ok_prev) {
        ekf_ok_prev = ekf_ok;
        if (ekf_ok) {
          ekf_att_stable_ms = now;
          ekf_queued = false;
        }
        Serial.printf("[EKF] flags=0x%04X att=%d mag=%.3f\n",
                       ekf_flags, ekf_ok, mag_test_ratio);
      }
      if (ekf_ok && !ekf_queued && now - ekf_att_stable_ms > 3000) {
        ekf_queued = true;
        send_statustext_udp("Bridge: EKF OK");
      }

      if (!ekf_ok) {
        // Таймаут: якщо EKF_ATTITUDE не піднявся за 30 секунд — продовжуємо
        static unsigned long ekf_timeout_log = 0;
        if (now - state_entry_ms > 30000) {
          if (now - ekf_timeout_log > 5000) {
            ekf_timeout_log = now;
            Serial.printf("[S] EKF_ATT timeout %lu s, forcing MAG_OK\n", (now - state_entry_ms) / 1000);
            send_statustext_udp("Bridge: EKF att timeout, skip mag check");
          }
          if (now - state_entry_ms > 35000) {
            state = STATE_MAG_OK;
            break;
          }
        }
        break;
      }
      if (now - ekf_att_stable_ms < 5000) break;

      state = STATE_MAG_OK;
      Serial.println("[S] EKF_ATT OK -> MAG_OK");
      break;
    }

    case STATE_MAG_ERROR: {
      if (!mag_error_msg_sent) {
        char buf[72];
        snprintf(buf, sizeof(buf), "mag=%.3f -> calibrating", mag_test_ratio);
        queue_statustext(buf);
        send_statustext_udp(buf);
        send_statustext(buf);
        mag_error_msg_sent = true;
      }
      state = STATE_CALIBRATION;
      break;
    }

    case STATE_MAG_OK: {
      if (!mag_ok_msg_sent) {
        char buf[72];
        snprintf(buf, sizeof(buf), "mag=%.3f waiting rotation", mag_test_ratio);
        queue_statustext(buf);
        send_statustext_udp(buf);
        send_statustext(buf);
        mag_ok_msg_sent = true;
      }

      if (fabs(roll_deg - rot_snap_roll) > 45.0f ||
          fabs(pitch_deg - rot_snap_pitch) > 45.0f) {
        rot_detected = true;
        send_statustext_udp("Bridge: rotation detected");
        Serial.printf("[ROT] MAG_OK dR=%.1f dP=%.1f!\n",
                      roll_deg - rot_snap_roll, pitch_deg - rot_snap_pitch);
      }

      if (rot_detected) {
        state = STATE_CALIBRATION;
        break;
      }

      if (now - state_entry_ms > 20000) {
        send_statustext_udp("Bridge: no rotation, skip calibration");
        state = STATE_NO_ARM;
      }
      break;
    }

    case STATE_CALIBRATION: {
      if (!cal_cmd_sent) {
        sendStartMagCal();
        send_statustext_udp("Bridge: calibration started");
        cal_cmd_sent = true;
        Serial.println("[CAL] command sent, waiting progress...");
      }
      if (cal_completion_pct > 0 && cal_completion_pct - last_cal_pct >= 10) {
        last_cal_pct = cal_completion_pct;
        char buf[32];
        snprintf(buf, sizeof(buf), "Cal: %d%%", cal_completion_pct);
        queue_statustext(buf);
        send_statustext(buf);
        Serial.printf("[CAL] progress %d%%\n", cal_completion_pct);
      }
      if (cal_success) {
        state = STATE_CALIBRATION_END;
        Serial.println("[CAL] SUCCESS -> CALIBRATION_END");
        if (cal_dia_x != 0.0f) {
          bool dia_ok = (fabs(1.0f - cal_dia_x) <= DIA_TOLERANCE) &&
                        (fabs(1.0f - cal_dia_y) <= DIA_TOLERANCE) &&
                        (fabs(1.0f - cal_dia_z) <= DIA_TOLERANCE);
          if (!dia_ok) {
            queue_statustext("DIA outside 15%");
          }
        }
      }

      // Повторні спроби калібрування
      unsigned long cal_time = now - state_entry_ms;
      if (!cal_success && cal_cmd_sent && cal_time > 30000) {
        cal_retries++;
        if (cal_retries < CAL_MAX_RETRIES) {
          Serial.printf("[CAL] timeout, retry %d/%d\n", cal_retries, CAL_MAX_RETRIES);
          cal_cmd_sent = false;
          cal_success = false;
          state_entry_ms = now;
          send_statustext_udp("Bridge: calibration retry");
        } else {
          Serial.println("[CAL] max retries, skip calibration");
          send_statustext_udp("Bridge: calibration failed, skipping");
          state = STATE_NO_ARM;
        }
      }
      break;
    }

    case STATE_CALIBRATION_END: {
      if (!cal_finalized) {
        sendAcceptMagCal();
        sendPreflightStorage();
        queue_statustext("calibration OK - power cycle FC");
        send_statustext_udp("Bridge: calibration OK - power cycle FC");
        mdfly = 60;
        cal_finalized = true;
      }
      break;
    }

    case STATE_NO_ARM: {
      if (mag_test_ratio > 0.5f) {
        send_statustext_udp("Bridge: mag>0.5 re-calibrating");
        state = STATE_MAG_ERROR;
        break;
      }

      if (!no_arm_init) {
        send_mission_request_list();
        no_arm_init = true;
      }

      if (gps_fix_type >= 3 && now - state_entry_ms >= 5000) {
        sendMavlinkArm();
        queue_statustext("ARM >>");
        state_entry_ms = now;
      }

      if (is_armed) {
        state = STATE_ARMING;
      }
      break;
    }

    case STATE_ARMING: {
      if (!is_armed) {
        queue_statustext("disarmed -> NO_ARM");
        state = STATE_NO_ARM;
        break;
      }

      bool can_auto = mission_loaded && missionFirstParsed && gps_fix_type >= 3 && (ekf_flags & EKF_POS_HORIZ_ABS);
      if (can_auto) {
        static bool auto_sent = false;
        if (!auto_sent || (now - state_entry_ms >= 20000)) {
          sendMavlinkSetMode(MODE_AUTO);
          queue_statustext("AUTO >>");
          auto_sent = true;
          state_entry_ms = now;
        }
      }

      if (is_armed && current_custom_mode == MODE_AUTO) {
        state = STATE_MISSION;
      }
      break;
    }

    case STATE_MISSION: {
      if (!mission_start_msg) {
        queue_statustext("START");
        mission_start_msg = true;
      }
      // Фіксуємо базову висоту при першому валідному VFR_HUD,
      // щоб не вважати AMSL-висоту поля (напр. 57 м) за взліт.
      if (mission_base_alt < 0.0f && vfr_alt > 0.5f) {
        mission_base_alt = vfr_alt;
      }
      if (mission_base_alt >= 0.0f && vfr_alt - mission_base_alt > 1.0f) {
        flew_above_1m = true;
      }
      if (current_custom_mode == MODE_AUTO) was_in_auto = true;

      bool mission_ended = !is_armed || (was_in_auto && current_custom_mode != MODE_AUTO);

      if (mission_ended || crash_triggered) {
        if (flew_above_1m) {
          sendMavlinkSetRelay();
          sendMavlinkForceDisarm();
          queue_statustext("Relay");
        } else {
          sendMavlinkForceDisarm();
          queue_statustext("Disarmed, no relay");
        }
        state = STATE_RELAY_CONTROL;
      }
      break;
    }

    case STATE_RELAY_CONTROL: {
      break;
    }

    default:
      break;
  }
}
