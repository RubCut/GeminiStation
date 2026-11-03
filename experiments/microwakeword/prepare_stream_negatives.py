#!/usr/bin/env python3
"""Add zero-padded negative prefixes to expose training to streaming startup.

Only uses the original training/validation negatives; independent test folders
are not read. Prefixes approximate, but do not perfectly reproduce, internal
streaming-state initialization. No audio is downloaded or uploaded.
"""
import argparse
import os
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from run_experiment import WORK, load_trainer

SOURCE = WORK / 'training/output/gemini_selected_tts_experimental'
TARGET = WORK / 'training/output/gemini_selected_tts_prefix'
SPEC = 204


def make_prefixes(trainer, paths, base_file, dest, per_file, seed):
    original = np.load(base_file, mmap_mode='r')
    expected = len(paths) * per_file
    if dest.exists():
        raise RuntimeError('Refusing to overwrite ' + str(dest))
    output = np.lib.format.open_memmap(dest, mode='w+', dtype=np.float32,
                                       shape=(len(original) + expected, SPEC, 40))
    output[:len(original)] = original
    rng = np.random.default_rng(seed)
    for i, path in enumerate(paths):
        rate, audio = wavfile.read(path)
        if rate != 16000 or audio.ndim != 1 or audio.dtype != np.int16:
            raise ValueError('Unexpected input WAV: ' + str(path))
        frames = trainer.extract_all_frames(audio)
        max_end = min(len(frames), SPEC - 3)
        possible = np.arange(30, max_end + 1, 3)
        if len(possible) < per_file: raise ValueError('Clip too short for prefixes: ' + str(path))
        for j, end in enumerate(rng.choice(possible, size=per_file, replace=False)):
            result = output[len(original) + i * per_file + j]
            result[:] = 0
            result[-end:] = frames[:end]
        if (i + 1) % 500 == 0: print(dest.name, i + 1, '/', len(paths), flush=True)
    output.flush()
    print(dest.name, 'shape:', output.shape, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', type=Path, default=TARGET)
    parser.add_argument('--prefixes-per-file', type=int, choices=(1, 2), default=2)
    args = parser.parse_args()
    os.chdir(WORK)
    trainer = load_trainer()
    out = args.target / 'mww_features'
    out.mkdir(parents=True, exist_ok=True)
    source = SOURCE / 'mww_features'
    for name in ('positive_train.npy', 'positive_val.npy'):
        path = out / name
        if path.exists() or path.is_symlink(): raise RuntimeError('Refusing to overwrite ' + str(path))
        path.symlink_to(source / name)
    train_paths = sorted((SOURCE / 'negative_train').glob('*.wav'))
    train_paths += sorted((SOURCE / 'selected_negative_train').glob('*.wav'))
    val_paths = sorted((SOURCE / 'negative_test').glob('*.wav'))
    if len(train_paths) != 2440 or len(val_paths) != 344:
        raise RuntimeError('Unexpected number of original negative recordings')
    make_prefixes(trainer, train_paths, source / 'negative_train.npy', out / 'negative_train.npy', args.prefixes_per_file, 7301)
    make_prefixes(trainer, val_paths, source / 'negative_val.npy', out / 'negative_val.npy', 1, 7302)

if __name__ == '__main__': main()
