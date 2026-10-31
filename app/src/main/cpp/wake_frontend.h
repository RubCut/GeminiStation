#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
void* wake_frontend_create();
// Process exactly 160 mono PCM samples. Returns 0 (warming up), 40 or -1.
int wake_frontend_process(void* handle, const int16_t* samples, float* output);
void wake_frontend_destroy(void* handle);
#ifdef __cplusplus
}
#endif
