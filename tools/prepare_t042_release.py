#!/usr/bin/env python3
"""Prepare versionCode 76 only AFTER the T042 Android16 instrumentation gate.

Runs on an ephemeral CI checkout. Keeps the version75 checkpoint unchanged in Git.
Does not sign, create keys, or distribute APKs.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'android/app/build.gradle.kts'
CHECKPOINT = ROOT / 'SOURCE_CHECKPOINT_T042.txt'
OLD = ('versionCode = 75', 'versionName = "2.14.41-in-app-oauth-hardening"')
NEW = ('versionCode = 76', 'versionName = "2.14.42-t042-controlled"')

def stage(path=SOURCE, marker=CHECKPOINT):
    if not marker.is_file() or 'UNRELEASED T042 CHECKPOINT' not in marker.read_text(encoding='utf-8'):
        raise ValueError('T042 checkpoint marker missing: refuse to stage')
    source = path.read_text(encoding='utf-8')
    for old, new in zip(OLD, NEW):
        if source.count(old) != 1 or new in source:
            raise ValueError(f'Unexpected version metadata for {old!r}: refuse to stage')
        source = source.replace(old, new, 1)
    path.write_text(source,encoding='utf-8')
    return 'Staged versionCode 76; candidate must still pass aapt/apksigner gate.'

if __name__ == '__main__':
    try:
        print(stage())
    except (OSError,ValueError) as error:
        print('BLOCKED:',error,file=sys.stderr)
        sys.exit(2)
