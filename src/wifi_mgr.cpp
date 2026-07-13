#include "fsm_types.h"
#include "wifi_mgr.h"

void wifiActivate() {
  if (wifiOn) return;
  if (strlen(cfg.sta_ssid) == 0) return;
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
  WiFi.begin(cfg.sta_ssid, cfg.sta_pass);
  wifiActivating = true;
  wifiTryStart = millis();
}

void wifiDeactivate() {
  if (!wifiOn) return;
  wifiOn = false;
  wifiActivating = false;
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
  wifiActivating = true;
  wifiTryStart = millis();
  if (strlen(cfg.sta_ssid) > 0)
    WiFi.begin(cfg.sta_ssid, cfg.sta_pass);
}
