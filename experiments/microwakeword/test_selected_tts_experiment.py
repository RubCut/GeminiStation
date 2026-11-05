#!/usr/bin/env python3
"""Check source separation and augmentation using generated sine-wave fixtures."""
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from scipy.io import wavfile
import run_selected_tts_experiment as exp


class SelectedTtsExperimentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.original_data, self.original_count = exp.DATA, exp.EXTRA_PER_CLASS
        exp.DATA, exp.EXTRA_PER_CLASS = self.root / 'data', 12
        for dirname in ('isolated', 'train', 'test'):
            (exp.DATA / dirname).mkdir(parents=True)
        names = [('isolated', x) for x in exp.TRAIN_POSITIVE + exp.HOLDOUT_POSITIVE]
        names += [('train', x) for x in exp.TRAIN_NEGATIVE]
        names += [('test', x) for x in exp.TRAIN_NEGATIVE]
        for i, (folder, name) in enumerate(names):
            n = 96000 if folder != 'isolated' else 12000
            t = np.arange(n) / 24000
            wavfile.write(exp.DATA / folder / name, 24000,
                          np.asarray(5000 * np.sin(2 * np.pi * (180 + 17 * i) * t), dtype=np.int16))

    def tearDown(self):
        exp.DATA, exp.EXTRA_PER_CLASS = self.original_data, self.original_count
        self.tmp.cleanup()

    def test_training_covers_six_full_words_and_holdout_is_separate(self):
        names = exp.TRAIN_POSITIVE
        for word in ('gemini', 'geminai', 'dzhemini', 'dzheminai', 'gemma', 'dzhemma'):
            self.assertEqual(sum(x.endswith('_' + word + '.wav') for x in names), 1)
        self.assertFalse(set(names) & set(exp.HOLDOUT_POSITIVE))
        pos, neg, hashes = exp.sources()
        self.assertEqual((len(pos), len(neg), len(set(hashes))), (6, 2, 12))

    def test_reject_silent_and_reused_source(self):
        path = exp.DATA / 'isolated' / exp.TRAIN_POSITIVE[0]
        original = path.read_bytes()
        wavfile.write(path, 24000, np.zeros(12000, dtype=np.int16))
        with self.assertRaisesRegex(ValueError, 'Near-silent'): exp.sources()
        path.write_bytes(original)
        (exp.DATA / 'isolated' / exp.HOLDOUT_POSITIVE[0]).write_bytes(original)
        with self.assertRaisesRegex(ValueError, 'distinct'): exp.sources()

    def test_augment_never_uses_heldout_sources_and_is_resumable(self):
        out = self.root / 'generated'
        exp.augment_selected(out)
        for kind in ('selected_positive_train', 'selected_negative_train'):
            folder = out / kind
            lines = [json.loads(line) for line in (folder / 'manifest.jsonl').read_text().splitlines()]
            self.assertEqual(len(lines), 12)
            allowed = set(exp.TRAIN_POSITIVE if 'positive' in kind else exp.TRAIN_NEGATIVE)
            self.assertTrue(all(item['source'] in allowed for item in lines))
            self.assertEqual(len(list(folder.glob('*.wav'))), 12)
        exp.augment_selected(out)  # same input: safe resume
        next((out / 'selected_positive_train').glob('*.wav')).unlink()
        with self.assertRaisesRegex(RuntimeError, 'Incomplete'): exp.augment_selected(out)


if __name__ == '__main__': unittest.main()
