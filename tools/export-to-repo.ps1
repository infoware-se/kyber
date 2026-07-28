<#
.SYNOPSIS
  Export Domino design (bulk DXL) into a git-friendly folder tree.

.PARAMETER RestUrl
  Domino REST base URL, e.g. https://host:8880

.PARAMETER NsfPath
  Target NSF path (relative to Domino data directory)

.PARAMETER OutDir
  Output directory (default: .\design)

.PARAMETER Token
  Bearer JWT. If omitted, authenticates with DOMINO_REST_USER / DOMINO_REST_PASSWORD from env or .env
#>
param(
  [Parameter(Mandatory = $true)][string]$RestUrl,
  [Parameter(Mandatory = $true)][string]$NsfPath,
  [string]$OutDir = ".\design",
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
    if (-not $RestUrl -or $RestUrl -eq "") {
      $u = Get-EnvFileValue $f "DOMINO_REST_URL"
      if (-not $u) { $u = Get-EnvFileValue $f "DOMINO_REST_API_DATA_URL" }
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

function Sanitize-Name([string]$Name) {
  if (-not $Name) { return "_unnamed" }
  $s = $Name -replace '[\\/:*?"<>|]', '_'
  $s = $s.Trim().TrimEnd('.')
  if (-not $s) { return "_unnamed" }
  return $s
}

function Get-DxlDoctype([string]$Dxl) {
  if ($Dxl -match '(?i)<!DOCTYPE\s+([A-Za-z][A-Za-z0-9_]*)') {
    return $Matches[1]
  }
  # Fallback: first root element after optional XML declaration
  $m = [regex]::Match($Dxl, '(?is)^\s*(?:<\?xml[^>]*\?>\s*)?<([A-Za-z][A-Za-z0-9_]*)\b')
  if ($m.Success) { return $m.Groups[1].Value }
  return "unknown"
}

function Get-UniqueRelPath([string]$OutDir, [string]$Dir, [string]$BaseName) {
  $file = "$BaseName.dxl"
  $rel = Join-Path $Dir $file
  $full = Join-Path $OutDir $rel
  $n = 2
  while (Test-Path $full) {
    $file = "${BaseName}-$n.dxl"
    $rel = Join-Path $Dir $file
    $full = Join-Path $OutDir $rel
    $n++
  }
  return @{ Rel = ($rel -replace '\\', '/'); Full = $full }
}

if (-not $RestUrl) {
  $RestUrl = $env:DOMINO_REST_URL
  if (-not $RestUrl) { $RestUrl = $env:DOMINO_REST_API_DATA_URL }
}
$RestUrl = $RestUrl.TrimEnd("/")
$bearer = Get-BearerToken
$headers = @{ Authorization = "Bearer $bearer" }

$uri = "$RestUrl/api/design-v1/designdxl?nsfPath=$([uri]::EscapeDataString($NsfPath))"
Write-Host "GET $uri"
$payload = Invoke-Drapi -Method GET -Uri $uri -Headers $headers

if (Test-Path $OutDir) {
  Remove-Item -Recurse -Force $OutDir
}
New-Item -ItemType Directory -Path $OutDir | Out-Null

$index = @()
foreach ($el in $payload.elements) {
  $type = [string]$el.type
  $name = if ($null -ne $el.name) { [string]$el.name } else { $null }
  $dxl = [string]$el.dxl
  $doctype = Get-DxlDoctype $dxl
  if ([string]::IsNullOrWhiteSpace($doctype)) { $doctype = "unknown" }

  # Folder = DOCTYPE local name (form, imageresource, helpaboutdocument, note, …)
  $dir = $doctype.ToLowerInvariant()
  $sub = Join-Path $OutDir $dir
  if (-not (Test-Path $sub)) { New-Item -ItemType Directory -Path $sub | Out-Null }

  $base = if ($name) { Sanitize-Name $name } else { Sanitize-Name $type }
  $paths = Get-UniqueRelPath -OutDir $OutDir -Dir $dir -BaseName $base

  [System.IO.File]::WriteAllText($paths.Full, $dxl, [System.Text.UTF8Encoding]::new($false))

  $entry = @{
    type = $type
    doctype = $doctype
    path = $paths.Rel
  }
  if ($name) { $entry.name = $name }
  $index += $entry
}

$manifest = @{
  nsfPath = $NsfPath
  exportedAt = (Get-Date).ToUniversalTime().ToString("o")
  layout = "doctype"
  elementCount = $index.Count
  elements = $index
}
$manifestPath = Join-Path $OutDir "manifest.json"
[System.IO.File]::WriteAllText(
  $manifestPath,
  ($manifest | ConvertTo-Json -Depth 6),
  [System.Text.UTF8Encoding]::new($false))

Write-Host "Wrote $($index.Count) elements to $OutDir (folders by DXL DOCTYPE)"
