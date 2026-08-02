"""Metadata candidates and how they are merged.

Four sources can describe a track, and they disagree constantly. Rather than
letting the last writer win, each source produces a `Candidate` with a
confidence, and `merge` resolves them field by field.

Merging per field rather than per source matters: a YouTube rip often has a
correct embedded title and a junk artist ("Various Artists", the uploader's
channel name), while the filename has the real composer. Taking whole records
from one winner would throw away the good half of each.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, fields
from typing import Iterable

# Values that appear in embedded tags but carry no information. Encoders and
# download tools insert these constantly.
_JUNK_VALUES = frozenset({
    "", "unknown", "unknown artist", "unknown album", "unknown title",
    "various", "various artists", "va", "untitled", "track", "audio track",
    "no artist", "none", "null", "n/a", "na", "-", "--", "...",
    "youtube", "youtube audio library", "soundcloud", "topic",
    "downloaded", "free download", "mp3", "song", "songs", "music",
})

# Channel-name artifacts: "Foo - Topic" is YouTube's auto-generated artist.
_TOPIC_SUFFIX = re.compile(r"\s*-\s*topic\s*$", re.IGNORECASE)
_VEVO_SUFFIX = re.compile(r"vevo\s*$", re.IGNORECASE)

FIELDS = ("title", "artist", "album", "album_artist", "composer",
          "year", "track_no", "language", "mbid")


@dataclass
class Candidate:
    """One source's opinion about a track."""

    source: str                       # musicbrainz|deezer|itunes|embedded|filename
    confidence: float                 # 0..1, how much this source is trusted here
    title: str | None = None
    artist: str | None = None
    album: str | None = None
    album_artist: str | None = None
    composer: str | None = None
    year: int | None = None
    track_no: int | None = None
    language: str | None = None
    mbid: str | None = None
    tags: list[tuple[str, str, float]] = None  # (tag, kind, confidence)

    def __post_init__(self) -> None:
        if self.tags is None:
            self.tags = []
        for name in FIELDS:
            setattr(self, name, clean(getattr(self, name)))


def clean(value):
    """Normalise a raw tag value, returning None if it carries no information."""
    if value is None:
        return None
    if isinstance(value, int):
        return value or None
    if not isinstance(value, str):
        return value

    text = value.replace("\x00", "").strip()
    text = _TOPIC_SUFFIX.sub("", text)
    text = _VEVO_SUFFIX.sub("", text).strip()
    text = re.sub(r"\s+", " ", text)

    if not text or text.casefold() in _JUNK_VALUES:
        return None
    # A value that is only punctuation or only digits-as-a-name is noise.
    if not any(c.isalnum() for c in text):
        return None
    return text


@dataclass
class Resolved:
    """The merged result, plus a record of which source won each field."""

    title: str | None = None
    artist: str | None = None
    album: str | None = None
    album_artist: str | None = None
    composer: str | None = None
    year: int | None = None
    track_no: int | None = None
    language: str | None = None
    mbid: str | None = None
    provenance: dict[str, str] = None
    confidence: float = 0.0

    def __post_init__(self) -> None:
        if self.provenance is None:
            self.provenance = {}

    @property
    def primary_source(self) -> str | None:
        """Whichever source supplied the title -- what we record as meta_source."""
        return self.provenance.get("title")


def merge(candidates: Iterable[Candidate]) -> Resolved:
    """Resolve competing candidates field by field, highest confidence wins.

    Ties break toward the candidate listed first, so callers control precedence
    among equally-confident sources by ordering them.
    """
    ranked = sorted(
        [c for c in candidates if c is not None],
        key=lambda c: c.confidence,
        reverse=True,
    )

    resolved = Resolved()
    for name in FIELDS:
        for cand in ranked:
            value = getattr(cand, name, None)
            if value is not None:
                setattr(resolved, name, value)
                resolved.provenance[name] = cand.source
                break

    # Overall confidence is that of whichever source supplied the title, since
    # a wrong title makes every other field useless for lookup or display.
    if (winner := resolved.primary_source):
        resolved.confidence = next(
            (c.confidence for c in ranked if c.source == winner), 0.0
        )

    # An album artist that merely repeats the artist adds nothing.
    if resolved.album_artist and resolved.album_artist == resolved.artist:
        resolved.album_artist = None

    return resolved


def to_track_fields(resolved: Resolved) -> dict:
    """Project a Resolved onto the writable columns of `tracks`."""
    out = {
        name: getattr(resolved, name)
        for name in FIELDS
        if getattr(resolved, name) is not None
    }
    if resolved.primary_source:
        out["meta_source"] = resolved.primary_source
    out["meta_confidence"] = round(resolved.confidence, 3)
    return out


def collect_tags(candidates: Iterable[Candidate]) -> dict[str, list[tuple[str, str, float]]]:
    """Group tag triples by source, for `db.replace_tags`."""
    grouped: dict[str, list[tuple[str, str, float]]] = {}
    for cand in candidates:
        if cand and cand.tags:
            grouped.setdefault(cand.source, []).extend(cand.tags)
    return grouped
