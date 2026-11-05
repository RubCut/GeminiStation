#!/usr/bin/env python3
"""Small, offline checks for controlled fragment noise and level generation."""
import unittest
import numpy as np
from run_noisy_experiment import (FRAGMENTS, FRAGMENT_CONDITIONS, FULL_CONDITIONS,
                                  SAMPLES, crop_word, mix)

class NoisyExamplesTest(unittest.TestCase):
    def setUp(self):
        t = np.arange(9000) / 16000
        self.speech = (18000 * np.sin(2 * np.pi * 260 * t)).astype(np.float32)

    def test_every_fragment_condition_is_strong_noise(self):
        for i, condition in enumerate(FRAGMENT_CONDITIONS):
            pcm, info = mix(self.speech, np.random.default_rng(i), condition)
            self.assertEqual((SAMPLES,), pcm.shape)
            self.assertEqual(np.int16, pcm.dtype)
            self.assertGreaterEqual(info['snr_db_active_speech'], -3)
            self.assertLessEqual(info['snr_db_active_speech'], 4)
            if 'quiet' in condition:
                self.assertGreaterEqual(info['gain_before_limiter'], .008)
                self.assertLessEqual(info['gain_before_limiter'], .045)
            else:
                self.assertGreaterEqual(info['gain_before_limiter'], .15)
                self.assertLessEqual(info['gain_before_limiter'], .8)
            self.assertLessEqual(np.abs(pcm.astype(np.int32)).max(), 32000)

    def test_full_words_cover_noise_and_volume_conditions(self):
        self.assertEqual(6, len(FULL_CONDITIONS))
        for i, condition in enumerate(FULL_CONDITIONS):
            _, info = mix(self.speech, np.random.default_rng(i), condition)
            lo, hi = ((-3, 4) if 'strong' in condition else
                      (8, 15) if 'moderate' in condition else (22, 35))
            self.assertGreaterEqual(info['snr_db_active_speech'], lo)
            self.assertLessEqual(info['snr_db_active_speech'], hi)

    def test_fragments_are_actual_crops(self):
        for name in FRAGMENTS:
            cropped = crop_word(self.speech, name, np.random.default_rng(8))
            self.assertLess(len(cropped), len(self.speech))
            self.assertGreater(len(cropped), len(self.speech) // 2)
            self.assertAlmostEqual(float(cropped[0]), 0.0, places=3)
            self.assertAlmostEqual(float(cropped[-1]), 0.0, places=3)

    def test_zero_speech_rejected(self):
        with self.assertRaises(RuntimeError):
            mix(np.zeros(100, dtype=np.float32), np.random.default_rng(0), 'quiet_strong')

if __name__ == '__main__':
    unittest.main()
