<#
.SYNOPSIS
  Import a design folder tree (from export-to-repo.ps1) into an NSF via Kyber bulk PUT /api/kyber-v1/designdxl.
  Creates the NSF if it does not exist.

.PARAMETER RestUrl
  Domino REST base URL, e.g. https://host:8880

.PARAMETER NsfPath
  Target NSF path (relative to Domino data directory)

.PARAMETER InDir
  NSF folder (manifest.json inside it) or a repo root that contains domino\.
  When omitted, uses the only design tree under .\domino. Pass the NSF folder when several are there.

.PARAMETER Token
  Bearer JWT. If omitted, authenticates with env / .env credentials

.PARAMETER Encryption
  Encryption for a NSF this import creates (none, simple, medium, strong, aes128, aes256).
  Ignored when the NSF already exists. Default is the API default, none.

.PARAMETER SkipCertificateCheck
  Accept any TLS certificate. Leave this off for a certificate the machine trusts.
#>
param(
  [Parameter(Mandatory = $true)][string]$RestUrl,
  [Parameter(Mandatory = $true)][string]$NsfPath,
  [string]$InDir = "",
  [string]$Token = "",
  [string]$Encryption = "",
  [switch]$SkipCertificateCheck
)

$ErrorActionPreference = "Stop"

function Enable-InsecureSsl {
  if (-not $SkipCertificateCheck) { return }
  if ($PSVersionTable.PSVersion.Major -ge 6) { return }
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

function Invoke-Drapi {
  param([string]$Method, [string]$Uri, [hashtable]$Headers, [string]$Body = $null, [string]$ContentType = $null)
  Enable-InsecureSsl
  $p = @{ Method = $Method; Uri = $Uri; Headers = $Headers }
  if ($Body) { $p.Body = $Body }
  if ($ContentType) { $p.ContentType = $ContentType }
  if ($SkipCertificateCheck -and $PSVersionTable.PSVersion.Major -ge 6) { $p.SkipCertificateCheck = $true }
  return Invoke-RestMethod @p
}

function Get-EnvFileValue([string]$Path, [string]$Key) {
  if (-not (Test-Path $Path)) { return $null }
  foreach ($line in Get-Content $Path) {
    if ($line -match "^\s*#" -or $line -notmatch "=") { continue }
    $k, $v = $line -split "=", 2
    if ($k.Trim() -eq $Key) { return $v.Trim().Trim('"').Trim("'") }
  }
  return $null
}

function Get-BearerToken {
  if ($Token) { return $Token }
  $user = $env:DOMINO_REST_USER
  if (-not $user) { $user = $env:REST_ADMIN_USER }
  $pass = $env:DOMINO_REST_PASSWORD
  if (-not $pass) { $pass = $env:REST_ADMIN_PASSWORD }
  foreach ($f in @(".\.env", ".\.env.drapi", "$PSScriptRoot\..\.env")) {
    if (-not $user) {
      $user = Get-EnvFileValue $f "DOMINO_REST_USER"
      if (-not $user) { $user = Get-EnvFileValue $f "REST_ADMIN_USER" }
    }
    if (-not $pass) {
      $pass = Get-EnvFileValue $f "DOMINO_REST_PASSWORD"
      if (-not $pass) { $pass = Get-EnvFileValue $f "REST_ADMIN_PASSWORD" }
    }
  }
  if (-not $user -or -not $pass) {
    throw "Set -Token or DOMINO_REST_USER/REST_ADMIN_USER and password env vars"
  }
  $auth = Invoke-Drapi -Method POST -Uri "$RestUrl/api/v1/auth" `
    -Headers @{} -ContentType "application/json" `
    -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
  return $auth.bearer
}

function Resolve-OneDesignTree([string]$DominoDir) {
  $found = @()
  if (Test-Path -LiteralPath $DominoDir) {
    $found = @(Get-ChildItem -LiteralPath $DominoDir -Recurse -Filter manifest.json -File)
  }
  if ($found.Count -eq 1) { return $found[0].DirectoryName }
  if ($found.Count -eq 0) {
    throw "Pass -InDir. No design tree under $DominoDir"
  }
  $list = ($found | ForEach-Object { $_.DirectoryName }) -join ", "
  throw "Pass -InDir. Several design trees: $list"
}

if (-not $InDir) {
  $InDir = Resolve-OneDesignTree (Join-Path (Get-Location).Path "domino")
} else {
  if (-not [System.IO.Path]::IsPathRooted($InDir)) {
    $InDir = Join-Path (Get-Location).Path $InDir
  }
  $direct = Join-Path $InDir "manifest.json"
  if (-not (Test-Path -LiteralPath $direct)) {
    $InDir = Resolve-OneDesignTree (Join-Path $InDir "domino")
  }
}

$manifestPath = Join-Path $InDir "manifest.json"
if (-not (Test-Path $manifestPath)) {
  throw "manifest.json not found in $InDir"
}

$manifest = Get-Content -Raw $manifestPath | ConvertFrom-Json
$elements = @()

foreach ($entry in $manifest.elements) {
  $rel = [string]$entry.path
  $full = Join-Path $InDir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
  if (-not (Test-Path $full)) {
    throw "Missing DXL file: $full"
  }
  $dxl = [System.IO.File]::ReadAllText($full)
  $obj = @{
    type = [string]$entry.type
    dxl = $dxl
  }
  if ($entry.name) { $obj.name = [string]$entry.name }
  $elements += $obj
}

$RestUrl = $RestUrl.TrimEnd("/")
$bearer = Get-BearerToken
$headers = @{
  Authorization = "Bearer $bearer"
  "Content-Type" = "application/json; charset=utf-8"
}

$bodyObj = @{ elements = $elements }
$bodyJson = $bodyObj | ConvertTo-Json -Depth 20
$aclCount = @($elements | Where-Object { [string]$_.type -eq 'acl' }).Count
if ($aclCount -gt 0) {
  Write-Host "ACL present in manifest - will be applied last by PUT /designdxl"
}

$uri = "$RestUrl/api/kyber-v1/designdxl?nsfPath=$([uri]::EscapeDataString($NsfPath))"
if ($Encryption) {
  $uri = "$uri&encryption=$([uri]::EscapeDataString($Encryption))"
}
Write-Host ("PUT {0} ({1} elements)" -f $uri, $elements.Count)
$result = Invoke-Drapi -Method PUT -Uri $uri -Headers $headers -Body $bodyJson -ContentType "application/json; charset=utf-8"

$failed = 0
if ($null -ne $result.failed) { $failed = [int]$result.failed }
Write-Host ("imported={0} failed={1} created={2} aclApplied={3} nsfPath={4}" -f $result.imported, $failed, $result.created, $result.aclApplied, $result.nsfPath)
$result | ConvertTo-Json -Depth 4 | Write-Output
if ($result.success -eq $false -or $failed -gt 0) {
  foreach ($row in @($result.results)) {
    if ($row.success -eq $false) {
      Write-Host ("FAIL {0} {1}: {2}" -f $row.type, $row.name, $row.message)
    }
  }
  throw "Import incomplete: failed=$failed"
}
