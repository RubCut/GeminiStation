#!/usr/bin/env python3
"""Check that the supplied WAV cues are packaged intact and suitable for playback."""
from pathlib import Path
import sys
import wave
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CUES = {
    'assistent start.wav': 'assistant_start.wav',
    'waiting for user anwser.wav': 'waiting_for_answer.wav',
    'user didnt anwsered.wav': 'no_answer.wav',
}
for original, resource in CUES.items():
    source = ROOT / original
    packaged = ROOT / 'app/src/main/res/raw' / resource
    assert source.read_bytes() == packaged.read_bytes(), f'{resource}: differs from supplied file'
    with wave.open(str(packaged), 'rb') as sound:
        assert sound.getcomptype() == 'NONE' and sound.getsampwidth() == 2
        assert sound.getnchannels() in (1, 2) and 8000 <= sound.getframerate() <= 48000
        duration = sound.getnframes() / sound.getframerate()
        assert 0 < duration < 3, f'{resource}: unexpected duration {duration}'

if len(sys.argv) > 1:
    with zipfile.ZipFile(sys.argv[1]) as apk:
        for resource in CUES.values():
            matches = [info for info in apk.infolist() if info.filename.endswith('/' + resource)]
            assert len(matches) == 1, f'{resource}: not found uniquely in APK'
            assert matches[0].compress_type == zipfile.ZIP_STORED, f'{resource}: must be stored uncompressed'
            expected = (ROOT / 'app/src/main/res/raw' / resource).read_bytes()
            assert apk.read(matches[0]) == expected, f'{resource}: changed in APK'
print('Sound cue assets verified')
