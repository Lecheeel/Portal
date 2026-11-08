#include <dobby.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include "sensor_hook.h"
#include <atomic>
#include <algorithm>

std::atomic_bool enableSensorHook{false};

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
    if (!status) clearNativeSensorFrames();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_system_location_service_jni_Dobby_setSensorFrame(JNIEnv *env, jobject thiz,
        jlong stepCount, jint stepCadence, jlong syncNanos,
        jfloatArray accelerometer, jfloatArray gyroscope, jfloatArray magnetometer,
        jfloat barometerHpa) {
    if (accelerometer == nullptr || gyroscope == nullptr || magnetometer == nullptr) return;
    if (env->GetArrayLength(accelerometer) < 3 || env->GetArrayLength(gyroscope) < 3 ||
        env->GetArrayLength(magnetometer) < 3) return;

    NativeSensorFrame frame{};
    frame.stepCount = stepCount;
    frame.stepCadence = stepCadence;
    frame.syncNanos = syncNanos;
    frame.barometerHpa = barometerHpa;

    auto copyThree = [env](jfloatArray source, float* destination) {
        jfloat* values = env->GetFloatArrayElements(source, nullptr);
        if (values == nullptr) return false;
        std::copy(values, values + 3, destination);
        env->ReleaseFloatArrayElements(source, values, JNI_ABORT);
        return true;
    };
    if (!copyThree(accelerometer, frame.accelerometer) ||
        !copyThree(gyroscope, frame.gyroscope) ||
        !copyThree(magnetometer, frame.magnetometer)) return;
    setNativeSensorFrame(frame);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_system_location_service_jni_Dobby_prepareSensors(JNIEnv *, jobject) {
    clearNativeSensorFrames();
    enableSensorHook.store(false);
    return doSensorHook();
}
