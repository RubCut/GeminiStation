#!/usr/bin/env python3
"""Second research run: full words at multiple levels and two-syllable fragments.

Standalone and actually cropped fragments are *always* mixed with strong noise.
The train/validation WAVs are different, but share Piper voices and synthesizer.
This experiment cannot establish real-world reliability or Android compatibility.
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

from run_experiment import (WORK, TRAIN_NEG, load_trainer, prepare_speakers,
                            noise_clip, features)
import numpy as np
from piper.config import SynthesisConfig
from scipy import signal
from scipy.io import wavfile

HERE = Path(__file__).resolve().parent
CONFIG = HERE / 'config-noisy.yaml'
MODEL = 'gemini_noisy_fragments_experimental'
FULL = ['Гемини', 'Геминай', 'Джемини', 'Джеминай', 'Гемма', 'Джемма']
FRAGMENTS = {
    'геми': ('Гемини', 'Геминай'),
    'джеми': ('Джемини', 'Джеминай'),
    'мини': ('Гемини', 'Джемини'),
    'минай': ('Геминай', 'Джеминай'),
}
FULL_CONDITIONS = ('quiet_low', 'normal_low', 'quiet_moderate', 'normal_moderate',
                   'quiet_strong', 'normal_strong')
FRAGMENT_CONDITIONS = ('standalone_quiet_strong', 'standalone_normal_strong',
                       'cropped_quiet_strong', 'cropped_normal_strong')
# Unseen ordinary negative phrases, without an intended positive fragment.
CLEAR_VAL = ['геометрия', 'димина', 'временно', 'поменяй', 'георгий', 'мне кажется',
             'ещё минуту', 'как именно', 'на диване', 'здесь никого', 'пожалуйста',
             'доброе утро', 'работает', 'календарь', 'сделай громче', 'другими словами',
             'иди ко мне', 'давай посмотрим', 'жемчуг', 'гимназия', 'перемена',
             'послушай меня', 'эмма', 'медленно', 'думай сам', 'книга на столе']
# Deliberately ambiguous real phrases containing «мини». Not used as training
# negatives, nor counted as unambiguous errors: the requested trigger is inside.
AMBIGUOUS_VAL = ['мини бар', 'мини юбка', 'мини футбол', 'мини пекарня', 'мини сериал',
                 'министерство', 'минимум', 'минимальный размер', 'миниатюра',
                 'мини автобус', 'мини компьютер', 'мини проект']
SR = 16000
SAMPLES = 35200


def synth(voice, text, rng):
    punctuation = ('', '!', '?')[int(rng.integers(0, 3))]
    options = SynthesisConfig(length_scale=float(rng.uniform(.78, 1.28)),
        noise_scale=float(rng.uniform(.5, 1.0)), noise_w_scale=float(rng.uniform(.65, 1.05)))
    chunks = list(voice.synthesize(text + punctuation, options))
    if not chunks or any(c.sample_rate != 22050 for c in chunks):
        raise RuntimeError(f'Unexpected Piper audio for {text!r}')
    return signal.resample_poly(np.concatenate([c.audio_int16_array for c in chunks]).astype(np.float32), 320, 441)


def crop_word(audio, fragment, rng):
    # Physically trim a generated complete keyword. The duration-based split
    # approximates a boundary; these examples are deliberately imperfect.
    active = np.flatnonzero(np.abs(audio) > max(60, np.max(np.abs(audio)) * .015))
    if len(active) < 100:
        raise RuntimeError('No active speech to crop')
    start, end = int(active[0]), int(active[-1]) + 1
    duration = end - start
    if fragment in ('геми', 'джеми'):
        end = start + int(duration * rng.uniform(.56, .78))
    else:
        start += int(duration * rng.uniform(.22, .42))
    clipped = audio[start:end].copy()
    fade = min(80, len(clipped) // 4)
    clipped[:fade] *= np.linspace(0, 1, fade)
    clipped[-fade:] *= np.linspace(1, 0, fade)
    return clipped


def mix(audio, rng, condition):
    """Return a 2.2 s PCM mix, with SNR measured on active speech samples."""
    audio = audio[:SAMPLES]
    peak = float(np.max(np.abs(audio)))
    if peak <= 0:
        raise RuntimeError('Silent speech')
    active = np.abs(audio) > max(60, peak * .05)
    if not np.any(active):
        raise RuntimeError('No active speech')
    gain = float(rng.uniform(.008, .045) if 'quiet' in condition else rng.uniform(.15, .8))
    speech = audio * gain
    if 'strong' in condition:
        snr_db = float(rng.uniform(-3, 4))
    elif 'moderate' in condition:
        snr_db = float(rng.uniform(8, 15))
    else:
        snr_db = float(rng.uniform(22, 35))
    # White or low-frequency correlated synthetic noise, not real room noise.
    noise = rng.normal(0, 1, SAMPLES)
    kind = 'white' if rng.random() < .5 else 'colored'
    if kind == 'colored':
        noise = signal.lfilter([1], [1, -float(rng.uniform(.85, .98))], noise)
    start = int(rng.integers(0, SAMPLES - len(speech) + 1))
    region = noise[start:start + len(speech)]
    speech_rms = float(np.sqrt(np.mean(speech[active] ** 2)))
    noise_rms = float(np.sqrt(np.mean(region[active] ** 2)))
    noise *= speech_rms / (noise_rms * 10 ** (snr_db / 20))
    noise[start:start + len(speech)] += speech
    # Scaling the whole mixture avoids clipping without altering its SNR.
    factor = min(1.0, 32000 / (float(np.max(np.abs(noise))) + 1e-9))
    pcm = np.clip(noise * factor, -32768, 32767).astype(np.int16)
    return pcm, {'gain_before_limiter': gain, 'snr_db_active_speech': snr_db,
                 'noise_kind': kind, 'mix_limiter': factor}


def generate_dir(root, folder_name, train, voices, seed):
    folder = root / folder_name
    folder.mkdir(parents=True, exist_ok=True)
    is_pos = folder_name.startswith('positive')
    is_ambiguous = folder_name == 'ambiguous_test'
    full_count = 1200 if train and is_pos else (360 if is_pos else 0)
    frag_count = 640 if train and is_pos else (256 if is_pos else 0)
    neg_count = 1840 if train and not is_pos else (96 if is_ambiguous else (344 if not is_pos else 0))
    count = full_count + frag_count + neg_count
    complete = folder / '.complete'
    if complete.exists() and len(list(folder.glob('*.wav'))) == count and (folder / 'manifest.jsonl').exists():
        print(f'{folder_name}: {count} existing; resume', flush=True)
        return
    for p in folder.glob('*.wav'):
        p.unlink()
    complete.unlink(missing_ok=True)
    rng = np.random.default_rng(seed)
    start_time = time.monotonic()
    with (folder / 'manifest.jsonl').open('w', encoding='utf-8') as manifest:
        for i in range(count):
            meta = {'file': f'{i:05d}.wav'}
            if is_pos and i < full_count:
                phrase = FULL[i % len(FULL)]
                condition = FULL_CONDITIONS[(i // len(FULL)) % len(FULL_CONDITIONS)]
                speaker_idx = int(rng.integers(0, len(voices)))
                audio = synth(voices[speaker_idx], phrase, rng)
                pcm, mix_info = mix(audio, rng, condition)
                meta.update(kind='full', phrase=phrase, condition=condition, speaker=speaker_idx, **mix_info)
            elif is_pos:
                j = i - full_count
                fragment = list(FRAGMENTS)[j % len(FRAGMENTS)]
                condition = FRAGMENT_CONDITIONS[(j // len(FRAGMENTS)) % len(FRAGMENT_CONDITIONS)]
                speaker_idx = int(rng.integers(0, len(voices)))
                cropped = condition.startswith('cropped')
                original = FRAGMENTS[fragment][int(rng.integers(0, 2))] if cropped else fragment
                audio = synth(voices[speaker_idx], original, rng)
                if cropped:
                    audio = crop_word(audio, fragment, rng)
                pcm, mix_info = mix(audio, rng, condition)
                meta.update(kind='fragment', phrase=fragment, source=original,
                            condition=condition, speaker=speaker_idx, **mix_info)
            elif (not is_ambiguous) and i < (300 if train else 80):
                pcm = noise_clip(rng)
                meta.update(kind='noise', phrase='synthetic_noise', condition='noise_only')
            else:
                texts = AMBIGUOUS_VAL if is_ambiguous else (TRAIN_NEG if train else CLEAR_VAL)
                offset = 0 if is_ambiguous else (300 if train else 80)
                phrase = texts[(i - offset) % len(texts)]
                condition = FULL_CONDITIONS[((i - offset) // len(texts)) % len(FULL_CONDITIONS)]
                speaker_idx = int(rng.integers(0, len(voices)))
                audio = synth(voices[speaker_idx], phrase, rng)
                pcm, mix_info = mix(audio, rng, condition)
                meta.update(kind='ambiguous' if is_ambiguous else 'negative', phrase=phrase,
                            condition=condition, speaker=speaker_idx, **mix_info)
            wavfile.write(str(folder / meta['file']), SR, pcm)
            manifest.write(json.dumps(meta, ensure_ascii=False) + '\n')
            if (i + 1) % 100 == 0 or i + 1 == count:
                print(f'{folder_name}: {i+1}/{count}, {time.monotonic()-start_time:.0f}s', flush=True)
    complete.write_text('complete\n', encoding='utf-8')


def prepare_features(trainer, cfg, root):
    # Already noisy: do not use upstream extra augmentation, which can change
    # the controlled gain/SNR of fragment-only examples.
    output = root / 'mww_features'
    output.mkdir(parents=True, exist_ok=True)
    jobs = [('positive_train', 'positive_train.npy'), ('positive_test', 'positive_val.npy'),
            ('negative_train', 'negative_train.npy'), ('negative_test', 'negative_val.npy')]
    for folder, filename in jobs:
        dest = output / filename
        if dest.exists():
            print(f'{filename}: exists; resume', flush=True)
            continue
        values = trainer.extract_features_from_dir(root / folder, folder, cfg['spectrogram_length'],
                                                    augment_stride=None, noise_augments=0)
        if values.ndim != 3 or len(values) == 0:
            raise RuntimeError(f'No features for {folder}')
        np.save(dest, values)
        print(f'{filename}: {values.shape}', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--phase', choices=['generate', 'features', 'train', 'export', 'independent', 'all'], default='all')
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    os.chdir(WORK)
    trainer = load_trainer()
    cfg = trainer.load_config(str(CONFIG))
    root = WORK / 'training' / 'output' / MODEL
    if args.phase in ('generate', 'all'):
        voices = prepare_speakers()
        for folder, train, seed in [('positive_train', True, 2101), ('positive_test', False, 2102),
                                    ('negative_train', True, 2103), ('negative_test', False, 2104),
                                    ('ambiguous_test', False, 2105)]:
            generate_dir(root, folder, train, voices, seed)
    if args.phase == 'independent':
        # Never used by model.fit or early stopping; still the same two voices
        # and phrase vocabulary, so not an independent-speaker/generalization test.
        voices = prepare_speakers()
        for folder, seed in [('positive_test', 3102), ('negative_test', 3104), ('ambiguous_test', 3105)]:
            generate_dir(root / 'independent', folder, False, voices, seed)
    if args.phase in ('features', 'all'):
        prepare_features(trainer, cfg, root)
    if args.phase in ('train', 'all'):
        trainer.phase_train(cfg)
    if args.phase in ('export', 'all'):
        trainer.phase_export(cfg)
    print('Finished phase:', args.phase, flush=True)
    print('RESEARCH ONLY; no live Android integration.', flush=True)

if __name__ == '__main__':
    main()
