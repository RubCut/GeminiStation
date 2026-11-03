#!/usr/bin/env python3
"""Verify pinned research model and native runtimes in a built APK."""
import hashlib
import sys
from pathlib import Path
from zipfile import ZipFile
EXPECTED = {
    'gemini_experimental.tflite': '4f9b112f7382cc136e8ce0f8f3e8bc7581219d54e3813b7f06672ed547b6e401',
    'gemini_selected_tts_experimental.tflite': '542922f2f9393dc8a73524b2c1d5f2b7f6cb53f02ff459bd5af02dbd19d4ac0d',
}
assert {p.name for p in Path('app/src/main/assets/microwakeword').glob('*.tflite')} == set(EXPECTED)
for filename, digest in EXPECTED.items():
    asset = Path('app/src/main/assets/microwakeword') / filename
    assert hashlib.sha256(asset.read_bytes()).hexdigest() == digest
vad = Path('app/src/main/assets/speech/vad.tflite')
vad_hash = '7aa4db6d5fb7c5358609f6931e7847d303c16a43d178638bc14104f50d7eff5f'
assert hashlib.sha256(vad.read_bytes()).hexdigest() == vad_hash
if len(sys.argv) > 1:
    with ZipFile(sys.argv[1]) as z:
        for filename, digest in EXPECTED.items():
            assert hashlib.sha256(z.read('assets/microwakeword/' + filename)).hexdigest() == digest
        assert hashlib.sha256(z.read('assets/speech/vad.tflite')).hexdigest() == vad_hash
        names = set(z.namelist())
        assert not any('pocketsphinx' in name.lower() for name in names)
        assert 'assets/microwakeword/gemini_all_stress_experimental.tflite' not in names
        for abi in ('armeabi-v7a', 'arm64-v8a'):
            for lib in ('libgemini_wake_frontend.so', 'libtensorflowlite_jni.so'):
                assert f'lib/{abi}/{lib}' in names, (abi, lib)
        assert 'assets/licenses/microfrontend-apache-2.0.txt' in names
        assert 'assets/licenses/kissfft.txt' in names
        assert 'assets/licenses/esphome-models-notice.txt' in names
        assert 'assets/licenses/esphome-models-apache-2.0.txt' in names
print('microWakeWord model and APK assets verified')
