"""Stage 1 -- discover files, content-hash them, dedupe.

Content addressing (blake3 over the file bytes) is what makes the whole
pipeline idempotent. Re-ingesting the same audio from a different folder, or
under a different filename, resolves to the same track rather than a duplicate.
A 32 GB drive fills with accidental duplicates alarmingly fast, so this is not
a nicety.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

from blake3 import blake3

from ..config import AUDIO_EXTS, Config

STAGE = "scan"
STAGE_VERSION = 1

_READ_CHUNK = 1024 * 1024  # 1 MiB -- comfortably above USB read-ahead size

# Folders that never contain music worth ingesting.
_SKIP_DIRS = frozenset({
    "OtoZine", ".cache", "$RECYCLE.BIN", "System Volume Information",
    ".git", "__pycache__", "node_modules", ".Trash-1000",
})


@dataclass(frozen=True)
class ScannedFile:
    path: Path                          # the best-named copy of this audio
    content_hash: str
    size_bytes: int
    mtime: int
    duplicates: tuple[Path, ...] = ()   # other paths with identical bytes


def iter_audio_files(roots: list[Path], *, follow_symlinks: bool = False) -> Iterator[Path]:
    """Walk the given roots, yielding audio files in a stable order.

    Sorted so that two runs over an unchanged tree do identical work in an
    identical sequence -- which is what makes `--limit` reproducible during
    testing.
    """
    for root in roots:
        root = Path(root)
        if root.is_file():
            if root.suffix.lower() in AUDIO_EXTS:
                yield root
            continue

        for dirpath, dirnames, filenames in os.walk(root, followlinks=follow_symlinks):
            # Prune in place so os.walk does not descend into them at all.
            dirnames[:] = sorted(d for d in dirnames if d not in _SKIP_DIRS and not d.startswith("."))
            for name in sorted(filenames):
                if Path(name).suffix.lower() in AUDIO_EXTS:
                    yield Path(dirpath) / name


def hash_file(path: Path) -> str:
    """blake3-256 of the file contents, hex encoded.

    blake3 rather than sha256 because it is several times faster on large files
    and we are hashing tens of gigabytes over USB.
    """
    hasher = blake3(max_threads=blake3.AUTO)
    with path.open("rb") as fh:
        while chunk := fh.read(_READ_CHUNK):
            hasher.update(chunk)
    return hasher.hexdigest()


def scan(roots: list[Path], *, limit: int | None = None) -> Iterator[ScannedFile]:
    """Yield one ScannedFile per unique content hash.

    When the same audio exists under several names -- the norm once a library
    has been collected from multiple sources -- we keep the *most informative*
    filename rather than whichever the directory walk reached first. These two
    can be byte-identical:

        Vaathi Coming MassTamilan.com 320kbps [HQ].mp3
        [Isaimini.com] Vaathi Coming - Master - Anirudh Ravichander (2020).mp3

    Only the second names the film and the composer. Picking by walk order threw
    that away roughly half the time.

    Unreadable files are skipped rather than fatal -- a single corrupt download
    should not halt a 3000-track ingest.
    """
    from .nameparse import parse

    by_hash: dict[str, list[Path]] = {}
    for path in iter_audio_files(roots):
        try:
            if path.stat().st_size == 0:
                continue
            digest = hash_file(path)
        except OSError:
            continue
        by_hash.setdefault(digest, []).append(path)

    emitted = 0
    for digest, paths in by_hash.items():
        if limit is not None and emitted >= limit:
            return

        # Richer parse wins; length breaks ties toward the more descriptive name.
        best = max(paths, key=lambda p: (parse(p).confidence, len(p.stem)))
        try:
            stat = best.stat()
        except OSError:
            continue

        yield ScannedFile(
            path=best,
            content_hash=digest,
            size_bytes=stat.st_size,
            mtime=int(stat.st_mtime),
            duplicates=tuple(p for p in paths if p != best),
        )
        emitted += 1


def archive_master(cfg: Config, src: Path, content_hash: str) -> Path:
    """Copy the original into the drive's master tier, content-addressed.

    Copy-then-rename so an interrupted copy never leaves a truncated file that a
    later run would treat as complete.
    """
    dst = cfg.shard(cfg.master_dir, content_hash, src.suffix.lower())
    if dst.exists() and dst.stat().st_size == src.stat().st_size:
        return dst

    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".part")
    try:
        with src.open("rb") as fin, tmp.open("wb") as fout:
            while chunk := fin.read(_READ_CHUNK):
                fout.write(chunk)
        tmp.replace(dst)
    except OSError:
        tmp.unlink(missing_ok=True)
        raise
    return dst
