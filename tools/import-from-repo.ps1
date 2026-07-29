<#
.SYNOPSIS
  Import a design folder tree (from export-to-repo.ps1) into an existing NSF via bulk PUT.
  The NSF must already exist.

.PARAMETER RestUrl
  Domino REST base URL, e.g. https://host:8880

.PARAMETER NsfPath
  Target NSF path (relative to Domino data directory)

.PARAMETER InDir
  Input directory containing manifest.json and .dxl files (default: .\design)

.PARAMETER Token
  Bearer JWT. If omitted, authenticates with env / .env credentials
#>
param(
  [Parameter(Mandatory = $true)][string]$RestUrl,
  [Parameter(Mandatory = $true)][string]$NsfPath,
  [string]$InDir = ".\design",
  [string]$Token = ""
)

$ErrorActionPreference = "Stop"

function Enable-InsecureSsl {
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
  if ($PSVersionTable.PSVersion.Major -ge 6) { $p.SkipCertificateCheck = $true }
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

$uri = "$RestUrl/api/design-v1/designdxl?nsfPath=$([uri]::EscapeDataString($NsfPath))"
Write-Host "PUT $uri ($($elements.Count) elements)"
$result = Invoke-Drapi -Method PUT -Uri $uri -Headers $headers -Body $bodyJson -ContentType "application/json; charset=utf-8"

Write-Host ("imported={0} failed={1} nsfPath={2}" -f $result.imported, $result.failed, $result.nsfPath)
$result | ConvertTo-Json -Depth 4 | Write-Output
