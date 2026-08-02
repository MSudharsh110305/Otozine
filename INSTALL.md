# Installing OtoZine

**`OtoZine.apk`** (28 MB) is in this folder. Signed, ready to transfer.

---

## 1. Install the app

Copy `OtoZine.apk` to the phone however you like — USB, Bluetooth, email to
yourself. Open it in the Files app and tap install. Android will ask you to
allow installing from that app the first time; that is expected for anything not
coming from the Play Store.

On first open it asks for two permissions. **Allow both** — neither is optional:

- **Notifications.** From Android 13 onward, no notification permission means no
  media notification, and no media notification means the playback service
  cannot run in the foreground. Playback stops when you leave the app.
- **Music and audio.** Without it the scan finds nothing — and it fails
  *silently*, so a missing permission looks exactly like a phone with no music
  on it.

**Once you allow audio access, the app plays straight away.** It scans the music
already on the phone and populates itself; there is nothing to import first.
Everything below is about adding the analysed library on top of that.

## 2. Get your music onto it

**Library → IMPORT LIBRARY**, then pick a folder. Three things work:

| what you pick | what happens |
|---|---|
| A folder of song files | The app reads the tags, parses the filenames and **builds its own library** |
| The pendrive (or its `OtoZine` folder) | Uses the prepared database — full analysis, loudness levelling, smart queues |
| A folder produced by `otozine stage` | Same as above |

Pointing at a plain folder of MP3s is fine and needs no PC. Those files are
referenced where they are, not copied, so nothing is duplicated.

What you give up without the Librarian: no volume levelling, no tempo or key,
and those tracks stay out of smart queues — sequencing on values we never
measured would degrade the queue for everything else. They play, they're
searchable, and running the Librarian later upgrades them.

There is also **More → Sources → Audio on this phone**, which picks up
everything in your music folders without choosing anything.

Re-import any time from the header pill or **More → Import library**. Your play
history survives it, deliberately: it lives in a separate database so
re-importing cannot wipe what the engine has learned.

## 3. Adding more music: the inbox

Copying songs onto the drive with a file manager does **not** add them to the
library. The master/Opus split, the loudness measurement and the mood analysis
all need ffmpeg, which means they need a PC — a drag-and-drop just leaves loose
files the app cannot see.

The inbox closes that gap:

```
G:\OtoZine\inbox\        <- drop songs here from anywhere
```

Then on a PC with the drive plugged in:

```powershell
.\.venv\Scripts\python.exe -m otozine.cli ingest --drive G:/ --consume
```

No `--from` needed — it processes the inbox. `--consume` clears the files
afterwards, but **only** once it has confirmed each one is archived on the
drive, verified by content hash rather than by filename.

With the portable bundle, double-clicking `otozine.bat` does exactly this.

Three ways in, all ending at the same place:

| from | how |
|---|---|
| **PC** | Copy into `OtoZine\inbox\`, or use `--from <folder>` directly |
| **Phone** | Long-press a track → **SEND TO DRIVE** |
| **Anywhere** | Any file manager, any OS — it is a plain folder |

## 4. Adding more music later

On the PC, with the drive plugged in:

```powershell
cd "music player\librarian"
.\.venv\Scripts\python.exe -m otozine.cli ingest --drive G:/ --from "path\to\new\songs"
```

Ingest is incremental — it skips everything it has already processed. Then
re-import on the phone.

## 5. Using a different pendrive

```powershell
.\.venv\Scripts\python.exe -m otozine.cli init --drive H:/
```

Creates the folder layout, an empty database and a config file, and tells you
roughly how many tracks will fit. Safe to re-run — it refuses to touch a drive
that already holds a library.

**On a PC that has never seen this project**, two options:

```powershell
# needs Python 3.11+; installs into a venv, leaves nothing on the drive
.\setup.ps1 -Drive H:/

# or build a bundle that needs nothing at all on the target machine
.\build-portable.ps1 -CopyTo H:\
```

The portable bundle puts `OtoZine-Librarian\otozine.bat` on the drive with
Python, ffmpeg and every dependency inside it (~300 MB). Double-click it on any
Windows PC — no install, no admin rights — and it works out which drive it is
sitting on automatically.

## 6. Streaming (optional)

`server/setup-navidrome.sh` provisions a free Oracle Cloud VM as a music
server: Navidrome behind Caddy with automatic HTTPS. Run it on the VM, then put
the URL and credentials into **More → Streaming server**.

Two things that trip people up, both handled or called out in the script:
Oracle's Ubuntu images have host firewall rules that block 80/443 even after you
open them in the cloud console, and Let's Encrypt cannot issue a certificate for
a bare IP so you need a domain (a free DuckDNS one is fine).

The Opus tier keeps its R128 gain tag through streaming — Navidrome passes Opus
through without re-encoding, so the loudness levelling survives.

---

## What works

Three tabs: **PLAY · LIBRARY · MORE**. Search lives inside Play and Library
rather than owning a tab — it is something you do to a list, not a place you go.

| | |
|---|---|
| **Playback** | Gapless, background, lock screen, Bluetooth buttons, headphone-unplug auto-pause |
| **Loudness** | Every library track normalised to −14 LUFS. Attenuation via player volume, boost via `LoudnessEnhancer` |
| **PLAY** | Search, continue card with its reason, Adventure slider, mood presets, stats |
| **LIBRARY** | Search, and a source switch between the curated library, audio on the phone, and the streaming server |
| **MORE** | Light/dark/system theme, sound map, import, device audio toggle, server setup, diagnostics |
| **Sound map** | Every track plotted by brightness against intensity. Tap a point to play from there |
| **Queue** | The engine's plan, with the reason each track was chosen |
| **Why this track** | Weighted reasons, and what the app thinks the track is |
| **Vibe pad** | Drag a point in mood space, queue rebuilds around it |
| **Sleep timer** | 15/30/45/60 min |
| **Import** | From a USB drive or internal storage, with progress |
| **Device audio** | Play anything already on the phone (More → Sources) |
| **Streaming** | Connect a Navidrome server and stream the whole library |

**The anti-repeat engine is real.** It records every (A → B) transition it
serves and refuses to serve them again, keeps a per-track cooldown that recovers
on a curve, spaces out composers and films, and prefers harmonically compatible
keys and small tempo steps between tracks. That was the original complaint —
not just repeated songs, but repeated *orders*.

## What is not there

- **No sound-alike search and no true sound map.** Both want CLAP embeddings,
  which need the ML ingest stages. The map plots real measured features instead,
  and says so.
- **Brightness is inferred, not measured** — from musical key, tempo and energy.
  It will occasionally be confidently wrong about a cheerful song in a minor key.
  Labelled as inferred wherever it is shown.
- **Play history does not sync back to the drive yet.** It is recorded and used
  on the phone, and queued for a merge that is not built.
- **No lyrics.** Nothing fetches them yet, so there is no screen for them rather
  than an empty one.
- **Tamil titles use the system font.** The bundled rounded face has no Tamil
  glyphs. Latin titles get the designed look, Tamil falls back and still reads
  fine.

## Known-untested

**None of this UI has run on a phone.** Both the device and the drive were
unplugged before I could install it, so everything above is compile- and
lint-verified only. The playback engine underneath *was* verified working on
your S24 FE earlier; the redesigned interface on top of it has not been.

Most likely to need adjusting: the neumorphic shadow radii. The values come
straight from the CSS mockup, and Android's blur does not render identically to
a browser's. If surfaces look flat or muddy, that is the first thing to change.

Minification is deliberately off in this build. R8 strips reflectively-reached
classes, and both Media3 and Compose use reflection — with no device to test a
shrunk build against, a smaller APK was not worth an app that crashes on first
play.
