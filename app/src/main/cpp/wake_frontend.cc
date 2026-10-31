#include "wake_frontend.h"
#include <cstdlib>
#include <cstring>
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"

struct WakeFrontend { FrontendConfig config; FrontendState state; };

void* wake_frontend_create() {
    auto* f = static_cast<WakeFrontend*>(std::calloc(1, sizeof(WakeFrontend)));
    if (!f) return nullptr;
    // Matches pymicro-features 2.0.2 and the training preprocessing exactly.
    auto& c = f->config;
    c.window.size_ms = 30; c.window.step_size_ms = 10;
    c.filterbank.num_channels = 40;
    c.filterbank.lower_band_limit = 125.0f; c.filterbank.upper_band_limit = 7500.0f;
    c.noise_reduction.smoothing_bits = 10;
    c.noise_reduction.even_smoothing = .025f; c.noise_reduction.odd_smoothing = .06f;
    c.noise_reduction.min_signal_remaining = .05f;
    c.pcan_gain_control.enable_pcan = 1; c.pcan_gain_control.strength = .95f;
    c.pcan_gain_control.offset = 80.f; c.pcan_gain_control.gain_bits = 21;
    c.log_scale.enable_log = 1; c.log_scale.scale_shift = 6;
    if (!FrontendPopulateState(&c, &f->state, 16000)) {
        FrontendFreeStateContents(&f->state); std::free(f); return nullptr;
    }
    return f;
}
int wake_frontend_process(void* handle, const int16_t* samples, float* output) {
    if (!handle || !samples || !output) return -1;
    auto* f = static_cast<WakeFrontend*>(handle);
    size_t read = 0;
    FrontendOutput out = FrontendProcessSamples(&f->state, samples, 160, &read);
    if (read != 160 || (out.size != 0 && out.size != 40)) return -1;
    for (size_t i = 0; i < out.size; ++i) output[i] = out.values[i] * 0.0390625f;
    return static_cast<int>(out.size);
}
void wake_frontend_destroy(void* handle) {
    if (!handle) return;
    auto* f = static_cast<WakeFrontend*>(handle);
    FrontendFreeStateContents(&f->state); std::free(f);
}
