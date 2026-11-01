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
