#include "../../app/src/main/cpp/display_format.h"
#include <cassert>
#include <cstring>
int main() {
    fb_fix_screeninfo fix{}; fb_var_screeninfo var{};
    fix.type = FB_TYPE_PACKED_PIXELS; fix.visual = FB_VISUAL_TRUECOLOR;
    fix.line_length = 75; fix.smem_len = 1200;
    var.xres = var.xres_virtual = 25; var.yres = var.yres_virtual = 16; var.bits_per_pixel = 24;
    var.red.length = var.green.length = var.blue.length = 8;
    var.red.offset = 0; var.green.offset = 8; var.blue.offset = 16;
    assert(supported_display(fix, var));
    uint8_t in[kFrameBytes] = {}, out[kFrameBytes] = {};
    in[0] = 17; in[1] = 42; in[2] = 99;
    in[kFrameBytes - 3] = 7; in[kFrameBytes - 2] = 8; in[kFrameBytes - 1] = 9;
    display_reorder(in, out, var); assert(out[0] == 17 && out[1] == 42 && out[2] == 99);
    var.red.offset = 16; var.blue.offset = 0;
    assert(supported_display(fix, var)); display_reorder(in, out, var);
    assert(out[0] == 99 && out[1] == 42 && out[2] == 17);
    assert(out[kFrameBytes - 3] == 9 && out[kFrameBytes - 2] == 8 && out[kFrameBytes - 1] == 7);
    var.blue.offset = 16; assert(!supported_display(fix, var)); var.blue.offset = 0;
    fix.line_length = 76; assert(!supported_display(fix, var)); fix.line_length = 75;
    fix.smem_len = 1199; assert(!supported_display(fix, var)); fix.smem_len = 1200;
    var.yoffset = 1; assert(!supported_display(fix, var)); var.yoffset = 0;
    var.bits_per_pixel = 32; assert(!supported_display(fix, var)); var.bits_per_pixel = 24;
    var.nonstd = 1; assert(!supported_display(fix, var)); var.nonstd = 0;
    fix.visual = FB_VISUAL_PSEUDOCOLOR; assert(!supported_display(fix, var));
}
