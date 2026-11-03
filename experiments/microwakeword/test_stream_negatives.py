#!/usr/bin/env python3
"""Ensure prefix hard negatives are generated without overwriting originals."""
import tempfile
import unittest
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from prepare_stream_negatives import make_prefixes


class FakeFrontend:
    @staticmethod
    def extract_all_frames(audio):
        return np.arange(218 * 40, dtype=np.float32).reshape(218, 40) + 1


class StreamNegativesTest(unittest.TestCase):
    def test_prefixes_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); audio = root / 'negative.wav'; original = root / 'original.npy'; dest = root / 'out.npy'
            wavfile.write(audio, 16000, np.zeros(35200, dtype=np.int16))
            original_data = np.ones((1, 204, 40), dtype=np.float32)
            np.save(original, original_data)
            make_prefixes(FakeFrontend, [audio], original, dest, 2, 11)
            output = np.load(dest)
            self.assertEqual(output.shape, (3, 204, 40))
            np.testing.assert_array_equal(output[0], original_data[0])
            for window in output[1:]:
                self.assertTrue(np.all(window[0] == 0))
                self.assertTrue(np.all(window[-1] > 0))
                nonzero = window[np.any(window != 0, axis=1)]
                np.testing.assert_array_equal(nonzero, FakeFrontend.extract_all_frames(None)[:len(nonzero)])
            with self.assertRaisesRegex(RuntimeError, 'overwrite'):
                make_prefixes(FakeFrontend, [audio], original, dest, 2, 11)

if __name__ == '__main__': unittest.main()
