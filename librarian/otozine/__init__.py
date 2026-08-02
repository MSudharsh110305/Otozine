"""OTOZINE Librarian -- the ingest pipeline for a portable music library.

Runs on a PC, writes everything to the pendrive. The Android player only ever
reads what this tool produces, so all expensive work (fingerprinting, ML
tagging, loudness analysis, transcoding) happens here exactly once per track.
"""

__version__ = "0.1.0"
