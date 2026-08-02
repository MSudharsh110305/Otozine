"""Musical key estimation and Camelot wheel mapping.

The Camelot notation exists so that harmonic mixing is arithmetic instead of
music theory: two tracks sound good back to back when their codes are equal,
adjacent on the wheel (+/-1), or the same number in the other mode. The auto-DJ
sequencer relies on that being a cheap integer comparison.
"""

from __future__ import annotations

import numpy as np

PITCH_NAMES = ("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

# Krumhansl-Kessler key profiles: perceptual weight of each pitch class within
# a major / minor key, from listener studies. Correlating an observed chroma
# vector against all 24 rotations is the standard key-finding method.
_MAJOR_PROFILE = np.array(
    [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
)
_MINOR_PROFILE = np.array(
    [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]
)

# Camelot codes, indexed by pitch class. Number = position on the circle of
# fifths; letter = mode. Relative major/minor share a number (C major 8B <-> A
# minor 8A), which is what makes the "same number, other letter" move work.
_MAJOR_CAMELOT = {0: "8B", 7: "9B", 2: "10B", 9: "11B", 4: "12B", 11: "1B",
                  6: "2B", 1: "3B", 8: "4B", 3: "5B", 10: "6B", 5: "7B"}
_MINOR_CAMELOT = {9: "8A", 4: "9A", 11: "10A", 6: "11A", 1: "12A", 8: "1A",
                  3: "2A", 10: "3A", 5: "4A", 0: "5A", 7: "6A", 2: "7A"}


def estimate_key(chroma: np.ndarray) -> tuple[str, str, float]:
    """Estimate key from a chroma matrix.

    `chroma` is (12, n_frames) as produced by librosa. Returns
    (camelot_code, human_name, confidence 0..1).
    """
    if chroma.ndim != 2 or chroma.shape[0] != 12:
        raise ValueError(f"expected a (12, n) chroma matrix, got {chroma.shape}")

    profile = chroma.mean(axis=1)
    if not np.any(profile) or not np.all(np.isfinite(profile)):
        return "8B", "C major", 0.0

    scores: list[tuple[float, int, bool]] = []
    for tonic in range(12):
        rotated = np.roll(profile, -tonic)
        for template, is_major in ((_MAJOR_PROFILE, True), (_MINOR_PROFILE, False)):
            corr = _correlate(rotated, template)
            scores.append((corr, tonic, is_major))

    scores.sort(reverse=True)
    best_corr, tonic, is_major = scores[0]
    runner_up = scores[1][0]

    # Confidence is the margin over the second-best key, not the raw
    # correlation: an ambiguous track correlates well with several keys at once.
    confidence = float(np.clip((best_corr - runner_up) * 3.0, 0.0, 1.0))

    if is_major:
        return _MAJOR_CAMELOT[tonic], f"{PITCH_NAMES[tonic]} major", confidence
    return _MINOR_CAMELOT[tonic], f"{PITCH_NAMES[tonic]} minor", confidence


def _correlate(observed: np.ndarray, template: np.ndarray) -> float:
    """Pearson correlation, returning 0 for a degenerate input."""
    a = observed - observed.mean()
    b = template - template.mean()
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    if denom < 1e-9:
        return 0.0
    return float(np.dot(a, b) / denom)


def camelot_distance(a: str | None, b: str | None) -> int:
    """Harmonic distance between two Camelot codes.

    0 = identical, 1 = a compatible move (adjacent on the wheel, or the relative
    major/minor), 2+ = increasingly dissonant. The sequencer treats <= 1 as a
    smooth transition.
    """
    parsed_a, parsed_b = _parse(a), _parse(b)
    if parsed_a is None or parsed_b is None:
        return 99

    num_a, mode_a = parsed_a
    num_b, mode_b = parsed_b

    # Wheel is circular: 12 -> 1 is one step, not eleven.
    steps = min((num_a - num_b) % 12, (num_b - num_a) % 12)

    if mode_a == mode_b:
        return steps
    # Relative major/minor: same number, different letter.
    return 1 if steps == 0 else steps + 1


def _parse(code: str | None) -> tuple[int, str] | None:
    if not code or len(code) < 2:
        return None
    number, mode = code[:-1], code[-1].upper()
    if mode not in ("A", "B") or not number.isdigit():
        return None
    value = int(number)
    if not 1 <= value <= 12:
        return None
    return value, mode
