"""SQLite access layer.

Everything the pipeline writes goes through here so that transaction and
durability policy lives in exactly one place.

Durability note: the database lives on a pendrive that can be physically yanked
mid-write. We use WAL with synchronous=NORMAL (rather than FULL) because ingest
performs tens of thousands of writes and FULL on USB flash is punishingly slow.
The safety net is `ingest_state`: a torn run loses at most the last batch, and
re-running the pipeline picks up exactly where it left off.
"""

from __future__ import annotations

import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterable, Iterator

from .config import SCHEMA_VERSION

_SCHEMA_FILE = Path(__file__).with_name("schema.sql")


def connect(db_path: Path, *, read_only: bool = False) -> sqlite3.Connection:
    """Open the library database with our standard pragmas applied."""
    db_path = Path(db_path)
    # check_same_thread=False: the pipeline shares one connection across its
    # worker pool and serialises every access behind a single lock, which is the
    # supported way to do this. Callers MUST hold that lock -- see Pipeline.
    if read_only:
        if not db_path.exists():
            raise FileNotFoundError(db_path)
        conn = sqlite3.connect(
            f"file:{db_path.as_posix()}?mode=ro", uri=True, check_same_thread=False
        )
    else:
        db_path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(
            db_path, isolation_level=None, timeout=30.0, check_same_thread=False
        )

    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    if not read_only:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA synchronous = NORMAL")
        conn.execute("PRAGMA temp_store = MEMORY")
        conn.execute("PRAGMA busy_timeout = 30000")
    return conn


def migrate(conn: sqlite3.Connection) -> int:
    """Apply the schema. Idempotent -- safe to call on every run."""
    conn.executescript(_SCHEMA_FILE.read_text(encoding="utf-8"))

    row = conn.execute("SELECT MAX(version) AS v FROM schema_version").fetchone()
    current = row["v"] if row and row["v"] is not None else 0

    if current < SCHEMA_VERSION:
        conn.execute(
            "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
            (SCHEMA_VERSION, int(time.time())),
        )
    elif current > SCHEMA_VERSION:
        raise RuntimeError(
            f"library.db is schema v{current} but this Librarian only understands "
            f"v{SCHEMA_VERSION}. Upgrade the tool rather than downgrading the drive."
        )
    return SCHEMA_VERSION


@contextmanager
def transaction(conn: sqlite3.Connection) -> Iterator[sqlite3.Connection]:
    """Explicit transaction. Batching writes is what keeps USB ingest tolerable."""
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
    except Exception:
        conn.execute("ROLLBACK")
        raise
    else:
        conn.execute("COMMIT")


# ------------------------------------------------------------------ tracks

# Columns the pipeline is allowed to write. Guards against typos silently
# creating no-op updates, and against a stage clobbering a column it shouldn't.
_TRACK_COLUMNS = frozenset({
    "source_path", "master_path", "opus_path", "art_path", "lyrics_path",
    "mbid", "acoustid", "title", "artist", "album_artist", "composer", "album",
    "track_no", "year", "language", "meta_source", "meta_confidence",
    "duration_ms", "sample_rate", "channels", "src_codec", "src_bitrate",
    "bpm", "key_camelot", "key_name", "key_confidence",
    "loudness_lufs", "loudness_range", "true_peak_db", "replaygain_db",
    "energy", "valence", "arousal", "danceability", "is_instrumental",
    "approachability", "engagement",
    "intro_end_ms", "outro_start_ms", "hook_start_ms",
    "vec_index", "tau_hours", "analyzed_at", "missing",
})


def get_track_id(conn: sqlite3.Connection, content_hash: str) -> int | None:
    row = conn.execute(
        "SELECT id FROM tracks WHERE content_hash = ?", (content_hash,)
    ).fetchone()
    return row["id"] if row else None


def insert_track(conn: sqlite3.Connection, content_hash: str, source_path: str, **fields) -> int:
    """Insert a new track, or return the existing id if the hash is already known.

    Content-addressed, so re-ingesting the same bytes from a different path is a
    no-op rather than a duplicate. This is the dedupe mechanism.
    """
    existing = get_track_id(conn, content_hash)
    if existing is not None:
        return existing

    _reject_unknown(fields)
    cols = ["content_hash", "source_path", "added_at", *fields]
    vals = [content_hash, source_path, int(time.time()), *fields.values()]
    placeholders = ", ".join("?" * len(cols))
    cur = conn.execute(
        f"INSERT INTO tracks ({', '.join(cols)}) VALUES ({placeholders})", vals
    )
    return int(cur.lastrowid)


def update_track(conn: sqlite3.Connection, track_id: int, **fields) -> None:
    """Update track columns, respecting user overrides.

    Any field the user has hand-corrected is silently dropped from the update.
    That is the whole contract of `user_overrides`: ingest never wins over a
    human, no matter how many times it re-runs.
    """
    if not fields:
        return
    _reject_unknown(fields)

    protected = {
        r["field"] for r in conn.execute(
            "SELECT field FROM user_overrides WHERE track_id = ?", (track_id,)
        )
    }
    writable = {k: v for k, v in fields.items() if k not in protected}
    if not writable:
        return

    assignments = ", ".join(f"{k} = ?" for k in writable)
    conn.execute(
        f"UPDATE tracks SET {assignments} WHERE id = ?", [*writable.values(), track_id]
    )


def _reject_unknown(fields: dict[str, Any]) -> None:
    unknown = set(fields) - _TRACK_COLUMNS
    if unknown:
        raise ValueError(f"not writable track columns: {sorted(unknown)}")


def set_user_override(conn: sqlite3.Connection, track_id: int, field: str, value) -> None:
    """Record a hand correction and apply it.

    This is the *only* supported way to change a field the pipeline also writes.
    `update_track` deliberately refuses to touch any column listed in
    `user_overrides`, so a correction applied through it would be recorded and
    then silently dropped. This writes both halves: the override row that makes
    the change permanent, and the value itself.

    Passing value=None clears the override, handing the field back to the
    pipeline on the next run.
    """
    if field not in _TRACK_COLUMNS:
        raise ValueError(f"not a correctable column: {field}")

    if value is None:
        conn.execute(
            "DELETE FROM user_overrides WHERE track_id = ? AND field = ?",
            (track_id, field),
        )
        return

    conn.execute(
        "INSERT OR REPLACE INTO user_overrides (track_id, field, value, set_at) "
        "VALUES (?, ?, ?, ?)",
        (track_id, field, str(value), int(time.time())),
    )
    conn.execute(f"UPDATE tracks SET {field} = ? WHERE id = ?", (value, track_id))


# -------------------------------------------------------------------- tags

def replace_tags(
    conn: sqlite3.Connection,
    track_id: int,
    source: str,
    tags: Iterable[tuple[str, str, float]],
) -> None:
    """Replace all tags from one source, leaving other sources untouched.

    Each tag is (tag, kind, confidence). Re-running a tagging stage refreshes
    only its own rows, so the CLAP tagger can never erase MusicBrainz genres.
    """
    conn.execute("DELETE FROM tags WHERE track_id = ? AND source = ?", (track_id, source))
    conn.executemany(
        "INSERT OR REPLACE INTO tags (track_id, tag, kind, source, confidence) "
        "VALUES (?, ?, ?, ?, ?)",
        [(track_id, t.strip().lower(), kind, source, conf) for t, kind, conf in tags if t and t.strip()],
    )


# ----------------------------------------------------------- ingest state

def stage_done(
    conn: sqlite3.Connection, content_hash: str, stage: str, stage_version: int = 1
) -> bool:
    """True if this stage already succeeded at this version for this track."""
    row = conn.execute(
        "SELECT status, stage_version FROM ingest_state WHERE content_hash = ? AND stage = ?",
        (content_hash, stage),
    ).fetchone()
    return bool(row and row["status"] == "ok" and row["stage_version"] >= stage_version)


def mark_stage(
    conn: sqlite3.Connection,
    content_hash: str,
    stage: str,
    status: str,
    *,
    stage_version: int = 1,
    detail: str | None = None,
) -> None:
    conn.execute(
        "INSERT OR REPLACE INTO ingest_state "
        "(content_hash, stage, status, stage_version, detail, updated_at) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (content_hash, stage, status, stage_version, detail, int(time.time())),
    )


def stage_counts(conn: sqlite3.Connection) -> dict[tuple[str, str], int]:
    """(stage, status) -> count. Drives the progress summary in the CLI."""
    return {
        (r["stage"], r["status"]): r["n"]
        for r in conn.execute(
            "SELECT stage, status, COUNT(*) AS n FROM ingest_state GROUP BY stage, status"
        )
    }


def library_stats(conn: sqlite3.Connection) -> dict[str, Any]:
    row = conn.execute(
        """
        SELECT COUNT(*)                                            AS tracks,
               COALESCE(SUM(duration_ms), 0) / 3600000.0           AS hours,
               SUM(CASE WHEN opus_path   IS NOT NULL THEN 1 ELSE 0 END) AS transcoded,
               SUM(CASE WHEN vec_index   IS NOT NULL THEN 1 ELSE 0 END) AS embedded,
               SUM(CASE WHEN mbid        IS NOT NULL THEN 1 ELSE 0 END) AS identified,
               SUM(CASE WHEN missing = 1 THEN 1 ELSE 0 END)             AS missing
        FROM tracks
        """
    ).fetchone()
    stats = dict(row)
    stats["by_language"] = {
        r["language"] or "unknown": r["n"]
        for r in conn.execute(
            "SELECT language, COUNT(*) AS n FROM tracks GROUP BY language ORDER BY n DESC"
        )
    }
    return stats
