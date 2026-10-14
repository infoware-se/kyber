<#
.SYNOPSIS
  Compare Domino design sets: Notes COM DXL file vs Kyber bulk /designdxl (by UNID).
  ACL is not part of COM SelectAllDesignElements / UNID compare — use GET /acl separately.

.EXAMPLE
  cscript //nologo .\tools\export-design-dxl-com.vbs $env:TEMP\com.xml YourDominoServer test\testdiscussion.nsf
  .\tools\compare-design-export.ps1 -ComDxlPath $env:TEMP\com.xml -NsfPath test/testdiscussion.nsf
#>
param(
  [Parameter(Mandatory = $true)][string]$ComDxlPath,
  [Parameter(Mandatory = $true)][string]$NsfPath,
  [string]$RestUrl = $env:DOMINO_REST_API_DATA_URL,
  [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"

function Import-EnvFile([string]$path) {
  if (-not (Test-Path $path)) { return }
  Get-Content $path | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $k, $v = $_.Split('=', 2)
    Set-Item -Path "env:$($k.Trim())" -Value $v.Trim().Trim('"').Trim("'")
  }
}

function Ensure-CertTrust {
  Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class IwsCompareTrust : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint sp, X509Certificate c, WebRequest r, int p) { return true; }
}
"@ -ErrorAction SilentlyContinue
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object IwsCompareTrust
  [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
}

function Get-UnidsFromDxl([string]$dxl) {
  $set = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
  foreach ($m in [regex]::Matches($dxl, "\bunid=['""]([A-Fa-f0-9]{32})['""]")) {
    [void]$set.Add($m.Groups[1].Value.ToUpperInvariant())
  }
  return $set
}

function Get-BulkUnids($elements) {
  $set = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
  $byUnid = @{}
  foreach ($e in $elements) {
    $dxl = [string]$e.dxl
    $m = [regex]::Match($dxl, "\bunid=['""]([A-Fa-f0-9]{32})['""]")
    $u = $null
    if ($m.Success) {
      $u = $m.Groups[1].Value.ToUpperInvariant()
    } elseif ([string]$e.name -match '^unid:([A-Fa-f0-9]{32})$') {
      $u = $Matches[1].ToUpperInvariant()
    }
    if (-not $u) { continue }
    [void]$set.Add($u)
    if (-not $byUnid.ContainsKey($u)) {
      $byUnid[$u] = [pscustomobject]@{ type = $e.type; name = $e.name }
    }
  }
  return @{ Set = $set; Map = $byUnid }
}

if ($EnvFile) {
  Import-EnvFile $EnvFile
} else {
  foreach ($f in @(".\.env", ".\.env.drapi", "$PSScriptRoot\..\.env")) {
    Import-EnvFile $f
  }
}
if (-not $RestUrl) { $RestUrl = $env:DOMINO_REST_API_DATA_URL }
if (-not $RestUrl) { $RestUrl = $env:DOMINO_REST_URL }
if (-not $RestUrl) { throw "RestUrl / DOMINO_REST_API_DATA_URL required" }
if (-not (Test-Path $ComDxlPath)) { throw "COM DXL not found: $ComDxlPath" }

Ensure-CertTrust
$auth = Invoke-RestMethod -Method POST -Uri "$RestUrl/api/v1/auth" -ContentType "application/json" `
  -Body (@{ username = $env:REST_ADMIN_USER; password = $env:REST_ADMIN_PASSWORD } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($auth.bearer)" }

$comDxl = [System.IO.File]::ReadAllText($ComDxlPath)
$comUnids = Get-UnidsFromDxl $comDxl
$bulk = Invoke-RestMethod -Uri "$RestUrl/api/kyber-v1/designdxl?nsfPath=$([uri]::EscapeDataString($NsfPath))" -Headers $headers
$bulkInfo = Get-BulkUnids $bulk.elements
$bulkUnids = $bulkInfo.Set

$onlyCom = @($comUnids | Where-Object { -not $bulkUnids.Contains($_) } | Sort-Object)
$onlyBulk = @($bulkUnids | Where-Object { -not $comUnids.Contains($_) } | Sort-Object)
$covered = $comUnids.Count - $onlyCom.Count
$pct = if ($comUnids.Count) { [math]::Round(100.0 * $covered / $comUnids.Count, 1) } else { 0 }

Write-Host "NSF: $NsfPath"
Write-Host ("COM  file={0}  unids={1}" -f $ComDxlPath, $comUnids.Count)
Write-Host ("Bulk elements={0}  unids={1}" -f $bulk.elements.Count, $bulkUnids.Count)
Write-Host ("COM covered by bulk: {0} / {1} = {2}%" -f $covered, $comUnids.Count, $pct)
Write-Host ("Only in COM:  {0}" -f $onlyCom.Count)
Write-Host ("Only in Bulk: {0}" -f $onlyBulk.Count)

if ($onlyCom.Count) {
  Write-Host "`nMissing from bulk (first 30 UNIDs):"
  $onlyCom | Select-Object -First 30 | ForEach-Object { "  $_" }
}
if ($onlyBulk.Count) {
  Write-Host "`nExtra in bulk:"
  foreach ($u in ($onlyBulk | Select-Object -First 30)) {
    $meta = $bulkInfo.Map[$u]
    "  $u type=$($meta.type) name=$($meta.name)"
  }
}

if ($onlyCom.Count -eq 0 -and $onlyBulk.Count -eq 0) {
  Write-Host "`nOK: identical design note set (by UNID)."
  exit 0
}
Write-Host "`nDIFF: design note sets differ."
exit 2
