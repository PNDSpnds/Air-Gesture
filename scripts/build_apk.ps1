#requires -Version 5.1
<#
  Build AirGesture Android APK (debug) with the portable toolchain in
  <repo>\.tools\android . Steps:
    1. local.properties (sdk.dir)
    2. copy MediaPipe model into app assets
    3. gradle assembleDebug
    4. copy APK to dist\AirGesture-v0.0.1-debug.apk
  ASCII-only on purpose.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = '',
    [switch]$Clean
)

$ErrorActionPreference = 'Continue'
$Root = if ($RepoRoot) { $RepoRoot } else { Split-Path -Parent $PSScriptRoot }
$A = Join-Path $Root '.tools\android'
$Jdk = Join-Path $A 'jdk17'
$Sdk = Join-Path $A 'sdk'
$Gradle = Join-Path $A 'gradle-8.9\bin\gradle.bat'
$Android = Join-Path $Root 'android'
$Model = Join-Path $Root '.tools\models\gesture_recognizer.task'
$Assets = Join-Path $Android 'app\src\main\assets'
$Dist = Join-Path $Root 'dist'

if (-not (Test-Path -LiteralPath (Join-Path $Jdk 'bin\java.exe'))) { Write-Host '[ERROR] JDK missing, run scripts\setup_android.ps1'; exit 1 }
if (-not (Test-Path -LiteralPath (Join-Path $Sdk 'platforms\android-34\android.jar'))) { Write-Host '[ERROR] Android SDK missing, run scripts\setup_android.ps1'; exit 1 }
if (-not (Test-Path -LiteralPath $Gradle)) { Write-Host '[ERROR] Gradle missing, run scripts\setup_android.ps1'; exit 1 }
if ((-not (Test-Path -LiteralPath $Model)) -and (-not (Test-Path -LiteralPath (Join-Path $Assets 'gesture_recognizer.task')))) {
    Write-Host '[ERROR] model missing: ' $Model
    exit 1
}

$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + (Join-Path $Sdk 'platform-tools') + ';' + $env:PATH
$env:TEMP = Join-Path $Root '.tools\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
$env:GRADLE_USER_HOME = Join-Path $A 'gradle-home'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null
# 当前环境下 Gradle 的 native services 无法加载 native-platform.dll；
# 关掉 native services（文件监听等）即可正常构建。
$env:GRADLE_OPTS = '-Dorg.gradle.native=false'

Write-Host '=== SDK location ==='
$sdkDir = $Sdk -replace '\\', '/'
$localProps = Join-Path $Android 'local.properties'
if ($sdkDir -match '[^\x00-\x7F]') {
    # 中文路径写进 local.properties 会因编码问题失效；改用 ANDROID_HOME 环境变量
    if (Test-Path -LiteralPath $localProps) { Remove-Item -LiteralPath $localProps -Force }
    Write-Host '  non-ASCII SDK path -> using ANDROID_HOME env'
} else {
    Set-Content -LiteralPath $localProps -Value "sdk.dir=$sdkDir" -Encoding ASCII
    Write-Host "  wrote $localProps"
}

Write-Host '=== copy MediaPipe model to assets ==='
New-Item -ItemType Directory -Force -Path $Assets | Out-Null
$assetModel = Join-Path $Assets 'gesture_recognizer.task'
if (Test-Path -LiteralPath $Model) {
    Copy-Item -LiteralPath $Model -Destination $assetModel -Force
    Write-Host "  from $Model"
} elseif (Test-Path -LiteralPath $assetModel) {
    Write-Host '  using existing asset model (no .tools model available)'
} else {
    Write-Host '[ERROR] model not available'
    exit 1
}

Write-Host '=== gradle assembleDebug ==='
$args = @('-p', $Android, 'assembleDebug', '--no-daemon', '--stacktrace')
if ($Clean) { $args = @('-p', $Android, 'clean', '--no-daemon') }
$code = 0
if ($Clean) {
    & $Gradle @args 2>&1 | Out-Host
    $code = $LASTEXITCODE
    if ($code -ne 0) { Write-Host "[ERROR] gradle clean exit=$code"; exit $code }
    $args = @('-p', $Android, 'assembleDebug', '--no-daemon', '--stacktrace')
}
& $Gradle @args 2>&1 | Out-Host
$code = $LASTEXITCODE
if ($code -ne 0) { Write-Host "[ERROR] gradle assembleDebug exit=$code"; exit $code }

$apk = Join-Path $Android 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { Write-Host "[ERROR] APK not found: $apk"; exit 1 }
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
# 中文应用名（隔空手控）用 Unicode 码点拼出来，保持脚本本身 ASCII-only（PS5.1 需要）
$appCn = -join ([char]0x9694, [char]0x7A7A, [char]0x624B, [char]0x63A7)
$target = Join-Path $Dist ($appCn + '-v1.0.0-debug.apk')
if (Test-Path -LiteralPath (Join-Path $Dist 'AirGesture-v0.0.1-debug.apk')) {
    Remove-Item -LiteralPath (Join-Path $Dist 'AirGesture-v0.0.1-debug.apk') -Force
}
Copy-Item -LiteralPath $apk -Destination $target -Force
$size = (Get-Item -LiteralPath $target).Length / 1MB
Write-Host ''
Write-Host ("BUILD OK: {0} ({1:N2} MB)" -f $target, $size)


