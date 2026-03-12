$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "$ProjectRoot\.tools\jdk_extract\jdk-17.0.18+8"
$env:ANDROID_SDK_ROOT = "$ProjectRoot\.tools\android-sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:Path"

Write-Output "JAVA_HOME=$env:JAVA_HOME"
Write-Output "ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"
