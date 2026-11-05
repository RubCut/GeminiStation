#!/usr/bin/env python3
"""Offline smoke tests for the streaming comparison, using generated audio only."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from scipy.io import wavfile
import evaluate_recordings as evaluation


class RecordingEvaluationTest(unittest.TestCase):
    def test_crossings_with_cooldown_and_rearming(self):
        self.assertEqual(evaluation.events([0, 1, 1, 0, 1, 0, 1], .9), 1)
        scores = [0, 1, 0] + [0] * 70 + [1, 0]
        self.assertEqual(evaluation.events(scores, .9), 2)
        self.assertEqual(evaluation.events([0.89] * 100, .9), 0)

    def test_resampling_and_levels(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'audio.wav'
            mono = np.full(48000, 2000, dtype=np.int16)
            wavfile.write(path, 48000, np.stack([mono, mono], axis=1))
            decoded = evaluation.read_wav(path)
            self.assertEqual(len(decoded), 16000)
            self.assertAlmostEqual(float(decoded[1000:-1000].mean()), 2000, delta=5)

    def test_model_is_streaming_and_reset_per_recording(self):
        pcm = np.random.default_rng(3).integers(-5000, 5000, 16000, dtype=np.int16)
        first = evaluation.evaluate_audio(pcm)
        second = evaluation.evaluate_audio(pcm)
        self.assertEqual(first, second)
        for name in evaluation.MODELS:
            self.assertEqual(len(first[name]), 23)
            self.assertTrue(all(0 <= value <= 1 for value in first[name]))

    def test_cli_aggregates_and_rejects_overlap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); pos = root / 'pos'; neg = root / 'neg'
            pos.mkdir(); neg.mkdir()
            pcm = np.zeros(16000, dtype=np.int16)
            wavfile.write(pos / 'private-name.wav', 16000, pcm)
            wavfile.write(neg / 'different-name.wav', 16000, pcm)
            cmd = [sys.executable, str(Path(evaluation.__file__)), '--positive', str(pos), '--negative', str(neg)]
            self.assertNotEqual(subprocess.run(cmd, capture_output=True).returncode, 0)
            pcm[3000:5000] = 5000
            wavfile.write(neg / 'different-name.wav', 16000, pcm)
            run = subprocess.run(cmd, check=True, capture_output=True, text=True)
            self.assertNotIn('private-name', run.stdout)
            result = json.loads(run.stdout)
            self.assertEqual((result['positive_files'], result['negative_files']), (1, 1))
            self.assertEqual((result['positive_seconds'], result['negative_seconds']), (1, 1))


if __name__ == '__main__': unittest.main()
