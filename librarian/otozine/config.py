"""Drive layout and tunable settings.

The pendrive is the source of truth, so every path in the database is stored
RELATIVE to `drive_root`. That is what makes the drive portable: plug it into a
different machine, it mounts on a different letter, and nothing breaks.
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field, asdict
from pathlib import Path

APP_DIR_NAME = "OtoZine"
CONFIG_FILENAME = "otozine.toml"
SCHEMA_VERSION = 1

# Audio extensions we will attempt to ingest.
AUDIO_EXTS = frozenset(
    {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".oga", ".opus", ".wma", ".aiff", ".aif", ".alac"}
)


@dataclass
class Config:
    """Runtime configuration. Loaded from <drive>/OtoZine/otozine.toml if present."""

    drive_root: Path

    # --- audio tiers -----------------------------------------------------
    # Dual-tier: originals archived untouched, Opus generated for the phone.
    opus_bitrate_k: int = 128
    keep_master: bool = True
    target_lufs: float = -14.0          # matches Spotify/YouTube normalisation
    true_peak_ceiling_db: float = -1.0

    # --- analysis --------------------------------------------------------
    analysis_sr: int = 16000            # mono PCM rate fed to the ML models
    analysis_max_seconds: float = 240.0 # cap per-track analysis cost
    hook_length_s: float = 30.0

    # --- metadata --------------------------------------------------------
    # Recover only the song name from filenames.
    #
    # Artist, album and composer are guessed from filename structure and online
    # search, and on a library of YouTube rips those guesses are wrong often
    # enough to be worse than blank: a confidently incorrect artist misleads,
    # where an absent one simply says nothing. Mood -- measured from the audio --
    # is the axis worth browsing by instead.
    title_only: bool = True

    # --- online lookups --------------------------------------------------
    online: bool = True
    acoustid_api_key: str | None = None
    lastfm_api_key: str | None = None
    musicbrainz_rate_limit_s: float = 1.05   # MB allows 1 req/sec; be polite
    user_agent: str = "OtoZine/0.1 (personal music library tool)"
    request_timeout_s: float = 15.0

    # --- pipeline --------------------------------------------------------
    workers: int = max(2, (os.cpu_count() or 4) - 1)
    ffmpeg: str = "ffmpeg"
    ffprobe: str = "ffprobe"

    # --- phone cache -----------------------------------------------------
    phone_cache_bytes: int = 12 * 1024**3    # 12 GB of Opus

    # Populated in __post_init__
    app_dir: Path = field(init=False)

    def __post_init__(self) -> None:
        self.drive_root = Path(self.drive_root).resolve()
        self.app_dir = self.drive_root / APP_DIR_NAME

    # --- derived paths ---------------------------------------------------
    @property
    def db_path(self) -> Path:
        return self.app_dir / "library.db"

    @property
    def vectors_path(self) -> Path:
        return self.app_dir / "vectors.bin"

    @property
    def prompts_vec_path(self) -> Path:
        return self.app_dir / "prompts.bin"

    @property
    def models_dir(self) -> Path:
        return self.app_dir / "models"

    @property
    def master_dir(self) -> Path:
        return self.app_dir / "audio" / "master"

    @property
    def opus_dir(self) -> Path:
        return self.app_dir / "audio" / "opus"

    @property
    def art_dir(self) -> Path:
        return self.app_dir / "art"

    @property
    def lyrics_dir(self) -> Path:
        return self.app_dir / "lyrics"

    @property
    def events_dir(self) -> Path:
        return self.app_dir / "events"

    @property
    def profile_dir(self) -> Path:
        return self.app_dir / "profile"

    @property
    def inbox_dir(self) -> Path:
        """Drop loose audio here from anywhere; ingest picks it up.

        Copying files onto the drive with a file manager cannot produce the
        master/Opus split -- that needs ffmpeg, so it needs a PC. The inbox
        makes the two halves of that reality meet: drop files in from a phone,
        another machine, or a download, and the next ingest processes whatever
        it finds without you having to remember a source path.
        """
        return self.app_dir / "inbox"

    @property
    def cache_dir(self) -> Path:
        """Scratch space for decoded PCM. Not synced, safe to delete."""
        return self.app_dir / ".cache"

    @property
    def config_path(self) -> Path:
        return self.app_dir / CONFIG_FILENAME

    # --- sharded storage -------------------------------------------------
    def shard(self, base: Path, content_hash: str, suffix: str) -> Path:
        """Two-level fan-out by hash prefix.

        exFAT degrades badly past ~1000 entries in a directory, and a 3500-track
        library would blow through that. Sharding on the first two hex chars
        keeps every directory under ~20 files.
        """
        return base / content_hash[:2] / f"{content_hash}{suffix}"

    def rel(self, path: Path) -> str:
        """Absolute path -> drive-relative POSIX string, for storage in the DB."""
        return Path(path).resolve().relative_to(self.drive_root).as_posix()

    def abs(self, rel_path: str) -> Path:
        """Drive-relative string -> absolute path on this machine."""
        return self.drive_root / rel_path

    def ensure_dirs(self) -> None:
        for d in (
            self.app_dir, self.models_dir, self.master_dir, self.opus_dir,
            self.art_dir, self.lyrics_dir, self.events_dir, self.profile_dir,
            self.inbox_dir, self.cache_dir,
        ):
            d.mkdir(parents=True, exist_ok=True)

    # --- persistence -----------------------------------------------------
    @classmethod
    def load(cls, drive_root: str | Path, **overrides) -> "Config":
        """Load config from the drive, applying env vars then explicit overrides.

        Precedence (low to high): defaults < otozine.toml < environment < kwargs.
        """
        root = Path(drive_root)
        cfg_file = root / APP_DIR_NAME / CONFIG_FILENAME

        data: dict = {}
        if cfg_file.is_file():
            with cfg_file.open("rb") as fh:
                raw = tomllib.load(fh)
            # Flatten the one level of sections we allow.
            for section in raw.values():
                if isinstance(section, dict):
                    data.update(section)
            data.update({k: v for k, v in raw.items() if not isinstance(v, dict)})

        # Environment beats the file, so secrets need not be written to the drive.
        for env_key, field_name in (
            ("OTOZINE_ACOUSTID_KEY", "acoustid_api_key"),
            ("OTOZINE_LASTFM_KEY", "lastfm_api_key"),
            ("OTOZINE_FFMPEG", "ffmpeg"),
            ("OTOZINE_FFPROBE", "ffprobe"),
        ):
            if os.environ.get(env_key):
                data[field_name] = os.environ[env_key]

        data.update(overrides)
        data.pop("drive_root", None)

        valid = {f for f in cls.__dataclass_fields__ if f != "app_dir"}
        unknown = set(data) - valid
        if unknown:
            raise ValueError(f"unknown config keys in {cfg_file}: {sorted(unknown)}")

        return cls(drive_root=root, **data)

    def to_dict(self) -> dict:
        d = asdict(self)
        d.pop("app_dir", None)
        d["drive_root"] = str(self.drive_root)
        return d
