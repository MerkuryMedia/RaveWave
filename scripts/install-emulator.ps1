$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\env.ps1"

$apk = "$ProjectRoot\app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $apk)) {
  & "$PSScriptRoot\build-debug.ps1"
}

$targets = & adb devices | Select-String "emulator-\d+\s+device$"
if (-not $targets) {
  throw "No running emulator detected. Start one with scripts/start-emulator.ps1"
}

& adb install -r "$apk"
& adb shell monkey -p com.ravewave.app.debug -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Output "Installed and launched on emulator."
