$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\env.ps1"

$apk = "$ProjectRoot\app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $apk)) {
  throw "APK not found. Run scripts/build-debug.ps1 first."
}

$devices = & adb devices | Select-String "\tdevice$"
if (-not $devices) {
  throw "No authorized Android device found. Enable USB debugging and accept RSA prompt."
}

& adb install -r "$apk"
Write-Output "Installed: $apk"
