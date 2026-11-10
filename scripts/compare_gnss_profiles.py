#!/usr/bin/env python3
"""Compare a receiver capture / detector report with another window, independently of Kotlin.

This is descriptive evidence, not a real/fake classifier or a statistical equivalence test.
Use a verified stopped-backend capture as reference; file contents cannot prove its source.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import json
import math
from pathlib import Path
import random
import sys

SYSTEMS = {0: "UNKNOWN", 1: "GPS", 2: "SBAS", 3: "GLONASS", 4: "QZSS",
           5: "BEIDOU", 6: "GALILEO", 7: "IRNSS"}
MAX_BYTES = 32 * 1024 * 1024


@dataclass(frozen=True)
class Satellite:
    system: str
    svid: int
    cn0: float
    elevation: float
    used: bool

    @property
    def identity(self):
        return self.system, self.svid


@dataclass(frozen=True)
class Frame:
    time: int
    signals: tuple[Satellite, ...]

    @property
    def identities(self):
        return {s.identity for s in self.signals}

    @property
    def used(self):
        return {s.identity for s in self.signals if s.used}


@dataclass
class Window:
    frames: list[Frame]
    device: object
    per_signal: bool
    source: str
    original_frames: int


def integer(value, name):
    if type(value) is not int or value < 0:
        raise ValueError(f"{name} must be a nonnegative integer")
    return value


def number(value, low, high, name, inclusive=True):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(f"invalid {name}: {value!r}")
    if value < low or (value > high if inclusive else value >= high):
        raise ValueError(f"{name} outside supported range: {value}")
    return value


def read_window(data):
    detector = "gnssEvidence" in data
    evidence = data.get("gnssEvidence") if detector else data
    if not isinstance(evidence, dict):
        raise ValueError("missing GNSS evidence; older detector reports have no frame data")
    if evidence.get("schema" if detector else "schemaVersion") != 1:
        raise ValueError("unsupported GNSS evidence schema")
    start = integer(evidence.get("startedAtMillis"), "window start")
    end = integer(evidence.get("endedAtMillis"), "window end")
    if end < start:
        raise ValueError("window ends before it starts")
    raw_frames = evidence.get("frames")
    if not isinstance(raw_frames, list) or not 1 <= len(raw_frames) <= 3000:
        raise ValueError("requires 1..3000 GNSS frames")
    by_second = {}
    previous_time = -1
    per_signal = True
    for frame in raw_frames:
        time = integer(frame.get("capturedAtMillis"), "frame time")
        if not start <= time <= end or time < previous_time:
            raise ValueError("nonchronological frame or frame outside window")
        previous_time = time
        observations = frame.get("signalObservations")
        if not detector and observations:
            records = [record["satellite"] for record in observations]
        else:
            records = frame.get("satellites")
            if not detector:
                per_signal = False
        if not isinstance(records, list) or len(records) > 512:
            raise ValueError("invalid signal record count")
        signals = []
        for record in records:
            raw_system = record.get("constellation")
            system = SYSTEMS.get(raw_system) if type(raw_system) is int else raw_system
            if system not in SYSTEMS.values():
                raise ValueError(f"unsupported constellation: {raw_system!r}")
            svid = integer(record.get("svid"), "SVID")
            if not 1 <= svid <= 999 or type(record.get("usedInFix")) is not bool:
                raise ValueError("invalid identity or usedInFix flag")
            elevation = number(record.get("elevationDegrees" if detector else "elevationDeg"),
                               0, 90, "elevation")
            number(record.get("azimuthDegrees" if detector else "azimuthDeg"), 0, 360, "azimuth", False)
            signals.append(Satellite(system, svid, number(record.get("cn0DbHz"), 0, 63, "CN0"),
                                     elevation, record["usedInFix"]))
        # Arrival bursts must not give one receiver second ten times the statistical weight.
        by_second[time // 1000] = Frame(time, tuple(signals))
    device = data.get("device") if detector else {"model": data.get("deviceModel"), "os": data.get("osVersion")}
    return Window(list(by_second.values()), device, per_signal,
                  "detector" if detector else "receiver_capture", len(raw_frames))


def metrics(frames):
    counts = Counter(identity[0] for f in frames for identity in f.identities)
    total = sum(counts.values())
    return {"meanVisible": total / len(frames),
            "usedFraction": sum(len(f.used) for f in frames) / total if total else None,
            **{f"ratio:{system}": counts[system] / total if total else None for system in SYSTEMS.values()}}


def blocks(window, block_seconds=10):
    start = window.frames[0].time
    result = {}
    for frame in window.frames:
        result.setdefault((frame.time - start) // (block_seconds * 1000), []).append(frame)
    return list(result.values())


def histogram_distance(a, b):
    """Total variation of empirical categorical probabilities: 1/2 sum |p_i-q_i|."""
    na, nb = sum(a.values()), sum(b.values())
    if not na or not nb:
        return None
    return 0.5 * sum(abs(a[key] / na - b[key] / nb) for key in a.keys() | b.keys())


def summaries(window, block_seconds):
    frames = window.frames
    gaps = [b.time - a.time for a, b in zip(frames, frames[1:])]
    return {"sourceFormat": window.source, "device": window.device,
            "receivedFrames": window.original_frames, "receiverSeconds": len(frames),
            "spanMillis": frames[-1].time - frames[0].time, "temporalBlocks": len(blocks(window, block_seconds)),
            "maximumGapMillis": max(gaps, default=0), "perSignalEvidence": window.per_signal,
            "visibleCountHistogram": dict(sorted(Counter(len(f.identities) for f in frames).items())),
            "metrics": metrics(frames)}


def enough(window, block_seconds):
    return (len(window.frames) >= 20 and window.frames[-1].time - window.frames[0].time >= 30000
            and len(blocks(window, block_seconds)) >= 3 and any(f.identities for f in window.frames))


def compare(reference, trial, minimum_satellites=0, iterations=1000, block_seconds=10):
    if (type(minimum_satellites) is not int or not 0 <= minimum_satellites <= 128 or iterations < 100
            or type(block_seconds) is not int or not 1 <= block_seconds <= 300):
        raise ValueError("invalid minimum, block size or bootstrap iteration count")
    left, right = summaries(reference, block_seconds), summaries(trial, block_seconds)
    notes = ["Reference authenticity and stopped-backend state must be established separately.",
             "Intervals describe these windows only; zero in an interval is not proof of equivalence.",
             "Separate marginal 95% intervals have no simultaneous coverage guarantee.",
             "Block resampling assumes approximate stationarity and weak dependence across blocks; check block-length sensitivity.",
             "The two windows are resampled independently; shared replay evidence does not create independent real observations."]
    eligible = enough(reference, block_seconds) and enough(trial, block_seconds)
    if not eligible:
        notes.append("Insufficient frame count/span/temporal blocks; bootstrap intervals withheld.")
    if any(s["maximumGapMillis"] > 2000 for s in (left, right)):
        notes.append("Sampling gaps exceed 2 seconds; temporal coverage is incomplete.")
    if minimum_satellites:
        notes.append("A positive visible-satellite floor changes the distribution; this is not auto-mode validation.")
    if reference.per_signal != trial.per_signal:
        notes.append("Per-signal evidence differs from spacecraft projection; joint signal distance withheld.")
    deltas = {key: {"reference": value, "trial": right["metrics"][key],
                    "deltaTrialMinusReference": right["metrics"][key] - value
                    if value is not None and right["metrics"][key] is not None else None,
                    "bootstrapPercentile95": None} for key, value in left["metrics"].items()}
    if eligible:
        rng = random.Random(0)
        lb, rb = blocks(reference, block_seconds), blocks(trial, block_seconds)
        samples = {key: [] for key in deltas}
        for _ in range(iterations):
            a = metrics([frame for _ in lb for frame in rng.choice(lb)])
            b = metrics([frame for _ in rb for frame in rng.choice(rb)])
            for key in deltas:
                if a[key] is not None and b[key] is not None:
                    samples[key].append(b[key] - a[key])
        for key, values in samples.items():
            # Do not discard empty-sky bootstrap draws and narrow the interval conditionally.
            if len(values) == iterations:
                values.sort()
                deltas[key]["bootstrapPercentile95"] = [values[math.floor(.025 * (iterations - 1))],
                                                        values[math.ceil(.975 * (iterations - 1))]]
    def joint(window):
        return Counter((s.system, min(17, int(s.elevation // 5)), int(s.cn0 // 5))
                       for f in window.frames for s in f.signals)
    identities_a = set().union(*(f.identities for f in reference.frames))
    identities_b = set().union(*(f.identities for f in trial.frames))
    return {"schema": 1, "verdict": "DESCRIPTIVE_ONLY", "minimumSatellites": minimum_satellites,
            "bootstrap": {"blockSeconds": block_seconds, "iterations": iterations, "seed": 0,
                          "method": "independent nonoverlapping temporal blocks; marginal percentile intervals"},
            "autoModeComparable": minimum_satellites == 0, "reference": left, "trial": right,
            "metrics": deltas, "visibleCountTotalVariation": histogram_distance(
                Counter(len(f.identities) for f in reference.frames), Counter(len(f.identities) for f in trial.frames)),
            "constellationTotalVariation": histogram_distance(
                Counter(i[0] for f in reference.frames for i in f.identities),
                Counter(i[0] for f in trial.frames for i in f.identities)),
            "signalJointTotalVariation": histogram_distance(joint(reference), joint(trial))
                if reference.per_signal and trial.per_signal else None,
            "addedIdentities": sorted(f"{c}:{s}" for c, s in identities_b - identities_a),
            "missingIdentities": sorted(f"{c}:{s}" for c, s in identities_a - identities_b),
            "notes": notes}


def load(path):
    if path.stat().st_size > MAX_BYTES:
        raise ValueError(f"input exceeds {MAX_BYTES} bytes")
    return read_window(json.loads(path.read_text(encoding="utf-8-sig")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("trial", type=Path)
    parser.add_argument("--minimum-satellites", type=int, required=True,
                        help="actual trial setting: 0=auto; a positive floor alters the reference distribution")
    parser.add_argument("--block-seconds", type=int, default=10,
                        help="temporal resampling block length; repeat with other lengths to assess sensitivity")
    args = parser.parse_args()
    try:
        result = compare(load(args.reference), load(args.trial), args.minimum_satellites,
                         block_seconds=args.block_seconds)
    except (ValueError, KeyError, TypeError, OSError, AttributeError) as error:
        print(f"GNSS comparison unavailable: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
