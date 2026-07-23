#pragma once

#include <stdint.h>

void fcBegin(int baud, int rx, int tx);
size_t fcAvailable();
void fcWrite(const uint8_t* d, size_t len);
void forwardToWiFi(const uint8_t* data, size_t len);
void sendToBoth(const uint8_t* data, uint16_t len);
void send_statustext(const char* text);
void send_statustext_udp(const char* text);
void send_queued_statustext();
void send_heartbeat();
void send_mission_request_list();
void send_command_long(uint16_t cmd, float p1, float p2, float p3,
                       float p4, float p5, float p6, float p7);
void sendMavlinkArm();
void sendMavlinkForceDisarm();
void sendMavlinkSetMode(uint8_t mode);
void sendMavlinkSetRelay();
void sendStartMagCal();
void sendAcceptMagCal();
void sendPreflightStorage();
void handle_mavlink_message(mavlink_message_t* msg);
void bridgeFCtoWiFi();
void bridgeWiFiToFC();
