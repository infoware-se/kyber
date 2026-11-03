<#
.SYNOPSIS
  Create kyber_blank.nsf the same way Kyber creates an empty NSF (POST /database).

  Dev only — open in Designer to inspect what a new Kyber-enabled database contains
  (title, Kyber icon, catalog category, launch of $MainFrameset, blank chrome DXL).
  XPages skeleton is not applied on empty create; it is ensured on first XPage /
  Java design write. Not used at runtime.

.PARAMETER Recreate
  DELETE kyber_blank.nsf first if it exists, then POST /database again.

.PARAMETER RestUrl
  Domino REST base URL. Default: DOMINO_REST_API_DATA_URL from env / .env

.PARAMETER SkipCertificateCheck
  Accept any TLS certificate.
#>
param(
  [switch]$Recreate,
  [string]$RestUrl = "",
  [string]$Token = "",
  [switch]$SkipCertificateCheck
)

$NsfPath = "kyber_blank.nsf"
$DbTitle = "Kyber Blank"
$ErrorActionPreference = "Stop"
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

function Get-BearerToken {
  if ($Token) { return $Token }
  $user = $env:REST_ADMIN_USER; if (-not $user) { $user = $env:DOMINO_REST_USER }
  $pass = $env:REST_ADMIN_PASSWORD; if (-not $pass) { $pass = $env:DOMINO_REST_PASSWORD }
  foreach ($f in @("$RepoRoot\.env", ".\.env")) {
    if (-not $user) {
      $user = Get-EnvFileValue $f "REST_ADMIN_USER"
      if (-not $user) { $user = Get-EnvFileValue $f "DOMINO_REST_USER" }
    }
    if (-not $pass) {
      $pass = Get-EnvFileValue $f "REST_ADMIN_PASSWORD"
      if (-not $pass) { $pass = Get-EnvFileValue $f "DOMINO_REST_PASSWORD" }
    }
  }
  if (-not $user -or -not $pass) { throw "Set -Token or REST_ADMIN_USER / REST_ADMIN_PASSWORD" }
  $auth = Invoke-Drapi -Method POST -Uri "$RestUrl/api/v1/auth" `
    -Headers @{} -ContentType "application/json" `
    -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
  return $auth.bearer
}

if (-not $RestUrl) {
  $RestUrl = $env:DOMINO_REST_API_DATA_URL
  if (-not $RestUrl) { $RestUrl = $env:DOMINO_REST_URL }
}
foreach ($f in @("$RepoRoot\.env", ".\.env")) {
  if (-not $RestUrl) {
    $RestUrl = Get-EnvFileValue $f "DOMINO_REST_API_DATA_URL"
    if (-not $RestUrl) { $RestUrl = Get-EnvFileValue $f "DOMINO_REST_URL" }
  }
}
if (-not $RestUrl) { throw "Pass -RestUrl or set DOMINO_REST_API_DATA_URL" }
$RestUrl = $RestUrl.TrimEnd("/")

$bearer = Get-BearerToken
$headers = @{ Authorization = "Bearer $bearer" }
$q = "nsfPath=$([uri]::EscapeDataString($NsfPath))&dbTitle=$([uri]::EscapeDataString($DbTitle))"

if ($Recreate) {
  Write-Host "DELETE $NsfPath (recreate)"
  $deleted = $false
  foreach ($try in 1..8) {
    try {
      Invoke-Drapi -Method DELETE -Uri "$RestUrl/api/kyber-v1/database?$q" -Headers $headers | Out-Null
      $deleted = $true
      break
    } catch {
      $detail = $_.ErrorDetails.Message
      if (-not $detail) { $detail = $_.Exception.Message }
      if ($detail -match 'in use|0x229') {
        Write-Host "DELETE try ${try}: database in use — close Designer/clients, retrying…"
        Start-Sleep -Seconds 3
        continue
      }
      if ($detail -match '404|NotFound|not found|does not exist') {
        $deleted = $true
        break
      }
      throw
    }
  }
  if (-not $deleted) {
    throw "Could not delete $NsfPath (still in use). Close it in Designer and retry."
  }
}

Write-Host "POST /database → $NsfPath (same path as CreateDatabase)"
try {
  $result = Invoke-Drapi -Method POST -Uri "$RestUrl/api/kyber-v1/database?$q" -Headers $headers
  Write-Host ("created={0} nsfPath={1} dbTitle={2}" -f $result.created, $result.nsfPath, $result.dbTitle)
  $result | ConvertTo-Json -Depth 4 | Write-Output
} catch {
  $msg = $_.Exception.Message
  if ($msg -match '409|already exists' -or ($_.ErrorDetails.Message -match 'already exists')) {
    Write-Host "$NsfPath already exists. Pass -Recreate to delete and create again."
    throw
  }
  throw
}

Write-Host "Open $NsfPath in Designer to inspect a Kyber empty-create NSF."
