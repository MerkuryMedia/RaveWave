param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptRoot

. "$ScriptRoot\env.ps1"

if (-not $SkipBuild) {
    & "$ScriptRoot\build-debug.ps1"
}

$emulatorDevice = (& adb devices) | Select-String "emulator-\d+\s+device$"
if (-not $emulatorDevice) {
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    if ($cpu -and $cpu.VirtualizationFirmwareEnabled -eq $false) {
        throw "CPU virtualization is disabled in BIOS/UEFI. Enable Intel VT-x (or AMD-V), reboot, then run this script again."
    }

    $emulatorExe = "$env:ANDROID_SDK_ROOT\emulator\emulator.exe"
    if (Test-Path $emulatorExe) {
        $accel = (& $emulatorExe -accel-check 2>&1 | Out-String)
        if ($accel -match "hypervisor driver is not installed") {
            throw "Emulator acceleration is missing. Run: powershell -ExecutionPolicy Bypass -File .\scripts\install-hypervisor-driver.ps1 (admin), reboot Windows, then retry."
        }
    }

    try {
        & "$ScriptRoot\start-emulator.ps1"
    }
    catch {
        throw
    }
}

& "$ScriptRoot\install-emulator.ps1"
Write-Output "Desktop test ready: app installed and launched in Android Emulator."
