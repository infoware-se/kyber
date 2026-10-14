# Fix minimal Java sources in kyber.nsf via bulk PUT (path CRUD cannot pass '/').
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

function Rewrite-JavaSource([string]$Source, [string]$Package, [string]$SimpleName) {
  $out = $Source
  if ([string]::IsNullOrWhiteSpace($Package)) {
    $out = [regex]::Replace($out, '(?m)^\s*package\s+[^;]+;\s*', '')
  } elseif ([regex]::IsMatch($out, '(?m)^\s*package\s+')) {
    $out = [regex]::Replace($out, '(?m)^(\s*package\s+)[^;]+;', "`${1}$Package;")
  } else {
    $out = "package $Package;`r`n`r`n$out"
  }
  $out = [regex]::Replace(
    $out,
    '(public\s+(?:abstract\s+|final\s+)?)(class|interface|enum)(\s+)\w+',
    "`${1}`${2}`${3}$SimpleName"
  )
  return $out
}

function Patch-LeSize([byte[]]$Raw, [int]$OldSize, [int]$NewSize) {
  for ($i = 0; $i + 3 -lt 42; $i++) {
    $v = $Raw[$i] -bor ($Raw[$i + 1] -shl 8) -bor ($Raw[$i + 2] -shl 16) -bor ($Raw[$i + 3] -shl 24)
    if ($v -eq $OldSize) {
      $Raw[$i] = [byte]($NewSize -band 0xff)
      $Raw[$i + 1] = [byte](($NewSize -shr 8) -band 0xff)
      $Raw[$i + 2] = [byte](($NewSize -shr 16) -band 0xff)
      $Raw[$i + 3] = [byte](($NewSize -shr 24) -band 0xff)
    }
  }
}

function New-MinimalJavaDxl([string]$TemplatePath, [string]$WantedName) {
  $dxl = Get-Content -Raw $TemplatePath
  $pathNoExt = $WantedName.Substring(0, $WantedName.Length - 5)
  $slash = $pathNoExt.LastIndexOf('/')
  $simple = if ($slash -ge 0) { $pathNoExt.Substring($slash + 1) } else { $pathNoExt }
  $pkg = if ($slash -ge 0) { $pathNoExt.Substring(0, $slash).Replace('/', '.') } else { '' }

  if ($dxl -notmatch '(?s)(<item name=''\$FileData''[^>]*>\s*<rawitemdata[^>]*>)([A-Za-z0-9+/=\s]+)(</rawitemdata>)') {
    throw "No `$FileData in $TemplatePath"
  }
  $prefix = $Matches[1]
  $suffix = $Matches[3]
  $raw = [Convert]::FromBase64String(($Matches[2] -replace '\s', ''))
  if ($raw.Length -le 42) { throw "FileData too short" }
  $oldSize = $raw.Length - 42
  $source = [Text.Encoding]::UTF8.GetString($raw, 42, $oldSize)
  $rewritten = Rewrite-JavaSource $source $pkg $simple
  $body = [Text.Encoding]::UTF8.GetBytes($rewritten)
  $out = New-Object byte[] (42 + $body.Length)
  [Array]::Copy($raw, 0, $out, 0, 42)
  [Array]::Copy($body, 0, $out, 42, $body.Length)
  Patch-LeSize $out $oldSize $body.Length
  $b64 = [Convert]::ToBase64String($out)

  # Replace titles / filenames / class index; strip compiled class payload
  $dxl = [regex]::Replace($dxl, "(?s)(<item name='\`$TITLE'><text>)[^<]+(</text></item>)", "`${1}$WantedName`${2}")
  $dxl = [regex]::Replace($dxl, "(?s)(<item name='\`$FileNames'[^>]*>\s*<text>)[^<]+(</text></item>)", "`${1}$WantedName`${2}")
  $dxl = [regex]::Replace($dxl, "(?s)(<item name='\`$ClassIndexItem'><text>)[^<]+(</text></item>)", "`${1}WEB-INF/classes/$pathNoExt.class`${2}")
  $dxl = [regex]::Replace($dxl, "(?s)(<item name='\`$FileSize'[^>]*>\s*<number>)\d+(</number></item>)", "`${1}$($body.Length)`${2}")
  $dxl = [regex]::Replace($dxl, "(?s)<item name='\`$ClassSize0'[^>]*>.*?</item>", '')
  $dxl = [regex]::Replace($dxl, "(?s)<item name='\`$ClassData0'[^>]*>.*?</item>", '')
  $dxl = [regex]::Replace($dxl, "(?s)(<item name='\`$FileData'[^>]*>\s*<rawitemdata[^>]*>)[A-Za-z0-9+/=\s]+(</rawitemdata>)", "`${1}`n$b64`n`${2}")

  # Drop note identity so UPSERT creates/replaces by name content rather than colliding oddly
  $dxl = [regex]::Replace($dxl, "(?s)<noteinfo\b.*?</noteinfo>", '')
  $dxl = [regex]::Replace($dxl, "\sreplicaid='[^']*'", '')

  return @{ name = $WantedName; dxl = $dxl; preview = (($rewritten -split "`n") | Select-Object -First 6) -join ' | ' }
}

function Resolve-KyberEnvFile([string]$Explicit) {
  if ($Explicit) {
    if (-not (Test-Path $Explicit)) { throw "Env file not found: $Explicit" }
    return (Resolve-Path $Explicit).Path
  }
  foreach ($f in @(".\.env", "$PSScriptRoot\..\.env", "C:\Git\install-domino-restapi\.env")) {
    if (Test-Path $f) { return (Resolve-Path $f).Path }
  }
  throw "No .env found. Pass -EnvFile."
}

if ($SkipCertificateCheck -and -not ([System.Management.Automation.PSTypeName]'IwsTrustAllCertsFix8').Type) {
  Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class IwsTrustAllCertsFix8 : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) { return true; }
}
"@
}
if ($SkipCertificateCheck) {
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object IwsTrustAllCertsFix8
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
$RepoRoot = Split-Path $PSScriptRoot -Parent
$defaults = Join-Path $RepoRoot "extension\src\main\resources\defaults"

$items = @(
  (New-MinimalJavaDxl (Join-Path $defaults "javasource.dxl") "se/infoware/kyber/minimal/Minimal.java"),
  (New-MinimalJavaDxl (Join-Path $defaults "javasource-interface.dxl") "se/infoware/kyber/minimal/MinimalInterface.java"),
  (New-MinimalJavaDxl (Join-Path $defaults "javasource-enum.dxl") "se/infoware/kyber/minimal/MinimalEnum.java")
)

foreach ($it in $items) {
  Write-Host "Prepared" $it.name
  Write-Host " " $it.preview
}

# Delete old minimal java notes via bulk delete flags if supported; else UPSERT by importing fixed DXL
# First try ImportDesignDxl delete: elements with delete:true
$deletePayload = @{
  elements = @(
    @{ type = "javasource"; name = "se/infoware/kyber/minimal/Minimal.java"; delete = $true },
    @{ type = "javasource"; name = "se/infoware/kyber/minimal/MinimalInterface.java"; delete = $true },
    @{ type = "javasource"; name = "se/infoware/kyber/minimal/MinimalEnum.java"; delete = $true }
  )
} | ConvertTo-Json -Depth 5

try {
  $del = Invoke-RestMethod -Method PUT -Uri "$base/api/kyber-v1/designdxl?$nsfQ" -Headers $headers `
    -ContentType "application/json" -Body $deletePayload
  Write-Host "Delete phase imported=$($del.imported) failed=$($del.failed)"
} catch {
  Write-Host "Delete phase skipped/failed:" $_.Exception.Message
}

$payload = @{
  elements = @(
    @{ type = "javasource"; name = $items[0].name; dxl = $items[0].dxl },
    @{ type = "javasource"; name = $items[1].name; dxl = $items[1].dxl },
    @{ type = "javasource"; name = $items[2].name; dxl = $items[2].dxl }
  )
} | ConvertTo-Json -Depth 5

$put = Invoke-RestMethod -Method PUT -Uri "$base/api/kyber-v1/designdxl?$nsfQ" -Headers $headers `
  -ContentType "application/json" -Body $payload
Write-Host "Upsert imported=$($put.imported) failed=$($put.failed)"
$put.results | ForEach-Object {
  Write-Host " " $_.type $_.name $_.success $_.message
  if ($_.log) { Write-Host "   log:" $_.log.Substring(0, [Math]::Min(200, $_.log.Length)) }
}

# Export kyber folder
$env:REST_ADMIN_USER = $user
$env:REST_ADMIN_PASSWORD = $pass
& (Join-Path $PSScriptRoot "export-to-repo.ps1") -RestUrl $base -NsfPath "kyber.nsf" `
  -OutDir (Join-Path $RepoRoot "kyber")

# Verify decoded source from export
$files = Get-ChildItem (Join-Path $RepoRoot "kyber\javasources") -Filter "*minimal*"
foreach ($f in $files) {
  if ($f.Extension -ne '.dxl') { continue }
  $dxl = Get-Content -Raw $f.FullName
  if ($dxl -match '(?s)<rawitemdata[^>]*>\s*([A-Za-z0-9+/=\s]+)\s*</rawitemdata>') {
    $raw = [Convert]::FromBase64String(($Matches[1] -replace '\s', ''))
    $src = [Text.Encoding]::UTF8.GetString($raw, 42, $raw.Length - 42)
    Write-Host "VERIFY" $f.Name
    Write-Host (($src -split "`n" | Select-Object -First 5) -join ' | ')
  }
}
