#!/usr/bin/env python3
"""Train a mixed Piper + selected-voice microWakeWord research candidate.

Uses only local WAV files. The selected-voice training sources are explicitly
separate from the two isolated holdout recordings and negative test recordings.
No real voices, television or room noise; do not infer real-world performance.
The short-fragment positives continue to come only from the prior experiment's
strong-noise generation. The new voices contribute complete words only.
"""
import argparse
import hashlib
import json
import os
import random
from pathlib import Path

import numpy as np
from scipy import signal
from scipy.io import wavfile

import run_pronunciation_experiment  # installs stress-aware Piper synth function
import run_noisy_experiment as base
from run_experiment import WORK, load_trainer, prepare_speakers

MODEL = os.environ.get('SELECTED_TTS_MODEL_NAME', 'gemini_selected_tts_experimental')
CONFIG = Path(os.environ.get('SELECTED_TTS_CONFIG', str(Path(__file__).with_name('config-selected-tts.yaml'))))
DATA = Path(os.environ.get('SELECTED_TTS_DATA', '/home/user/gemini-tts'))
# Six independently generated utterances; one per full target phrase. Two
# additional isolated utterances are deliberately never used for training.
TRAIN_POSITIVE = ('male_gemini.wav', 'female_geminai.wav', 'female_dzhemini.wav',
                  'male_dzheminai.wav', 'male_gemma.wav', 'female_dzhemma.wav')
HOLDOUT_POSITIVE = ('female_gemini.wav', 'female_dzheminai.wav')
TRAIN_NEGATIVE = ('negative_male.wav', 'negative_female.wav')
EXTRA_PER_CLASS = 600


def source_pcm(path):
    rate, audio = wavfile.read(str(path))
    if rate != 24000 or audio.dtype != np.int16 or audio.ndim != 1:
        raise ValueError('Expected 24 kHz mono PCM16 input: ' + str(path))
    audio = signal.resample_poly(audio.astype(np.float32), 2, 3)
    rms = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))
    if rms < 100 or np.max(np.abs(audio)) < 1000:
        raise ValueError('Near-silent TTS output must not be used: ' + str(path))
    return audio


def sources():
    pos = [DATA / 'isolated' / p for p in TRAIN_POSITIVE]
    neg = [DATA / 'train' / p for p in TRAIN_NEGATIVE]
    held = [DATA / 'isolated' / p for p in HOLDOUT_POSITIVE]
    held += [DATA / 'test' / p for p in TRAIN_NEGATIVE]
    paths = pos + neg + held
    hashes = [hashlib.sha256(p.read_bytes()).hexdigest() for p in paths]
    if len(set(hashes)) != len(hashes):
        raise ValueError('Training and held-out source files must be distinct')
    # Verify holdouts too, but only return training audio to augmentation.
    for p in held: source_pcm(p)
    return [(p.name, source_pcm(p)) for p in pos], [(p.name, source_pcm(p)) for p in neg], hashes


def augment_selected(root):
    pos, neg, hashes = sources()
    for kind, clips, seed in [('selected_positive_train', pos, 6201),
                              ('selected_negative_train', neg, 6202)]:
        folder = root / kind
        folder.mkdir(parents=True, exist_ok=True)
        marker = folder / '.complete'
        identity = {'source_sha256': hashes, 'count': EXTRA_PER_CLASS, 'version': 1}
        if marker.exists():
            if json.loads(marker.read_text()) != identity or len(list(folder.glob('*.wav'))) != EXTRA_PER_CLASS:
                raise RuntimeError('Incomplete or mismatched generated set: ' + str(folder))
            print(kind, 'already prepared', flush=True)
            continue
        if list(folder.glob('*.wav')):
            raise RuntimeError('Partial generated set; remove only this experiment folder before retrying: ' + str(folder))
        rng = np.random.default_rng(seed)
        with (folder / 'manifest.jsonl').open('w', encoding='utf-8') as out:
            for i in range(EXTRA_PER_CLASS):
                name, audio = clips[i % len(clips)]
                # Negative speech is sampled from a long, non-keyword sentence.
                # Positive examples are never cropped into fragments.
                if kind == 'selected_negative_train':
                    length = int(rng.integers(19200, min(len(audio), base.SAMPLES) + 1))
                    offset = int(rng.integers(0, len(audio) - length + 1))
                    audio = audio[offset:offset + length]
                # Tempo/pitch, level, noise and placement change together; these
                # are not new voices or independent speech examples.
                speed = float(rng.uniform(.88, 1.14))
                old_len = len(audio)
                audio = signal.resample(audio, max(1, int(old_len / speed))).astype(np.float32)
                condition = base.FULL_CONDITIONS[(i // len(clips)) % len(base.FULL_CONDITIONS)]
                pcm, meta = base.mix(audio, rng, condition)
                filename = f'{i:05d}.wav'
                wavfile.write(str(folder / filename), 16000, pcm)
                out.write(json.dumps({'file': filename, 'source': name, 'speed': speed,
                                      'condition': condition, **meta}) + '\n')
        marker.write_text(json.dumps(identity), encoding='utf-8')
        print(kind, 'prepared:', EXTRA_PER_CLASS, flush=True)


def prepare_features(trainer, cfg, root):
    out_dir = root / 'mww_features'
    out_dir.mkdir(parents=True, exist_ok=True)
    jobs = [('positive_train', 'selected_positive_train', 'positive_train.npy'),
            ('negative_train', 'selected_negative_train', 'negative_train.npy'),
            ('positive_test', None, 'positive_val.npy'), ('negative_test', None, 'negative_val.npy')]
    for base_folder, extra_folder, filename in jobs:
        dest = out_dir / filename
        if dest.exists():
            print(filename, 'exists; resume', flush=True)
            continue
        arrays = [trainer.extract_features_from_dir(root / base_folder, base_folder,
                  cfg['spectrogram_length'], augment_stride=None, noise_augments=0)]
        if extra_folder:
            arrays.append(trainer.extract_features_from_dir(root / extra_folder, extra_folder,
                          cfg['spectrogram_length'], augment_stride=None, noise_augments=0))
        expected = sum(len(list((root / d).glob('*.wav'))) for d in (base_folder, extra_folder) if d)
        if any(a.ndim != 3 for a in arrays) or sum(len(a) for a in arrays) != expected:
            raise RuntimeError('Feature extraction dropped samples for ' + filename)
        np.save(dest, np.concatenate(arrays))
        print(filename, 'shape:', np.load(dest, mmap_mode='r').shape, flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--phase', choices=('generate', 'features', 'train', 'export', 'independent', 'independent-final', 'all'), default='all')
    args = p.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    os.chdir(WORK)
    np.random.seed(614); random.seed(614)
    trainer = load_trainer()
    cfg = trainer.load_config(str(CONFIG))
    if cfg['model_name'] != MODEL:
        raise ValueError('SELECTED_TTS_MODEL_NAME must match the config model_name')
    base.MODEL = MODEL
    root = WORK / 'training' / 'output' / MODEL
    if args.phase in ('generate', 'all'):
        sources()  # refuse to generate anything if the held-out WAVs are absent or unusable
        voices = prepare_speakers()
        for folder, train, seed in [('positive_train', True, 2101), ('positive_test', False, 2102),
                                    ('negative_train', True, 2103), ('negative_test', False, 2104)]:
            base.generate_dir(root, folder, train, voices, seed)
        augment_selected(root)
    if args.phase in ('independent', 'independent-final'):
        voices = prepare_speakers()
        offset = 3000 if args.phase == 'independent' else 4000
        folder_root = root / ('independent' if args.phase == 'independent' else 'independent_final')
        for folder, seed in [('positive_test', offset + 102), ('negative_test', offset + 104), ('ambiguous_test', offset + 105)]:
            base.generate_dir(folder_root, folder, False, voices, seed)
    if args.phase in ('features', 'all'): prepare_features(trainer, cfg, root)
    if args.phase in ('train', 'all'):
        import tensorflow as tf
        tf.random.set_seed(614)
        trainer.phase_train(cfg)
    if args.phase in ('export', 'all'): trainer.phase_export(cfg)
    print('Finished:', args.phase, '; experimental, no device or human validation.', flush=True)

if __name__ == '__main__': main()
