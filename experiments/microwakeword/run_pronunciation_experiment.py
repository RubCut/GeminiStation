#!/usr/bin/env python3
"""New candidate: train every syllabic stress position for all keywords and fragments.
Still synthetic; stress/phonemes require human and device validation.
"""
import run_noisy_experiment as experiment

experiment.CONFIG = experiment.HERE / 'config-pronunciation.yaml'
experiment.MODEL = 'gemini_all_stress_experimental'
_original_synth = experiment.synth

# Explicit IPA support in Piper avoids eSpeak ignoring Cyrillic combining stress.
# Each tuple includes one stressed variant per syllable; auto eSpeak pronunciation
# remains in the mix too. Stressed-vowel quality is an approximation, not a
# complete model of Russian phonetics or real human accents.
PRONUNCIATIONS = {
    'Гемини': ('ɡʲˈemʲinʲɪ', 'ɡʲimʲˈinʲɪ', 'ɡʲimʲinʲˈi'),
    'Геминай': ('ɡʲˈemʲinaj', 'ɡʲimʲˈinaj', 'ɡʲimʲinˈaj'),
    'Джемини': ('dʒˈɛmʲinʲɪ', 'dʒemʲˈinʲɪ', 'dʒemʲinʲˈi'),
    'Джеминай': ('dʒˈɛmʲinaj', 'dʒemʲˈinaj', 'dʒemʲinˈaj'),
    'Гемма': ('ɡʲˈemmə', 'ɡʲemˈma'),
    'Джемма': ('dʒˈemmə', 'dʒemˈma'),
    'геми': ('ɡʲˈemʲi', 'ɡʲemʲˈi'),
    'джеми': ('dʒˈɛmʲi', 'dʒemʲˈi'),
    'мини': ('mʲˈinʲɪ', 'mʲinʲˈi'),
    'минай': ('mʲˈinaj', 'mʲinˈaj'),
}

def synth(voice, text, rng):
    variants = PRONUNCIATIONS.get(text)
    selected = None
    if variants and rng.random() < .75:
        selected = variants[int(rng.integers(0, len(variants)))]
        text = '[[' + selected + ']]'
    synth.last_pronunciation = selected or 'automatic'
    return _original_synth(voice, text, rng)

experiment.synth = synth
if __name__ == '__main__':
    experiment.main()
