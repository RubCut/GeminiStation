"""Validate built-in keyword vocabulary against the pinned acoustic phone inventory."""
from pathlib import Path
import re

assets = Path('app/src/main/assets/wakeword')
model = Path('app/src/main/assets/pocketsphinx/ru')
lines = (model / 'mdef').read_text(encoding='utf8').splitlines()
# The first lines of this model declare monophones; ignore context-dependent rows.
phones = {parts[0] for line in lines for parts in [line.split()]
          if len(parts) >= 6 and parts[1:4] == ['-', '-', '-']}
assert phones, 'No monophones found in acoustic model'
pronunciations = {}
for line in (assets / 'words.dict').read_text(encoding='utf8').splitlines():
    token, *pronunciation = line.split()
    word = re.sub(r'\(\d+\)$', '', token)
    assert pronunciation and all(p in phones for p in pronunciation), (token, pronunciation)
    pronunciations.setdefault(word, 0)
    pronunciations[word] += 1
keywords = []
for line in (assets / 'keywords.list').read_text(encoding='utf8').splitlines():
    word, threshold = line.split()
    assert threshold.startswith('/') and threshold.endswith('/') and 0 < float(threshold[1:-1]) < 1
    assert word in pronunciations, word
    keywords.append(word)
assert set(keywords) == {'gemini', 'jeminai', 'jemini'}
for filename in ['feat.params', 'feature_transform', 'mdef', 'means', 'mixture_weights', 'noisedict', 'transition_matrices', 'variances']:
    assert (model / filename).stat().st_size > 0, filename
print('Wake-word assets and phoneme inventory verified (no speech accuracy test).')
