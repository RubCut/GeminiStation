#pragma once
#include <linux/fb.h>
#include <stdint.h>

constexpr int kDisplayWidth = 25, kDisplayHeight = 16, kFrameBytes = 1200;

// Reject unexpected layouts rather than guessing or changing kernel framebuffer configuration.
inline bool supported_display(const fb_fix_screeninfo& fix, const fb_var_screeninfo& var) {
    auto channel = [](const fb_bitfield& b) { return b.length == 8 && !b.msb_right && (b.offset == 0 || b.offset == 8 || b.offset == 16); };
    return var.xres == kDisplayWidth && var.yres == kDisplayHeight
        && var.xres_virtual >= kDisplayWidth && var.yres_virtual >= kDisplayHeight
        && var.xoffset == 0 && var.yoffset == 0 && var.bits_per_pixel == 24
        && var.nonstd == 0 && var.grayscale == 0 && var.rotate == 0
        && fix.type == FB_TYPE_PACKED_PIXELS && fix.visual == FB_VISUAL_TRUECOLOR
        && fix.line_length == kDisplayWidth * 3 && fix.smem_len >= kFrameBytes
        && var.transp.length == 0 && channel(var.red) && channel(var.green) && channel(var.blue)
        && var.red.offset != var.green.offset && var.red.offset != var.blue.offset && var.green.offset != var.blue.offset;
}
inline void display_reorder(const uint8_t* rgb, uint8_t* result, const fb_var_screeninfo& var) {
    for (int p = 0; p < kDisplayWidth * kDisplayHeight; ++p) {
        result[p * 3 + var.red.offset / 8] = rgb[p * 3];
        result[p * 3 + var.green.offset / 8] = rgb[p * 3 + 1];
        result[p * 3 + var.blue.offset / 8] = rgb[p * 3 + 2];
    }
}
