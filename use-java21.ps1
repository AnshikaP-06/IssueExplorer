$ErrorActionPreference = 'Stop'
$projectPath = $PSScriptRoot
$jdkPath = (Get-ChildItem -LiteralPath (Join-Path $projectPath '.tools') -Directory | Where-Object Name -Like 'jdk-21*' | Select-Object -First 1).FullName
if (-not $jdkPath) { throw 'Portable Java 21 JDK is missing.' }
$env:JAVA_HOME = $jdkPath
$env:PATH = (Join-Path $jdkPath 'bin') + ';' + $env:PATH
Write-Host "Java 21 enabled for this PowerShell session: $jdkPath"
