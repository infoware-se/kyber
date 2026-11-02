# Create/refresh kyber_ref\rt_seed_applet_dc.nsf with typed applet + dataconnection
# (JAR defaults via empty POST). Used for bulk RT coverage of those residual types.
#
#   .\tools\seed-applet-dataconnection-nsf.ps1 -SkipCertificateCheck

param(
  [string]$NsfPath = "kyber_ref\rt_seed_applet_dc.nsf",
  [string]$RestUrl,
  [string]$User,
  [string]$Password,
  [switch]$SkipCertificateCheck
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $RepoRoot ".env"
function Get-EnvVal([string]$k) {
  if (-not (Test-Path $envFile)) { return $null }
  $line = Get-Content $envFile | Where-Object { $_ -match "^$([regex]::Escape($k))=" } | Select-Object -First 1
  if ($line) { return $line.Substring($k.Length + 1) }
  return $null
}
if (-not $RestUrl) {
  $RestUrl = Get-EnvVal "DOMINO_REST_API_DATA_URL"
  if (-not $RestUrl) { $RestUrl = Get-EnvVal "DOMINO_REST_URL" }
}
if (-not $RestUrl) { throw "RestUrl / DOMINO_REST_* missing" }
$RestUrl = $RestUrl.TrimEnd("/")
if (-not $User) { $User = Get-EnvVal "REST_ADMIN_USER" }
if (-not $Password) { $Password = Get-EnvVal "REST_ADMIN_PASSWORD" }

if ($SkipCertificateCheck -and "TrustAllCertsPolicy" -as [type] -eq $null) {
  Add-Type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllCertsPolicy : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint s, X509Certificate c, WebRequest r, int p) { return true; }
}
"@
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllCertsPolicy
}

$auth = Invoke-RestMethod -Method POST -Uri "$RestUrl/api/v1/auth" `
  -ContentType "application/json" -Body (@{ username = $User; password = $Password } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($auth.bearer)" }
$q = "nsfPath=$([uri]::EscapeDataString($NsfPath))"

Write-Host "DELETE $NsfPath (fresh)"
try { Invoke-RestMethod -Method DELETE -Uri "$RestUrl/api/kyber-v1/database?$q" -Headers $headers | Out-Null } catch {}
Write-Host "POST /database → $NsfPath"
Invoke-RestMethod -Method POST `
  -Uri "$RestUrl/api/kyber-v1/database?$q&dbTitle=$([uri]::EscapeDataString('Kyber RT applet+dataconnection seed'))" `
  -Headers $headers | Out-Null

$empty = Join-Path $env:TEMP "kyber-seed-empty.xml"
Set-Content -LiteralPath $empty -Value "" -Encoding ascii -NoNewline
foreach ($seed in @(
    @{ coll = "forms"; name = "SeedForm" },
    @{ coll = "applets"; name = "SeedApplet" },
    @{ coll = "dataconnections"; name = "SeedConnection" }
  )) {
  $uri = "$RestUrl/api/kyber-v1/$($seed.coll)?$q&name=$([uri]::EscapeDataString($seed.name))"
  Invoke-RestMethod -Method POST -Uri $uri -Headers $headers -ContentType "text/xml" -InFile $empty | Out-Null
  Write-Host "seeded $($seed.coll)/$($seed.name)"
}

$exp = Invoke-RestMethod -Uri "$RestUrl/api/kyber-v1/export?$q" -Headers $headers
$ad = @($exp.elements | Where-Object { $_.type -in @("applet", "dataconnection") })
Write-Host ("export elements={0} applet/dc={1}" -f @($exp.elements).Count, $ad.Count)
$ad | ForEach-Object { Write-Host ("  {0}|{1}" -f $_.type, $_.name) }
if ($ad.Count -lt 2) { throw "expected applet + dataconnection in bulk export" }

$server = Get-EnvVal "DOMINO_SERVER"
if (-not $server) { $server = "Dantooine" }
$vbs = @"
Set session = CreateObject("Notes.NotesSession")
path = "$($NsfPath -replace '\\','\\')"
Set db = session.GetDatabase("$server", path)
If Not db.IsOpen Then Call db.Open("", "")
If db.IsOpen Then
  If InStr(1, db.Categories, "KyberEnabled", 1) = 0 Then
    If Trim(db.Categories) = "" Then
      db.Categories = "KyberEnabled"
    Else
      db.Categories = Trim(db.Categories) & ", KyberEnabled"
    End If
  End If
  WScript.Echo "OK cats=[" & db.Categories & "]"
Else
  WScript.Echo "FAIL open"
  WScript.Quit 1
End If
"@
$vbsPath = Join-Path $env:TEMP "kyber-seed-applet-dc-enable.vbs"
Set-Content -LiteralPath $vbsPath -Value $vbs -Encoding ASCII
cscript //nologo $vbsPath
if ($LASTEXITCODE -ne 0) { throw "KyberEnabled mark failed" }
Write-Host "DONE $NsfPath"
