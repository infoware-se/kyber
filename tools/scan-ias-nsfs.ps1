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

$nsfs = @("ias.nsf", "ias_servers.nsf")
$collections = [ordered]@{
  forms = "form"
  views = "view"
  folders = "folder"
  agents = "agent"
  pages = "page"
  framesets = "frameset"
  outlines = "outline"
  navigators = "navigator"
  subforms = "subform"
  sharedfields = "sharedfield"
  sharedcolumns = "sharedcolumn"
  sharedactions = "sharedactions"
  scriptlibraries = "scriptlibrary"
  images = "image"
  fileresources = "fileresource"
  stylesheets = "stylesheet"
  themes = "theme"
  xpages = "xpage"
  customcontrols = "customcontrol"
  xpagesresources = "xpagesresource"
  jars = "jar"
  applets = "applet"
  javasources = "javasource"
  webcontent = "webcontent"
  webservices = "webservice"
  webserviceconsumers = "webserviceconsumer"
  dataconnections = "dataconnection"
  wiringproperties = "wiringproperties"
  compositeapplications = "compositeapplication"
  compositecomponents = "compositecomponent"
}

# Ensure KyberEnabled via COM if needed
$vbs = @'
Set session = CreateObject("Notes.NotesSession")
For Each path In Array("ias.nsf", "ias_servers.nsf")
  On Error Resume Next
  Set db = session.GetDatabase("Dantooine", path)
  If Not db.IsOpen Then Call db.Open("", "")
  If db.IsOpen Then
    WScript.Echo path & " title=[" & db.Title & "] cats=[" & db.Categories & "] size=" & db.Size
    If InStr(1, db.Categories, "KyberEnabled", 1) = 0 Then
      If Trim(db.Categories) = "" Then
        db.Categories = "KyberEnabled"
      Else
        db.Categories = Trim(db.Categories) & ", KyberEnabled"
      End If
      WScript.Echo "  marked KyberEnabled"
    End If
  Else
    WScript.Echo "FAIL open " & path & " err=" & Err.Description
  End If
  Err.Clear
Next
'@
Set-Content "$env:TEMP\enable-ias.vbs" $vbs -Encoding ASCII
cscript //nologo "$env:TEMP\enable-ias.vbs"

$wantedRare = @(
  "sharedcolumn", "jar", "applet", "javasource", "webcontent", "webservice",
  "webserviceconsumer", "dataconnection", "wiringproperties", "compositecomponent"
)

foreach ($nsf in $nsfs) {
  Write-Host ""
  Write-Host "======== $nsf ========"
  $found = [ordered]@{}
  $rareHits = @()
  foreach ($plural in $collections.Keys) {
    $type = $collections[$plural]
    $uri = "$RestUrl/api/kyber-v1/${plural}?nsfPath=$([uri]::EscapeDataString($nsf))"
    try {
      $r = Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 120
      $n = @($r.elements).Count
      if ($n -gt 0) {
        $found[$type] = $n
        if ($wantedRare -contains $type) { $rareHits += "$type=$n" }
      }
    } catch {
      $msg = $_.Exception.Message
      if ($_.ErrorDetails.Message) {
        try { $j = $_.ErrorDetails.Message | ConvertFrom-Json; if ($j.message) { $msg = $j.message } } catch {}
      }
      Write-Host ("  ERR {0}: {1}" -f $plural, $msg)
    }
  }
  $total = 0
  foreach ($v in $found.Values) { $total += $v }
  Write-Host ("typed notes (listed)≈{0}" -f $total)
  Write-Host ("types: " + (($found.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ", "))
  if ($rareHits.Count) {
    Write-Host ("RARE: " + ($rareHits -join ", "))
  } else {
    Write-Host "RARE: (none of jar/applet/javasource/webcontent/webservice*/dataconnection/wiring/compositecomponent/sharedcolumn)"
  }

  # Full export type union (catches designnote, singletons, etc.)
  $tmp = Join-Path $env:TEMP ("ias-exp-" + [IO.Path]::GetFileNameWithoutExtension($nsf) + ".json")
  try {
    Invoke-WebRequest -Uri "$RestUrl/api/kyber-v1/export?nsfPath=$([uri]::EscapeDataString($nsf))" `
      -Headers $headers -OutFile $tmp -UseBasicParsing -TimeoutSec 600 | Out-Null
    Add-Type -AssemblyName System.Web.Extensions
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
    $kb = [int]((Get-Item $tmp).Length / 1kb)
    Write-Host ("export elements={0} sizeKB={1}" -f $els.Count, $kb)
    Write-Host ("export types: " + (($types.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ", "))
    $exportRare = @($wantedRare + @("designnote", "compositeapplication") | Where-Object { $types.ContainsKey($_) } | ForEach-Object { "$_=$($types[$_])" })
    if ($exportRare.Count) { Write-Host ("export RARE/extra: " + ($exportRare -join ", ")) }
  } catch {
    Write-Host ("export FAIL: " + $_.Exception.Message)
    if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message.Substring(0, [Math]::Min(300, $_.ErrorDetails.Message.Length)) }
  } finally {
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
  }
}
