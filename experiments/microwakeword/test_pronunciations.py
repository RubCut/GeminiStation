#!/usr/bin/env python3
"""Dependency-free checks on the training script's explicit IPA coverage."""
import ast
from pathlib import Path

HERE = Path(__file__).resolve().parent

def literal(path, name):
    tree = ast.parse(path.read_text(encoding='utf-8'))
    for stmt in tree.body:
        if isinstance(stmt, ast.Assign) and any(isinstance(t, ast.Name) and t.id == name for t in stmt.targets):
            return ast.literal_eval(stmt.value)
    raise AssertionError(f'{name} not found in {path}')

variants = literal(HERE / 'run_pronunciation_experiment.py', 'PRONUNCIATIONS')
full = literal(HERE / 'run_noisy_experiment.py', 'FULL')
fragments = literal(HERE / 'run_noisy_experiment.py', 'FRAGMENTS')
assert set(variants) == set(full) | set(fragments), 'every full name and fragment must have explicit variants'
for word, pronunciations in variants.items():
    syllables = sum(c in 'аеёиоуыэюя' for c in word.lower())
    assert len(pronunciations) == syllables, word
    stressed_positions = []
    for ipa in pronunciations:
        assert ipa.count('ˈ') == 1, (word, ipa)
        left, right = ipa.split('ˈ')
        assert any(c in 'eɛiɪaə' for c in right), (word, ipa)
        stressed_positions.append(sum(c in 'eɛiɪaə' for c in left))
    assert sorted(stressed_positions) == list(range(syllables)), (word, pronunciations)
print('All full names and fragments cover every stress position')
