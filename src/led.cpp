#include "fsm_types.h"
#include "led.h"

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
  if (state == STATE_MISSION) { setLed(LED_GREEN); return; }
  if (state == STATE_CALIBRATION_END) { setLed(LED_OFF); return; }
  if (state == STATE_MAG_ERROR || state == STATE_MAG_OK) {
    setLed((millis() % 1250) < 250 ? getMavCoColor() : LED_OFF);
    return;
  }
  if (state == STATE_CALIBRATION) {
    setLed((millis() % 500) < 250 ? LED_MAGENTA : LED_OFF);
    return;
  }
  bool onPhase = (millis() % 1000) < 500;
  if (state == STATE_INIT_WIFI) {
    setLed(onPhase ? getWifiCoColor() : LED_OFF);
  } else {
    setLed(onPhase ? getMavCoColor() : LED_OFF);
  }
}
