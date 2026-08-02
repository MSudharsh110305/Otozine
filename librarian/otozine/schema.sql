-- OTOZINE library schema  (schema_version = 1)
--
-- This file is the contract between the Python Librarian (writer) and the
-- Android player (reader). Both sides code against it. Keep it in sync.
--
-- Design notes:
--  * content_hash (blake3 of the source bytes) is the stable cross-device
--    identity of a track. track_id is stable too, because the drive DB is the
--    single master -- the phone never invents ids.
--  * play_events is APPEND-ONLY and keyed by (device_id, event_id) so that
--    merging two devices is a set union with no conflict resolution.
--  * Everything derived (tau, model weights, transition counts) is recomputed
--    from play_events rather than synced. The event log is the only thing that
--    must survive.

-- ---------------------------------------------------------------- versioning

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER NOT NULL,
    applied_at  INTEGER NOT NULL
);

-- ------------------------------------------------------------------- tracks

CREATE TABLE IF NOT EXISTS tracks (
    id              INTEGER PRIMARY KEY,
    content_hash    TEXT    NOT NULL UNIQUE,   -- blake3-256 of source bytes

    -- provenance / storage (paths are RELATIVE to the drive root)
    source_path     TEXT    NOT NULL,          -- where we originally found it
    master_path     TEXT,                      -- audio/master/ab/<hash>.<ext>
    opus_path       TEXT,                      -- audio/opus/ab/<hash>.opus
    art_path        TEXT,                      -- art/ab/<hash>.webp
    lyrics_path     TEXT,                      -- lyrics/ab/<hash>.lrc

    -- identity
    mbid            TEXT,                      -- MusicBrainz recording id
    acoustid        TEXT,
    title           TEXT,
    artist          TEXT,                      -- primary performer / vocalist
    album_artist    TEXT,
    composer        TEXT,                      -- music director; the headline
                                               -- credit for Tamil film music
    album           TEXT,                      -- for Tamil film music: the movie
    track_no        INTEGER,
    year            INTEGER,
    language        TEXT,                      -- ta | en | instrumental | unknown
    meta_source     TEXT,                      -- which stage won: musicbrainz|deezer|itunes|filename|user
    meta_confidence REAL,

    -- source audio properties
    duration_ms     INTEGER,
    sample_rate     INTEGER,
    channels        INTEGER,
    src_codec       TEXT,
    src_bitrate     INTEGER,

    -- DSP analysis
    bpm             REAL,
    key_camelot     TEXT,                      -- e.g. '8A'
    key_name        TEXT,                      -- e.g. 'A minor'
    key_confidence  REAL,
    loudness_lufs   REAL,                      -- EBU R128 integrated
    loudness_range  REAL,
    true_peak_db    REAL,
    replaygain_db   REAL,                      -- gain to reach target LUFS

    -- ML scalar features (Essentia heads, 0..1 unless noted)
    energy          REAL,
    valence         REAL,
    arousal         REAL,
    danceability    REAL,
    is_instrumental INTEGER,                   -- 0/1
    approachability REAL,
    engagement      REAL,

    -- structure (ms offsets into the track)
    intro_end_ms    INTEGER,                   -- end of leading silence / intro
    outro_start_ms  INTEGER,                   -- start of trailing silence / outro
    hook_start_ms   INTEGER,                   -- best 30s preview start

    -- embedding: row index into vectors.bin (NULL until stage 6 runs)
    vec_index       INTEGER,

    -- per-track learned boredom half-life, in hours (see cooldown model)
    tau_hours       REAL    NOT NULL DEFAULT 168.0,

    -- bookkeeping
    added_at        INTEGER NOT NULL,          -- unix seconds
    analyzed_at     INTEGER,
    missing         INTEGER NOT NULL DEFAULT 0 -- 1 if source file disappeared
);

CREATE INDEX IF NOT EXISTS idx_tracks_artist    ON tracks(artist);
CREATE INDEX IF NOT EXISTS idx_tracks_album     ON tracks(album);
CREATE INDEX IF NOT EXISTS idx_tracks_language  ON tracks(language);
CREATE INDEX IF NOT EXISTS idx_tracks_vec       ON tracks(vec_index);
CREATE INDEX IF NOT EXISTS idx_tracks_added     ON tracks(added_at);

-- --------------------------------------------------------------------- tags
-- Multi-source tagging. We keep every source's opinion rather than collapsing
-- them, so the player can weight (or ignore) a source, and so re-running one
-- stage never destroys another stage's work.

CREATE TABLE IF NOT EXISTS tags (
    track_id    INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    tag         TEXT    NOT NULL,              -- normalised, lowercase
    kind        TEXT    NOT NULL,              -- genre|style|mood|language|instrument|theme|era
    source      TEXT    NOT NULL,              -- musicbrainz|lastfm|deezer|clap|effnet|filename|user
    confidence  REAL    NOT NULL DEFAULT 1.0,
    PRIMARY KEY (track_id, tag, source)
);

CREATE INDEX IF NOT EXISTS idx_tags_tag  ON tags(tag, kind);
CREATE INDEX IF NOT EXISTS idx_tags_kind ON tags(kind);

-- ---------------------------------------------------------- user corrections
-- Hand edits. The ingest pipeline reads this table and NEVER overwrites a
-- field that appears here. Also used as few-shot anchors for the tagger.

CREATE TABLE IF NOT EXISTS user_overrides (
    track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    field    TEXT    NOT NULL,                 -- column name in tracks, or 'tag:<kind>'
    value    TEXT,
    set_at   INTEGER NOT NULL,
    PRIMARY KEY (track_id, field)
);

-- -------------------------------------------------------------- ingest state
-- Per-track, per-stage progress. This is what makes the pipeline resumable and
-- idempotent: a stage is skipped when a row here says it already succeeded and
-- the stage's input version hasn't changed.

CREATE TABLE IF NOT EXISTS ingest_state (
    content_hash TEXT    NOT NULL,
    stage        TEXT    NOT NULL,
    status       TEXT    NOT NULL,             -- ok | failed | skipped
    stage_version INTEGER NOT NULL DEFAULT 1,  -- bump to force a re-run
    detail       TEXT,                         -- error message when failed
    updated_at   INTEGER NOT NULL,
    PRIMARY KEY (content_hash, stage)
);

CREATE INDEX IF NOT EXISTS idx_ingest_status ON ingest_state(stage, status);

-- --------------------------------------------------------------- play events
-- Append-only. Never UPDATE, never DELETE. Merge across devices = set union.

CREATE TABLE IF NOT EXISTS play_events (
    device_id     TEXT    NOT NULL,
    event_id      INTEGER NOT NULL,            -- monotonic counter, per device
    track_id      INTEGER NOT NULL,
    session_id    TEXT    NOT NULL,
    prev_track_id INTEGER,                     -- for transition learning
    started_at    INTEGER NOT NULL,            -- unix seconds
    ms_played     INTEGER NOT NULL,
    pct_played    REAL    NOT NULL,            -- 0..1, can exceed 1 on repeat
    outcome       TEXT    NOT NULL,            -- completed|skipped|replaced|abandoned
    -- context, all captured on-device at play time
    ctx_hour      INTEGER,                     -- 0..23 local
    ctx_dow       INTEGER,                     -- 0=Mon .. 6=Sun
    ctx_activity  TEXT,                        -- still|walking|running|vehicle|unknown
    ctx_output    TEXT,                        -- wired|bt|speaker|cast
    ctx_bt_codec  TEXT,                        -- sbc|aac|aptx|ldac|null
    ctx_battery   INTEGER,                     -- 0..100
    ctx_charging  INTEGER,                     -- 0/1
    ctx_screen_on INTEGER,                     -- 0/1
    PRIMARY KEY (device_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_events_track   ON play_events(track_id, started_at);
CREATE INDEX IF NOT EXISTS idx_events_time    ON play_events(started_at);
CREATE INDEX IF NOT EXISTS idx_events_session ON play_events(session_id);

-- --------------------------------------------------------------- transitions
-- The sequence-level anti-repetition table. Records every (A -> B) pair we have
-- ever SERVED (not just played), so the queue engine can refuse to build the
-- same path twice.

CREATE TABLE IF NOT EXISTS transitions (
    from_track     INTEGER NOT NULL,
    to_track       INTEGER NOT NULL,
    last_served_at INTEGER NOT NULL,
    serve_count    INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (from_track, to_track)
);

CREATE INDEX IF NOT EXISTS idx_transitions_recent ON transitions(last_served_at);

-- ----------------------------------------------------------------- playlists

CREATE TABLE IF NOT EXISTS playlists (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    kind       TEXT    NOT NULL DEFAULT 'manual',  -- manual | smart
    rule_json  TEXT,                                -- for kind='smart'
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS playlist_items (
    playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    track_id    INTEGER NOT NULL REFERENCES tracks(id)   ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    added_at    INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, track_id)
);

CREATE INDEX IF NOT EXISTS idx_playlist_items_pos ON playlist_items(playlist_id, position);

-- -------------------------------------------------------------- prompt bank
-- CLAP zero-shot classification prompts. Adding a category = inserting a row
-- here; the whole library re-tags against existing embeddings in seconds.
-- Vectors live in prompts.bin, indexed by vec_index.

CREATE TABLE IF NOT EXISTS prompts (
    id        INTEGER PRIMARY KEY,
    text      TEXT    NOT NULL UNIQUE,          -- the natural-language prompt
    label     TEXT    NOT NULL,                 -- tag it maps to
    kind      TEXT    NOT NULL,                 -- genre|mood|language|scene|era
    vec_index INTEGER,
    enabled   INTEGER NOT NULL DEFAULT 1
);

-- -------------------------------------------------------------- model state
-- Bandit weights, skip-model coefficients, cache plan, sync cursors.

CREATE TABLE IF NOT EXISTS model_state (
    key        TEXT PRIMARY KEY,
    blob       BLOB NOT NULL,
    updated_at INTEGER NOT NULL
);

-- ---------------------------------------------------------------- full text

CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(
    title, artist, album,
    content='tracks',
    content_rowid='id',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER IF NOT EXISTS tracks_fts_ins AFTER INSERT ON tracks BEGIN
    INSERT INTO tracks_fts(rowid, title, artist, album)
    VALUES (new.id, new.title, new.artist, new.album);
END;

CREATE TRIGGER IF NOT EXISTS tracks_fts_del AFTER DELETE ON tracks BEGIN
    INSERT INTO tracks_fts(tracks_fts, rowid, title, artist, album)
    VALUES ('delete', old.id, old.title, old.artist, old.album);
END;

CREATE TRIGGER IF NOT EXISTS tracks_fts_upd AFTER UPDATE ON tracks BEGIN
    INSERT INTO tracks_fts(tracks_fts, rowid, title, artist, album)
    VALUES ('delete', old.id, old.title, old.artist, old.album);
    INSERT INTO tracks_fts(rowid, title, artist, album)
    VALUES (new.id, new.title, new.artist, new.album);
END;
