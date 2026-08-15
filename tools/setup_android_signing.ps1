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

Write-Host "==================================================" -ForegroundColor DarkCyan
Write-Host "Safety Ledger - upload EXISTING permanent signing key" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor DarkCyan
Write-Host "This script NEVER creates a new signing key." -ForegroundColor Yellow
Write-Host "It only uploads the already-created permanent key to GitHub Actions Secrets." -ForegroundColor Yellow
Write-Host ""

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

$keystore = Join-Path $BackupDirectory "safety-ledger-release.jks"
$recovery = Join-Path $BackupDirectory "SIGNING-RECOVERY-KEEP-PRIVATE.txt"
if (-not (Test-Path $keystore)) {
    throw "Missing permanent keystore: $keystore. Do NOT generate another key. Restore the official signing backup first."
}
if (-not (Test-Path $recovery)) {
    throw "Missing recovery text: $recovery. Do NOT generate another key. Restore the official signing backup first."
}

$pairs = @{}
Get-Content $recovery | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $pairs[$matches[1]] = $matches[2] }
}
$storePassword = $pairs['STORE_PASSWORD']
$keyPassword = $pairs['KEY_PASSWORD']
$alias = $pairs['KEY_ALIAS']
$expectedFingerprint = $pairs['CERT_SHA256']
if (-not $storePassword -or -not $keyPassword -or -not $alias) {
    throw "Recovery file is incomplete."
}

$keytool = Find-Tool "keytool.exe" @(
    "C:\Program Files\Eclipse Adoptium\jdk-17*\bin\keytool.exe",
    "C:\Program Files\Java\jdk-*\bin\keytool.exe",
    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
)
if ($keytool -and $expectedFingerprint) {
    $details = & $keytool -list -v -keystore $keystore -storepass $storePassword -alias $alias 2>&1 | Out-String
    if ($details -notmatch [regex]::Escape($expectedFingerprint)) {
        throw "Signing certificate fingerprint does not match the official permanent key. Upload aborted."
    }
}

$bytes = [IO.File]::ReadAllBytes($keystore)
$base64 = [Convert]::ToBase64String($bytes)

Write-Host "Uploading the permanent signing material to GitHub Actions Secrets..." -ForegroundColor Cyan
$base64 | & $gh secret set ANDROID_KEYSTORE_BASE64 --repo $Repository
$storePassword | & $gh secret set ANDROID_KEYSTORE_PASSWORD --repo $Repository
$alias | & $gh secret set ANDROID_KEY_ALIAS --repo $Repository
$keyPassword | & $gh secret set ANDROID_KEY_PASSWORD --repo $Repository
if ($LASTEXITCODE -ne 0) { throw "Failed to save GitHub Actions secrets." }

Write-Host ""
Write-Host "PERMANENT SIGNING KEY UPLOADED." -ForegroundColor Green
Write-Host "Do not delete the private offline backup." -ForegroundColor Yellow
Write-Host "Future releases must keep using this exact same key." -ForegroundColor Yellow
