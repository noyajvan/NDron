#include <Preferences.h>
#include "fsm_types.h"
#include "config.h"

void loadConfig() {
  Preferences p;
  p.begin("dbridge", true);
  p.getString("ssid", cfg.sta_ssid, sizeof(cfg.sta_ssid));
  p.getString("pass", cfg.sta_pass, sizeof(cfg.sta_pass));
  cfg.baud      = p.getUInt("baud", 921600);
  cfg.sys_id    = p.getUInt("sys_id", 1);
  cfg.sys_id = 1;
  p.end();
}

void saveConfig() {
  Preferences p;
  p.begin("dbridge", false);
  p.putString("ssid", cfg.sta_ssid);
  p.putString("pass", cfg.sta_pass);
  p.putUInt("baud", cfg.baud);
  p.putUInt("sys_id", cfg.sys_id);
  p.end();
}
