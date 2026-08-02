"""Stage 7 -- mood analysis from the audio signal.

Nothing here reads a tag, a filename or a lookup. It listens to the waveform,
which is why it works identically on a pristine release and a YouTube rip: the
provenance of the file has no bearing on what the music sounds like.

The plan calls for Essentia's trained mood heads. Those need the ONNX models,
which are not wired up yet -- so this derives mood from signal features that are
well established in music information retrieval and that librosa can compute
directly. The results are honest but heuristic, and every value it produces is
labelled as inferred wherever the app shows it.

Two dimensions carry most of the meaning, following the Russell circumplex model
that most music-emotion research is built on:

    arousal   calm ....................... intense
    valence   bleak ...................... bright

Everything else -- the descriptive tags -- is a region of that plane, adjusted by
timbre. A track gets *several* tags rather than one, because music genuinely is
several things at once: a song can be calm and gentle and a little melancholy,
and forcing a single label throws away most of what you know.
"""

from __future__ import annotations

import logging
import warnings
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from ..config import Config
from ..util import ffmpeg as ff

log = logging.getLogger("otozine")

STAGE = "mood"
STAGE_VERSION = 1

_SR = 22050

# Analyse a window from the hook rather than the whole track. It is both cheaper
# and more representative: intros are frequently unlike the song they introduce,
# and averaging a 20-second ambient pad into a dance track drags every feature
# toward the middle.
_WINDOW_S = 60.0


@dataclass
class MoodResult:
    valence: float          # 0 bleak .. 1 bright
    arousal: float          # 0 calm .. 1 intense
    acousticness: float     # 0 electronic/dense .. 1 sparse/acoustic
    brightness: float       # spectral centre of mass
    tension: float          # roughness / dissonance
    dynamics: float         # how much the loudness moves
    instrumentalness: float # 0 vocal-forward .. 1 likely instrumental
    tags: list[tuple[str, float]] = field(default_factory=list)  # (mood, confidence)


def analyse(
    cfg: Config,
    path: Path,
    *,
    bpm: float | None = None,
    key_camelot: str | None = None,
    hook_start_ms: int = 0,
) -> MoodResult:
    """Derive mood from the audio itself."""
    import librosa

    start = max(0.0, hook_start_ms / 1000.0)
    audio = ff.decode_pcm(
        cfg.ffmpeg, path,
        sample_rate=_SR, mono=True,
        start_seconds=start, max_seconds=_WINDOW_S,
    )
    # A hook near the end can leave too little to work with; fall back to the top.
    if audio.size < _SR * 5 and start > 0:
        audio = ff.decode_pcm(
            cfg.ffmpeg, path, sample_rate=_SR, mono=True, max_seconds=_WINDOW_S
        )
    if audio.size < _SR:
        return MoodResult(0.5, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0)

    # Normalise level BEFORE measuring anything.
    #
    # This is the difference between the analysis working and not working.
    # Modern masters are limited to within a few LU of each other, so absolute
    # loudness says almost nothing about the music -- but it leaks into every
    # spectral measure. Measured raw, a soft ballad mastered as hard as a dance
    # track reads as equally intense, and the first version of this file
    # duly tagged 13 of 14 tracks "intense".
    rms = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))
    if rms > 1e-6:
        audio = (audio * (0.1 / rms)).astype(np.float32)

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        features = _features(librosa, audio)

    return _interpret(features, bpm=bpm, key_camelot=key_camelot)


# ------------------------------------------------------------------ features

def _features(librosa, audio: np.ndarray) -> dict[str, float]:
    """Signal descriptors, each normalised to roughly 0..1.

    The normalisation ranges are the practical span of recorded music rather
    than the theoretical span of the measure -- spectral centroid can reach
    Nyquist in principle, but music lives between about 500 Hz and 4 kHz, and
    scaling to the theoretical range would compress every real track into a
    narrow band where differences vanish.
    """
    stft = np.abs(librosa.stft(audio, n_fft=2048, hop_length=512))

    centroid = librosa.feature.spectral_centroid(S=stft, sr=_SR)[0]
    rolloff = librosa.feature.spectral_rolloff(S=stft, sr=_SR, roll_percent=0.85)[0]
    bandwidth = librosa.feature.spectral_bandwidth(S=stft, sr=_SR)[0]
    flatness = librosa.feature.spectral_flatness(S=stft)[0]
    contrast = librosa.feature.spectral_contrast(S=stft, sr=_SR)
    zcr = librosa.feature.zero_crossing_rate(audio, frame_length=2048, hop_length=512)[0]
    onset_env = librosa.onset.onset_strength(S=librosa.amplitude_to_db(stft), sr=_SR)

    # Harmonic/percussive split on the spectrogram: much cheaper than on the
    # waveform, and only the energy ratio is needed rather than the signals.
    harmonic_s, percussive_s = librosa.decompose.hpss(stft)
    harmonic_energy = float(np.sum(harmonic_s ** 2))
    percussive_energy = float(np.sum(percussive_s ** 2))
    total_energy = harmonic_energy + percussive_energy + 1e-9

    onsets = librosa.onset.onset_detect(onset_envelope=onset_env, sr=_SR)
    duration_s = len(audio) / _SR

    # Ranges are calibrated against real material rather than the theoretical
    # span of each measure. A little headroom past the observed extremes leaves
    # room for quieter or harsher music than the calibration set contained,
    # without collapsing the useful middle.
    return {
        # Where the spectral mass sits. The best single correlate of "bright".
        "brightness": _scale(float(np.mean(centroid)), 1500, 4000),
        "rolloff": _scale(float(np.mean(rolloff)), 2500, 9000),
        # Wide bandwidth reads as dense and busy, narrow as focused and clean.
        "bandwidth": _scale(float(np.mean(bandwidth)), 2000, 3300),
        # Flatness separates tonal music from noise-like texture. Log-scaled
        # because the useful range spans orders of magnitude.
        "noisiness": _scale(float(np.log10(np.mean(flatness) + 1e-8)), -2.6, -1.0),
        # Peak-to-valley contrast: high means clear harmonic structure, low
        # means everything is smeared into one band.
        "contrast": _scale(float(np.mean(contrast)), 14.0, 23.0),
        "zcr": _scale(float(np.mean(zcr)), 0.04, 0.17),
        # Crest factor rather than absolute level: peak over average survives
        # loudness normalisation, absolute loudness does not.
        "dynamics": _scale(
            float(np.max(np.abs(audio)) / (np.sqrt(np.mean(audio ** 2)) + 1e-9)), 2.5, 7.0
        ),
        "percussive_ratio": _scale(percussive_energy / total_energy, 0.05, 0.65),
        "harmonic_ratio": harmonic_energy / total_energy,
        # Events per second. Density of activity, largely independent of tempo.
        "onset_rate": _scale(len(onsets) / max(duration_s, 1e-9), 2.0, 6.5),
        "onset_strength": _scale(float(np.mean(onset_env)), 0.5, 6.0),
        # Spectral flux: how fast the timbre changes frame to frame.
        "flux": _scale(
            float(np.mean(np.sqrt(np.sum(np.diff(stft, axis=1) ** 2, axis=0)))), 25.0, 46.0
        ),
    }


def _scale(value: float, low: float, high: float) -> float:
    if not np.isfinite(value):
        return 0.5
    return float(np.clip((value - low) / (high - low), 0.0, 1.0))


# --------------------------------------------------------------- interpretation

def _interpret(f: dict[str, float], *, bpm: float | None, key_camelot: str | None) -> MoodResult:
    """Turn signal descriptors into mood."""

    # --- arousal: how activating the music is ---------------------------
    # Rhythmic density and percussive share carry this. Absolute loudness is
    # deliberately absent: it was normalised away, and including it before
    # normalisation is what made everything read as intense.
    tempo_term = 0.5 if bpm is None else float(np.clip((bpm - 65) / 105.0, 0.0, 1.0))
    arousal = (
        0.32 * f["onset_rate"]
        + 0.28 * f["percussive_ratio"]
        + 0.20 * f["flux"]
        + 0.20 * tempo_term
    )

    # --- valence: how positive it feels ---------------------------------
    # Mode is the strongest single cue in the literature, and brightness the
    # next. Harmonic contrast is kept deliberately light -- a sad ballad has
    # very clear harmonic structure, so weighting it heavily drags melancholy
    # upward, which is exactly the error worth avoiding here.
    mode_term = 0.5
    if key_camelot:
        last = key_camelot.strip()[-1:].upper()
        if last == "B":
            mode_term = 0.78          # major
        elif last == "A":
            mode_term = 0.26          # minor

    valence = (
        0.38 * mode_term
        + 0.26 * f["brightness"]
        + 0.10 * f["contrast"]
        + 0.14 * tempo_term
        - 0.16 * f["noisiness"]
        + 0.08 * f["harmonic_ratio"]
    )
    valence = float(np.clip(valence + 0.08, 0.0, 1.0))   # recentre after the subtraction
    arousal = float(np.clip(arousal, 0.0, 1.0))

    # --- supporting axes -------------------------------------------------
    acousticness = float(np.clip(
        0.45 * f["harmonic_ratio"] + 0.25 * (1 - f["noisiness"])
        + 0.15 * f["dynamics"] + 0.15 * (1 - f["bandwidth"]),
        0.0, 1.0,
    ))
    tension = float(np.clip(
        0.40 * f["noisiness"] + 0.25 * f["zcr"] + 0.20 * (1 - f["contrast"])
        + 0.15 * f["bandwidth"],
        0.0, 1.0,
    ))
    # Vocals sit mid-band with strong harmonic content and moderate flux. This
    # is a weak signal and is treated as such -- it only nudges tag confidence.
    instrumentalness = float(np.clip(
        0.5 + 0.3 * (f["percussive_ratio"] - 0.5) - 0.4 * (f["contrast"] - 0.5),
        0.0, 1.0,
    ))

    result = MoodResult(
        valence=round(valence, 4),
        arousal=round(arousal, 4),
        acousticness=round(acousticness, 4),
        brightness=round(f["brightness"], 4),
        tension=round(tension, 4),
        dynamics=round(f["dynamics"], 4),
        instrumentalness=round(instrumentalness, 4),
    )
    result.tags = _tags(result, f, tempo_term)
    return result


# Each mood is a soft region of the feature space. Confidence is how well a
# track sits inside it, so several can fire at once with different strengths --
# which is the point: music is rarely one thing.
def _tags(m: MoodResult, f: dict[str, float], tempo_term: float) -> list[tuple[str, float]]:
    candidates: list[tuple[str, float]] = []

    def add(name: str, *terms: float) -> None:
        # Geometric mean: every condition has to hold reasonably well, so a
        # single strong term cannot carry a tag on its own the way a sum would.
        score = float(np.prod(terms) ** (1.0 / len(terms)))
        if score > 0.45:
            candidates.append((name, round(score, 3)))

    lo = lambda x: 1.0 - x  # noqa: E731

    add("calm", lo(m.arousal), lo(f["onset_rate"]))
    add("gentle", lo(m.arousal), m.acousticness, lo(m.tension))
    add("dreamy", lo(m.arousal), m.brightness, lo(f["onset_rate"]), lo(m.tension))
    add("melancholic", lo(m.valence), lo(m.arousal), m.acousticness)
    add("sad", lo(m.valence), lo(f["brightness"]))
    add("romantic", lo(m.arousal), m.acousticness, _near(m.valence, 0.58, 0.28))
    add("warm", m.acousticness, lo(f["noisiness"]), _near(m.brightness, 0.45, 0.3))

    add("energetic", m.arousal, f["onset_rate"])
    add("driving", m.arousal, f["percussive_ratio"], tempo_term)
    add("uplifting", m.valence, m.arousal, m.brightness)
    add("joyful", m.valence, m.brightness, f["contrast"])
    add("playful", m.valence, _near(m.arousal, 0.62, 0.3), f["onset_rate"])
    add("intense", m.arousal, f["flux"], f["percussive_ratio"])
    add("aggressive", m.arousal, m.tension, f["percussive_ratio"], f["zcr"])

    add("dark", lo(m.valence), lo(m.brightness), lo(f["contrast"]))
    add("brooding", lo(m.valence), lo(m.arousal), lo(m.brightness))
    add("tense", m.tension, f["noisiness"])
    add("epic", m.arousal, m.dynamics, f["contrast"])

    add("acoustic", m.acousticness, lo(f["noisiness"]), m.dynamics)
    add("dense", f["bandwidth"], f["onset_rate"], lo(m.dynamics))

    # Keep the strongest handful. Twenty weak labels describe nothing.
    candidates.sort(key=lambda t: -t[1])
    return candidates[:5]


def _near(value: float, centre: float, width: float) -> float:
    """1.0 at `centre`, falling to 0 at +/- `width`. For 'middling' conditions."""
    return float(np.clip(1.0 - abs(value - centre) / width, 0.0, 1.0))
