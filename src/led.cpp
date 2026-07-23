#include <math.h>
#include "fsm_types.h"
#include "led.h"

#define PI_F 3.14159265f

void setLed(uint32_t c) {
  pixels.setBrightness(5);
  pixels.setPixelColor(0, c);
  pixels.show();
}

static void setLedBreathing(uint32_t color, unsigned long period_ms) {
  float phase = (float)(millis() % period_ms) / period_ms * 2.0f * PI_F;
  float brightness = (sin(phase - PI_F / 2.0f) + 1.0f) / 2.0f;
  brightness = 0.4f + brightness * 0.6f;
  pixels.setBrightness((uint8_t)(brightness * 5.0f));
  pixels.setPixelColor(0, color);
  pixels.show();
}

void updateLED() {
  if (mdfly == 60) { setLed(LED_OFF); return; }
  unsigned long now = millis();

  if (!heartbeat_received) {
    if (!hasServer) {
      if (!wifiOn || !hasWifi) {
        setLed((now % 1500) < 250 ? LED_WHITE : LED_OFF);
      } else {
        setLed((now % 1000) < 500 ? LED_WHITE : LED_OFF);
      }
    } else {
      setLed(LED_WHITE);
    }
    return;
  }

  switch (state) {
    case STATE_INIT_MAVLINK:
      setLedBreathing(LED_BLUE, 2000);
      break;

    case STATE_MAG_OK:
      setLed((now % 1000) < 500 ? LED_BLUE : LED_OFF);
      break;

    case STATE_CALIBRATION: {
      unsigned long period = 2000 - ((uint32_t)cal_completion_pct * 18);
      if (period < 200) period = 200;
      setLedBreathing(LED_CHERRY_D, period);
      break;
    }

    case STATE_CALIBRATION_END:
      setLed(LED_OFF);
      break;

    case STATE_NO_ARM:
    case STATE_ARMING:
      setLed((now % 1000) < 500 ? LED_YELLOW : LED_OFF);
      break;

    case STATE_ARMED:
    case STATE_START_MISSION:
    case STATE_MISSION:
      setLed(LED_GREEN);
      break;

    case STATE_RELAY_CONTROL:
      setLed((now % 1000) < 500 ? LED_RED : LED_OFF);
      break;

    default:
      setLed(LED_OFF);
      break;
  }
}
