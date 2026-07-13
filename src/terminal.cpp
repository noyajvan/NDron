#include "fsm_types.h"
#include "terminal.h"
#include "config.h"
#include "mavlink_util.h"

void handleTerminalConfig() {
  while (Serial.available() > 0) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      Serial.println();
      if (inputBuffer.length() > 0) {
        inputBuffer.trim();

        if (inputBuffer.equalsIgnoreCase("STATUS")) {
          Serial.println("--- STATUS ---");
          Serial.printf("State: %d  mdfly: %d\n", state, mdfly);
          Serial.printf("WiFi: %s  IP: %s\n",
              (WiFi.status() == WL_CONNECTED) ? "OK" : "NO",
              WiFi.localIP().toString().c_str());
          Serial.printf("hasServer: %d  hb: %d  armed: %d  mode: %u\n",
              hasServer, heartbeat_received, is_armed, current_custom_mode);
          Serial.printf("EKF: 0x%04X  mag: %.3f  mission: %d/%d\n",
              ekf_flags, mag_test_ratio, mission_loaded, mission_count);
          Serial.printf("FC: %u bytes  %u msgs\n", fc_bytes, fc_msgs);
          Serial.printf("WiFi cfg: %s / %s\n", cfg.sta_ssid,
              strlen(cfg.sta_pass) ? "pass set" : "no pass");
        }
        else if (inputBuffer.startsWith("SSID=")) {
          String val = inputBuffer.substring(5);
          val.trim();
          if (val.length() == 0) {
            cfg.sta_ssid[0] = '\0';
            cfg.sta_pass[0] = '\0';
            Serial.println(">> SSID cleared, WiFi OFF");
          } else {
            strncpy(cfg.sta_ssid, val.c_str(), sizeof(cfg.sta_ssid) - 1);
            cfg.sta_ssid[sizeof(cfg.sta_ssid) - 1] = '\0';
            Serial.printf(">> SSID = %s\n", cfg.sta_ssid);
          }
        }
        else if (inputBuffer.startsWith("PASS=")) {
          strncpy(cfg.sta_pass, inputBuffer.substring(5).c_str(), sizeof(cfg.sta_pass) - 1);
          cfg.sta_pass[sizeof(cfg.sta_pass) - 1] = '\0';
          Serial.printf(">> PASS = %s\n", cfg.sta_pass);
        }
        else if (inputBuffer.startsWith("BAUD=")) {
          uint32_t b = inputBuffer.substring(5).toInt();
          if (b >= 9600 && b <= 921600) {
            cfg.baud = b;
            Serial.printf(">> BAUD = %u\n", cfg.baud);
          } else Serial.println(">> Invalid baud");
        }
        else if (inputBuffer.startsWith("SYSID=")) {
          uint8_t sid = inputBuffer.substring(6).toInt();
          if (sid >= 1 && sid <= 255) {
            cfg.sys_id = sid;
            Serial.printf(">> SYSID = %u\n", cfg.sys_id);
          }
        }
        else if (inputBuffer.equalsIgnoreCase("RELAY")) {
          sendMavlinkSetRelay();
          Serial.println(">> RELAY sent");
        }
        else if (inputBuffer.equalsIgnoreCase("DISARM")) {
          sendMavlinkForceDisarm();
          Serial.println(">> DISARM sent");
        }
        else if (inputBuffer.equalsIgnoreCase("SAVE")) {
          saveConfig();
          Serial.println(">> Saved. Rebooting...");
          delay(500);
          ESP.restart();
        }
        else {
          Serial.println("CMD: STATUS | SSID=name | PASS=pass | BAUD= | SYSID= | RELAY | DISARM | SAVE");
        }
        inputBuffer = "";
      }
      Serial.print("> ");
    } else {
      Serial.print(c);
      inputBuffer += c;
    }
  }
}
