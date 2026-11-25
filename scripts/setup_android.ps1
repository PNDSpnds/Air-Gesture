#requires -Version 5.1
<#
  AirGesture Android build toolchain bootstrap.
  Installs a portable toolchain inside <repo>\.tools\android :

    .tools\android\jdk17          Temurin JDK 17 (portable zip, no installer)
    .tools\android\sdk            Android SDK (cmdline-tools + platform-tools +
                                  platforms;android-34 + build-tools;34.0.0)
    .tools\android\gradle-8.9     Gradle distribution
    .tools\android\setup.log      log

  Defaults to China-friendly mirrors (TUNA Adoptium / Tencent Gradle) with the
  official URLs as fallback. Proxy: system proxy first, then direct.
  ASCII-only on purpose (Windows PowerShell 5.1 mis-reads BOM-less UTF-8).
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = '',
    [string]$ProxyUrl = '',
    [switch]$Force
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$Root    = if ($RepoRoot) { $RepoRoot } else { Split-Path -Parent $PSScriptRoot }
$A       = Join-Path $Root '.tools\android'
$Dl      = Join-Path $A 'downloads'
$JdkDir  = Join-Path $A 'jdk17'
$SdkDir  = Join-Path $A 'sdk'
$GradleDir = Join-Path $A 'gradle-8.9'
$Log     = Join-Path $A 'setup.log'

$JdkUrls = @(
    'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.21.1_2.zip',
    'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'
)
$CmdToolsUrls = @(
    'https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-11076708_latest.zip',
    'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
)
$GradleUrls = @(
    'https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip',
    'https://services.gradle.org/distributions/gradle-8.9-bin.zip'
)

New-Item -ItemType Directory -Force -Path $A, $Dl | Out-Null
if (-not (Test-Path -LiteralPath $Log)) { Set-Content -LiteralPath $Log -Value '' -Encoding UTF8 }
$env:TEMP = Join-Path $Root '.tools\tmp'
$env:TMP  = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

function Write-Log {
    param([string]$Message)
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $Message
    Write-Host $line
    Add-Content -LiteralPath $Log -Value $line -Encoding UTF8
}

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments | Out-Host
    return [int]$LASTEXITCODE
}

function Test-LocalPort {
    param([int]$Port)
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(300)) { $client.Close(); return $false }
        $client.EndConnect($iar)
        $client.Close()
        return $true
    } catch { return $false }
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
    foreach ($port in @(7897, 7890, 1080, 10809, 2080, 8080)) {
        if (Test-LocalPort $port) { return "http://127.0.0.1:$port" }
    }
    return ''
}

$script:Proxy = ''
function Get-RemoteFileAny {
    param([string[]]$Urls, [string]$Dest)
    $proxyCandidate = if ($ProxyUrl) { $ProxyUrl } else { Get-SystemProxy }
    # 大文件优先直连（本地代理对超大 HTTP 流会卡住）；失败再走代理
    $transports = @('')
    if ($proxyCandidate) { $transports += $proxyCandidate }
    foreach ($url in $Urls) {
        foreach ($c in $transports) {
            $via = if ($c) { $c } else { 'direct' }
            $tmp = $Dest + '.part'
            if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
            $args = @('-sS','-L','--fail','--retry','2','--retry-delay','2',
                      '--connect-timeout','30','--max-time','1800',
                      '--speed-limit','4096','--speed-time','60')
            if ($c) { $args += @('-x', $c) }
            $args += @('-o', $tmp, $url)
            Write-Log "download ($via): $url"
            $code = Invoke-Native -Exe 'curl.exe' -Arguments $args
            if ($code -eq 0 -and (Test-Path -LiteralPath $tmp) -and (Get-Item -LiteralPath $tmp).Length -gt 0) {
                Move-Item -LiteralPath $tmp -Destination $Dest -Force
                $mb = (Get-Item -LiteralPath $Dest).Length / 1MB
                Write-Log ('  saved {0} ({1:N1} MB)' -f (Split-Path -Leaf $Dest), $mb)
                if ($c) { $script:Proxy = $c }
                return
            }
            Write-Log "  failed via $via (exit $code)"
            if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
        }
    }
    throw "cannot download any of: $($Urls -join ' | ')"
}

function Expand-ZipTo([string]$Zip, [string]$TargetDir) {
    if (Test-Path -LiteralPath $TargetDir) { Remove-Item -LiteralPath $TargetDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    $code = Invoke-Native -Exe 'tar.exe' -Arguments @('-xf', $Zip, '-C', $TargetDir)
    if ($code -ne 0) {
        Write-Log 'tar extract failed, falling back to Expand-Archive ...'
        Expand-Archive -LiteralPath $Zip -DestinationPath $TargetDir -Force
    }
}

# ------------------------------------------------------------------ 1. JDK
if ($Force -or -not (Test-Path -LiteralPath (Join-Path $JdkDir 'bin\java.exe'))) {
    $zip = Join-Path $Dl 'jdk17-win-x64.zip'
    if ($Force -or -not (Test-Path -LiteralPath $zip)) { Get-RemoteFileAny -Urls $JdkUrls -Dest $zip }
    Write-Log 'extracting JDK ...'
    $tmp = Join-Path $A 'jdk-extract'
    Expand-ZipTo $zip $tmp
    $inner = Get-ChildItem -LiteralPath $tmp -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
    if (-not $inner) { throw 'JDK extract failed: bin\java.exe not found' }
    if (Test-Path -LiteralPath $JdkDir) { Remove-Item -LiteralPath $JdkDir -Recurse -Force }
    Move-Item -LiteralPath $inner.FullName -Destination $JdkDir
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Log 'JDK installed'
}
$env:JAVA_HOME = $JdkDir
$env:PATH = (Join-Path $JdkDir 'bin') + ';' + $env:PATH
$code = Invoke-Native -Exe (Join-Path $JdkDir 'bin\java.exe') -Arguments @('-version')
if ($code -ne 0) { throw 'java -version failed' }

# ------------------------------------------------------------------ 2. cmdline-tools
$sdkManager = Join-Path $SdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
if ($Force -or -not (Test-Path -LiteralPath $sdkManager)) {
    $zip = Join-Path $Dl 'commandlinetools-win.zip'
    if ($Force -or -not (Test-Path -LiteralPath $zip)) { Get-RemoteFileAny -Urls $CmdToolsUrls -Dest $zip }
    Write-Log 'extracting command-line tools ...'
    $tmp = Join-Path $A 'cmdtools-extract'
    Expand-ZipTo $zip $tmp
    $inner = Join-Path $tmp 'cmdline-tools'
    if (-not (Test-Path (Join-Path $inner 'bin\sdkmanager.bat'))) { throw 'cmdline-tools extract failed' }
    New-Item -ItemType Directory -Force -Path (Join-Path $SdkDir 'cmdline-tools') | Out-Null
    $latest = Join-Path $SdkDir 'cmdline-tools\latest'
    if (Test-Path -LiteralPath $latest) { Remove-Item -LiteralPath $latest -Recurse -Force }
    Move-Item -LiteralPath $inner -Destination $latest
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Log 'cmdline-tools installed'
}

# ------------------------------------------------------------------ 3. SDK packages
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir
# sdkmanager 走直连（dl.google.com 直连可用）；本地代理对长时间下载会卡住
$proxyArgs = @()
Write-Log 'accepting Android SDK licenses ...'
$answers = ("y`n" * 40)
$answers | & $sdkManager @proxyArgs --sdk_root=$SdkDir --licenses 2>&1 | Out-Host

$packages = @('platform-tools', 'platforms;android-34', 'build-tools;34.0.0')
foreach ($pkg in $packages) {
    Write-Log "installing SDK package: $pkg"
    $code = Invoke-Native -Exe $sdkManager -Arguments (@($proxyArgs) + @("--sdk_root=$SdkDir", $pkg))
    if ($code -ne 0) { Write-Log "  WARN: sdkmanager exit=$code for $pkg" }
}

# ------------------------------------------------------------------ 4. Gradle
if ($Force -or -not (Test-Path -LiteralPath (Join-Path $GradleDir 'bin\gradle.bat'))) {
    $zip = Join-Path $Dl 'gradle-8.9-bin.zip'
    if ($Force -or -not (Test-Path -LiteralPath $zip)) { Get-RemoteFileAny -Urls $GradleUrls -Dest $zip }
    Write-Log 'extracting Gradle ...'
    $tmp = Join-Path $A 'gradle-extract'
    Expand-ZipTo $zip $tmp
    $inner = Get-ChildItem -LiteralPath $tmp -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\gradle.bat') } | Select-Object -First 1
    if (-not $inner) { throw 'Gradle extract failed' }
    if (Test-Path -LiteralPath $GradleDir) { Remove-Item -LiteralPath $GradleDir -Recurse -Force }
    Move-Item -LiteralPath $inner.FullName -Destination $GradleDir
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Log 'Gradle installed'
}
$code = Invoke-Native -Exe (Join-Path $GradleDir 'bin\gradle.bat') -Arguments @('--version')
if ($code -ne 0) { Write-Log 'WARN: gradle --version failed' }

# ------------------------------------------------------------------ 5. verify
Write-Log '=== versions ==='
& (Join-Path $JdkDir 'bin\java.exe') -version 2>&1 | Out-Host
& (Join-Path $SdkDir 'platform-tools\adb.exe') version 2>&1 | Out-Host
Write-Log 'ANDROID SETUP OK'
Write-Host ''
Write-Host 'Android toolchain ready:'
Write-Host "  JAVA_HOME    = $JdkDir"
Write-Host "  ANDROID_HOME = $SdkDir"
Write-Host "  Gradle       = $GradleDir"
