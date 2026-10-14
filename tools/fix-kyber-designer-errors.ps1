# Fix Designer Problems in kyber.nsf (Java package/type, LS reserved name, DB script Option Public)
param(
  [string]$EnvFile = "",
  [switch]$SkipCertificateCheck
)
$ErrorActionPreference = "Stop"

function Get-EnvFileValue([string]$Path, [string]$Key) {
  foreach ($line in Get-Content $Path) {
    if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
    $k, $v = $line -split "=", 2
    if ($k.Trim() -eq $Key) { return $v.Trim().Trim('"').Trim("'") }
  }
  return $null
}

function Resolve-KyberEnvFile([string]$Explicit) {
  if ($Explicit) {
    if (-not (Test-Path $Explicit)) { throw "Env file not found: $Explicit" }
    return (Resolve-Path $Explicit).Path
  }
  foreach ($f in @("$PSScriptRoot\..\.env", ".\.env")) {
    if (Test-Path $f) { return (Resolve-Path $f).Path }
  }
  throw "No .env found. Pass -EnvFile."
}

if ($SkipCertificateCheck -and -not ([System.Management.Automation.PSTypeName]'IwsTrustAllCertsFix3').Type) {
  Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class IwsTrustAllCertsFix3 : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) { return true; }
}
"@
}
if ($SkipCertificateCheck) {
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object IwsTrustAllCertsFix3
  [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
}

$envFile = Resolve-KyberEnvFile $EnvFile
$base = (Get-EnvFileValue $envFile "DOMINO_REST_API_DATA_URL").TrimEnd('/')
$user = Get-EnvFileValue $envFile "REST_ADMIN_USER"
$pass = Get-EnvFileValue $envFile "REST_ADMIN_PASSWORD"
$auth = Invoke-RestMethod -Method POST -Uri "$base/api/v1/auth" -ContentType "application/json" `
  -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($auth.bearer)" }
$nsfQ = "nsfPath=" + [uri]::EscapeDataString("kyber.nsf")
$repo = Split-Path $PSScriptRoot -Parent

function Invoke-Drapi {
  param([string]$Method, [string]$Uri, [string]$Body = $null)
  $p = @{ Method = $Method; Uri = $Uri; Headers = $headers; UseBasicParsing = $true }
  if ($null -ne $Body) {
    $p.Body = $Body
    $p.ContentType = "application/xml"
  }
  try {
    $r = Invoke-WebRequest @p
    return [pscustomobject]@{ Ok = $true; Code = [int]$r.StatusCode; Body = $r.Content }
  } catch {
    $code = [int]$_.Exception.Response.StatusCode
    $reader = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())
    return [pscustomobject]@{ Ok = $false; Code = $code; Body = $reader.ReadToEnd() }
  }
}

Write-Host "=== databasescript ==="
$dbDxl = Get-Content -Raw (Join-Path $repo "extension\src\main\resources\defaults\databasescript.dxl")
$r = Invoke-Drapi PUT "$base/api/kyber-v1/databasescript?$nsfQ" $dbDxl
Write-Host "PUT $($r.Code) ok=$($r.Ok) stillHasOptionPublic=$($r.Body -match 'Option Public')"

Write-Host "=== Default-LotusScript ==="
$lsDxl = Get-Content -Raw (Join-Path $repo "extension\src\main\resources\defaults\scriptlibrary.dxl")
$r = Invoke-Drapi PUT "$base/api/kyber-v1/scriptlibraries/Default-LotusScript?$nsfQ" $lsDxl
$hasAsText = $lsDxl.Contains('AsText')
$hasReserved = $lsDxl.Contains('Hello(name As')
Write-Host "PUT $($r.Code) ok=$($r.Ok) hasAsText=$hasAsText hasReservedName=$hasReserved"

Write-Host "=== newscriptlibrarylotusscript ==="
$got = Invoke-RestMethod -Method GET -Uri "$base/api/kyber-v1/scriptlibraries/newscriptlibrarylotusscript?$nsfQ" -Headers $headers
$minLs = $got.dxl
$minLs = $minLs.Replace('Function Text As String', 'Function AsText As String')
$minLs = $minLs.Replace('Text = "Hello, "', 'AsText = "Hello, "')
$minLs = $minLs.Replace('g.Text', 'g.AsText')
$minLs = $minLs.Replace("Function Text`r", "Function AsText`r")
$minLs = $minLs.Replace("Function Text`n", "Function AsText`n")
$r = Invoke-Drapi PUT "$base/api/kyber-v1/scriptlibraries/newscriptlibrarylotusscript?$nsfQ" $minLs
Write-Host "PUT $($r.Code) ok=$($r.Ok)"

Write-Host "=== minimal Java sources ==="
$javaNames = @(
  @{ Name = 'se/infoware/kyber/minimal/Minimal.java'; Kind = 'class' },
  @{ Name = 'se/infoware/kyber/minimal/MinimalInterface.java'; Kind = 'interface' },
  @{ Name = 'se/infoware/kyber/minimal/MinimalEnum.java'; Kind = 'enum' }
)
foreach ($j in $javaNames) {
  $enc = [uri]::EscapeDataString($j.Name)
  $del = Invoke-Drapi DELETE "$base/api/kyber-v1/javasources/$enc?$nsfQ"
  Write-Host "DELETE $($j.Name) $($del.Code)"
  $uri = "$base/api/kyber-v1/javasources?$nsfQ&kind=$($j.Kind)&name=$enc"
  $cre = Invoke-Drapi POST $uri ""
  Write-Host "POST $($j.Name) $($cre.Code) ok=$($cre.Ok)"
  if (-not $cre.Ok) {
    Write-Host $cre.Body.Substring(0, [Math]::Min(300, $cre.Body.Length))
    continue
  }
  $obj = $cre.Body | ConvertFrom-Json
  if ($obj.dxl -match '(?s)<rawitemdata[^>]*>\s*([A-Za-z0-9+/=\s]+)\s*</rawitemdata>') {
    $raw = [Convert]::FromBase64String(($Matches[1] -replace '\s', ''))
    $src = [Text.Encoding]::UTF8.GetString($raw, 42, $raw.Length - 42)
    $head = (($src -split "`n") | Select-Object -First 6) -join ' | '
    Write-Host "  $head"
  }
}

Write-Host "Done."
