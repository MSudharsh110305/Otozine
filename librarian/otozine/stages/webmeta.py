"""Stage 5 -- online metadata lookup.

Three providers, tried in order of trustworthiness:

  1. AcoustID -> MusicBrainz. Matches on an acoustic fingerprint, so it works on
     a re-encoded YouTube rip that shares no bytes with the release. Excellent
     for English/Western music, thin for Tamil film music.
  2. Deezer search. No API key, good Indian catalogue, returns cover art.
  3. iTunes search. No API key, useful cross-check and high-resolution art.

All three are optional. With `--offline` the pipeline skips this stage entirely
and leans on embedded tags plus the filename parser, which is the design
assumption for the Tamil half of the library anyway.
"""

from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import requests

from ..config import Config
from ..metadata import Candidate

STAGE = "webmeta"
STAGE_VERSION = 1

_ACOUSTID_URL = "https://api.acoustid.org/v2/lookup"
_MUSICBRAINZ_URL = "https://musicbrainz.org/ws/2/recording/{mbid}"
_DEEZER_URL = "https://api.deezer.com/search"
_ITUNES_URL = "https://itunes.apple.com/search"


class RateLimiter:
    """Process-wide minimum interval between calls to one host.

    MusicBrainz asks for no more than one request per second and will start
    returning 503 if you ignore that. Shared across worker threads.
    """

    def __init__(self, min_interval_s: float) -> None:
        self._min_interval = min_interval_s
        self._lock = threading.Lock()
        self._last = 0.0

    def wait(self) -> None:
        with self._lock:
            elapsed = time.monotonic() - self._last
            if elapsed < self._min_interval:
                time.sleep(self._min_interval - elapsed)
            self._last = time.monotonic()


@dataclass
class WebResult:
    candidates: list[Candidate]
    art_url: str | None = None
    acoustid: str | None = None


class WebMetadata:
    """Holds the HTTP session and rate limiters for one ingest run."""

    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.session = requests.Session()
        self.session.headers["User-Agent"] = cfg.user_agent
        self._mb_limiter = RateLimiter(cfg.musicbrainz_rate_limit_s)
        self._general_limiter = RateLimiter(0.15)

    # ------------------------------------------------------------ helpers
    def _get(self, url: str, *, limiter: RateLimiter, **kwargs) -> dict | None:
        """GET returning parsed JSON, or None on any failure.

        Network problems must never abort an ingest run -- the pipeline is
        resumable and an un-looked-up track simply gets retried next time.
        """
        limiter.wait()
        try:
            response = self.session.get(url, timeout=self.cfg.request_timeout_s, **kwargs)
            if response.status_code != 200:
                return None
            return response.json()
        except (requests.RequestException, ValueError):
            return None

    # ---------------------------------------------------------- providers
    def lookup(self, path: Path, hint: Candidate | None = None) -> WebResult:
        """Run the provider chain for one track."""
        result = WebResult(candidates=[])

        mb_candidate, acoustid = self._acoustid(path)
        if mb_candidate:
            result.candidates.append(mb_candidate)
            result.acoustid = acoustid

        # Search providers need something to search for. Prefer whatever the
        # fingerprint found; fall back to the filename parse.
        query_title = (mb_candidate.title if mb_candidate else None) or (hint.title if hint else None)
        query_artist = (mb_candidate.artist if mb_candidate else None) or (hint.artist if hint else None)

        if query_title:
            if (deezer := self._deezer(query_title, query_artist)):
                candidate, art = deezer
                result.candidates.append(candidate)
                result.art_url = result.art_url or art
            if (itunes := self._itunes(query_title, query_artist)):
                candidate, art = itunes
                result.candidates.append(candidate)
                result.art_url = result.art_url or art

        return result

    def _acoustid(self, path: Path) -> tuple[Candidate | None, str | None]:
        """Fingerprint the audio and resolve it to a MusicBrainz recording."""
        if not self.cfg.acoustid_api_key:
            return None, None
        try:
            import acoustid
        except ImportError:
            return None, None

        try:
            duration, fingerprint = acoustid.fingerprint_file(str(path))
        except Exception:
            return None, None

        data = self._get(
            _ACOUSTID_URL,
            limiter=self._general_limiter,
            params={
                "client": self.cfg.acoustid_api_key,
                "duration": int(duration),
                "fingerprint": fingerprint,
                "meta": "recordings releasegroups",
            },
        )
        if not data or data.get("status") != "ok":
            return None, None

        results = data.get("results") or []
        if not results:
            return None, None

        best = max(results, key=lambda r: r.get("score", 0.0))
        score = float(best.get("score", 0.0))
        recordings = best.get("recordings") or []
        if score < 0.5 or not recordings:
            return None, best.get("id")

        recording = recordings[0]
        artists = recording.get("artists") or []
        artist = ", ".join(a["name"] for a in artists if a.get("name")) or None

        groups = recording.get("releasegroups") or []
        album = groups[0].get("title") if groups else None

        candidate = Candidate(
            source="musicbrainz",
            # AcoustID's own match score maps directly onto our confidence: a
            # 0.95 fingerprint match really is near-certain.
            confidence=round(min(0.60 + 0.35 * score, 0.97), 3),
            title=recording.get("title"),
            artist=artist,
            album=album,
            mbid=recording.get("id"),
        )
        return candidate, best.get("id")

    def _deezer(self, title: str, artist: str | None) -> tuple[Candidate, str | None] | None:
        query = f'track:"{title}"' + (f' artist:"{artist}"' if artist else "")
        data = self._get(
            _DEEZER_URL, limiter=self._general_limiter,
            params={"q": query, "limit": 5},
        )
        if not data or not data.get("data"):
            return None

        best = data["data"][0]
        if not _plausible(title, best.get("title")):
            return None

        album = best.get("album") or {}
        clean_title, film = _clean_release_title(best.get("title"))
        candidate = Candidate(
            source="deezer",
            confidence=0.55 if artist else 0.45,
            title=clean_title,
            artist=(best.get("artist") or {}).get("name"),
            album=_resolve_album(album.get("title"), clean_title, film),
        )
        art = album.get("cover_xl") or album.get("cover_big") or album.get("cover_medium")
        return candidate, art

    def _itunes(self, title: str, artist: str | None) -> tuple[Candidate, str | None] | None:
        term = f"{artist} {title}" if artist else title
        data = self._get(
            _ITUNES_URL, limiter=self._general_limiter,
            params={"term": term, "media": "music", "entity": "song", "limit": 5},
        )
        if not data or not data.get("results"):
            return None

        best = data["results"][0]
        if not _plausible(title, best.get("trackName")):
            return None

        year = None
        if (release := best.get("releaseDate")) and len(release) >= 4 and release[:4].isdigit():
            year = int(release[:4])

        tags: list[tuple[str, str, float]] = []
        if (genre := best.get("primaryGenreName")):
            tags.append((genre, "genre", 0.55))

        clean_title, film = _clean_release_title(best.get("trackName"))
        candidate = Candidate(
            source="itunes",
            confidence=0.50 if artist else 0.40,
            title=clean_title,
            artist=best.get("artistName"),
            album=_resolve_album(best.get("collectionName"), clean_title, film),
            year=year,
            track_no=best.get("trackNumber"),
            tags=tags,
        )
        # iTunes serves 100x100 by default; the URL pattern scales to 600x600.
        art = None
        if (raw_art := best.get("artworkUrl100")):
            art = raw_art.replace("100x100bb", "600x600bb")
        return candidate, art

    def fetch_art(self, url: str, dest: Path) -> bool:
        """Download cover art. Returns True on success."""
        self._general_limiter.wait()
        try:
            response = self.session.get(url, timeout=self.cfg.request_timeout_s)
            if response.status_code != 200 or not response.content:
                return False
        except requests.RequestException:
            return False

        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(dest.suffix + ".part")
        tmp.write_bytes(response.content)
        tmp.replace(dest)
        return True


_FROM_FILM = re.compile(
    r'\s*[\(\[]\s*from\s+["“]?(.+?)["”]?\s*[\)\]]', re.IGNORECASE
)
_RELEASE_SUFFIX = re.compile(
    r"\s*[-–]\s*(single|ep|original motion picture soundtrack|"
    r"original soundtrack|soundtrack|deluxe|remastered)\s*$",
    re.IGNORECASE,
)


def _clean_release_title(title: str | None) -> tuple[str | None, str | None]:
    """Strip store-catalogue decoration, returning (title, film_hint).

    Deezer and iTunes title tracks the way a shop lists them, not the way anyone
    refers to them: 'Pavazha Malli (From "Think Indie")' and
    'Pavazha Malli (From "Think Indie") - Single'. Without this the online
    lookup actively degrades a filename we had already parsed correctly.

    The '(From "X")' part is not discarded -- for film music it names the film,
    which is exactly the album field we want.
    """
    if not title:
        return None, None

    film = None
    if (m := _FROM_FILM.search(title)):
        film = m.group(1).strip() or None
        title = (title[: m.start()] + " " + title[m.end():]).strip()

    title = _RELEASE_SUFFIX.sub("", title).strip()
    return (title or None), film


def _resolve_album(
    raw_album: str | None, title: str | None, film_hint: str | None
) -> str | None:
    """Pick a sensible album, discarding the catalogue entry for a single."""
    album, album_film = _clean_release_title(raw_album)
    film_hint = film_hint or album_film

    # A single's "album" is just the track name again; the film is the real answer.
    if album and title and album.casefold() == title.casefold():
        return film_hint
    return album or film_hint


def _plausible(query: str, found: str | None) -> bool:
    """Reject search hits that are not actually the track we asked for.

    Search APIs always return *something*. Without this guard, a Tamil track
    Deezer has never heard of comes back as an unrelated English song, and we
    would confidently write the wrong artist onto it.
    """
    if not found:
        return False

    a = _normalise(query)
    b = _normalise(found)
    if not a or not b:
        return False
    if a == b or a in b or b in a:
        return True

    # Token overlap: at least half the shorter title's words must appear.
    tokens_a, tokens_b = set(a.split()), set(b.split())
    if not tokens_a or not tokens_b:
        return False
    overlap = len(tokens_a & tokens_b) / min(len(tokens_a), len(tokens_b))
    return overlap >= 0.5


def _normalise(text: str) -> str:
    return " ".join(
        "".join(c for c in text.lower() if c.isalnum() or c.isspace()).split()
    )
