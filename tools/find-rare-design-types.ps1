<#
.SYNOPSIS
  Scan Domino templates/NSFs for rare Kyber design types (list endpoints, no full DXL export).
#>
param(
  [switch]$SkipCertificateCheck,
  [int]$MaxDbs = 80
)

$ErrorActionPreference = "Continue"
$RepoRoot = Split-Path $PSScriptRoot -Parent

function Get-EnvFileValue([string]$Path, [string]$Key) {
  if (-not (Test-Path $Path)) { return $null }
  foreach ($line in Get-Content $Path) {
    if ($line -match "^\s*#" -or $line -notmatch "=") { continue }
    $k, $v = $line -split "=", 2
    if ($k.Trim() -eq $Key) { return $v.Trim().Trim('"').Trim("'") }
  }
  return $null
}

if ($SkipCertificateCheck -and $PSVersionTable.PSVersion.Major -lt 6) {
  if (-not ([System.Management.Automation.PSTypeName]'IwsTrustAllCerts').Type) {
    Add-Type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class IwsTrustAllCerts : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) { return true; }
}
"@
  }
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object IwsTrustAllCerts
  [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
}

$RestUrl = (Get-EnvFileValue "$RepoRoot\.env" "DOMINO_REST_API_DATA_URL").TrimEnd("/")
$user = Get-EnvFileValue "$RepoRoot\.env" "REST_ADMIN_USER"
$pass = Get-EnvFileValue "$RepoRoot\.env" "REST_ADMIN_PASSWORD"
$auth = Invoke-RestMethod -Method POST -Uri "$RestUrl/api/v1/auth" -ContentType "application/json" `
  -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($auth.bearer)" }

# Rare typed collections (plural path → type key)
$rare = [ordered]@{
  jars = "jar"
  applets = "applet"
  javasources = "javasource"
  webcontent = "webcontent"
  webservices = "webservice"
  webserviceconsumers = "webserviceconsumer"
  dataconnections = "dataconnection"
  wiringproperties = "wiringproperties"
  compositecomponents = "compositecomponent"
  sharedcolumns = "sharedcolumn"
}

function Get-Count($nsf, $plural) {
  $uri = "$RestUrl/api/kyber-v1/$plural?nsfPath=$([uri]::EscapeDataString($nsf))"
  try {
    $r = Invoke-RestMethod -Uri $uri -Headers $h -TimeoutSec 60
    if ($null -ne $r.count) { return [int]$r.count }
    if ($r.elements) { return @($r.elements).Count }
    if ($r.names) { return @($r.names).Count }
    return 0
  } catch {
    return -1
  }
}

# Candidate paths: known templates + directory listing (databases + templates)
$candidates = New-Object System.Collections.Generic.List[string]
foreach ($p in @(
    "teamrm145.ntf", "bookmark.ntf", "pernames.ntf", "mail145.ntf",
    "dominoblog.ntf", "discussion145.ntf", "doclbw7.ntf", "nntpdi50.ntf",
    "rt_discussion.nsf", "rt_blog.nsf", "rt_nntp.nsf", "rt_resource.nsf",
    "KeepConfig.nsf", "KeepAgents.ntf", "domcfg5.ntf", "admin4.ntf"
  )) {
  [void]$candidates.Add($p)
}

foreach ($fileType in @("template", "database")) {
  try {
    $list = Invoke-RestMethod -Uri "$RestUrl/api/kyber-v1/databases?fileType=$fileType" -Headers $h -TimeoutSec 120
    foreach ($db in @($list.databases)) {
      $path = [string]$db.path
      if ([string]::IsNullOrWhiteSpace($path)) { $path = [string]$db.nsfPath }
      if ([string]::IsNullOrWhiteSpace($path)) { continue }
      if (-not $candidates.Contains($path)) { [void]$candidates.Add($path) }
    }
  } catch {
    Write-Host "databases?fileType=$fileType failed: $($_.Exception.Message)"
  }
}

Write-Host ("Scanning up to {0} of {1} paths for rare types…" -f [Math]::Min($MaxDbs, $candidates.Count), $candidates.Count)
$hits = @()
$n = 0
foreach ($nsf in $candidates) {
  if ($n -ge $MaxDbs) { break }
  $n++
  $found = [ordered]@{}
  $any = $false
  foreach ($plural in $rare.Keys) {
    $c = Get-Count $nsf $plural
    if ($c -gt 0) {
      $found[$rare[$plural]] = $c
      $any = $true
    }
  }
  if ($any) {
    $line = ($found.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ", "
    Write-Host ("HIT {0} :: {1}" -f $nsf, $line)
    $hits += [pscustomobject]@{ path = $nsf; types = $line; map = $found }
  } elseif (($n % 15) -eq 0) {
    Write-Host ("… scanned {0}" -f $n)
  }
}

Write-Host ""
Write-Host "======== HITS $($hits.Count) ========"
$byType = @{}
foreach ($t in $rare.Values) { $byType[$t] = @() }
foreach ($hrow in $hits) {
  foreach ($kv in $hrow.map.GetEnumerator()) {
    $byType[$kv.Key] = @($byType[$kv.Key] + $hrow.path)
  }
}
foreach ($t in ($byType.Keys | Sort-Object)) {
  $paths = @($byType[$t] | Select-Object -Unique)
  if ($paths.Count -eq 0) {
    Write-Host ("MISSING {0}" -f $t)
  } else {
    Write-Host ("{0}: {1}" -f $t, ($paths -join ", "))
  }
}

$out = Join-Path $RepoRoot "ignore\rare-type-hits.json"
$hits | ConvertTo-Json -Depth 6 | Set-Content $out -Encoding UTF8
Write-Host "Wrote $out"
