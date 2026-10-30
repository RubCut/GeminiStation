"""Generate original 25x16 Gemini LED animations. Requires Pillow.

Each file contains a minute of repeated frames for firmware that ignores GIF
loop metadata, plus explicit looping like Station Max Studio. The app sends a
separate short clear GIF when the dialog ends.
"""
import math
from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/led"
FRAME_MS = 200
CYCLE_FRAMES = 12
CYCLES = 25  # 60 seconds

for mode in ("listening", "thinking", "speaking"):
    frames = []
    for index in range(CYCLE_FRAMES * CYCLES):
        phase = 2 * math.pi * (index % CYCLE_FRAMES) / CYCLE_FRAMES
        if mode == "thinking":
            angle = phase / 4  # 90-degree symmetry makes the loop seamless
        elif mode == "speaking":
            angle = 0.18 * math.sin(phase)
        else:
            angle = 0.10 * math.sin(phase)
        image = Image.new("L", (25, 16), 0)
        for y in range(16):
            for x in range(25):
                xx, yy = x - 12, y - 7.5
                rotated_x = xx * math.cos(angle) - yy * math.sin(angle)
                rotated_y = xx * math.sin(angle) + yy * math.cos(angle)
                if (abs(rotated_x) / 7) ** .60 + (abs(rotated_y) / 7) ** .60 < 1:
                    image.putpixel((x, y), 210)  # no brightness pulse
        # Preserve each frame, including visually identical consecutive frames.
        image.putpixel((24, 15), index & 1)
        frames.append(image.convert("P"))
    frames[0].save(OUT / f"{mode}.gif", format="GIF", save_all=True,
                   append_images=frames[1:], duration=FRAME_MS,
                   loop=0, disposal=2, optimize=False)
