param(
    [string]$JxlJar,
    [string]$JavaExecutable
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JxlJar) {
    $cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/net.sourceforge.jexcelapi/jxl/2.6.12'
    $JxlJar = Get-ChildItem -LiteralPath $cache -Filter 'jxl-2.6.12.jar' -File -Recurse |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $JxlJar -or -not (Test-Path -LiteralPath $JxlJar -PathType Leaf)) {
    throw 'Pass -JxlJar with the unmodified jxl-2.6.12.jar dependency.'
}
if (-not $JavaExecutable) {
    $localJava = Join-Path $PSScriptRoot 'local/jdk-21/bin/java.exe'
    $JavaExecutable = if (Test-Path -LiteralPath $localJava) { $localJava } else { (Get-Command java).Source }
}
$source = Join-Path $PSScriptRoot 'fixtures/BiffFixture.java'
$output = Join-Path $projectRoot 'app/src/androidTest/assets/biff-regression/binary-timetable.xls'
& $JavaExecutable '-Dfile.encoding=UTF-8' '-cp' $JxlJar $source $output
if ($LASTEXITCODE -ne 0) { throw "Fixture generation failed ($LASTEXITCODE)." }
Get-FileHash -LiteralPath $output -Algorithm SHA256
