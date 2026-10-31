#!/usr/bin/env python3
"""Restricted-CPU microWakeWord research run. No network, microphone or deployment.

Generates two Piper voices, negative phrases and synthetic noise; creates separate
synthetic validation examples, trains a MixedNet and attempts a streaming export.
Not a production training set: no human speech, music, TV or measured room noise.
Dependencies and checked third-party weights must be prepared separately.
"""
import argparse
import importlib.util
import os
import random
import sys
import time
from pathlib import Path

os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL', '2')
os.environ.setdefault('TF_NUM_INTEROP_THREADS', '1')
os.environ.setdefault('TF_NUM_INTRAOP_THREADS', '2')
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')

import numpy as np
from scipy import signal
from scipy.io import wavfile
from piper import PiperVoice
from piper.config import SynthesisConfig

WORK = Path(os.environ.get('GEMINI_MWW_WORK', '/home/user/.cache/gemini-mww'))
TRAINER = WORK / 'trainer' / 'train_mww.py'
CONFIG = Path(__file__).resolve().parent / 'config.yaml'
MODEL = 'gemini_six_experimental'
SPEAKERS = ('dmitri', 'irina')
TRAIN_NEG = ['алиса', 'маруся', 'привет', 'хорошо', 'слушай', 'извини', 'открой', 'нажми',
    'джем', 'джинсы', 'джин', 'джеймс', 'джунгли', 'джентльмен', 'джек', 'джокер', 'джип',
    'геннадий', 'генерал', 'гений', 'гимн', 'глина', 'гемоглобин', 'где мы', 'где меня',
    'кремний', 'камин', 'камень', 'минуту', 'на имени', 'времени', 'кем он', 'сегодня',
    'комната', 'конечно', 'добрый день', 'включи музыку', 'это система', 'как дела',
    'скажи мне', 'помоги', 'хватит', 'ко мне', 'мимо', 'семь дней', 'где мой телефон',
    'сделай потише', 'включи телевизор', 'начни сначала', 'какая погода', 'открой дверь']
VAL_NEG = ['геометрия', 'где мини', 'где минин', 'димина', 'временно', 'поменяй', 'георгий',
    'мне кажется', 'ещё минуту', 'как именно', 'на диване', 'здесь никого', 'пожалуйста',
    'доброе утро', 'работает', 'календарь', 'сделай громче', 'другими словами',
    'иди ко мне', 'давай посмотрим', 'жемчуг', 'гимназия', 'перемена', 'послушай меня', 'эмма']


def load_trainer():
    if not TRAINER.is_file():
        raise SystemExit('Missing pinned upstream trainer: ' + str(TRAINER))
    spec = importlib.util.spec_from_file_location('train_mww', TRAINER)
    trainer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(trainer)
    return trainer


def prepare_speakers():
    result = []
    for speaker in SPEAKERS:
        path = WORK / 'voices' / ('ru_RU-' + speaker + '-medium.onnx')
        if not path.is_file() or not Path(str(path) + '.json').is_file():
            raise SystemExit('Missing Piper model or config: ' + str(path))
        result.append(PiperVoice.load(path))
    return result


def pad_with_synthetic_noise(audio, rng, samples=35200):
    # Simulated quiet background, not a recording from a real room.
    audio = audio[:samples]
    noise = rng.normal(0, rng.uniform(0, 45), samples)
    start = int(rng.integers(0, samples - len(audio) + 1))
    noise[start:start + len(audio)] += audio * rng.uniform(0.25, 1.0)
    return np.clip(noise, -32768, 32767).astype(np.int16)


def noise_clip(rng, samples=35200):
    level = rng.uniform(5, 2000)
    audio = rng.normal(0, level, samples)
    if rng.random() < .5:
        freq = rng.uniform(70, 3000)
        audio += rng.uniform(0, level) * np.sin(np.arange(samples) * 2 * np.pi * freq / 16000)
    for _ in range(int(rng.integers(0, 4))):
        start = int(rng.integers(0, samples - 800))
        length = int(rng.integers(10, 800))
        audio[start:start + length] += rng.normal(0, rng.uniform(500, 8000), length)
    return np.clip(audio, -32768, 32767).astype(np.int16)


def generate_dir(folder, texts, count, voices, seed, noise_count=0):
    folder.mkdir(parents=True, exist_ok=True)
    complete = folder / '.complete'
    if complete.exists() and len(list(folder.glob('*.wav'))) == count:
        print(f'{folder.name}: {count} existing; resume', flush=True)
        return
    for p in folder.glob('*.wav'):
        p.unlink()
    rng = np.random.default_rng(seed)
    random.seed(seed)
    started = time.monotonic()
    for i in range(count):
        if i < noise_count:
            pcm = noise_clip(rng)
        else:
            phrase = texts[(i - noise_count) % len(texts)]
            # Vary intonation with punctuation as well as TTS synthesis parameters.
            phrase += ['', '!', '?'][int(rng.integers(0, 3))]
            voice = voices[int(rng.integers(0, len(voices)))]
            opts = SynthesisConfig(length_scale=float(rng.uniform(.75, 1.3)),
                                   noise_scale=float(rng.uniform(.5, 1.0)),
                                   noise_w_scale=float(rng.uniform(.65, 1.05)))
            chunks = list(voice.synthesize(phrase, opts))
            if not chunks:
                raise RuntimeError(f'Piper returned no audio for {phrase!r}')
            raw = np.concatenate([chunk.audio_int16_array for chunk in chunks]).astype(np.float32)
            pcm = pad_with_synthetic_noise(signal.resample_poly(raw, 320, 441), rng)
        wavfile.write(str(folder / f'{i:05d}.wav'), 16000, pcm)
        if (i + 1) % 100 == 0 or i + 1 == count:
            print(f'{folder.name}: {i + 1}/{count}, {time.monotonic()-started:.0f}s', flush=True)
    complete.write_text('complete\n')


def generate(cfg, root):
    voices = prepare_speakers()
    positives = cfg['target_phrases']
    generate_dir(root / 'positive_train', positives, cfg['n_samples'], voices, 1101)
    generate_dir(root / 'positive_test', positives, cfg['n_samples_val'], voices, 1102)
    generate_dir(root / 'negative_train', TRAIN_NEG, 1200, voices, 1103, noise_count=200)
    generate_dir(root / 'negative_test', VAL_NEG, 384, voices, 1104, noise_count=80)


def features(trainer, cfg, root):
    feature_dir = root / 'mww_features'
    feature_dir.mkdir(parents=True, exist_ok=True)
    # Unlike upstream's automatic 90/10 split of augmented windows, test clips
    # are separate files. Voices are NOT held out, so this is not real-world validation.
    jobs = [('positive_train', 'positive_train.npy', 2), ('positive_test', 'positive_val.npy', 0),
            ('negative_train', 'negative_train.npy', 2), ('negative_test', 'negative_val.npy', 0)]
    for folder, output, augment in jobs:
        dest = feature_dir / output
        if dest.exists():
            print(f'{output}: exists; resume', flush=True)
            continue
        values = trainer.extract_features_from_dir(root / folder, folder, cfg['spectrogram_length'],
                                                   augment_stride=None, noise_augments=augment)
        if values.ndim != 3 or not len(values):
            raise RuntimeError('No usable features: ' + folder)
        np.save(dest, values)
        print(f'{output}: {values.shape}', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--phase', choices=['generate', 'features', 'train', 'export', 'all'], default='all')
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    os.chdir(WORK)
    trainer = load_trainer()
    cfg = trainer.load_config(str(CONFIG))
    root = WORK / 'training' / 'output' / MODEL
    if args.phase in ('generate', 'all'):
        generate(cfg, root)
    if args.phase in ('features', 'all'):
        features(trainer, cfg, root)
    if args.phase in ('train', 'all'):
        trainer.phase_train(cfg)
    if args.phase in ('export', 'all'):
        trainer.phase_export(cfg)
    print('Finished phase:', args.phase, flush=True)
    print('EXPERIMENTAL ONLY: no Android integration or real-world validation.', flush=True)

if __name__ == '__main__':
    main()
