param(
    [ValidatePattern('^\d+(\.\d+)?$')]
    [string]$Api = '36',
    [string]$ImageTag = 'default',
    [int]$Port = 5566,
    [int]$TimeoutSeconds = 300,
    [switch]$ShowWindow
)
$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$emulator = Join-Path $sdk 'emulator/emulator.exe'
$imageDir = Join-Path $sdk "system-images/android-$Api/$ImageTag/x86_64"
if (-not (Test-Path (Join-Path $imageDir 'system.img'))) {
    throw "Install system-images/android-$Api/$ImageTag/x86_64 with Android SDK Manager first."
}
if (-not (Test-Path $adb) -or -not (Test-Path $emulator)) { throw 'Android Emulator and platform-tools are required.' }
$serial = "emulator-$Port"
$env:ANDROID_HOME = $sdk
$env:ANDROID_AVD_HOME = Join-Path $PSScriptRoot 'local/avd'
$avdName = "CridNextApi$Api"
$avdDirectory = Join-Path $env:ANDROID_AVD_HOME "$avdName.avd"
New-Item -ItemType Directory -Force -Path $avdDirectory | Out-Null
$ini = @"
avd.ini.encoding=UTF-8
path=$avdDirectory
target=android-$Api
"@
[IO.File]::WriteAllText((Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"), $ini, [Text.UTF8Encoding]::new($false))
$configPath = Join-Path $avdDirectory 'config.ini'
if (-not (Test-Path $configPath)) {
    $config = @"
AvdId=$avdName
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=Crid Next API $Api
avd.ini.encoding=UTF-8
disk.dataPartition.size=4G
fastboot.forceColdBoot=yes
hw.accelerometer=yes
hw.audioInput=no
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.dPad=no
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=software
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density=320
hw.lcd.height=1600
hw.lcd.width=720
hw.mainKeys=no
hw.ramSize=2048
hw.sdCard=no
image.sysdir.1=system-images/android-$Api/$ImageTag/x86_64/
runtime.network.latency=none
runtime.network.speed=full
showDeviceFrame=no
tag.display=$ImageTag
tag.id=$ImageTag
target=android-$Api
vm.heapSize=256
"@
    [IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))
}
& $adb start-server | Out-Null
$connected = (& $adb devices) -match "^$serial\s+device$"
if (-not $connected) {
    $arguments = @('-avd', $avdName, '-port', "$Port", '-no-audio', '-no-boot-anim', '-no-snapshot-load', '-gpu', 'software')
    if (-not $ShowWindow) { $arguments += '-no-window' }
    $start = @{
        FilePath = $emulator
        ArgumentList = $arguments
        WindowStyle = 'Hidden'
        RedirectStandardOutput = (Join-Path $PSScriptRoot 'local/emulator-out.log')
        RedirectStandardError = (Join-Path $PSScriptRoot 'local/emulator-error.log')
        PassThru = $true
    }
    if ($ShowWindow) { $start.WindowStyle = 'Normal' }
    $emulatorProcess = Start-Process @start
    Write-Host "Starting $avdName (PID $($emulatorProcess.Id))..."
}
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
    if ($boot -and $boot.Trim() -eq '1') {
        & $adb -s $serial shell input keyevent 82 | Out-Null
        Write-Host "Ready: $serial (Android API $(& $adb -s $serial shell getprop ro.build.version.sdk))"
        exit 0
    }
    if ($emulatorProcess -and $emulatorProcess.HasExited) {
        throw "Emulator exited. See tools/local/emulator-error.log."
    }
    Start-Sleep -Seconds 3
}
throw "Emulator did not boot in $TimeoutSeconds seconds. See tools/local/emulator-error.log."
