"""Stage 11 -- produce the Opus tier the phone actually plays.

Dual-tier storage: the original stays untouched in `audio/master/` as the
archive, and this stage writes a 128 kbit/s Opus copy to `audio/opus/` that is
roughly a third of the size. A 32 GB drive holds about 3x more music this way,
and the phone cache holds 2500+ tracks.

Two deliberate non-decisions:

  * **We do not bake in the normalisation gain.** It is written as an
    R128_TRACK_GAIN tag and applied at playback. Baking it in would make the
    target loudness permanent and risk clipping a track twice if the pipeline
    ever re-ran.
  * **We do not trim the dead air.** Trimming would shift every stored
    millisecond offset (hook, intro, outro) out of alignment with the master,
    leaving two incompatible coordinate systems. The player seeks past the
    intro instead, which achieves the same thing and stays reversible.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

from ..config import Config
from ..util import ffmpeg as ff

STAGE = "transcode"
STAGE_VERSION = 1


def to_opus(
    cfg: Config,
    src: Path,
    content_hash: str,
    row: sqlite3.Row | None = None,
    *,
    force: bool = False,
) -> tuple[Path, ff.LoudnessResult | None]:
    """Encode `src` to the phone tier.

    Returns (path, loudness_of_the_encoded_file). The loudness is re-measured
    on the Opus rather than inherited from the source, because **lossy encoding
    moves the true peak**. A master that already clips at +1.4 dBTP -- routine
    in modern Tamil film mastering -- came back at +2.2 dBTP after encoding, so
    a ceiling computed from the source let the delivered file clip by 0.8 dB.
    Integrated loudness is unaffected (it moved by 0.01 dB in testing); only the
    peak needs the delivered file to be correct.

    Returns None for the loudness when the file was already present, since the
    stored gain is then already correct.

    `force` re-encodes even when the output exists. Without it, `--force
    transcode` would re-run the stage but skip the actual work here, silently
    leaving the previously stored gain in place.
    """
    dst = cfg.shard(cfg.opus_dir, content_hash, ".opus")
    if not force and dst.exists() and dst.stat().st_size > 0:
        return dst, None

    # Carry a minimal tag set so the files remain usable in any other player.
    # The database is authoritative; these are a courtesy.
    tags: dict[str, str] = {}
    for column, tag_name in (("title", "title"), ("artist", "artist"), ("album", "album")):
        if (value := _get(row, column)):
            tags[tag_name] = str(value)

    # 1. Encode without a gain tag -- we cannot know the right value yet.
    staged = dst.with_suffix(".staged.opus")
    try:
        ff.transcode_opus(
            cfg.ffmpeg, src, staged,
            bitrate_k=cfg.opus_bitrate_k,
            replaygain_db=None,
            tags=tags,
        )

        # 2. Measure what we are actually going to ship.
        loudness = ff.measure_loudness(cfg.ffmpeg, staged)
        gain = loudness.gain_for(cfg.target_lufs, cfg.true_peak_ceiling_db)

        # 3. Stamp the gain in. A stream copy, so no second encode.
        ff.tag_opus(cfg.ffmpeg, staged, dst, replaygain_db=gain, tags=tags)
    finally:
        staged.unlink(missing_ok=True)

    return dst, loudness


def _get(row: sqlite3.Row | None, column: str):
    if row is None:
        return None
    try:
        return row[column]
    except (IndexError, KeyError):
        return None


def estimate_size_bytes(duration_ms: int | None, bitrate_k: int) -> int:
    """Predicted Opus size. Used by the phone cache planner to fit a budget."""
    if not duration_ms:
        return 0
    # Opus container overhead is roughly 2% at these bitrates.
    return int((duration_ms / 1000.0) * (bitrate_k * 1000 / 8) * 1.02)
