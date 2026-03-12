$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\env.ps1"
Set-Location $ProjectRoot
& .\gradlew.bat :app:assembleDebug

$apk = "$ProjectRoot\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
  Write-Output "APK: $apk"
} else {
  throw "APK not found at $apk"
}
