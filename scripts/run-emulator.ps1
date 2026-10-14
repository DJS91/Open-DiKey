<#
.SYNOPSIS
  Builds the debug APK, starts the emulator if needed, installs the app and launches it.

.DESCRIPTION
  Run with no parameters for an interactive prompt (press Enter to accept each default).
  Pass any parameter to skip the prompts.

.EXAMPLE
  .\scripts\run-emulator.ps1
  .\scripts\run-emulator.ps1 -SkipBuild
  .\scripts\run-emulator.ps1 -Avd OpenDiKey_Test -Fresh
#>

$Avd = 'OpenDiKey_Test'
# Reuse the existing APK instead of running Gradle.
$SkipBuild
# Uninstall first, so the app starts with no saved data.
$Fresh
$BootTimeoutSec = 180


$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$package = 'com.sphy.airconcontroller'
$apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'

foreach ($tool in @($adb, $emulator)) {
    if (-not (Test-Path $tool)) { throw "Not found: $tool (is the Android SDK installed at $env:ANDROID_HOME?)" }
}

function Get-OnlineEmulator {
    (& $adb devices) | Where-Object { $_ -match '^emulator-\d+\s+device$' } | Select-Object -First 1
}

function Read-YesNo([string]$Question, [bool]$Default) {
    $hint = if ($Default) { 'Y/n' } else { 'y/N' }
    while ($true) {
        $answer = (Read-Host "$Question [$hint]").Trim().ToLower()
        if ($answer -eq '') { return $Default }
        if ($answer -in @('y', 'yes')) { return $true }
        if ($answer -in @('n', 'no')) { return $false }
        Write-Host 'Please answer y or n.'
    }
}

# No parameters given: ask what to do instead of using the defaults silently.
if ($PSBoundParameters.Count -eq 0) {
    Write-Host 'Open DiKey - run on emulator' -ForegroundColor Cyan

    $avds = @(& $emulator -list-avds | Where-Object { $_ })
    if ($avds.Count -eq 0) { throw 'No emulators found. Create one in Android Studio (Device Manager) first.' }
    if ($avds.Count -eq 1) {
        $Avd = $avds[0]
        Write-Host "Emulator: $Avd"
    } else {
        for ($i = 0; $i -lt $avds.Count; $i++) { Write-Host "  [$($i + 1)] $($avds[$i])" }
        while ($true) {
            $pick = (Read-Host "Emulator number [1]").Trim()
            if ($pick -eq '') { $pick = '1' }
            if ($pick -match '^\d+$' -and [int]$pick -ge 1 -and [int]$pick -le $avds.Count) {
                $Avd = $avds[[int]$pick - 1]
                break
            }
            Write-Host "Enter a number from 1 to $($avds.Count)."
        }
    }

    if (Test-Path $apk) {
        $SkipBuild = -not (Read-YesNo 'Rebuild the APK first?' $true)
    } else {
        Write-Host 'No APK built yet - it will be built first.'
    }
    $Fresh = Read-YesNo 'Uninstall first for a clean start (erases app data)?' $false
    Write-Host ''
}

if (-not $SkipBuild) {
    Write-Host '==> Building debug APK' -ForegroundColor Cyan
    Push-Location $repo
    try {
        & .\gradlew.bat :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path $apk)) { throw "APK not found: $apk (run without -SkipBuild)" }

if (-not (Get-OnlineEmulator)) {
    Write-Host "==> Starting emulator '$Avd'" -ForegroundColor Cyan
    if (-not ((& $emulator -list-avds) -contains $Avd)) {
        throw "No emulator named '$Avd'. Available: $((& $emulator -list-avds) -join ', ')"
    }
    # Start-Process keeps the emulator running after this script ends.
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $Avd) | Out-Null

    Write-Host '==> Waiting for the emulator to boot' -ForegroundColor Cyan
    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
    while ($true) {
        if ((Get-Date) -gt $deadline) { throw "Emulator did not finish booting within $BootTimeoutSec s" }
        if (Get-OnlineEmulator) {
            # adb can error out while the emulator is still coming up; treat that as "not ready yet".
            $booted = try { (& $adb shell getprop sys.boot_completed) -join '' } catch { '' }
            if ($booted.Trim() -eq '1') { break }
        }
        Start-Sleep -Seconds 2
    }
} else {
    Write-Host '==> Emulator already running' -ForegroundColor Cyan
}

if ($Fresh) {
    Write-Host '==> Uninstalling existing app (-Fresh)' -ForegroundColor Cyan
    & $adb uninstall $package | Out-Null
}

Write-Host '==> Installing APK' -ForegroundColor Cyan
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }

Write-Host '==> Launching app' -ForegroundColor Cyan
& $adb shell am start -n "$package/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw 'Failed to launch the app' }

$version = (& $adb shell dumpsys package $package | Select-String 'versionName=' | Select-Object -First 1).Line.Trim()
Write-Host "==> Done. Installed: $version" -ForegroundColor Green
