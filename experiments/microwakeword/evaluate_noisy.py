#!/usr/bin/env python3
"""Compare old and new streaming models on identical held-out synthetic clips."""
import json
import os
import sys
from collections import defaultdict
from pathlib import Path

from scipy.io import wavfile
from run_experiment import WORK, load_trainer
from run_noisy_experiment import MODEL
from evaluate_streaming import clip_score

ROOT = Path(os.environ.get('MWW_EVAL_ROOT', str(WORK / 'training' / 'output' / MODEL)))
OLD_MODEL = Path(os.environ.get('MWW_BASELINE_MODEL', str(Path(__file__).resolve().parent / 'results' / 'gemini_six_experimental_mww.tflite')))
NEW_MODEL = Path(os.environ.get('MWW_CANDIDATE_MODEL', str(WORK / (MODEL + '_mww.tflite'))))


def main():
    trainer = load_trainer()
    results = {}
    for name, model in [(os.environ.get('MWW_BASELINE_LABEL', 'old'), OLD_MODEL),
                        (os.environ.get('MWW_CANDIDATE_LABEL', 'noisy_fragments'), NEW_MODEL)]:
        if not model.is_file():
            raise FileNotFoundError(model)
        data = defaultdict(list)
        total = 0
        for directory in ('positive_test', 'negative_test', 'ambiguous_test'):
            folder = ROOT / directory
            manifest = [json.loads(x) for x in (folder / 'manifest.jsonl').read_text(encoding='utf-8').splitlines()]
            for item in manifest:
                sr, audio = wavfile.read(str(folder / item['file']))
                if sr != 16000 or audio.dtype.name != 'int16' or audio.ndim != 1:
                    raise ValueError('Expected mono 16kHz PCM: ' + item['file'])
                score = clip_score(model, audio, trainer.extract_all_frames)
                data[item['kind']].append((item, score))
                total += 1
                if total % 200 == 0:
                    print(f'{name}: {total}', file=sys.stderr, flush=True)
        record = {}
        for cutoff in (.5, .9):
            by_kind = {}
            for kind, examples in sorted(data.items()):
                categories = {}
                for key in ('phrase', 'condition', 'pronunciation_ipa'):
                    buckets = defaultdict(list)
                    for item, score in examples:
                        if key in item:
                            buckets[item[key]].append(score)
                    categories[key] = {x: {'triggered': sum(v >= cutoff for v in values), 'total': len(values)}
                                       for x, values in sorted(buckets.items())}
                by_kind[kind] = {'triggered': sum(v >= cutoff for _, v in examples),
                                 'total': len(examples), **categories}
            record[str(cutoff)] = by_kind
        results[name] = record
    output = {'description': 'Streaming maximum of a 10-output rolling average. '
             'Synthetic held-out clips from the same two Piper voices; not real-world validation. '
             'Ambiguous speech contains the requested fragment мини and is separated from clear negatives.',
              'thresholds': results}
    print(json.dumps(output, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
