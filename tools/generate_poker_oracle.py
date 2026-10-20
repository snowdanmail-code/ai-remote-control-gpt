#!/usr/bin/env python3
"""Independent Python reference for immutable deterministic poker vectors.
Does not import Kotlin production code, modules, Android, or external packages.
"""
from collections import Counter
from itertools import combinations
from pathlib import Path
import random

RANKS = '23456789TJQKA'
SUITS = 'cdhs'
DECK = [r+s for r in RANKS for s in SUITS]


def rank_five(cards):
    rank_values = [RANKS.index(card[0]) + 2 for card in cards]
    counts = Counter(rank_values)
    ordered = sorted(counts, key=lambda rank: (-counts[rank], -rank))
    flush = len({card[1] for card in cards}) == 1
    unique = set(rank_values)
    straight = next((high for high in range(14, 4, -1)
                     if all((14 if v == 1 else v) in unique
                            for v in range(high - 4, high + 1))), None)
    if flush and straight:
        return 8, (straight,)
    if counts[ordered[0]] == 4:
        return 7, (ordered[0], ordered[1])
    if sorted(counts.values()) == [2, 3]:
        return 6, (ordered[0], ordered[1])
    if flush:
        return 5, tuple(sorted(rank_values, reverse=True))
    if straight:
        return 4, (straight,)
    if counts[ordered[0]] == 3:
        return 3, (ordered[0],) + tuple(sorted(ordered[1:], reverse=True))
    if sorted(counts.values()) == [1, 2, 2]:
        return 2, tuple(sorted(ordered[:2], reverse=True)) + (ordered[2],)
    if counts[ordered[0]] == 2:
        return 1, (ordered[0],) + tuple(sorted(ordered[1:], reverse=True))
    return 0, tuple(sorted(rank_values, reverse=True))


def best(cards):
    if len(cards) != len(set(cards)) or len(cards) not in (5, 6, 7):
        raise ValueError('Invalid cards')
    return max(rank_five(hand) for hand in combinations(cards, 5))


def generate():
    examples = [
        'As Ks Qs Js Ts 2h 3d', 'As 2s 3s 4s 5s Kc Qd',
        'As Ah Ad Ac Ks Qd 2c', 'As Ah Ad Ks Kh Qd Qc',
        'As 2s 3s 4s 7s Qc Kd', 'As Kh Qd Jc Ts 2c 3c',
        'Ah Ad As Kc Qd 4h 3s', 'As Ah Kc Kh Qd Qc 2s',
        'As Ah Kc Qd Jc 4h 3s', 'As Kh Qd 9c 7h 4h 3s',
        'As Ks Qs Js 9s Td 8h', 'Ac Ad Ah Kc Kd Kh 2s',
    ]
    rnd = random.Random(21422)
    for size in (5, 6, 7):
        for _ in range(180):
            examples.append(' '.join(rnd.sample(DECK, size)))
    records = []
    for example in examples:
        category, tie = best(example.split())
        records.append(f'{example}\t{category}\t{",".join(map(str, tie))}')
    return '\n'.join(records) + '\n'


if __name__ == '__main__':
    output = Path(__file__).resolve().parent / 'fixtures' / 'poker_oracle.tsv'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(generate(), encoding='utf-8')
    print('Generated', len(output.read_text().splitlines()), 'independent poker vectors')
