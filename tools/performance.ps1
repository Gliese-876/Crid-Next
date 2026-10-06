[CmdletBinding()]
param(
    [string]$BaselineRef = 'd2b0fe3',
    [string]$OutputDirectory = 'artifacts/performance-2026-10-02',
    [ValidateRange(1, 10)][int]$Forks = 3,
    [ValidateRange(1, 100)][int]$Warmups = 8,
    [ValidateRange(3, 100)][int]$Samples = 9,
    [switch]$VerifyOnly,
    [switch]$SingleDayProbe
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$outputRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$cacheRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$java = Join-Path $projectRoot 'tools/local/jdk-21/bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'Expected tools/local/jdk-21. Run the project setup first.' }
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

function CachedJar([string]$group, [string]$module, [string]$version) {
    $moduleRoot = Join-Path $cacheRoot "$group/$module/$version"
    $found = @(Get-ChildItem -LiteralPath $moduleRoot -Recurse -File -Filter "$module-$version.jar")
    if ($found.Count -ne 1) { throw "Expected one cached $module $version jar, got $($found.Count). Run the project build first." }
    return $found[0].FullName
}

$stdlib = CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.3.0'
$annotations = CachedJar 'org.jetbrains' 'annotations' '13.0'
$runtimeJars = @(
    $stdlib
    $annotations
    (CachedJar 'org.jetbrains.kotlinx' 'kotlinx-serialization-core-jvm' '1.9.0')
    (CachedJar 'org.jetbrains.kotlinx' 'kotlinx-serialization-json-jvm' '1.9.0')
    (CachedJar 'net.sourceforge.jexcelapi' 'jxl' '2.6.12')
    (CachedJar 'org.jsoup' 'jsoup' '1.18.3')
)
$compilerJars = @(
    $stdlib
    $annotations
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.3.0')
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.3.0')
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10')
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.3.0')
    (CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.8.0')
)
$serializationPlugin = CachedJar 'org.jetbrains.kotlin' 'kotlin-serialization-compiler-plugin-embeddable' '2.3.0'
$platformFiles = @('ReminderPlanner.kt', 'ReminderIdentity.kt', 'ReminderSnapshot.kt') | ForEach-Object {
    "app/src/main/java/cn/crid/next/platform/$_"
}
$baselineCommit = (& git -C $projectRoot rev-parse "$BaselineRef^{commit}").Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot resolve baseline $BaselineRef" }
$coreFiles = @(& git -C $projectRoot ls-tree -r --name-only $baselineCommit -- core/src/main/kotlin) | Where-Object { $_.EndsWith('.kt') }
$inputPaths = @($coreFiles) + $platformFiles
$inputHashes = @()

foreach ($variant in @('baseline', 'current')) {
    $variantRoot = Join-Path $outputRoot $variant
    New-Item -ItemType Directory -Force -Path $variantRoot | Out-Null
    $sourcePaths = @()
    $variantInputs = if ($variant -eq 'baseline') { $inputPaths } else {
        @(Get-ChildItem (Join-Path $projectRoot 'core/src/main/kotlin') -File -Recurse -Filter '*.kt' | ForEach-Object {
            [IO.Path]::GetRelativePath($projectRoot, $_.FullName).Replace('\', '/')
        }) + $platformFiles
    }
    foreach ($relative in $variantInputs) {
        $destination = Join-Path $variantRoot "source/$relative"
        New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
        if ($variant -eq 'baseline') {
            $content = @(& git -C $projectRoot show "${baselineCommit}:$relative")
            if ($LASTEXITCODE -ne 0) { throw "Cannot export $relative" }
            [IO.File]::WriteAllText($destination, ($content -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
        } else {
            Copy-Item -LiteralPath (Join-Path $projectRoot $relative) -Destination $destination -Force
        }
        $sourcePaths += $destination
        $inputHashes += [pscustomobject]@{ variant = $variant; path = $relative; sha256 = (Get-FileHash $destination -Algorithm SHA256).Hash }
    }
    $adapter = Join-Path $variantRoot 'ScheduleAdapter.kt'
    $call = if ($variant -eq 'baseline') {
        'return dates.map { ScheduleEngine.occurrences(semester, plan, it, settings, calendar) }'
    } else {
        "val prepared = ScheduleEngine.prepare(semester, plan, settings, calendar)`n    return dates.map { prepared.occurrences(it) }"
    }
    [IO.File]::WriteAllText($adapter, @"
package cn.crid.next.benchmark
import cn.crid.next.core.*
import java.time.LocalDate
internal fun benchmarkOccurrences(semester: Semester, plan: Plan, settings: Settings, calendar: HolidayCalendar, dates: List<LocalDate>): List<List<Occurrence>> {
    $call
}
"@, [Text.UTF8Encoding]::new($false))
    $harness = Join-Path $variantRoot 'CoreBenchmark.kt'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'performance/CoreBenchmark.kt') -Destination $harness -Force
    $inputHashes += [pscustomobject]@{ variant = $variant; path = 'benchmark/CoreBenchmark.kt'; sha256 = (Get-FileHash $harness -Algorithm SHA256).Hash }
    $inputHashes += [pscustomobject]@{ variant = $variant; path = 'benchmark/ScheduleAdapter.kt'; sha256 = (Get-FileHash $adapter -Algorithm SHA256).Hash }
    $jar = Join-Path $variantRoot 'benchmark.jar'
    $arguments = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', ($runtimeJars -join ';'), "-Xplugin=$serializationPlugin", '-d', $jar)
    $arguments += $sourcePaths + $adapter + $harness
    $argumentFile = Join-Path $variantRoot 'compiler.args'
    $arguments | ForEach-Object { '"' + $_.Replace('\', '/') + '"' } | Set-Content -LiteralPath $argumentFile -Encoding utf8
    Write-Host "Compiling isolated $variant benchmark (no Gradle tasks)."
    & $java '-Xmx1536m' '-cp' ($compilerJars -join ';') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' "@$argumentFile"
    if ($LASTEXITCODE -ne 0) { throw "$variant compilation failed" }
}

$inputHashes | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputRoot 'source-hashes.json') -Encoding utf8
$metadata = [ordered]@{
    baselineCommit = $baselineCommit
    currentHead = (& git -C $projectRoot rev-parse HEAD).Trim()
    measuredAt = (Get-Date -Format o)
    operatingSystem = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    processor = $env:PROCESSOR_IDENTIFIER
    processors = [Environment]::ProcessorCount
    java = (& $java '-version' 2>&1 | Out-String).Trim()
    kotlin = '2.3.0'
    runnerSha256 = (Get-FileHash $PSCommandPath -Algorithm SHA256).Hash
    jvmFlags = '-Xms512m -Xmx1536m -XX:+UseSerialGC'
    forks = $Forks
    warmups = $Warmups
    samples = $Samples
    minSampleTargetMs = 100
    singleDayProbe = [bool]$SingleDayProbe
    singleDayWarmups = $(if ($SingleDayProbe) { [Math]::Max($Warmups, 100) } else { $null })
    singleDayMaxBatchSize = $(if ($SingleDayProbe) { 256 } else { $null })
    guardWarmups = $(if ($SingleDayProbe) { [Math]::Max($Warmups, 10000) } else { $null })
    guardMaxBatchSize = $(if ($SingleDayProbe) { 1000000 } else { $null })
    allocationMetric = 'HotSpot ThreadMXBean current-thread allocated bytes per operation; -1 means unavailable. This is allocation volume, not retained heap or peak memory.'
    fixtures = $(if ($SingleDayProbe) { 'single day in/out of semester: 500 courses/3000 lessons; the out-of-semester date is one day before semester start' } else { 'schedule: 500 courses/3000 lessons/112 days; display: 120 courses/720 lessons/112 days; import: 180 courses with duplicates and merge; reminder: 120 courses/64 due-time cap' })
    limitation = 'Desktop JVM CPU computation only; does not measure Android frame times, rendering, battery use, or real-device responsiveness.'
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputRoot 'metadata.json') -Encoding utf8
$rows = @()
$checks = @{}
for ($fork = 1; $fork -le $(if ($VerifyOnly) { 1 } else { $Forks }); $fork++) {
    # Alternate ordering between forks to reduce systematic first-run bias.
    $variants = if ($fork % 2 -eq 1) { @('baseline', 'current') } else { @('current', 'baseline') }
    foreach ($variant in $variants) {
        $classpath = @((Join-Path $outputRoot "$variant/benchmark.jar")) + $runtimeJars
        $mode = if ($SingleDayProbe) { if ($VerifyOnly) { 'verify-probe' } else { 'probe' } } else { if ($VerifyOnly) { 'verify' } else { 'measure' } }
        Write-Host "Running $variant fork $fork ($mode)."
        $result = @(& $java '-Xms512m' '-Xmx1536m' '-XX:+UseSerialGC' '-cp' ($classpath -join ';') 'cn.crid.next.benchmark.CoreBenchmarkKt' $Warmups $Samples $mode)
        if ($LASTEXITCODE -ne 0) { throw "$variant fork $fork failed" }
        $result | Set-Content (Join-Path $outputRoot "$variant-fork-$fork.txt") -Encoding utf8
        foreach ($line in $result) {
            $fields = $line -split "`t"
            if ($fields[0] -eq 'CHECK') {
                $key = $fields[1]
                if ($checks.ContainsKey($key) -and $checks[$key] -ne $fields[2]) { throw "Output mismatch for $key in $variant fork $fork" }
                $checks[$key] = $fields[2]
            } elseif ($fields[0] -eq 'TIMING') {
                $sample = 0
                $allocations = $fields[4] -split ','
                foreach ($value in ($fields[3] -split ',')) {
                    $rows += [pscustomobject]@{ variant = $variant; fork = $fork; workload = $fields[1]; batchSize = [int]$fields[2]; sample = ++$sample; milliseconds = [double]::Parse($value, [Globalization.CultureInfo]::InvariantCulture); allocatedBytes = [double]::Parse($allocations[$sample - 1], [Globalization.CultureInfo]::InvariantCulture) }
                }
            }
        }
    }
}
$checks | ConvertTo-Json | Set-Content (Join-Path $outputRoot 'verified-output-digests.json') -Encoding utf8
$expectedDigests = if ($SingleDayProbe) { 2 } else { 5 }
if ($checks.Count -ne $expectedDigests) { throw "Expected $expectedDigests output digests, got $($checks.Count)" }
if ($VerifyOnly) { Write-Host "All $expectedDigests output digests match."; return }
$rows | Export-Csv -LiteralPath (Join-Path $outputRoot 'samples.csv') -NoTypeInformation -Encoding utf8
function Median($values) {
    $sorted = @($values | Sort-Object)
    $mid = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2) { return $sorted[$mid] }
    return ($sorted[$mid - 1] + $sorted[$mid]) / 2
}
$summary = foreach ($workload in ($rows.workload | Sort-Object -Unique)) {
    $before = Median @($rows | Where-Object { $_.variant -eq 'baseline' -and $_.workload -eq $workload } | ForEach-Object { $_.milliseconds })
    $after = Median @($rows | Where-Object { $_.variant -eq 'current' -and $_.workload -eq $workload } | ForEach-Object { $_.milliseconds })
    $beforeBytes = Median @($rows | Where-Object { $_.variant -eq 'baseline' -and $_.workload -eq $workload } | ForEach-Object { $_.allocatedBytes })
    $afterBytes = Median @($rows | Where-Object { $_.variant -eq 'current' -and $_.workload -eq $workload } | ForEach-Object { $_.allocatedBytes })
    [pscustomobject]@{ workload = $workload; baselineMedianMs = $before; currentMedianMs = $after; speedup = $before / $after; reductionPercent = 100 * (1 - $after / $before); baselineAllocatedBytes = $beforeBytes; currentAllocatedBytes = $afterBytes; allocationReductionPercent = $(if ($beforeBytes -gt 0 -and $afterBytes -ge 0) { 100 * (1 - $afterBytes / $beforeBytes) } else { $null }); outputDigest = $checks[$workload] }
}
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputRoot 'summary.json') -Encoding utf8
$summary | Format-Table workload, baselineMedianMs, currentMedianMs, speedup, reductionPercent -AutoSize
Write-Host "Raw samples, source hashes, JVM metadata and output digests: $outputRoot"
