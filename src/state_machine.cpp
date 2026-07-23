#include <math.h>
#include "fsm_types.h"
#include "state_machine.h"
#include "mavlink_util.h"
#include "wifi_mgr.h"

extern bool crash_triggered;

void updateSystemState() {
  unsigned long now = millis();

  hasWifi = wifiOn && (WiFi.status() == WL_CONNECTED);

  if (!staWasConnected && wifiOn && (now - start_time > WIFI_TIMEOUT_MS)) {
    wifiDeactivate();
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
        send_statustext_udp("Bridge: compass calibration");
        break;
      case STATE_CALIBRATION_END:
        cal_finalized = false;
        break;
      case STATE_NO_ARM:
        no_arm_init = false;
        queue_statustext("check arm conditions");
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

  switch (state) {

    case STATE_INIT_WIFI: {
      if (state_entry_ms == 0) state_entry_ms = now;
      if (now - state_entry_ms > 2000) {
        state = STATE_INIT_MAVLINK;
      }
      break;
    }

    case STATE_INIT_MAVLINK: {
      if (!heartbeat_received) break;
      if (!ekf_report_received) break;

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

      if (!ekf_ok) break;
      if (now - ekf_att_stable_ms < 5000) break;

      state = STATE_MAG_OK;
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
      }
      static uint8_t last_cal_pct = 0;
      if (cal_completion_pct > 0 && cal_completion_pct - last_cal_pct >= 10) {
        last_cal_pct = cal_completion_pct;
        char buf[32];
        snprintf(buf, sizeof(buf), "Cal: %d%%", cal_completion_pct);
        queue_statustext(buf);
        send_statustext_udp(buf);
        send_statustext(buf);
      }
      if (cal_success) {
        state = STATE_CALIBRATION_END;
        break;
      }
      break;
    }

    case STATE_CALIBRATION_END: {
      if (!cal_finalized) {
        sendAcceptMagCal();
        sendPreflightStorage();
        queue_statustext("calibration complete");
        send_statustext_udp("Bridge: calibration complete");
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
        queue_statustext("check arm conditions");
        no_arm_init = true;
      }

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
          queue_statustext(mission_loaded ? "Mission loaded" : "Mission NOT loaded");
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

    case STATE_ARMING: {
      if (!is_armed && now - state_entry_ms > 3000) {
        queue_statustext("disarmed -> back to NO_ARM");
        state = STATE_NO_ARM;
        break;
      }
      if (!arm_cmd_sent || (now - last_arm_retry_ms >= ARM_RETRY_INTERVAL_MS)) {
        sendMavlinkArm();
        queue_statustext("sending ARM command");
        arm_cmd_sent = true;
        last_arm_retry_ms = now;
      }
      if (is_armed) {
        state = STATE_ARMED;
      }
      break;
    }

    case STATE_ARMED: {
      if (!is_armed) {
        queue_statustext("disarmed -> back to NO_ARM");
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
          if (!mission_loaded) queue_statustext("Mission NOT loaded");
        }
      }
      break;
    }

    case STATE_START_MISSION: {
      if (!is_armed) {
        queue_statustext("disarmed -> back to NO_ARM");
        state = STATE_NO_ARM;
        break;
      }
      if (!mode_cmd_sent || (now - last_mode_retry_ms >= MODE_RETRY_INTERVAL_MS)) {
        sendMavlinkSetMode(MODE_AUTO);
        queue_statustext("switch to AUTO mode");
        mode_cmd_sent = true;
        last_mode_retry_ms = now;
      }
      if (current_custom_mode == MODE_AUTO) {
        state = STATE_MISSION;
      }
      break;
    }

    case STATE_MISSION: {
      if (!mission_start_msg) {
        queue_statustext("AUTO mode - mission running");
        mission_start_msg = true;
      }
      if (current_custom_mode == MODE_AUTO) was_in_auto = true;

      bool mode_changed = (was_in_auto && current_custom_mode != MODE_AUTO);
      bool mission_ended = mode_changed || !is_armed;

      if (mission_ended || crash_triggered) {
        const char* m = mode_changed ? "mission ended, relay ON" : "mission disarmed, relay ON";
        queue_statustext(m);
        state = STATE_RELAY_CONTROL;
      }
      break;
    }

    case STATE_RELAY_CONTROL: {
      static bool relay_sent = false;
      if (!relay_sent) {
        sendMavlinkSetRelay();
        sendMavlinkForceDisarm();
        queue_statustext("Relay ON + DISARM");
        relay_sent = true;
      }
      break;
    }

    default:
      break;
  }
}
