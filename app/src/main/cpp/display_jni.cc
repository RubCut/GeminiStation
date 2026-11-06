#include <jni.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <memory>
#include <new>
#include "display_format.h"

namespace {
struct Display { int fd; fb_var_screeninfo var; int bytes; };
jlong open_framebuffer(const char* path, bool ring) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat statbuf{}; fb_fix_screeninfo fix{}; fb_var_screeninfo var{};
    if (fstat(fd, &statbuf) || !S_ISCHR(statbuf.st_mode) || ioctl(fd, FBIOGET_FSCREENINFO, &fix)
            || ioctl(fd, FBIOGET_VSCREENINFO, &var)
            || !(ring ? supported_ring(fix, var) : supported_display(fix, var))) { close(fd); return -2; }
    auto display = std::unique_ptr<Display>(new (std::nothrow) Display{fd, var, ring ? kRingBytes : kFrameBytes});
    if (!display) { close(fd); return -3; }
    return reinterpret_cast<jlong>(display.release());
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_ru_geministation_RootDisplayHelper_openDisplay(JNIEnv*, jclass) {
    return open_framebuffer("/dev/graphics/fb4", false);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_ru_geministation_RootDisplayHelper_writeFrame(JNIEnv* env, jclass, jlong handle, jbyteArray input) {
    auto* display = reinterpret_cast<Display*>(handle);
    if (!display || !input || env->GetArrayLength(input) != display->bytes) return JNI_FALSE;
    uint8_t rgb[kFrameBytes], packed[kFrameBytes];
    env->GetByteArrayRegion(input, 0, display->bytes, reinterpret_cast<jbyte*>(rgb));
    if (env->ExceptionCheck()) return JNI_FALSE;
    rgb_reorder(rgb, packed, display->var, display->bytes / 3);
    if (lseek(display->fd, 0, SEEK_SET) != 0) return JNI_FALSE;
    size_t done = 0;
    while (done < static_cast<size_t>(display->bytes)) {
        ssize_t n = write(display->fd, packed + done, display->bytes - done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return JNI_FALSE;
        done += static_cast<size_t>(n);
    }
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL
Java_ru_geministation_RootDisplayHelper_closeDisplay(JNIEnv*, jclass, jlong handle) {
    auto* display = reinterpret_cast<Display*>(handle);
    if (display) { close(display->fd); delete display; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_geministation_RootRingHelper_openRing(JNIEnv*, jclass) {
    return open_framebuffer("/dev/graphics/fb5", true);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_ru_geministation_RootRingHelper_writeFrame(JNIEnv* env, jclass type, jlong handle, jbyteArray input) {
    return Java_ru_geministation_RootDisplayHelper_writeFrame(env, type, handle, input);
}
extern "C" JNIEXPORT void JNICALL
Java_ru_geministation_RootRingHelper_closeRing(JNIEnv* env, jclass type, jlong handle) {
    Java_ru_geministation_RootDisplayHelper_closeDisplay(env, type, handle);
}
