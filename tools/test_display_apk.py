#!/usr/bin/env python3
"""Ensure the opt-in framebuffer helper is actually packaged alongside the existing native library."""
import re
import sys
import zipfile

if len(sys.argv) != 2:
    raise SystemExit('Usage: test_display_apk.py app-debug.apk')
with zipfile.ZipFile(sys.argv[1]) as apk:
    names = apk.namelist()
    def abis(name):
        return {m.group(1) for path in names if (m := re.fullmatch(r'lib/([^/]+)/' + name, path))}
    wake = abis('libgemini_wake_frontend\\.so')
    display = abis('libgemini_display\\.so')
    assert wake and wake == display, f'native library ABI mismatch: wake={wake}, display={display}'
    assert {'armeabi-v7a', 'arm64-v8a'} <= display, f'ARM framebuffer libraries missing: {display}'
    for abi in display:
        assert apk.read(f'lib/{abi}/libgemini_display.so')[:4] == b'\x7fELF'
    dex = [name for name in names if re.fullmatch(r'classes\d*\.dex', name)]
    for helper in (b'Lru/geministation/RootDisplayHelper;', b'Lru/geministation/RootRingHelper;'):
        assert dex and any(helper in apk.read(name) for name in dex), f'root helper missing from DEX: {helper}'
print('Framebuffer helper and native library are present for every supported ABI')
