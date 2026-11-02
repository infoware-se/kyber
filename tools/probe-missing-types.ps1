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
$h = @{ Authorization = "Bearer $($auth.bearer)" }

try {
  Invoke-RestMethod -Uri "$RestUrl/api/kyber-v1/export?nsfPath=probe_fbws.nsf" -Headers $h | Out-Null
  Write-Host "fbws export OK"
} catch {
  Write-Host "fbws: $($_.Exception.Message)"
  if ($_.ErrorDetails.Message) {
    $m = $_.ErrorDetails.Message
    Write-Host $m.Substring(0, [Math]::Min(400, $m.Length))
  }
}

$vbs = @'
Set session = CreateObject("Notes.NotesSession")
Function Probe(t, n)
  On Error Resume Next
  Set dst = session.GetDatabase("Dantooine", n)
  If dst.IsOpen Then Call dst.Remove
  Err.Clear: On Error GoTo 0
  Set src = session.GetDatabase("Dantooine", t)
  If Not src.IsOpen Then Call src.Open("","")
  If Not src.IsOpen Then
    WScript.Echo "FAIL open " & t
    Exit Function
  End If
  Set dst = src.CreateFromTemplate("Dantooine", n, False)
  If dst Is Nothing Then
    WScript.Echo "FAIL create " & n
    Exit Function
  End If
  If Not dst.IsOpen Then Call dst.Open("","")
  dst.Categories = "KyberEnabled"
  WScript.Echo "OK " & n & " " & dst.Title
End Function
Call Probe("KeepAgents.ntf", "probe_keepagents.nsf")
Call Probe("Forms9_x.ntf", "probe_forms9.nsf")
Call Probe("pubweb50.ntf", "probe_pubweb.nsf")
Call Probe("doclbs7.ntf", "probe_smartsuite.nsf")
'@
Set-Content "$env:TEMP\probe2.vbs" $vbs -Encoding ASCII
cscript //nologo "$env:TEMP\probe2.vbs"

$wanted = @(
  "sharedcolumn", "webcontent", "jar", "applet", "javasource", "webservice",
  "webserviceconsumer", "dataconnection", "wiringproperties", "compositecomponent",
  "designnote", "dbicon"
)

Add-Type -AssemblyName System.Web.Extensions
foreach ($nsf in @(
    "probe_keepagents.nsf", "probe_forms9.nsf", "probe_pubweb.nsf", "probe_smartsuite.nsf",
    "kybertest.nsf", "kyber_jar.nsf", "kyber_blank.nsf"
  )) {
  $tmp = Join-Path $env:TEMP ("exp2-" + [guid]::NewGuid().ToString("n") + ".json")
  try {
    $p = @{
      Uri = "$RestUrl/api/kyber-v1/export?nsfPath=$([uri]::EscapeDataString($nsf))"
      Headers = $h
      OutFile = $tmp
      UseBasicParsing = $true
      TimeoutSec = 180
    }
    Invoke-WebRequest @p | Out-Null
  } catch {
    Write-Host "FAIL $nsf : $($_.Exception.Message)"
    continue
  }
  $ser = New-Object System.Web.Script.Serialization.JavaScriptSerializer
  $ser.MaxJsonLength = [int]::MaxValue
  $obj = $ser.DeserializeObject([IO.File]::ReadAllText($tmp))
  $els = @($obj["elements"])
  $types = @{}
  foreach ($el in $els) {
    $t = [string]$el["type"]
    if (-not $types.ContainsKey($t)) { $types[$t] = 0 }
    $types[$t]++
  }
  $hits = @($wanted | Where-Object { $types.ContainsKey($_) })
  $bytes = (Get-Item $tmp).Length
  $typeStr = ($types.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ", "
  Write-Host ("==== {0} n={1} KB={2:N0} rare=[{3}]" -f $nsf, $els.Count, ($bytes / 1kb), ($hits -join ","))
  Write-Host "  $typeStr"
  Remove-Item $tmp -Force -ErrorAction SilentlyContinue
}
