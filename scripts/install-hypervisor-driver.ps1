$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\env.ps1"

$driverScript = "$env:ANDROID_SDK_ROOT\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat"
if (!(Test-Path $driverScript)) {
  throw "Hypervisor driver package not found."
}

Write-Output "Installing Android Emulator Hypervisor Driver (admin prompt expected)..."
Start-Process -FilePath $driverScript -Verb RunAs -Wait
Write-Output "Done. Re-run emulator -accel-check if needed."
