"""Cross-language schema contract test.

`library.db` is written by Python and read by Kotlin. Nothing in either
toolchain notices when those two drift -- the Kotlin side just starts throwing
`IllegalArgumentException: column 'foo' does not exist` at runtime, on a phone,
where it is most annoying to debug.

So we extract the actual SQL and column names out of the Kotlin source and run
them against a real database built by the pipeline. If someone renames a column
in schema.sql without updating LibraryRepository.kt, this fails on the PC.
"""

from __future__ import annotations

import re
import sqlite3
from pathlib import Path

import pytest

from otozine import db
from otozine.config import SCHEMA_VERSION
from otozine.pipeline import Pipeline

from conftest import requires_ffmpeg

_ANDROID_SRC = (
    Path(__file__).resolve().parents[2]
    / "android/app/src/main/java/net/otozine/player"
)
_REPOSITORY_KT = _ANDROID_SRC / "library/LibraryRepository.kt"

pytestmark = pytest.mark.skipif(
    not _REPOSITORY_KT.is_file(), reason="android sources not present"
)


def _kotlin_source() -> str:
    return _REPOSITORY_KT.read_text(encoding="utf-8")


def _extract_track_query() -> str:
    """Pull the TRACK_QUERY string literal out of the Kotlin source."""
    match = re.search(
        r'private const val TRACK_QUERY\s*=\s*"""(.*?)"""',
        _kotlin_source(),
        re.DOTALL,
    )
    assert match, "could not find TRACK_QUERY in LibraryRepository.kt"
    return match.group(1)


def _extract_column_names() -> set[str]:
    """Pull every getColumnIndexOrThrow("...") name out of the Kotlin source."""
    return set(re.findall(r'getColumnIndexOrThrow\("([^"]+)"\)', _kotlin_source()))


@pytest.fixture
def library(drive, source_music):
    with Pipeline(drive, offline=True) as pipeline:
        pipeline.ingest([source_music])
    conn = db.connect(drive.db_path, read_only=True)
    yield conn
    conn.close()


@requires_ffmpeg
def test_kotlin_track_query_runs(library):
    """The player's main query must execute against a real library."""
    query = _extract_track_query()
    try:
        rows = library.execute(query, ("100",)).fetchall()
    except sqlite3.Error as exc:
        pytest.fail(f"LibraryRepository.TRACK_QUERY is invalid against schema: {exc}")

    assert rows, "query returned nothing; the player would show an empty library"


@requires_ffmpeg
def test_kotlin_reads_only_columns_that_exist(library):
    """Every column the Kotlin cursor asks for must be in the query's output."""
    query = _extract_track_query()
    cursor = library.execute(query, ("1",))
    available = {d[0] for d in cursor.description}

    requested = _extract_column_names()
    assert requested, "found no getColumnIndexOrThrow calls -- did the reader change?"

    missing = requested - available
    assert not missing, (
        f"LibraryRepository reads columns the query does not select: {sorted(missing)}"
    )


_BUILDER_KT = _ANDROID_SRC / "library/LocalLibraryBuilder.kt"


@pytest.mark.skipif(not _BUILDER_KT.is_file(), reason="LocalLibraryBuilder not present")
def test_app_built_schema_satisfies_the_readers_query():
    """The app can build its own library.db when pointed at a plain folder.

    That schema is hand-written in Kotlin rather than shared with schema.sql, so
    nothing stops it drifting from what LibraryRepository selects -- and the
    failure would be a crash on the phone, at the moment a first-time user
    imports their music. Check every column the reader asks for is declared.
    """
    builder = _BUILDER_KT.read_text(encoding="utf-8")

    match = re.search(r"CREATE TABLE tracks \((.*?)\n\s*\)", builder, re.DOTALL)
    assert match, "could not find the tracks CREATE TABLE in LocalLibraryBuilder.kt"

    declared = {
        m.group(1)
        for m in re.finditer(r"^\s{16}([a-z_]+)\s+\w", match.group(1), re.MULTILINE)
    }
    assert declared, "parsed no column names -- has the formatting changed?"

    # Columns the player's query selects, taken from the query itself.
    selected = set(
        re.findall(
            r"[\s,]([a-z_]+)(?=[,\s])",
            _extract_track_query().split("FROM")[0].replace("SELECT", ""),
        )
    )
    required = {c for c in selected if c not in {"as", "from"}}

    missing = required - declared
    assert not missing, (
        f"LocalLibraryBuilder's schema is missing columns the reader selects: "
        f"{sorted(missing)}"
    )

    # And the filter columns, which are not in the SELECT list.
    for column in ("missing", "opus_path", "track_no", "added_at"):
        assert column in declared, f"LocalLibraryBuilder must declare {column}"


def test_schema_version_constants_agree():
    """A silent version skew would let the player open an incompatible DB."""
    match = re.search(r"const val SCHEMA_VERSION\s*=\s*(\d+)", _kotlin_source())
    assert match, "SCHEMA_VERSION not found in LibraryRepository.kt"

    kotlin_version = int(match.group(1))
    assert kotlin_version == SCHEMA_VERSION, (
        f"Kotlin expects schema v{kotlin_version} but Python writes "
        f"v{SCHEMA_VERSION}; update both together"
    )


@requires_ffmpeg
def test_playable_tracks_have_everything_the_player_needs(library):
    """Guard the fields the player would crash or misbehave without."""
    query = _extract_track_query()
    rows = library.execute(query, ("100",)).fetchall()

    for row in rows:
        assert row["opus_path"], f"track {row['id']} has no audio to play"
        assert row["duration_ms"] and row["duration_ms"] > 0, (
            f"track {row['id']} has no duration; the seek bar would be broken"
        )
        # replaygain_db is read with a 0f default on the Kotlin side, but a NULL
        # here means the loudness stage silently did not run.
        assert row["replaygain_db"] is not None, (
            f"track {row['id']} has no normalisation gain"
        )
