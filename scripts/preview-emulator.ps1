[CmdletBinding()]
param(
    [string]$AvdName = "RemoteScroll_Pixel",
    [switch]$Watch,
    [switch]$Logcat,
    [switch]$StartEmulatorOnly,
    [switch]$NoBoot,
    [switch]$CheckOnly
)

$ErrorActionPreference = "Continue"

function Invoke-QuietLines {
    param(
        [string]$Exe,
        [string[]]$CmdArgs
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        $output = & $Exe @CmdArgs 2>&1
    } finally {
        $ErrorActionPreference = $prev
    }
    return @($output | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AndroidProjectDir = Join-Path $RepoRoot "RemoteScroll"
$GradleWrapper = Join-Path $AndroidProjectDir "gradlew.bat"
$PackageId = "com.remotescroll.app"
$MainActivity = "$PackageId/.MainActivity"

function Find-JavaHome {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
        return $env:JAVA_HOME
    }

    $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCmd) {
        return (Split-Path (Split-Path $javacCmd.Source -Parent) -Parent)
    }

    $candidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "D:\dev\tools\jdk*",
        "C:\Program Files\Eclipse Adoptium\jdk*",
        "C:\Program Files\Microsoft\jdk*",
        "C:\Program Files\Java\jdk*",
        "$env:USERPROFILE\.gemini\antigravity\brain\*\scratch\jdk*\jdk*"
    )

    foreach ($pattern in $candidates) {
        $matches = Get-Item -Path $pattern -ErrorAction SilentlyContinue | Sort-Object FullName -Descending
        foreach ($item in $matches) {
            if (Test-Path (Join-Path $item.FullName "bin\javac.exe")) {
                return $item.FullName
            }
        }
    }

    throw "JDK 17+ not found. Set JAVA_HOME or install JDK 17 (for example: winget install EclipseAdoptium.Temurin.17.JDK)."
}

function Find-AndroidSdk {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($dir in $candidates) {
        $adb = Join-Path $dir "platform-tools\adb.exe"
        $emu = Join-Path $dir "emulator\emulator.exe"
        if ((Test-Path $adb) -and (Test-Path $emu)) {
            return @{
                Root     = $dir
                Adb      = $adb
                Emulator = $emu
            }
        }
    }

    throw "Android SDK (adb.exe + emulator.exe) not found. Set ANDROID_HOME or install Android SDK in $env:LOCALAPPDATA\Android\Sdk."
}

function Get-OnlineDevices {
    param([string]$AdbPath)
    $lines = Invoke-QuietLines -Exe $AdbPath -CmdArgs @("devices")
    $serials = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device$') {
            $serials += $Matches[1]
        }
    }
    return $serials
}

function Ensure-TargetDevice {
    param(
        [string]$AdbPath,
        [string]$EmulatorPath,
        [string]$TargetAvd,
        [switch]$SkipBoot
    )

    $devices = @(Get-OnlineDevices -AdbPath $AdbPath)
    if ($devices.Count -eq 0) {
        if ($SkipBoot) {
            throw "No online ADB device or emulator found (and -NoBoot was passed)."
        }

        $avds = @(Invoke-QuietLines -Exe $EmulatorPath -CmdArgs @("-list-avds"))
        if ($avds -notcontains $TargetAvd) {
            if ($avds.Count -gt 0) {
                $TargetAvd = $avds[0]
            } else {
                throw "No Android Virtual Device found. Create one first (avdmanager create avd ...)."
            }
        }

        Write-Host "Starting Android emulator '$TargetAvd'..." -ForegroundColor Cyan
        Start-Process -FilePath $EmulatorPath -ArgumentList @("-avd", $TargetAvd, "-no-snapshot-save") | Out-Null

        Write-Host "Waiting for emulator to boot (up to 180s)..." -ForegroundColor Yellow
        $deadline = (Get-Date).AddSeconds(180)
        $bootCompleted = ""
        do {
            $online = @(Get-OnlineDevices -AdbPath $AdbPath)
            if ($online.Count -gt 0) {
                $props = Invoke-QuietLines -Exe $AdbPath -CmdArgs @("shell", "getprop", "sys.boot_completed")
                $bootCompleted = if ($props.Count -gt 0) { $props[-1] } else { "" }
                if ($bootCompleted -eq "1") {
                    break
                }
            }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)

        if ($bootCompleted -ne "1") {
            throw "Timed out waiting for Android emulator to finish booting."
        }

        $devices = @(Get-OnlineDevices -AdbPath $AdbPath)
    }

    $preferred = $devices | Where-Object { $_ -like "emulator-*" } | Select-Object -First 1
    if ($preferred) {
        return $preferred
    }
    return ($devices | Select-Object -First 1)
}

function Get-SourceStamp {
    param([string]$ProjectDir)
    $srcDir = Join-Path $ProjectDir "app\src"
    $gradleFile = Join-Path $ProjectDir "app\build.gradle.kts"
    $files = @(Get-ChildItem -Path $srcDir -Recurse -File -ErrorAction SilentlyContinue)
    if (Test-Path $gradleFile) {
        $files += Get-Item $gradleFile
    }
    if ($files.Count -eq 0) {
        return [datetime]::MinValue
    }
    return ($files | Measure-Object -Property LastWriteTimeUtc -Maximum).Maximum
}

function Invoke-DeployPreview {
    param(
        [string]$ProjectDir,
        [string]$GradlePath,
        [string]$AdbPath,
        [string]$Serial,
        [string]$Component
    )

    $env:ANDROID_SERIAL = $Serial
    $a11yService = "$PackageId/$PackageId.GestureAccessibilityService"
    $beforeA11y = (Invoke-QuietLines -Exe $AdbPath -CmdArgs @("-s", $Serial, "shell", "settings", "get", "secure", "enabled_accessibility_services")) -join ""
    $wasA11yEnabled = ($beforeA11y -split ':') -contains $a11yService

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Host "`n[$(Get-Date -Format 'HH:mm:ss')] Building and installing debug build to $Serial..." -ForegroundColor Cyan

    Push-Location $ProjectDir
    try {
        & $GradlePath installDebug --console=plain
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $sw.Stop()
    $elapsed = [math]::Round($sw.Elapsed.TotalSeconds, 1)

    if ($exitCode -ne 0) {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Build/install failed (${elapsed}s)." -ForegroundColor Red
        return $false
    }

    if ($wasA11yEnabled) {
        $afterA11y = (Invoke-QuietLines -Exe $AdbPath -CmdArgs @("-s", $Serial, "shell", "settings", "get", "secure", "enabled_accessibility_services")) -join ""
        if ([string]::IsNullOrWhiteSpace($afterA11y) -or $afterA11y -eq "null") {
            & $AdbPath -s $Serial shell settings put secure enabled_accessibility_services $a11yService 2>$null | Out-Null
        } elseif (($afterA11y -split ':') -notcontains $a11yService) {
            & $AdbPath -s $Serial shell settings put secure enabled_accessibility_services "$afterA11y`:$a11yService" 2>$null | Out-Null
        }
    }

    Invoke-QuietLines -Exe $AdbPath -CmdArgs @("-s", $Serial, "shell", "am", "start", "-n", $Component) | Out-Null
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Preview updated & launched in ${elapsed}s!" -ForegroundColor Green
    return $true
}

$JavaHome = Find-JavaHome
$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"

$Sdk = Find-AndroidSdk
if (-not (Test-Path $GradleWrapper)) {
    throw "Gradle wrapper not found at $GradleWrapper"
}

if ($CheckOnly) {
    $avds = @(Invoke-QuietLines -Exe $Sdk.Emulator -CmdArgs @("-list-avds"))
    $online = @(Get-OnlineDevices -AdbPath $Sdk.Adb)
    Write-Host "JAVA_HOME: $JavaHome"
    Write-Host "SDK Root:  $($Sdk.Root)"
    Write-Host "AVDs:      $(if ($avds.Count) { $avds -join ', ' } else { '<none>' })"
    Write-Host "Online:    $(if ($online.Count) { $online -join ', ' } else { '<none>' })"
    exit 0
}

$Serial = Ensure-TargetDevice -AdbPath $Sdk.Adb -EmulatorPath $Sdk.Emulator -TargetAvd $AvdName -SkipBoot:$NoBoot
Write-Host "Target device: $Serial" -ForegroundColor Gray

if ($StartEmulatorOnly) {
    Write-Host "Emulator '$AvdName' is ready ($Serial)." -ForegroundColor Green
    exit 0
}

if ($Logcat) {
    Write-Host "Streaming RemoteScroll logs on $Serial (Ctrl+C to stop)..." -ForegroundColor Cyan
    & $Sdk.Adb -s $Serial logcat -c
    & $Sdk.Adb -s $Serial logcat -v time `
        "YouTubeAdSkipper:V" `
        "GestureA11yService:V" `
        "MiniControlOverlay:V" `
        "RemoteMediaService:V" `
        "MediaKeyHandler:V" `
        "LockOverlay:V" `
        "SleepBlackoutOverlay:V" `
        "FloatingNavOverlay:V" `
        "PointsCalibration:V" `
        "ProximityLock:V" `
        "AndroidRuntime:E" `
        "*:S"
    exit $LASTEXITCODE
}

$ok = Invoke-DeployPreview -ProjectDir $AndroidProjectDir -GradlePath $GradleWrapper -AdbPath $Sdk.Adb -Serial $Serial -Component $MainActivity
if (-not $Watch) {
    if (-not $ok) { exit 1 }
    exit 0
}

$lastStamp = Get-SourceStamp -ProjectDir $AndroidProjectDir
Write-Host "`nWatching RemoteScroll\app\src for saved changes (Ctrl+C to stop)..." -ForegroundColor Yellow

while ($true) {
    Start-Sleep -Milliseconds 1000
    $currentStamp = Get-SourceStamp -ProjectDir $AndroidProjectDir
    if ($currentStamp -gt $lastStamp) {
        Start-Sleep -Milliseconds 400
        $lastStamp = Get-SourceStamp -ProjectDir $AndroidProjectDir
        Invoke-DeployPreview -ProjectDir $AndroidProjectDir -GradlePath $GradleWrapper -AdbPath $Sdk.Adb -Serial $Serial -Component $MainActivity | Out-Null
    }
}
