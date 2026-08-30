<#
.SYNOPSIS
    Builds, installs, launches, and captures evidence for Munitter Development Debug on an Android emulator.

.DESCRIPTION
    Discovers the Android SDK without hard-coded user paths, reuses or starts the requested AVD,
    waits for Android to finish booting, builds only developmentDebug, installs it, verifies the
    foreground activity and Development health endpoints, and writes sanitized evidence outside Git.
#>
[CmdletBinding()]
param(
    [string]$AvdName = "Munitter_Development_API_36",
    [string]$AndroidSdk = "",
    [string]$ArtifactsRoot = "",
    [int]$BootTimeoutSeconds = 300,
    [switch]$ColdBoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Path $PSScriptRoot -Parent
$appId = "com.munitter.android.development.debug"
$activity = "com.munitter.android.MainActivity"
$apk = Join-Path $repoRoot "app\build\outputs\apk\development\debug\app-development-debug.apk"
$developmentBaseUrl = "https://dev.munitter.com"

function Resolve-AndroidSdk {
    param([string]$Requested)

    $candidates = @($Requested, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk") }

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $resolved = [Environment]::ExpandEnvironmentVariables($candidate.Trim())
        if ((Test-Path (Join-Path $resolved "platform-tools\adb.exe")) -and
            (Test-Path (Join-Path $resolved "emulator\emulator.exe"))) {
            return (Resolve-Path $resolved).Path
        }
    }
    throw "Android SDK with adb and emulator was not found. Set ANDROID_HOME or pass -AndroidSdk."
}

function Wait-AdbDevice {
    param([string]$Adb, [string]$Serial, [datetime]$Deadline)

    do {
        $state = (& $Adb -s $Serial get-state 2>$null | Select-Object -First 1)
        if ($state -eq "device") { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $Deadline)
    throw "ADB device '$Serial' did not become ready before timeout."
}

function Get-EmulatorSerial {
    param([string]$Adb, [string]$ExpectedAvd)

    $serials = & $Adb devices | Select-String '^emulator-\d+\s+device$' | ForEach-Object {
        ($_ -split '\s+')[0]
    }
    foreach ($serial in $serials) {
        $runningAvd = (& $Adb -s $serial emu avd name 2>$null | Select-Object -First 1).Trim()
        if ($runningAvd -eq $ExpectedAvd) { return $serial }
    }
    return ""
}

$sdk = Resolve-AndroidSdk -Requested $AndroidSdk
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$avdManager = Join-Path $sdk "cmdline-tools\latest\bin\avdmanager.bat"
if (-not (Test-Path $avdManager)) { throw "avdmanager was not found under '$sdk'." }

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$avdList = (& $emulator -list-avds) | ForEach-Object { $_.Trim() }
if ($avdList -notcontains $AvdName) {
    throw "Required AVD '$AvdName' does not exist. Create the Wing-owned API 36 Google APIs AVD first."
}

$serial = Get-EmulatorSerial -Adb $adb -ExpectedAvd $AvdName
if ([string]::IsNullOrWhiteSpace($serial)) {
    $arguments = @('-avd', $AvdName, '-gpu', 'auto', '-netdelay', 'none', '-netspeed', 'full')
    if ($ColdBoot) { $arguments += '-no-snapshot-load' }
    Start-Process -FilePath $emulator -ArgumentList $arguments | Out-Null

    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        $serial = Get-EmulatorSerial -Adb $adb -ExpectedAvd $AvdName
    } while ([string]::IsNullOrWhiteSpace($serial) -and (Get-Date) -lt $deadline)
    if ([string]::IsNullOrWhiteSpace($serial)) { throw "AVD '$AvdName' did not register with adb." }
} else {
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
}

Wait-AdbDevice -Adb $adb -Serial $serial -Deadline $deadline
do {
    $bootComplete = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1).Trim()
    if ($bootComplete -ne '1') { Start-Sleep -Seconds 2 }
} while ($bootComplete -ne '1' -and (Get-Date) -lt $deadline)
if ($bootComplete -ne '1') { throw "Android did not finish booting before timeout." }

Push-Location $repoRoot
try {
    & (Join-Path $repoRoot 'tools\Invoke-MunitterAndroidSigning.ps1') -Mode Gradle -GradleArguments @(
        ':app:assembleDevelopmentDebug', '--no-daemon', '--console=plain'
    )
    if ($LASTEXITCODE -ne 0) { throw "Development Debug build failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}
if (-not (Test-Path $apk)) { throw "Expected APK was not produced: $apk" }

& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed with exit code $LASTEXITCODE." }
& $adb -s $serial logcat -c
& $adb -s $serial shell am force-stop $appId
& $adb -s $serial shell am start -n "$appId/$activity" | Out-Null
Start-Sleep -Seconds 10
Wait-AdbDevice -Adb $adb -Serial $serial -Deadline ((Get-Date).AddSeconds(60))

$resumed = (& $adb -s $serial shell dumpsys activity activities | Select-String 'topResumedActivity' | Select-Object -First 1).Line
if ($resumed -notlike "*$appId/$activity*") { throw "Development activity is not foreground: $resumed" }

$health = [ordered]@{}
foreach ($path in @('live', 'health', 'ready')) {
    $response = Invoke-WebRequest -Uri "$developmentBaseUrl/$path" -Method Get -UseBasicParsing -TimeoutSec 20
    $health[$path] = $response.StatusCode
    if ($response.StatusCode -ne 200) { throw "Development /$path returned HTTP $($response.StatusCode)." }
}

if ([string]::IsNullOrWhiteSpace($ArtifactsRoot)) {
    $ArtifactsRoot = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'MunitterAndroidArtifacts\smoke'
}
$runDir = Join-Path $ArtifactsRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$remoteScreenshot = "/sdcard/munitter-development-smoke.png"
$screenshot = Join-Path $runDir 'development-home.png'
& $adb -s $serial shell screencap -p $remoteScreenshot | Out-Null
& $adb -s $serial pull $remoteScreenshot $screenshot | Out-Null
& $adb -s $serial shell rm $remoteScreenshot | Out-Null

$logPath = Join-Path $runDir 'logcat-sanitized.txt'
$rawLog = & $adb -s $serial logcat -d -v threadtime
$sanitized = $rawLog |
    Where-Object { $_ -notmatch '(?i)cookie|authorization|token|password|set-cookie' } |
    ForEach-Object {
        $_ -replace '(?i)(https?://[^/\s]+)(/[^\s?]*)?(\?[^\s]*)?', '$1/<path-redacted>'
    }
[System.IO.File]::WriteAllLines($logPath, [string[]]$sanitized)

$result = [ordered]@{
    success = $true
    avd = $AvdName
    serial = $serial
    applicationId = $appId
    activity = $activity
    variant = 'developmentDebug'
    baseUrl = $developmentBaseUrl
    health = $health
    screenshot = $screenshot
    sanitizedLogcat = $logPath
}
$resultPath = Join-Path $runDir 'result.json'
[System.IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Depth 4))
$result | ConvertTo-Json -Depth 4
