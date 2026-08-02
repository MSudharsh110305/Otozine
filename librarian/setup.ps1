<#
.SYNOPSIS
    Set up the Librarian on a PC that has Python but no OtoZine environment.

.DESCRIPTION
    The lightweight alternative to the portable bundle: creates a virtual
    environment, installs dependencies, and checks for ffmpeg. Needs Python 3.11+
    and an internet connection, but adds nothing to the drive.

    Use the portable bundle when neither is available.

.EXAMPLE
    .\setup.ps1
    .\setup.ps1 -Drive G:\        # also initialise that drive when done
#>
[CmdletBinding()]
param([string]$Drive = "")

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Find-Python {
    foreach ($candidate in @("python", "python3", "py")) {
        $cmd = Get-Command $candidate -ErrorAction SilentlyContinue
        if (-not $cmd) { continue }
        $version = & $candidate -c "import sys; print('%d.%d' % sys.version_info[:2])" 2>$null
        if ($version -and [version]$version -ge [version]"3.11") { return $candidate }
    }
    return $null
}

$python = Find-Python
if (-not $python) {
    Write-Host "Python 3.11+ not found." -ForegroundColor Red
    Write-Host "Install it:  winget install Python.Python.3.12"
    Write-Host "Or use the portable bundle instead — it needs no Python at all."
    exit 1
}
Write-Host "==> using $python" -ForegroundColor Cyan

$venv = Join-Path $root ".venv"
if (-not (Test-Path $venv)) {
    Write-Host "==> creating virtual environment" -ForegroundColor Cyan
    & $python -m venv $venv
}

$venvPython = Join-Path $venv "Scripts\python.exe"
Write-Host "==> installing dependencies (a few minutes the first time)" -ForegroundColor Cyan
& $venvPython -m pip install --quiet --upgrade pip --disable-pip-version-check
& $venvPython -m pip install --quiet -e "$root" --disable-pip-version-check
if ($LASTEXITCODE -ne 0) { throw "dependency install failed" }

# ffmpeg is not optional -- every audio stage goes through it.
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    $bundled = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter ffmpeg.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $bundled) {
        Write-Host "==> installing ffmpeg" -ForegroundColor Cyan
        winget install --id Gyan.FFmpeg --accept-package-agreements --accept-source-agreements --disable-interactivity 2>&1 | Out-Null
    }
}

Write-Host "==> checking" -ForegroundColor Cyan
& $venvPython -m otozine.cli doctor --drive $(if ($Drive) { $Drive } else { $root })

if ($Drive) {
    Write-Host "==> initialising $Drive" -ForegroundColor Cyan
    & $venvPython -m otozine.cli init --drive $Drive
}

Write-Host ""
Write-Host "Ready. Use:" -ForegroundColor Green
Write-Host "  .\.venv\Scripts\python.exe -m otozine.cli ingest --drive G:/ --from `"path\to\music`""
