"""Combine the old motion curve with the newer antialiased mark and accents."""
import math
from PIL import Image, ImageDraw
from generate_led_animations import OUT, WIDTH, HEIGHT, FRAME_MS, CYCLE_FRAMES, CYCLES, SUPERSAMPLE, curve, frame


def mark():
    p = []
    for seg in [((12,.4),(12.7,4.5),(15.1,6.9),(19,7.5)), ((19,7.5),(15.1,8.1),(12.7,10.5),(12,14.6)),
                ((12,14.6),(11.3,10.5),(8.9,8.1),(5,7.5)), ((5,7.5),(8.9,6.9),(11.3,4.5),(12,.4))]:
        p.extend(curve(*seg))
    im = Image.new('L',(WIDTH*SUPERSAMPLE,HEIGHT*SUPERSAMPLE))
    ImageDraw.Draw(im).polygon([(round(x*SUPERSAMPLE),round(y*SUPERSAMPLE)) for x,y in p],fill=210)
    return im


def main():
    dest = OUT / 'hybrid'; dest.mkdir(parents=True, exist_ok=True)
    base = mark()
    for mode in ('listening','thinking','speaking'):
        frames = []
        for i in range(CYCLE_FRAMES * CYCLES):
            phase = 2 * math.pi * (i % CYCLE_FRAMES) / CYCLE_FRAMES
            angle = phase / 4 if mode == 'thinking' else (0.18 if mode == 'speaking' else 0.10) * math.sin(phase)
            rotated = base.rotate(-math.degrees(angle), resample=Image.Resampling.BICUBIC,
                                  center=(12*SUPERSAMPLE,7.5*SUPERSAMPLE))
            image = rotated.resize((WIDTH,HEIGHT),Image.Resampling.LANCZOS)
            frames.append(frame(mode,i,image))
        frames[0].save(dest / f'{mode}.gif',save_all=True,append_images=frames[1:],duration=FRAME_MS,loop=0,disposal=2,optimize=False)

if __name__ == '__main__': main()
