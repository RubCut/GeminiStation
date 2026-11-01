#!/usr/bin/env python3
"""Check the display GIFs without depending on the physical station."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1] / 'app/src/main/assets/led'
centres = {}
for mode in ('listening', 'thinking', 'speaking'):
    im = Image.open(root / (mode + '.gif'))
    assert im.size == (25, 16) and im.n_frames == 300 and im.info.get('loop') == 0, mode
    total = 0
    logo = None
    distinct = set()
    for i in range(im.n_frames):
        im.seek(i)
        assert im.info['duration'] == 200, (mode, i)
        total += im.info['duration']
        frame = im.convert('L')
        centre = frame.crop((9, 4, 16, 12)).tobytes()
        logo = centre if logo is None else logo
        assert centre == logo, (mode, i, 'logo should not flash or move')
        assert frame.getpixel((12, 7)) >= 180, (mode, i, 'no blank frames')
        distinct.add(frame.tobytes())
    assert total == 60000 and len(distinct) >= 5, mode
    centres[mode] = logo
assert len(set(centres.values())) == 1, 'same brand mark for all states'
clear = Image.open(root / 'clear.gif')
assert clear.size == (25, 16) and clear.info.get('loop') is None
for i in range(clear.n_frames):
    clear.seek(i)
    assert clear.convert('L').getextrema()[1] <= 1, 'clear must remain effectively black'
print('LED animations: fixed logo, 60-second loops, distinct phases and finite clear')

# The user's former animations must remain byte-identical, and the hybrid keeps
# the former rotation/rocking while rendering the newer antialiased mark.
import hashlib
old_hashes = {
    'listening':'503837598e457c4ad473270b2301040a59c496839dc9b7daae4586d27b2b19d7',
    'thinking':'9971711d04f13200c3a6c1f79d4f50bc9c6542bb8a1bf06e76e28c93079512cf',
    'speaking':'c007e22973def52ddccef6627f63363ae55aa2c44ec0ddccbaf999fe01b2691c',
}
for mode, expected in old_hashes.items():
    assert hashlib.sha256((root / 'classic' / (mode+'.gif')).read_bytes()).hexdigest() == expected
    for style in ('classic', 'hybrid'):
        im = Image.open(root / style / (mode+'.gif'))
        assert im.size == (25,16) and im.n_frames == 300 and im.info.get('loop') == 0, (style,mode)
        frames = []
        for i in range(12):
            im.seek(i); assert im.info['duration'] == 200
            frame = im.convert('L'); assert frame.getpixel((12,7)) > 100
            frames.append(frame.crop((5,1,20,15)).tobytes())
        assert len(set(frames)) >= 5, (style,mode,'the main mark should move')
print('Classic assets retained; hybrid motion and one-minute loops verified')
