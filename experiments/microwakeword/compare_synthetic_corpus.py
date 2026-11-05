#!/usr/bin/env python3
"""Compare bundled models with a candidate on labelled synthetic WAV clips.

Counts per-clip max 10-output average for various raw thresholds. This does not
simulate Android signal conditioning/dialogs and is NOT false activations/hour.
Use only an independently generated corpus never used for training/selection.
"""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path
import numpy as np
from evaluate_recordings import MODELS, evaluate_audio, read_wav

THRESHOLDS = (0.50, 0.60, 0.70, 0.80, 0.90, 0.95, 0.99)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--warmup', action='store_true', help='prepend 2 s digital silence, exclude warm-up outputs')
    args = parser.parse_args()
    models = dict(MODELS, candidate=args.candidate)
    counts = {n: {f'{t:.2f}': defaultdict(lambda: {'triggered': 0, 'total': 0, 'phrases': {}})
                  for t in THRESHOLDS} for n in models}
    for folder in ('positive_test', 'negative_test', 'ambiguous_test'):
        directory = args.root / folder
        manifest = [json.loads(x) for x in (directory / 'manifest.jsonl').read_text(encoding='utf-8').splitlines()]
        for index, item in enumerate(manifest):
            filename = item['file']
            if not isinstance(filename, str) or Path(filename).name != filename or not filename.lower().endswith('.wav'):
                raise ValueError('Manifest entries must name WAV files directly inside the dataset folder')
            pcm = read_wav(directory / filename)
            if args.warmup: pcm = np.pad(pcm, (32000, 0))
            all_scores = evaluate_audio(pcm, models)
            for name, scores in all_scores.items():
                # First averaged output is at ~320 ms (frontend warmup + 10 x 30 ms).
                selected = scores[56:] if args.warmup else scores
                if not selected: raise ValueError('Clip too short for score')
                peak = max(selected)
                for t in THRESHOLDS:
                    group = counts[name][f'{t:.2f}'][item['kind']]
                    hit = int(peak >= t)
                    group['total'] += 1; group['triggered'] += hit
                    phrase = group['phrases'].setdefault(item['phrase'], {'total': 0, 'triggered': 0})
                    phrase['total'] += 1; phrase['triggered'] += hit
            if (index + 1) % 200 == 0: print(folder, index + 1, flush=True, file=__import__('sys').stderr)
    result = {'description': 'Synthetic clip maxima, no real speech, no Android signal filtering, '
                             'same Piper voices as training; not false activations/hour.',
              'warmup_seconds_of_digital_silence': 2 if args.warmup else 0,
              'models_sha256': {n: hashlib.sha256(p.read_bytes()).hexdigest() for n, p in models.items()},
              'thresholds': counts}
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
