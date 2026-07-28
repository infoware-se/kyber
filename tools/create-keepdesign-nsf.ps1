# Create/update keepdesign.nsf with starter design elements from GET /defaults (embedded JAR).
# Afterward, edit keepdesign.nsf in Designer; those Default elements override JAR starters.
$ErrorActionPreference = "Stop"

Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class KeepDesignTrust : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint sp, X509Certificate c, WebRequest r, int p) { return true; }
}
"@ -ErrorAction SilentlyContinue
[System.Net.ServicePointManager]::CertificatePolicy = New-Object KeepDesignTrust
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

function Import-EnvFile([string]$path) {
  if (-not (Test-Path $path)) { return }
  Get-Content $path | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $k, $v = $_.Split('=', 2)
    Set-Item -Path "env:$($k.Trim())" -Value $v.Trim().Trim('"').Trim("'")
  }
}

$RepoRoot = Split-Path $PSScriptRoot -Parent
foreach ($f in @(
  (Join-Path $RepoRoot ".env"),
  (Join-Path $RepoRoot ".env.drapi"),
  "C:\Git\install-domino-restapi\.env"
)) { Import-EnvFile $f }

$RestUrl = $env:DOMINO_REST_API_DATA_URL
if (-not $RestUrl) { $RestUrl = $env:DOMINO_REST_URL }
$user = $env:REST_ADMIN_USER; if (-not $user) { $user = $env:DOMINO_REST_USER }
$pass = $env:REST_ADMIN_PASSWORD; if (-not $pass) { $pass = $env:DOMINO_REST_PASSWORD }

$auth = Invoke-RestMethod -Method POST -Uri "$RestUrl/api/v1/auth" -ContentType "application/json" `
  -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($auth.bearer)"; "Content-Type" = "application/json" }
$base = "$RestUrl/api/design-v1"
$nsf = "keepdesign.nsf"

Write-Host "Fetching starters from GET /defaults ..."
$defaults = Invoke-RestMethod -Method GET -Uri "$base/defaults" -Headers $h
$elements = @()
foreach ($el in $defaults.elements) {
  $row = @{ type = $el.type; dxl = $el.dxl }
  if ($el.name) { $row.name = $el.name }
  $elements += $row
}
Write-Host ("  {0} elements (defaultNsf={1})" -f $elements.Count, $defaults.defaultNsf)

$body = @{ elements = $elements } | ConvertTo-Json -Depth 20 -Compress
Write-Host "Importing into $nsf via PUT /designdxl ..."
$result = Invoke-RestMethod -Method PUT -Uri "$base/designdxl?nsfPath=$([uri]::EscapeDataString($nsf))" `
  -Headers $h -Body $body
Write-Host ("Done. created={0} imported={1} failed={2}" -f $result.created, $result.imported, $result.failed)
if (-not $result.success) {
  $result.results | Where-Object { -not $_.success } | ForEach-Object {
    Write-Host ("  FAIL {0}: {1}" -f $_.type, $_.message) -ForegroundColor Red
  }
  exit 1
}
Write-Host "Edit $nsf in Designer to customize server-side defaults (element name Default)." -ForegroundColor Green
