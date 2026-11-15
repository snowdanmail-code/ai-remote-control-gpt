#!/usr/bin/env python3
"""T050/05: verify measured seat-badge color evidence against the user's videos.

Offline only; never connects to 1win/ChatGPT, dispatches gestures, or
converts a colored badge into a verified player action.

Usage:
 python tools/t050_video_replay_check.py \
   --first Screen_Recording_20261113_030125.mp4 \
   --second Screen_Recording_20261113_030422.mp4

opencv-python and the ffmpeg executable are needed ONLY for this optional local replay. Github CI
validates the manifest structure without the private videos.
"""
import argparse
import json
from pathlib import Path


MANIFEST = Path(__file__).resolve().parents[1] / "evidence" / "T050_video_reference_labels.json"


def check_manifest(data: dict) -> None:
    assert data["schema"] == "t050.recorded-seats.v1"
    assert len(data["pixel_rois"]) == 3
    for name in ("video1", "video2"):
        item = data[name]
        assert (item["width"], item["height"]) == (1080, 2340)
        assert all(0 <= event["second"] < 160 for event in item["events"])
        assert len(set(event["second"] for event in item["events"])) == len(item["events"])
    for roi in data["pixel_rois"]:
        x1, y1, x2, y2 = roi["rect_px"]
        assert 0 <= x1 < x2 <= 1080 and 0 <= y1 < y2 <= 2340
        for samples in roi["reference"].values():
            assert all(0 <= samples[key] <= 1 for key in ("green", "light", "red"))
    assert "pot_is_not_added_to_seat_wagers" in data["invariants"]
    labels = data["video1"]["events"][1]["actions"]
    assert len(labels) == 2 and {x["seat"] for x in labels} == {
        "upper_left", "top"
    }
    second = data["video2"]["events"][0]
    assert sum(second["contributions_minor"].values()) == second["pot_minor"]


def sample(video: Path, second: float, rect, cv2) -> tuple:
    """Get the true presentation-time frame; Android recordings use VFR.

    OpenCV CAP_PROP_POS_MSEC seeking is NOT accurate for this VFR source:
    asking for 40s has been observed to return a roughly 42s frame.
    Accurate seeking via FFmpeg protects the labeled ground truth.
    """
    import subprocess
    import numpy as np

    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error",
         "-ss", str(second), "-i", str(video),
         "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "pipe:1"],
        capture_output=True, check=True
    )
    buffer = np.frombuffer(result.stdout, dtype=np.uint8)
    frame = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
    if frame is None or frame.shape[:2] != (2340, 1080):
        raise RuntimeError(f"Cannot decode exact 1080x2340 video at t={second}")
    x1, y1, x2, y2 = rect
    roi = frame[y1:y2:2, x1:x2:2]
    b = roi[:, :, 0].astype(float)
    g = roi[:, :, 1].astype(float)
    r = roi[:, :, 2].astype(float)
    lo = np.minimum(np.minimum(b, g), r)
    hi = np.maximum(np.maximum(b, g), r)
    green = (g >= 90) & (g >= r * 1.35) & (g >= b * 1.12) & (g-lo > 40)
    light = (lo >= 166) & (hi-lo < 60)
    red = (r >= 140) & (r >= g * 1.35) & (r >= b * 1.26) & (r-lo > 38)
    return (float(green.mean()), float(light.mean()), float(red.mean()))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--first", type=Path, required=True)
    parser.add_argument("--second", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    check_manifest(data)
    try:
        import cv2
    except ImportError as ex:
        raise SystemExit("Install opencv-python for optional offline replay") from ex
    for path in (args.first, args.second):
        if not path.is_file():
            raise SystemExit(f"Missing private user video: {path}")
    for zone in data["pixel_rois"]:
        report = []
        for at, expected in zone["reference"].items():
            t = float(at[1:])
            observed = sample(args.first, t, zone["rect_px"], cv2)
            report.append((t, observed))
            for actual, key in zip(observed, ("green", "light", "red")):
                if abs(actual - expected[key]) > 0.13:
                    raise AssertionError(
                        f"{zone['name']} {at} {key}: measured {actual:.3f}, expected {expected[key]:.3f}"
                    )
        print(zone["name"], report)
    # Prove expected color separation even against a bright static avatar.
    zones = {z["name"]: z for z in data["pixel_rois"]}
    assert zones["left_action"]["reference"]["t42"]["green"] - zones["left_action"]["reference"]["t40"]["green"] > 0.5
    assert zones["top_action"]["reference"]["t42"]["light"] - zones["top_action"]["reference"]["t40"]["light"] > 0.5
    assert zones["right_action"]["reference"]["t48"]["light"] - zones["right_action"]["reference"]["t40"]["light"] > 0.3
    print("PASS: frame-grounded color evidence, no OCR/gameplay claims")


if __name__ == "__main__":
    main()
