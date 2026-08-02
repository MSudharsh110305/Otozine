"""The ingest orchestrator.

Runs each track through the stages, recording per-stage progress so the whole
thing is resumable and idempotent. Two properties are load-bearing:

  * **Resumable.** Yank the drive mid-run, plug it back in, re-run: work already
    done is skipped via `ingest_state`.
  * **Idempotent.** Running twice over an unchanged library changes nothing.
    This is asserted by a test, not assumed.

Concurrency: analysis is CPU-bound but spends most of its time inside ffmpeg and
numpy, both of which release the GIL, so a thread pool gets real parallelism
without the cost of shipping audio buffers between processes.
"""

from __future__ import annotations

import logging
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path

from . import db, metadata
from .config import Config
from .stages import dsp, mood, nameparse, scan, tags, transcode
from .stages.webmeta import WebMetadata
from .util import ffmpeg as ff

log = logging.getLogger("otozine")


@dataclass
class Stats:
    scanned: int = 0
    added: int = 0
    skipped: int = 0
    analysed: int = 0
    moods: int = 0
    transcoded: int = 0
    identified: int = 0
    duplicates: int = 0          # redundant copies found on disk
    duplicate_bytes: int = 0     # space they were wasting
    failed: int = 0
    errors: list[tuple[str, str]] = field(default_factory=list)

    def merge_error(self, name: str, message: str) -> None:
        self.failed += 1
        if len(self.errors) < 25:            # keep the summary readable
            self.errors.append((name, message))


class Pipeline:
    def __init__(self, cfg: Config, *, offline: bool = False, force: set[str] | None = None):
        self.cfg = cfg
        self.offline = offline or not cfg.online
        self.force = force or set()
        self.stats = Stats()
        self.web = None if self.offline else WebMetadata(cfg)

        # One connection, guarded by a lock. SQLite writes serialise anyway, and
        # a single connection keeps transaction batching simple.
        self._db_lock = threading.Lock()
        self._conn: sqlite3.Connection | None = None

    # ---------------------------------------------------------------- setup
    def open(self) -> None:
        self.cfg.ensure_dirs()
        self.cfg.ffmpeg = ff.resolve_binary("ffmpeg", self.cfg.ffmpeg)
        self.cfg.ffprobe = ff.resolve_binary("ffprobe", self.cfg.ffprobe)
        self._conn = db.connect(self.cfg.db_path)
        db.migrate(self._conn)

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def __enter__(self) -> "Pipeline":
        self.open()
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    @property
    def conn(self) -> sqlite3.Connection:
        if self._conn is None:
            raise RuntimeError("pipeline is not open")
        return self._conn

    def _should_run(self, content_hash: str, stage: str, version: int) -> bool:
        if stage in self.force:
            return True
        with self._db_lock:
            return not db.stage_done(self.conn, content_hash, stage, version)

    def _mark(self, content_hash: str, stage: str, status: str, version: int, detail: str | None = None) -> None:
        with self._db_lock:
            db.mark_stage(self.conn, content_hash, stage, status,
                          stage_version=version, detail=detail)

    # ------------------------------------------------------------------ run
    def ingest(self, roots: list[Path], *, limit: int | None = None) -> Stats:
        """Ingest every audio file under `roots`."""
        found = list(scan.scan(roots, limit=limit))
        self.stats.scanned = len(found)
        self.stats.duplicates = sum(len(f.duplicates) for f in found)
        self.stats.duplicate_bytes = sum(f.size_bytes * len(f.duplicates) for f in found)

        log.info("found %d unique audio files", len(found))
        if self.stats.duplicates:
            log.info(
                "  (%d duplicate copies ignored, %.1f MB)",
                self.stats.duplicates, self.stats.duplicate_bytes / 1024**2,
            )
            for item in found:
                for dup in item.duplicates:
                    log.debug("  dup: %s == %s", dup.name, item.path.name)
        if not found:
            return self.stats

        with ThreadPoolExecutor(max_workers=self.cfg.workers) as pool:
            futures = {pool.submit(self._process, item): item for item in found}
            for future in as_completed(futures):
                item = futures[future]
                try:
                    future.result()
                except Exception as exc:                  # noqa: BLE001
                    log.warning("failed: %s (%s)", item.path.name, exc)
                    self.stats.merge_error(item.path.name, str(exc))
                    self._mark(item.content_hash, "pipeline", "failed", 1, str(exc)[:500])

        return self.stats

    def _process(self, item: scan.ScannedFile) -> None:
        """Run all stages for one track."""
        source_rel = str(item.path)

        with self._db_lock:
            with db.transaction(self.conn):
                existing = db.get_track_id(self.conn, item.content_hash)
                track_id = (
                    existing if existing is not None
                    else db.insert_track(self.conn, item.content_hash, source_rel)
                )
        if existing is None:
            self.stats.added += 1
        else:
            self.stats.skipped += 1

        self._stage_archive(item, track_id)
        self._stage_metadata(item, track_id)
        self._stage_analyse(item, track_id)
        self._stage_mood(item, track_id)
        self._stage_transcode(item, track_id)

    # --------------------------------------------------------------- stages
    def _stage_archive(self, item: scan.ScannedFile, track_id: int) -> None:
        """Copy the original into the drive's master tier."""
        if not self.cfg.keep_master:
            return
        if not self._should_run(item.content_hash, "archive", scan.STAGE_VERSION):
            return
        try:
            dst = scan.archive_master(self.cfg, item.path, item.content_hash)
        except OSError as exc:
            self._mark(item.content_hash, "archive", "failed", scan.STAGE_VERSION, str(exc)[:500])
            raise

        with self._db_lock, db.transaction(self.conn):
            db.update_track(self.conn, track_id, master_path=self.cfg.rel(dst))
            db.mark_stage(self.conn, item.content_hash, "archive", "ok",
                          stage_version=scan.STAGE_VERSION)

    def _stage_metadata(self, item: scan.ScannedFile, track_id: int) -> None:
        """Embedded tags + filename parse + optional online lookup, then merge."""
        stage_version = max(tags.STAGE_VERSION, nameparse.STAGE_VERSION)
        if not self._should_run(item.content_hash, "metadata", stage_version):
            return

        candidates: list[metadata.Candidate] = []

        embedded = tags.read(item.path)
        if embedded.title:
            candidates.append(embedded)

        parsed = nameparse.parse(item.path)
        filename_candidate = metadata.Candidate(
            source="filename",
            confidence=parsed.confidence,
            title=parsed.title, artist=parsed.artist, album=parsed.album,
            composer=parsed.composer, year=parsed.year, track_no=parsed.track_no,
            language=parsed.language_hint,
        )
        candidates.append(filename_candidate)

        art_url = acoustid = None
        if self.web is not None:
            result = self.web.lookup(item.path, hint=filename_candidate)
            candidates.extend(result.candidates)
            art_url, acoustid = result.art_url, result.acoustid
            if any(c.source == "musicbrainz" for c in result.candidates):
                self.stats.identified += 1

        resolved = metadata.merge(candidates)
        fields = metadata.to_track_fields(resolved)

        if self.cfg.title_only:
            # Keep the year (usually right, useful for sorting) and clear the
            # rest. Explicit NULLs rather than omitting the keys: omitting them
            # leaves whatever a previous run wrote, so re-running with this on
            # would appear to do nothing.
            keep = ("title", "year", "language", "meta_source", "meta_confidence")
            fields = {k: v for k, v in fields.items() if k in keep}
            for column in ("artist", "album", "album_artist", "composer", "track_no"):
                fields[column] = None
        if acoustid:
            fields["acoustid"] = acoustid

        art_path = None
        if art_url:
            dest = self.cfg.shard(self.cfg.art_dir, item.content_hash, ".jpg")
            if not dest.exists() and self.web.fetch_art(art_url, dest):
                art_path = self.cfg.rel(dest)
            elif dest.exists():
                art_path = self.cfg.rel(dest)
        if art_path:
            fields["art_path"] = art_path

        with self._db_lock, db.transaction(self.conn):
            db.update_track(self.conn, track_id, **fields)
            for source, tag_list in metadata.collect_tags(candidates).items():
                db.replace_tags(self.conn, track_id, source, tag_list)
            db.mark_stage(self.conn, item.content_hash, "metadata", "ok",
                          stage_version=stage_version)

    def _stage_analyse(self, item: scan.ScannedFile, track_id: int) -> None:
        """Tempo, key, loudness, structure."""
        if not self._should_run(item.content_hash, dsp.STAGE, dsp.STAGE_VERSION):
            return
        try:
            result = dsp.analyse(self.cfg, item.path)
        except (ff.FFmpegError, ValueError) as exc:
            self._mark(item.content_hash, dsp.STAGE, "failed", dsp.STAGE_VERSION, str(exc)[:500])
            raise

        with self._db_lock, db.transaction(self.conn):
            db.update_track(
                self.conn, track_id,
                duration_ms=result.duration_ms,
                bpm=result.bpm,
                key_camelot=result.key_camelot,
                key_name=result.key_name,
                key_confidence=result.key_confidence,
                loudness_lufs=result.loudness_lufs,
                loudness_range=result.loudness_range,
                true_peak_db=result.true_peak_db,
                replaygain_db=result.replaygain_db,
                intro_end_ms=result.intro_end_ms,
                outro_start_ms=result.outro_start_ms,
                hook_start_ms=result.hook_start_ms,
                energy=result.energy,
                danceability=result.danceability,
                analyzed_at=int(time.time()),
            )
            db.mark_stage(self.conn, item.content_hash, dsp.STAGE, "ok",
                          stage_version=dsp.STAGE_VERSION)
        self.stats.analysed += 1

    def _stage_mood(self, item: scan.ScannedFile, track_id: int) -> None:
        """Derive mood from the signal.

        Runs after `dsp` because it reuses that stage's tempo, key and hook
        offset -- mode is the strongest single cue for valence, and analysing
        from the hook avoids letting an unrepresentative intro define the track.
        """
        if not self._should_run(item.content_hash, mood.STAGE, mood.STAGE_VERSION):
            return

        with self._db_lock:
            row = self.conn.execute(
                "SELECT bpm, key_camelot, hook_start_ms FROM tracks WHERE id = ?", (track_id,)
            ).fetchone()

        try:
            result = mood.analyse(
                self.cfg, item.path,
                bpm=row["bpm"] if row else None,
                key_camelot=row["key_camelot"] if row else None,
                hook_start_ms=(row["hook_start_ms"] or 0) if row else 0,
            )
        except (ff.FFmpegError, ValueError) as exc:
            self._mark(item.content_hash, mood.STAGE, "failed", mood.STAGE_VERSION, str(exc)[:500])
            raise

        with self._db_lock, db.transaction(self.conn):
            db.update_track(
                self.conn, track_id,
                valence=result.valence,
                arousal=result.arousal,
                approachability=result.acousticness,
                engagement=result.tension,
                is_instrumental=1 if result.instrumentalness > 0.62 else 0,
            )
            db.replace_tags(
                self.conn, track_id, "mood",
                [(name, "mood", confidence) for name, confidence in result.tags],
            )
            db.mark_stage(self.conn, item.content_hash, mood.STAGE, "ok",
                          stage_version=mood.STAGE_VERSION)
        self.stats.moods += 1

    def _stage_transcode(self, item: scan.ScannedFile, track_id: int) -> None:
        """Produce the Opus tier the phone will actually play."""
        if not self._should_run(item.content_hash, transcode.STAGE, transcode.STAGE_VERSION):
            return

        with self._db_lock:
            row = self.conn.execute(
                "SELECT title, artist, album, replaygain_db, intro_end_ms, outro_start_ms "
                "FROM tracks WHERE id = ?", (track_id,)
            ).fetchone()

        try:
            dst, loudness = transcode.to_opus(
                self.cfg, item.path, item.content_hash, row,
                force=transcode.STAGE in self.force,
            )
        except ff.FFmpegError as exc:
            self._mark(item.content_hash, transcode.STAGE, "failed",
                       transcode.STAGE_VERSION, str(exc)[:500])
            raise

        fields: dict = {"opus_path": self.cfg.rel(dst)}
        if loudness is not None:
            # Loudness measured on the delivered Opus supersedes the source
            # measurement -- see transcode.to_opus for why the peak differs.
            fields.update(
                loudness_lufs=loudness.integrated_lufs,
                loudness_range=loudness.loudness_range,
                true_peak_db=loudness.true_peak_db,
                replaygain_db=loudness.gain_for(
                    self.cfg.target_lufs, self.cfg.true_peak_ceiling_db
                ),
            )

        with self._db_lock, db.transaction(self.conn):
            db.update_track(self.conn, track_id, **fields)
            db.mark_stage(self.conn, item.content_hash, transcode.STAGE, "ok",
                          stage_version=transcode.STAGE_VERSION)
        self.stats.transcoded += 1
