param(
    [switch]$Offline,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Release signing uses Windows CurrentUser DPAPI.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$utf8 = [System.Text.UTF8Encoding]::new($false)
$environmentNames = @(
    'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'PATH',
    'CRID_RELEASE_STORE_FILE', 'CRID_RELEASE_STORE_PASSWORD',
    'CRID_RELEASE_KEY_ALIAS', 'CRID_RELEASE_KEY_PASSWORD'
)
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Invoke-ReleaseTool {
    param([string]$Executable, [string[]]$Arguments, [string]$LogPath)
    # Native stderr is diagnostic output; the process exit code decides success.
    $ErrorActionPreference = 'Continue'
    $lines = [System.Collections.Generic.List[string]]::new()
    & $Executable @Arguments 2>&1 | ForEach-Object {
        $line = $_.ToString()
        $lines.Add($line)
        Write-Host $line
        if ($LogPath) { [IO.File]::AppendAllText($LogPath, $line + [Environment]::NewLine, $utf8) }
    }
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "Release tool failed: $([IO.Path]::GetFileName($Executable)) (exit $exitCode)." }
    return ($lines -join [Environment]::NewLine)
}

function Get-NativeLibraries {
    param([string]$Path, [string]$EntryPattern)
    $elfAbis = @{
        'armeabi-v7a' = @(1, 40)
        'arm64-v8a' = @(2, 183)
        'x86' = @(1, 3)
        'x86_64' = @(2, 62)
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $libraries = @($archive.Entries | Where-Object { $_.FullName -match $EntryPattern })
        if ($libraries.Count -eq 0) { throw "No native libraries found in $([IO.Path]::GetFileName($Path))." }
        foreach ($entry in $libraries) {
            $abi = [regex]::Match($entry.FullName, $EntryPattern).Groups[1].Value
            if (-not $elfAbis.ContainsKey($abi)) { throw "Unknown ABI in release: $($entry.FullName)." }
            $expected = $elfAbis[$abi]
            $stream = $entry.Open()
            $reader = [IO.BinaryReader]::new($stream)
            try { $header = $reader.ReadBytes(20) } finally { $reader.Dispose() }
            if ($header.Length -ne 20 -or $header[0] -ne 0x7f -or $header[1] -ne 0x45 -or
                $header[2] -ne 0x4c -or $header[3] -ne 0x46 -or $header[4] -ne $expected[0] -or
                $header[5] -ne 1 -or $header[18] -ne $expected[1] -or $header[19] -ne 0) {
                throw "Native library ELF header does not match its ABI: $($entry.FullName)."
            }
            $entry.FullName
        }
    } finally { $archive.Dispose() }
}

function Protect-SigningPath {
    param([string]$Path, [switch]$Directory)
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    # Modify only the existing DACL; changing owner/audit information can require
    # SeSecurityPrivilege when reusing signing material from a previous release.
    $acl = Get-Acl -LiteralPath $Path
    if ($Directory) {
        $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    } else {
        $inheritance = [Security.AccessControl.InheritanceFlags]::None
    }
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($existingRule in @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))) {
        $acl.RemoveAccessRuleSpecific($existingRule)
    }
    foreach ($principal in @($currentUser, $system)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $principal, [Security.AccessControl.FileSystemRights]::FullControl,
            $inheritance, [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow
        )
        $acl.AddAccessRule($rule)
    }
    $entry = Get-Item -LiteralPath $Path -Force
    if ($entry.PSObject.Methods.Name -contains 'SetAccessControl') {
        $entry.SetAccessControl($acl)
    } else {
        [IO.FileSystemAclExtensions]::SetAccessControl($entry, $acl)
    }
}

Push-Location $projectRoot
try {
    $sourceCommit = (& git rev-parse HEAD 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the source Git commit.' }
    $sourceDirty = -not [string]::IsNullOrWhiteSpace((& git status --porcelain 2>$null | Out-String))
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the source Git status.' }
    if ($sourceDirty) { throw 'Commit the release sources before building so the distributed source archive matches the APK.' }
    $jdk = @((Join-Path $PSScriptRoot 'local/jdk-21'), $env:JAVA_HOME) | Where-Object {
        $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin/java.exe')) -and
        (Test-Path -LiteralPath (Join-Path $_ 'release')) -and
        ((Get-Content -LiteralPath (Join-Path $_ 'release') -Raw) -match 'JAVA_VERSION="21[.\"]')
    } | Select-Object -First 1
    if (-not $jdk) { throw 'Install JDK 21 in tools/local/jdk-21 or set JAVA_HOME to JDK 21.' }
    $sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android/Sdk')) |
        Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'build-tools')) } |
        Select-Object -First 1
    if (-not $sdk) { throw 'Install Android SDK and set ANDROID_HOME.' }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw 'Install a stable Android SDK Build Tools release.' }
    $keytool = Join-Path $jdk 'bin/keytool.exe'
    $jarsigner = Join-Path $jdk 'bin/jarsigner.exe'
    $apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
    $zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
    $aapt = Join-Path $buildTools.FullName 'aapt.exe'
    foreach ($toolPath in @($keytool, $jarsigner, $apksigner, $zipalign, $aapt)) {
        if (-not (Test-Path -LiteralPath $toolPath)) { throw "Required release tool is missing: $toolPath" }
    }
    $env:JAVA_HOME = $jdk
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $env:PATH = "$jdk\bin;$($buildTools.FullName);$sdk\platform-tools;$env:PATH"

    $gradleSource = Get-Content -LiteralPath 'app/build.gradle.kts' -Raw
    $versionName = [regex]::Match($gradleSource, 'versionName\s*=\s*"([^"\r\n]+)"').Groups[1].Value
    $versionCode = [regex]::Match($gradleSource, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    $applicationId = [regex]::Match($gradleSource, 'applicationId\s*=\s*"([^"\r\n]+)"').Groups[1].Value
    $minSdk = [regex]::Match($gradleSource, 'minSdk\s*=\s*(\d+)').Groups[1].Value
    $targetSdk = [regex]::Match($gradleSource, 'targetSdk\s*=\s*(\d+)').Groups[1].Value
    if ($versionName -notmatch '^\d+\.\d+\.\d+(?:[-.][A-Za-z0-9]+)*$' -or -not $versionCode -or -not $applicationId) {
        throw 'Could not resolve the application version and package from app/build.gradle.kts.'
    }
    $releaseDirectory = Join-Path $projectRoot "artifacts/releases/v$versionName"
    [IO.Directory]::CreateDirectory($releaseDirectory) | Out-Null
    $buildLogName = 'build-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log'
    $buildLog = Join-Path $releaseDirectory $buildLogName
    [IO.File]::WriteAllText($buildLog, '', $utf8)

    $signingDirectory = Join-Path $PSScriptRoot 'local/signing'
    $storeFile = Join-Path $signingDirectory 'crid-next-release.p12'
    $passwordFile = Join-Path $signingDirectory 'crid-next-release.password.dpapi'
    $certificatePinFile = Join-Path $projectRoot 'gradle/release-certificate.sha256'
    $hasCertificatePin = Test-Path -LiteralPath $certificatePinFile
    $expectedCertificate = if ($hasCertificatePin) {
        [IO.File]::ReadAllText($certificatePinFile).Trim().ToLowerInvariant()
    } else { $null }
    if ($hasCertificatePin -and $expectedCertificate -notmatch '^[a-f0-9]{64}$') {
        throw 'The release certificate fingerprint file is invalid.'
    }
    $hasStore = Test-Path -LiteralPath $storeFile
    $hasPassword = Test-Path -LiteralPath $passwordFile
    if ($expectedCertificate -and (-not $hasStore -or -not $hasPassword)) {
        throw 'This project already has a release certificate. Restore its original keystore and password; a new key cannot sign compatible updates.'
    }
    if ($hasStore -ne $hasPassword) {
        throw 'Release signing material is incomplete. Restore the original keystore and DPAPI password together; no replacement key was generated.'
    }
    [IO.Directory]::CreateDirectory($signingDirectory) | Out-Null
    Protect-SigningPath -Path $signingDirectory -Directory
    $securePassword = $null
    if ($hasStore) {
        Protect-SigningPath -Path $storeFile
        Protect-SigningPath -Path $passwordFile
        try {
            $securePassword = ConvertTo-SecureString -String ([IO.File]::ReadAllText($passwordFile).Trim())
        } catch {
            throw 'Cannot decrypt the release signing password with this Windows account. Restore access to the original signing material.'
        }
    } else {
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        $bytes = New-Object byte[] 48
        try {
            $random.GetBytes($bytes)
            $securePassword = ConvertTo-SecureString -String ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
            $encryptedPassword = ConvertFrom-SecureString -SecureString $securePassword
            [IO.File]::WriteAllText($passwordFile, $encryptedPassword, $utf8)
            Protect-SigningPath -Path $passwordFile
        } finally {
            [Array]::Clear($bytes, 0, $bytes.Length)
            $random.Dispose()
        }
    }
    $passwordPointer = [IntPtr]::Zero
    try {
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $env:CRID_RELEASE_STORE_FILE = $storeFile
        $env:CRID_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        $env:CRID_RELEASE_KEY_ALIAS = 'crid-next'
        $env:CRID_RELEASE_KEY_PASSWORD = $env:CRID_RELEASE_STORE_PASSWORD
    } finally {
        if ($passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
        if ($securePassword) { $securePassword.Dispose() }
    }
    if (-not $hasStore) {
        $null = Invoke-ReleaseTool -Executable $keytool -LogPath $buildLog -Arguments @(
            '-genkeypair', '-noprompt', '-keystore', $storeFile, '-storetype', 'PKCS12',
            '-storepass:env', 'CRID_RELEASE_STORE_PASSWORD', '-keypass:env', 'CRID_RELEASE_KEY_PASSWORD',
            '-alias', 'crid-next', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
            '-dname', 'CN=Crid Next'
        )
        Protect-SigningPath -Path $storeFile
    }
    # Validate the original store before building; failure must never rotate its key.
    $storeDetails = Invoke-ReleaseTool -Executable $keytool -LogPath $buildLog -Arguments @(
        '-J-Duser.language=en', '-list', '-keystore', $storeFile, '-storetype', 'PKCS12',
        '-storepass:env', 'CRID_RELEASE_STORE_PASSWORD', '-alias', 'crid-next'
    )
    $storeCertificate = [regex]::Match($storeDetails, 'Certificate fingerprint \(SHA-256\):\s*([A-Fa-f0-9:]+)').Groups[1].Value.Replace(':', '').ToLowerInvariant()
    if ($storeCertificate -notmatch '^[a-f0-9]{64}$') { throw 'Could not read the keystore public certificate fingerprint.' }
    if ($expectedCertificate -and $storeCertificate -ne $expectedCertificate) {
        throw 'The keystore certificate differs from the registered release certificate. Restore the original signing material.'
    }
    $tasks = @(':app:assembleRelease', ':app:bundleRelease', ':app:lintRelease')
    if (-not $SkipTests) { $tasks += @(':app:testReleaseUnitTest', ':core:test') }
    # Avoid persisting signing environment/passwords in a daemon or configuration cache.
    $gradleArguments = @('--console=plain', '--no-daemon', '--no-configuration-cache') + $tasks
    if ($Offline) { $gradleArguments += '--offline' }
    $null = Invoke-ReleaseTool -Executable (Join-Path $projectRoot 'gradlew.bat') -Arguments $gradleArguments -LogPath $buildLog

    $builtApk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
    $builtBundle = Join-Path $projectRoot 'app/build/outputs/bundle/release/app-release.aab'
    foreach ($artifact in @($builtApk, $builtBundle)) {
        if (-not (Test-Path -LiteralPath $artifact)) { throw "Expected signed release output is missing: $artifact" }
    }
    $signature = Invoke-ReleaseTool -Executable $apksigner -LogPath $buildLog -Arguments @('verify', '--verbose', '--print-certs', $builtApk)
    $certificateSha256 = [regex]::Match($signature, 'certificate SHA-256 digest:\s*([a-fA-F0-9]{64})').Groups[1].Value.ToLowerInvariant()
    if (-not $certificateSha256) { throw 'APK signature verified but its public certificate fingerprint was not found.' }
    if ($certificateSha256 -ne $storeCertificate -or ($expectedCertificate -and $certificateSha256 -ne $expectedCertificate)) {
        throw 'The APK signing certificate does not match the registered release identity.'
    }
    $null = Invoke-ReleaseTool -Executable $zipalign -LogPath $buildLog -Arguments @('-c', '-P', '16', '4', $builtApk)
    $badging = Invoke-ReleaseTool -Executable $aapt -LogPath $buildLog -Arguments @('dump', 'badging', $builtApk)
    $expectedPackage = "package: name='$applicationId' versionCode='$versionCode' versionName='$versionName'"
    if (-not $badging.Contains($expectedPackage) -or $badging.Contains('application-debuggable') -or
        -not $badging.Contains("sdkVersion:'$minSdk'") -or -not $badging.Contains("targetSdkVersion:'$targetSdk'")) {
        throw 'APK identity, SDK levels or debuggable flag does not match the intended release.'
    }
    $manifestTree = Invoke-ReleaseTool -Executable $aapt -LogPath $buildLog -Arguments @('dump', 'xmltree', $builtApk, 'AndroidManifest.xml')
    foreach ($flag in [regex]::Matches($manifestTree, '(?m)^\s*A: android:(?:debuggable|testOnly)\([^\r\n]+')) {
        if ($flag.Value -notmatch '\(type 0x12\)0x0(?:\s|$)') {
            throw 'The APK manifest enables debuggable or testOnly.'
        }
    }
    $bundleSignature = Invoke-ReleaseTool -Executable $jarsigner -LogPath $buildLog -Arguments @('-J-Duser.language=en', '-verify', $builtBundle)
    if (-not $bundleSignature.Contains('jar verified.')) { throw 'The Android App Bundle is not signed.' }
    $bundleCertificate = Invoke-ReleaseTool -Executable $keytool -LogPath $buildLog -Arguments @('-J-Duser.language=en', '-printcert', '-jarfile', $builtBundle)
    $bundleCertificateSha256 = [regex]::Match($bundleCertificate, 'SHA256:\s*([A-Fa-f0-9:]+)').Groups[1].Value.Replace(':', '').ToLowerInvariant()
    if ($bundleCertificateSha256 -ne $certificateSha256) { throw 'APK and Android App Bundle signing certificates differ.' }

    $apkNativeLibraries = @(Get-NativeLibraries -Path $builtApk -EntryPattern '^lib/([^/]+)/.+\.so$')
    $bundleNativeLibraries = @(Get-NativeLibraries -Path $builtBundle -EntryPattern '^[^/]+/lib/([^/]+)/.+\.so$')
    $nativeAbis = @($apkNativeLibraries | ForEach-Object { $_.Split('/')[1] } | Sort-Object -Unique)
    $bundleAbis = @($bundleNativeLibraries | ForEach-Object { $_.Split('/')[2] } | Sort-Object -Unique)
    if (($nativeAbis -join ',') -ne ($bundleAbis -join ',')) { throw 'APK and AAB native architectures differ.' }
    $mappingFile = Join-Path $projectRoot 'app/build/outputs/mapping/release/mapping.txt'
    if (-not (Test-Path -LiteralPath $mappingFile) -or (Get-Item -LiteralPath $mappingFile).Length -eq 0) {
        throw 'R8 mapping is missing. Verify that release optimization completed.'
    }

    $apkName = "Crid-Next-$versionName.apk"
    $bundleName = "Crid-Next-$versionName.aab"
    Copy-Item -LiteralPath $builtApk -Destination (Join-Path $releaseDirectory $apkName) -Force
    Copy-Item -LiteralPath $builtBundle -Destination (Join-Path $releaseDirectory $bundleName) -Force
    Copy-Item -LiteralPath $mappingFile -Destination (Join-Path $releaseDirectory 'r8-mapping.txt') -Force
    $licenseFiles = @('LICENSE', 'app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt',
        'third_party/jxl-2.6.12-complete-sources.zip', 'docs/REBUILDING.md')
    foreach ($licenseFile in $licenseFiles) {
        Copy-Item -LiteralPath (Join-Path $projectRoot $licenseFile) -Destination $releaseDirectory -Force
    }
    $testSummary = @()
    if (-not $SkipTests) {
        foreach ($suite in @(
            @{ Name = 'appReleaseJvm'; Path = 'app/build/test-results/testReleaseUnitTest' },
            @{ Name = 'coreJvm'; Path = 'core/build/test-results/test' }
        )) {
            $tests = 0; $failures = 0; $errors = 0; $skipped = 0
            $reports = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot $suite.Path) -Filter 'TEST-*.xml' -File)
            if ($reports.Count -eq 0) { throw "No test results found for $($suite.Name)." }
            foreach ($report in $reports) {
                [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
                $tests += [int]$xml.testsuite.tests
                $failures += [int]$xml.testsuite.failures
                $errors += [int]$xml.testsuite.errors
                $skipped += [int]$xml.testsuite.skipped
            }
            $testSummary += [ordered]@{ suite = $suite.Name; tests = $tests; failures = $failures; errors = $errors; skipped = $skipped }
        }
    }
    $finalSourceCommit = (& git rev-parse HEAD 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $finalSourceCommit -ne $sourceCommit) { throw 'Source commit changed during the release build.' }
    $sourceDirty = -not [string]::IsNullOrWhiteSpace((& git status --porcelain 2>$null | Out-String))
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the source Git status.' }
    if ($sourceDirty) { throw 'Source files changed during the release build.' }
    $sourceArchiveName = "Crid-Next-$versionName-source.zip"
    $sourceArchivePath = Join-Path $releaseDirectory $sourceArchiveName
    $null = Invoke-ReleaseTool -Executable (Get-Command git).Source -Arguments @(
        'archive', '--format=zip', "--output=$sourceArchivePath", $sourceCommit
    )
    $distributionNames = @($apkName, $bundleName, $sourceArchiveName, 'r8-mapping.txt', 'LICENSE',
        'THIRD_PARTY_NOTICES.txt', 'jxl-2.6.12-complete-sources.zip', 'REBUILDING.md')
    $distributionFiles = foreach ($fileName in $distributionNames) {
        $file = Get-Item -LiteralPath (Join-Path $releaseDirectory $fileName)
        [ordered]@{ name = $fileName; bytes = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $manifest = [ordered]@{
        schemaVersion = 1
        applicationId = $applicationId
        versionName = $versionName
        versionCode = [int]$versionCode
        minSdk = [int]$minSdk
        targetSdk = [int]$targetSdk
        nativeAbis = $nativeAbis
        nativeLibraries = [ordered]@{ apk = $apkNativeLibraries; aab = $bundleNativeLibraries }
        buildType = 'release'
        builtAtUtc = [DateTime]::UtcNow.ToString('o')
        sourceCommit = $sourceCommit
        sourceDirty = $sourceDirty
        certificateSha256 = $certificateSha256
        signingKeyAlias = 'crid-next'
        buildTasks = $tasks
        testLog = $buildLogName
        testsSkipped = [bool]$SkipTests
        testResults = $testSummary
        instrumentationTestsRun = $false
        lintReport = 'app/build/reports/lint-results-release.html'
        checks = @('APK signature', 'APK 16 KB zip alignment', 'APK package/version/SDK levels', 'APK not debuggable or testOnly', 'AAB signature and matching certificate', 'APK and AAB matching native ABIs and ELF headers', 'R8 mapping archived', 'Release lint', 'Clean matching application source archive', 'Complete JExcelAPI source and rebuild instructions')
        files = @($distributionFiles)
    }
    [IO.File]::WriteAllText((Join-Path $releaseDirectory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8), $utf8)
    $checksumNames = $distributionNames + @('manifest.json')
    $checksumLines = foreach ($fileName in $checksumNames) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $releaseDirectory $fileName) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $fileName"
    }
    [IO.File]::WriteAllLines((Join-Path $releaseDirectory 'SHA256SUMS'), [string[]]$checksumLines, $utf8)
    Write-Host "Release ready: $releaseDirectory"
    Write-Host "Public certificate SHA-256: $certificateSha256"
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    Pop-Location
}
