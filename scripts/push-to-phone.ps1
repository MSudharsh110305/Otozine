<#
.SYNOPSIS
    Stage a subset of the pendrive library and push it to the phone over adb.

.DESCRIPTION
    A developer convenience, not the real sync. Phase 2 replaces this with the
    phone reading the drive directly over USB-OTG (Storage Access Framework),
    with play history merging back. Until then this does the same job manually.

    Steps:
      1. `otozine stage`  picks what fits the budget and lays it out
      2. tar it, so 14+ files transfer as one blob with paths intact
      3. unpack inside the app's private storage via `run-as` (debug builds only)

.EXAMPLE
    .\scripts\push-to-phone.ps1 -Drive G:\
    .\scripts\push-to-phone.ps1 -Drive G:\ -Budget 4GB -Restart
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Drive,
    [string]$Budget = "12GB",
    [string]$Package = "net.otozine.player.debug",
    [switch]$Restart
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$python = Join-Path $root "librarian\.venv\Scripts\python.exe"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$staging = Join-Path $env:TEMP "otozine-stage"
$tarball = Join-Path $env:TEMP "otozine-lib.tar"

foreach ($tool in @($python, $adb)) {
    if (-not (Test-Path $tool)) { throw "not found: $tool" }
}

# The drive letter must not be passed with a trailing backslash: PowerShell
# hands `"G:\"` to a native exe as `G:"`, because the backslash escapes the
# quote. Normalising to a forward slash sidesteps it entirely.
$driveArg = $Drive.TrimEnd('\', '/') + "/"

$devices = & $adb devices | Select-String "\sdevice$"
if (-not $devices) { throw "no authorised device. Check the USB debugging prompt on the phone." }

Write-Host "==> staging from $driveArg (budget $Budget)" -ForegroundColor Cyan
& $python -m otozine.cli stage --drive $driveArg --out $staging --budget $Budget --clean
if ($LASTEXITCODE -ne 0) { throw "staging failed" }

Write-Host "==> packing" -ForegroundColor Cyan
Push-Location $staging
try { tar -cf $tarball . } finally { Pop-Location }
$mb = [math]::Round((Get-Item $tarball).Length / 1MB, 1)
Write-Host "    $mb MB"

Write-Host "==> pushing" -ForegroundColor Cyan
& $adb push $tarball /data/local/tmp/otozine-lib.tar | Out-Null

Write-Host "==> unpacking into app storage" -ForegroundColor Cyan
# Wipe first: a stale track left behind would still show in the list and then
# fail to play, which looks like a playback bug rather than a sync one.
& $adb shell "run-as $Package sh -c 'rm -rf files/library && mkdir -p files/library && cd files/library && tar xf /data/local/tmp/otozine-lib.tar'"
& $adb shell "rm -f /data/local/tmp/otozine-lib.tar"

$count = (& $adb shell "run-as $Package find files/library -name '*.opus'" | Measure-Object -Line).Lines
Write-Host "==> $count tracks on device" -ForegroundColor Green

if ($Restart) {
    & $adb shell am force-stop $Package
    & $adb shell am start -n "$Package/net.otozine.player.MainActivity" | Out-Null
    Write-Host "==> app restarted" -ForegroundColor Green
}
