#!/usr/bin/env python3
"""Smoke-test the *streaming* TFLite output against synthetic validation clips.

Each clip starts with a new interpreter and frontend; the recorded score is the
maximum ten-frame moving average, with inferences 30 ms apart. This is not a
measurement of false activations/hour, a human-speaker test, or an Android test.
"""
import json
import os
import sys
from collections import defaultdict
from pathlib import Path

os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL', '2')
os.environ.setdefault('OMP_NUM_THREADS', '1')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')
os.environ.setdefault('TF_NUM_INTRAOP_THREADS', '1')
os.environ.setdefault('TF_NUM_INTEROP_THREADS', '1')
import numpy as np
import tensorflow as tf
from scipy.io import wavfile

from run_experiment import WORK, MODEL, CONFIG, VAL_NEG, load_trainer


def clip_score(model_path, audio, extract):
    interpreter = tf.lite.Interpreter(model_path=str(model_path), num_threads=1)
    interpreter.allocate_tensors()
    inp, out = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    if tuple(inp['shape']) != (1, 3, 40) or inp['dtype'] != np.int8:
        raise ValueError(f"Not the expected 30 ms streaming input: {inp}")
    if tuple(out['shape']) != (1, 1) or out['dtype'] != np.uint8:
        raise ValueError(f"Unexpected output: {out}")
    frames = extract(audio)
    scale, zero = inp['quantization']
    out_scale, out_zero = out['quantization']
    if scale <= 0 or out_scale <= 0:
        raise ValueError('Missing quantization parameters')
    scores = []
    for start in range(0, len(frames) - 2, 3):
        x = np.clip(np.rint(frames[start:start+3] / scale + zero), -128, 127).astype(np.int8)
        interpreter.set_tensor(inp['index'], x.reshape(1, 3, 40))
        interpreter.invoke()
        y = interpreter.get_tensor(out['index'])[0, 0]
        scores.append((int(y) - out_zero) * out_scale)
    if len(scores) < 10:
        raise ValueError('Clip shorter than ten streaming predictions')
    return float(np.max(np.convolve(scores, np.ones(10)/10, mode='valid')))


def main():
    trainer = load_trainer()
    cfg = trainer.load_config(str(CONFIG))
    root = WORK / 'training' / 'output' / MODEL
    model_path = WORK / f'{MODEL}_mww.tflite'
    summaries = defaultdict(list)
    for label, directory in [('positive', 'positive_test'), ('negative', 'negative_test')]:
        for i, path in enumerate(sorted((root / directory).glob('*.wav'))):
            sr, audio = wavfile.read(str(path))
            if sr != 16000 or audio.dtype != np.int16 or audio.ndim != 1:
                raise ValueError('Expected mono 16kHz PCM: ' + str(path))
            value = clip_score(model_path, audio, trainer.extract_all_frames)
            if label == 'positive':
                key = cfg['target_phrases'][i % len(cfg['target_phrases'])]
            else:
                key = 'synthetic_noise' if i < 80 else VAL_NEG[(i - 80) % len(VAL_NEG)]
            summaries[(label, key)].append(value)
            if (i + 1) % 100 == 0:
                print(f'{directory} {i+1}', file=sys.stderr, flush=True)
    output = {'description': 'Synthetic same-voice validation; no real speech or environment',
              'score': 'max 10-output rolling average, streaming int8 TFLite',
              'thresholds': {}}
    for threshold in (0.5, 0.9):
        by_group = {f'{label}: {key}': {'n': len(values),
                    'triggered': sum(v >= threshold for v in values)}
                    for (label, key), values in sorted(summaries.items())}
        output['thresholds'][str(threshold)] = {
            'positive': {'total': sum(len(v) for (k, _), v in summaries.items() if k == 'positive'),
                         'triggered': sum(sum(x >= threshold for x in v) for (k, _), v in summaries.items() if k == 'positive')},
            'negative': {'total': sum(len(v) for (k, _), v in summaries.items() if k == 'negative'),
                         'triggered': sum(sum(x >= threshold for x in v) for (k, _), v in summaries.items() if k == 'negative')},
            'groups': by_group}
    print(json.dumps(output, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
