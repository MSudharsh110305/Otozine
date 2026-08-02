"""Stage 4 -- recover metadata from filenames and folder structure.

For a large part of a Tamil library this is not a fallback, it is the primary
source of truth: AcoustID/MusicBrainz simply do not have the recordings, and the
file arrives named something like

    [Isaimini.com] Vaathi Coming - Master - Anirudh Ravichander (2020) 320kbps.mp3

The parser strips the noise, splits into segments, then *classifies* each
segment using the alias table rather than assuming a fixed field order. Knowing
that "Anirudh Ravichander" is a music director and "Master" is not is what
turns a positional guess into a real assignment.
"""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

STAGE = "nameparse"
STAGE_VERSION = 1

_DATA_FILE = Path(__file__).resolve().parents[1] / "data" / "tamil_aliases.json"

# Tamil script block (U+0B80..U+0BFF). A single character in this range is
# decisive evidence of language in a way no transliteration heuristic can match.
_TAMIL_SCRIPT = re.compile(r"[஀-௿]")

# Bare domains and the TLD fragments left behind once a site name is stripped.
_DOMAIN = re.compile(
    r"\b(?:www\.)?[a-z0-9][a-z0-9-]*\.(?:com|net|in|org|co|me|info|cc|xyz|biz|us|tv)\b",
    re.IGNORECASE,
)
_ORPHAN_TLD = re.compile(
    r"(?:^|\s|[\[\](){}])\.?(?:com|net|org|info|xyz)\b", re.IGNORECASE
)

_YEAR = re.compile(r"\b(19[3-9]\d|20[0-4]\d)\b")
_TRACK_NO = re.compile(r"^\s*(\d{1,3})\s*[-.)_]\s*")
_FEAT = re.compile(r"\b(?:feat|ft|featuring|with)\.?\s+(.+?)$", re.IGNORECASE)
_FROM_ALBUM = re.compile(r"\bfrom\s+[\"'“]?(.+?)[\"'”]?$", re.IGNORECASE)
_BRACKETS = re.compile(r"[\(\[\{]([^\)\]\}]*)[\)\]\}]")
_NON_ALNUM = re.compile(r"[^a-z0-9\s]")
_WS = re.compile(r"\s+")


@dataclass
class ParsedName:
    """What we managed to recover from the path alone."""

    title: str | None = None
    artist: str | None = None
    album: str | None = None            # for Tamil film music this is the movie
    composer: str | None = None
    year: int | None = None
    track_no: int | None = None
    language_hint: str | None = None    # 'ta' | 'en' | None
    confidence: float = 0.0
    roles: dict[str, str] = field(default_factory=dict)   # segment -> role, for debugging


# ------------------------------------------------------------------ tables

@lru_cache(maxsize=1)
def _tables() -> dict:
    """Load and invert the alias table once per process."""
    raw = json.loads(_DATA_FILE.read_text(encoding="utf-8"))

    def invert(section: str) -> dict[str, str]:
        out: dict[str, str] = {}
        for canonical, aliases in raw.get(section, {}).items():
            out[_key(canonical)] = canonical
            for alias in aliases:
                out[_key(alias)] = canonical
        return out

    return {
        "directors": invert("music_directors"),
        "singers": invert("singers"),
        "actors": {_key(a) for a in raw.get("actors", [])},
        "film_directors": {_key(d) for d in raw.get("directors", [])},
        "studios": {_key(s) for s in raw.get("studios", [])},
        "site_spam": sorted(raw.get("site_spam", []), key=len, reverse=True),
        "quality_junk": sorted(raw.get("quality_junk", []), key=len, reverse=True),
        "video_junk": sorted(raw.get("video_junk", []), key=len, reverse=True),
        "split_junk": sorted(raw.get("split_junk", []), key=len, reverse=True),
        "trailing_junk": sorted(raw.get("trailing_junk", []), key=len, reverse=True),
        # Phrases that act as delimiters must survive every deletion pass, or
        # the structure they mark is gone before the splitter ever runs.
        "protected": {_key(p) for p in raw.get("split_junk", [])},
        "tamil_markers": {_key(m) for m in raw.get("tamil_markers", [])},
    }


def _flexible(phrase: str) -> str:
    """Regex matching a normalised phrase against real-world punctuation.

    'a r rahman' has to match 'A.R. Rahman', 'A. R. Rahman' and 'AR Rahman',
    so tokens are joined by an optional run of separators rather than a space.
    """
    tokens = [re.escape(t) for t in phrase.split()]
    return r"\b" + r"[\s.\-_]*".join(tokens) + r"\b"


@lru_cache(maxsize=1)
def _entity_pattern() -> re.Pattern:
    """One alternation matching every known name, anywhere in a string.

    YouTube-rip filenames do not put names in their own delimited segment --
    'Video Song Kaththi Vijay Samantha Anirudh Ravichander' is one run of words.
    Whole-segment classification cannot see into that; a scan can.
    """
    tables = _tables()
    entries: list[tuple[str, str, str]] = []

    for alias, canonical in tables["directors"].items():
        entries.append((alias, "composer", canonical))
    for alias, canonical in tables["singers"].items():
        entries.append((alias, "singer", canonical))
    for alias in tables["actors"]:
        entries.append((alias, "cast", alias))
    for alias in tables["film_directors"]:
        entries.append((alias, "director", alias))
    for alias in tables["studios"]:
        entries.append((alias, "studio", alias))

    # Longest first so 'anirudh ravichander' wins over 'anirudh', and skip
    # very short aliases, which would fire on fragments of ordinary words.
    entries = [e for e in entries if len(e[0]) >= 4]
    entries.sort(key=lambda e: -len(e[0]))

    _ENTITY_LOOKUP.clear()
    parts = []
    for index, (alias, role, canonical) in enumerate(entries):
        group = f"e{index}"
        _ENTITY_LOOKUP[group] = (role, canonical)
        parts.append(f"(?P<{group}>{_flexible(alias)})")

    return re.compile("|".join(parts), re.IGNORECASE)


# group name -> (role, canonical), populated by _entity_pattern()
_ENTITY_LOOKUP: dict[str, tuple[str, str]] = {}


def _key(text: str) -> str:
    """Aggressive normalisation used only for alias lookup.

    Collapses 'A.R. Rahman', 'AR  Rahman' and 'a r rahman' to 'a r rahman'.
    """
    text = unicodedata.normalize("NFKD", text).casefold()
    text = _NON_ALNUM.sub(" ", text)
    return _WS.sub(" ", text).strip()


# ------------------------------------------------------------------ cleanup

def _denoise(text: str, *, keep_delimiters: bool = True) -> str:
    """Strip domains and junk phrases. Order matters.

    Domains go first: if 'isaimini' were removed before 'isaimini.com' were
    recognised as a domain, the orphaned '.com' would survive and end up as a
    title. Junk phrases are applied longest-first so 'official music video' is
    consumed whole rather than fragmented by 'video'.

    `keep_delimiters` preserves the phrases that `_segment` needs as split
    points. 'Video Song' sits between the song title and the film name, so
    deleting it here would erase the only structure the filename has.
    """
    tables = _tables()
    protected = tables["protected"] if keep_delimiters else set()

    text = _DOMAIN.sub(" ", text)
    for term in (*tables["site_spam"], *tables["video_junk"], *tables["quality_junk"]):
        if _key(term) in protected:
            continue
        text = re.sub(rf"(?<![a-z0-9]){re.escape(term)}(?![a-z0-9])", " ", text, flags=re.IGNORECASE)
    return _ORPHAN_TLD.sub(" ", text)


def _strip_junk(text: str) -> tuple[str, int | None]:
    """Remove site watermarks, quality markers and video decorations.

    Returns the cleaned text and any year found along the way. Bracketed groups
    are examined individually: a group that is purely junk is dropped, a group
    that holds a year contributes the year, and anything else is unwrapped and
    kept, because '(From Vikram)' and '(Unplugged)' are real metadata.
    """
    year: int | None = None

    def handle_bracket(match: re.Match) -> str:
        nonlocal year
        inner = match.group(1).strip()
        if not inner:
            return " "

        if (m := _YEAR.search(inner)) and len(_key(inner)) <= 6:
            year = int(m.group(1))
            return " "

        # Drop the group only if what survives denoising is negligible. Here we
        # deliberately denoise *without* protecting delimiters and also strip
        # trailing-junk words, so '(Official Video)' and '(Tamil)' both vanish
        # while '(From Vikram)' and '(Unplugged)' survive.
        residue = _denoise(inner, keep_delimiters=False)
        for term in _tables()["trailing_junk"]:
            residue = re.sub(rf"\b{re.escape(term)}\b", " ", residue, flags=re.IGNORECASE)
        if len(_WS.sub("", _key(residue))) <= 2:
            return " "

        return f" {inner} "

    text = _BRACKETS.sub(handle_bracket, text)

    if year is None and (m := _YEAR.search(text)):
        year = int(m.group(1))
        text = text[: m.start()] + " " + text[m.end():]

    text = _denoise(text)

    # A spaced underscore stands in for a pipe in YouTube-derived filenames and
    # is a real separator; an unspaced one is snake_case and is just a space.
    text = re.sub(r"\s+_+\s+", " - ", text)
    text = re.sub(r"_+", " ", text)

    text = re.sub(r"\s*[-–—|~]\s*$", "", text)
    text = re.sub(r"^\s*[-–—|~]\s*", "", text)
    # Collapse runs of separators left behind by removed segments.
    text = re.sub(r"(?:\s*[-–—|~]\s*){2,}", " - ", text)
    return _WS.sub(" ", text).strip(" -–—|~.,"), year


def _titlecase(text: str) -> str:
    """Title-case only when the input is clearly not already cased by a human.

    Short all-caps strings are left alone -- 'AM', 'IV', 'OK', 'ABBA' and 'MGMT'
    are titles and acronyms, not shouting. Four letters is the cut-off: it covers
    the common acronyms while still fixing 'VAATHI COMING'.
    """
    if not text:
        return text
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return text

    all_upper = all(c.isupper() for c in letters)
    if all_upper and len(letters) <= 4:
        return text

    # All-caps or all-lower filenames get fixed; mixed case is left alone.
    if all_upper or all(c.islower() for c in letters):
        return " ".join(w.capitalize() if w.islower() or w.isupper() else w for w in text.split())
    return text


# ------------------------------------------------------ entity scan & split

# Placeholder left where a removed entity was, so neighbouring words do not fuse.
_GAP = "\x00"
_JUNK_MARK = "\x01"
_DASH_MARK = "\x02"

_HANDLE = re.compile(r"@\s*([A-Za-z0-9_.]{3,})")
_SITE_PREFIX = re.compile(
    r"^\s*(?:www\.)?[a-z0-9-]+\.(?:com|cc|net|in|org|me|xyz|info)[\s_\-]+", re.IGNORECASE
)


def _strip_prefixes(stem: str) -> tuple[str, str | None]:
    """Remove a leading site watermark and pull out any @channel handle.

    The handle is worth keeping: for independent releases it is often the only
    artist credit present ('@SaiAbhyankkar - Pavazha Malli').
    """
    stem = _SITE_PREFIX.sub("", stem)
    handle = None
    if (m := _HANDLE.search(stem)):
        handle = m.group(1)
        stem = _HANDLE.sub(" ", stem)
    return stem.strip(), handle


def _unslug(stem: str) -> str:
    """Turn a URL slug back into a normal title.

    'aathi-video-song-kaththi-vijay-samantha' comes from mp3-converter sites,
    where hyphens join *words*, not fields -- so they must become spaces before
    the field splitter ever sees them.
    """
    if " " in stem or stem.count("-") < 3:
        return stem
    if stem != stem.lower():
        return stem
    return stem.replace("-", " ")


def _extract_entities(text: str) -> tuple[str, dict[str, list[str]]]:
    """Remove every known name from the string, recording what was found.

    This is the core of handling YouTube-rip filenames: names are embedded in a
    run of words with no delimiters, so they must be recognised by identity
    rather than by position. What survives the scan is song and film.
    """
    found: dict[str, list[str]] = {}

    def replace(match: re.Match) -> str:
        role, canonical = _ENTITY_LOOKUP[match.lastgroup]
        bucket = found.setdefault(role, [])
        if canonical not in bucket:
            bucket.append(canonical)
        return _GAP

    return _entity_pattern().sub(replace, text), found


def _strip_trailing(text: str) -> str:
    """Repeatedly peel promotional tails: '... Super Hit Tamil Song'.

    Delimiter phrases are skipped -- stripping a trailing 'Video' here would
    delete the marker that tells `_segment` where the song title ended.
    """
    tables = _tables()
    protected = tables["protected"]
    for _ in range(6):                       # bounded; tails stack a few deep
        before = text
        for phrase in tables["trailing_junk"]:
            if _key(phrase) in protected:
                continue
            text = re.sub(
                rf"[\s,\-|~{_GAP}]*{_flexible(phrase)}\s*$", "", text, flags=re.IGNORECASE
            )
        text = text.strip(" ,-|~.")
        if text == before:
            break
    return text


def _segment(text: str) -> tuple[list[str], set[int]]:
    """Split into fragments and report where a junk phrase did the splitting.

    Returns (fragments, junk_boundaries) where a boundary of i means a junk
    phrase sat between fragment i-1 and fragment i. A boundary equal to
    len(fragments) means the junk trailed the final fragment.
    """
    tables = _tables()
    for phrase in tables["split_junk"]:
        text = re.sub(_flexible(phrase), _JUNK_MARK, text, flags=re.IGNORECASE)
    text = re.sub(r"\s*[-–—|~]\s*", _DASH_MARK, text)

    fragments: list[str] = []
    junk_boundaries: set[int] = set()
    pending_junk = False

    for token in re.split(f"([{_JUNK_MARK}{_DASH_MARK}])", text):
        if token == _JUNK_MARK:
            pending_junk = True
            continue
        if token == _DASH_MARK:
            continue

        cleaned = _WS.sub(" ", token.replace(_GAP, " ")).strip(" ,-|~.")
        if not cleaned:
            continue
        if pending_junk:
            junk_boundaries.add(len(fragments))
            pending_junk = False
        fragments.append(cleaned)

    if pending_junk:
        junk_boundaries.add(len(fragments))
    return fragments, junk_boundaries


# ------------------------------------------------------------- classification

def _classify(segment: str) -> tuple[str, str | None]:
    """Map a segment to (role, canonical_name).

    role is one of: composer, singer, cast, year, text.
    """
    if not segment.strip():
        return "empty", None

    # An all-Tamil-script segment normalises to an empty probe (the alias table
    # is romanised), but it is still perfectly good title text -- so the empty
    # check above deliberately looks at the raw segment, not the probe.
    probe = _key(segment)
    if not probe:
        return "text", segment

    tables = _tables()
    if probe.isdigit() and len(probe) == 4 and 1930 <= int(probe) <= 2049:
        return "year", probe
    if (canonical := tables["directors"].get(probe)):
        return "composer", canonical
    if (canonical := tables["singers"].get(probe)):
        return "singer", canonical
    if probe in tables["actors"]:
        return "cast", segment

    # Multi-name segments like "Anirudh Ravichander, Sid Sriram".
    parts = [p.strip() for p in re.split(r"[,&]|\band\b", segment) if p.strip()]
    if len(parts) > 1:
        roles = [_classify(p) for p in parts]
        if any(r == "singer" for r, _ in roles):
            names = [n for r, n in roles if r in ("singer", "composer") and n]
            if names:
                return "singer", ", ".join(dict.fromkeys(names))
        if any(r == "composer" for r, _ in roles):
            names = [n for r, n in roles if r == "composer" and n]
            if names:
                return "composer", ", ".join(dict.fromkeys(names))

    return "text", segment


# Orthographic signature of romanised Tamil. 'zh' (ழ) is essentially unique to
# it; doubled consonants and long vowels are far denser than in English.
_TA_DIGRAPHS = (
    ("zh", 3.0), ("nj", 1.5), ("dh", 1.5), ("kk", 1.5), ("pp", 1.5),
    ("aa", 1.5), ("uu", 1.0), ("ee", 1.0), ("oo", 1.0), ("tt", 1.0),
    ("nn", 1.0), ("ndh", 1.0), ("nth", 1.0), ("mm", 0.8), ("rr", 0.8),
    ("ll", 0.8), ("ii", 0.8), ("ng", 0.6), ("th", 0.5),
)
_TA_SUFFIXES = (
    ("kal", 1.5), ("gal", 1.5), ("um", 1.0), ("aa", 1.0), ("ai", 1.0),
    ("ae", 1.0), ("oo", 1.0), ("thu", 1.0), ("am", 0.8), ("an", 0.6),
    ("en", 0.6), ("du", 0.5),
)
# High-frequency English words. Their presence is strong evidence against Tamil.
_EN_STOPWORDS = frozenset({
    "the", "and", "but", "for", "with", "you", "your", "his", "her", "our",
    "are", "was", "were", "been", "does", "did", "dont", "cant", "wont",
    "this", "that", "these", "those", "what", "why", "how", "when", "where",
    "who", "can", "will", "would", "should", "could", "get", "got", "going",
    "know", "want", "need", "feel", "like", "just", "not", "all", "out",
    "down", "away", "back", "into", "over", "from", "about", "again",
    "love", "heart", "night", "day", "time", "life", "never", "always",
    "baby", "girl", "boy", "man", "god", "dream", "dreams", "one", "two",
})

_TAMIL_SCORE_THRESHOLD = 0.9


def _romanised_tamil_score(text: str) -> float:
    """Score how much a Latin-script string looks like romanised Tamil.

    A word list cannot generalise -- there are tens of thousands of Tamil song
    titles and any list will miss most of them. Orthography does generalise:
    'Nenjukkul Peidhidum' scores high on nj/kk/dh and the -um ending without
    appearing in any dictionary we ship.
    """
    normalised = _key(text)
    words = [w for w in normalised.split() if len(w) >= 2]
    if not words:
        return 0.0

    score = sum(normalised.count(pat) * weight for pat, weight in _TA_DIGRAPHS)
    for word in words:
        for suffix, weight in _TA_SUFFIXES:
            if word.endswith(suffix):
                score += weight
                break

    english_ratio = sum(1 for w in words if w in _EN_STOPWORDS) / len(words)
    return score / len(words) - 2.5 * english_ratio


def _detect_language(text: str, segments: list[str]) -> str | None:
    """Cheap pre-classification language guess.

    Native script is decisive. Everything else is a weak hint whose only job is
    to seed the audio classifier and break the Song/Movie ordering tie --
    VoxLingua has the final say later in the pipeline.
    """
    if _TAMIL_SCRIPT.search(text):
        return "ta"

    joined = " ".join(segments)
    if _tables()["tamil_markers"] & set(_key(joined).split()):
        return "ta"
    if _romanised_tamil_score(joined) >= _TAMIL_SCORE_THRESHOLD:
        return "ta"
    return None


# -------------------------------------------------------------------- parse

def parse(path: Path, *, use_folder_hints: bool = True) -> ParsedName:
    """Recover what we can from a file path.

    `use_folder_hints` lets the parent directory act as an album/movie hint,
    which is right for tidy `Artist/Album/01 Track.mp3` trees and harmless for
    flat download folders (a folder named 'Downloads' or 'Music' is ignored).
    """
    result = ParsedName()

    stem, handle = _strip_prefixes(path.stem)
    stem = _unslug(stem)

    # Leading track number, before junk removal eats the digits.
    if (m := _TRACK_NO.match(stem)):
        candidate = int(m.group(1))
        if 1 <= candidate <= 999:
            result.track_no = candidate
            stem = stem[m.end():]

    cleaned, year = _strip_junk(stem)
    result.year = year
    if not cleaned:
        cleaned = _strip_junk(path.stem)[0] or path.stem

    # Pull "feat. X" out before the entity scan so the featured name is not
    # merged into whatever fragment happens to surround it.
    featured: str | None = None
    if (m := _FEAT.search(cleaned)):
        head = cleaned[: m.start()].strip(" .,-")
        if head:
            featured = m.group(1).strip(" .,-")
            cleaned = head

    # Recognise names anywhere in the string, not just as whole segments.
    cleaned, entities = _extract_entities(cleaned)
    result.roles = {name: role for role, names in entities.items() for name in names}

    if (composers := entities.get("composer")):
        result.composer = composers[0]
    if (singers := entities.get("singer")):
        result.artist = singers[0]

    # Segment first, THEN clean each fragment. Doing it the other way round
    # deletes the delimiter phrases before the splitter can use them.
    raw_fragments, raw_boundaries = _segment(cleaned)

    fragments: list[str] = []
    junk_boundaries: set[int] = set()
    for index, fragment in enumerate(raw_fragments):
        if index in raw_boundaries:
            junk_boundaries.add(len(fragments))
        if (trimmed := _strip_trailing(fragment)):
            fragments.append(trimmed)
    if len(raw_fragments) in raw_boundaries:
        junk_boundaries.add(len(fragments))

    result.language_hint = _detect_language(path.name, fragments or [cleaned])

    # Folder hint: a parent directory that is not a generic dumping ground.
    folder_hint: str | None = None
    if use_folder_hints:
        parent = path.parent.name
        generic = {"music", "songs", "downloads", "download", "audio", "mp3",
                   "media", "new folder", "desktop", "documents", "tamil", "english",
                   "various", "various artists", "unknown", "unknown album", "temp"}
        # No minimum length: 'AM', 'IV' and 'OK' are real album titles.
        if parent and (probe := _key(parent)) and probe not in generic:
            folder_hint = _strip_junk(parent)[0] or None

    # --- assign title / album -------------------------------------------
    if junk_boundaries:
        # A junk phrase ('Video Song', 'Music Video') marks the END of the song
        # title -- that is what it is doing in every YouTube-derived filename.
        # So the fragment before it is the song and the one after it is the film,
        # regardless of which side of the dash they fell on.
        boundary = min(junk_boundaries)
        if boundary == 0:
            result.title = fragments[0] if fragments else None
            result.album = fragments[1] if len(fragments) > 1 else None
        else:
            result.title = fragments[boundary - 1]
            if boundary < len(fragments):
                result.album = fragments[boundary]
            elif boundary >= 2:
                # Junk trailed the last fragment, so the film is what came first.
                result.album = fragments[boundary - 2]
    elif len(fragments) == 1:
        result.title = fragments[0]
        if folder_hint and _key(folder_hint) != _key(fragments[0]):
            result.album = folder_hint
    elif len(fragments) >= 2:
        first, second = fragments[0], fragments[1]

        # The folder naming one of the fragments settles the order far more
        # reliably than any positional rule.
        if folder_hint and _key(folder_hint) == _key(second):
            result.title, result.album = first, second
        elif folder_hint and _key(folder_hint) == _key(first):
            result.title, result.album = second, first
        elif result.language_hint == "ta" or result.composer:
            # A dash-delimited Tamil rip is 'Song - Movie'.
            result.title, result.album = first, second
        else:
            # Western convention is 'Artist - Title'.
            result.artist = result.artist or first
            result.title = second

        if len(fragments) >= 3 and not result.album:
            result.album = fragments[2]

    if not result.artist and handle:
        # '@SaiAbhyankkar' is a channel handle; run it through the alias table in
        # case it names someone we know.
        role, canonical = _classify(handle)
        if role in ("singer", "composer"):
            result.artist = canonical
            if role == "composer" and not result.composer:
                result.composer = canonical
        else:
            result.artist = handle

    # --- refinements -----------------------------------------------------
    if result.title and not result.album:
        # "Titli (From 'Chennai Express')" -- the bracket was unwrapped earlier
        # because it is real metadata, and this is where it becomes the album.
        if (m := _FROM_ALBUM.search(result.title)):
            result.album = m.group(1).strip(" .,-\"'")
            result.title = result.title[: m.start()].strip(" .,-")

    if not result.artist and featured:
        role, canonical = _classify(featured)
        result.artist = canonical if role in ("singer", "composer") else featured

    # A film composer is the closest thing to an "artist" a Tamil film track has,
    # and it is what you would actually search for.
    if not result.artist and result.composer:
        result.artist = result.composer

    for attr in ("title", "artist", "album", "composer"):
        if (value := getattr(result, attr)):
            setattr(result, attr, _titlecase(value.strip(" .,-_")) or None)

    result.confidence = _score(result, entities, len(fragments))
    return result


def _score(result: ParsedName, entities: dict[str, list[str]], fragment_count: int) -> float:
    """How much we trust this parse, 0..1.

    Drives whether an online lookup is allowed to overwrite these fields.
    """
    if not result.title:
        return 0.0

    score = 0.35                                    # we got a title at all
    if entities.get("composer"):
        score += 0.25                               # alias table fired: strong
    if entities.get("singer"):
        score += 0.20
    if entities.get("cast") or entities.get("director") or entities.get("studio"):
        score += 0.05                               # recognised the film's people
    if result.album:
        score += 0.10
    if result.year:
        score += 0.05
    if fragment_count >= 2:
        score += 0.05                               # structured, not a bare name

    # A title that is still mostly digits or a single token is suspect.
    if len(result.title) < 3 or result.title.replace(" ", "").isdigit():
        score *= 0.3

    return round(min(score, 0.95), 2)
