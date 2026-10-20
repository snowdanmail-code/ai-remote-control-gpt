#!/usr/bin/env python3
"""Replay user-owned 1win captures through six minimal COLOR samples.

No networking, OCR, user names, cards, screenshots or full frames are exported.
The audit evaluates an occlusion *blocker*, NOT poker recognition accuracy.
Measured only for the recorded 1080x2340 portrait theme/layout.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from PIL import Image
from io import BytesIO

POINTS = [(680,380), (750,500), (650,570), (750,720), (840,440), (700,290)]


def classify(colors: list[tuple[int,int,int]]) -> tuple[str,int,int]:
    if len(colors) != len(POINTS):
        raise ValueError('exactly six measured pixel samples required')
    navy = sum(b-r >= 11 and b-g >= 8 and r <= 75 and g <= 90 for r,g,b in colors)
    neutral = sum(abs(r-g) <= 10 and abs(g-b) <= 12 and 105 <= r+g+b <= 510 for r,g,b in colors)
    label = ('PANEL_VISIBLE' if navy >= 4 else
             'TABLE_LIKE' if navy == 0 and neutral >= 4 else 'UNKNOWN')
    return label, navy, neutral


def sample_clip(path: Path, sec: float) -> list[tuple[int,int,int]]:
    cmd = ['ffmpeg','-nostdin','-v','error','-ss',str(sec),'-i',str(path),
           '-frames:v','1','-vf','scale=540:1170', '-f','image2pipe',
           '-vcodec','mjpeg','-']
    output = subprocess.run(cmd, capture_output=True, check=True, timeout=30).stdout
    with Image.open(BytesIO(output)) as img:
        # JPEG is used in Android too; transform to standard RGB.
        image = img.convert('RGB')
        return [image.getpixel((round(x*539/1000),round(y*1169/1000))) for x,y in POINTS]


def assess(labels: dict, folder: Path) -> dict:
    observations = []
    for clip in labels['clips']:
        video = folder / clip['filename']
        if not video.is_file():
            raise FileNotFoundError(f'operator video missing: {video.name}')
        for s in clip['samples']:
            state, navy, neutral = classify(sample_clip(video, s['second']))
            # Only coarse counts and timing are emitted, never RGB/image/person data.
            should_block = s['expected'] == 'BLOCK'
            blocked = state != 'TABLE_LIKE'
            observations.append({
                'filename': video.name, 'second': s['second'],
                'expected': s['expected'], 'observed': state,
                'panelVotes': navy, 'neutralVotes': neutral,
                'correctForBlocker': blocked == should_block
            })
    return {'schemaVersion':1, 'source':'user-owned original 1win recordings',
            'purpose':'read-only overlay blocker evaluation, not gameplay accuracy',
            'recordingLayout':'1080x2340 portrait only',
            'counts':{'samples':len(observations), 'correct':sum(x['correctForBlocker'] for x in observations),
                'blockingMisses':sum(x['expected']=='BLOCK' and x['observed']=='TABLE_LIKE' for x in observations),
                'unnecessaryBlocks':sum(x['expected']=='TABLE' and x['observed']!='TABLE_LIKE' for x in observations)},
            'observations':observations,
            'limitations':['samples are deliberately selected and not a blind independent holdout',
                'TABLE_LIKE is not proof of table identity, hand, actions or turn',
                'unknown layout/theme, animation or partial overlay must fail closed',
                'no operator package identifier, runtime captured-JPEG dimensions or live device validation']}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--labels',type=Path,default=Path(__file__).with_name('recorded_surface_labels.json'))
    p.add_argument('--videos',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    labels=json.loads(args.labels.read_text(encoding='utf-8'))
    result=assess(labels,args.videos)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result['counts']))
    if result['counts']['blockingMisses']:
        raise SystemExit('FAIL: a recorded panel frame incorrectly looked like the table')

if __name__=='__main__': main()
