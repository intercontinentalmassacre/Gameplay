[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v\d+\.\d+\.\d+([-.][0-9A-Za-z.-]+)?$')]
    [string]$Tag,

    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$buildFile = Join-Path $repoRoot 'app\build.gradle.kts'
$keystoreProperties = Join-Path $repoRoot 'app\keystores\keystore.properties'
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'

function Require-Path([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Description not found: $Path"
    }
}

Require-Path $buildFile 'Android build file'
Require-Path $gradleWrapper 'Gradle wrapper'
Require-Path $keystoreProperties 'Local release signing configuration'

$buildText = Get-Content -LiteralPath $buildFile -Raw
$versionCodeMatch = [regex]::Match($buildText, 'versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($buildText, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw 'Could not read versionCode/versionName from app/build.gradle.kts.'
}

$versionCode = [int]$versionCodeMatch.Groups[1].Value
$versionName = $versionNameMatch.Groups[1].Value
if ($Tag.Substring(1) -ne $versionName) {
    throw "Tag $Tag does not match versionName $versionName. Update the version before releasing."
}

$properties = @{}
Get-Content -LiteralPath $keystoreProperties | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.+?)\s*$') {
        $properties[$matches[1].Trim()] = $matches[2].Trim()
    }
}
foreach ($required in 'storeFile', 'storePassword', 'keyAlias', 'keyPassword') {
    if ([string]::IsNullOrWhiteSpace($properties[$required])) {
        throw "Missing $required in app/keystores/keystore.properties."
    }
}

$trackedSigningConfig = & git -C $repoRoot ls-files --error-unmatch 'app/keystores/keystore.properties' 2>$null
if ($LASTEXITCODE -eq 0 -or $trackedSigningConfig) {
    throw 'app/keystores/keystore.properties is tracked by Git. Remove it from the index before releasing.'
}

$gradleArgs = @(':app:assembleModernRelease-signed', '--no-daemon')
if ($Offline) { $gradleArgs += '--offline' }

Write-Host "Building signed Gameplay $versionName ($versionCode) for $Tag..."
Push-Location $repoRoot
try {
    & $gradleWrapper @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

$apk = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'app\build\outputs\apk\modern\release-signed') -Filter '*.apk' |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $apk) { throw 'Signed modern APK was not produced.' }

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $null }
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { throw 'Set ANDROID_HOME (or ANDROID_SDK_ROOT) to verify the APK.' }

$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($null -eq $buildTools) { throw 'No Android build-tools installation found.' }

$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
Require-Path $aapt 'aapt'
Require-Path $apksigner 'apksigner'

$badging = & $aapt dump badging $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'aapt could not inspect the release APK.' }
$packageMatch = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
if (-not $packageMatch.Success) { throw 'Could not parse APK package metadata.' }
if ($packageMatch.Groups[1].Value -ne 'app.gameplay' -or
    [int]$packageMatch.Groups[2].Value -ne $versionCode -or
    $packageMatch.Groups[3].Value -ne $versionName) {
    throw "APK metadata does not match Gameplay $versionName ($versionCode)."
}

& $apksigner verify --verbose --print-certs $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

$hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$metadata = [ordered]@{
    schemaVersion = 1
    packageName = 'app.gameplay'
    versionCode = $versionCode
    versionName = $versionName
    assetName = 'Gameplay-modern-release.apk'
    sha256 = $hash
    sizeBytes = $apk.Length
}

Write-Host ''
Write-Host 'Release preflight passed.' -ForegroundColor Green
$metadata | ConvertTo-Json
