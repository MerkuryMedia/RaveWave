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

$apk = "$ProjectRoot\app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $apk)) {
    throw "APK not found. Run scripts/build-debug.ps1 first."
}

$deviceLine = (& adb devices) | Select-String "^([A-Za-z0-9_.:-]+)\s+device$" | Where-Object { $_.Line -notmatch "^emulator-" } | Select-Object -First 1
if (-not $deviceLine) {
    throw "No physical Android device found. Connect phone by USB, enable USB debugging, and accept RSA prompt."
}
$serial = ($deviceLine.Matches[0].Groups[1].Value)

Write-Output "Installing on device: $serial"
& adb -s $serial install -r "$apk"

$package = "com.ravewave.app.debug"
& adb -s $serial shell monkey -p $package -c android.intent.category.LAUNCHER 1 | Out-Null

$scrcpyExe = $null
$cmd = Get-Command scrcpy -ErrorAction SilentlyContinue
if ($cmd) {
    $scrcpyExe = $cmd.Source
} else {
    $candidate = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "Genymobile.scrcpy*" } |
        ForEach-Object {
            Get-ChildItem $_.FullName -Recurse -Filter scrcpy.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        } |
        Select-Object -First 1
    if ($candidate) {
        $scrcpyExe = $candidate.FullName
    }
}

if (-not $scrcpyExe) {
    throw "scrcpy is not available in this session. Open a new PowerShell window or run: winget install -e --id Genymobile.scrcpy"
}

Write-Output "Starting desktop mirror with scrcpy..."
Start-Process -FilePath $scrcpyExe -ArgumentList @("-s", $serial, "--stay-awake", "--max-fps=60", "--video-bit-rate=16M")
Write-Output "Ready: app running on phone and mirrored on desktop."
