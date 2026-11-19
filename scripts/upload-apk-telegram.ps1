<#
.SYNOPSIS
    Builds (optional) and uploads the latest RemoteScroll APK to Telegram via Bot API.

.EXAMPLE
    .\scripts\upload-apk-telegram.ps1
    .\scripts\upload-apk-telegram.ps1 -BuildFirst -Caption "v3.3 with YouTube Auto-Skip Ads"
#>
param(
    [string]$ApkPath = "",
    [string]$BotToken = "",
    [string]$ChatId = "",
    [string]$Caption = "",
    [switch]$BuildFirst
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $RepoRoot ".telegram.env"

function Read-DotEnvValue([string]$Path, [string]$Key) {
    if (-not (Test-Path $Path)) { return "" }
    foreach ($line in Get-Content $Path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
        $idx = $trimmed.IndexOf("=")
        if ($idx -le 0) { continue }
        $k = $trimmed.Substring(0, $idx).Trim()
        if ($k -eq $Key) {
            return $trimmed.Substring($idx + 1).Trim().Trim('"').Trim("'")
        }
    }
    return ""
}

if ([string]::IsNullOrWhiteSpace($BotToken)) {
    $BotToken = $env:TELEGRAM_BOT_TOKEN
}
if ([string]::IsNullOrWhiteSpace($BotToken)) {
    $BotToken = Read-DotEnvValue $EnvFile "TELEGRAM_BOT_TOKEN"
}

if ([string]::IsNullOrWhiteSpace($ChatId)) {
    $ChatId = $env:TELEGRAM_CHAT_ID
}
if ([string]::IsNullOrWhiteSpace($ChatId)) {
    $ChatId = Read-DotEnvValue $EnvFile "TELEGRAM_CHAT_ID"
}

if ([string]::IsNullOrWhiteSpace($BotToken) -or [string]::IsNullOrWhiteSpace($ChatId)) {
    Write-Error "Missing TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID. Set env vars, pass -BotToken/-ChatId, or add them to .telegram.env"
    exit 1
}

if ($BuildFirst) {
    $RemoteScrollDir = Join-Path $RepoRoot "RemoteScroll"
    Push-Location $RemoteScrollDir
    try {
        & .\gradlew.bat --no-daemon assembleDebug
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $DebugApkDir = Join-Path $RepoRoot "RemoteScroll\app\build\outputs\apk\debug"
    $Candidate = $null
    if (Test-Path $DebugApkDir) {
        $Candidate = Get-ChildItem -Path $DebugApkDir -Filter "*.apk" -File |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }
    if (-not $Candidate) {
        $Candidate = Get-ChildItem -Path $RepoRoot -Filter "RemoteScroll*.apk" -File |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }
    if (-not $Candidate) {
        Write-Error "No APK found. Build the project first or pass -ApkPath."
        exit 1
    }
    $ApkPath = $Candidate.FullName
} elseif (-not (Test-Path $ApkPath)) {
    $Resolved = Join-Path $RepoRoot $ApkPath
    if (Test-Path $Resolved) {
        $ApkPath = $Resolved
    } else {
        Write-Error "APK not found at: $ApkPath"
        exit 1
    }
}

$ApkFile = Get-Item $ApkPath
$SizeMb = [Math]::Round($ApkFile.Length / 1MB, 2)

if ([string]::IsNullOrWhiteSpace($Caption)) {
    $Caption = "RemoteScroll APK Update: $($ApkFile.Name) (${SizeMb} MB)"
}

Write-Host "Uploading $($ApkFile.Name) (${SizeMb} MB) to Telegram chat $ChatId..."

$Url = "https://api.telegram.org/bot$BotToken/sendDocument"
$ResponseRaw = & curl.exe -sS -X POST $Url `
    -F "chat_id=$ChatId" `
    -F "document=@$($ApkFile.FullName)" `
    -F "caption=$Caption"

if ($LASTEXITCODE -ne 0) {
    Write-Error "curl.exe failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

$Response = $ResponseRaw | ConvertFrom-Json
if ($Response.ok -ne $true) {
    Write-Error "Telegram API error: $ResponseRaw"
    exit 1
}

$MsgId = $Response.result.message_id
$DocName = $Response.result.document.file_name
Write-Host "Uploaded successfully! message_id=$MsgId file_name=$DocName"
