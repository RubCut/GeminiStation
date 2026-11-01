#!/usr/bin/env python3
"""Verify the locally bundled OFL font has Cyrillic and the notices ship with it."""
from pathlib import Path
from hashlib import sha256
from fontTools.ttLib import TTFont
root = Path(__file__).resolve().parents[1]
font = root / 'app/src/main/assets/fonts/google_sans.ttf'
assert sha256(font.read_bytes()).hexdigest() == 'd0a87d835a944b8b40d0e82a5651bb59ab97b936a2aeed5946eb57e7b2a3a90a'
f = TTFont(font)
assert f['name'].getDebugName(1) == 'Google Sans'
assert all(ord(c) in f.getBestCmap() for c in 'Gemini Станция Ёж Щука 123')
for name in ('google-sans-ofl.txt','google-sans-trademarks.md','material-icons-apache-2.0.txt'):
    assert (root/'licenses'/name).read_bytes() == (root/'app/src/main/assets/licenses'/name).read_bytes()
html=(root/'app/src/main/assets/index.html').read_text()
server=(root/'app/src/main/java/ru/geministation/WebServerService.java').read_text()
assert '/api/fonts/google-sans.ttf' in html and '/api/fonts/google-sans.ttf' in server
assert '--font:"Google Sans",Roboto' in html
assert "font-src 'self'" in server and "img-src 'self' data:" in server
print('Google Sans Cyrillic, local serving, Material icon notices and CSP verified')
import re
for name, body in re.findall(r'<symbol id="([^"]+)"[^>]*>(.*?)</symbol>', html, re.S):
    assert re.search(r'<(?:path|polygon|circle|rect)\b', body), f'Empty icon: {name}'
