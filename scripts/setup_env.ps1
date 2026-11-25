#requires -Version 5.1
<#
  AirGesture prototype - portable toolchain bootstrap (Windows PowerShell 5.1).
  ========================================================================
  Installs EVERYTHING inside <repo>\.tools so the machine stays clean:

    .tools\python\python.exe      Python 3.11 (embeddable) + pip + requirements.txt
    .tools\platform-tools\adb.exe Android platform-tools
    .tools\models\*.task          MediaPipe model assets
    .tools\setup.log              install log
    .tools\downloads\             cached archives

  Network: tries the system (Internet Settings) proxy first, then direct.
  Pass -ProxyUrl http://127.0.0.1:7897 to force one.

  Idempotent: re-run to verify/repair; add -Force to reinstall a component.
  ASCII-only on purpose: Windows PowerShell 5.1 mis-reads BOM-less UTF-8 .ps1.
  Note: $ErrorActionPreference stays 'Continue' and every native exit code is
  checked explicitly - PS 5.1 turns redirected native stderr into terminating
  errors under 'Stop', which would abort this script.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = '',
    [string]$ProxyUrl = '',
    [switch]$Force
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$RepoRoot = if ($RepoRoot) { $RepoRoot } else { Split-Path -Parent $PSScriptRoot }
$Tools        = Join-Path $RepoRoot '.tools'
$Downloads    = Join-Path $Tools 'downloads'
$PythonDir    = Join-Path $Tools 'python'
$PythonExe    = Join-Path $PythonDir 'python.exe'
$PlatformDir  = Join-Path $Tools 'platform-tools'
$AdbExe       = Join-Path $PlatformDir 'adb.exe'
$ModelsDir    = Join-Path $Tools 'models'
$ModelFile    = Join-Path $ModelsDir 'gesture_recognizer.task'
$LogFile      = Join-Path $Tools 'setup.log'
$Requirements = Join-Path $RepoRoot 'requirements.txt'

$PyEmbedUrl  = 'https://www.python.org/ftp/python/3.11.9/python-3.11.9-embed-amd64.zip'
$GetPipUrl   = 'https://bootstrap.pypa.io/get-pip.py'
$PlatformUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
$ModelUrl    = 'https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task'

New-Item -ItemType Directory -Force -Path $Tools, $Downloads, $ModelsDir | Out-Null
# Keep adb's temp/log files inside the repo: sandboxed sessions may not be
# allowed to write %TEMP%.
$env:TEMP = Join-Path $Tools 'tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
# matplotlib cache: keep it inside the repo (AppData may be read-only in sandboxed sessions)
$env:MPLCONFIGDIR = Join-Path $Tools 'mplcache'
if (-not (Test-Path -LiteralPath $LogFile)) {
    Set-Content -LiteralPath $LogFile -Value '' -Encoding UTF8
}

$script:UseProxy = ''

function Write-Log {
    param([string]$Message)
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $Message
    Write-Host $line
    Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
}

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments | Out-Host
    return [int]$LASTEXITCODE
}

function Get-SystemProxy {
    try {
        $ie = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction Stop
        if ($ie.ProxyEnable -eq 1 -and $ie.ProxyServer) {
            $s = [string]$ie.ProxyServer
            if ($s -notmatch '^https?://') { $s = 'http://' + $s }
            return $s
        }
    } catch { }
    return ''
}

function Test-Https {
    param([string]$Proxy)
    $curlArgs = @('-sS', '-I', '--max-time', '20', '-o', 'NUL')
    if ($Proxy) { $curlArgs += @('-x', $Proxy) }
    $curlArgs += 'https://pypi.org/simple/'
    & curl.exe @curlArgs 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Select-Transport {
    param([string]$Preferred)
    $candidates = New-Object System.Collections.ArrayList
    if ($Preferred) { [void]$candidates.Add($Preferred) }
    if (-not $candidates.Contains('')) { [void]$candidates.Add('') }
    foreach ($p in $candidates) {
        $label = if ($p) { $p } else { 'direct' }
        Write-Log "testing HTTPS via $label ..."
        if (Test-Https -Proxy $p) {
            $script:UseProxy = $p
            Write-Log "HTTPS works via $label"
            return $true
        }
        Write-Log "HTTPS via $label failed"
    }
    return $false
}

function Get-RemoteFile {
    param([string]$Url, [string]$Dest)
    $attempts = New-Object System.Collections.ArrayList
    if ($script:UseProxy) { [void]$attempts.Add($script:UseProxy) }
    [void]$attempts.Add('')
    foreach ($p in $attempts) {
        $via = if ($p) { $p } else { 'direct' }
        $tmp = $Dest + '.part'
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
        $curlArgs = @('-sS', '-L', '--fail', '--retry', '2', '--retry-delay', '2',
                      '--connect-timeout', '20', '--max-time', '3600')
        if ($p) { $curlArgs += @('-x', $p) }
        $curlArgs += @('-o', $tmp, $Url)
        $code = Invoke-Native -Exe 'curl.exe' -Arguments $curlArgs
        if ($code -eq 0 -and (Test-Path -LiteralPath $tmp)) {
            Move-Item -LiteralPath $tmp -Destination $Dest -Force
            $mb = (Get-Item -LiteralPath $Dest).Length / 1MB
            Write-Log ('downloaded {0} ({1:N1} MB, via {2})' -f (Split-Path -Leaf $Dest), $mb, $via)
            return
        }
        Write-Log ("download failed via {0} (exit {1}): {2}" -f $via, $code, $Url)
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    }
    throw "cannot download $Url (network unavailable?)"
}

function Fail {
    param([string]$Message)
    throw $Message
}

# ---------------------------------------------------------------- 0. network
Write-Log "repo root : $RepoRoot"
$sysProxy = if ($ProxyUrl) { $ProxyUrl } else { Get-SystemProxy }
Write-Log "proxy cand: '$sysProxy'"
if (-not (Select-Transport -Preferred $sysProxy)) {
    Fail 'No working HTTPS transport. Check the proxy / network, then re-run with -ProxyUrl <url>.'
}

# ---------------------------------------------------------------- 1. python
if ($Force -or -not (Test-Path -LiteralPath $PythonExe)) {
    $zip = Join-Path $Downloads 'python-3.11.9-embed-amd64.zip'
    if ($Force -or -not (Test-Path -LiteralPath $zip)) { Get-RemoteFile -Url $PyEmbedUrl -Dest $zip }
    if (Test-Path -LiteralPath $PythonDir) { Remove-Item -LiteralPath $PythonDir -Recurse -Force }
    Write-Log 'extracting python ...'
    Expand-Archive -LiteralPath $zip -DestinationPath $PythonDir -Force
    if (-not (Test-Path -LiteralPath $PythonExe)) { Fail 'python extraction failed' }
}

$pth = Join-Path $PythonDir 'python311._pth'
if (Test-Path -LiteralPath $pth) {
    $lines = @(Get-Content -LiteralPath $pth)
    $out = New-Object System.Collections.Generic.List[string]
    $hasSite = $false
    $hasLib = $false
    $hasSrc = $false
    foreach ($l in $lines) {
        if ($l -match '^#?\s*import\s+site\s*$') {
            if (-not $hasSite) { $out.Add('import site'); $hasSite = $true }
            continue
        }
        if ($l -match '^Lib\\site-packages') { $hasLib = $true }
        if ($l -eq '..\..\src') { $hasSrc = $true }
        $out.Add($l)
    }
    if (-not $hasSite) { $out.Add('import site') }
    if (-not $hasLib) { $out.Add('Lib\site-packages') }
    # Embeddable python ignores PYTHONPATH when a ._pth file exists, so the
    # project source dir must be listed here explicitly.
    if (-not $hasSrc) { $out.Add('..\..\src') }
    Set-Content -LiteralPath $pth -Value $out -Encoding ASCII
    Write-Log 'patched python311._pth (site + Lib\site-packages + project src)'
}

$code = Invoke-Native -Exe $PythonExe -Arguments @('-c', "import sys; print('python ok:', sys.version.split()[0])")
if ($code -ne 0) { Fail 'python sanity check failed' }

# ---------------------------------------------------------------- 2. pip
$pipInit = Join-Path $PythonDir 'Lib\site-packages\pip\__init__.py'
if ($Force -or -not (Test-Path -LiteralPath $pipInit)) {
    $getpip = Join-Path $Downloads 'get-pip.py'
    if ($Force -or -not (Test-Path -LiteralPath $getpip)) { Get-RemoteFile -Url $GetPipUrl -Dest $getpip }
    $gpArgs = @($getpip, '--no-warn-script-location', '--disable-pip-version-check')
    if ($script:UseProxy) { $gpArgs += @('--proxy', $script:UseProxy) }
    Write-Log 'bootstrapping pip ...'
    $code = Invoke-Native -Exe $PythonExe -Arguments $gpArgs
    if ($code -ne 0 -or -not (Test-Path -LiteralPath $pipInit)) { Fail 'get-pip failed' }
}

# ---------------------------------------------------------------- 3. deps
if (-not (Test-Path -LiteralPath $Requirements)) { Fail "missing requirements.txt: $Requirements" }
$env:PIP_DISABLE_PIP_VERSION_CHECK = '1'
$env:PIP_NO_INPUT = '1'
if ($script:UseProxy) { $env:HTTP_PROXY = $script:UseProxy; $env:HTTPS_PROXY = $script:UseProxy }
$upArgs = @('-m', 'pip', 'install', '--no-warn-script-location', '--no-cache-dir', '--upgrade', 'pip')
if ($script:UseProxy) { $upArgs += @('--proxy', $script:UseProxy) }
Write-Log 'upgrading pip ...'
$code = Invoke-Native -Exe $PythonExe -Arguments $upArgs
if ($code -ne 0) { Fail 'pip self-upgrade failed' }

$piArgs = @('-m', 'pip', 'install', '--no-warn-script-location', '--no-cache-dir', '-r', $Requirements)
if ($script:UseProxy) { $piArgs += @('--proxy', $script:UseProxy) }
Write-Log 'installing requirements (mediapipe / opencv / numpy / pyyaml / pytest) ...'
$code = Invoke-Native -Exe $PythonExe -Arguments $piArgs
if ($code -ne 0) { Fail 'pip install requirements failed' }

# ---------------------------------------------------------------- 4. adb
if ($Force -or -not (Test-Path -LiteralPath $AdbExe)) {
    $zip = Join-Path $Downloads 'platform-tools-latest-windows.zip'
    if ($Force -or -not (Test-Path -LiteralPath $zip)) { Get-RemoteFile -Url $PlatformUrl -Dest $zip }
    if (Test-Path -LiteralPath $PlatformDir) { Remove-Item -LiteralPath $PlatformDir -Recurse -Force }
    Write-Log 'extracting platform-tools ...'
    Expand-Archive -LiteralPath $zip -DestinationPath $Tools -Force
    if (-not (Test-Path -LiteralPath $AdbExe)) { Fail 'platform-tools extraction failed' }
}
$code = Invoke-Native -Exe $AdbExe -Arguments @('version')
if ($code -ne 0) { Fail 'adb sanity check failed' }

# adb user key: adb normally generates it on first run, but in a sandboxed
# session that write can be denied. Generate it explicitly and place it where
# adb expects it. The phone will ask to authorize this key once.
$androidDir = Join-Path $env:USERPROFILE '.android'
$keyFile = Join-Path $androidDir 'adbkey'
if (-not (Test-Path -LiteralPath $keyFile)) {
    $tmpKey = Join-Path $Tools 'adbkey.tmp'
    $code = Invoke-Native -Exe $AdbExe -Arguments @('keygen', $tmpKey)
    if ($code -eq 0 -and (Test-Path -LiteralPath $tmpKey)) {
        New-Item -ItemType Directory -Force -Path $androidDir | Out-Null
        Copy-Item -LiteralPath $tmpKey -Destination $keyFile -Force
        if (Test-Path -LiteralPath ($tmpKey + '.pub')) {
            Copy-Item -LiteralPath ($tmpKey + '.pub') -Destination ($keyFile + '.pub') -Force
        }
        Remove-Item -LiteralPath $tmpKey -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ($tmpKey + '.pub') -Force -ErrorAction SilentlyContinue
        Write-Log "created adb user key: $keyFile"
    } else {
        Write-Log 'WARN: could not create adb user key (phone authorization may fail)'
    }
} else {
    Write-Log 'adb user key already present'
}

# ---------------------------------------------------------------- 5. model
if ($Force -or -not (Test-Path -LiteralPath $ModelFile)) {
    Get-RemoteFile -Url $ModelUrl -Dest $ModelFile
}
$modelMb = (Get-Item -LiteralPath $ModelFile).Length / 1MB
if ($modelMb -lt 1) { Fail "gesture model looks broken: $ModelFile ($modelMb MB)" }
Write-Log ('model ok: {0} ({1:N1} MB)' -f (Split-Path -Leaf $ModelFile), $modelMb)

# ---------------------------------------------------------------- 6. verify
$verify = "import sys, numpy, cv2, yaml, mediapipe, pytest; print('deps ok:', sys.version.split()[0], 'numpy', numpy.__version__, 'cv2', cv2.__version__, 'mediapipe', mediapipe.__version__)"
$code = Invoke-Native -Exe $PythonExe -Arguments @('-c', $verify)
if ($code -ne 0) { Fail 'dependency import check failed' }

Write-Log 'SETUP OK'
Write-Host ''
Write-Host 'Toolchain ready:' -ForegroundColor Green
Write-Host "  python : $PythonExe"
Write-Host "  adb    : $AdbExe"
Write-Host "  model  : $ModelFile"
Write-Host ''
Write-Host 'Next: scripts\run.ps1 --help'
