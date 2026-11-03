#!/usr/bin/env python3
"""Evaluate the bundled wake model plus VAD on local synthetic WAVs only.

Optional keyboard source: the key-down/up snippets from Switchtone, downloaded
separately. This script creates no audio files and emits only aggregate numbers.
These offline score crossings do not simulate Android gain, signal guard, device
microphone, user/dialog or real-world false activations per hour.
"""
import argparse
import collections
import json
import random
from pathlib import Path
import numpy as np
from pymicro_features import MicroFrontend
from evaluate_recordings import Model, read_wav

ROOT = Path(__file__).resolve().parents[2]
WAKE = ROOT / 'app/src/main/assets/microwakeword/gemini_selected_tts_experimental.tflite'
VAD = ROOT / 'app/src/main/assets/speech/vad.tflite'


def stream(pcm, wake=WAKE, vad=VAD):
    """Shared frontend, independent quantizers and causal 5/10, 40-step rings."""
    w, v, frontend = Model(wake), Model(vad), MicroFrontend()
    vad_history = collections.deque(maxlen=40)
    result, group = [], []
    for off in range(0, len(pcm) - 159, 160):
        frame = frontend.process_samples(pcm[off:off + 160].tobytes())
        if not len(frame.features): continue
        group.append(np.asarray(frame.features, dtype=np.float32))
        if len(group) != 3: continue
        features = np.stack(group); group.clear()
        w.run(features); v.run(features)
        vad_history.append(v.history[-1])
        if len(w.history) == 10:
            result.append(((off + 160) / 16000, sum(w.history) / 10,
                           sum(w.history[-5:]) / 5, max(vad_history)))
    return np.asarray(result, dtype=np.float64).reshape(-1, 4)


def events(mask):
    """Count score crossings with a fixed 2s cooldown, not Android triggers."""
    last, count, previous = -10000, 0, False
    for i, value in enumerate(mask):
        if value and not previous and i * .03 - last >= 2:
            count += 1; last = i * .03
        previous = value
    return count


def corpus(path):
    counts = {}
    for folder in ('positive_test', 'negative_test', 'ambiguous_test'):
        directory = path / folder
        for line in (directory / 'manifest.jsonl').read_text(encoding='utf-8').splitlines():
            item = json.loads(line); name = item['file']
            if Path(name).name != name or not name.endswith('.wav'): raise ValueError('Unsafe filename')
            result = stream(np.pad(read_wav(directory / name), (32000, 0)))
            result = result[result[:, 0] >= 2.0]
            c = counts.setdefault(item['kind'], {'total': 0, **{f'{t:.1f}': [0, 0, 0] for t in (.6, .9)}})
            c['total'] += 1
            for threshold in (.6, .9):
                # Same 10-frame wake average without/with VAD, then an experimental 5-frame average.
                c[f'{threshold:.1f}'][0] += int(np.any(result[:, 1] >= threshold))
                c[f'{threshold:.1f}'][1] += int(np.any((result[:, 1] >= threshold) & (result[:, 3] >= .2)))
                c[f'{threshold:.1f}'][2] += int(np.any((result[:, 2] >= threshold) & (result[:, 3] >= .2)))
    return counts


def keyboard(path):
    rng, report = random.Random(91), {}
    for profile in ('clicky', 'linear', 'tactile'):
        samples = [read_wav(p).astype(np.float64) for p in sorted((path / profile).glob('key-*-[0-9]*.wav'))]
        if not samples: raise ValueError('Missing keyboard samples in ' + str(path / profile))
        data = np.zeros(90 * 16000)
        for second in range(0, 90, 15):
            t = second + .5
            while t < second + 14.5:
                sample = rng.choice(samples); gain = rng.choice([.12, .3, .6, 1])
                start = int(t * 16000); stop = min(len(data), start + len(sample))
                data[start:stop] += sample[:stop-start] * gain
                t += rng.uniform(.085, .30)
        data += np.random.default_rng(54).normal(0, 40, len(data))
        result = stream(np.clip(data, -32768, 32767).astype(np.int16))
        result = result[result[:, 0] >= 2.0]
        report[profile] = {'seconds': 90, 'vad_peak': round(float(result[:, 3].max()), 4),
            'crossings': {f'{t:.1f}': [events(result[:, 1] >= t),
                events((result[:, 1] >= t) & (result[:, 3] >= .2)),
                events((result[:, 2] >= t) & (result[:, 3] >= .2))] for t in (.6, .9)}}
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus', type=Path, help='synthetic corpus with positive/negative manifests')
    parser.add_argument('--keyboard-samples', type=Path, help='local Switchtone Resources/Sounds directory')
    args = parser.parse_args()
    if not args.corpus and not args.keyboard_samples: parser.error('Provide at least one input')
    result = {'comparison': '[10-frame wake average, 10-frame with VAD, trial 5-frame with VAD; threshold 0.2 and 1.2s lookback]',
              'limitations': 'Offline raw scores only; keyboard sequences are assembled from short samples; not device false-activation rates.'}
    if args.corpus: result['synthetic'] = corpus(args.corpus)
    if args.keyboard_samples: result['keyboard'] = keyboard(args.keyboard_samples)
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
