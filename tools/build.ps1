param(
    [string[]]$Task = @(':core:test', ':app:testDebugUnitTest', ':app:assembleDebug'),
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkCandidates = @(
    (Join-Path $PSScriptRoot 'local/jdk-21'),
    $env:JAVA_HOME,
    'C:/Program Files/Java/jdk-24'
)
$jdk = $jdkCandidates | Where-Object {
    if (-not $_ -or -not (Test-Path (Join-Path $_ 'bin/java.exe'))) { return $false }
    $releaseFile = Join-Path $_ 'release'
    if (-not (Test-Path $releaseFile)) { return $false }
    $version = [regex]::Match((Get-Content $releaseFile -Raw), 'JAVA_VERSION="(\d+)').Groups[1].Value
    return $version -and [int]$version -ge 17 -and [int]$version -le 24
} | Select-Object -First 1
if (-not $jdk) { throw 'Install JDK 21 and set JAVA_HOME before building.' }
$env:JAVA_HOME = $jdk
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path $sdk)) { throw 'Install Android SDK and set ANDROID_HOME before building.' }
$env:ANDROID_HOME = $sdk
$arguments = @('--console=plain') + $Task
if ($Offline) { $arguments += '--offline' }
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @arguments
    exit $LASTEXITCODE
} finally { Pop-Location }
