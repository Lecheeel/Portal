//
// Created by fuqiuluo on 2024/10/15.
//
#include <dobby.h>
#include <unistd.h>
#include <cmath>
#include <cstring>
#include <mutex>
#include <algorithm>
#include <atomic>
#include "sensor_hook.h"
#include "logging.h"
#include "elf_util.h"
#include "dobby_hook.h"

#define LIBSF_PATH "/system/lib64/libsensorservice.so"

extern std::atomic_bool enableSensorHook;

namespace {
OriginalConvertToSensorEventType OriginalConvertToSensorEvent = nullptr;
OriginalConvertToSensorEventType OriginalAidlConvertToSensorEvent = nullptr;

std::mutex gFrameMutex;
NativeSensorFrame gPrevious{};
NativeSensorFrame gCurrent{};
bool gHasPrevious = false;
bool gHasCurrent = false;

float lerp(float a, float b, float t) { return a + (b - a) * t; }
int64_t lerpRound(int64_t a, int64_t b, float t) {
    return static_cast<int64_t>(std::llround(static_cast<double>(a) +
        (static_cast<double>(b) - static_cast<double>(a)) * t));
}

float interpolationFactor(const NativeSensorFrame& previous, const NativeSensorFrame& current,
                          int64_t timestampNanos) {
    if (current.syncNanos <= previous.syncNanos) return 1.0f;
    const double span = static_cast<double>(current.syncNanos - previous.syncNanos);
    const double offset = static_cast<double>(timestampNanos - previous.syncNanos);
    return static_cast<float>(std::clamp(offset / span, 0.0, 1.0));
}
} // namespace

void setNativeSensorFrame(const NativeSensorFrame& frame) {
    std::lock_guard<std::mutex> lock(gFrameMutex);
    if (gHasCurrent) {
        gPrevious = gCurrent;
        gHasPrevious = true;
    }
    gCurrent = frame;
    gHasCurrent = true;
}

void clearNativeSensorFrames() {
    std::lock_guard<std::mutex> lock(gFrameMutex);
    gHasPrevious = false;
    gHasCurrent = false;
}

bool readNativeSensorFrame(int64_t timestampNanos, NativeSensorFrame& out) {
    std::lock_guard<std::mutex> lock(gFrameMutex);
    if (!gHasCurrent) return false;
    if (!gHasPrevious || timestampNanos >= gCurrent.syncNanos) {
        out = gCurrent;
        return true;
    }
    const float t = interpolationFactor(gPrevious, gCurrent, timestampNanos);
    out = gCurrent;
    out.stepCount = lerpRound(gPrevious.stepCount, gCurrent.stepCount, t);
    out.stepCadence = static_cast<int32_t>(std::llround(
        lerp(static_cast<float>(gPrevious.stepCadence),
             static_cast<float>(gCurrent.stepCadence), t)));
    for (int i = 0; i < 3; ++i) {
        out.accelerometer[i] = lerp(gPrevious.accelerometer[i], gCurrent.accelerometer[i], t);
        out.gyroscope[i] = lerp(gPrevious.gyroscope[i], gCurrent.gyroscope[i], t);
        out.magnetometer[i] = lerp(gPrevious.magnetometer[i], gCurrent.magnetometer[i], t);
    }
    out.barometerHpa = lerp(gPrevious.barometerHpa, gCurrent.barometerHpa, t);
    return true;
}

void applyEvent(void *dst) {
    if (!enableSensorHook.load()) return;
    auto *event = reinterpret_cast<ASensorEvent *>(dst);
    if (event == nullptr) return;
    NativeSensorFrame frame;
    if (!readNativeSensorFrame(event->timestamp, frame)) return;

    switch (event->type) {
        case 1: // accelerometer
            std::memcpy(event->data, frame.accelerometer, sizeof(frame.accelerometer));
            break;
        case 4: // gyroscope
            std::memcpy(event->data, frame.gyroscope, sizeof(frame.gyroscope));
            break;
        case 2: // magnetic field
            std::memcpy(event->data, frame.magnetometer, sizeof(frame.magnetometer));
            break;
        case 6: // pressure hPa
            event->data[0] = frame.barometerHpa;
            break;
        case 19: // step counter
            event->data[0] = static_cast<float>(frame.stepCount);
            break;
        case 18: // step detector
            event->data[0] = frame.stepCadence > 0 ? 1.0f : 0.0f;
            break;
        default:
            break;
    }
}

void ConvertToSensorEvent(void *src, void *dst) {
    if (OriginalConvertToSensorEvent == nullptr) return;
    OriginalConvertToSensorEvent(src, dst);
    applyEvent(dst);
}

void ConvertToSensorEventAidl(void *src, void *dst) {
    if (OriginalAidlConvertToSensorEvent == nullptr) return;
    OriginalAidlConvertToSensorEvent(src, dst);
    applyEvent(dst);
}

bool doSensorHook() {
    if (OriginalConvertToSensorEvent != nullptr || OriginalAidlConvertToSensorEvent != nullptr)
        return true;
    SandHook::ElfImg sensorService(LIBSF_PATH);
    if (!sensorService.isValid()) {
        LOGE("failed to load libsensorservice");
        return false;
    }

    auto convertToSensorEvent = sensorService.getSymbolAddress<void*>(
        "_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEventERKNS2_5EventEP15sensors_event_t");
    auto aidlConvertToSensorEvent = sensorService.getSymbolAddress<void*>(
        "_ZN7android8hardware7sensors14implementation20convertToSensorEventERKN4aidl7android8hardware7sensors5EventEP15sensors_event_t");
    if (convertToSensorEvent == nullptr && aidlConvertToSensorEvent == nullptr) {
        LOGE("convertToSensorEvent symbol not found");
        return false;
    }
    if (convertToSensorEvent != nullptr) {
        OriginalConvertToSensorEvent = (OriginalConvertToSensorEventType)
            InlineHook(convertToSensorEvent, (void *) ConvertToSensorEvent);
    }
    if (aidlConvertToSensorEvent != nullptr) {
        OriginalAidlConvertToSensorEvent = (OriginalConvertToSensorEventType)
            InlineHook(aidlConvertToSensorEvent, (void *) ConvertToSensorEventAidl);
    }
    return OriginalConvertToSensorEvent != nullptr || OriginalAidlConvertToSensorEvent != nullptr;
}
