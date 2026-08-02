"""Thin, typed wrapper around ffmpeg/ffprobe.

Every audio byte in the pipeline flows through here. Decoding goes straight to
a numpy array over a pipe -- no temp files, which matters when the working
directory is a slow USB drive.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

# Suppress the console window that would otherwise flash on every ffmpeg call
# when the tool is run from a GUI launcher on Windows.
_NO_WINDOW = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0

# Places Windows users commonly end up with ffmpeg that are not (yet) on PATH.
# Literal directories first, then glob patterns.
_WINDOWS_HINT_DIRS = (
    r"C:\ffmpeg\bin",
    r"C:\Program Files\ffmpeg\bin",
    r"C:\ProgramData\chocolatey\bin",
    r"~\scoop\shims",
    r"~\AppData\Local\Microsoft\WinGet\Links",
)
# winget installs into a versioned package directory and only edits PATH, which
# does not take effect until the shell restarts. Globbing for it means the tool
# works immediately after `winget install Gyan.FFmpeg` rather than confusingly
# still reporting the binary as missing.
_WINDOWS_HINT_GLOBS = (
    r"~\AppData\Local\Microsoft\WinGet\Packages\*FFmpeg*\**\bin",
    r"C:\ProgramData\chocolatey\lib\ffmpeg*\tools\**\bin",
)


class FFmpegError(RuntimeError):
    """ffmpeg exited non-zero, or produced output we could not parse."""


class FFmpegMissing(FFmpegError):
    """ffmpeg/ffprobe could not be located at all."""


def resolve_binary(name: str, configured: str | None = None) -> str:
    """Find ffmpeg/ffprobe on PATH, in a bundled dir, or in common install spots."""
    candidates: list[str] = []
    if configured and configured != name:
        candidates.append(configured)

    # A copy shipped next to the tool on the pendrive takes priority over the
    # host machine's, so the drive stays self-contained.
    bundled = Path(__file__).resolve().parents[2] / "bin" / f"{name}.exe"
    if bundled.is_file():
        candidates.append(str(bundled))

    candidates.append(name)

    for cand in candidates:
        found = shutil.which(cand)
        if found:
            return found

    if sys.platform == "win32":
        for hint in _WINDOWS_HINT_DIRS:
            exe = Path(hint).expanduser() / f"{name}.exe"
            if exe.is_file():
                return str(exe)

        for pattern in _WINDOWS_HINT_GLOBS:
            expanded = str(Path(pattern).expanduser())
            root, _, tail = expanded.partition("*")
            root_path = Path(root).parent if not Path(root).is_dir() else Path(root)
            if not root_path.is_dir():
                continue
            # Newest first, so an upgraded winget package wins over a stale one.
            matches = sorted(
                root_path.glob(expanded[len(str(root_path)):].lstrip("\\/") + f"\\{name}.exe"),
                reverse=True,
            )
            if matches:
                return str(matches[0])

    raise FFmpegMissing(
        f"{name} not found. Install it and re-run, or set OTOZINE_{name.upper()} "
        f"to its full path. On Windows: winget install Gyan.FFmpeg"
    )


def _run(cmd: list[str], *, capture_stdout: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        stdout=subprocess.PIPE if capture_stdout else subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        creationflags=_NO_WINDOW,
        check=False,
    )


# --------------------------------------------------------------------- probe

@dataclass(frozen=True)
class ProbeResult:
    duration_ms: int | None
    codec: str | None
    bitrate: int | None          # bits per second
    sample_rate: int | None
    channels: int | None
    tags: dict[str, str]         # embedded metadata, lowercased keys


def probe(ffprobe: str, path: Path) -> ProbeResult:
    """Read container/stream properties and any embedded tags."""
    proc = _run(
        [
            ffprobe, "-v", "error",
            "-print_format", "json",
            "-show_format", "-show_streams",
            "-select_streams", "a:0",
            str(path),
        ],
        capture_stdout=True,
    )
    if proc.returncode != 0:
        raise FFmpegError(f"ffprobe failed on {path.name}: {proc.stderr.decode(errors='replace').strip()}")

    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise FFmpegError(f"ffprobe emitted invalid JSON for {path.name}") from exc

    streams = data.get("streams") or []
    stream = streams[0] if streams else {}
    fmt = data.get("format") or {}

    # Tags can live on either the container or the stream; container wins.
    tags: dict[str, str] = {}
    for src in (stream.get("tags") or {}, fmt.get("tags") or {}):
        for k, v in src.items():
            if isinstance(v, str) and v.strip():
                tags[k.lower()] = v.strip()

    def _int(value) -> int | None:
        try:
            return int(float(value))
        except (TypeError, ValueError):
            return None

    duration_s = fmt.get("duration") or stream.get("duration")
    duration_ms = None
    if duration_s is not None:
        try:
            duration_ms = int(float(duration_s) * 1000)
        except (TypeError, ValueError):
            duration_ms = None

    return ProbeResult(
        duration_ms=duration_ms,
        codec=stream.get("codec_name"),
        bitrate=_int(stream.get("bit_rate") or fmt.get("bit_rate")),
        sample_rate=_int(stream.get("sample_rate")),
        channels=_int(stream.get("channels")),
        tags=tags,
    )


# -------------------------------------------------------------------- decode

def decode_pcm(
    ffmpeg: str,
    path: Path,
    *,
    sample_rate: int = 16000,
    mono: bool = True,
    max_seconds: float | None = None,
    start_seconds: float = 0.0,
) -> np.ndarray:
    """Decode to float32 PCM in memory.

    Returns shape (n,) for mono or (2, n) for stereo, values nominally in
    [-1, 1]. Piping f32le avoids a temp file on the USB drive.
    """
    channels = 1 if mono else 2
    cmd = [ffmpeg, "-v", "error", "-nostdin"]
    if start_seconds > 0:
        cmd += ["-ss", f"{start_seconds:.3f}"]
    cmd += ["-i", str(path)]
    if max_seconds is not None:
        cmd += ["-t", f"{max_seconds:.3f}"]
    cmd += [
        "-map", "a:0",
        "-f", "f32le",
        "-acodec", "pcm_f32le",
        "-ac", str(channels),
        "-ar", str(sample_rate),
        "-",
    ]

    proc = _run(cmd, capture_stdout=True)
    if proc.returncode != 0:
        raise FFmpegError(f"decode failed for {path.name}: {proc.stderr.decode(errors='replace').strip()[:400]}")

    audio = np.frombuffer(proc.stdout, dtype=np.float32)
    if audio.size == 0:
        raise FFmpegError(f"decode produced no samples for {path.name}")

    if not mono:
        usable = (audio.size // 2) * 2
        audio = audio[:usable].reshape(-1, 2).T
    # Guard against decoder NaN/inf leaking into the ML stages.
    return np.nan_to_num(audio, nan=0.0, posinf=0.0, neginf=0.0)


# ------------------------------------------------------------------ loudness

@dataclass(frozen=True)
class LoudnessResult:
    integrated_lufs: float       # EBU R128 integrated loudness
    loudness_range: float        # LRA
    true_peak_db: float          # dBTP
    threshold_lufs: float

    def gain_for(self, target_lufs: float, ceiling_db: float = -1.0) -> float:
        """Gain in dB to reach `target_lufs` without exceeding `ceiling_db` true peak.

        Peak-limited rather than naive, so a quiet-but-clipped track (very common
        in YouTube rips) does not get pushed into distortion.
        """
        gain = target_lufs - self.integrated_lufs
        headroom = ceiling_db - self.true_peak_db
        return round(min(gain, headroom), 2)


_LOUDNORM_JSON = re.compile(r"\{[^{}]*\"input_i\"[^{}]*\}", re.DOTALL)


def measure_loudness(ffmpeg: str, path: Path) -> LoudnessResult:
    """First-pass EBU R128 measurement via ffmpeg's loudnorm filter."""
    proc = _run([
        ffmpeg, "-v", "info", "-nostdin", "-i", str(path),
        "-af", "loudnorm=I=-14:TP=-1:LRA=11:print_format=json",
        "-f", "null", "-",
    ])
    stderr = proc.stderr.decode(errors="replace")
    if proc.returncode != 0:
        raise FFmpegError(f"loudness measurement failed for {path.name}: {stderr.strip()[:400]}")

    match = _LOUDNORM_JSON.search(stderr)
    if not match:
        raise FFmpegError(f"could not parse loudnorm output for {path.name}")

    data = json.loads(match.group(0))

    def _f(key: str, fallback: float) -> float:
        try:
            value = float(data[key])
        except (KeyError, TypeError, ValueError):
            return fallback
        # loudnorm reports -inf / -70 for digital silence.
        return fallback if not np.isfinite(value) else value

    return LoudnessResult(
        integrated_lufs=_f("input_i", -70.0),
        loudness_range=_f("input_lra", 0.0),
        true_peak_db=_f("input_tp", -1.0),
        threshold_lufs=_f("input_thresh", -70.0),
    )


# ------------------------------------------------------------------- silence

_SILENCE_START = re.compile(r"silence_start:\s*(-?[\d.]+)")
_SILENCE_END = re.compile(r"silence_end:\s*(-?[\d.]+)")


def detect_silence(
    ffmpeg: str, path: Path, *, threshold_db: float = -50.0, min_duration_s: float = 0.35
) -> list[tuple[float, float]]:
    """Find silent spans as (start_s, end_s).

    Used to trim the dead air that YouTube rips habitually carry at both ends.
    """
    proc = _run([
        ffmpeg, "-v", "info", "-nostdin", "-i", str(path),
        "-af", f"silencedetect=noise={threshold_db}dB:d={min_duration_s}",
        "-f", "null", "-",
    ])
    stderr = proc.stderr.decode(errors="replace")

    starts = [float(m) for m in _SILENCE_START.findall(stderr)]
    ends = [float(m) for m in _SILENCE_END.findall(stderr)]

    spans: list[tuple[float, float]] = []
    for i, start in enumerate(starts):
        end = ends[i] if i < len(ends) else float("inf")
        spans.append((max(0.0, start), end))
    return spans


# ----------------------------------------------------------------- transcode

def transcode_opus(
    ffmpeg: str,
    src: Path,
    dst: Path,
    *,
    bitrate_k: int = 128,
    replaygain_db: float | None = None,
    trim_start_s: float = 0.0,
    trim_end_s: float | None = None,
    tags: dict[str, str] | None = None,
) -> None:
    """Encode the phone tier.

    The normalisation gain is written as an `R128_TRACK_GAIN` tag rather than
    baked into the samples. Keeping the audio untouched means we can retarget
    loudness later without re-encoding, and the player applies the gain at
    playback -- which is also what lets the same file sound right on wired
    headphones and a loud bus.
    """
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".part")

    cmd = [ffmpeg, "-v", "error", "-nostdin", "-y"]
    if trim_start_s > 0:
        cmd += ["-ss", f"{trim_start_s:.3f}"]
    cmd += ["-i", str(src)]
    if trim_end_s is not None and trim_end_s > trim_start_s:
        cmd += ["-t", f"{trim_end_s - trim_start_s:.3f}"]

    cmd += [
        "-map", "a:0",
        "-map_metadata", "0",
        "-c:a", "libopus",
        "-b:a", f"{bitrate_k}k",
        "-vbr", "on",
        "-compression_level", "10",
        "-application", "audio",
        "-ar", "48000",          # Opus is natively 48 kHz; resample once, here
        # We write to a '.opus.part' temp file for atomicity, which defeats
        # ffmpeg's extension-based format detection -- so state it explicitly.
        "-f", "opus",
    ]

    if replaygain_db is not None:
        # R128_TRACK_GAIN is Q7.8 fixed point (dB * 256), per RFC 7845.
        cmd += ["-metadata", f"R128_TRACK_GAIN={int(round(replaygain_db * 256))}"]

    for key, value in (tags or {}).items():
        if value:
            cmd += ["-metadata", f"{key}={value}"]

    cmd.append(str(tmp))

    proc = _run(cmd)
    if proc.returncode != 0:
        tmp.unlink(missing_ok=True)
        raise FFmpegError(
            f"opus encode failed for {src.name}: {proc.stderr.decode(errors='replace').strip()[:400]}"
        )

    # Atomic publish: a yanked drive can never leave a half-written .opus that
    # the pipeline would later mistake for a completed one.
    tmp.replace(dst)


def tag_opus(
    ffmpeg: str,
    src: Path,
    dst: Path,
    *,
    replaygain_db: float | None = None,
    tags: dict[str, str] | None = None,
) -> None:
    """Rewrite an Opus file's metadata without re-encoding it.

    Used to stamp the R128 gain after the encoded file has been measured. A
    stream copy costs milliseconds, where a second encode would cost seconds
    per track and lose quality.
    """
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".part")

    cmd = [ffmpeg, "-v", "error", "-nostdin", "-y", "-i", str(src),
           "-map", "0:a:0", "-c:a", "copy", "-map_metadata", "0"]

    if replaygain_db is not None:
        cmd += ["-metadata", f"R128_TRACK_GAIN={int(round(replaygain_db * 256))}"]
    for key, value in (tags or {}).items():
        if value:
            cmd += ["-metadata", f"{key}={value}"]

    cmd += ["-f", "opus", str(tmp)]

    proc = _run(cmd)
    if proc.returncode != 0:
        tmp.unlink(missing_ok=True)
        raise FFmpegError(
            f"opus retag failed for {src.name}: {proc.stderr.decode(errors='replace').strip()[:400]}"
        )
    tmp.replace(dst)
