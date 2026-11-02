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

$all = @(
  "acl", "applicationproperties", "about", "using", "sharedactions", "databasescript", "dbicon",
  "image", "fileresource", "webcontent", "stylesheet", "theme", "scriptlibrary", "sharedfield",
  "sharedcolumn", "subform", "form", "page", "outline", "navigator", "frameset", "view", "folder",
  "agent", "webservice", "webserviceconsumer", "dataconnection", "jar", "applet", "javasource",
  "xspproperties", "xpagesresource", "designnote", "customcontrol", "xpage", "wiringproperties",
  "compositeapplication", "compositecomponent"
)

$nsfs = @(
  "rt_notebook.nsf", "rt_resource.nsf", "rt_discussion.nsf", "rt_nntp.nsf", "rt_doclibrary.nsf",
  "rt_blog.nsf", "ias.nsf", "ias_servers.nsf", "rt_pab.nsf", "rt_bookmark.nsf"
)

$union = @{}
$where = @{}
Add-Type -AssemblyName System.Web.Extensions
foreach ($nsf in $nsfs) {
  $tmp = Join-Path $env:TEMP ("cov-" + [guid]::NewGuid().ToString("n") + ".json")
  try {
    Invoke-WebRequest -Uri "$RestUrl/api/kyber-v1/export?nsfPath=$([uri]::EscapeDataString($nsf))" `
      -Headers $headers -OutFile $tmp -UseBasicParsing -TimeoutSec 300 | Out-Null
    $ser = New-Object System.Web.Script.Serialization.JavaScriptSerializer
    $ser.MaxJsonLength = [int]::MaxValue
    $obj = $ser.DeserializeObject([IO.File]::ReadAllText($tmp))
    $types = @{}
    foreach ($el in @($obj["elements"])) {
      $t = [string]$el["type"]
      $union[$t] = $true
      if (-not $types.ContainsKey($t)) { $types[$t] = 0 }
      $types[$t]++
      if (-not $where.ContainsKey($t)) { $where[$t] = New-Object System.Collections.Generic.List[string] }
      if (-not $where[$t].Contains($nsf)) { [void]$where[$t].Add($nsf) }
    }
    Write-Host ("OK {0} n={1}" -f $nsf, @($obj["elements"]).Count)
  } catch {
    Write-Host ("FAIL {0}: {1}" -f $nsf, $_.Exception.Message)
  } finally {
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
  }
}

$have = @($all | Where-Object { $union.ContainsKey($_) })
$missing = @($all | Where-Object { -not $union.ContainsKey($_) })
$extra = @($union.Keys | Where-Object { $all -notcontains $_ } | Sort-Object)

Write-Host ""
Write-Host ("HAVE {0}/{1}" -f $have.Count, $all.Count)
Write-Host ("MISSING ({0}): {1}" -f $missing.Count, ($missing -join ", "))
if ($extra.Count) { Write-Host ("EXTRA: " + ($extra -join ", ")) }
Write-Host ""
Write-Host "Where missing types would help — still absent everywhere in this set."
