<#
.SYNOPSIS
  Assert Kyber version tables match extension/pom.xml.

.DESCRIPTION
  Checks README.md, extension/README.md, and OpenAPI info.version against the
  first <version> in extension/pom.xml. Used by CI verify and tools/release-gate.ps1.
  Kyber-only — does not call Aldhani or Yavin.
#>
$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
$PomPath = Join-Path $RepoRoot "extension\pom.xml"

$pomLine = Select-String -Path $PomPath -Pattern '<version>([^<]+)</version>' |
  Select-Object -First 1
if (-not $pomLine) {
  throw "No <version> found in extension/pom.xml"
}
$pom = $pomLine.Matches.Groups[1].Value
if ([string]::IsNullOrWhiteSpace($pom)) {
  throw "Empty version in extension/pom.xml"
}

Write-Host "pom=$pom"

$readme = Join-Path $RepoRoot "README.md"
$extReadme = Join-Path $RepoRoot "extension\README.md"
$openapi = Join-Path $RepoRoot "extension\src\main\resources\openapi\openapi.kyber.json"

$needle = "**Version** | $pom"
if (-not (Select-String -Path $readme -SimpleMatch -Pattern $needle)) {
  throw "README.md missing version table entry: $needle"
}
if (-not (Select-String -Path $extReadme -SimpleMatch -Pattern $needle)) {
  throw "extension/README.md missing version table entry: $needle"
}

if (-not (Select-String -Path $openapi -Pattern "`"version`"\s*:\s*`"$([regex]::Escape($pom))`"")) {
  throw "openapi.kyber.json info.version is not $pom"
}

foreach ($name in @("LICENSE", "SECURITY.md")) {
  $p = Join-Path $RepoRoot $name
  if (-not (Test-Path $p)) {
    throw "Missing $name"
  }
}

Write-Host "Version sync OK ($pom)" -ForegroundColor Green
exit 0
