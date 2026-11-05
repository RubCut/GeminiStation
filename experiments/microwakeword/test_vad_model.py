#!/usr/bin/env python3
"""Smoke-test the bundled speech detector's streaming shape and silence/click handling."""
import unittest
import numpy as np
from evaluate_vad import stream

class SpeechModelTest(unittest.TestCase):
    def test_streaming_silence_and_artificial_clicks(self):
        rng = np.random.default_rng(7)
        audio = rng.normal(0, 25, 64000)
        for start in range(4000, 63000, 2133):
            n = min(120, len(audio) - start)
            audio[start:start+n] += np.exp(-np.arange(n) / 18) * rng.choice([-1, 1], n) * 8000
        pcm = np.clip(audio, -32768, 32767).astype(np.int16)
        scores = stream(pcm)
        self.assertGreater(len(scores), 100)
        self.assertTrue(np.isfinite(scores).all())
        self.assertLess(float(scores[:, 3].max()), .2)
        self.assertFalse(np.any((scores[:, 1] >= .9) & (scores[:, 3] >= .2)))

if __name__ == '__main__': unittest.main()
