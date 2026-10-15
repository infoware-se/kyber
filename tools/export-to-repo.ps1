<#
.SYNOPSIS
  Export Domino design via Kyber bulk GET /api/kyber-v1/designdxl into a git-friendly folder tree.

.PARAMETER RestUrl
  Domino REST base URL, e.g. https://host:8880

.PARAMETER NsfPath
  Target NSF path (relative to Domino data directory)

.PARAMETER OutDir
  Output directory (default: .\kyber)

.PARAMETER Token
  Bearer JWT. If omitted, authenticates with DOMINO_REST_USER / DOMINO_REST_PASSWORD from env or .env

.PARAMETER SkipTypes
  API types to omit from export (default: none)

.PARAMETER ReadableJava
  Strip compiled <javaresource> (.class Base64) from DXL and write sibling .java
  files extracted from <java name='...'>. Applies to Java script libraries, agents,
  and applets. Source stays in DXL; Domino recompiles on import.
  On by default. Pass -ReadableJava:`$false to keep binary .class in DXL.

.PARAMETER SkipCertificateCheck
  Accept any TLS certificate. Leave this off for a certificate the machine trusts.

.PARAMETER AllowSkipped
  Write the tree even when GET /designdxl reports skipped notes. Without this switch
  the script stops before replacing OutDir.
#>
param(
  [Parameter(Mandatory = $true)][string]$RestUrl,
  [Parameter(Mandatory = $true)][string]$NsfPath,
  [string]$OutDir = ".\kyber",
  [string]$Token = "",
  [string[]]$SkipTypes = @(),
  [bool]$ReadableJava = $true,
  [switch]$SkipCertificateCheck,
  [switch]$AllowSkipped
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

function Get-DxlNoteId([string]$Dxl) {
  $m = [regex]::Match($Dxl, "(?is)<noteinfo\b[^>]*\bnoteid\s*=\s*['""]([^'""]+)['""]")
  if ($m.Success) { return $m.Groups[1].Value.Trim() }
  return $null
}

function Get-DxlUnid([string]$Dxl) {
  $m = [regex]::Match($Dxl, "(?is)<noteinfo\b[^>]*\bunid\s*=\s*['""]([^'""]+)['""]")
  if ($m.Success) { return $m.Groups[1].Value.Trim() }
  return $null
}

# API type -> repo folder (CRUD + typed bulk). Empty = use type name as folder.
$script:RepoDirByType = [ordered]@{
  acl                   = "acl"
  about                 = "about"
  using                 = "using"
  sharedactions         = "sharedactions"
  databasescript        = "databasescript"
  image                 = "images"
  fileresource          = "fileresources"
  webcontent            = "webcontent"
  stylesheet            = "stylesheets"
  theme                 = "themes"
  scriptlibrary         = "scriptlibraries"
  sharedfield           = "sharedfields"
  sharedcolumn          = "sharedcolumns"
  subform               = "subforms"
  form                  = "forms"
  page                  = "pages"
  outline               = "outlines"
  navigator             = "navigators"
  frameset              = "framesets"
  view                  = "views"
  folder                = "folders"
  agent                 = "agents"
  webservice            = "webservices"
  webserviceconsumer    = "webserviceconsumers"
  dataconnection        = "dataconnections"
  jar                   = "jars"
  applet                = "applets"
  javasource            = "javasources"
  xpagesresource        = "xpagesresources"
  designnote            = "designnotes"
  customcontrol         = "customcontrols"
  xpage                 = "xpages"
  wiringproperties      = "wiringproperties"
  compositeapplication  = "compositeapplications"
  compositecomponent    = "compositecomponents"
  applicationproperties = "note"
  xspproperties         = "note"
}

function Get-ExportDir([string]$Type, [string]$Doctype) {
  $t = if ($Type) { $Type.Trim().ToLowerInvariant() } else { "" }
  $d = if ($Doctype) { $Doctype.Trim().ToLowerInvariant() } else { "unknown" }

  # CRUD / typed-bulk API types -> logical folders (even when DXL DOCTYPE is note)
  if ($t -and $script:RepoDirByType.Contains($t)) {
    $mapped = $script:RepoDirByType[$t]
    if ([string]::IsNullOrWhiteSpace($mapped)) { return $t }
    return $mapped
  }

  # Everything else (e.g. applicationproperties) stays under DXL DOCTYPE — often note/
  return $d
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
if (-not [System.IO.Path]::IsPathRooted($OutDir)) {
  $OutDir = Join-Path (Get-Location).Path $OutDir
}
$bearer = Get-BearerToken
$headers = @{ Authorization = "Bearer $bearer" }

$uri = "$RestUrl/api/kyber-v1/designdxl?nsfPath=$([uri]::EscapeDataString($NsfPath))"
Write-Host "GET $uri"
$payload = Invoke-Drapi -Method GET -Uri $uri -Headers $headers

$skipped = @($payload.skipped | Where-Object { $_ })
if ($skipped.Count -gt 0) {
  foreach ($s in $skipped) {
    Write-Warning ("skipped {0} {1}: {2}" -f $s.type, $s.name, $s.message)
  }
  if (-not $AllowSkipped) {
    throw "Export skipped $($skipped.Count) design note(s). Re-run with -AllowSkipped to write a partial tree."
  }
}

if (Test-Path $OutDir) {
  Remove-Item -Recurse -Force $OutDir
}
New-Item -ItemType Directory -Path $OutDir | Out-Null

$index = @()
foreach ($el in $payload.elements) {
  $type = [string]$el.type
  if ($SkipTypes -contains $type) { continue }
  $name = if ($null -ne $el.name) { [string]$el.name } else { $null }
  $dxl = [string]$el.dxl
  $doctype = Get-DxlDoctype $dxl
  if ([string]::IsNullOrWhiteSpace($doctype)) { $doctype = "unknown" }

  # Known API types (CRUD + typed bulk) -> logical folders; else DXL DOCTYPE
  $dir = Get-ExportDir -Type $type -Doctype $doctype
  $sub = Join-Path $OutDir $dir
  if (-not (Test-Path $sub)) { New-Item -ItemType Directory -Path $sub | Out-Null }

  $base = if ($name) { Sanitize-Name $name } else { Sanitize-Name $type }
  $noteId = Get-DxlNoteId $dxl
  $unid = Get-DxlUnid $dxl
  if ($noteId) {
    $base = "$base-$(Sanitize-Name $noteId)"
  } elseif ($unid) {
    # Fallback if noteid missing: last 8 of UNID keeps names unique/short
    $tail = if ($unid.Length -gt 8) { $unid.Substring($unid.Length - 8) } else { $unid }
    $base = "$base-$tail"
  }
  $paths = Get-UniqueRelPath -OutDir $OutDir -Dir $dir -BaseName $base

  $javaSidecars = @()
  if ($ReadableJava -and $dxl -match '<java\b') {
    $javaMatches = [regex]::Matches($dxl, "(?s)<java\s+name='([^']+\.java)'>(.*?)</java>")
    foreach ($m in $javaMatches) {
      $javaName = Sanitize-Name $m.Groups[1].Value
      $javaSrc = $m.Groups[2].Value
      $javaFull = Join-Path (Split-Path $paths.Full -Parent) $javaName
      if (Test-Path $javaFull) {
        $stem = [IO.Path]::GetFileNameWithoutExtension($javaName)
        $suffix = if ($noteId) { Sanitize-Name $noteId } else { Sanitize-Name $base }
        $javaName = "$stem-$suffix.java"
        $javaFull = Join-Path (Split-Path $paths.Full -Parent) $javaName
      }
      [System.IO.File]::WriteAllText($javaFull, $javaSrc.TrimEnd() + "`n", [System.Text.UTF8Encoding]::new($false))
      $javaSidecars += (Join-Path $dir $javaName).Replace('\', '/')
    }
    $dxl = [regex]::Replace($dxl, "(?s)<javaresource\b[^>]*>.*?</javaresource>", "")
  }

  [System.IO.File]::WriteAllText($paths.Full, $dxl, [System.Text.UTF8Encoding]::new($false))

  $entry = @{
    type = $type
    doctype = $doctype
    path = $paths.Rel
  }
  if ($javaSidecars.Count -gt 0) { $entry.javaSources = $javaSidecars }
  if ($name) { $entry.name = $name }
  if ($noteId) { $entry.noteid = $noteId }
  if ($unid) { $entry.unid = $unid }
  $index += $entry
}

$manifest = @{
  nsfPath = $NsfPath
  exportedAt = (Get-Date).ToUniversalTime().ToString("o")
  layout = "api-type"
  elementCount = $index.Count
  elements = $index
}
$manifestPath = Join-Path $OutDir "manifest.json"
[System.IO.File]::WriteAllText(
  $manifestPath,
  ($manifest | ConvertTo-Json -Depth 6),
  [System.Text.UTF8Encoding]::new($false))

Write-Host "Wrote $($index.Count) elements to $OutDir (folders by API type / CRUD)"
