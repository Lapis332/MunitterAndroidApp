<#  
    Invoke-A57DevelopmentSmoke.ps1
    Purpose:
      Development Debug APK build + A57 cold launch + screenshot + log + anomaly check
      for com.munitter.android.provisional.development.debug
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$AdbPath = "",
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SkipScreenshot,
    [switch]$RunBasicActions,
    [Nullable[int]]$TapX,
    [Nullable[int]]$TapY,
    [Nullable[int]]$SwipeX1,
    [Nullable[int]]$SwipeY1,
    [Nullable[int]]$SwipeX2,
    [Nullable[int]]$SwipeY2,
    [int]$SwipeDurationMs = 300
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Path $PSScriptRoot -Parent
Set-Location $repoRoot
$projectTag = "A57 Development Smoke"
$artifactDir = Join-Path $repoRoot "artifacts\device-test"
$remoteTempDir = "/sdcard"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$expectedAppId = "com.munitter.android.provisional.development.debug"
$expectedLauncherActivity = "com.munitter.android.MainActivity"
$apkRelativePath = "app\build\outputs\apk\development\debug\app-development-debug.apk"
$apkPath = Join-Path $repoRoot $apkRelativePath
$expectedDeviceModel = "SM_A576Q"
$expectedDeviceCode = "a57x"

function Write-StepResult {
    param([string]$Title, [string]$Value, [string]$Status = "PASS")
    Write-Host ("[{0}] {1}: {2}" -f $Status, $Title, $Value)
}

function Fail-Script {
    param(
        [string]$Step,
        [int]$ExitCode,
        [string]$Reason,
        [string[]]$Evidence
    )

    Write-Host ""
    Write-Host "A57 Development Smoke: FAIL"
    Write-Host "FailedStep: $Step"
    Write-Host ("ExitCode: {0}" -f $ExitCode)
    Write-Host "Reason: $Reason"
    if ($Evidence -and $Evidence.Count -gt 0) {
        Write-Host "Evidence:"
        $Evidence | ForEach-Object { Write-Host ("  - $_") }
    }
    exit $ExitCode
}

function Resolve-CommandPath {
    param(
        [string]$Path
    )
    if (-not $Path) {
        return ""
    }
    if (Test-Path $Path) {
        return $Path
    }
    return ""
}

function Invoke-ADB {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [switch]$IgnoreError
    )
    $command = @("&", "`"$Adb`"") + $Arguments
    $output = & $Adb @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0 -and -not $IgnoreError) {
        throw "ADB command failed ($Arguments): exit=$exitCode"
    }
    return @{Output=$output; ExitCode=$exitCode}
}

function Has-OnlyTarget($line, [string]$targetApp) {
    return $line -like "*$targetApp*"
}

function Has-CrashSignature($line, [string[]]$criticalPatterns, [string[]]$ignorePatterns) {
    if (-not $line) { return $false }
    foreach ($ignore in $ignorePatterns) {
        if ($line -match $ignore) {
            return $false
        }
    }
    foreach ($pattern in $criticalPatterns) {
        if ($line -match $pattern) {
            return $true
        }
    }
    return $false
}

try {
    Write-Host "===== Git status ====="
    $branch = (git -C $repoRoot rev-parse --abbrev-ref HEAD).Trim()
    $headSha = (git -C $repoRoot rev-parse HEAD).Trim()
    $gitStatus = git -C $repoRoot status --short --branch
    Write-Host "branch: $branch"
    Write-Host "HEAD: $headSha"
    if ($gitStatus) { $gitStatus | ForEach-Object { Write-Host $_ } } else { Write-Host "(clean)" }

    Write-Host ""
    Write-Host "===== Resolve adb path ====="
    $resolvedAdb = ""
    if ($AdbPath) {
        $resolvedAdb = Resolve-CommandPath -Path $AdbPath
        if (-not $resolvedAdb) {
            Fail-Script -Step "ADB path" -ExitCode 2 -Reason "Explicit AdbPath does not exist: $AdbPath" -Evidence @()
        }
    }

    if (-not $resolvedAdb) {
        $candidateFromEnv = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        $resolvedAdb = Resolve-CommandPath -Path $candidateFromEnv
    }
    if (-not $resolvedAdb) {
        $resolvedAdb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    }
    if (-not $resolvedAdb) {
        Fail-Script -Step "ADB path" -ExitCode 2 -Reason "adb could not be resolved from explicit arg, $env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe, or PATH." -Evidence @()
    }
    Write-StepResult -Title "ADB" -Value $resolvedAdb

    Write-Host ""
    Write-Host "===== Resolve target device ====="
    $devices = Invoke-ADB -Adb $resolvedAdb -Arguments @("devices", "-l")
    $deviceLines = @($devices.Output | Where-Object { $_ -match "^\S+\s+device" })
    $matched = @()
    foreach ($line in $deviceLines) {
        if ($line -notmatch "^([\w\.-]+)\s+device\b") { continue }
        $serialCandidate = $Matches[1]
        if ($line -match "model:([^ ]+)" -and $line -match "device:([^ ]+)") {
            $model = $Matches[1]
            if ($line -match "device:([^ ]+)") { $deviceName = $Matches[1] }
        } else {
            continue
        }
        if ($model -eq $expectedDeviceModel -and $deviceName -eq $expectedDeviceCode) {
            $matched += [pscustomobject]@{
                Serial = $serialCandidate
                Model = $model
                Device = $deviceName
                Line = $line
            }
        }
    }
    if ($Serial) {
        $matched = $matched | Where-Object { $_.Serial -eq $Serial }
        if (-not $matched) {
            Fail-Script -Step "Device filter" -ExitCode 3 -Reason "Serial was provided but did not pass safety gate: $Serial" -Evidence @($Serial)
        }
    }
    if ($matched.Count -eq 0) {
        Fail-Script -Step "Device filter" -ExitCode 3 -Reason "No matching device found (state=device, model=$expectedDeviceModel, device=$expectedDeviceCode)." -Evidence @($devices.Output)
    }
    if ($matched.Count -gt 1) {
        $found = $matched | ForEach-Object { $_.Serial }
        Fail-Script -Step "Device filter" -ExitCode 3 -Reason "More than one matching device found; ambiguous." -Evidence @($found)
    }

    $targetSerial = $matched[0].Serial
    Write-StepResult -Title "Target serial" -Value $targetSerial

    if (-not $SkipBuild) {
        Write-Host ""
        Write-Host "===== Build: :app:assembleDevelopmentDebug ====="
        $buildOutput = & "$repoRoot\gradlew.bat" ":app:assembleDevelopmentDebug" "--no-daemon" "--stacktrace" 2>&1
        $buildExit = $LASTEXITCODE
        if ($VerbosePreference -ne 'SilentlyContinue') {
            $buildOutput | ForEach-Object { Write-Host $_ }
        }
        if ($buildExit -ne 0) {
            $importantBuildErrors = $buildOutput | Select-String -Pattern "FAILURE:|BUILD FAILED|Exception|Error"
            Fail-Script -Step "Build" -ExitCode 4 -Reason "gradlew assembleDevelopmentDebug failed." -Evidence @($importantBuildErrors | ForEach-Object { $_.Line })
        }
        Write-StepResult -Title "Build" -Value "OK"
    }
    else {
        Write-StepResult -Title "Build" -Value "SKIPPED"
    }

    if (-not (Test-Path $apkPath)) {
        Fail-Script -Step "APK path" -ExitCode 5 -Reason "Expected APK not found." -Evidence @($apkPath)
    }
    Write-StepResult -Title "APK path" -Value $apkPath

    $aapt2Paths = @()
    $buildToolsRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools"
    if (Test-Path $buildToolsRoot) {
        $aapt2Paths += Get-ChildItem -Directory -Path $buildToolsRoot | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "aapt2.exe" } | Where-Object { Test-Path $_ }
    }
    $aapt2 = $aapt2Paths | Select-Object -First 1
    $pkgName = $versionCode = $versionName = "unknown"
    if ($aapt2) {
        $badging = & $aapt2 dump badging $apkPath 2>&1
        foreach ($line in $badging) {
            if ($line -match "package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'") {
                $pkgName = $Matches[1]
                $versionCode = $Matches[2]
                $versionName = $Matches[3]
                break
            }
        }
    } else {
        $manifestXml = & $resolvedAdb "shell", "cat", "/data/data/$expectedAppId/../../../../system/app/fake" 2>$null
        if ($manifestXml -match "dummy") { }
        Write-Host "Warning: aapt2 not found; package/version fallback skipped."
    }
    Write-StepResult -Title "APK package" -Value $pkgName
    Write-StepResult -Title "APK version" -Value "$versionName ($versionCode)"
    if ($pkgName -ne $expectedAppId) {
        Fail-Script -Step "APK safety gate" -ExitCode 6 -Reason "Unexpected package in APK. Expected $expectedAppId." -Evidence @("package=$pkgName", "apk=$apkPath")
    }

    if (-not $SkipInstall) {
        Write-Host ""
        Write-Host "===== Install ====="
        $install = Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "install", "-r", $apkPath)
        if ($install.Output -notmatch "Success") {
            Fail-Script -Step "Install" -ExitCode 7 -Reason "Install failed." -Evidence @($install.Output)
        }
        Write-StepResult -Title "Install" -Value "PASS (update)"
    } else {
        Write-StepResult -Title "Install" -Value "SKIPPED"
    }

    Write-Host ""
    Write-Host "===== Cold launch ====="
    $forceStop = Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "shell", "am", "force-stop", $expectedAppId)
    $clearLogcat = Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "logcat", "-c")
    if ($clearLogcat.ExitCode -ne 0) {
        Fail-Script -Step "logcat clear" -ExitCode 8 -Reason "Could not clear logcat." -Evidence @($clearLogcat.Output)
    }

    $launcher = "$expectedAppId/$expectedLauncherActivity"
    $startArgs = @("-s", $targetSerial, "shell", "am", "start", "-W", "-n", $launcher)
    $start = Invoke-ADB -Adb $resolvedAdb -Arguments $startArgs
    $statusLine = ($start.Output | Select-String "Status:")
    $launchStateLine = ($start.Output | Select-String "LaunchState:")
    $totalTimeLine = ($start.Output | Select-String "TotalTime:")
    $waitTimeLine = ($start.Output | Select-Object | Select-String "WaitTime:")
    if ($statusLine.Count -eq 0 -or $start.Output -notmatch "ComponentInfo") {
        Fail-Script -Step "Cold launch" -ExitCode 9 -Reason "am start did not return expected output." -Evidence @($start.Output)
    }
    if (($statusLine | Select-Object -ExpandProperty Line) -notmatch "ok") {
        Fail-Script -Step "Cold launch" -ExitCode 9 -Reason "Launch status is not ok." -Evidence @($statusLine | ForEach-Object { $_.Line })
    }
    Write-Host ($statusLine | ForEach-Object { $_.Line })
    Write-Host ($launchStateLine | ForEach-Object { $_.Line })
    Write-Host ($totalTimeLine | ForEach-Object { $_.Line })
    Write-Host ($waitTimeLine | ForEach-Object { $_.Line })

    Write-Host ""
    Write-Host "===== Foreground wait ====="
    $deadline = (Get-Date).AddSeconds(18)
    $startTime = Get-Date
    $fgConfirmed = $false
    $procPid = ""
    while ((Get-Date) -lt $deadline) {
        $activity = (& $resolvedAdb -s $targetSerial shell "dumpsys", "activity")
        $window = (& $resolvedAdb -s $targetSerial shell "dumpsys", "window", "windows")
        $isFocus = $window -join "`n" | Select-String "mCurrentFocus=Window.*$([regex]::Escape($expectedAppId))" | Select-Object -First 1
        $isTopResumed = $activity -join "`n" | Select-String "topResumedActivity=.*$([regex]::Escape($expectedAppId))" | Select-Object -First 1
        $pid = (& $resolvedAdb -s $targetSerial shell "pidof", $expectedAppId) -join ""
        if ($pid -match "^\d+$") {
            $procPid = $pid
            if ($isFocus -and $isTopResumed) {
                $fgConfirmed = $true
                break
            }
        }
        Start-Sleep -Milliseconds 600
    }
    $durationMs = [int][math]::Round((Get-Date - $startTime).TotalMilliseconds)
    if (-not $fgConfirmed) {
        Fail-Script -Step "Foreground check" -ExitCode 10 -Reason "App not confirmed foreground within timeout." -Evidence @("timeoutMs=$durationMs", "pid=$procPid")
    }
    Write-StepResult -Title "Foreground" -Value "PASS (pid=$procPid, wait=${durationMs}ms)"

    if (-not $SkipScreenshot) {
        Write-Host ""
        Write-Host "===== Screenshot ====="
        if (-not (Test-Path $artifactDir)) { New-Item -ItemType Directory -Path $artifactDir | Out-Null }
        $remotePng = "$remoteTempDir/a57-development-smoke-$timestamp.png"
        $localPng = Join-Path $artifactDir "a57-development-smoke-$timestamp.png"
        $shot = Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "shell", "screencap", "-p", $remotePng)
        if ($shot.ExitCode -ne 0 -or (Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "pull", $remotePng, $localPng)).ExitCode -ne 0) {
            Fail-Script -Step "Screenshot" -ExitCode 11 -Reason "Failed to capture screenshot." -Evidence @($shot.Output)
        }
        Write-StepResult -Title "Screenshot" -Value $localPng
    } else {
        Write-StepResult -Title "Screenshot" -Value "SKIPPED"
    }

    Write-Host ""
    Write-Host "===== Collect log ====="
    if (-not (Test-Path $artifactDir)) { New-Item -ItemType Directory -Path $artifactDir | Out-Null }
    $logPath = Join-Path $artifactDir "a57-development-smoke-$timestamp.log"
    Start-Sleep -Seconds 2
    $logRaw = Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "logcat", "-d")
    if ($logRaw.ExitCode -ne 0) {
        Fail-Script -Step "Logcat collect" -ExitCode 12 -Reason "Failed to collect logcat." -Evidence @($logRaw.Output)
    }
    $logRaw.Output | Out-File -FilePath $logPath -Encoding UTF8
    Write-StepResult -Title "Logcat" -Value $logPath

    Write-Host ""
    Write-Host "===== Crash / ANR detect ====="
    $criticalPatterns = @(
        'FATAL EXCEPTION',
        'ANR in',
        'Process .*has died',
        'package.*not found',
        'ActivityManager:.*START.*failed',
        'Unable to start activity',
        'permission denied',
        'Permission.*denial',
        'SSLHandshakeException',
        'failed to establish trust',
        'WebView.*Crashed',
        'Process .*is bad'
    )
    $ignorePatterns = @(
        'PackageConfigPersister: App-specific configuration not found',
        'WindowManager.*not found',
        'W/System.err.*'
    )
    $logLines = Get-Content -Path $logPath
    $detected = @()
    foreach ($line in $logLines) {
        if (Has-CrashSignature -line $line -criticalPatterns $criticalPatterns -ignorePatterns $ignorePatterns) {
            if ((Has-OnlyTarget -line $line -targetApp $expectedAppId) -or ($line -match 'FATAL EXCEPTION') -or ($line -match 'ANR in')) {
                $detected += $line
            }
        }
    }
    if ($detected.Count -gt 0) {
        Fail-Script -Step "Crash / ANR detect" -ExitCode 13 -Reason "Potential critical signal detected in logcat." -Evidence @($detected | Select-Object -First 20)
    }

    Write-Host ""
    if ($RunBasicActions) {
        Write-Host "===== Basic actions ====="
        if (-not $PSBoundParameters.ContainsKey('TapX')) {
            Write-Host "No safe tap coordinates provided. Skip tap."
        } else {
            if ($TapY -ne $null -and $TapX -ge 0 -and $TapY -ge 0) {
                Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "shell", "input", "tap", "$TapX", "$TapY") | Out-Null
                Write-Host "safe tap done"
            }
        }

        if ($PSBoundParameters.ContainsKey('SwipeX1') -and $PSBoundParameters.ContainsKey('SwipeY1') -and $PSBoundParameters.ContainsKey('SwipeX2') -and $PSBoundParameters.ContainsKey('SwipeY2')) {
            Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "shell", "input", "swipe", "$SwipeX1", "$SwipeY1", "$SwipeX2", "$SwipeY2", "$SwipeDurationMs") | Out-Null
            Write-Host "safe swipe done"
        }

        Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "shell", "input", "keyevent", "4") | Out-Null
        Invoke-ADB -Adb $resolvedAdb -Arguments @("-s", $targetSerial, "shell", "input", "keyevent", "3") | Out-Null
        Write-Host "BACK and HOME sent"
    } else {
        Write-Host "Basic actions skipped"
    }

    Write-Host ""
    Write-Host "A57 Development Smoke: PASS"
    Write-Host "Device: $($matched[0].Model) / $($matched[0].Device)"
    Write-Host "Serial: $targetSerial"
    Write-Host "APK: $pkgName ($versionName, code=$versionCode)"
    Write-Host "Launch: PASS (cold launch with am start -W)"
    Write-Host "Foreground: PASS (pid=$procPid)"
    Write-Host "Screenshot: $artifactDir\a57-development-smoke-$timestamp.png"
    Write-Host "Log: $logPath"
    exit 0
}
catch {
    Fail-Script -Step "Unhandled error" -ExitCode 99 -Reason $_.Exception.Message -Evidence @($_.Exception.ToString())
}
