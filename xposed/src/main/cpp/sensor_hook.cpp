//
// Created by fuqiuluo on 2024/10/15.
//
#include <dobby.h>
#include <unistd.h>
#include <cmath>
#include "sensor_hook.h"
#include "logging.h"
#include "elf_util.h"
#include "dobby_hook.h"
#include <atomic>

#define LIBSF_PATH "/system/lib64/libsensorservice.so"

extern std::atomic_bool enableSensorHook;
extern std::atomic<int32_t> g_sensorMode;
extern std::atomic<float> g_sensorSpeed;
extern std::atomic<float> g_sensorBearing;
extern std::atomic<float> g_sensorAltitude;

// _ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventm
OriginalSensorEventQueueWriteType OriginalSensorEventQueueWrite = nullptr;

OriginalConvertToSensorEventType OriginalConvertToSensorEvent = nullptr;

int64_t SensorEventQueueWrite(void *tube, void *events, int64_t numEvents) {
    return OriginalSensorEventQueueWrite(tube, events, numEvents);
}

void ConvertToSensorEvent(void *src, void *dst) {
    OriginalConvertToSensorEvent(src, dst);
    if (!enableSensorHook.load()) return;

    auto *event = reinterpret_cast<ASensorEvent *>(dst);
    if (event == nullptr) return;

    const double t = static_cast<double>(event->timestamp) / 1e9;
    constexpr double kPi = 3.14159265358979323846;
    const int mode = g_sensorMode.load();
    const float bearing = g_sensorBearing.load();
    const float altitude = g_sensorAltitude.load();

    if (event->type == 1) { // accelerometer
        float x = 0.0f, y = 0.0f, z = 9.81f;
        if (mode == 1) { // walk
            x = static_cast<float>(0.45 * std::sin(2 * kPi * 1.6 * t));
            z += static_cast<float>(1.4 * std::sin(2 * kPi * 1.6 * t));
        } else if (mode == 2) { // run
            x = static_cast<float>(1.2 * std::sin(2 * kPi * 2.7 * t));
            z += static_cast<float>(2.8 * std::sin(2 * kPi * 2.7 * t));
        } else if (mode == 3) { // vehicle
            x = static_cast<float>(0.2 * std::sin(2 * kPi * 8.0 * t));
            z += static_cast<float>(0.15 * std::sin(2 * kPi * 8.0 * t));
        }
        event->data[0] = x;
        event->data[1] = y;
        event->data[2] = z;
    } else if (event->type == 4) { // gyroscope
        event->data[0] = 0.003f;
        event->data[1] = 0.003f;
        event->data[2] = mode == 1 ? static_cast<float>(0.08 * std::sin(2 * kPi * 1.6 * t))
                : mode == 2 ? static_cast<float>(0.22 * std::sin(2 * kPi * 2.7 * t))
                : 0.005f;
    } else if (event->type == 2) { // magnetic field
        const double rad = bearing * kPi / 180.0;
        event->data[0] = static_cast<float>(std::cos(rad) * 25.0);
        event->data[1] = static_cast<float>(std::sin(rad) * 25.0);
        event->data[2] = -40.0f;
    } else if (event->type == 6) { // pressure hPa
        const double pressure = 1013.25 * std::pow(1.0 - 2.25577e-5 * altitude, 5.25588);
        event->data[0] = static_cast<float>(pressure);
    } else if (event->type == 19) { // step counter
        const double cadence = mode == 1 ? 105.0 : mode == 2 ? 170.0 : 0.0;
        event->data[0] = static_cast<float>(t * cadence / 60.0);
    } else if (event->type == 18) { // step detector
        event->data[0] = (mode == 1 || mode == 2) ? 1.0f : 0.0f;
    }
}

bool doSensorHook() {
    if (OriginalConvertToSensorEvent != nullptr) return true;
    SandHook::ElfImg sensorService(LIBSF_PATH);

    if (!sensorService.isValid()) {
        LOGE("failed to load libsensorservice");
        return false;
    }

    auto sensorWrite = sensorService.getSymbolAddress<void*>("_ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventm");
    if (sensorWrite == nullptr) {
        sensorWrite = sensorService.getSymbolAddress<void*>("_ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventj");
    }

    auto convertToSensorEvent = sensorService.getSymbolAddress<void*>("_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEventERKNS2_5EventEP15sensors_event_t");

    LOGD("Dobby SensorEventQueue::write found at %p", sensorWrite);
    LOGD("Dobby convertToSensorEvent found at %p", convertToSensorEvent);

    if (sensorWrite != nullptr && OriginalSensorEventQueueWrite == nullptr) {
        OriginalSensorEventQueueWrite = (OriginalSensorEventQueueWriteType)InlineHook(sensorWrite, (void *)SensorEventQueueWrite);
    }

    if (convertToSensorEvent != nullptr) {
        OriginalConvertToSensorEvent = (OriginalConvertToSensorEventType)InlineHook(convertToSensorEvent, (void *)ConvertToSensorEvent);
    }
    return OriginalConvertToSensorEvent != nullptr;
}

