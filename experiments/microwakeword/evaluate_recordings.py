#!/usr/bin/env python3
"""Offline comparison of streaming microWakeWord models using local WAV recordings.

No network, audio output, microphone access, or filename/content in the result.
Each positive WAV contains exactly one wake word; each negative WAV is a
continuous recording without any wake words. This approximates the model's raw
streaming output, NOT the complete Android signal filter or dialog lifecycle.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from scipy import signal
from scipy.io import wavfile
from pymicro_features import MicroFrontend
from tflite_runtime.interpreter import Interpreter

ROOT = Path(__file__).resolve().parents[2]
MODELS = {
    'previous': ROOT / 'app/src/main/assets/microwakeword/gemini_experimental.tflite',
    'selected_tts': ROOT / 'app/src/main/assets/microwakeword/gemini_selected_tts_experimental.tflite',
}
RATE, SAMPLES, STEP, WINDOW = 16000, 160, 3, 10
THRESHOLDS = (0.50, 0.70, 0.80, 0.90, 0.95, 0.99)


def read_wav(path):
    rate, data = wavfile.read(str(path))
    if not 8000 <= rate <= 96000 or data.ndim not in (1, 2) or not len(data):
        raise ValueError('Unsupported WAV sample rate, channels or empty audio')
    if len(data) / rate > 1800 or (data.ndim == 2 and not 1 <= data.shape[1] <= 2):
        raise ValueError('WAV must be mono/stereo and no longer than 30 minutes')
    if np.issubdtype(data.dtype, np.unsignedinteger):
        scale = float(2 ** (8 * data.dtype.itemsize - 1))
        x = (data.astype(np.float64) - scale) / scale
    elif np.issubdtype(data.dtype, np.signedinteger):
        x = data.astype(np.float64) / float(2 ** (8 * data.dtype.itemsize - 1))
    elif np.issubdtype(data.dtype, np.floating):
        x = data.astype(np.float64)
    else:
        raise ValueError('Unsupported WAV encoding')
    if not np.all(np.isfinite(x)): raise ValueError('Non-finite PCM values')
    if x.ndim == 2: x = x.mean(axis=1)
    if rate != RATE:
        common = math.gcd(rate, RATE)
        x = signal.resample_poly(x, RATE // common, rate // common)
    # Signed 16-bit PCM; preserve the actual recording level rather than normalizing it.
    return np.clip(np.rint(x * 32768.0), -32768, 32767).astype(np.int16)


class Model:
    def __init__(self, path):
        self.interpreter = Interpreter(model_path=str(path), num_threads=1)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        self.output = self.interpreter.get_output_details()[0]
        if (tuple(self.input['shape']) != (1, 3, 40) or self.input['dtype'] != np.int8 or
                tuple(self.output['shape']) != (1, 1) or self.output['dtype'] != np.uint8):
            raise ValueError('Model is not the expected stateful microWakeWord format')
        self.in_scale, self.in_zero = self.input['quantization']
        self.out_scale, self.out_zero = self.output['quantization']
        if self.in_scale <= 0 or self.out_scale <= 0: raise ValueError('Invalid quantization')
        self.history = []
        self.averages = []

    def run(self, features):
        # Java Math.round(x) uses floor(x + .5), unlike numpy's round-to-even.
        q = np.floor(features / self.in_scale + .5) + self.in_zero
        q = np.clip(q, -128, 127).astype(np.int8).reshape(1, 3, 40)
        self.interpreter.set_tensor(self.input['index'], q)
        self.interpreter.invoke()
        value = int(self.interpreter.get_tensor(self.output['index'])[0, 0])
        self.history.append((value - self.out_zero) * self.out_scale)
        if len(self.history) > WINDOW: self.history.pop(0)
        if len(self.history) == WINDOW: self.averages.append(sum(self.history) / WINDOW)


def evaluate_audio(pcm, models=MODELS):
    # Separate instances per clip prevent state leakage between recordings.
    instances = {name: Model(path) for name, path in models.items()}
    frontend = MicroFrontend()
    feature_group = []
    for offset in range(0, len(pcm) - SAMPLES + 1, SAMPLES):
        result = frontend.process_samples(pcm[offset:offset + SAMPLES].tobytes())
        if len(result.features) == 0: continue
        if len(result.features) != 40 or result.samples_read != SAMPLES:
            raise ValueError('Unexpected microfrontend output')
        feature_group.append(np.asarray(result.features, dtype=np.float32))
        if len(feature_group) == STEP:
            features = np.stack(feature_group)
            for model in instances.values(): model.run(features)
            feature_group.clear()
    return {name: model.averages for name, model in instances.items()}


def events(scores, cutoff, cooldown_seconds=2.0):
    """Count score crossings with a fixed cooldown; not actual Android activations."""
    count, last, was_above = 0, -float('inf'), False
    for i, score in enumerate(scores):
        above = score >= cutoff
        if above and not was_above and i * .03 - last >= cooldown_seconds:
            count += 1; last = i * .03
        was_above = above
    return count


def files(folder):
    if folder is None: return []
    if not folder.is_dir(): raise ValueError('Expected a directory')
    items = sorted(folder.glob('*.wav')) + sorted(folder.glob('*.WAV'))
    if len(items) > 1000: raise ValueError('Too many WAV files')
    return items


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--positive', type=Path, help='directory of one-word positive WAV clips')
    parser.add_argument('--negative', type=Path, help='directory of continuous non-wake WAV recordings')
    parser.add_argument('--candidate', type=Path, help='optional alternative streaming int8 TFLite model')
    args = parser.parse_args()
    groups = {'positive': files(args.positive), 'negative': files(args.negative)}
    if not any(groups.values()): parser.error('Provide at least one WAV via --positive or --negative')
    # Identical files cannot be both positive and negative, even under different names.
    positive_hashes = {hashlib.sha256(p.read_bytes()).digest() for p in groups['positive']}
    if any(hashlib.sha256(p.read_bytes()).digest() in positive_hashes for p in groups['negative']):
        parser.error('Positive and negative recordings must not overlap')
    models = dict(MODELS)
    if args.candidate: models['candidate'] = args.candidate
    result = {'description': 'Raw streaming model comparison; excludes Android gain, guard, microphone and dialog timing.',
              'privacy': 'Aggregate output only; source audio and names are not copied or uploaded.',
              'negative_event_count': 'score threshold crossings with 2s cooldown; not measured Android activations/hour',
              'models_sha256': {n: hashlib.sha256(p.read_bytes()).hexdigest() for n, p in models.items()},
              'thresholds': {n: {f'{t:.2f}': {'positive_clips': 0, 'negative_crossings': 0} for t in THRESHOLDS} for n in models},
              'positive_files': len(groups['positive']), 'negative_files': len(groups['negative']),
              'positive_seconds': 0.0, 'negative_seconds': 0.0}
    for group, paths in groups.items():
        for path in paths:
            pcm = read_wav(path)
            if len(pcm) < RATE // 2: raise ValueError('Each WAV must be at least 0.5 seconds')
            result[group + '_seconds'] += len(pcm) / RATE
            scores = evaluate_audio(pcm, models)
            for name in models:
                if not scores[name]: raise ValueError('Recording is too short for model warm-up')
                for cutoff in THRESHOLDS:
                    counts = result['thresholds'][name][f'{cutoff:.2f}']
                    if group == 'positive': counts['positive_clips'] += int(max(scores[name]) >= cutoff)
                    else: counts['negative_crossings'] += events(scores[name], cutoff)
    for key in ('positive_seconds', 'negative_seconds'): result[key] = round(result[key], 3)
    if result['negative_seconds']:
        for thresholds in result['thresholds'].values():
            for counts in thresholds.values():
                counts['negative_crossings_per_hour'] = round(
                    counts['negative_crossings'] * 3600 / result['negative_seconds'], 3)
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
