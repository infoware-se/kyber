<#
.SYNOPSIS
  Dev NSF for JAR element defaults. Runtime never opens kyber_jar.nsf.

  Holds one starter note per top-level defaults/*.dxl (not blank/, not kyber_blank.nsf).
  Edit in Designer, then -Sync into the JAR. Empty-create chrome stays in defaults/blank/
  and kyber_blank.nsf only.

.PARAMETER Seed
  Import current top-level defaults/*.dxl into kyber_jar.nsf (skips kyber-dbicon.dxl,
  blank/, xpages-project/). Creates the NSF via import if missing.

.PARAMETER Recreate
  With -Seed: DELETE kyber_jar.nsf first, then seed again.

.PARAMETER Sync
  Export design from kyber_jar.nsf into extension/src/main/resources/defaults/*.dxl.
  Matches each starter file by type and name=. Does not touch blank/.

.PARAMETER RestUrl
  Domino REST base URL. Default: DOMINO_REST_API_DATA_URL from env / .env

.PARAMETER DefaultsDir
  Folder of JAR starter DXL. Default: extension/src/main/resources/defaults

.PARAMETER SkipCertificateCheck
  Accept any TLS certificate.
#>
param(
  [switch]$Seed,
  [switch]$Recreate,
  [switch]$Sync,
  [string]$RestUrl = "",
  [string]$DefaultsDir = "",
  [string]$Token = "",
  [switch]$SkipCertificateCheck
)

# Fixed filename — JAR starter defaults only (not blank chrome).
$NsfPath = "kyber_jar.nsf"
$DbTitle = "Kyber JAR"

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
if (-not $DefaultsDir) {
  $DefaultsDir = Join-Path $RepoRoot "extension\src\main\resources\defaults"
}
if (-not $Seed -and -not $Sync) {
  throw "Pass -Seed and/or -Sync"
}
if ($Recreate -and -not $Seed) {
  throw "-Recreate requires -Seed"
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
    if (-not $script:ResolvedRestUrl) {
      $script:ResolvedRestUrl = Get-EnvFileValue $f "DOMINO_REST_API_DATA_URL"
      if (-not $script:ResolvedRestUrl) { $script:ResolvedRestUrl = Get-EnvFileValue $f "DOMINO_REST_URL" }
    }
  }
  if (-not $user -or -not $pass) { throw "Set -Token or REST_ADMIN_USER / REST_ADMIN_PASSWORD" }
  $auth = Invoke-Drapi -Method POST -Uri "$RestUrl/api/v1/auth" `
    -Headers @{} -ContentType "application/json" `
    -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
  return $auth.bearer
}

function Get-DxlRootName([string]$Dxl) {
  # Prefer root-element name= (form/view/…), never <item name=…>.
  if ($Dxl -match "(?is)<(?!item\b)[A-Za-z][\w.-]*\b[^>]*\bname\s*=\s*'([^']+)'") {
    return $Matches[1]
  }
  if ($Dxl -match '(?is)<(?!item\b)[A-Za-z][\w.-]*\b[^>]*\bname\s*=\s*"([^"]+)"') {
    return $Matches[1]
  }
  # note-form exports: <item name='$TITLE'><text>…</text>  (single-quoted patterns — $TITLE must stay literal)
  if ($Dxl -match '(?is)<item\s+[^>]*\bname\s*=\s*''\$TITLE''[^>]*>\s*<text>([^<]*)</text>') {
    $t = $Matches[1].Trim()
    if ($t) { return $t }
  }
  if ($Dxl -match '(?is)<item\s+[^>]*\bname\s*=\s*"\$TITLE"[^>]*>\s*<text>([^<]*)</text>') {
    $t = $Matches[1].Trim()
    if ($t) { return $t }
  }
  return $null
}

function Get-StarterFiles([string]$Dir) {
  Get-ChildItem -LiteralPath $Dir -File -Filter *.dxl |
    Where-Object { $_.Name -ne "kyber-dbicon.dxl" } |
    Sort-Object Name
}

function Get-TypeFromStarterFile([string]$FileName) {
  $base = [IO.Path]::GetFileNameWithoutExtension($FileName)
  switch -Regex ($base) {
    '^agent(-|$)' { return "agent" }
    '^scriptlibrary(-|$)' { return "scriptlibrary" }
    '^javasource(-|$)' { return "javasource" }
    default { return $base }
  }
}

if (-not $RestUrl) {
  $RestUrl = $env:DOMINO_REST_API_DATA_URL
  if (-not $RestUrl) { $RestUrl = $env:DOMINO_REST_URL }
}
$script:ResolvedRestUrl = $null
if (-not $RestUrl) {
  # Get-BearerToken fills ResolvedRestUrl from .env when needed
  $null = $null
}
# Pre-read .env for URL before auth
foreach ($f in @("$RepoRoot\.env", ".\.env")) {
  if (-not $RestUrl) {
    $RestUrl = Get-EnvFileValue $f "DOMINO_REST_API_DATA_URL"
    if (-not $RestUrl) { $RestUrl = Get-EnvFileValue $f "DOMINO_REST_URL" }
  }
}
if (-not $RestUrl) { throw "Pass -RestUrl or set DOMINO_REST_API_DATA_URL" }
$RestUrl = $RestUrl.TrimEnd("/")

if (-not (Test-Path -LiteralPath $DefaultsDir)) {
  throw "DefaultsDir not found: $DefaultsDir"
}

$bearer = Get-BearerToken
$headers = @{ Authorization = "Bearer $bearer" }
$q = "nsfPath=$([uri]::EscapeDataString($NsfPath))&dbTitle=$([uri]::EscapeDataString($DbTitle))"

function Remove-KyberJarNsf {
  Write-Host "DELETE $NsfPath"
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

if ($Seed) {
  if ($Recreate) {
    Remove-KyberJarNsf
  }
  $files = @(Get-StarterFiles $DefaultsDir)
  if ($files.Count -lt 1) { throw "No starter DXL under $DefaultsDir" }
  $elements = @()
  foreach ($f in $files) {
    $dxl = [IO.File]::ReadAllText($f.FullName)
    $type = Get-TypeFromStarterFile $f.Name
    $name = Get-DxlRootName $dxl
    $row = @{ type = $type; dxl = $dxl }
    if ($name) { $row.name = $name }
    $elements += $row
  }
  $body = @{ elements = $elements } | ConvertTo-Json -Depth 8 -Compress
  $uri = "$RestUrl/api/kyber-v1/import?$q"
  Write-Host "SEED PUT /import → $NsfPath ($($elements.Count) starters from $DefaultsDir; not blank/)"
  $result = Invoke-Drapi -Method PUT -Uri $uri -Headers ($headers + @{ "Content-Type" = "application/json; charset=utf-8" }) `
    -Body $body -ContentType "application/json; charset=utf-8"
  $failed = 0
  if ($null -ne $result.failed) { $failed = [int]$result.failed }
  Write-Host ("seeded imported={0} failed={1} created={2} dbTitle={3}" -f `
    $result.imported, $failed, $result.created, $result.dbTitle)
  if ($result.success -eq $false -or $failed -gt 0) {
    foreach ($row in @($result.results)) {
      if ($row.success -eq $false) {
        Write-Host ("FAIL {0} {1}: {2}" -f $row.type, $row.name, $row.message)
      }
    }
    throw "Seed incomplete: failed=$failed"
  }
  Write-Host "Open $NsfPath in Designer. Run -Sync to write starters back into the JAR defaults."
}

if ($Sync) {
  $uri = "$RestUrl/api/kyber-v1/export?nsfPath=$([uri]::EscapeDataString($NsfPath))"
  Write-Host "SYNC GET /export ← $NsfPath"
  $exported = Invoke-Drapi -Method GET -Uri $uri -Headers $headers
  $byKey = @{}
  foreach ($el in @($exported.elements)) {
    $type = [string]$el.type
    $name = [string]$el.name
    if (-not $type -or -not $name) { continue }
    $key = ($type.ToLowerInvariant() + "|" + $name.ToLowerInvariant())
    $byKey[$key] = [string]$el.dxl
  }
  $written = 0
  $missing = @()
  foreach ($f in @(Get-StarterFiles $DefaultsDir)) {
    $dxlOnDisk = [IO.File]::ReadAllText($f.FullName)
    $type = Get-TypeFromStarterFile $f.Name
    $name = Get-DxlRootName $dxlOnDisk
    if (-not $name) {
      $missing += "$($f.Name) (no name= in file)"
      continue
    }
    $key = ($type.ToLowerInvariant() + "|" + $name.ToLowerInvariant())
    if (-not $byKey.ContainsKey($key)) {
      $missing += "$($f.Name) → $type/$name"
      continue
    }
    $newDxl = $byKey[$key]
    if ($newDxl -eq $dxlOnDisk) {
      Write-Host "unchanged $($f.Name)"
      continue
    }
    [IO.File]::WriteAllText($f.FullName, $newDxl)
    $written++
    Write-Host "wrote $($f.Name)"
  }
  Write-Host "sync wrote=$written missing=$($missing.Count)"
  foreach ($m in $missing) { Write-Host "MISSING $m" }
  if ($missing.Count -gt 0) {
    throw "Sync incomplete: $($missing.Count) starters not found in $NsfPath (keep the DXL name= when renaming in Designer, or update the defaults file name= first)"
  }
}
