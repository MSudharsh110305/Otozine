"""Command line interface.

    otozine doctor  --drive E:\\             check the toolchain and drive
    otozine ingest  --drive E:\\ --from D:\\Music
    otozine stats   --drive E:\\
    otozine inspect --drive E:\\ --query "vaathi"
"""

from __future__ import annotations

import argparse
import logging
import re
import shutil
import sys
from pathlib import Path

from . import db
from .config import APP_DIR_NAME, AUDIO_EXTS, Config
from .stages import scan
from .util import ffmpeg as ff

log = logging.getLogger("otozine")


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(message)s",
        stream=sys.stderr,
    )

    if args.command is None:
        parser.print_help()
        return 1

    handlers = {
        "doctor": cmd_doctor,
        "ingest": cmd_ingest,
        "stats": cmd_stats,
        "inspect": cmd_inspect,
        "fix": cmd_fix,
        "stage": cmd_stage,
        "init": cmd_init,
    }
    try:
        return handlers[args.command](args)
    except KeyboardInterrupt:
        print("\ninterrupted -- progress is saved, re-run to resume", file=sys.stderr)
        return 130


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="otozine",
        description="Ingest pipeline for the OTOZINE portable music library.",
    )
    parser.add_argument("-v", "--verbose", action="store_true")

    subparsers = parser.add_subparsers(dest="command")

    def with_drive(sub):
        sub.add_argument(
            "--drive", required=True, type=Path,
            help=r"Root of the pendrive, e.g. E:\ (the OtoZine folder lives here)",
        )
        return sub

    with_drive(subparsers.add_parser("doctor", help="check toolchain and drive readiness"))

    ingest = with_drive(subparsers.add_parser("ingest", help="scan and ingest audio files"))
    ingest.add_argument("--from", dest="sources", nargs="+", type=Path,
                        help="folders (or files) to ingest "
                             "(default: the drive's OtoZine/inbox folder)")
    ingest.add_argument("--consume", action="store_true",
                        help="delete inbox files once they are safely archived")
    ingest.add_argument("--limit", type=int, help="stop after N new files (for testing)")
    ingest.add_argument("--offline", action="store_true",
                        help="skip all network lookups")
    ingest.add_argument("--force", nargs="*", default=[],
                        metavar="STAGE",
                        help="re-run these stages even if already done "
                             "(archive metadata dsp mood transcode)")
    ingest.add_argument("--workers", type=int, help="override worker thread count")

    with_drive(subparsers.add_parser("stats", help="summarise the library"))

    inspect = with_drive(subparsers.add_parser("inspect", help="show parsed metadata for tracks"))
    inspect.add_argument("--query", help="substring match on title or artist")
    inspect.add_argument("--limit", type=int, default=20)

    fix = with_drive(subparsers.add_parser(
        "fix", help="hand-correct a track; the pipeline will never overwrite it"))
    fix.add_argument("--id", type=int, required=True, help="track id (see 'inspect')")
    for field in ("title", "artist", "album", "composer", "language"):
        fix.add_argument(f"--{field}", help=f"set {field}")
    fix.add_argument("--year", type=int, help="set year")
    fix.add_argument("--clear", nargs="+", metavar="FIELD", default=[],
                     help="hand these fields back to the pipeline")

    stage = with_drive(subparsers.add_parser(
        "stage", help="build a phone-ready subset of the library"))
    stage.add_argument("--out", required=True, type=Path,
                       help="directory to write the staged tree into")
    stage.add_argument("--budget", default="12GB",
                       help="size limit, e.g. 12GB / 500MB (default 12GB)")
    stage.add_argument("--clean", action="store_true",
                       help="wipe the output directory first")

    init = with_drive(subparsers.add_parser(
        "init", help="prepare a blank drive for use as an OtoZine library"))
    init.add_argument("--force", action="store_true",
                      help="proceed even if the drive already has a library")

    return parser


# ------------------------------------------------------------------ doctor

def cmd_doctor(args) -> int:
    cfg = Config.load(args.drive)
    ok = True

    print(f"drive root   {cfg.drive_root}")
    print(f"app dir      {cfg.app_dir}")

    if not cfg.drive_root.exists():
        print(f"  FAIL       drive root does not exist")
        return 1

    # --- binaries -------------------------------------------------------
    for name in ("ffmpeg", "ffprobe"):
        try:
            path = ff.resolve_binary(name, getattr(cfg, name))
            print(f"  ok         {name}: {path}")
        except ff.FFmpegMissing as exc:
            ok = False
            print(f"  MISSING    {exc}")

    # --- python packages ------------------------------------------------
    for module, purpose, required in (
        ("numpy", "array maths", True),
        ("blake3", "content hashing", True),
        ("librosa", "tempo/key analysis", True),
        ("mutagen", "embedded tag reading", True),
        ("requests", "online metadata", True),
        ("acoustid", "acoustic fingerprinting", False),
        ("onnxruntime", "ML tagging (stage 6-8)", False),
    ):
        try:
            __import__(module)
            print(f"  ok         {module} ({purpose})")
        except ImportError:
            if required:
                ok = False
                print(f"  MISSING    {module} -- needed for {purpose}")
            else:
                print(f"  optional   {module} not installed ({purpose})")

    # --- api keys -------------------------------------------------------
    if cfg.acoustid_api_key:
        print("  ok         AcoustID key present")
    else:
        print("  optional   no AcoustID key -- fingerprint lookup disabled")
        print("             free key: https://acoustid.org/new-application")
        print("             then set OTOZINE_ACOUSTID_KEY")

    # --- space ----------------------------------------------------------
    try:
        usage = shutil.disk_usage(cfg.drive_root)
        free_gib = usage.free / 1024**3
        total_gib = usage.total / 1024**3
        print(f"  {'ok        ' if free_gib > 1 else 'LOW       '} "
              f"space: {free_gib:.1f} GiB free of {total_gib:.1f} GiB")
    except OSError as exc:
        print(f"  warn       could not read free space: {exc}")

    # --- database -------------------------------------------------------
    if cfg.db_path.exists():
        conn = db.connect(cfg.db_path, read_only=True)
        try:
            stats = db.library_stats(conn)
            print(f"  ok         library.db: {stats['tracks']} tracks")
        finally:
            conn.close()
    else:
        print(f"  new        no library yet (will be created at {APP_DIR_NAME}/library.db)")

    print()
    print("READY" if ok else "NOT READY -- resolve the MISSING items above")
    return 0 if ok else 1


# ------------------------------------------------------------------ ingest

def cmd_ingest(args) -> int:
    from .pipeline import Pipeline           # imported late: pulls in librosa

    overrides = {}
    if args.workers:
        overrides["workers"] = args.workers
    cfg = Config.load(args.drive, **overrides)

    # No source given: process whatever has been dropped in the inbox. This is
    # the path most transfers take -- copy songs onto the drive from wherever,
    # plug it into a PC, run one command.
    sources = args.sources
    from_inbox = not sources
    if from_inbox:
        cfg.ensure_dirs()
        sources = [cfg.inbox_dir]
        loose = [
            p for p in cfg.inbox_dir.rglob("*")
            if p.is_file() and p.suffix.lower() in AUDIO_EXTS
        ]
        if not loose:
            print(f"  inbox is empty: {cfg.inbox_dir}")
            print("  copy songs in there, or pass --from <folder>")
            return 0
        print(f"  processing {len(loose)} file(s) from the inbox")

    missing = [str(s) for s in sources if not Path(s).exists()]
    if missing:
        print(f"source does not exist: {', '.join(missing)}", file=sys.stderr)
        return 1

    valid_stages = {"archive", "metadata", "dsp", "mood", "transcode"}
    if unknown := set(args.force) - valid_stages:
        print(f"unknown stage(s) for --force: {sorted(unknown)}", file=sys.stderr)
        print(f"valid stages: {sorted(valid_stages)}", file=sys.stderr)
        return 1

    with Pipeline(cfg, offline=args.offline, force=set(args.force)) as pipeline:
        stats = pipeline.ingest(sources, limit=args.limit)

    print()
    print(f"  scanned      {stats.scanned}")
    if stats.duplicates:
        print(f"  duplicates   {stats.duplicates} ignored "
              f"({stats.duplicate_bytes / 1024**2:.1f} MB saved)")
    print(f"  added        {stats.added}")
    print(f"  already had  {stats.skipped}")
    print(f"  analysed     {stats.analysed}")
    print(f"  transcoded   {stats.transcoded}")
    if not args.offline:
        print(f"  identified   {stats.identified}  (fingerprint matched)")
    if stats.failed:
        print(f"  FAILED       {stats.failed}")
        for name, message in stats.errors:
            print(f"    {name}: {message[:110]}")

    # Only clear the inbox once the archive copy is confirmed on disk. Deleting
    # on "ingest reported success" alone would risk removing the only copy of a
    # song if the archive write had failed quietly.
    if from_inbox and args.consume and stats.failed == 0:
        removed = _drain_inbox(cfg)
        if removed:
            print(f"  inbox        {removed} file(s) cleared (archived on the drive)")

    return 0 if stats.failed == 0 else 2


def _drain_inbox(cfg: Config) -> int:
    """Delete inbox files that are provably archived in the master tier."""
    conn = db.connect(cfg.db_path, read_only=True)
    try:
        archived = {
            Path(r["master_path"]).name
            for r in conn.execute(
                "SELECT master_path FROM tracks WHERE master_path IS NOT NULL"
            )
        }
    finally:
        conn.close()

    removed = 0
    for path in list(cfg.inbox_dir.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in AUDIO_EXTS:
            continue
        # Archived names are content hashes, so confirm by hashing the file
        # rather than trusting its name.
        digest = scan.hash_file(path)
        if any(name.startswith(digest) for name in archived):
            path.unlink(missing_ok=True)
            removed += 1
    return removed


# ------------------------------------------------------------------- stats

def cmd_stats(args) -> int:
    cfg = Config.load(args.drive)
    if not cfg.db_path.exists():
        print("no library yet -- run 'otozine ingest' first", file=sys.stderr)
        return 1

    conn = db.connect(cfg.db_path, read_only=True)
    try:
        stats = db.library_stats(conn)
        print(f"  tracks       {stats['tracks']}")
        print(f"  duration     {stats['hours']:.1f} hours")
        print(f"  transcoded   {stats['transcoded']}")
        print(f"  embedded     {stats['embedded']}  (ML vectors)")
        print(f"  identified   {stats['identified']}  (MusicBrainz id)")
        if stats["missing"]:
            print(f"  MISSING      {stats['missing']} source files gone")

        print("\n  by language")
        for language, count in stats["by_language"].items():
            print(f"    {language:<12} {count}")

        print("\n  ingest progress")
        counts = db.stage_counts(conn)
        stages = sorted({stage for stage, _ in counts})
        for stage in stages:
            done = counts.get((stage, "ok"), 0)
            failed = counts.get((stage, "failed"), 0)
            suffix = f"  ({failed} failed)" if failed else ""
            print(f"    {stage:<12} {done}{suffix}")
    finally:
        conn.close()
    return 0


# ----------------------------------------------------------------- inspect

def cmd_inspect(args) -> int:
    cfg = Config.load(args.drive)
    if not cfg.db_path.exists():
        print("no library yet -- run 'otozine ingest' first", file=sys.stderr)
        return 1

    conn = db.connect(cfg.db_path, read_only=True)
    try:
        sql = (
            "SELECT id, title, artist, composer, album, year, language, bpm, "
            "       key_camelot, loudness_lufs, replaygain_db, meta_source, meta_confidence "
            "FROM tracks "
        )
        params: tuple = ()
        if args.query:
            sql += "WHERE title LIKE ? OR artist LIKE ? OR album LIKE ? "
            like = f"%{args.query}%"
            params = (like, like, like)
        sql += "ORDER BY added_at DESC LIMIT ?"
        params += (args.limit,)

        rows = conn.execute(sql, params).fetchall()
        if not rows:
            print("no matching tracks")
            return 0

        for row in rows:
            bpm = f"{row['bpm']:.0f}" if row["bpm"] else "--"
            lufs = f"{row['loudness_lufs']:.1f}" if row["loudness_lufs"] is not None else "--"
            gain = f"{row['replaygain_db']:+.1f}" if row["replaygain_db"] is not None else "--"
            print(f"\n  [{row['id']}] {row['title'] or '(untitled)'}")
            print(f"      artist    {row['artist'] or '--'}"
                  + (f"   composer {row['composer']}" if row["composer"] else ""))
            print(f"      album     {row['album'] or '--'}"
                  + (f"  ({row['year']})" if row["year"] else ""))
            print(f"      audio     {bpm} bpm   key {row['key_camelot'] or '--'}   "
                  f"{lufs} LUFS   gain {gain} dB")
            print(f"      source    {row['meta_source'] or '--'} "
                  f"(confidence {row['meta_confidence'] or 0:.2f})   lang {row['language'] or '--'}")
    finally:
        conn.close()
    return 0


# --------------------------------------------------------------------- fix

def cmd_fix(args) -> int:
    """Apply a hand correction that re-ingest will respect forever."""
    cfg = Config.load(args.drive)
    if not cfg.db_path.exists():
        print("no library yet -- run 'otozine ingest' first", file=sys.stderr)
        return 1

    correctable = ("title", "artist", "album", "composer", "language", "year")
    updates = {f: getattr(args, f) for f in correctable if getattr(args, f, None) is not None}

    if invalid := [f for f in args.clear if f not in correctable]:
        print(f"cannot clear: {invalid}. valid: {list(correctable)}", file=sys.stderr)
        return 1
    if not updates and not args.clear:
        print(f"nothing to do -- pass one of {['--' + f for f in correctable]} "
              f"or --clear FIELD", file=sys.stderr)
        return 1

    conn = db.connect(cfg.db_path)
    try:
        row = conn.execute("SELECT title FROM tracks WHERE id = ?", (args.id,)).fetchone()
        if row is None:
            print(f"no track with id {args.id}", file=sys.stderr)
            return 1

        with db.transaction(conn):
            for field, value in updates.items():
                db.set_user_override(conn, args.id, field, value)
            for field in args.clear:
                db.set_user_override(conn, args.id, field, None)
    finally:
        conn.close()

    print(f"  [{args.id}] {row['title']}")
    for field, value in updates.items():
        print(f"      {field} = {value!r}  (pinned; ingest will not overwrite)")
    for field in args.clear:
        print(f"      {field} released back to the pipeline")
    return 0


# ------------------------------------------------------------------- stage

_SIZE_UNITS = {"b": 1, "kb": 1024, "mb": 1024**2, "gb": 1024**3, "tb": 1024**4}


def _parse_size(text: str) -> int:
    match = re.fullmatch(r"\s*([\d.]+)\s*([a-zA-Z]*)\s*", text)
    if not match:
        raise ValueError(f"could not parse size: {text!r}")
    value, unit = float(match.group(1)), (match.group(2) or "b").lower()
    if unit not in _SIZE_UNITS:
        raise ValueError(f"unknown size unit {unit!r}; use B/KB/MB/GB")
    return int(value * _SIZE_UNITS[unit])


def cmd_stage(args) -> int:
    from .stages import cache

    cfg = Config.load(args.drive)
    if not cfg.db_path.exists():
        print("no library yet -- run 'otozine ingest' first", file=sys.stderr)
        return 1

    try:
        budget = _parse_size(args.budget)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 1

    plan = cache.stage(cfg, args.out, budget, clean=args.clean)

    print(f"  staged       {plan.count} tracks into {args.out}")
    print(f"  size         {plan.total_bytes / 1024**2:.1f} MB "
          f"of {budget / 1024**3:.1f} GB budget")
    if plan.excluded_over_budget:
        print(f"  left behind  {plan.excluded_over_budget} tracks (budget full)")
    if plan.skipped_no_audio:
        print(f"  no audio     {plan.skipped_no_audio} tracks not transcoded yet")
    return 0


# -------------------------------------------------------------------- init

_CONFIG_TEMPLATE = """\
# OtoZine drive configuration.
# Every path in library.db is relative to this drive, so the drive works on any
# machine regardless of which letter it mounts as.

[audio]
opus_bitrate_k = {bitrate}
target_lufs = {lufs}
keep_master = true

[online]
online = true
# Secrets belong in the environment, not on a drive you carry around:
#   set OTOZINE_ACOUSTID_KEY=...     (free: https://acoustid.org/new-application)

[phone]
phone_cache_bytes = {cache}
"""


def cmd_init(args) -> int:
    """Lay out a blank drive so it can hold a library."""
    cfg = Config.load(args.drive)

    if not cfg.drive_root.exists():
        print(f"drive not found: {cfg.drive_root}", file=sys.stderr)
        return 1

    if cfg.db_path.exists() and not args.force:
        conn = db.connect(cfg.db_path, read_only=True)
        try:
            count = db.library_stats(conn)["tracks"]
        finally:
            conn.close()
        print(f"this drive already holds a library ({count} tracks) at {cfg.app_dir}")
        print("nothing to do -- pass --force to rewrite the config anyway")
        return 0

    try:
        usage = shutil.disk_usage(cfg.drive_root)
    except OSError as exc:
        print(f"cannot read the drive: {exc}", file=sys.stderr)
        return 1

    cfg.ensure_dirs()
    conn = db.connect(cfg.db_path)
    try:
        db.migrate(conn)
    finally:
        conn.close()

    if not cfg.config_path.exists() or args.force:
        cfg.config_path.write_text(
            _CONFIG_TEMPLATE.format(
                bitrate=cfg.opus_bitrate_k,
                lufs=cfg.target_lufs,
                cache=cfg.phone_cache_bytes,
            ),
            encoding="utf-8",
        )

    free_gib = usage.free / 1024**3
    # Rough planning figure: Opus 128k is about 1 MB/min, and the dual-tier
    # layout keeps originals too, so budget roughly 6 MB per track all-in.
    capacity = int((free_gib * 1024) / 6)

    print(f"  initialised   {cfg.app_dir}")
    print(f"  free space    {free_gib:.1f} GiB  (~{capacity:,} tracks at 128k Opus + originals)")
    print(f"  config        {cfg.config_path}")
    print()
    print("  next:")
    print(f"    otozine ingest --drive {args.drive} --from <folder of music>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
