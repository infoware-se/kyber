<#
.SYNOPSIS
  Bulk DXL round-trip regress (CompareMode=import).

.PARAMETER AllDominoNtf
  Ensure KyberEnabled copies of every Domino .ntf and round-trip each
  (kyber_ref\rt_ntf_<name>.nsf). Default.

.PARAMETER CuratedOnly
  Previous 10-app coverage set only (faster).
#>
param(
  [switch]$SkipCertificateCheck,
  [int]$TimeoutSec = 1200,
  # Smaller chunks reduce KEEP 408 on huge NSF (e.g. teamrm145 ~3k elements).
  [int]$MaxElementsPerChunk = 75,
  [switch]$AllDominoNtf,
  [switch]$CuratedOnly,
  [switch]$SkipEnsure
)

$ErrorActionPreference = "Continue"
$RepoRoot = Split-Path $PSScriptRoot -Parent

if (-not $CuratedOnly) { $AllDominoNtf = $true }

$curated = @(
  "rt_notebook.nsf",
  "rt_resource.nsf",
  "rt_discussion.nsf",
  "rt_nntp.nsf",
  "rt_doclibrary.nsf",
  "rt_blog.nsf",
  "ias.nsf",
  "ias_servers.nsf",
  "rt_pab.nsf",
  "rt_bookmark.nsf"
)

if ($AllDominoNtf -and -not $SkipEnsure) {
  Write-Host "Ensuring Domino NTF copies…"
  $ensureArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "ensure-domino-ntf-copies.ps1")
  )
  & powershell @ensureArgs
}

if ($CuratedOnly) {
  $nsfs = $curated
} else {
  $mapPath = Join-Path $RepoRoot "ignore\domino-ntf-copies.json"
  if (-not (Test-Path $mapPath)) {
    throw "Missing $mapPath — run ensure-domino-ntf-copies.ps1 first"
  }
  $map = Get-Content $mapPath -Raw | ConvertFrom-Json
  $nsfs = @($map | ForEach-Object { $_.nsf })
  # Keep live IAS apps in the full set as well
  foreach ($extra in @("ias.nsf", "ias_servers.nsf")) {
    if ($nsfs -notcontains $extra) { $nsfs += $extra }
  }
  # OpenNTF / external rare-type samples (ignore/external-ntf → kyber_ref\rt_ext_*.nsf)
  $extMapPath = Join-Path $RepoRoot "ignore\openntf-ext-copies.json"
  if (Test-Path $extMapPath) {
    $extMap = Get-Content $extMapPath -Raw | ConvertFrom-Json
    foreach ($row in @($extMap)) {
      $p = $row.nsfPath
      if (-not $p) { $p = $row.nsf }
      if ($p -and $nsfs -notcontains $p) { $nsfs += $p }
    }
  }
}

Write-Host ("Round-trip set: {0} NSF(s)" -f $nsfs.Count)

$summary = @()
foreach ($nsf in $nsfs) {
  Write-Host ""
  Write-Host "################ $nsf ################"
  $out = Join-Path $RepoRoot "ignore\roundtrip-bulk-$([IO.Path]::GetFileNameWithoutExtension($nsf)).json"
  $args = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "smoke-dxl-roundtrip.ps1"),
    "-NsfPath", $nsf,
    "-TimeoutSec", "$TimeoutSec",
    "-MaxElementsPerChunk", "$MaxElementsPerChunk",
    "-CompareMode", "import",
    "-OutJson", $out
  )
  if ($SkipCertificateCheck) { $args += "-SkipCertificateCheck" }
  & powershell @args
  $code = $LASTEXITCODE
  $row = $null
  if (Test-Path $out) {
    $row = Get-Content $out -Raw | ConvertFrom-Json
  }
  $summary += [pscustomobject]@{
    nsf = $nsf
    exit = $code
    matched = $row.matched
    missing = $row.missingCount
    assertFail = $row.assertFailCount
    importFailed = $row.importFailed
    seconds = $row.seconds
    sourceElements = $row.sourceElements
  }
}

Write-Host ""
Write-Host "======== ALL SUMMARY ========"
$summary | Format-Table -AutoSize | Out-String | Write-Host
$sumPath = Join-Path $RepoRoot "ignore\roundtrip-bulk-summary.json"
$summary | ConvertTo-Json -Depth 4 | Set-Content $sumPath -Encoding UTF8
$fail = @($summary | Where-Object { $_.exit -ne 0 -or $_.importFailed -gt 0 -or $_.assertFail -gt 0 })
Write-Host ("Wrote {0}  failedOrImportIssues={1}/{2}" -f $sumPath, $fail.Count, $summary.Count)
