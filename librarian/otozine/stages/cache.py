"""Phone cache planning and staging.

The pendrive holds everything; the phone holds a rotating subset that fits a
byte budget. This module decides *what* that subset is and materialises it in
the exact layout the Android app expects:

    <out>/library.db
    <out>/audio/opus/ab/<hash>.opus
    <out>/art/ab/<hash>.jpg

Paths inside the database are drive-relative, and the staged tree reproduces
those same relative paths, so nothing has to be rewritten for the phone.
"""

from __future__ import annotations

import logging
import shutil
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path

from ..config import Config
from .. import db

log = logging.getLogger("otozine")

STAGE = "cache"
STAGE_VERSION = 1


@dataclass
class CachePlan:
    tracks: list[sqlite3.Row] = field(default_factory=list)
    total_bytes: int = 0
    skipped_no_audio: int = 0
    excluded_over_budget: int = 0

    @property
    def count(self) -> int:
        return len(self.tracks)


def plan(conn: sqlite3.Connection, cfg: Config, budget_bytes: int) -> CachePlan:
    """Choose which tracks the phone should carry.

    Ranking, best first:

      1. **Loved and recent** -- tracks actually completed lately. Recency is
         weighted because taste drifts; a track played 40 times last year should
         not outrank one played 5 times this week.
      2. **Never played** -- newly ingested material, so new music always gets a
         chance to be heard rather than being starved by history.
      3. **Everything else** by how recently it was added.

    A track that has been skipped hard and often sinks, which is what frees the
    space for the first two groups.
    """
    rows = conn.execute(
        """
        SELECT t.id, t.content_hash, t.title, t.opus_path, t.art_path,
               t.duration_ms,
               COALESCE(s.plays, 0)        AS plays,
               COALESCE(s.skips, 0)        AS skips,
               COALESCE(s.last_played, 0)  AS last_played
        FROM tracks t
        LEFT JOIN (
            SELECT track_id,
                   SUM(CASE WHEN outcome = 'completed' THEN 1 ELSE 0 END) AS plays,
                   SUM(CASE WHEN outcome = 'skipped'
                             AND pct_played < 0.2 THEN 1 ELSE 0 END)      AS skips,
                   MAX(started_at)                                        AS last_played
            FROM play_events
            GROUP BY track_id
        ) s ON s.track_id = t.id
        WHERE t.missing = 0
        ORDER BY t.added_at DESC
        """
    ).fetchall()

    now = _now(conn)
    scored = sorted(rows, key=lambda r: -_score(r, now))

    result = CachePlan()
    for row in scored:
        if not row["opus_path"]:
            result.skipped_no_audio += 1
            continue

        source = cfg.abs(row["opus_path"])
        if not source.is_file():
            result.skipped_no_audio += 1
            continue

        size = source.stat().st_size
        if row["art_path"] and (art := cfg.abs(row["art_path"])).is_file():
            size += art.stat().st_size

        if result.total_bytes + size > budget_bytes:
            result.excluded_over_budget += 1
            continue

        result.tracks.append(row)
        result.total_bytes += size

    return result


def _now(conn: sqlite3.Connection) -> int:
    row = conn.execute("SELECT MAX(started_at) AS t FROM play_events").fetchone()
    return int(row["t"] or 0)


def _score(row: sqlite3.Row, now: int) -> float:
    """Rank a track for inclusion. Higher is more wanted."""
    plays = row["plays"] or 0
    skips = row["skips"] or 0
    last = row["last_played"] or 0

    if plays == 0 and skips == 0:
        # Never played: give it a solid mid-rank so new music is not starved by
        # history it has not had the chance to accumulate.
        return 1.0

    # Half-life of 30 days, so taste drift is followed rather than fought.
    recency = 0.0
    if last and now:
        age_days = max(0.0, (now - last) / 86400.0)
        recency = 0.5 ** (age_days / 30.0)

    return (plays * (0.5 + recency)) - (skips * 0.75)


def stage(cfg: Config, out_dir: Path, budget_bytes: int, *, clean: bool = False) -> CachePlan:
    """Materialise the planned subset into `out_dir`, ready to copy to a phone."""
    out_dir = Path(out_dir)
    if clean and out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    conn = db.connect(cfg.db_path, read_only=True)
    try:
        chosen = plan(conn, cfg, budget_bytes)
        _export_db(conn, out_dir / "library.db", {r["id"] for r in chosen.tracks})
    finally:
        conn.close()

    for row in chosen.tracks:
        _copy(cfg.abs(row["opus_path"]), out_dir / row["opus_path"])
        if row["art_path"]:
            _copy(cfg.abs(row["art_path"]), out_dir / row["art_path"])

    return chosen


def _copy(src: Path, dst: Path) -> None:
    if not src.is_file():
        return
    if dst.is_file() and dst.stat().st_size == src.stat().st_size:
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".part")
    shutil.copyfile(src, tmp)
    tmp.replace(dst)


def _export_db(conn: sqlite3.Connection, dst: Path, keep_ids: set[int]) -> None:
    """Write a consolidated copy of the database for the phone.

    Uses SQLite's backup API rather than a file copy so that any pending WAL is
    folded in -- copying library.db alone can otherwise hand the phone a
    database missing the most recent writes.

    Tracks whose audio was not staged are marked `missing = 1` rather than
    deleted, so their play history and ids stay valid when they are cached again
    later. Merging events back to the drive depends on those ids being stable.
    """
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(".part")
    tmp.unlink(missing_ok=True)

    target = sqlite3.connect(tmp)
    try:
        conn.backup(target)
        placeholders = ",".join("?" * len(keep_ids)) if keep_ids else "NULL"
        target.execute(
            f"UPDATE tracks SET missing = 1 WHERE id NOT IN ({placeholders})",
            tuple(keep_ids),
        )
        target.commit()

        # VACUUM cannot run inside a transaction, and the UPDATE above opened
        # one implicitly. Autocommit mode plus the commit clears the way.
        target.isolation_level = None
        target.execute("VACUUM")
    finally:
        target.close()

    dst.unlink(missing_ok=True)
    tmp.replace(dst)
