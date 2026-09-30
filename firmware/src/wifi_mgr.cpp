#include "fsm_types.h"
#include "wifi_mgr.h"

// Надёжный транспорт дрон->VPS: вихідний TCP до реле (14553).
// TCP сам гарантує доставку (ретрансмісія) — на відміну від UDP на 4G,
// який губив ~90% пакетів і «вічна» загрузка параметрів у Mission Planner.
WiFiClient tcpLink;
static unsigned long lastTcpTry = 0;
static bool tcpWasUp = false;

bool tcpConnected() {
  if (!wifiOn || WiFi.status() != WL_CONNECTED) return false;
  return tcpLink.connected();
}

void tcpLinkService() {
  if (!wifiOn || WiFi.status() != WL_CONNECTED) {
    if (tcpWasUp) {
      tcpWasUp = false;
      tcpLink.stop();
    }
    return;
  }
  if (tcpLink.connected()) {
    if (!tcpWasUp) {
      tcpWasUp = true;
      tcpLink.setNoDelay(true);
      queue_statustext("TCP link up");
    }
    return;
  }
  tcpWasUp = false;
  unsigned long now = millis();
  if (now - lastTcpTry < 3000) return;
  lastTcpTry = now;
  if (tcpLink.connect(gcsIP, TCP_PORT, 2000)) {
    tcpLink.setNoDelay(true);
    queue_statustext("TCP link up");
  } else {
    tcpLink.stop();
  }
}


// Адаптивну потужність прибрано: фіксовані 11 dBm — перевірене робоче
// значення (на 2 dBm зв'язок слабший, максимум не потрібен).

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
  WiFi.setTxPower(WIFI_POWER_11dBm);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  udp.begin(UDP_PORT);
  tcpLink.stop();
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

void wifiRetryConnect() {
  if (!wifiOn || strlen(cfg.sta_ssid) == 0) return;
  WiFi.setAutoReconnect(true);
  WiFi.reconnect();
}

void wifiFullRestart() {
  if (!wifiOn) return;
  queue_statustext("WiFi restart");
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  delay(100);
  WiFi.mode(WIFI_STA);
  delay(50);
  WiFi.setTxPower(WIFI_POWER_11dBm);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  udp.begin(UDP_PORT);
  tcpLink.stop();
  wifiActivating = true;
  wifiTryStart = millis();
  if (strlen(cfg.sta_ssid) > 0)
    WiFi.begin(cfg.sta_ssid, cfg.sta_pass);
}
