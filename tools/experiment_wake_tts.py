#!/usr/bin/env python3
"""Small CPU-only synthetic wake-word experiment; not a production model.

Requires espeakng-loader, numpy, scipy and scikit-learn. No audio leaves the machine.
The held-out split uses eSpeak variants, NOT real people or station recordings.
"""
import ctypes as ct
import numpy as np
from scipy.signal import resample_poly, stft
from scipy.fft import dct
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
import espeakng_loader

lib = espeakng_loader.load_library()
lib.espeak_Initialize.argtypes = [ct.c_int, ct.c_int, ct.c_char_p, ct.c_int]
RATE = lib.espeak_Initialize(2, 0, espeakng_loader.get_data_path().encode(), 0)
if RATE <= 0:
    raise RuntimeError('eSpeak initialization failed')
lib.espeak_SetVoiceByName.argtypes = [ct.c_char_p]
lib.espeak_SetParameter.argtypes = [ct.c_int, ct.c_int, ct.c_int]
lib.espeak_Synth.argtypes = [ct.c_void_p, ct.c_size_t, ct.c_uint, ct.c_int, ct.c_uint, ct.c_uint, ct.c_void_p, ct.c_void_p]
Callback = ct.CFUNCTYPE(ct.c_int, ct.POINTER(ct.c_short), ct.c_int, ct.c_void_p)
parts = []
@Callback
def receive(samples, size, events):
    if samples and size > 0:
        parts.append(ct.string_at(samples, size * 2))
    return 0
lib.espeak_SetSynthCallback.argtypes = [Callback]
lib.espeak_SetSynthCallback(receive)

def synth(text, voice, speed, pitch):
    parts.clear()
    if lib.espeak_SetVoiceByName(('ru+' + voice).encode()) != 0:
        raise RuntimeError('voice unavailable: ' + voice)
    lib.espeak_SetParameter(1, speed, 0)  # rate in words/minute
    lib.espeak_SetParameter(3, pitch, 0)  # pitch
    encoded = text.encode('utf-8')
    if lib.espeak_Synth(encoded, len(encoded) + 1, 0, 1, 0, 1, None, None) != 0:
        raise RuntimeError('synthesis failed')
    return np.frombuffer(b''.join(parts), dtype='<i2').astype(np.float32) / 32768

def features(signal):
    # Temporal MFCC-like features; the prototype is not a streaming Android model.
    signal = resample_poly(signal, 320, 441)  # eSpeak 22050 -> 16000
    _, _, spectrum = stft(signal, 16000, nperseg=400, noverlap=240, nfft=512, boundary=None)
    power = np.abs(spectrum) ** 2
    edges = 700 * (10 ** (np.linspace(0, 2595 * np.log10(1 + 8000 / 700), 28) / 2595) - 1)
    hz = np.linspace(0, 8000, power.shape[0])
    filters = np.array([np.maximum(0, np.minimum((hz - a) / max(1, b - a), (c - hz) / max(1, c - b)))
                        for a, b, c in zip(edges[:-2], edges[1:-1], edges[2:])])
    cepstra = dct(np.log(np.maximum(filters @ power, 1e-10)), type=2, norm='ortho', axis=0)[:13]
    # Normalize utterance duration. Same frontend and negative set used for held-out voices.
    frames = np.linspace(0, max(cepstra.shape[1] - 1, 0), 24)
    return np.concatenate([np.interp(frames, np.arange(cepstra.shape[1]), row) for row in cepstra])

positive = ['гемини', 'джеминай', 'джемини']
train_negative = ['привет', 'алиса', 'маруся', 'хорошо', 'не надо', 'домой', 'где меня', 'камин',
                  'гемоглобин', 'гимн', 'камень', 'джинсы', 'динамик', 'ежи', 'гений', 'нажми',
                  'джем', 'имени', 'времени', 'семь дней', 'замени', 'генерал', 'музыка', 'тишина']
test_negative = ['где мини', 'где минин', 'геометрия', 'кремний', 'имины', 'джем на чай', 'где меня найти',
                 'где мы', 'здесь', 'поехали', 'включи телевизор', 'доброе утро', 'минуту', 'метеор', 'включи музыку']

def make(voices, speeds, pitches, negatives):
    x, y, names = [], [], []
    for voice in voices:
        for speed in speeds:
            for pitch in pitches:
                for label, texts in [(1, positive), (0, negatives)]:
                    for text in texts:
                        x.append(features(synth(text, voice, speed, pitch)))
                        y.append(label); names.append(text)
    return np.array(x), np.array(y), names

train_x, train_y, _ = make(['m1', 'm2', 'f1'], [140, 185], [40, 65], train_negative)
test_x, test_y, test_names = make(['m3', 'f3'], [155, 195], [50], test_negative)
model = make_pipeline(StandardScaler(), LogisticRegression(max_iter=2000, class_weight='balanced', random_state=1))
model.fit(train_x, train_y)
pred = model.predict(test_x)
tp = int(np.sum((pred == 1) & (test_y == 1)))
fn = int(np.sum((pred == 0) & (test_y == 1)))
fp = int(np.sum((pred == 1) & (test_y == 0)))
tn = int(np.sum((pred == 0) & (test_y == 0)))
print('Train clips:', len(train_y), 'held-out clips:', len(test_y))
print('Held-out synthetic voices: TP=%d FN=%d FP=%d TN=%d' % (tp, fn, fp, tn))
print('False-positive phrases:', sorted({name for name, actual, guess in zip(test_names, test_y, pred) if not actual and guess}))
print('Missed wake phrases:', sorted({name for name, actual, guess in zip(test_names, test_y, pred) if actual and not guess}))
print('No station, background, real-human, streaming or false-activations-per-hour test; NOT deployable.')
