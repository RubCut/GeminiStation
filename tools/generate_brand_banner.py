"""Regenerate the 320 x 180 launcher banner. Requires Pillow and DejaVu Sans."""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from generate_led_animations import curve

SCALE = 4
W, H = 320 * SCALE, 180 * SCALE
image = Image.new('RGB', (W, H))
pixels = image.load()
for y in range(H):
    for x in range(W):
        # A restrained violet wash and a subtle halo behind the mark.
        halo = max(0., 1 - math.hypot(x / SCALE - 67, y / SCALE - 87) / 105) ** 2
        blend = (x / W * .4 + y / H * .6)
        pixels[x, y] = (round(28 + 8*halo + 3*blend), round(27 + 5*halo + 2*blend), round(37 + 20*halo + 5*blend))

draw = ImageDraw.Draw(image)
def point(x, y): return (round(x*SCALE), round(y*SCALE))
segments = [((67,42),(70.8,64),(85,82),(107,87)), ((107,87),(85,92),(70.8,110),(67,132)),
            ((67,132),(63.2,110),(49,92),(27,87)), ((27,87),(49,82),(63.2,64),(67,42))]
shape = []
for segment in segments: shape.extend(point(x,y) for x,y in curve(*segment, steps=40))
mask = Image.new('L',(W,H)); ImageDraw.Draw(mask).polygon(shape, fill=255)
gradient = Image.new('RGB',(W,H)); grad = gradient.load()
for y in range(H):
    for x in range(W):
        t = min(1.,max(0.,(x / SCALE - 25 + 135 - y / SCALE) / 160))
        grad[x,y] = (round(111+125*t), round(170+6*t), round(255-34*t))
image.paste(gradient,(0,0),mask)
bold = ImageFont.truetype('DejaVuSans-Bold.ttf', 27*SCALE)
regular = ImageFont.truetype('DejaVuSans.ttf', 16*SCALE)
draw = ImageDraw.Draw(image)
draw.text(point(128,56),'Gemini',font=bold,fill='#F1ECF8',stroke_width=0)
draw.text(point(129,95),'Station',font=regular,fill='#CFC5DA')
out = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi/banner.png'
out.parent.mkdir(parents=True, exist_ok=True)
image.resize((320,180),Image.Resampling.LANCZOS).save(out,optimize=True)
