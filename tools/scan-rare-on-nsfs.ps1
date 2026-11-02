param(
  [switch]$SkipCertificateCheck,
  [string[]]$NsfPath = @(
    "rt_pab.nsf",
    "rt_bookmark.nsf",
    "rt_teamroom_types.nsf",
    "rt_nntp.nsf",
    "rt_discussion.nsf",
    "rt_blog.nsf",
    "rt_office.nsf",
    "rt_doclibrary.nsf"
  )
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
$headers = @{ Authorization = "Bearer $($auth.bearer)" }

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
  compositeapplications = "compositeapplication"
  sharedcolumns = "sharedcolumn"
}

function Get-ListCount([string]$Nsf, [string]$Plural, $AuthHeaders) {
  $uri = "$RestUrl/api/kyber-v1/$Plural?nsfPath=$([uri]::EscapeDataString($Nsf))"
  try {
    $r = Invoke-RestMethod -Uri $uri -Headers $AuthHeaders -TimeoutSec 90
    $els = $r.elements
    if ($null -ne $els) { return @($els).Count }
    return 0
  } catch {
    return -1
  }
}

$byType = @{}
foreach ($t in @($rare.Values)) { $byType[$t] = @() }

foreach ($nsf in $NsfPath) {
  Write-Host "==== $nsf ===="
  $any = $false
  foreach ($plural in @($rare.Keys)) {
    $c = Get-ListCount $nsf $plural $headers
    if ($c -gt 0) {
      $any = $true
      $type = $rare[$plural]
      Write-Host ("  {0}={1}" -f $type, $c)
      $byType[$type] = @($byType[$type] + $nsf)
    } elseif ($c -lt 0) {
      Write-Host ("  ERR {0}" -f $plural)
    }
  }
  if (-not $any) { Write-Host "  (none of rare types)" }
}

Write-Host ""
Write-Host "======== BY TYPE ========"
foreach ($t in ($byType.Keys | Sort-Object)) {
  $paths = @($byType[$t] | Select-Object -Unique)
  if ($paths.Count -eq 0) { Write-Host "MISSING $t" }
  else { Write-Host ("{0}: {1}" -f $t, ($paths -join ", ")) }
}

# Note kyber_jar only as reference (not used as RT target per product choice)
Write-Host ""
Write-Host "==== reference kyber_jar.nsf (not an RT target) ===="
foreach ($plural in @($rare.Keys)) {
  $c = Get-ListCount "kyber_jar.nsf" $plural $headers
  if ($c -gt 0) { Write-Host ("  {0}={1}" -f $rare[$plural], $c) }
}
