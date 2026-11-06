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
struct Display { int fd; fb_var_screeninfo var; };
}
extern "C" JNIEXPORT jlong JNICALL
Java_ru_geministation_RootDisplayHelper_openDisplay(JNIEnv*, jclass) {
    int fd = open("/dev/graphics/fb4", O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat statbuf{}; fb_fix_screeninfo fix{}; fb_var_screeninfo var{};
    if (fstat(fd, &statbuf) || !S_ISCHR(statbuf.st_mode) || ioctl(fd, FBIOGET_FSCREENINFO, &fix)
            || ioctl(fd, FBIOGET_VSCREENINFO, &var) || !supported_display(fix, var)) { close(fd); return -2; }
    auto display = std::unique_ptr<Display>(new (std::nothrow) Display{fd, var});
    if (!display) { close(fd); return -3; }
    return reinterpret_cast<jlong>(display.release());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_ru_geministation_RootDisplayHelper_writeFrame(JNIEnv* env, jclass, jlong handle, jbyteArray input) {
    auto* display = reinterpret_cast<Display*>(handle);
    if (!display || !input || env->GetArrayLength(input) != kFrameBytes) return JNI_FALSE;
    uint8_t rgb[kFrameBytes], packed[kFrameBytes];
    env->GetByteArrayRegion(input, 0, kFrameBytes, reinterpret_cast<jbyte*>(rgb));
    if (env->ExceptionCheck()) return JNI_FALSE;
    display_reorder(rgb, packed, display->var);
    if (lseek(display->fd, 0, SEEK_SET) != 0) return JNI_FALSE;
    size_t done = 0;
    while (done < sizeof(packed)) {
        ssize_t n = write(display->fd, packed + done, sizeof(packed) - done);
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
