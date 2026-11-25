#requires -Version 5.1
<#
  AirGesture launcher: uses the portable Python under .tools and sets PYTHONPATH.
  Usage:
      scripts\run.ps1
      scripts\run.ps1 --check
      scripts\run.ps1 --source mjpeg:http://192.168.1.10:8080/video
      scripts\run.ps1 --dry-run --source synthetic --no-hud --max-frames 30
  NOTE: keep this file ASCII-only (Windows PowerShell 5.1 mis-reads BOM-less UTF-8).
#>
$Root = Split-Path -Parent $PSScriptRoot
$Python = Join-Path $Root '.tools\python\python.exe'

if (-not (Test-Path -LiteralPath $Python)) {
    Write-Host '[ERROR] toolchain not installed. Run first:' -ForegroundColor Red
    Write-Host '  powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1' -ForegroundColor Yellow
    exit 1
}

# Keep console <-> python encoding consistent (UTF-8) so Chinese log text is readable.
try { chcp 65001 > $null } catch { }
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = 'utf-8'

Set-Location -LiteralPath $Root
$env:PYTHONPATH = Join-Path $Root 'src'
# matplotlib (indirect dependency of mediapipe) needs a writable cache dir
$env:MPLCONFIGDIR = Join-Path $Root '.tools\mplcache'
& $Python -m airgesture @args
exit $LASTEXITCODE
