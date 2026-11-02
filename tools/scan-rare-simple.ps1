param([switch]$SkipCertificateCheck)
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

$checks = @(
  @{ nsf = "rt_pab.nsf"; path = "sharedcolumns"; type = "sharedcolumn" },
  @{ nsf = "rt_pab.nsf"; path = "wiringproperties"; type = "wiringproperties" },
  @{ nsf = "rt_pab.nsf"; path = "compositeapplications"; type = "compositeapplication" },
  @{ nsf = "rt_pab.nsf"; path = "jars"; type = "jar" },
  @{ nsf = "rt_pab.nsf"; path = "webservices"; type = "webservice" },
  @{ nsf = "rt_pab.nsf"; path = "webcontent"; type = "webcontent" },
  @{ nsf = "rt_pab.nsf"; path = "javasources"; type = "javasource" },
  @{ nsf = "rt_pab.nsf"; path = "applets"; type = "applet" },
  @{ nsf = "rt_pab.nsf"; path = "dataconnections"; type = "dataconnection" },
  @{ nsf = "rt_pab.nsf"; path = "compositecomponents"; type = "compositecomponent" },
  @{ nsf = "rt_bookmark.nsf"; path = "wiringproperties"; type = "wiringproperties" },
  @{ nsf = "rt_bookmark.nsf"; path = "compositeapplications"; type = "compositeapplication" },
  @{ nsf = "rt_teamroom_types.nsf"; path = "sharedcolumns"; type = "sharedcolumn" },
  @{ nsf = "rt_discussion.nsf"; path = "compositeapplications"; type = "compositeapplication" },
  @{ nsf = "rt_discussion.nsf"; path = "webcontent"; type = "webcontent" },
  @{ nsf = "rt_blog.nsf"; path = "navigators"; type = "navigator" }
)

$found = @{}
foreach ($c in $checks) {
  $uri = "$RestUrl/api/kyber-v1/$($c.path)?nsfPath=$([uri]::EscapeDataString($c.nsf))"
  try {
    $r = Invoke-RestMethod -Uri $uri -Headers $headers
    $n = @($r.elements).Count
    if ($n -gt 0) {
      Write-Host ("FOUND {0} on {1}: {2}" -f $c.type, $c.nsf, $n)
      if (-not $found.ContainsKey($c.type)) { $found[$c.type] = @() }
      $found[$c.type] += $c.nsf
    } else {
      Write-Host ("empty {0} on {1}" -f $c.type, $c.nsf)
    }
  } catch {
    $msg = $_.Exception.Message
    if ($_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
    Write-Host ("FAIL {0} on {1}: {2}" -f $c.type, $c.nsf, $msg)
  }
}

Write-Host ""
Write-Host "Wanted rare types — HCL hits only:"
foreach ($t in @("sharedcolumn","wiringproperties","compositeapplication","compositecomponent","jar","applet","javasource","webcontent","webservice","webserviceconsumer","dataconnection")) {
  if ($found.ContainsKey($t)) { Write-Host ("  {0}: {1}" -f $t, ($found[$t] -join ", ")) }
  else { Write-Host ("  MISSING {0}" -f $t) }
}
