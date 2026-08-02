"""Entry point for `python -m otozine` and for the PyInstaller bundle."""

from .cli import main

if __name__ == "__main__":
    raise SystemExit(main())
