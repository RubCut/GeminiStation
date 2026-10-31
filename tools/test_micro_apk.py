#!/usr/bin/env python3
"""Verify pinned research model and native runtimes in a built APK."""
import hashlib
import sys
from pathlib import Path
from zipfile import ZipFile
EXPECTED = '4f9b112f7382cc136e8ce0f8f3e8bc7581219d54e3813b7f06672ed547b6e401'
asset = Path('app/src/main/assets/microwakeword/gemini_experimental.tflite')
assert hashlib.sha256(asset.read_bytes()).hexdigest() == EXPECTED
if len(sys.argv) > 1:
    with ZipFile(sys.argv[1]) as z:
        assert hashlib.sha256(z.read('assets/microwakeword/gemini_experimental.tflite')).hexdigest() == EXPECTED
        names = set(z.namelist())
        for abi in ('armeabi-v7a', 'arm64-v8a'):
            for lib in ('libgemini_wake_frontend.so', 'libtensorflowlite_jni.so'):
                assert f'lib/{abi}/{lib}' in names, (abi, lib)
        assert 'assets/licenses/microfrontend-apache-2.0.txt' in names
        assert 'assets/licenses/kissfft.txt' in names
print('microWakeWord model and APK assets verified')
