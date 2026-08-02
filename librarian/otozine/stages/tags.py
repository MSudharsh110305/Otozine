"""Stage 3 -- read metadata already embedded in the file.

Cheap, offline, and surprisingly good for properly-ripped albums. Worthless for
YouTube rips, which is why the confidence returned here is *computed* from how
complete and plausible the tags are rather than being a constant.
"""

from __future__ import annotations

import re
from pathlib import Path

from ..metadata import Candidate, clean

STAGE = "tags"
STAGE_VERSION = 1

# mutagen exposes different key names per container; this maps them all onto our
# field names. Keys are checked in order, first hit wins.
_KEY_MAP: dict[str, tuple[str, ...]] = {
    "title":        ("title", "TIT2", "\xa9nam"),
    "artist":       ("artist", "TPE1", "\xa9ART"),
    "album_artist": ("albumartist", "album_artist", "TPE2", "aART"),
    "album":        ("album", "TALB", "\xa9alb"),
    "composer":     ("composer", "TCOM", "\xa9wrt"),
    "date":         ("date", "year", "originalyear", "TDRC", "TYER", "\xa9day"),
    "track":        ("tracknumber", "TRCK", "trkn"),
    "genre":        ("genre", "TCON", "\xa9gen"),
    "language":     ("language", "TLAN"),
    "mbid":         ("musicbrainz_trackid", "musicbrainz_releasetrackid",
                     "MusicBrainz Track Id", "UFID:http://musicbrainz.org"),
}

_YEAR_IN_DATE = re.compile(r"(19[0-9]{2}|20[0-9]{2})")
_TRACK_NUM = re.compile(r"^(\d{1,3})")

# ISO-639 codes and names we care about, normalised to our two-letter form.
_LANGUAGE_MAP = {
    "tam": "ta", "ta": "ta", "tamil": "ta",
    "eng": "en", "en": "en", "english": "en",
    "hin": "hi", "hi": "hi", "hindi": "hi",
    "tel": "te", "te": "te", "telugu": "te",
    "mal": "ml", "ml": "ml", "malayalam": "ml",
    "kan": "kn", "kn": "kn", "kannada": "kn",
}


def read(path: Path) -> Candidate:
    """Extract embedded tags. Never raises -- a broken header yields an empty candidate."""
    try:
        import mutagen
        audio = mutagen.File(path, easy=False)
    except Exception:
        return Candidate(source="embedded", confidence=0.0)

    if audio is None or not getattr(audio, "tags", None):
        return Candidate(source="embedded", confidence=0.0)

    raw = _flatten(audio.tags)

    title = _first(raw, _KEY_MAP["title"])
    artist = _first(raw, _KEY_MAP["artist"])
    album = _first(raw, _KEY_MAP["album"])
    album_artist = _first(raw, _KEY_MAP["album_artist"])
    composer = _first(raw, _KEY_MAP["composer"])

    year = None
    if (date := _first(raw, _KEY_MAP["date"])) and (m := _YEAR_IN_DATE.search(date)):
        year = int(m.group(1))

    track_no = None
    if (track := _first(raw, _KEY_MAP["track"])) and (m := _TRACK_NUM.match(str(track).strip())):
        value = int(m.group(1))
        track_no = value if 1 <= value <= 999 else None

    language = None
    if (lang := _first(raw, _KEY_MAP["language"])):
        language = _LANGUAGE_MAP.get(lang.strip().lower())

    tags: list[tuple[str, str, float]] = []
    if (genre := _first(raw, _KEY_MAP["genre"])):
        # Genre fields are frequently multi-valued: "Rock; Indie / Alternative".
        for part in re.split(r"[;,/|]", genre):
            if (value := clean(part)):
                tags.append((value, "genre", 0.6))

    candidate = Candidate(
        source="embedded",
        confidence=0.0,
        title=title, artist=artist, album=album, album_artist=album_artist,
        composer=composer, year=year, track_no=track_no, language=language,
        mbid=_first(raw, _KEY_MAP["mbid"]),
        tags=tags,
    )
    candidate.confidence = _score(candidate)
    return candidate


def _flatten(tags) -> dict[str, str]:
    """Collapse mutagen's per-format tag object into plain lowercase strings."""
    out: dict[str, str] = {}
    try:
        items = tags.items()
    except Exception:
        return out

    for key, value in items:
        if isinstance(value, (list, tuple)):
            value = value[0] if value else None
        if value is None:
            continue
        # ID3 frames and MP4 atoms stringify to their text content.
        text = getattr(value, "text", value)
        if isinstance(text, (list, tuple)):
            text = text[0] if text else None
        if text is None:
            continue
        rendered = str(text).strip()
        if rendered:
            out[str(key).lower()] = rendered
    return out


def _first(raw: dict[str, str], keys: tuple[str, ...]) -> str | None:
    for key in keys:
        if (value := raw.get(key.lower())) is not None:
            if (cleaned := clean(value)) is not None:
                return cleaned
    return None


def _score(candidate: Candidate) -> float:
    """Confidence based on how complete and plausible the tag set is.

    A file carrying a MusicBrainz id was tagged by a real tagger and is highly
    trustworthy. A file with only a title is probably a rip whose 'title' is the
    whole YouTube video name, so it barely outranks the filename parser.
    """
    if not candidate.title:
        return 0.0

    score = 0.30
    if candidate.mbid:
        score += 0.45              # tagged by Picard or similar: trust it
    if candidate.artist:
        score += 0.15
    if candidate.album:
        score += 0.08
    if candidate.year:
        score += 0.04
    if candidate.track_no:
        score += 0.03

    # A "title" longer than a plausible song title is a video description.
    if len(candidate.title) > 80:
        score *= 0.4
    # Artist equal to title is a classic single-field rip artifact.
    if candidate.artist and candidate.artist.casefold() == candidate.title.casefold():
        score *= 0.5

    return round(min(score, 0.95), 3)
