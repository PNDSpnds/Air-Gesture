#requires -Version 5.1
<#
  Install the built APK to the connected phone and launch the app.
  ASCII-only on purpose.
#>
[CmdletBinding()]
param([string]$RepoRoot = '')

$Root = if ($RepoRoot) { $RepoRoot } else { Split-Path -Parent $PSScriptRoot }
$env:TEMP = Join-Path $Root '.tools\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

$adb = Join-Path $Root '.tools\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    $adb = Join-Path $Root '.tools\android\sdk\platform-tools\adb.exe'
}
if (-not (Test-Path -LiteralPath $adb)) { Write-Host '[ERROR] adb not found'; exit 1 }

$appCn = -join ([char]0x9694, [char]0x7A7A, [char]0x624B, [char]0x63A7)
$apk = Join-Path $Root ("dist\" + $appCn + '-v0.1.0-debug.apk')
if (-not (Test-Path -LiteralPath $apk)) {
    # 兼容旧文件名
    $apk = Join-Path $Root 'dist\AirGesture-v0.0.1-debug.apk'
}
if (-not (Test-Path -LiteralPath $apk)) { Write-Host "[ERROR] APK not built: $apk"; exit 1 }

Write-Host '=== devices ==='
& $adb devices
$devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device\b' })
if ($devices.Count -eq 0) { Write-Host '[ERROR] no authorized device'; exit 1 }

Write-Host '=== install ==='
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] adb install failed (exit $LASTEXITCODE)"; exit $LASTEXITCODE }

Write-Host '=== launch ==='
& $adb shell am start -n com.airgesture.app/.MainActivity
Write-Host 'OK'
