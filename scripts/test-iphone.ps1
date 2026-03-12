param(
  [ValidateSet("auto", "lan", "tunnel")]
  [string]$Mode = "auto",
  [int]$StartPort = 8081,
  [switch]$Perf
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$IosRoot = Join-Path $ProjectRoot "ravewave-ios"

if (!(Test-Path $IosRoot)) {
  throw "iOS app folder not found: $IosRoot"
}

Set-Location $IosRoot
if (!(Test-Path (Join-Path $IosRoot "node_modules"))) {
  npm install
}

function Stop-StaleExpoProcesses {
  $expoProcs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
      $_.Name -eq "node.exe" -and
      $_.CommandLine -like "*expo*start*" -and
      $_.CommandLine -like "*$IosRoot*"
    }

  foreach ($p in $expoProcs) {
    try {
      Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
      Write-Output "Stopped stale Expo process: $($p.ProcessId)"
    } catch {
      Write-Output "Could not stop process $($p.ProcessId): $($_.Exception.Message)"
    }
  }
}

function Get-PreferredIPv4 {
  $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
      $_.IPAddress -ne "127.0.0.1" -and
      $_.IPAddress -notlike "169.254.*" -and
      $_.AddressState -eq "Preferred"
    }

  $preferred = $ips |
    Where-Object { $_.InterfaceAlias -match "Ethernet|Wi-Fi|WiFi|WLAN" } |
    Select-Object -First 1

  if (-not $preferred) {
    $preferred = $ips | Select-Object -First 1
  }

  return $preferred
}

function Get-NetworkCategoryForInterface([string]$InterfaceAlias) {
  if ([string]::IsNullOrWhiteSpace($InterfaceAlias)) {
    return $null
  }

  $profile = Get-NetConnectionProfile -ErrorAction SilentlyContinue |
    Where-Object { $_.InterfaceAlias -eq $InterfaceAlias } |
    Select-Object -First 1

  if ($profile) {
    return $profile.NetworkCategory
  }

  return $null
}

function Find-FreePort([int]$Candidate) {
  $basePort = [int]$Candidate
  $occupied = Get-NetTCPConnection -LocalPort $basePort -State Listen -ErrorAction SilentlyContinue
  if (-not $occupied) {
    return $basePort
  }

  $fallbacks = @(($basePort + 1), 8082, 19000, 19001)
  foreach ($p in $fallbacks) {
    if ($p -eq $basePort) { continue }
    $inUse = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if (-not $inUse) {
      return $p
    }
  }

  throw "Could not find an available Expo port."
}

function Ensure-NgrokForTunnel {
  npm ls @expo/ngrok --depth=0 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Write-Output "Installing @expo/ngrok for tunnel mode..."
    npm install --save-dev @expo/ngrok@^4.1.3
  }
}

Stop-StaleExpoProcesses

$preferred = Get-PreferredIPv4
if (-not $preferred) {
  throw "No usable IPv4 address found."
}

$networkCategory = Get-NetworkCategoryForInterface -InterfaceAlias $preferred.InterfaceAlias
$effectiveMode = $Mode
$selectedPort = Find-FreePort -Candidate $StartPort

if ($Mode -eq "auto") {
  $effectiveMode = "lan"
}

if ($effectiveMode -eq "tunnel") {
  Ensure-NgrokForTunnel
  Write-Output "Using Expo tunnel mode (recommended for Public/restricted networks)."
  Write-Output "Host interface: $($preferred.InterfaceAlias) ($($preferred.IPAddress))"
  Write-Output "Starting Expo on port: $selectedPort"
  if ($Perf) {
    Write-Output "Perf mode enabled: --no-dev --minify"
    npx expo start --tunnel --port $selectedPort -c --no-dev --minify
  } else {
    npx expo start --tunnel --port $selectedPort -c
  }
  exit $LASTEXITCODE
}

$env:REACT_NATIVE_PACKAGER_HOSTNAME = $preferred.IPAddress

Write-Output "Using LAN host IP: $($preferred.IPAddress) on $($preferred.InterfaceAlias)"
Write-Output "Network profile: $networkCategory"
if ($networkCategory -eq "Public") {
  Write-Output "Warning: Network profile is Public. If iPhone cannot connect, run PowerShell as admin and set Ethernet to Private."
}
Write-Output "Starting Expo on port: $selectedPort"
Write-Output "If QR scan fails, enter this URL in Expo Go:"
Write-Output "exp://$($preferred.IPAddress):$selectedPort"

if ($Perf) {
  Write-Output "Perf mode enabled: --no-dev --minify"
  npx expo start --lan --port $selectedPort -c --no-dev --minify
} else {
  npx expo start --lan --port $selectedPort -c
}
