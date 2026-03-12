$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\env.ps1"

$avdName = "RaveWave_API35"
$emulatorExe = "$env:ANDROID_SDK_ROOT\emulator\emulator.exe"
if (!(Test-Path $emulatorExe)) { throw "Emulator not installed." }

$avds = & $emulatorExe -list-avds
if ($avds -notcontains $avdName) {
  throw "AVD $avdName not found."
}

Write-Output "Starting emulator: $avdName"
Start-Process -FilePath $emulatorExe -ArgumentList "-avd $avdName -netdelay none -netspeed full" | Out-Null

Write-Output "Waiting for emulator device..."
& adb wait-for-device

$booted = $false
for ($i=0; $i -lt 180; $i++) {
  $state = (& adb shell getprop sys.boot_completed 2>$null).Trim()
  if ($state -eq "1") { $booted = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $booted) { throw "Emulator did not finish booting in time." }

Write-Output "Emulator is ready."
