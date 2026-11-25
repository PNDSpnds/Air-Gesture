#requires -Version 5.1
<#
  Run AirGesture JVM unit tests (gesture state machines) without relying on the
  Gradle test worker.

  Why: on this machine the project path contains non-ASCII characters, and the
  Gradle test worker fails to launch (ClassNotFoundException:
  worker.org.gradle.process.internal.worker.GradleWorkerMain) because the Java
  @argfile classpath is decoded with the wrong charset. We compile tests with
  Gradle, then run JUnitCore directly with a normal command line classpath.

  ASCII-only on purpose.
#>
[CmdletBinding()]
param([string]$RepoRoot = '')

$ErrorActionPreference = 'Stop'
$Root = if ($RepoRoot) { $RepoRoot } else { Split-Path -Parent $PSScriptRoot }
$A = Join-Path $Root '.tools\android'
$Jdk = Join-Path $A 'jdk17'
$Android = Join-Path $Root 'android'
$Gradle = Join-Path $A 'gradle-8.9\bin\gradle.bat'
$Cache = Join-Path $A 'gradle-home\caches\modules-2\files-2.1'

if (-not (Test-Path -LiteralPath (Join-Path $Jdk 'bin\java.exe'))) {
    Write-Host '[ERROR] JDK missing, run scripts\setup_android.ps1'
    exit 1
}

$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = Join-Path $A 'sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH
$env:TEMP = Join-Path $Root '.tools\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
$env:GRADLE_USER_HOME = Join-Path $A 'gradle-home'
$env:GRADLE_OPTS = '-Dorg.gradle.native=false'

Write-Host '=== compile unit tests (gradle) ==='
& $Gradle -p $Android compileDebugUnitTestKotlin --no-daemon --console=plain 2>&1 | Select-Object -Last 12 | Out-Host
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] gradle compileDebugUnitTestKotlin exit=$LASTEXITCODE"; exit $LASTEXITCODE }

$mainDir = Join-Path $Android 'app\build\tmp\kotlin-classes\debug'
$testDir = Join-Path $Android 'app\build\tmp\kotlin-classes\debugUnitTest'
if (-not (Test-Path -LiteralPath $testDir)) { Write-Host '[ERROR] test classes missing'; exit 1 }

function Find-Jar([string]$Name) {
    $j = Get-ChildItem -LiteralPath $Cache -Recurse -Filter $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $j) { throw "jar not found in gradle cache: $Name" }
    return $j.FullName
}
$classpath = @(
    $mainDir,
    $testDir,
    (Find-Jar 'junit-4.13.2.jar'),
    (Find-Jar 'hamcrest-core-1.3.jar'),
    (Find-Jar 'kotlin-stdlib-1.9.24.jar')
) -join ';'

# 从编译输出目录发现测试类
$testClasses = Get-ChildItem -LiteralPath $testDir -Recurse -Filter '*Test.class' |
    ForEach-Object {
        $_.FullName.Substring($testDir.Length + 1).Replace('\', '.').Replace('.class', '')
    } | Sort-Object
if (-not $testClasses) { Write-Host '[ERROR] no test classes found'; exit 1 }

Write-Host '=== run JUnit ==='
foreach ($c in $testClasses) { Write-Host "  test: $c" }
& (Join-Path $Jdk 'bin\java.exe') -cp $classpath org.junit.runner.JUnitCore @testClasses 2>&1 | Out-Host
$code = $LASTEXITCODE
Write-Host ''
if ($code -eq 0) { Write-Host 'UNIT TESTS OK' } else { Write-Host "UNIT TESTS FAILED (exit $code)" }
exit $code
