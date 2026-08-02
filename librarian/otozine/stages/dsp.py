"""Stage 9/10 -- signal analysis: tempo, key, loudness, structure.

Everything here is computed once on the PC and stored, because the phone must
never do this work. The outputs feed three different features:

  * loudness  -> playback normalisation (the biggest single quality win over a
                 stock player, since downloaded files vary by 15+ LU)
  * bpm/key   -> harmonic auto-DJ sequencing
  * structure -> dead-air trimming and hook previews
"""

from __future__ import annotations

import logging
import warnings
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import numpy as np

from ..config import Config
from ..util import ffmpeg as ff
from ..util.camelot import estimate_key

log = logging.getLogger("otozine")

STAGE = "dsp"
STAGE_VERSION = 1

# librosa's default analysis rate. High enough for chroma (Nyquist 11 kHz covers
# every musical pitch class) and roughly 4x cheaper than full rate.
_DSP_SR = 22050

# Silence below this is treated as dead air worth trimming.
_SILENCE_DB = -50.0
_MIN_SILENCE_S = 0.35


@dataclass
class DspResult:
    duration_ms: int
    bpm: float | None
    key_camelot: str | None
    key_name: str | None
    key_confidence: float
    loudness_lufs: float | None
    loudness_range: float | None
    true_peak_db: float | None
    replaygain_db: float | None
    intro_end_ms: int
    outro_start_ms: int
    hook_start_ms: int
    energy: float
    danceability: float


def analyse(cfg: Config, path: Path) -> DspResult:
    """Run the full signal analysis for one track."""
    import librosa  # imported lazily: it costs ~1.5s to import

    loud = ff.measure_loudness(cfg.ffmpeg, path)

    audio = ff.decode_pcm(
        cfg.ffmpeg, path,
        sample_rate=_DSP_SR, mono=True, max_seconds=cfg.analysis_max_seconds,
    )
    duration_s = len(audio) / _DSP_SR
    full_duration_ms = _probe_duration_ms(cfg, path) or int(duration_s * 1000)

    with warnings.catch_warnings():
        # librosa emits noisy warnings on very short or near-silent inputs; we
        # handle those cases explicitly below.
        warnings.simplefilter("ignore")

        bpm = _estimate_bpm(librosa, audio)
        key_camelot, key_name, key_conf = _estimate_key(librosa, audio)
        rms = librosa.feature.rms(y=audio, frame_length=2048, hop_length=512)[0]

    intro_ms, outro_ms = _trim_points(cfg, path, full_duration_ms)
    hook_ms = _find_hook(rms, duration_s, cfg.hook_length_s, intro_ms, outro_ms)

    return DspResult(
        duration_ms=full_duration_ms,
        bpm=bpm,
        key_camelot=key_camelot,
        key_name=key_name,
        key_confidence=key_conf,
        loudness_lufs=loud.integrated_lufs,
        loudness_range=loud.loudness_range,
        true_peak_db=loud.true_peak_db,
        replaygain_db=loud.gain_for(cfg.target_lufs, cfg.true_peak_ceiling_db),
        intro_end_ms=intro_ms,
        outro_start_ms=outro_ms,
        hook_start_ms=hook_ms,
        energy=_energy(rms, loud.integrated_lufs),
        danceability=_danceability(rms, bpm),
    )


def _probe_duration_ms(cfg: Config, path: Path) -> int | None:
    """True duration, which may exceed the analysis window we decoded."""
    try:
        return ff.probe(cfg.ffprobe, path).duration_ms
    except ff.FFmpegError:
        return None


@lru_cache(maxsize=1)
def _tempo_fn():
    """Resolve librosa's tempo callable once.

    librosa lazy-loads its submodules, so `librosa.feature.rhythm.tempo` raises
    AttributeError on a cold attribute walk even though the function exists. An
    explicit `from ... import` forces the load and sidesteps that entirely. The
    fallback covers older versions where it lived under `librosa.beat`.
    """
    try:
        from librosa.feature.rhythm import tempo
    except ImportError:
        from librosa.beat import tempo
    return tempo


def _estimate_bpm(librosa, audio: np.ndarray) -> float | None:
    """Tempo in BPM, folded into a musically sensible range.

    Beat trackers routinely lock onto half or double time. Folding to 70-180
    means a 75 BPM ballad and a 150 BPM one are not treated as opposites during
    sequencing, which is what actually matters downstream.
    """
    if audio.size < _DSP_SR:
        return None

    try:
        onset_env = librosa.onset.onset_strength(y=audio, sr=_DSP_SR)
        if onset_env.size < 4 or not np.any(onset_env):
            return None
        tempo = _tempo_fn()(onset_envelope=onset_env, sr=_DSP_SR, aggregate=np.median)
    except Exception as exc:                       # noqa: BLE001
        # Logged rather than silently swallowed -- a swallowed AttributeError
        # here is exactly how this returned None for every track once already.
        log.debug("tempo estimation failed: %s: %s", type(exc).__name__, exc)
        return None

    value = float(np.atleast_1d(tempo)[0])
    if not np.isfinite(value) or value <= 0:
        return None

    while value < 70:
        value *= 2
    while value > 180:
        value /= 2
    return round(value, 2)


def _estimate_key(librosa, audio: np.ndarray) -> tuple[str | None, str | None, float]:
    if audio.size < _DSP_SR:
        return None, None, 0.0
    try:
        # CQT chroma tracks pitch far better than STFT chroma for music.
        chroma = librosa.feature.chroma_cqt(y=audio, sr=_DSP_SR)
    except Exception as exc:                       # noqa: BLE001
        log.debug("key estimation failed: %s: %s", type(exc).__name__, exc)
        return None, None, 0.0

    camelot, name, confidence = estimate_key(chroma)
    return camelot, name, confidence


def _trim_points(cfg: Config, path: Path, duration_ms: int) -> tuple[int, int]:
    """Leading/trailing dead-air boundaries in ms.

    YouTube rips habitually carry a second of silence at the head and a long
    fade or channel outro at the tail. Both are worth skipping automatically.
    """
    intro_ms, outro_ms = 0, duration_ms
    try:
        spans = ff.detect_silence(
            cfg.ffmpeg, path, threshold_db=_SILENCE_DB, min_duration_s=_MIN_SILENCE_S
        )
    except ff.FFmpegError:
        return intro_ms, outro_ms

    for start_s, end_s in spans:
        # Silence that begins at the very start of the file is the intro.
        if start_s <= 0.25 and np.isfinite(end_s):
            intro_ms = max(intro_ms, int(end_s * 1000))
        # Silence that runs to the end of the file is the outro.
        if not np.isfinite(end_s) or end_s * 1000 >= duration_ms - 250:
            outro_ms = min(outro_ms, int(start_s * 1000))

    # Never trim so hard that nothing is left; a badly-mastered quiet track
    # should degrade to "no trim" rather than "zero-length track".
    if outro_ms - intro_ms < 5000:
        return 0, duration_ms
    return intro_ms, outro_ms


def _find_hook(
    rms: np.ndarray, duration_s: float, hook_len_s: float, intro_ms: int, outro_ms: int
) -> int:
    """Pick the start of the most representative `hook_len_s` window.

    Loudness is a crude but effective chorus proxy in modern production: the
    chorus is nearly always the densest, loudest section. Used for browse
    previews, so that scrubbing a track plays the part you would recognise
    rather than 20 seconds of intro.
    """
    if rms.size == 0 or duration_s <= hook_len_s:
        return intro_ms

    frames_per_s = rms.size / duration_s
    window = max(1, int(hook_len_s * frames_per_s))
    if window >= rms.size:
        return intro_ms

    # Moving average of energy over a hook-length window.
    kernel = np.ones(window, dtype=np.float64) / window
    smoothed = np.convolve(rms.astype(np.float64), kernel, mode="valid")

    # Restrict to the region between the trim points, and avoid the last window.
    lo = int((intro_ms / 1000.0) * frames_per_s)
    hi = int((outro_ms / 1000.0) * frames_per_s) - window
    lo = max(0, min(lo, smoothed.size - 1))
    hi = max(lo + 1, min(hi if hi > lo else smoothed.size, smoothed.size))

    best = int(np.argmax(smoothed[lo:hi])) + lo
    return int((best / frames_per_s) * 1000)


def _energy(rms: np.ndarray, lufs: float | None) -> float:
    """Perceived intensity, 0..1.

    Blends integrated loudness with RMS variability so that a loud-but-flat
    drone does not outrank a dynamic rock track.
    """
    if rms.size == 0:
        return 0.0
    loudness_term = 0.0 if lufs is None else float(np.clip((lufs + 30.0) / 25.0, 0.0, 1.0))
    drive = float(np.clip(rms.mean() * 6.0, 0.0, 1.0))
    return round(float(np.clip(0.6 * loudness_term + 0.4 * drive, 0.0, 1.0)), 4)


def _danceability(rms: np.ndarray, bpm: float | None) -> float:
    """Cheap danceability proxy, 0..1.

    Rewards a steady, strongly periodic envelope in the 90-135 BPM band. This is
    a placeholder for the Essentia danceability head, which replaces it once the
    ONNX models are in place -- the column contract stays identical either way.
    """
    if rms.size < 8:
        return 0.0

    centred = rms - rms.mean()
    if not np.any(centred):
        return 0.0

    # Autocorrelation peak outside lag 0 measures how regular the pulse is.
    corr = np.correlate(centred, centred, mode="full")[centred.size - 1:]
    if corr[0] <= 0:
        return 0.0
    corr = corr / corr[0]
    pulse = float(np.clip(corr[1:min(corr.size, 200)].max(), 0.0, 1.0)) if corr.size > 1 else 0.0

    tempo_fit = 0.5
    if bpm is not None:
        # Triangular window peaking at ~112 BPM.
        tempo_fit = float(np.clip(1.0 - abs(bpm - 112.0) / 60.0, 0.0, 1.0))

    return round(float(np.clip(0.6 * pulse + 0.4 * tempo_fit, 0.0, 1.0)), 4)
