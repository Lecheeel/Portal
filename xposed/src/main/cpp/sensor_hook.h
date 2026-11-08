//
// Created by fuqiuluo on 2024/10/15.
//

#ifndef LOCATIONEXT_SENSOR_HOOK_H
#define LOCATIONEXT_SENSOR_HOOK_H

#include <cstdint>
#include "android/sensor.h"

struct NativeSensorFrame {
    int64_t stepCount;
    int32_t stepCadence;
    int64_t syncNanos;
    float accelerometer[3];
    float gyroscope[3];
    float magnetometer[3];
    float barometerHpa;
};

void setNativeSensorFrame(const NativeSensorFrame& frame);
void clearNativeSensorFrames();
bool readNativeSensorFrame(int64_t timestampNanos, NativeSensorFrame& out);

// void convertToSensorEvent(const Event &src, sensors_event_t *dst);
typedef void (*OriginalConvertToSensorEventType)(void*, void*);

bool doSensorHook();

#endif //LOCATIONEXT_SENSOR_HOOK_H
