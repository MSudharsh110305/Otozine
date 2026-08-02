"""Regression tests for filename metadata recovery.

These lock in the behaviour for the filename shapes that actually show up in a
downloaded Tamil + English library. Every case here was a real bug at some
point; keep them passing.
"""

import json
from pathlib import Path

import pytest

from otozine.stages.nameparse import (
    _DATA_FILE,
    _detect_language,
    _key,
    _romanised_tamil_score,
    _tables,
    parse,
)


def p(raw: str):
    return parse(Path(raw))


# --------------------------------------------------------------- Tamil rips

def test_strips_site_watermark_without_leaving_tld():
    """Removing 'isaimini' must not orphan '.com' into the title."""
    r = p(r"D:\Music\[Isaimini.com] Vaathi Coming - Master - Anirudh Ravichander (2020) 320kbps.mp3")
    assert r.title == "Vaathi Coming"
    assert r.artist == "Anirudh Ravichander"
    assert r.album == "Master"
    assert r.year == 2020


def test_spaced_underscore_is_a_separator():
    """YouTube-rip tooling substitutes '_' for '|'."""
    r = p(r"D:\Music\Vaathi Coming _ Master _ Thalapathy Vijay _ Anirudh.mp3")
    assert r.title == "Vaathi Coming"
    assert r.album == "Master"
    assert r.artist == "Anirudh Ravichander"


def test_unspaced_underscore_is_not_a_separator():
    r = p(r"D:\Music\random_file_2847.mp3")
    assert r.title == "Random File 2847"
    assert r.confidence < 0.5


def test_leading_track_number():
    r = p(r"D:\Music\01 - Ennai Vidaadhe - Naanum Rowdy Dhaan - Anirudh.mp3")
    assert r.track_no == 1
    assert r.title == "Ennai Vidaadhe"
    assert r.album == "Naanum Rowdy Dhaan"


def test_singer_preferred_over_composer_as_artist():
    """Both roles present: the vocalist is the artist, the composer is separate."""
    r = p(r"D:\Music\Munbe Vaa - Sillunu Oru Kaadhal - A.R. Rahman - Shreya Ghoshal.mp3")
    assert r.title == "Munbe Vaa"
    assert r.artist == "Shreya Ghoshal"
    assert r.composer == "A. R. Rahman"
    assert r.album == "Sillunu Oru Kaadhal"
    assert r.confidence >= 0.9


def test_alias_resolves_to_canonical_spelling():
    r = p(r"D:\Music\Ilayaraja - Ilamai Itho Itho - Sakalakala Vallavan.mp3")
    assert r.composer == "Ilaiyaraaja"      # not the 'Ilayaraja' spelling on disk


def test_song_movie_order_without_any_alias_hit():
    """No known name anywhere; orthography alone has to break the tie."""
    r = p(r"D:\Music\Nenjukkul Peidhidum (Official Video) [320Kbps] - Vaaranam Aayiram.mp3")
    assert r.title == "Nenjukkul Peidhidum"
    assert r.album == "Vaaranam Aayiram"
    assert r.language_hint == "ta"


def test_native_tamil_script_survives():
    r = p("D:\\Music\\\u0b95\u0ba3\u0bcd\u0ba3\u0bc7 \u0b95\u0bb2\u0bc8\u0bae\u0bbe\u0ba9\u0bc7 - Moondram Pirai.mp3")
    assert r.language_hint == "ta"
    assert "\u0b95" in (r.title or "")
    assert r.album == "Moondram Pirai"


# ------------------------------------------------------------- Western rips

@pytest.mark.parametrize(
    "raw, artist, title",
    [
        (r"D:\Downloads\Radiohead - Karma Police (Official Music Video).mp3", "Radiohead", "Karma Police"),
        (r"D:\Downloads\Tame Impala - The Less I Know The Better [Official Audio] 320kbps.m4a",
         "Tame Impala", "The Less I Know The Better"),
    ],
)
def test_western_artist_title_order(raw, artist, title):
    r = p(raw)
    assert r.artist == artist
    assert r.title == title


def test_folder_supplies_album_including_two_letter_names():
    r = p(r"D:\Music\Arctic Monkeys\AM\Do I Wanna Know.flac")
    assert r.title == "Do I Wanna Know"
    assert r.album == "AM"


def test_generic_folder_is_not_treated_as_album():
    r = p(r"D:\Downloads\Do I Wanna Know.flac")
    assert r.album is None


def test_numbered_track_in_album_folder():
    r = p(r"D:\Music\Daft Punk\Discovery\04. Daft Punk - Veridis Quo.mp3")
    assert r.track_no == 4
    assert r.title == "Veridis Quo"
    assert r.artist == "Daft Punk"


def test_feat_and_from_album_are_both_extracted():
    r = p(r"D:\Music\Titli (From 'Chennai Express') - A.R. Rahman feat. Chinmayi.mp3")
    assert r.title == "Titli"
    assert r.album == "Chennai Express"
    assert r.composer == "A. R. Rahman"
    assert r.artist == "Chinmayi"


def test_acronym_album_is_not_title_cased():
    """'AM' must not become 'Am'. Same for IV, OK, ABBA, MGMT."""
    r = p(r"D:\Music\Arctic Monkeys\MGMT\Kids.mp3")
    assert r.album == "MGMT"


# --------------------------------------------- language detection (2 paths)
# The orthographic scorer generalises to words we have never seen; the marker
# list covers high-frequency words that carry no distinctive orthography.
# Neither alone is sufficient, so assert on the combined entry point.

@pytest.mark.parametrize("title", [
    "Nenjukkul Peidhidum",   # scorer: nj, kk, dh, -um
    "Vaaranam Aayiram",      # scorer: doubled vowels, -am
    "Kaadhal Rojave",        # scorer: aa, dh
    "Uyire Uyire",           # marker list: no distinctive digraph at all
    "Anbe Vaa",              # marker list
])
def test_language_detection_accepts_tamil(title):
    assert _detect_language(title, [title]) == "ta"


@pytest.mark.parametrize("title", [
    "Karma Police",
    "The Less I Know The Better",
    "Veridis Quo",
    "Do I Wanna Know",
    "Bohemian Rhapsody",
])
def test_language_detection_rejects_english(title):
    assert _detect_language(title, [title]) is None


@pytest.mark.parametrize("text", ["Nenjukkul Peidhidum", "Vaaranam Aayiram", "Kaadhal Rojave"])
def test_scorer_generalises_without_the_word_list(text):
    assert _romanised_tamil_score(text) >= 0.9


def test_parse_never_raises_on_hostile_input():
    """A 3000-track ingest must not die on one weird filename."""
    for raw in ["", "   ", "...", "---", "1", "[]", "()", "___", "a" * 300, "🎵🎶"]:
        parse(Path(f"D:/Music/{raw}.mp3"))


# ------------------------------------------------------- alias table health

def test_no_alias_maps_to_two_different_people():
    """A duplicated alias silently corrupts canonicalisation.

    Adding a standalone 'Anirudh' entry alongside 'Anirudh Ravichander' once
    made the alias 'anirudh' resolve to whichever the dict happened to write
    last -- so a correctly-parsed name started coming out truncated.
    """
    raw = json.loads(_DATA_FILE.read_text(encoding="utf-8"))

    for section in ("music_directors", "singers"):
        owners: dict[str, list[str]] = {}
        for canonical, aliases in raw[section].items():
            for alias in [canonical, *aliases]:
                owners.setdefault(_key(alias), []).append(canonical)

        collisions = {a: sorted(set(o)) for a, o in owners.items() if len(set(o)) > 1}
        assert not collisions, f"{section} has ambiguous aliases: {collisions}"


def test_delimiter_phrases_are_protected_from_deletion():
    """`split_junk` must survive every deletion pass or segmentation breaks.

    'Video Song' sits between the song title and the film name. Deleting it as
    noise -- which two separate passes used to do -- erases the only structure
    a YouTube-rip filename has.
    """
    tables = _tables()
    for phrase in tables["split_junk"]:
        assert _key(phrase) in tables["protected"], f"{phrase!r} is not protected"


# ---------------------------------------------- real YouTube-rip filenames
# Every case below is an actual file from the user's library. These are the
# shapes that matter: fields run together with no delimiters, and a junk phrase
# ('Video Song') is the only thing marking where the song title ends.

REAL_FILES = [
    ("Pakkam Vanthu - Video Song Kaththi Vijay Samantha Ruth Prabhu Anirudh Ravichander.mp3",
     "Pakkam Vanthu", "Kaththi", "Anirudh Ravichander"),
    ("Selfie Pulla - Video Song Kaththi Vijay Samantha Anirudh Ravichander A. R. Murugadoss.mp3",
     "Selfie Pulla", "Kaththi", "Anirudh Ravichander"),
    ("Maari - Don't Don't Don't Video Dhanush, Kajal Anirudh Super Hit Song.mp3",
     "Don't Don't Don't", "Maari", "Anirudh Ravichander"),
    ("Yaan - Aathangara Orathil Video Jiiva Harris Jayaraj Super Hit Tamil Song.mp3",
     "Aathangara Orathil", "Yaan", "Harris Jayaraj"),
    ("Theeratha Vilayattu Pillai - En Jannal Vandha Video Yuvanshankar Raja Vishal.mp3",
     "En Jannal Vandha", "Theeratha Vilayattu Pillai", "Yuvan Shankar Raja"),
    ("Maattrraan - Theeyae Theeyae Video Suriya, Kajal Agarwal.mp3",
     "Theeyae Theeyae", "Maattrraan", None),
    ("Kaadhal Ennulle Official Video Song Neram (Tamil) Nivin Pauly Nazriya Nazim Alphonse Puthren.mp3",
     "Kaadhal Ennulle", "Neram", None),
    ("Unnaal Unnaal Un Ninaivaal Full Video Song M.S.Dhoni Tamil Sushant Singh Rajput, Kiara Advani.mp3",
     "Unnaal Unnaal Un Ninaivaal", "M.S.Dhoni", None),
    ("Hangova - Music Video DC Lokesh Kanagaraj Sun Pictures Anirudh Arun Matheswaran Wamiqa Gabbi.mp3",
     "Hangova", "DC", "Anirudh Ravichander"),
    ("Adiye Video Song Bachelor G.V. Prakash Kumar Dhibu Ninan Thomas Sathish G Dilli Babu.mp3",
     "Adiye", "Bachelor", "G. V. Prakash Kumar"),
    ("Ennai Vittu -Kannum Kannum Kollaiyadithaal Dulquer S,Ritu V Ranjith,Vignesh S,Masala Coffee Tamil.mp3",
     "Ennai Vittu", "Kannum Kannum Kollaiyadithaal", "Masala Coffee"),
    ("Verappa Extended - Video Song Suriyas Karuppu RJ Balaji @SaiAbhyankkar Dream Warrior Pictures.mp3",
     "Verappa", "Suriyas Karuppu", "Sai Abhyankkar"),
    ("ytmp3free.cc_aathi-video-song-kaththi-vijay-samantha-ruth-prabhu-anirudh-ravichander.mp3",
     "Aathi", "Kaththi", "Anirudh Ravichander"),
]


@pytest.mark.parametrize("filename, title, album, composer", REAL_FILES)
def test_real_youtube_rip_filenames(filename, title, album, composer):
    r = parse(Path(f"D:/songs/{filename}"))
    assert r.title == title
    assert r.album == album
    if composer is not None:
        assert r.composer == composer


def test_independent_single_has_no_album():
    """A non-film release must not have a film invented for it."""
    r = parse(Path(
        "D:/songs/@SaiAbhyankkar - Pavazha Malli (Music Video) "
        "Kayadu Shruti Haasan Vivek Thejo Think Indie.mp3"
    ))
    assert r.title == "Pavazha Malli"
    assert r.artist == "Sai Abhyankkar"
    assert r.album is None


def test_channel_handle_is_not_mistaken_for_a_song():
    r = parse(Path("D:/songs/@SomeUnknownChannel - Real Song Title.mp3"))
    assert r.title == "Real Song Title"
    assert r.artist == "SomeUnknownChannel"
