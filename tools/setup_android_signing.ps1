param(
    [string]$Repository = "icc114/safety-inspection-ledger",
    [string]$BackupDirectory = "$PSScriptRoot\android-signing-backup"
)

$ErrorActionPreference = "Stop"

function Find-Tool([string]$Name, [string[]]$Fallbacks) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $Fallbacks) {
        $match = Get-ChildItem $candidate -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
        if ($match) { return $match.FullName }
    }
    return $null
}

function New-RandomSecret([int]$Bytes = 24) {
    $data = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($data)
    return ([Convert]::ToBase64String($data)).Replace("+", "A").Replace("/", "B").Replace("=", "")
}

Write-Host "==================================================" -ForegroundColor DarkCyan
Write-Host "Safety Ledger - one-time Android release signing setup" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor DarkCyan
Write-Host "This creates ONE permanent private signing key for all future Android releases." -ForegroundColor Yellow
Write-Host "Do not regenerate the key in future versions." -ForegroundColor Yellow
Write-Host ""

$keytool = Find-Tool "keytool.exe" @(
    "C:\Program Files\Eclipse Adoptium\jdk-17*\bin\keytool.exe",
    "C:\Program Files\Java\jdk-*\bin\keytool.exe",
    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
)
if (-not $keytool) {
    throw "keytool.exe was not found. Install JDK 17 (Temurin/OpenJDK) or Android Studio first."
}

$gh = Find-Tool "gh.exe" @("C:\Program Files\GitHub CLI\gh.exe")
if (-not $gh) {
    throw "GitHub CLI gh.exe was not found. Install GitHub CLI first."
}

& $gh auth status
if ($LASTEXITCODE -ne 0) {
    Write-Host "GitHub CLI is not logged in. A browser login will open now." -ForegroundColor Yellow
    & $gh auth login --hostname github.com --git-protocol https --web
    if ($LASTEXITCODE -ne 0) { throw "GitHub login failed." }
}

New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
$keystore = Join-Path $BackupDirectory "safety-ledger-release.jks"
$recovery = Join-Path $BackupDirectory "SIGNING-RECOVERY-KEEP-PRIVATE.txt"

if (Test-Path $keystore) {
    Write-Host "Existing signing key found: $keystore" -ForegroundColor Green
    Write-Host "This script will NOT create a second key." -ForegroundColor Yellow
    if (-not (Test-Path $recovery)) {
        throw "The keystore exists but recovery text is missing. Do not regenerate; recover the original passwords."
    }
    $pairs = @{}
    Get-Content $recovery | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { $pairs[$matches[1]] = $matches[2] }
    }
    $storePassword = $pairs['STORE_PASSWORD']
    $keyPassword = $pairs['KEY_PASSWORD']
    $alias = $pairs['KEY_ALIAS']
    if (-not $storePassword -or -not $keyPassword -or -not $alias) {
        throw "Recovery file is incomplete."
    }
} else {
    $storePassword = New-RandomSecret 24
    $keyPassword = New-RandomSecret 24
    $alias = "safety-ledger-release"
    & $keytool -genkeypair -v `
        -keystore $keystore `
        -storetype JKS `
        -storepass $storePassword `
        -keypass $keyPassword `
        -alias $alias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname "CN=Safety Inspection Ledger, OU=Android, O=Safety Ledger, L=Beijing, ST=Beijing, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to create the signing key." }

    @(
        "Repository=$Repository",
        "KEY_ALIAS=$alias",
        "STORE_PASSWORD=$storePassword",
        "KEY_PASSWORD=$keyPassword",
        "Keystore=$keystore",
        "",
        "IMPORTANT: Keep this file and safety-ledger-release.jks private and backed up.",
        "Every future Android release must use this exact same key."
    ) | Set-Content -Encoding UTF8 $recovery
}

$bytes = [IO.File]::ReadAllBytes($keystore)
$base64 = [Convert]::ToBase64String($bytes)

Write-Host "Uploading signing material to GitHub Actions Secrets..." -ForegroundColor Cyan
$base64 | & $gh secret set ANDROID_KEYSTORE_BASE64 --repo $Repository
$storePassword | & $gh secret set ANDROID_KEYSTORE_PASSWORD --repo $Repository
$alias | & $gh secret set ANDROID_KEY_ALIAS --repo $Repository
$keyPassword | & $gh secret set ANDROID_KEY_PASSWORD --repo $Repository

if ($LASTEXITCODE -ne 0) { throw "Failed to save GitHub Actions secrets." }

Write-Host ""
Write-Host "SIGNING SETUP COMPLETE." -ForegroundColor Green
Write-Host "Private backup folder: $BackupDirectory" -ForegroundColor Green
Write-Host "Keep that folder in at least one additional OFFLINE private backup." -ForegroundColor Yellow
Write-Host "Do NOT upload the JKS or recovery file to GitHub, cloud drives, chat groups, or public sharing." -ForegroundColor Yellow
Write-Host ""
Write-Host "Next: GitHub -> Actions -> Android Stable Signed Release -> Run workflow." -ForegroundColor Cyan
