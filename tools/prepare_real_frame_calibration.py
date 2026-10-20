#!/usr/bin/env python3
"""Explicitly measured ORIGINAL Android screenshots -> read-only calibration JSON.

No detection, AI guessing, networking, or automatic copying of screenshots.
Input measurements must come from a human-approved, redacted real screen.
Pixel bounds are [left, top, right, bottom] on the supplied image.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from PIL import Image

ROLES = {"SEAT_ACTION", "SEAT_STACK", "SEAT_CONTRIBUTION", "SEAT_TURN", "POT", "TIMER", "HERO_FOLD", "HERO_CHECK_CALL", "HERO_BET_RAISE"}
APP_RE = re.compile(r"[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+")


def make_profile(image_path: Path, regions_file: Path, package: str, layout_key: str,
                 capture_width: int | None = None, capture_height: int | None = None) -> dict:
    if not APP_RE.fullmatch(package):
        raise ValueError("Android package must be explicit and valid")
    if not layout_key or len(layout_key) > 64 or any(ord(ch) < 32 for ch in layout_key):
        raise ValueError("invalid layoutKey")
    with Image.open(image_path) as img:
        width, height = img.size
    if width <= 0 or height <= 0:
        raise ValueError("invalid input image")
    if (capture_width is None) != (capture_height is None):
        raise ValueError("specify both capture dimensions")
    target_width = capture_width or width
    target_height = capture_height or height
    if not 320 <= target_width <= 5000 or not 320 <= target_height <= 9000:
        raise ValueError("invalid captured JPEG dimensions")
    if abs(width / height - target_width / target_height) > 0.005:
        raise ValueError("capture dimensions must preserve the image aspect ratio")
    with regions_file.open(encoding="utf-8") as source:
        regions = json.load(source)["zones"]
    if not 2 <= len(regions) <= 52:
        raise ValueError("expected 2-52 calibrated fields")
    output_zones = []
    unique_roles = set()
    for item in regions:
        role = item["role"]
        if role not in ROLES:
            raise ValueError(f"invalid role: {role}")
        seat = item.get("seatIndex")
        if role.startswith("SEAT_"):
            if type(seat) is not int or not 0 <= seat <= 9:
                raise ValueError("seat zones require seatIndex 0..9")
        elif seat is not None:
            raise ValueError("global zones cannot have a seatIndex")
        key = (role, seat)
        if key in unique_roles:
            raise ValueError("duplicate role/seat")
        unique_roles.add(key)
        box = item.get("boundsPixels")
        if (not isinstance(box, list) or len(box) != 4 or
            any(type(v) is not int for v in box)):
            raise ValueError("boundsPixels must contain four integer coordinates")
        l, t, r, b = box
        if not (0 <= l < r <= width and 0 <= t < b <= height):
            raise ValueError("pixel bounds out of image")
        normalized = [round(l * 1000 / width), round(t * 1000 / height),
                      round(r * 1000 / width), round(b * 1000 / height)]
        if normalized[0] >= normalized[2] or normalized[1] >= normalized[3]:
            raise ValueError("box becomes too small after normalization")
        if role == "SEAT_ACTION" and not 40 <= normalized[1] < normalized[3] <= 900:
            raise ValueError("seat action box outside conservative OCR band")
        result = {"role": role, "bounds": normalized}
        if seat is not None:
            result["seatIndex"] = seat
        output_zones.append(result)
    controls = [z for z in output_zones if z["role"].startswith("HERO_")]
    if controls and (len(controls) != 3 or len({z["role"] for z in controls}) != 3):
        raise ValueError("all three hero control regions required together")
    for control in controls:
        box = control["bounds"]
        cx = (box[0] + box[2]) // 2
        allowed = {
            "HERO_FOLD": (50, 325),
            "HERO_CHECK_CALL": (335, 660),
            "HERO_BET_RAISE": (670, 950)
        }[control["role"]]
        if not (900 <= box[1] < box[3] <= 990 and
                80 <= box[2]-box[0] <= 340 and
                18 <= box[3]-box[1] <= 90 and
                15 <= box[0] and box[2] <= 985 and allowed[0] <= cx <= allowed[1]):
            raise ValueError("invalid measured hero button geometry")
    for i, a in enumerate(controls):
        for b in controls[i+1:]:
            x, y = a["bounds"], b["bounds"]
            if x[0] < y[2] and y[0] < x[2] and x[1] < y[3] and y[1] < x[3]:
                raise ValueError("overlapping hero button regions")
    seats = [z for z in output_zones if z["role"] == "SEAT_ACTION"]
    if not 2 <= len(seats) <= 10 or len({z["seatIndex"] for z in seats}) != len(seats):
        raise ValueError("2..10 distinct seat action regions required")
    for i, a in enumerate(output_zones):
        for b in output_zones[i+1:]:
            if a["role"] != b["role"]:
                continue
            x, y = a["bounds"], b["bounds"]
            if x[0] < y[2] and y[0] < x[2] and x[1] < y[3] and y[1] < x[3]:
                raise ValueError("overlapping same-role regions")
    return {"schemaVersion": 1, "layoutKey": layout_key, "expectedPackage": package,
            "frameWidth": target_width, "frameHeight": target_height, "zones": output_zones}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--image", type=Path, required=True, help="authentic, redacted screenshot")
    p.add_argument("--regions", type=Path, required=True, help="JSON with zones and pixel rectangles")
    p.add_argument("--package", required=True, help="observed Android package identifier (no guessing)")
    p.add_argument("--layout-key", required=True)
    p.add_argument("--capture-width", type=int, help="actual JPEG width observed in app")
    p.add_argument("--capture-height", type=int, help="actual JPEG height observed in app")
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    profile = make_profile(args.image, args.regions, args.package,
                           args.layout_key, args.capture_width, args.capture_height)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Calibrated {len(profile['zones'])} fields. No screenshots were exported. "
          "Read-only diagnostic profile, NOT identity verification or permission to tap.")


if __name__ == "__main__":
    main()
