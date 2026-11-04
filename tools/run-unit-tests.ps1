<#
.SYNOPSIS
  Run Kyber extension unit tests (no live Domino required).

.DESCRIPTION
  Resolves Maven the same way as extension/build.ps1 (PATH, repo .tools, or sibling
  deploy/design repos), then runs `mvn test` in extension/.
#>
param(
  [string]$Test = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
$ExtensionRoot = Join-Path $RepoRoot "extension"
$ToolsMaven = Join-Path $RepoRoot ".tools\apache-maven-3.9.9\bin\mvn.cmd"

function Resolve-JavaHome {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    return $env:JAVA_HOME
  }
  $candidates = Get-ChildItem "C:\Program Files\Microsoft\jdk-*" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending
  foreach ($dir in $candidates) {
    if (Test-Path (Join-Path $dir.FullName "bin\java.exe")) {
      return $dir.FullName
    }
  }
  return $null
}

function Resolve-Mvn {
  if (Get-Command mvn -ErrorAction SilentlyContinue) {
    return "mvn"
  }
  if (Test-Path $ToolsMaven) {
    return $ToolsMaven
  }
  $parent = Split-Path $RepoRoot -Parent
  foreach ($sib in @("drapi-iws-deploy", "domino-deploy", "domino-design")) {
    $siblingMaven = Join-Path $parent "$sib\.tools\apache-maven-3.9.9\bin\mvn.cmd"
    if (Test-Path $siblingMaven) {
      return $siblingMaven
    }
  }
  return $null
}

$javaHome = Resolve-JavaHome
if (-not $javaHome) {
  throw "JDK 21 not found. Set JAVA_HOME or install Microsoft OpenJDK 21."
}
$env:JAVA_HOME = $javaHome

$mvn = Resolve-Mvn
if (-not $mvn) {
  throw "Maven not found. Run extension\build.ps1 once after placing Maven under .tools, or install mvn on PATH."
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "Maven: $mvn"
Push-Location $ExtensionRoot
try {
  if ($Test) {
    & $mvn -q "-Dtest=$Test" test
  } else {
    & $mvn -q test
  }
  if ($LASTEXITCODE -ne 0) {
    throw "mvn test failed (exit $LASTEXITCODE)"
  }
  Write-Host "Unit tests OK" -ForegroundColor Green
} finally {
  Pop-Location
}
