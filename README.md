# OTOZINE

A self-hosted music library that lives on a pendrive. Offline-first, with a
taste model that travels with the drive.

Two components:

| | what it is | status |
|---|---|---|
| **`librarian/`** | Python ingest tool. Runs on a PC, writes everything to the drive. All the expensive work happens here, once per track. | working, 46 tests passing |
| **`android/`** | Kotlin/Compose player. Reads what the Librarian produced. | builds + lint clean; **playback not yet verified on a device** |

Full design: [`~/.claude/plans/i-m-tired-of-paying-reflective-crystal.md`](../../.claude/plans/i-m-tired-of-paying-reflective-crystal.md)

---

## Why the work is split this way

Heavy ML (fingerprinting, embeddings, genre/mood/language tagging) runs **once
on a PC at ingest time**. The phone only does arithmetic on precomputed vectors.
That is what makes a shader-heavy player with a real recommender feasible on a
battery.

One constraint drives the storage design: **exFAT over Android's Storage Access
Framework gives no POSIX file handles**, so SQLite cannot be opened in place on
the drive from Android. The database is copied to internal storage on sync, the
phone appends to a local event log, and the two merge on next plug-in.

---

## Librarian

### Setup

```powershell
cd librarian
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .
winget install Gyan.FFmpeg        # required
```

Check everything is wired up:

```powershell
.\.venv\Scripts\python.exe -m otozine.cli doctor --drive E:\
```

Optional, for acoustic fingerprinting (worth it for Western music, largely
useless for Tamil film music):

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[fingerprint]"
$env:OTOZINE_ACOUSTID_KEY = "your-key"   # free: https://acoustid.org/new-application
```

### Use

```powershell
# ingest a folder onto the drive
otozine ingest  --drive E:\ --from D:\Music

# no network at all -- embedded tags + filename parsing only
otozine ingest  --drive E:\ --from D:\Music --offline

# library summary and per-stage progress
otozine stats   --drive E:\

# what did it actually work out about my tracks?
otozine inspect --drive E:\ --query "vaathi"

# hand-correct something; ingest will never overwrite it again
otozine fix     --drive E:\ --id 42 --artist "Anirudh Ravichander"
otozine fix     --drive E:\ --id 42 --clear artist    # release it back
```

Interrupting is safe. Progress is recorded per track per stage, so re-running
resumes rather than restarting. Re-running an unchanged library does nothing at
all — that is asserted by a test, not assumed.

### Drive layout

```
E:\OtoZine\
  library.db          SQLite -- the contract between Librarian and player
  vectors.bin         mmap'd fp16 embedding matrix, one row per track
  models/             ONNX models
  audio/master/ab/…   originals, content-addressed, archive tier
  audio/opus/ab/…     Opus 128k, phone tier
  art/  lyrics/       cover art, .lrc
  events/             append-only play log, per device
  profile/            taste model weights
```

Paths in the database are **relative to the drive root**, so the drive works on
any machine regardless of which letter it mounts as. Files are sharded two
levels deep by hash prefix because exFAT degrades badly past ~1000 entries per
directory.

### The pipeline

| # | stage | what it does |
|---|---|---|
| 1 | scan | walk, blake3 content-hash, dedupe |
| 2 | archive | copy the original into the master tier |
| 3 | tags | read embedded ID3/Vorbis metadata |
| 4 | nameparse | recover metadata from the filename |
| 5 | webmeta | AcoustID→MusicBrainz, Deezer, iTunes |
| 6 | merge | resolve the four sources field by field |
| 9 | dsp | BPM, key→Camelot, EBU R128 loudness, structure |
| 11 | transcode | Opus 128k with an R128 gain tag |

Stages 6–8 (CLAP embeddings, Essentia heads, VoxLingua language ID) are designed
but not yet implemented — see *Not done yet*.

### Three decisions worth knowing

**Loudness is measured on the file we deliver, not the source.** Lossy encoding
moves the true peak: a master clipping at +1.37 dBTP came back at +2.20 dBTP
after Opus encoding, so a ceiling computed from the source let the delivered
file clip by 0.8 dB. The encoder therefore runs first, the result is measured,
and the R128 gain tag is stamped in afterwards by a stream copy (no second
encode). Integrated loudness is unaffected by encoding — only the peak is.

**Metadata merges per field, not per source.** A YouTube rip often has a correct
embedded title and a junk artist (the uploader's channel), while the filename
carries the real composer. Taking whole records from a single winner throws away
the good half of each. Every source produces a scored candidate and each field
is resolved independently.

**Dedupe keeps the best-named copy, not the first one found.** These can be
byte-identical:

```
Vaathi Coming MassTamilan.com 320kbps [HQ].mp3
[Isaimini.com] Vaathi Coming - Master - Anirudh Ravichander (2020).mp3
```

Only the second names the film and the composer. Picking by directory-walk order
discarded that roughly half the time.

### Tamil metadata

MusicBrainz coverage of Tamil film music is thin, and the gap is bigger than it
looks. Measured against the live API:

| artist | MusicBrainz recordings |
|---|---|
| Radiohead | 12,481 (for a catalogue of ~150 songs) |
| Daft Punk | 1,886 |
| Anirudh Ravichander | 723 |
| Ilaiyaraaja | 654 (for a catalogue of several thousand) |

Recent viral tracks resolve fine (*Vaathi Coming*, *Why This Kolaveri Di*), but
**"Munbe Vaa" — one of the most famous Tamil songs ever recorded — returns zero
hits.** So for that half of a library the filename *is* the metadata.

Four mechanisms, in `stages/nameparse.py` and `data/tamil_aliases.json`:

- **Entity recognition across the whole string.** Real downloads look like
  `Pakkam Vanthu - Video Song Kaththi Vijay Samantha Anirudh Ravichander` — one
  run of words with no delimiters. Whole-segment classification cannot see into
  that, so ~300 known names (composers, singers, cast, directors, studios) are
  matched *anywhere* and removed; what survives is song and film.
- **Junk phrases used as delimiters, not deleted.** "Video Song" sits exactly
  between the song title and the film name in every YouTube-derived filename.
  It is the only structure such a name has. Two separate passes used to delete
  it as noise before the splitter ran.
- **An alias table** with the spellings that actually appear (`ilayaraja`,
  `illayaraja`, `isaignani` → *Ilaiyaraaja*).
- **A romanisation scorer** that generalises past any word list. `zh` is
  essentially unique to romanised Tamil; doubled consonants and long vowels are
  far denser than in English.

Measured on a real library of 14 Tamil YouTube rips: **14/14 titles correct,
13/14 films correct**, and every composer named in a filename extracted and
canonicalised. Before the entity-scan rewrite it was 2/14.

### Verified behaviour

`pytest tests/ -q` → 65 passing. The non-obvious ones:

- key detection is exact on all fixtures (Am→8A, C→8B, Em→9A, G→9B)
- tempo within 15% of ground truth
- every track reaches −14 LUFS after its stored gain, *or* is correctly
  peak-limited short of it
- the delivered Opus never clips after gain
- a second ingest run does zero work
- deleting one stage's output re-runs only that stage
- `--force transcode` really re-encodes rather than just re-running bookkeeping
- a hand correction survives `--force metadata`
- no alias maps to two different people
- delimiter phrases survive every deletion pass
- 13 real YouTube-rip filenames parse to the right song/film/composer

Two tests guard against themselves becoming vacuous: one asserts a fixture
genuinely clips (a limiter once held the "hot" fixture at −3 dBTP, silently
removing the only case that mattered), and one asserts the alias table has no
ambiguous entries.

### Measured on a real library

14 Tamil YouTube rips, ingested to a pendrive:

- source loudness spanned **7.2 LU** (−8.1 to −15.3 LUFS); 12 of 14 land exactly
  on −14.0, the other 2 correctly peak-limited because their masters clip
- Opus tier is **52%** the size of the originals
- years, full singer credits and artwork came from Deezer/iTunes for 12 of 14
- **one master peaks at +1.37 dBTP** — already clipping before we touch it,
  which is ordinary for modern Tamil film mastering

### Not done yet

- **Stages 6–8**: CLAP embeddings, Essentia ONNX heads (genre/mood/danceability),
  VoxLingua language ID. `vectors.bin`, the `prompts` table and the `energy` /
  `valence` / `danceability` columns exist and are wired; they are currently fed
  by DSP proxies rather than the real models.
- **Hook detection is untested on real music.** The synthetic fixtures have flat
  energy, so "loudest 30s window" trivially picks the start. The logic is sound
  but needs real material to tune.
- `essentia` is deliberately **not** a dependency — it has no Windows wheels. The
  plan is to run its exported `.onnx` models through `onnxruntime` and build the
  mel-spectrogram input in numpy.
- Artwork is fetched only when an online provider returns a URL; embedded cover
  art is not yet extracted.

---

## Android player

Phase 0: get playback correct before anything clever. The UI is deliberately
plain — the neubrutalist treatment is Phase 4 and gets built against a real
library, not placeholder data.

### Toolchain

Pinned, and each constraint forced the next:

| | version | why |
|---|---|---|
| AGP | 9.3.1 | first that supports `compileSdk 37` |
| Gradle | 9.6.1 | AGP 9.3.1 requires ≥ 9.5.0 |
| Kotlin | 2.4.10 | Compose compiler plugin only — **AGP 9 has built-in Kotlin** and rejects `org.jetbrains.kotlin.android` |
| compileSdk | 37 | current AndroidX (core-ktx 1.19, lifecycle 2.11) requires consumers to compile against 37 |
| minSdk | 33 | AGSL `RuntimeShader`, needed for the Phase 4 UI |
| targetSdk | 36 | not 37 — that opts into runtime behaviour changes not testable without a device |

The SDK path comes from `ANDROID_HOME`; there is deliberately no
`local.properties` (it is machine-specific and lint rejects unescaped Windows
paths in it).

### Build

```powershell
cd android
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.11"
.\gradlew.bat :app:assembleDebug        # -> app/build/outputs/apk/debug/
.\gradlew.bat :app:lintDebug            # clean
```

### What Phase 0 covers

- **`PlaybackService`** — Media3 `MediaSessionService`. Lock screen, notification,
  Bluetooth headset buttons and Android Auto all come from `MediaSession` rather
  than being hand-rolled.
- **Audio focus** — ducking for navigation, pausing for calls, resuming after.
- **Becoming-noisy** — auto-pause on headphone unplug. Without it, yanking the
  cable blasts the track out of the phone speaker.
- **`LoudnessController`** — applies the gain the Librarian measured. Needs two
  mechanisms because they pull opposite ways: attenuation via `ExoPlayer.volume`
  (exact, free) and amplification via `LoudnessEnhancer` (`volume` is capped at
  1.0 and cannot boost). Boost is capped at +15 dB, past which the effect's
  compression is audible as pumping.
- **`AudioOutputMonitor`** — wired / Bluetooth / speaker detection. Phase 0 only
  reports it; Phase 3 uses it as recommender context.
- **`LibraryRepository`** — reads `library.db` directly. Raw SQLite, not Room:
  the schema is authored and migrated on the Python side, and pointing Room at a
  database it does not own means fighting its schema validation for no benefit.
- **Intro skip** — seeks past measured dead air rather than trimming the file,
  so every stored millisecond offset stays aligned with the master copy.

### On-device layout

Mirrors the drive so paths in the database resolve without translation:

```
filesDir/library/
  library.db
  audio/opus/ab/<hash>.opus
  art/ab/<hash>.jpg
```

Phase 2 populates this by syncing from the pendrive over SAF. Until then,
`adb push` is enough to exercise playback.

### The cross-language contract

`library.db` is written by Python and read by Kotlin, and nothing in either
toolchain notices when they drift — the Kotlin side just throws
`column 'foo' does not exist` at runtime, on a phone.

`librarian/tests/test_android_contract.py` closes that gap: it extracts the
actual SQL and every `getColumnIndexOrThrow` name out of `LibraryRepository.kt`
and runs them against a database the pipeline really built. Rename a column in
`schema.sql` without updating the Kotlin and it fails on the PC. It also asserts
the `SCHEMA_VERSION` constants on both sides agree.

### Getting the library onto the phone

```powershell
.\scripts\push-to-phone.ps1 -Drive G:\ -Budget 12GB -Restart
```

Stages a subset that fits the budget, tars it, and unpacks it inside the app's
private storage via `run-as`. A developer convenience — Phase 2 replaces it with
the phone reading the drive directly over USB-OTG.

### Verified on the device

Galaxy S24 FE (SM-S721B), **Android 16 / API 36**, with a real 14-track library:

| | evidence |
|---|---|
| audio actually plays | `AudioPlaybackConfiguration state:started`, `USAGE_MEDIA`, 48 kHz stereo |
| correct service type | `isForeground=true types=0x00000002` (= `MEDIA_PLAYBACK`) |
| media notification | `category=transport actions=3 vis=PUBLIC` |
| lock screen controls | prev/pause/next **with the artwork the Librarian fetched** |
| background playback | position advanced 142791 → 148814 ms with the screen off |
| media buttons | `KEYCODE_MEDIA_NEXT` switches track (this is the Bluetooth path) |
| loudness — attenuate | `gain -4.84 dB -> volume=0.57` (10^(−4.84/20) = 0.573 ✓) |
| loudness — boost | `LoudnessEnhancer session 657: targetGain=126.0 mB, enabled=true` (1.26 dB × 100 ✓) |

Both loudness paths matter: −4.84 dB uses `ExoPlayer.volume`, +1.26 dB *cannot*
(volume caps at 1.0) and needs the platform effect. Only one track in the
library has positive gain, and it is what exercises that branch.

### Still not verified

- **Headphone-unplug auto-pause.** `AUDIO_BECOMING_NOISY` is a protected
  broadcast, so it cannot be faked from adb — it needs a physical unplug.
  `setHandleAudioBecomingNoisy(true)` is set, but that is not proof.
- **Bluetooth ↔ wired handoff mid-track**, and BT codec reporting.
- **Gapless / crossfade** between tracks.
- **Battery over a long session** — the plan's 3-hour screen-off run.
- **USB-OTG**: the phone reading the pendrive directly. That is Phase 2 and does
  not exist yet; today the library gets there via the push script.
