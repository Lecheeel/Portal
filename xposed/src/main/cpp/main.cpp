
#include <dobby.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include "sensor_hook.h"
#include <atomic>

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
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_system_location_service_jni_Dobby_prepareSensors(JNIEnv *, jobject) {
    return doSensorHook();
}
