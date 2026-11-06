
#include <dobby.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include "sensor_hook.h"
#include <atomic>

std::atomic_bool enableSensorHook{false};
std::atomic<int32_t> g_sensorMode{0};
std::atomic<float> g_sensorSpeed{0.0f};
std::atomic<float> g_sensorBearing{0.0f};
std::atomic<float> g_sensorAltitude{80.0f};

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_system_location_service_jni_Dobby_setStatus(JNIEnv *env, jobject thiz, jboolean status) {
    enableSensorHook.store(status);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_system_location_service_jni_Dobby_setMotionState(JNIEnv *env, jobject thiz,
        jint mode, jfloat speed, jfloat bearing, jfloat altitude) {
    g_sensorMode.store(mode);
    g_sensorSpeed.store(speed);
    g_sensorBearing.store(bearing);
    g_sensorAltitude.store(altitude);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_system_location_service_jni_Dobby_prepareSensors(JNIEnv *, jobject) {
    return doSensorHook();
}
