#requires -Version 5.1
<#
  USB-direct streaming helper.
  Maps the phone's IP Webcam HTTP server (default port 8080) to 127.0.0.1 via
  adb port forward. No WiFi / same-LAN requirement, usually lower latency.

  Usage:
      scripts\usb_stream.ps1              # create forward on port 8080
      scripts\usb_stream.ps1 -Port 8080
      scripts\usb_stream.ps1 -Remove      # remove the forward

  Then use this URL:  http://127.0.0.1:<Port>/video
      scripts\run.ps1 --source mjpeg:http://127.0.0.1:8080/video

  NOTE: keep this file ASCII-only (Windows PowerShell 5.1 mis-reads BOM-less UTF-8).
#>
param(
    [int]$Port = 8080,
    [switch]$Remove
)

$Root = Split-Path -Parent $PSScriptRoot
$Adb = Join-Path $Root '.tools\platform-tools\adb.exe'

if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Host '[ERROR] adb not found. Run scripts\setup_env.ps1 first.' -ForegroundColor Red
    exit 1
}

$env:TEMP = Join-Path $Root '.tools\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

if ($Remove) {
    & $Adb forward --remove "tcp:$Port" | Out-Null
    Write-Host "[OK] removed tcp:$Port forward"
    exit 0
}

$devices = @(& $Adb devices | Where-Object { $_ -match '^\S+\s+device\b' })
if ($devices.Count -eq 0) {
    Write-Host '[ERROR] no authorized device. Check USB debugging / adb devices.' -ForegroundColor Red
    exit 1
}

& $Adb forward "tcp:$Port" "tcp:$Port" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host '[ERROR] adb forward failed.' -ForegroundColor Red
    exit 1
}

Write-Host "[OK] phone port $Port -> 127.0.0.1:$Port"
Write-Host "Stream URL: http://127.0.0.1:$Port/video"
Write-Host "Run:  scripts\run.ps1 --source mjpeg:http://127.0.0.1:$Port/video"
