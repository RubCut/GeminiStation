#pragma once
#include <linux/fb.h>
#include <stdint.h>

constexpr int kDisplayWidth = 25, kDisplayHeight = 16, kFrameBytes = 1200;
constexpr int kRingWidth = 24, kRingHeight = 1, kRingBytes = 72;

// Reject unexpected layouts rather than guessing or changing kernel framebuffer configuration.
inline bool supported_rgb24(const fb_fix_screeninfo& fix, const fb_var_screeninfo& var, int width, int height) {
    auto channel = [](const fb_bitfield& b) { return b.length == 8 && !b.msb_right && (b.offset == 0 || b.offset == 8 || b.offset == 16); };
    return var.xres == static_cast<unsigned>(width) && var.yres == static_cast<unsigned>(height)
        && var.xres_virtual >= static_cast<unsigned>(width) && var.yres_virtual >= static_cast<unsigned>(height)
        && var.xoffset == 0 && var.yoffset == 0 && var.bits_per_pixel == 24
        && var.nonstd == 0 && var.grayscale == 0 && var.rotate == 0
        && fix.type == FB_TYPE_PACKED_PIXELS && fix.visual == FB_VISUAL_TRUECOLOR
        && fix.line_length == static_cast<unsigned>(width * 3) && fix.smem_len >= static_cast<unsigned>(width * height * 3)
        && var.transp.length == 0 && channel(var.red) && channel(var.green) && channel(var.blue)
        && var.red.offset != var.green.offset && var.red.offset != var.blue.offset && var.green.offset != var.blue.offset;
}
inline bool supported_display(const fb_fix_screeninfo& fix, const fb_var_screeninfo& var) {
    return supported_rgb24(fix, var, kDisplayWidth, kDisplayHeight);
}
inline bool supported_ring(const fb_fix_screeninfo& fix, const fb_var_screeninfo& var) {
    return supported_rgb24(fix, var, kRingWidth, kRingHeight);
}
inline void rgb_reorder(const uint8_t* rgb, uint8_t* result, const fb_var_screeninfo& var, int pixels) {
    for (int p = 0; p < pixels; ++p) {
        result[p * 3 + var.red.offset / 8] = rgb[p * 3];
        result[p * 3 + var.green.offset / 8] = rgb[p * 3 + 1];
        result[p * 3 + var.blue.offset / 8] = rgb[p * 3 + 2];
    }
}
inline void display_reorder(const uint8_t* rgb, uint8_t* result, const fb_var_screeninfo& var) {
    rgb_reorder(rgb, result, var, kDisplayWidth * kDisplayHeight);
}
