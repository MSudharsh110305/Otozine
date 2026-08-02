<#
.SYNOPSIS
    Build a self-contained Librarian that runs from a pendrive on any Windows PC.

.DESCRIPTION
    Produces `dist/OtoZine-Librarian/` containing otozine.exe, ffmpeg and every
    Python dependency. No Python, no venv, no install on the target machine —
    which is the point: a new pendrive should be usable on a friend's laptop or
    a lab PC without administrator rights.

    Copy the folder to the drive root and run `otozine.exe` from there.

.EXAMPLE
    .\build-portable.ps1
    .\build-portable.ps1 -CopyTo G:\
#>
[CmdletBinding()]
param(
    [string]$CopyTo = "",
    [switch]$SkipFfmpeg
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$python = Join-Path $root ".venv\Scripts\python.exe"
$out = Join-Path $root "dist\OtoZine-Librarian"

if (-not (Test-Path $python)) {
    throw "no venv found. Run: python -m venv .venv; .\.venv\Scripts\python.exe -m pip install -e ."
}

Write-Host "==> installing build dependencies" -ForegroundColor Cyan
& $python -m pip install --quiet --disable-pip-version-check pyinstaller
& $python -m pip install --quiet --disable-pip-version-check -e ".[fingerprint]" 2>&1 | Out-Null

Write-Host "==> building executable" -ForegroundColor Cyan
# --collect-all for librosa and its numba/llvmlite stack: PyInstaller's static
# analysis cannot see their lazily-imported submodules or their data files, and
# the result fails at runtime rather than at build time.
& $python -m PyInstaller `
    --noconfirm --clean `
    --name otozine `
    --distpath (Join-Path $root "dist") `
    --workpath (Join-Path $root "build\pyinstaller") `
    --specpath (Join-Path $root "build") `
    --collect-all librosa `
    --collect-all soxr `
    --collect-all lazy_loader `
    --collect-data otozine `
    --hidden-import sklearn.utils._typedefs `
    --hidden-import sklearn.neighbors._partition_nodes `
    (Join-Path $root "otozine\__main__.py")

if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed" }

# PyInstaller names the folder after the entry script.
$built = Join-Path $root "dist\otozine"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
Move-Item $built $out

# --- ffmpeg ----------------------------------------------------------------
if (-not $SkipFfmpeg) {
    Write-Host "==> bundling ffmpeg" -ForegroundColor Cyan
    $binDir = Join-Path $out "bin"
    New-Item -ItemType Directory -Force $binDir | Out-Null

    $ffmpeg = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source
    if (-not $ffmpeg) {
        $found = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter ffmpeg.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $ffmpeg = $found.FullName }
    }
    if ($ffmpeg) {
        $src = Split-Path $ffmpeg
        Copy-Item (Join-Path $src "ffmpeg.exe")  $binDir -Force
        Copy-Item (Join-Path $src "ffprobe.exe") $binDir -Force
        Write-Host "    from $src"
    } else {
        Write-Warning "ffmpeg not found — the bundle will need it on PATH. Install: winget install Gyan.FFmpeg"
    }
}

# --- launcher --------------------------------------------------------------
@'
@echo off
rem Portable OtoZine Librarian. Run from the drive; no install needed.
setlocal
set "HERE=%~dp0"
set "OTOZINE_FFMPEG=%HERE%bin\ffmpeg.exe"
set "OTOZINE_FFPROBE=%HERE%bin\ffprobe.exe"

rem The drive is whatever this folder sits on.
set "DRIVE=%~d0\"

rem Double-clicked with no arguments: process whatever is in the inbox. That is
rem what a transfer looks like -- copy songs onto the drive, plug it into a PC,
rem run this. Everything else is a named command.
if "%~1"=="" (
  echo OtoZine Librarian  --  portable
  echo Drive detected as %DRIVE%
  echo.
  echo Processing OtoZine\inbox ...
  echo.
  "%HERE%otozine.exe" ingest --drive "%DRIVE%" --consume
  echo.
  echo   otozine init                  prepare this drive
  echo   otozine ingest --from FOLDER  add music from elsewhere
  echo   otozine stats                 what is on here
  echo   otozine stage --out FOLDER    build a phone copy
  echo.
  pause
  goto :eof
)

"%HERE%otozine.exe" %* --drive "%DRIVE%"
'@ | Set-Content -Path (Join-Path $out "otozine.bat") -Encoding ASCII

$size = [math]::Round((Get-ChildItem $out -Recurse | Measure-Object Length -Sum).Sum / 1MB, 0)
Write-Host "==> built: $out  ($size MB)" -ForegroundColor Green

if ($CopyTo) {
    $target = Join-Path $CopyTo "OtoZine-Librarian"
    Write-Host "==> copying to $target" -ForegroundColor Cyan
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Copy-Item $out $target -Recurse
    Write-Host "==> done. Run $target\otozine.bat on any Windows PC." -ForegroundColor Green
}
