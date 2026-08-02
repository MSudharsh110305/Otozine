"""Shared fixtures.

Audio fixtures are synthesised with ffmpeg rather than committed as binaries:
they stay tiny in git, and the ground truth (key, tempo, loudness) is known
exactly because we chose it, which is what lets the DSP tests assert real
numbers instead of just "did not crash".
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path
from typing import NamedTuple

import pytest

from otozine.config import Config
from otozine.util import ffmpeg as ff

# Chords chosen so each maps to a distinct, known Camelot code.
CHORDS = {
    "Am": ((440.00, 523.25, 659.25), "8A"),
    "C":  ((261.63, 329.63, 392.00), "8B"),
    "Em": ((329.63, 392.00, 493.88), "9A"),
    "G":  ((392.00, 493.88, 587.33), "9B"),
}

class Fixture(NamedTuple):
    filename: str
    title: str      # what the parser should recover, so tests stay data-driven
    chord: str
    bpm: int
    gain: str
    lead_s: float


FIXTURES = [
    Fixture("[Isaimini.com] Vaathi Coming - Master - Anirudh Ravichander (2020) 320kbps.mp3",
            "Vaathi Coming", "Am", 120, "-6dB", 1.5),
    Fixture("01 - Ennai Vidaadhe - Naanum Rowdy Dhaan - Anirudh.mp3",
            "Ennai Vidaadhe", "Em", 96, "-20dB", 0.0),
    Fixture("Radiohead - Karma Police (Official Music Video).mp3",
            "Karma Police", "C", 76, "-3dB", 0.4),
    # Deliberately mastered into clipping. Real Tamil film masters routinely
    # peak above 0 dBFS, and lossy encoding pushes their inter-sample peaks
    # higher still -- which is the case that exposed the true-peak bug. Every
    # earlier fixture had headroom, so none of them could have caught it.
    Fixture("Yaanji - Vikram Vedha - Sam C.S..mp3",
            "Yaanji", "G", 128, "+9dB", 0.0),
]

# title -> expected value, derived so adding a fixture never breaks a test.
EXPECTED_BPM = {f.title: f.bpm for f in FIXTURES}
EXPECTED_CAMELOT = {f.title: CHORDS[f.chord][1] for f in FIXTURES}


def _have_ffmpeg() -> bool:
    try:
        ff.resolve_binary("ffmpeg")
        ff.resolve_binary("ffprobe")
        return True
    except ff.FFmpegMissing:
        return False


requires_ffmpeg = pytest.mark.skipif(
    not _have_ffmpeg(), reason="ffmpeg/ffprobe not installed"
)


@pytest.fixture(scope="session")
def ffmpeg_bin() -> str:
    return ff.resolve_binary("ffmpeg")


@pytest.fixture(scope="session")
def source_music(tmp_path_factory, ffmpeg_bin) -> Path:
    """A folder of synthetic tracks, including one byte-identical duplicate."""
    out = tmp_path_factory.mktemp("srcmusic")

    for name, _title, chord, bpm, gain, lead_s in FIXTURES:
        freqs, _ = CHORDS[chord]
        tones = " + ".join(f"sin(2*PI*{f}*t)" for f in freqs)
        expr = (f"(({tones})/{len(freqs) * 1.6})"
                f"*(0.55+0.45*sin(2*PI*{bpm / 60.0}*t))")

        filters = []
        if lead_s > 0:
            filters.append(f"adelay={int(lead_s * 1000)}")
        filters += [f"volume={gain}", "afade=t=out:st=26:d=3"]
        # Deliberately NO limiter on the hot fixture: a limiter would hold it at
        # -3 dBTP, i.e. not clipping at all, which is the opposite of what that
        # fixture exists to test. Unlimited +9 dB lands around +1.6 dBTP, close
        # to the +1.37 dBTP measured on a real Tamil film master.

        subprocess.run(
            [ffmpeg_bin, "-v", "error", "-y",
             "-f", "lavfi", "-i", f"aevalsrc={expr}:d=30:s=44100",
             "-af", ",".join(filters),
             "-ac", "2", "-c:a", "libmp3lame", "-b:a", "192k", str(out / name)],
            check=True,
        )

    # Same bytes, worse filename: the scanner must keep the informative one.
    shutil.copyfile(out / FIXTURES[0].filename, out / "Vaathi Coming MassTamilan.com [HQ].mp3")
    return out


@pytest.fixture
def drive(tmp_path) -> Config:
    """An empty 'pendrive' rooted in a temp dir.

    `title_only` is switched off here so the metadata tests can assert on the
    fields the parser and merge actually recover. Storing only the title is a
    display decision, not a parsing one -- the machinery underneath still needs
    testing, and `test_title_only_clears_unreliable_fields` covers the default.
    """
    root = tmp_path / "drive"
    root.mkdir()
    return Config.load(root, online=False, title_only=False)


@pytest.fixture
def title_only_drive(tmp_path) -> Config:
    """A drive with the shipping default: song name only."""
    root = tmp_path / "titleonly"
    root.mkdir()
    return Config.load(root, online=False, title_only=True)
