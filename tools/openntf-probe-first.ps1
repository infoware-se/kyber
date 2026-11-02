<#
.SYNOPSIS
  OpenNTF probe-first pipeline: download candidates → KyberEnabled probe NSF → type/export probe.
  Does NOT full round-trip. Promote only when the report flags new/rare signal.

.PARAMETER Download
  Fetch missing catalog URLs into ignore/external-ntf/

.PARAMETER Probe
  CreateFromTemplate into kyber_ref\rt_probe_<id>.nsf and GET /export type histogram

.PARAMETER Promote
  Copy promoteRecommended probes into kyber_ref\rt_ext_<id>.nsf and update openntf-ext-copies.json

.PARAMETER RefreshCatalogFromGitHub
  Re-scan OpenNTF org for .nsf/.ntf blobs and merge into catalog (github source rows)

.PARAMETER MaxCandidates
  Cap how many not-yet-have items to download/probe (default 20)
#>
param(
  [switch]$SkipCertificateCheck,
  [switch]$Download,
  [switch]$Probe,
  [switch]$Promote,
  [switch]$RefreshCatalogFromGitHub,
  [int]$MaxCandidates = 20,
  [string]$Server = "Dantooine",
  [string]$Folder = "kyber_ref"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
$ExtDir = Join-Path $RepoRoot "ignore\external-ntf"
$CandDir = Join-Path $ExtDir "candidates"
$CatalogPath = Join-Path $RepoRoot "ignore\openntf-probe-catalog.json"
$ReportPath = Join-Path $RepoRoot "ignore\openntf-probe-report.json"
$BaselineProbePath = Join-Path $RepoRoot "ignore\openntf-ext-type-probe.json"
$ExtCopiesPath = Join-Path $RepoRoot "ignore\openntf-ext-copies.json"
$DominoIncoming = "C:\Domino\Data\kyber_incoming"
$SourcesPath = Join-Path $ExtDir "SOURCES.txt"

New-Item -ItemType Directory -Force -Path $ExtDir, $CandDir, $DominoIncoming | Out-Null

function Get-EnvFileValue([string]$Path, [string]$Key) {
  if (-not (Test-Path $Path)) { return $null }
  foreach ($line in Get-Content $Path) {
    if ($line -match "^\s*#" -or $line -notmatch "=") { continue }
    $k, $v = $line -split "=", 2
    if ($k.Trim() -eq $Key) { return $v.Trim().Trim('"').Trim("'") }
  }
  return $null
}

function Enable-InsecureSsl {
  if (-not $SkipCertificateCheck) { return }
  if ($PSVersionTable.PSVersion.Major -ge 6) { return }
  if (-not ([System.Management.Automation.PSTypeName]"IwsTrustAllCerts").Type) {
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

# Types that are scarce / historically hard — more samples still useful even if not "new"
$RareWanted = @(
  "applet", "dataconnection", "jar", "webcontent", "javasource",
  "webservice", "webserviceconsumer", "wiringproperties",
  "compositecomponent", "compositeapplication", "navigator",
  "sharedcolumn", "theme", "outline"
)

function Get-BaselineTypes {
  $set = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
  if (Test-Path $BaselineProbePath) {
    $probe = Get-Content $BaselineProbePath -Raw | ConvertFrom-Json
    foreach ($row in @($probe)) {
      if (-not $row.types) { continue }
      foreach ($pair in ($row.types -split ",\s*")) {
        $t = ($pair -split "=", 2)[0]
        if ($t) { [void]$set.Add($t) }
      }
    }
  }
  # Domino NTF / product baseline always assumed present
  foreach ($t in @(
      "acl", "applicationproperties", "about", "using", "form", "view", "folder", "agent",
      "scriptlibrary", "image", "fileresource", "page", "frameset", "subform", "sharedfield",
      "sharedactions", "stylesheet", "xpage", "customcontrol", "xpagesresource", "xspproperties"
    )) {
    [void]$set.Add($t)
  }
  return $set
}

function Parse-TypeHistogram($exportObj) {
  $hist = [ordered]@{}
  foreach ($el in @($exportObj.elements)) {
    $t = [string]$el.type
    if ([string]::IsNullOrWhiteSpace($t)) { continue }
    if (-not $hist.Contains($t)) { $hist[$t] = 0 }
    $hist[$t]++
  }
  return $hist
}

function Format-TypeHist($hist) {
  ($hist.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ", "
}

if (-not (Test-Path $CatalogPath)) {
  throw "Missing catalog $CatalogPath"
}
# PS 5.1: force array — ConvertFrom-Json can yield a single object for 1-element arrays;
# also unwrap via .Count on Object[].
$catalogJson = [IO.File]::ReadAllText($CatalogPath)
$catalogParsed = $catalogJson | ConvertFrom-Json
if ($catalogParsed -is [System.Array]) {
  $catalog = @($catalogParsed)
} else {
  $catalog = @($catalogParsed)
}
Write-Host ("Catalog rows: {0} from {1}" -f $catalog.Count, $CatalogPath)

if ($RefreshCatalogFromGitHub) {
  Write-Host "Refreshing GitHub NSF/NTF catalog from OpenNTF org…"
  $repos = Invoke-RestMethod -Uri "https://api.github.com/orgs/OpenNTF/repos?per_page=100"
  $byId = @{}
  foreach ($row in $catalog) { $byId[$row.id] = $row }
  foreach ($repo in @($repos)) {
    $name = $repo.name
    $default = $repo.default_branch
    if (-not $default) { continue }
    try {
      $tree = Invoke-RestMethod -Uri "https://api.github.com/repos/OpenNTF/$name/git/trees/${default}?recursive=1"
    } catch { continue }
    foreach ($f in @($tree.tree)) {
      if ($f.type -ne "blob" -or $f.path -notmatch "\.(nsf|ntf)$") { continue }
      $slug = [IO.Path]::GetFileNameWithoutExtension($f.path) -replace "[^A-Za-z0-9_\-]", "_"
      if ($byId.ContainsKey($slug)) { continue }
      $row = [pscustomobject]@{
        id = $slug
        source = "github"
        repo = "OpenNTF/$name"
        url = "https://raw.githubusercontent.com/OpenNTF/$name/$default/$($f.path)"
        fileName = "openntf_$slug$([IO.Path]::GetExtension($f.path))"
        alreadyHave = $false
        note = "auto from GitHub tree scan"
        skip = ($slug -match "^(empty|blank)$")
      }
      $catalog += $row
      $byId[$slug] = $row
      Write-Host "  + $slug ($($row.repo))"
    }
  }
  $catalog | ConvertTo-Json -Depth 6 | Set-Content $CatalogPath -Encoding UTF8
  Write-Host "Catalog now $($catalog.Count) rows → $CatalogPath"
}

if (-not $Download -and -not $Probe -and -not $Promote -and -not $RefreshCatalogFromGitHub) {
  $Download = $true
  $Probe = $true
}

$todo = @($catalog | Where-Object {
    -not $_.skip -and -not $_.alreadyHave -and ($_.source -eq "github" -or $_.source -eq "url")
  } | Select-Object -First $MaxCandidates)

Write-Host ("Candidates to process: {0}" -f $todo.Count)

if ($Download) {
  foreach ($row in $todo) {
    if (-not $row.url) { continue }
    $dest = Join-Path $CandDir $row.fileName
    if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 1024)) {
      Write-Host "HAVE $($row.id) → $dest"
      continue
    }
    Write-Host "GET $($row.url)"
    try {
      curl.exe -fsSL $row.url -o $dest
      if (-not (Test-Path $dest) -or (Get-Item $dest).Length -lt 1024) {
        Write-Host "FAIL download too small: $($row.id)"
        Remove-Item $dest -ErrorAction SilentlyContinue
      } else {
        Write-Host ("OK {0} {1:N0} bytes" -f $row.id, (Get-Item $dest).Length)
      }
    } catch {
      Write-Host "FAIL download $($row.id): $($_.Exception.Message)"
    }
  }
}

$report = @()
$baseline = Get-BaselineTypes

if ($Probe) {
  Enable-InsecureSsl
  $RestUrl = (Get-EnvFileValue "$RepoRoot\.env" "DOMINO_REST_API_DATA_URL")
  if (-not $RestUrl) { $RestUrl = Get-EnvFileValue "$RepoRoot\.env" "DOMINO_REST_URL" }
  $RestUrl = $RestUrl.TrimEnd("/")
  $user = Get-EnvFileValue "$RepoRoot\.env" "REST_ADMIN_USER"
  $pass = Get-EnvFileValue "$RepoRoot\.env" "REST_ADMIN_PASSWORD"
  if (-not $RestUrl -or -not $user -or -not $pass) { throw "Missing REST URL / credentials in .env" }

  $authBody = @{ username = $user; password = $pass } | ConvertTo-Json -Compress
  $authFile = Join-Path $env:TEMP "kyber-openntf-auth.json"
  [IO.File]::WriteAllText($authFile, $authBody)
  $token = ((curl.exe -sk -X POST "$RestUrl/api/v1/auth" -H "Content-Type: application/json" `
    --data-binary "@$authFile") | ConvertFrom-Json).bearer
  if (-not $token) { throw "auth failed" }
  $headers = @{ Authorization = "Bearer $token" }

  $probeRows = @($todo)
  # Also re-probe already-downloaded candidates present on disk even if catalog said have=false
  foreach ($row in $catalog) {
    if ($row.skip) { continue }
    if ($row.alreadyHave) { continue }
    $local = Join-Path $CandDir $row.fileName
    $alt = Join-Path $ExtDir $row.fileName
    if ((Test-Path $local) -or (Test-Path $alt)) {
      if (-not ($probeRows | Where-Object { $_.id -eq $row.id })) { $probeRows += $row }
    }
  }
  $probeRows = @($probeRows | Select-Object -First $MaxCandidates)

  foreach ($row in $probeRows) {
    $id = [string]$row.id
    $srcFile = Join-Path $CandDir $row.fileName
    if (-not (Test-Path $srcFile)) { $srcFile = Join-Path $ExtDir $row.fileName }
    if (-not (Test-Path $srcFile)) {
      $report += [pscustomobject]@{
        id = $id; status = "missing_file"; promoteRecommended = $false
      }
      Write-Host "SKIP $id — file missing"
      continue
    }

    $incomingName = "kyber_incoming\$($row.fileName)"
    $incomingFull = Join-Path $DominoIncoming $row.fileName
    Copy-Item $srcFile $incomingFull -Force
    $probeNsf = "$Folder\rt_probe_$id.nsf"

    $vbs = @"
Set session = CreateObject("Notes.NotesSession")
On Error Resume Next
Set dst = session.GetDatabase("$Server", "$probeNsf")
If dst.IsOpen Then
  Call dst.Remove
End If
Err.Clear
On Error GoTo 0
Set src = session.GetDatabase("$Server", "$incomingName")
If Not src.IsOpen Then Call src.Open("", "")
If Not src.IsOpen Then
  WScript.Echo "FAIL open $incomingName err=" & Err.Description
  WScript.Quit 2
End If
Set dst = src.CreateFromTemplate("$Server", "$probeNsf", False)
If dst Is Nothing Then
  WScript.Echo "FAIL create $probeNsf"
  WScript.Quit 3
End If
If Not dst.IsOpen Then Call dst.Open("", "")
dst.Categories = "KyberEnabled"
WScript.Echo "OK $probeNsf title=[" & dst.Title & "]"
"@
    $vbsPath = Join-Path $env:TEMP "kyber-openntf-probe-$id.vbs"
    Set-Content $vbsPath $vbs -Encoding ASCII
    $createOut = & cscript //nologo $vbsPath 2>&1 | Out-String
    Write-Host $createOut.Trim()
    if ($LASTEXITCODE -ne 0 -or $createOut -notmatch "^OK ") {
      $report += [pscustomobject]@{
        id = $id
        status = "create_failed"
        detail = $createOut.Trim()
        promoteRecommended = $false
        file = $srcFile
      }
      continue
    }

    $exportUri = "$RestUrl/api/kyber-v1/export?nsfPath=$([uri]::EscapeDataString($probeNsf))"
    $outFile = Join-Path $env:TEMP "kyber-openntf-export-$id.json"
    $code = curl.exe -sk -o $outFile -w "%{http_code}" -H "Authorization: Bearer $token" -- "$exportUri"
    if ($code -ne "200") {
      $snip = ""
      if (Test-Path $outFile) { $snip = (Get-Content $outFile -Raw); if ($snip.Length -gt 240) { $snip = $snip.Substring(0, 240) } }
      $report += [pscustomobject]@{
        id = $id
        status = "export_failed"
        http = $code
        detail = $snip
        nsfPath = $probeNsf
        promoteRecommended = $true
        reason = "export_error_signal"
        file = $srcFile
      }
      Write-Host "EXPORT FAIL $id HTTP $code"
      continue
    }

    $exp = Get-Content $outFile -Raw | ConvertFrom-Json
    $hist = Parse-TypeHistogram $exp
    $types = @($hist.Keys)
    $newTypes = @($types | Where-Object { -not $baseline.Contains($_) })
    $rareHit = @($types | Where-Object { $RareWanted -contains $_ })
    $skipped = @($exp.skipped)
    # Do not name this $promote — PowerShell params are case-insensitive and would clobber -$Promote.
    $shouldPromote = ($newTypes.Count -gt 0) -or ($rareHit.Count -gt 0)
    $reason = @()
    if ($newTypes.Count -gt 0) { $reason += "new_types:$($newTypes -join '+')" }
    if ($rareHit.Count -gt 0) { $reason += "rare:$($rareHit -join '+')" }
    if (-not $shouldPromote) { $reason += "no_new_signal" }

    $rowOut = [pscustomobject]@{
      id = $id
      status = "probed"
      nsfPath = $probeNsf
      elements = @($exp.elements).Count
      skippedCount = $skipped.Count
      types = (Format-TypeHist $hist)
      newTypes = ($newTypes -join ",")
      rareTypes = ($rareHit -join ",")
      promoteRecommended = [bool]$shouldPromote
      reason = ($reason -join "; ")
      file = $srcFile
      source = $row.source
      repo = $row.repo
      url = $row.url
    }
    $report += $rowOut
    Write-Host ("PROBE {0} elements={1} new=[{2}] rare=[{3}] promote={4}" -f `
      $id, $rowOut.elements, $rowOut.newTypes, $rowOut.rareTypes, $rowOut.promoteRecommended)
  }
}

if ($Promote) {
  if (-not (Test-Path $ReportPath) -and $report.Count -eq 0) {
    throw "No report to promote — run -Probe first"
  }
  if ($report.Count -eq 0) {
    $report = @(Get-Content $ReportPath -Raw | ConvertFrom-Json)
  }
  $extCopies = @()
  if (Test-Path $ExtCopiesPath) {
    $extParsed = [IO.File]::ReadAllText($ExtCopiesPath) | ConvertFrom-Json
    if ($extParsed -is [System.Array]) { $extCopies = @($extParsed) }
    else { $extCopies = @($extParsed) }
  }
  $existingPaths = @{}
  foreach ($e in $extCopies) {
    $p = $e.nsfPath; if (-not $p) { $p = $e.nsf }
    if ($p) { $existingPaths[$p] = $true }
  }

  foreach ($row in @($report | Where-Object { $_.promoteRecommended -and $_.status -eq "probed" })) {
    $id = $row.id
    $srcProbe = $row.nsfPath
    $destExt = "$Folder\rt_ext_$id.nsf"
    if ($existingPaths.ContainsKey($destExt)) {
      Write-Host "PROMOTE skip exists $destExt"
      continue
    }
    $vbs = @"
Set session = CreateObject("Notes.NotesSession")
On Error Resume Next
Set dst = session.GetDatabase("$Server", "$destExt")
If dst.IsOpen Then
  WScript.Echo "EXISTS $destExt"
  WScript.Quit 0
End If
Err.Clear
On Error GoTo 0
Set src = session.GetDatabase("$Server", "$srcProbe")
If Not src.IsOpen Then Call src.Open("", "")
If Not src.IsOpen Then
  WScript.Echo "FAIL open $srcProbe"
  WScript.Quit 2
End If
Set dst = src.CreateFromTemplate("$Server", "$destExt", False)
If dst Is Nothing Then
  WScript.Echo "FAIL create $destExt"
  WScript.Quit 3
End If
If Not dst.IsOpen Then Call dst.Open("", "")
dst.Categories = "KyberEnabled"
WScript.Echo "OK $destExt"
"@
    $vbsPath = Join-Path $env:TEMP "kyber-openntf-promote-$id.vbs"
    Set-Content $vbsPath $vbs -Encoding ASCII
    $out = & cscript //nologo $vbsPath 2>&1 | Out-String
    Write-Host $out.Trim()
    if ($out -match "^OK " -or $out -match "^EXISTS ") {
      $extCopies += [pscustomobject]@{
        nsfPath = $destExt
        origin = "openntf"
        promotedFrom = $srcProbe
        reason = $row.reason
        types = $row.types
      }
      $existingPaths[$destExt] = $true
      # archive source file under external-ntf root if from candidates
      if ($row.file -and (Test-Path $row.file)) {
        $arch = Join-Path $ExtDir ([IO.Path]::GetFileName($row.file))
        if (-not (Test-Path $arch)) { Copy-Item $row.file $arch -Force }
      }
    }
  }
  $extCopies | ConvertTo-Json -Depth 6 | Set-Content $ExtCopiesPath -Encoding UTF8
  Write-Host "Updated $ExtCopiesPath ($($extCopies.Count) rows)"

  $srcLines = @()
  if (Test-Path $SourcesPath) { $srcLines = @(Get-Content $SourcesPath) }
  $banner = "`r`n# Probe-first promotions ($(Get-Date -Format 'yyyy-MM-dd'))"
  if ($srcLines -notcontains $banner.Trim()) {
    Add-Content $SourcesPath $banner
  }
  foreach ($row in @($report | Where-Object { $_.promoteRecommended })) {
    $line = "- $($row.id): $($row.repo) $($row.url) — $($row.reason)"
    Add-Content $SourcesPath $line
  }
}

if ($report.Count -gt 0) {
  $report | ConvertTo-Json -Depth 6 | Set-Content $ReportPath -Encoding UTF8
  Write-Host ""
  Write-Host "======== PROBE REPORT ========"
  $report | Select-Object id, status, elements, newTypes, rareTypes, promoteRecommended, reason |
    Format-Table -AutoSize | Out-String | Write-Host
  Write-Host "Wrote $ReportPath"
  $prom = @($report | Where-Object { $_.promoteRecommended }).Count
  Write-Host "promoteRecommended=$prom / $($report.Count)"
}
