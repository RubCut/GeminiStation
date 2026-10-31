#include <jni.h>
#include <cstdint>
#include "wake_frontend.h"

extern "C" JNIEXPORT jlong JNICALL
Java_ru_geministation_MicroFrontend_nativeCreate(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(wake_frontend_create()));
}
extern "C" JNIEXPORT jint JNICALL
Java_ru_geministation_MicroFrontend_nativeProcess(JNIEnv* env, jclass, jlong ptr,
                                                   jshortArray pcm, jint offset, jfloatArray features) {
    if (!ptr || !pcm || !features || offset < 0 || env->GetArrayLength(pcm) - offset < 160 || env->GetArrayLength(features) < 40) return -1;
    jshort samples[160]; float output[40];
    env->GetShortArrayRegion(pcm, offset, 160, samples);
    if (env->ExceptionCheck()) return -1;
    int n = wake_frontend_process(reinterpret_cast<void*>(static_cast<intptr_t>(ptr)), samples, output);
    if (n == 40) env->SetFloatArrayRegion(features, 0, 40, output);
    return n;
}
extern "C" JNIEXPORT void JNICALL
Java_ru_geministation_MicroFrontend_nativeDestroy(JNIEnv*, jclass, jlong ptr) {
    wake_frontend_destroy(reinterpret_cast<void*>(static_cast<intptr_t>(ptr)));
}
