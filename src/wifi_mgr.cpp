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


// Адаптивна потужність передавача. Викликається не частіше ніж раз на
// TX_POWER_CHECK_MS, щоб не смикати драйвер WiFi у кожному циклі loop().
#define TX_POWER_CHECK_MS 2000
static const wifi_power_t tx_power_steps[] = {
  WIFI_POWER_2dBm,  WIFI_POWER_5dBm,  WIFI_POWER_7dBm,
  WIFI_POWER_8_5dBm, WIFI_POWER_11dBm, WIFI_POWER_13dBm,
  WIFI_POWER_15dBm, WIFI_POWER_17dBm, WIFI_POWER_18_5dBm,
  WIFI_POWER_19dBm, WIFI_POWER_19_5dBm
};
#define TX_STEP_COUNT (sizeof(tx_power_steps)/sizeof(tx_power_steps[0]))
static uint8_t tx_power_idx = 0;

void applyAdaptiveTxPower(bool connected) {
  static unsigned long last_check = 0;
  unsigned long now = millis();
  if (now - last_check < TX_POWER_CHECK_MS) return;
  last_check = now;

  if (connected) {
    // Зв'язок є — знижуємо потужність до середнього рівня (енергозбереження),
    // але не нижче, щоб не втратити сигнал.
    if (tx_power_idx > 4) tx_power_idx--;
  } else {
    // Зв'язку немає — тримаємо максимум потужності.
    tx_power_idx = TX_STEP_COUNT - 1;
  }

  wifi_power_t level = tx_power_steps[tx_power_idx];
  if (WiFi.getTxPower() != level) {
    WiFi.setTxPower(level);
    Serial.printf("[WIFI] TX power=%ddBm\n", (int)level);
  }
}

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
  WiFi.setTxPower(WIFI_POWER_2dBm);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  udp.begin(UDP_PORT);
  tcpLink.stop();
  wifiActivating = true;
  wifiTryStart = millis();
  if (strlen(cfg.sta_ssid) > 0)
    WiFi.begin(cfg.sta_ssid, cfg.sta_pass);
}
