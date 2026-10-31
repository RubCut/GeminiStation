#!/usr/bin/env python3
"""New candidate: train multiple plausible pronunciations including first-stressed Гемини.
Still synthetic; stress/phonemes require human and device validation.
"""
import run_noisy_experiment as experiment

experiment.CONFIG = experiment.HERE / 'config-pronunciation.yaml'
experiment.MODEL = 'gemini_pronunciation_experimental'
_original_synth = experiment.synth

# Explicit IPA support in Piper avoids eSpeak ignoring Cyrillic combining stress.
# Keep the old automatic pronunciation in 40% of utterances.
PRONUNCIATIONS = {
    'Гемини': ('ɡʲˈemʲinʲɪ', 'ɡˈɛmʲinʲi', 'ɡʲemʲinʲˈi'),
    'Геминай': ('ɡʲˈemʲinaj', 'ɡˈɛmʲinaj'),
    'Джемини': ('dʒˈɛmʲinʲɪ', 'dʒˈɛmʲinʲi', 'dʒemʲinʲˈi'),
    'Джеминай': ('dʒˈɛmʲinaj', 'dʒemʲɪnˈaj'),
    'геми': ('ɡʲˈemʲi', 'ɡˈɛmʲi'),
    'джеми': ('dʒˈɛmʲi', 'dʒemʲˈi'),
}

def synth(voice, text, rng):
    variants = PRONUNCIATIONS.get(text)
    if variants and rng.random() < .6:
        text = '[[' + variants[int(rng.integers(0, len(variants)))] + ']]'
    return _original_synth(voice, text, rng)

experiment.synth = synth
if __name__ == '__main__':
    experiment.main()
