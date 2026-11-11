// Experimental libc path rewriting for explicitly selected application processes.
//
// This is deliberately *not* a general-purpose root hider. It only changes the answers of the
// process's own libc file calls while the user enables the feature; a native scanner using raw
// syscalls can bypass it. The default is disabled and the Kotlin controller decides when to load
// the library.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>

#include "dobby_hook.h"

#define LOG_TAG "CodecExt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::atomic_bool gEnabled{false};
std::atomic_bool gHooked{false};

using OpenFn = int (*)(const char*, int, ...);
using OpenAtFn = int (*)(int, const char*, int, ...);
using FopenFn = FILE* (*)(const char*, const char*);
using AccessFn = int (*)(const char*, int);
using FAccessAtFn = int (*)(int, const char*, int, int);
using StatFn = int (*)(const char*, struct stat*);
using LstatFn = int (*)(const char*, struct stat*);
using ReadlinkFn = ssize_t (*)(const char*, char*, size_t);
using OpendirFn = DIR* (*)(const char*);

OpenFn gOpen = nullptr;
OpenAtFn gOpenAt = nullptr;
FopenFn gFopen = nullptr;
FopenFn gFopen64 = nullptr;
AccessFn gAccess = nullptr;
FAccessAtFn gFAccessAt = nullptr;
StatFn gStat = nullptr;
LstatFn gLstat = nullptr;
ReadlinkFn gReadlink = nullptr;
OpendirFn gOpendir = nullptr;

bool replaceSegment(const char* path, const char* needle, const char* replacement,
                    char* out, size_t size) {
    size_t needleLen = strlen(needle);
    for (const char* at = strstr(path, needle); at != nullptr; at = strstr(at + 1, needle)) {
        bool left = at == path || at[-1] == '/';
        bool right = at[needleLen] == '\0' || at[needleLen] == '/';
        if (!left || !right) continue;
        size_t prefix = static_cast<size_t>(at - path);
        if (prefix + strlen(replacement) + strlen(at + needleLen) + 1 > size) return false;
        memcpy(out, path, prefix);
        strcpy(out + prefix, replacement);
        strcat(out, at + needleLen);
        return true;
    }
    return false;
}

/** Returns either the original pointer or a thread-local rewritten path. */
const char* rewrite(const char* path) {
    if (!gEnabled.load(std::memory_order_relaxed) || path == nullptr) return path;
    static thread_local char buffer[PATH_MAX];
    // Reading one's own map is the standard way to notice an injected library. Returning the
    // status file makes the read look like a normal /proc probe instead of a maps listing.
    if (strstr(path, "/proc/") != nullptr && strstr(path, "/maps") != nullptr) {
        snprintf(buffer, sizeof(buffer), "/proc/self/status");
        return buffer;
    }
    if (strstr(path, "/data/adb") != nullptr) {
        snprintf(buffer, sizeof(buffer), "/data/_null");
        return buffer;
    }
    if (replaceSegment(path, "/su", "/su_f", buffer, sizeof(buffer))) return buffer;
    if (replaceSegment(path, "/sbin", "/etc", buffer, sizeof(buffer))) return buffer;
    return path;
}

int hookOpen(const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    return gOpen != nullptr ? gOpen(rewrite(path), flags, mode) : -1;
}

int hookOpenAt(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    const char* effective = (path != nullptr && path[0] == '/') ? rewrite(path) : path;
    return gOpenAt != nullptr ? gOpenAt(dirfd, effective, flags, mode) : -1;
}

FILE* hookFopen(const char* path, const char* mode) {
    return gFopen != nullptr ? gFopen(rewrite(path), mode) : nullptr;
}

FILE* hookFopen64(const char* path, const char* mode) {
    return gFopen64 != nullptr ? gFopen64(rewrite(path), mode) : nullptr;
}

int hookAccess(const char* path, int mode) {
    return gAccess != nullptr ? gAccess(rewrite(path), mode) : -1;
}

int hookFAccessAt(int dirfd, const char* path, int mode, int flags) {
    const char* effective = (path != nullptr && path[0] == '/') ? rewrite(path) : path;
    return gFAccessAt != nullptr ? gFAccessAt(dirfd, effective, mode, flags) : -1;
}

int hookStat(const char* path, struct stat* info) {
    return gStat != nullptr ? gStat(rewrite(path), info) : -1;
}

int hookLstat(const char* path, struct stat* info) {
    return gLstat != nullptr ? gLstat(rewrite(path), info) : -1;
}

ssize_t hookReadlink(const char* path, char* buffer, size_t size) {
    return gReadlink != nullptr ? gReadlink(rewrite(path), buffer, size) : -1;
}

DIR* hookOpendir(const char* path) {
    return gOpendir != nullptr ? gOpendir(rewrite(path)) : nullptr;
}

template <typename T>
T resolve(const char* name) {
    return reinterpret_cast<T>(dlsym(RTLD_DEFAULT, name));
}

/** Dobby returns the trampoline; that pointer, not the patched target, must be called. */
template <typename T>
bool hookSymbol(const char* name, void* hook, T* original) {
    T target = resolve<T>(name);
    if (target == nullptr) return false;
    void* trampoline = InlineHook(reinterpret_cast<void*>(target), hook);
    if (trampoline == nullptr) return false;
    *original = reinterpret_cast<T>(trampoline);
    return true;
}

bool ensureHooks() {
    if (gHooked.exchange(true)) return true;
    bool any = false;
    any |= hookSymbol<OpenFn>("open", reinterpret_cast<void*>(hookOpen), &gOpen);
    any |= hookSymbol<OpenAtFn>("openat", reinterpret_cast<void*>(hookOpenAt), &gOpenAt);
    any |= hookSymbol<FopenFn>("fopen", reinterpret_cast<void*>(hookFopen), &gFopen);
    // Some ABIs alias fopen64 to fopen; do not patch the same address twice.
    if (resolve<FopenFn>("fopen64") != resolve<FopenFn>("fopen")) {
        any |= hookSymbol<FopenFn>("fopen64", reinterpret_cast<void*>(hookFopen64), &gFopen64);
    }
    any |= hookSymbol<AccessFn>("access", reinterpret_cast<void*>(hookAccess), &gAccess);
    any |= hookSymbol<FAccessAtFn>("faccessat", reinterpret_cast<void*>(hookFAccessAt), &gFAccessAt);
    any |= hookSymbol<StatFn>("stat", reinterpret_cast<void*>(hookStat), &gStat);
    if (reinterpret_cast<void*>(resolve<LstatFn>("lstat")) !=
        reinterpret_cast<void*>(resolve<StatFn>("stat"))) {
        any |= hookSymbol<LstatFn>("lstat", reinterpret_cast<void*>(hookLstat), &gLstat);
    }
    any |= hookSymbol<ReadlinkFn>("readlink", reinterpret_cast<void*>(hookReadlink), &gReadlink);
    any |= hookSymbol<OpendirFn>("opendir", reinterpret_cast<void*>(hookOpendir), &gOpendir);
    if (!any) LOGE("no libc file API could be hooked");
    return any;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_system_location_service_jni_AntiDetect_nativeSetEnabled(JNIEnv*, jobject, jboolean enabled) {
    if (enabled && !ensureHooks()) return JNI_FALSE;
    gEnabled.store(enabled == JNI_TRUE);
    LOGI("anti-detect enabled=%d", enabled);
    return JNI_TRUE;
}
