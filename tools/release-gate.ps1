<#
.SYNOPSIS
  Pre-release gate for Kyber: versions, clean defaults tree, unit/package, Extended smoke.

.DESCRIPTION
  Kyber product quality only (/api/kyber-v1). Does not call Aldhani (MCP) or Yavin (deploy).
  Yavin owns post-deploy "what's loaded on the host" smoke; this gate is pre-tag API quality.

.PARAMETER SkipSmoke
  Skip tools/smoke-kyber-api.ps1 -Extended (unit + package only).

.PARAMETER SkipInstall
  Do not pass -InstallToDrapi to build.ps1 (package JAR only; smoke may need a prior install).
#>
param(
  [switch]$SkipSmoke,
  [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $RepoRoot

Write-Host "=== Kyber release-gate (not Aldhani / not Yavin) ===" -ForegroundColor Cyan

# --- clean tree for release resources ---
$porcelain = & git status --porcelain
if ($LASTEXITCODE -ne 0) { throw "git status failed" }

$dirtyTracked = @($porcelain | Where-Object { $_ -match '^(M|A|D|R|C|U|\sM|\sD)' -or ($_ -match '^..' -and $_ -notmatch '^\?\?') })
if ($dirtyTracked.Count -gt 0) {
  Write-Host ($dirtyTracked -join "`n") -ForegroundColor Red
  throw "Working tree has uncommitted changes — commit before release-gate"
}
# Untracked under defaults/ is always a hard fail (missed skeleton / starter).
$untrackedDefaults = @($porcelain | Where-Object {
  $_ -match '^\?\? extension/src/main/resources/defaults/' -or
  $_ -match '^\?\? extension\\src\\main\\resources\\defaults\\'
})
if ($untrackedDefaults.Count -gt 0) {
  Write-Host ($untrackedDefaults -join "`n") -ForegroundColor Red
  throw "Untracked files under extension/src/main/resources/defaults/ — commit or remove before release"
}

& (Join-Path $PSScriptRoot "check-version-sync.ps1")
if ($LASTEXITCODE -ne 0) { throw "check-version-sync.ps1 failed" }

$pomLine = Select-String -Path (Join-Path $RepoRoot "extension\pom.xml") -Pattern '<version>([^<]+)</version>' |
  Select-Object -First 1
$version = $pomLine.Matches.Groups[1].Value

if (-not $env:DRAPI_HOME -or -not (Test-Path $env:DRAPI_HOME)) {
  throw "Set DRAPI_HOME to a Domino REST install (keep-* JARs). Example: `$env:DRAPI_HOME = 'C:\Domino\restapi'"
}

Write-Host "=== Build + unit tests (mvn package) ===" -ForegroundColor Cyan
$build = Join-Path $RepoRoot "extension\build.ps1"
$buildArgs = @{}
if (-not $SkipInstall) {
  $buildArgs["InstallToDrapi"] = $true
}
& $build @buildArgs
if ($LASTEXITCODE -ne 0) {
  # Fallback: PowerShell sometimes surfaces Maven stderr as a native error while exit is 0.
  # Re-check jar presence.
  $jarCheck = Join-Path $RepoRoot "extension\target\infoware-kyber.jar"
  if (-not (Test-Path $jarCheck)) {
    throw "extension\build.ps1 failed and no target\infoware-kyber.jar"
  }
  Write-Host "build.ps1 reported failure but JAR exists — continuing" -ForegroundColor Yellow
}

$jarSrc = Join-Path $RepoRoot "extension\target\infoware-kyber.jar"
if (-not (Test-Path $jarSrc)) {
  throw "Missing $jarSrc after build"
}
$jarOut = Join-Path $RepoRoot "infoware-kyber-$version.jar"
Copy-Item $jarSrc $jarOut -Force
Write-Host "Release JAR: $jarOut" -ForegroundColor Green

if (-not $SkipSmoke) {
  Write-Host "=== Extended API smoke (Kyber only) ===" -ForegroundColor Cyan
  & (Join-Path $PSScriptRoot "smoke-kyber-api.ps1") -Extended
  if ($LASTEXITCODE -ne 0) { throw "smoke-kyber-api.ps1 -Extended failed" }
} else {
  Write-Host "Skipped Extended smoke (-SkipSmoke)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "RELEASE GATE PASSED — Kyber $version" -ForegroundColor Green
Write-Host "  JAR:  $jarOut"
Write-Host "  Next: git tag v$version && gh release create v$version --title `"Kyber $version`" $jarOut"
Write-Host "  Deploy of that release onto hosts is Yavin's job, not this gate."
exit 0
