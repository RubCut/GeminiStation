"""Create the original Gemini Station animations for the 25 x 16 monochrome display.

The four-point mark follows the same curved silhouette as the app logo. The
centre is kept still and at a steady brightness so it stays legible at this
resolution. Subtle edge accents distinguish listening, thinking and speaking.
A full minute is encoded even for firmware which ignores GIF loop metadata;
the existing finite clear GIF is intentionally not changed.

Requires Pillow 11.3.0. Run from any directory.
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/led'
WIDTH, HEIGHT = 25, 16
FRAME_MS = 200
CYCLE_FRAMES = 12
CYCLES = 25  # 60 seconds: 12 * 25 * 200 ms
SUPERSAMPLE = 8


def curve(start, control1, control2, end, steps=24):
    for n in range(steps):
        t = n / steps
        yield tuple((1-t)**3 * start[i] + 3*(1-t)**2*t * control1[i]
                    + 3*(1-t)*t*t * control2[i] + t**3 * end[i] for i in (0, 1))


def logo():
    # Cubic curves echo gemini_star.xml, while preserving thin, crisp tips.
    points = []
    quadrants = [((12, .4), (12.7, 4.5), (15.1, 6.9), (19, 7.5)),
                 ((19, 7.5), (15.1, 8.1), (12.7, 10.5), (12, 14.6)),
                 ((12, 14.6), (11.3, 10.5), (8.9, 8.1), (5, 7.5)),
                 ((5, 7.5), (8.9, 6.9), (11.3, 4.5), (12, .4))]
    for segment in quadrants:
        points.extend(curve(*segment))
    image = Image.new('L', (WIDTH * SUPERSAMPLE, HEIGHT * SUPERSAMPLE))
    ImageDraw.Draw(image).polygon([(round(x * SUPERSAMPLE), round(y * SUPERSAMPLE)) for x, y in points], fill=210)
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)


def frame(mode, index, base):
    image = base.copy()
    draw = ImageDraw.Draw(image)
    p = index % CYCLE_FRAMES
    if mode == 'listening':
        # Two soft side dots appear to travel out from the mark and return.
        distance = (0, 0, 1, 1, 2, 2, 2, 2, 1, 1, 0, 0)[p]
        brightness = (62, 84, 105, 126, 145, 155, 155, 145, 126, 105, 84, 62)[p]
        draw.point((4 - distance, 7), fill=brightness)
        draw.point((20 + distance, 8), fill=brightness)
    elif mode == 'thinking':
        # A short orbit instead of rotating the tiny logo into an unreadable square.
        orbit = [(4, 4), (6, 2), (9, 1), (15, 1), (18, 2), (20, 4),
                 (20, 11), (18, 13), (15, 14), (9, 14), (6, 13), (4, 11)]
        x, y = orbit[p]
        draw.point((x, y), fill=180)
        x, y = orbit[(p - 1) % len(orbit)]
        draw.point((x, y), fill=65)
    elif mode == 'speaking':
        # Small, symmetric voice bars; the central mark does not pulse.
        heights = (1, 2, 3, 2, 1, 1, 2, 3, 3, 2, 1, 1)
        for x, h in ((2, heights[p]), (4, heights[(p + 3) % 12]),
                     (20, heights[(p + 3) % 12]), (22, heights[p])):
            for dy in range(h):
                draw.point((x, 7 - dy), fill=105 + dy * 25)
                if dy: draw.point((x, 7 + dy), fill=105 + dy * 25)
    else:
        raise ValueError(mode)
    # Prevent encoders merging identical frames into huge timing gaps. The
    # alternating 0/1 pixel is effectively black on the physical display.
    image.putpixel((24, 15), index & 1)
    image = image.convert('P')
    image.putpalette([component for level in range(256) for component in (level, level, level)])
    return image


def main():
    base = logo()
    for mode in ('listening', 'thinking', 'speaking'):
        frames = [frame(mode, i, base) for i in range(CYCLE_FRAMES * CYCLES)]
        frames[0].save(OUT / f'{mode}.gif', format='GIF', save_all=True,
                       append_images=frames[1:], duration=FRAME_MS,
                       loop=0, disposal=2, optimize=False)


if __name__ == '__main__':
    main()
