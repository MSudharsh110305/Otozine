"""End-to-end ingest tests.

These assert the two properties the whole design rests on -- idempotence and
resumability -- plus the loudness contract, which is the single biggest
day-to-day quality difference from a stock player.
"""

from __future__ import annotations

import pytest

from otozine import db
from otozine.pipeline import Pipeline
from otozine.util import ffmpeg as ff

from conftest import EXPECTED_BPM, EXPECTED_CAMELOT, FIXTURES, requires_ffmpeg

pytestmark = requires_ffmpeg


@pytest.fixture
def ingested(drive, source_music):
    """A drive with the fixture library fully ingested."""
    with Pipeline(drive, offline=True) as pipeline:
        stats = pipeline.ingest([source_music])
    return drive, stats


# ------------------------------------------------------------------ dedupe

def test_duplicate_is_collapsed_to_one_track(ingested):
    drive, stats = ingested
    assert stats.scanned == len(FIXTURES)          # 4 files on disk, 3 unique
    assert stats.duplicates == 1
    assert stats.failed == 0


def test_dedupe_keeps_the_most_informative_filename(ingested):
    """Both copies are byte-identical; the richer name must win."""
    drive, _ = ingested
    conn = db.connect(drive.db_path, read_only=True)
    try:
        row = conn.execute(
            "SELECT artist, album, year FROM tracks WHERE title = 'Vaathi Coming'"
        ).fetchone()
    finally:
        conn.close()

    assert row is not None
    # These come only from the '[Isaimini.com] ... - Master - Anirudh ...' name.
    assert row["artist"] == "Anirudh Ravichander"
    assert row["album"] == "Master"
    assert row["year"] == 2020


# ------------------------------------------------------- idempotence/resume

def test_second_run_does_no_work(ingested, source_music):
    """The property that makes weekly re-syncs cheap."""
    drive, first = ingested
    with Pipeline(drive, offline=True) as pipeline:
        second = pipeline.ingest([source_music])

    assert first.added == len(FIXTURES)
    assert second.added == 0
    assert second.analysed == 0
    assert second.transcoded == 0
    assert second.skipped == len(FIXTURES)
    assert second.failed == 0


def test_interrupted_run_resumes(drive, source_music):
    """Simulate a yanked drive: delete the outputs of one stage, re-run.

    Only the missing stage should be redone -- not the whole library.
    """
    with Pipeline(drive, offline=True) as pipeline:
        pipeline.ingest([source_music])

    conn = db.connect(drive.db_path)
    try:
        with db.transaction(conn):
            conn.execute("DELETE FROM ingest_state WHERE stage = 'transcode'")
    finally:
        conn.close()
    for opus in drive.opus_dir.rglob("*.opus"):
        opus.unlink()

    with Pipeline(drive, offline=True) as pipeline:
        stats = pipeline.ingest([source_music])

    assert stats.transcoded == len(FIXTURES)   # redone
    assert stats.analysed == 0                 # untouched
    assert stats.added == 0
    assert stats.failed == 0


def test_force_reruns_only_the_named_stage(drive, source_music):
    with Pipeline(drive, offline=True) as pipeline:
        pipeline.ingest([source_music])
    with Pipeline(drive, offline=True, force={"dsp"}) as pipeline:
        stats = pipeline.ingest([source_music])

    assert stats.analysed == len(FIXTURES)
    assert stats.transcoded == 0


# ---------------------------------------------------------------- loudness

def test_every_track_normalises_to_target(ingested):
    """The loudness contract: measured + stored gain lands within 1 LU of target.

    Fixtures span a 17 dB range on purpose, which is typical of a downloaded
    library. This is what stops one track deafening you after another.
    """
    drive, _ = ingested
    conn = db.connect(drive.db_path, read_only=True)
    try:
        rows = conn.execute(
            "SELECT id, title, loudness_lufs, replaygain_db, true_peak_db FROM tracks"
        ).fetchall()
    finally:
        conn.close()

    assert len(rows) == len(FIXTURES)
    for row in rows:
        corrected = row["loudness_lufs"] + row["replaygain_db"]
        peak_after = row["true_peak_db"] + row["replaygain_db"]

        # Landing short is correct for a peak-limited track: a master already
        # at the ceiling cannot be boosted without clipping, and quiet beats
        # distorted. Only an unexplained miss is a failure.
        peak_limited = peak_after >= drive.true_peak_ceiling_db - 0.15
        within_target = abs(corrected - drive.target_lufs) <= 1.0
        assert within_target or (corrected < drive.target_lufs and peak_limited), (
            f"{row['title']}: {corrected:.1f} LUFS, expected {drive.target_lufs} "
            f"(peak after gain {peak_after:+.2f} dBTP)"
        )


def test_gain_never_pushes_a_track_into_clipping(ingested):
    drive, _ = ingested
    conn = db.connect(drive.db_path, read_only=True)
    try:
        rows = conn.execute(
            "SELECT title, true_peak_db, replaygain_db FROM tracks"
        ).fetchall()
    finally:
        conn.close()

    for row in rows:
        peak_after = row["true_peak_db"] + row["replaygain_db"]
        assert peak_after <= drive.true_peak_ceiling_db + 0.15, (
            f"{row['title']} would clip at {peak_after:+.2f} dBTP"
        )


def test_delivered_opus_never_clips_after_gain(ingested):
    """The real contract: the file that plays must respect the peak ceiling.

    Background: loudness used to be measured on the source MP3 while the Opus
    was what shipped. Lossy encoding moves the true peak -- a real master
    clipping at +1.37 dBTP came back at +2.20 dBTP after encoding -- so a
    ceiling computed from the source let the delivered file clip by 0.8 dB.

    Honest limitation: on these synthetic fixtures the encoder shifts the peak
    by only ~0.03 dB, because a three-tone chord reconstructs almost exactly.
    Dense real music shifts far more. So this test cannot catch a
    source-vs-delivered mix-up by the size of the discrepancy alone; what it
    does enforce is the invariant that actually matters to a listener, plus the
    mechanism check that the stored peak came from the Opus.
    """
    drive, _ = ingested
    conn = db.connect(drive.db_path, read_only=True)
    try:
        rows = conn.execute(
            "SELECT title, opus_path, true_peak_db, loudness_lufs, replaygain_db "
            "FROM tracks WHERE opus_path IS NOT NULL"
        ).fetchall()
    finally:
        conn.close()

    assert rows

    # Guard against this test quietly becoming vacuous: the peak-limiting path
    # is only exercised if at least one fixture is genuinely above full scale.
    # A limiter on the hot fixture once held it at -3 dBTP, which silently
    # removed the only case that mattered.
    peaks = [ff.measure_loudness(drive.ffmpeg, drive.abs(r["opus_path"])).true_peak_db
             for r in rows]
    assert max(peaks) > 0.0, (
        f"no fixture clips (max peak {max(peaks):+.2f} dBTP) -- the peak-limiting "
        f"path is untested; check the '+9dB' fixture is not being limited"
    )

    for row in rows:
        actual = ff.measure_loudness(drive.ffmpeg, drive.abs(row["opus_path"]))

        assert abs(row["true_peak_db"] - actual.true_peak_db) <= 0.1, (
            f"{row['title']}: stored peak {row['true_peak_db']:+.2f} dBTP does not "
            f"match the Opus ({actual.true_peak_db:+.2f}) -- measured on the source?"
        )

        delivered_peak = actual.true_peak_db + row["replaygain_db"]
        assert delivered_peak <= drive.true_peak_ceiling_db + 0.15, (
            f"{row['title']}: the delivered file clips at {delivered_peak:+.2f} dBTP"
        )


def test_force_transcode_actually_re_encodes(drive, source_music):
    """--force must redo the work, not just re-run the bookkeeping.

    to_opus used to short-circuit on an existing output file even when forced,
    so a forced re-run reported success while silently keeping the old gain.
    """
    with Pipeline(drive, offline=True) as pipeline:
        pipeline.ingest([source_music])

    opus_files = list(drive.opus_dir.rglob("*.opus"))
    assert opus_files
    before = {f: f.stat().st_mtime_ns for f in opus_files}

    with Pipeline(drive, offline=True, force={"transcode"}) as pipeline:
        stats = pipeline.ingest([source_music])

    assert stats.transcoded == len(FIXTURES)
    after = {f: f.stat().st_mtime_ns for f in opus_files}
    assert after != before, "forced transcode left the existing files untouched"


def test_r128_tag_written_to_opus(ingested):
    """Other players should see the gain too, even though our DB is the authority."""
    drive, _ = ingested
    conn = db.connect(drive.db_path, read_only=True)
    try:
        rows = conn.execute(
            "SELECT opus_path, replaygain_db FROM tracks WHERE opus_path IS NOT NULL"
        ).fetchall()
    finally:
        conn.close()

    assert rows
    for row in rows:
        probe = ff.probe(drive.ffprobe, drive.abs(row["opus_path"]))
        tag = probe.tags.get("r128_track_gain")
        assert tag is not None, "R128_TRACK_GAIN missing"
        # Q7.8 fixed point: dB * 256.
        assert abs(int(tag) / 256.0 - row["replaygain_db"]) < 0.05


# --------------------------------------------------------------------- dsp

def test_key_detection_matches_the_synthesised_chord(ingested):
    """Every fixture is a known triad, so the Camelot code is known exactly."""
    drive, _ = ingested
    conn = db.connect(drive.db_path, read_only=True)
    try:
        rows = conn.execute("SELECT title, key_camelot FROM tracks").fetchall()
    finally:
        conn.close()

    assert len(rows) == len(EXPECTED_CAMELOT)
    for row in rows:
        expected = EXPECTED_CAMELOT[row["title"]]
        assert row["key_camelot"] == expected, (
            f"{row['title']}: got {row['key_camelot']}, expected {expected}"
        )


def test_bpm_is_detected_and_plausible(ingested):
    """Within 15% of ground truth.

    Not tighter: the fixtures use a smooth sinusoidal amplitude envelope rather
    than percussive onsets, which is harder to track than real drums.
    """
    drive, _ = ingested
    conn = db.connect(drive.db_path, read_only=True)
    try:
        rows = conn.execute("SELECT title, bpm FROM tracks").fetchall()
    finally:
        conn.close()

    assert len(rows) == len(EXPECTED_BPM)
    for row in rows:
        assert row["bpm"] is not None, f"{row['title']}: no tempo detected"
        target = EXPECTED_BPM[row["title"]]
        assert abs(row["bpm"] - target) / target <= 0.15, (
            f"{row['title']}: {row['bpm']} bpm, expected ~{target}"
        )


def test_leading_silence_is_measured(ingested):
    """Vaathi Coming has 1.5 s of lead-in; Ennai Vidaadhe has none."""
    drive, _ = ingested
    conn = db.connect(drive.db_path, read_only=True)
    try:
        rows = {r["title"]: r for r in conn.execute(
            "SELECT title, intro_end_ms, outro_start_ms, duration_ms FROM tracks"
        )}
    finally:
        conn.close()

    assert 1300 <= rows["Vaathi Coming"]["intro_end_ms"] <= 1700
    assert rows["Ennai Vidaadhe"]["intro_end_ms"] == 0
    # The 3 s fade starting at 26 s must be caught as the outro.
    for row in rows.values():
        assert row["outro_start_ms"] < row["duration_ms"]


# ------------------------------------------------------------ user overrides

def test_user_override_survives_reingest(drive, source_music):
    """A hand correction must never be clobbered by the pipeline."""
    with Pipeline(drive, offline=True) as pipeline:
        pipeline.ingest([source_music])

    conn = db.connect(drive.db_path)
    try:
        track_id = conn.execute(
            "SELECT id FROM tracks WHERE title = 'Vaathi Coming'"
        ).fetchone()["id"]
        with db.transaction(conn):
            db.set_user_override(conn, track_id, "artist", "Anirudh")
    finally:
        conn.close()

    with Pipeline(drive, offline=True, force={"metadata"}) as pipeline:
        pipeline.ingest([source_music])

    conn = db.connect(drive.db_path, read_only=True)
    try:
        artist = conn.execute(
            "SELECT artist FROM tracks WHERE id = ?", (track_id,)
        ).fetchone()["artist"]
    finally:
        conn.close()

    assert artist == "Anirudh", "re-ingest overwrote a user correction"


def test_clearing_an_override_returns_the_field_to_the_pipeline(drive, source_music):
    with Pipeline(drive, offline=True) as pipeline:
        pipeline.ingest([source_music])

    conn = db.connect(drive.db_path)
    try:
        track_id = conn.execute(
            "SELECT id FROM tracks WHERE title = 'Vaathi Coming'"
        ).fetchone()["id"]
        with db.transaction(conn):
            db.set_user_override(conn, track_id, "artist", "Anirudh")
        with db.transaction(conn):
            db.set_user_override(conn, track_id, "artist", None)   # clear it
    finally:
        conn.close()

    with Pipeline(drive, offline=True, force={"metadata"}) as pipeline:
        pipeline.ingest([source_music])

    conn = db.connect(drive.db_path, read_only=True)
    try:
        artist = conn.execute(
            "SELECT artist FROM tracks WHERE id = ?", (track_id,)
        ).fetchone()["artist"]
    finally:
        conn.close()

    assert artist == "Anirudh Ravichander"


# ------------------------------------------------------------- title only

def test_title_only_clears_unreliable_fields(title_only_drive, source_music):
    """The shipping default keeps the song name and drops the guesses.

    Artist and album are inferred from filename shape and online search. On a
    library of rips they are wrong often enough that a confidently incorrect
    artist misleads more than a blank one informs -- so they are cleared rather
    than displayed. The title still has to survive intact.
    """
    with Pipeline(title_only_drive, offline=True) as pipeline:
        pipeline.ingest([source_music])

    conn = db.connect(title_only_drive.db_path, read_only=True)
    try:
        rows = conn.execute(
            "SELECT title, artist, album, composer, track_no FROM tracks"
        ).fetchall()
    finally:
        conn.close()

    assert len(rows) == len(FIXTURES)
    titles = {r["title"] for r in rows}
    assert titles == {f.title for f in FIXTURES}, "titles must survive"

    for row in rows:
        for column in ("artist", "album", "composer", "track_no"):
            assert row[column] is None, (
                f"{row['title']}: {column} should be cleared by title_only"
            )


def test_title_only_clears_fields_written_by_an_earlier_run(drive, source_music):
    """Re-running with title_only on must actually remove what was there.

    Omitting the columns from the update leaves the previous values in place, so
    the setting would silently appear to do nothing on an existing library.
    """
    with Pipeline(drive, offline=True) as pipeline:
        pipeline.ingest([source_music])

    conn = db.connect(drive.db_path, read_only=True)
    try:
        before = conn.execute(
            "SELECT COUNT(*) AS n FROM tracks WHERE artist IS NOT NULL"
        ).fetchone()["n"]
    finally:
        conn.close()
    assert before > 0, "fixture should have recovered some artists first"

    drive.title_only = True
    with Pipeline(drive, offline=True, force={"metadata"}) as pipeline:
        pipeline.ingest([source_music])

    conn = db.connect(drive.db_path, read_only=True)
    try:
        after = conn.execute(
            "SELECT COUNT(*) AS n FROM tracks WHERE artist IS NOT NULL"
        ).fetchone()["n"]
    finally:
        conn.close()
    assert after == 0, "re-running with title_only left stale artists behind"
