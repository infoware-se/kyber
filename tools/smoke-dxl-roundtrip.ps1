<#
.SYNOPSIS
  Fast DXL round-trip via bulk export → import → export.

  Default path (fidelity):
    GET /export (source) → PUT /import (fresh target) → GET /export (target)
    Compare normalized DXL per (type, name). Frameset size attrs asserted separately.

  -CompareMode import (default, fastest): trust PUT /import results; frameset size check via
    typed GET on target (no second full export).
  -CompareMode presence: second export; key match + frameset sizes (no full DXL normalize).
  -CompareMode full: second export + normalized DXL equality (slow, noisy on Domino rewrite).

  Optional -TypedSpotCheck: also PUT/GET clean-named framesets (regress for DxlImportFixes).

.PARAMETER NsfPath
  Source NSF. When omitted, creates a small blank smoke NSF (POST /database + seeds).

.PARAMETER TargetNsfPath
  Fresh NSF that receives the import. Default: kyber_rt_bulk_<source>.nsf.

.PARAMETER OutJson
  Write machine-readable results here (default: ignore/roundtrip-bulk-<source>.json).
#>
param(
  [string]$RestUrl = "",
  [string]$Token = "",
  [string]$NsfPath = "",
  [string]$TargetNsfPath = "",
  [string]$OutJson = "",
  [ValidateSet("import", "presence", "full")]
  [string]$CompareMode = "import",
  [int]$MaxElementsPerChunk = 150,
  [int]$MaxChunkBytes = 7000000,
  [switch]$SkipCertificateCheck,
  [switch]$KeepTarget,
  [switch]$TypedSpotCheck,
  [int]$MaxDiffs = 50,
  [int]$TimeoutSec = 600
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
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

function Enable-InsecureSsl {
  if (-not $SkipCertificateCheck) { return }
  if ($PSVersionTable.PSVersion.Major -ge 6) { return }
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

function Invoke-Drapi {
  param(
    [string]$Method,
    [string]$Uri,
    [hashtable]$Headers,
    [string]$Body = $null,
    [string]$ContentType = $null,
    [int]$Timeout = $TimeoutSec
  )
  Enable-InsecureSsl
  $p = @{ Method = $Method; Uri = $Uri; Headers = $Headers; TimeoutSec = $Timeout }
  if ($Body) { $p.Body = $Body }
  if ($ContentType) { $p.ContentType = $ContentType }
  if ($SkipCertificateCheck -and $PSVersionTable.PSVersion.Major -ge 6) { $p.SkipCertificateCheck = $true }
  return Invoke-RestMethod @p
}

function Get-BearerToken {
  if ($Token) { return $Token }
  $user = $env:REST_ADMIN_USER; if (-not $user) { $user = $env:DOMINO_REST_USER }
  $pass = $env:REST_ADMIN_PASSWORD; if (-not $pass) { $pass = $env:DOMINO_REST_PASSWORD }
  foreach ($f in @("$RepoRoot\.env", ".\.env")) {
    if (-not $user) {
      $user = Get-EnvFileValue $f "REST_ADMIN_USER"
      if (-not $user) { $user = Get-EnvFileValue $f "DOMINO_REST_USER" }
    }
    if (-not $pass) {
      $pass = Get-EnvFileValue $f "REST_ADMIN_PASSWORD"
      if (-not $pass) { $pass = Get-EnvFileValue $f "DOMINO_REST_PASSWORD" }
    }
  }
  if (-not $user -or -not $pass) { throw "Set -Token or REST_ADMIN_USER / REST_ADMIN_PASSWORD" }
  $auth = Invoke-Drapi -Method POST -Uri "$RestUrl/api/v1/auth" `
    -Headers @{} -ContentType "application/json" `
    -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
  return $auth.bearer
}

function Escape-JsonString([string]$Value) {
  if ($null -eq $Value) { return "" }
  $sb = New-Object System.Text.StringBuilder ($Value.Length + 32)
  foreach ($ch in $Value.ToCharArray()) {
    switch ($ch) {
      '"' { [void]$sb.Append('\"') }
      '\' { [void]$sb.Append('\\') }
      "`b" { [void]$sb.Append('\b') }
      "`f" { [void]$sb.Append('\f') }
      "`n" { [void]$sb.Append('\n') }
      "`r" { [void]$sb.Append('\r') }
      "`t" { [void]$sb.Append('\t') }
      default {
        $code = [int][char]$ch
        if ($code -lt 0x20) {
          [void]$sb.AppendFormat('\u{0:x4}', $code)
        } else {
          [void]$sb.Append($ch)
        }
      }
    }
  }
  return $sb.ToString()
}

function Normalize-Dxl([string]$Dxl) {
  if ([string]::IsNullOrWhiteSpace($Dxl)) { return "" }
  $s = $Dxl
  $s = [regex]::Replace($s, '<\?xml[^?]*\?>', '', 'IgnoreCase')
  # Volatile / identity attrs that legitimately change across NSF copies
  foreach ($attr in @(
      'replicaid', 'noteid', 'unid', 'sequence', 'updatedby', 'wassignedby',
      'lastmodified', 'lastaccessed', 'created', 'modified', 'revision',
      'designerversion', 'fromtemplate', 'templatemodifiedtime', 'database'
    )) {
    $s = [regex]::Replace($s, "\s+$attr\s*=\s*(['""])[^'""]*\1", '', 'IgnoreCase')
  }
  # Volatile child elements Domino rewrites on import/sign
  foreach ($tag in @('updatedby', 'wassignedby', 'revision', 'creation', 'modified', 'lastmodified', 'lastaccessed')) {
    $s = [regex]::Replace($s, "(?is)<$tag\b[^>]*>.*?</$tag>", '', 'IgnoreCase')
    $s = [regex]::Replace($s, "(?is)<$tag\b[^>]*/\s*>", '', 'IgnoreCase')
  }
  $s = [regex]::Replace($s, '>\s+<', '><')
  $s = $s.Trim()
  return $s
}

function Get-ElProp($el, [string]$Name) {
  if ($null -eq $el) { return $null }
  if ($el -is [System.Collections.IDictionary]) { return $el[$Name] }
  return $el.$Name
}

function Get-ElDxl($el) { return [string](Get-ElProp $el 'dxl') }

function Get-ElementKey($el) {
  $type = [string](Get-ElProp $el 'type')
  $name = [string](Get-ElProp $el 'name')
  if ([string]::IsNullOrWhiteSpace($name)) { $name = "(singleton)" }
  return "$type|$name"
}

function Get-AllFramesetSizeAttrs([string]$Dxl) {
  $list = New-Object System.Collections.Generic.List[string]
  foreach ($m in [regex]::Matches($Dxl, "(?is)<frameset\b([^>]*)>")) {
    $attrs = $m.Groups[1].Value
    $c = [regex]::Match($attrs, "\bcolumns\s*=\s*['""]([^'""]*)['""]")
    $r = [regex]::Match($attrs, "\brows\s*=\s*['""]([^'""]*)['""]")
    if ($c.Success) { [void]$list.Add("columns=$($c.Groups[1].Value)") }
    elseif ($r.Success) { [void]$list.Add("rows=$($r.Groups[1].Value)") }
  }
  return ,$list.ToArray()
}

function Index-Elements($elements) {
  $map = @{}
  foreach ($el in @($elements)) {
    $key = Get-ElementKey $el
    if (-not $map.ContainsKey($key)) { $map[$key] = New-Object System.Collections.Generic.List[object] }
    [void]$map[$key].Add($el)
  }
  # Comma prevents PowerShell from enumerating the hashtable into DictionaryEntry rows.
  return ,$map
}

function Compare-Exports($beforeEls, $afterEls, [string]$Mode = "presence") {
  $beforeMap = Index-Elements $beforeEls
  $afterMap = Index-Elements $afterEls
  $matched = 0
  $dxlIdentical = 0
  $missing = New-Object System.Collections.Generic.List[string]
  $extra = New-Object System.Collections.Generic.List[string]
  $dxlChanged = New-Object System.Collections.Generic.List[object]
  $assertFail = New-Object System.Collections.Generic.List[object]
  $byType = @{}
  $doFullDxl = ($Mode -eq "full")

  function Touch-Type([string]$Type, [string]$Field) {
    if (-not $byType.ContainsKey($Type)) {
      $byType[$Type] = [ordered]@{ matched = 0; dxl_identical = 0; missing = 0; extra = 0; dxl_changed = 0; assert_fail = 0 }
    }
    $byType[$Type][$Field] = [int]$byType[$Type][$Field] + 1
  }

  foreach ($key in @($beforeMap.Keys | Sort-Object)) {
    $bList = $beforeMap[$key]
    # Do not assign List via `if (...)` — PS unwraps single-element collections.
    $aList = $null
    $aCount = 0
    if ($afterMap.ContainsKey($key)) {
      $aList = $afterMap[$key]
      $aCount = $aList.Count
    }
    $bCount = $bList.Count
    $type = ($key -split '\|', 2)[0]
    for ($i = 0; $i -lt $bCount; $i++) {
      $b = $bList[$i]
      if ($null -eq $aList -or $i -ge $aCount) {
        [void]$missing.Add($key)
        Touch-Type $type 'missing'
        continue
      }
      $a = $aList[$i]
      $sizesOk = $true
      if ($type -eq 'frameset') {
        $bs = @(Get-AllFramesetSizeAttrs (Get-ElDxl $b)) -join '|'
        $as = @(Get-AllFramesetSizeAttrs (Get-ElDxl $a)) -join '|'
        if ($bs -and ($bs -ne $as)) {
          $sizesOk = $false
          [void]$assertFail.Add([pscustomobject]@{
              key = $key; kind = 'frameset_columns_rows'; before = $bs; after = $as
            })
          Touch-Type $type 'assert_fail'
        }
      }
      $matched++
      Touch-Type $type 'matched'
      if ($doFullDxl) {
        $bn = Normalize-Dxl (Get-ElDxl $b)
        $an = Normalize-Dxl (Get-ElDxl $a)
        if ($bn -eq $an) {
          $dxlIdentical++
          Touch-Type $type 'dxl_identical'
        } elseif ($sizesOk) {
          [void]$dxlChanged.Add([pscustomobject]@{
              key = $key
              beforeLen = $bn.Length
              afterLen = $an.Length
            })
          Touch-Type $type 'dxl_changed'
        }
      }
    }
    if ($aCount -gt $bCount) {
      for ($j = $bCount; $j -lt $aCount; $j++) {
        [void]$extra.Add($key)
        Touch-Type $type 'extra'
      }
    }
  }

  foreach ($key in @($afterMap.Keys | Sort-Object)) {
    if ($beforeMap.ContainsKey($key)) { continue }
    $type = ($key -split '\|', 2)[0]
    foreach ($unused in $afterMap[$key]) {
      [void]$extra.Add($key)
      Touch-Type $type 'extra'
    }
  }

  return ,[ordered]@{
    matched = $matched
    dxlIdentical = $dxlIdentical
    missing = $missing
    extra = $extra
    dxlChanged = $dxlChanged
    assertFail = $assertFail
    byType = $byType
  }
}

function Encode-KyberPathSegment([string]$Value) {
  # .NET EscapeDataString leaves () unencoded; Vert.x 404s on raw parentheses in path segments.
  ($Value.ToCharArray() | ForEach-Object {
      if ($_ -match '[A-Za-z0-9\-._~]') { $_ }
      else { '%{0:X2}' -f [int][char]$_ }
    }) -join ''
}

function Get-KyberNamedUri([string]$Type, [string]$Name, [string]$NsfQuery) {
  # Prefer /named/{type}?name= for Domino names that break path routing when under-encoded.
  if ($Name -match '[()$\\/\s]') {
    return "$RestUrl/api/kyber-v1/named/${Type}?${NsfQuery}&name=$([uri]::EscapeDataString($Name))"
  }
  return "$RestUrl/api/kyber-v1/$($Type)s/$(Encode-KyberPathSegment $Name)?$NsfQuery"
}

function Assert-FramesetsFromSource([string]$Nsf, $beforeEls, $headers) {
  # Cheap contract check without a second full export.
  $q = "nsfPath=$([uri]::EscapeDataString($Nsf))"
  $assertFail = New-Object System.Collections.Generic.List[object]
  $checked = 0
  $skipped = 0
  foreach ($el in @($beforeEls)) {
    if ((Get-ElProp $el 'type') -ne 'frameset') { continue }
    $name = [string](Get-ElProp $el 'name')
    if ([string]::IsNullOrWhiteSpace($name)) {
      $skipped++
      continue
    }
    $before = Get-ElDxl $el
    $bs = [string]::Join('|', @((Get-AllFramesetSizeAttrs $before) | ForEach-Object { $_ }))
    if (-not $bs) { $skipped++; continue }
    $uri = Get-KyberNamedUri 'frameset' $name $q
    try {
      $afterObj = Invoke-Drapi -Method GET -Uri $uri -Headers $headers
      $as = [string]::Join('|', @((Get-AllFramesetSizeAttrs ([string]$afterObj.dxl)) | ForEach-Object { $_ }))
      $checked++
      if ($bs -ne $as) {
        [void]$assertFail.Add([pscustomobject]@{
            key = "frameset|$name"; kind = 'frameset_columns_rows'; before = $bs; after = $as
          })
        Write-Host "ASSERT frameset|$name before=[$bs] after=[$as]"
      } else {
        Write-Host "OK     frameset|$name"
      }
    } catch {
      $msg = $_.Exception.Message
      $kind = 'frameset_get'
      if ($msg -match 'not found' -or $msg -match '\(400\)') {
        $kind = 'frameset_missing_after_import'
        $msg = "missing on target after import (often Domino alias collision): $msg"
      }
      [void]$assertFail.Add([pscustomobject]@{
          key = "frameset|$name"; kind = $kind; before = $bs; after = $msg; uri = $uri
        })
      Write-Host "ASSERT frameset|$name :: $msg"
    }
  }
  return ,[ordered]@{ checked = $checked; skipped = $skipped; assertFail = $assertFail }
}

function New-ImportBody($elements) {
  # Manual JSON — ConvertTo-Json / JavaScriptSerializer choke on large DXL PSObjects.
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.Append('{"elements":[')
  $first = $true
  foreach ($el in @($elements)) {
    if (-not $first) { [void]$sb.Append(',') }
    $first = $false
    $type = Escape-JsonString ([string](Get-ElProp $el 'type'))
    $dxl = Escape-JsonString (Get-ElDxl $el)
    $name = [string](Get-ElProp $el 'name')
    [void]$sb.Append('{"type":"').Append($type).Append('"')
    if (-not [string]::IsNullOrWhiteSpace($name)) {
      [void]$sb.Append(',"name":"').Append((Escape-JsonString $name)).Append('"')
    }
    [void]$sb.Append(',"dxl":"').Append($dxl).Append('"}')
  }
  [void]$sb.Append(']}')
  return $sb.ToString()
}

function Split-ImportChunks($elements, [int]$MaxElements = 150, [int]$MaxBodyBytes = 7000000) {
  # Keep each PUT /import under reverse-proxy limits (413 on ~10 MB bodies).
  # Prefer fewer larger chunks — HTTP round-trips dominate wall time.
  $chunks = New-Object System.Collections.Generic.List[object]
  $current = New-Object System.Collections.Generic.List[object]
  $approx = 16
  foreach ($el in @($elements)) {
    $dxlLen = (Get-ElDxl $el).Length
    $nameLen = ([string](Get-ElProp $el 'name')).Length
    $rowApprox = 64 + [int]($dxlLen * 1.15) + $nameLen + ([string](Get-ElProp $el 'type')).Length
    $shouldFlush =
      ($current.Count -ge $MaxElements) -or
      ($current.Count -gt 0 -and ($approx + $rowApprox) -gt $MaxBodyBytes)
    if ($shouldFlush) {
      [void]$chunks.Add($current)
      $current = New-Object System.Collections.Generic.List[object]
      $approx = 16
    }
    [void]$current.Add($el)
    $approx += $rowApprox
    # Oversized single note: flush immediately so it travels alone.
    if ($current.Count -eq 1 -and $approx -gt $MaxBodyBytes) {
      [void]$chunks.Add($current)
      $current = New-Object System.Collections.Generic.List[object]
      $approx = 16
    }
  }
  if ($current.Count -gt 0) { [void]$chunks.Add($current) }
  return ,$chunks
}

function Invoke-ImportPut([string]$Uri, [string]$JsonBody, [string]$Bearer) {
  $importFile = Join-Path $env:TEMP ("kyber-import-" + [guid]::NewGuid().ToString('n') + ".json")
  [IO.File]::WriteAllText($importFile, $JsonBody, [Text.UTF8Encoding]::new($false))
  Enable-InsecureSsl
  $bytes = [IO.File]::ReadAllBytes($importFile)
  $importRaw = $null
  $attempts = 3
  for ($try = 1; $try -le $attempts; $try++) {
    $wr = [System.Net.HttpWebRequest]::Create($Uri)
    $wr.Method = "PUT"
    $wr.ContentType = "application/json; charset=utf-8"
    $wr.Timeout = $TimeoutSec * 1000
    $wr.ReadWriteTimeout = $TimeoutSec * 1000
    $wr.Headers.Add("Authorization", "Bearer $Bearer")
    $wr.ContentLength = $bytes.Length
    $reqStream = $wr.GetRequestStream()
    $reqStream.Write($bytes, 0, $bytes.Length)
    $reqStream.Close()
    try {
      $resp = $wr.GetResponse()
      $reader = New-Object IO.StreamReader($resp.GetResponseStream())
      $importRaw = $reader.ReadToEnd()
      $reader.Close(); $resp.Close()
      break
    } catch [System.Net.WebException] {
      $errResp = $_.Exception.Response
      $importRaw = $null
      $status = $null
      if ($errResp) {
        $status = [int]$errResp.StatusCode
        $reader = New-Object IO.StreamReader($errResp.GetResponseStream())
        $importRaw = $reader.ReadToEnd()
        $reader.Close()
      }
      $msg = $_.Exception.Message
      $retriable = ($status -eq 408 -or $status -eq 502 -or $status -eq 503 -or $msg -match '408|timed out|timeout')
      if ($retriable -and $try -lt $attempts) {
        $wait = 15 * $try
        Write-Host "PUT /import retry $try/$attempts after HTTP $($status) (wait ${wait}s)"
        Start-Sleep -Seconds $wait
        continue
      }
      Remove-Item -LiteralPath $importFile -Force -ErrorAction SilentlyContinue
      throw "PUT /import failed: $msg $importRaw"
    }
  }
  Remove-Item -LiteralPath $importFile -Force -ErrorAction SilentlyContinue
  return ($importRaw | ConvertFrom-Json)
}

function Get-ExportElements([string]$Nsf, $headers) {
  # File + JavaScriptSerializer avoids Invoke-RestMethod's 2 MB MaxJsonLength default.
  Add-Type -AssemblyName System.Web.Extensions -ErrorAction Stop
  $ser = New-Object System.Web.Script.Serialization.JavaScriptSerializer
  $ser.MaxJsonLength = [int]::MaxValue
  $tmp = Join-Path $env:TEMP ("kyber-export-" + [IO.Path]::GetFileNameWithoutExtension($Nsf) + "-" + [guid]::NewGuid().ToString('n') + ".json")
  Enable-InsecureSsl
  $uri = "$RestUrl/api/kyber-v1/export?nsfPath=$([uri]::EscapeDataString($Nsf))"
  $p = @{ Uri = $uri; Headers = $headers; OutFile = $tmp; TimeoutSec = $TimeoutSec; UseBasicParsing = $true }
  if ($SkipCertificateCheck -and $PSVersionTable.PSVersion.Major -ge 6) { $p.SkipCertificateCheck = $true }
  Invoke-WebRequest @p | Out-Null
  $bytes = (Get-Item -LiteralPath $tmp).Length
  Write-Host ("  export file {0:N0} bytes" -f $bytes)
  $obj = $ser.DeserializeObject([IO.File]::ReadAllText($tmp))
  Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
  return ,@($obj["elements"])
}

function Ensure-BlankSource([string]$Path, $headers) {
  $q = "nsfPath=$([uri]::EscapeDataString($Path))"
  Write-Host "DELETE $Path (fresh blank source)"
  try { Invoke-Drapi -Method DELETE -Uri "$RestUrl/api/kyber-v1/database?$q" -Headers $headers | Out-Null } catch {}
  Write-Host "POST /database → $Path"
  Invoke-Drapi -Method POST -Uri "$RestUrl/api/kyber-v1/database?$q&dbTitle=$([uri]::EscapeDataString($Path))" `
    -Headers $headers | Out-Null
  foreach ($seed in @(
      @{ path = "forms"; name = "RtForm" },
      @{ path = "agents"; name = "RtAgent"; query = "&kind=formula" }
    )) {
    $uri = "$RestUrl/api/kyber-v1/$($seed.path)?$q&name=$([uri]::EscapeDataString($seed.name))$($seed.query)"
    try {
      Invoke-Drapi -Method POST -Uri $uri -Headers $headers -ContentType "application/json" -Body "{}" | Out-Null
      Write-Host "seeded $($seed.path)/$($seed.name)"
    } catch {
      Write-Host "seed skip $($seed.path)/$($seed.name): $($_.Exception.Message)"
    }
  }
}

function Invoke-TypedFramesetSpotCheck([string]$Nsf, $beforeEls, $headers) {
  $q = "nsfPath=$([uri]::EscapeDataString($Nsf))"
  $results = @()
  $frames = @($beforeEls | Where-Object {
      (Get-ElProp $_ 'type') -eq 'frameset' -and
      -not [string]::IsNullOrWhiteSpace([string](Get-ElProp $_ 'name'))
    })
  foreach ($el in $frames) {
    $name = [string](Get-ElProp $el 'name')
    $uri = Get-KyberNamedUri 'frameset' $name $q
    $before = Get-ElDxl $el
    $bs = @(Get-AllFramesetSizeAttrs $before) -join '|'
    try {
      $body = (@{ dxl = $before } | ConvertTo-Json -Compress -Depth 5)
      Invoke-Drapi -Method PUT -Uri $uri -Headers $headers -ContentType "application/json; charset=utf-8" -Body $body | Out-Null
      $afterObj = Invoke-Drapi -Method GET -Uri $uri -Headers $headers
      $as = @(Get-AllFramesetSizeAttrs ([string]$afterObj.dxl)) -join '|'
      $ok = (-not $bs) -or ($bs -eq $as)
      $results += [ordered]@{ name = $name; ok = $ok; before = $bs; after = $as }
      if ($ok) { Write-Host "TYPED OK  frameset $name" }
      else { Write-Host "TYPED FAIL frameset $name before=[$bs] after=[$as]" }
    } catch {
      Write-Host "TYPED ERR  frameset $name :: $($_.Exception.Message)"
      $results += [ordered]@{ name = $name; ok = $false; error = $_.Exception.Message }
    }
  }
  return $results
}

# --- main ---
if (-not $RestUrl) {
  $RestUrl = $env:DOMINO_REST_API_DATA_URL
  if (-not $RestUrl) { $RestUrl = $env:DOMINO_REST_URL }
}
foreach ($f in @("$RepoRoot\.env", ".\.env")) {
  if (-not $RestUrl) {
    $RestUrl = Get-EnvFileValue $f "DOMINO_REST_API_DATA_URL"
    if (-not $RestUrl) { $RestUrl = Get-EnvFileValue $f "DOMINO_REST_URL" }
  }
}
if (-not $RestUrl) { throw "Pass -RestUrl or set DOMINO_REST_API_DATA_URL" }
$RestUrl = $RestUrl.TrimEnd("/")

$createdBlankSource = $false
if ([string]::IsNullOrWhiteSpace($NsfPath)) {
  $NsfPath = "kyber_roundtrip.nsf"
  $createdBlankSource = $true
}
if ([string]::IsNullOrWhiteSpace($TargetNsfPath)) {
  $base = [IO.Path]::GetFileNameWithoutExtension($NsfPath)
  $TargetNsfPath = "kyber_rt_bulk_$base.nsf"
}
if ([string]::IsNullOrWhiteSpace($OutJson)) {
  $OutJson = Join-Path $RepoRoot "ignore\roundtrip-bulk-$([IO.Path]::GetFileNameWithoutExtension($NsfPath)).json"
}

$bearer = Get-BearerToken
$headers = @{ Authorization = "Bearer $bearer" }

$sw = [Diagnostics.Stopwatch]::StartNew()
if ($createdBlankSource) {
  Ensure-BlankSource $NsfPath $headers
}

Write-Host "GET /export ← $NsfPath"
$beforeEls = Get-ExportElements $NsfPath $headers
if ($beforeEls.Count -lt 1) { throw "export returned no elements from $NsfPath" }
$typeGroups = @{}
foreach ($el in $beforeEls) {
  $t = [string](Get-ElProp $el 'type')
  if (-not $typeGroups.ContainsKey($t)) { $typeGroups[$t] = 0 }
  $typeGroups[$t]++
}
$byType = @($typeGroups.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { "$($_.Key)=$($_.Value)" })
Write-Host ("source elements={0}  {1}" -f $beforeEls.Count, ($byType -join ", "))

$tq = "nsfPath=$([uri]::EscapeDataString($TargetNsfPath))"
Write-Host "DELETE $TargetNsfPath (fresh target)"
try { Invoke-Drapi -Method DELETE -Uri "$RestUrl/api/kyber-v1/database?$tq" -Headers $headers | Out-Null } catch {}

$chunkList = Split-ImportChunks $beforeEls -MaxElements $MaxElementsPerChunk -MaxBodyBytes $MaxChunkBytes
Write-Host "PUT /import → $TargetNsfPath ($($beforeEls.Count) elements in $($chunkList.Count) chunk(s), CompareMode=$CompareMode)"
$importUri = "$RestUrl/api/kyber-v1/import?$tq&dbTitle=$([uri]::EscapeDataString($TargetNsfPath))"
$imported = 0; $failedImport = 0
$importFailRows = @()
$createdFlag = $false
for ($chunkIndex = 0; $chunkIndex -lt $chunkList.Count; $chunkIndex++) {
  $chunk = $chunkList[$chunkIndex]
  $importJson = New-ImportBody $chunk
  Write-Host ("  chunk {0}/{1}: {2} elements, ~{3:N0} json chars" -f ($chunkIndex + 1), $chunkList.Count, $chunk.Count, $importJson.Length)
  $importResult = Invoke-ImportPut -Uri $importUri -JsonBody $importJson -Bearer $bearer
  if ($importResult.created) { $createdFlag = $true }
  if ($null -ne $importResult.imported) { $imported += [int]$importResult.imported }
  if ($null -ne $importResult.failed) { $failedImport += [int]$importResult.failed }
  if ([int]$importResult.failed -gt 0) {
    foreach ($row in @($importResult.results)) {
      if ($row.success -eq $false -and $importFailRows.Count -lt 30) {
        $msg = ("{0} {1}: {2}" -f $row.type, $row.name, $row.message)
        Write-Host "IMPORT FAIL $msg"
        $importFailRows += $msg
      }
    }
  }
}
Write-Host ("import imported={0} failed={1} created={2}" -f $imported, $failedImport, $createdFlag)

$afterEls = @()
$typed = @()
$fsCheck = $null
$cmp = $null
if ($CompareMode -eq "import") {
  Write-Host "CompareMode=import — skip second export; check frameset sizes via typed GET"
  $fsCheck = Assert-FramesetsFromSource $TargetNsfPath $beforeEls $headers
  $missing = New-Object System.Collections.Generic.List[string]
  foreach ($msg in $importFailRows) {
    # "type name: message"
    if ($msg -match '^\s*(\S+)\s+(.+?):\s') {
      [void]$missing.Add("$($Matches[1])|$($Matches[2])")
    }
  }
  $cmp = [ordered]@{
    matched = $imported
    dxlIdentical = 0
    missing = $missing
    extra = (New-Object System.Collections.Generic.List[string])
    dxlChanged = (New-Object System.Collections.Generic.List[object])
    assertFail = $fsCheck.assertFail
    byType = @{}
  }
} else {
  Write-Host "GET /export ← $TargetNsfPath"
  $afterEls = Get-ExportElements $TargetNsfPath $headers
  Write-Host ("target elements={0}" -f $afterEls.Count)
  $cmp = Compare-Exports $beforeEls $afterEls -Mode $CompareMode
}
$sw.Stop()

Write-Host ""
Write-Host "======== BULK ROUND-TRIP ========"
Write-Host ("source={0} target={1} mode={2}" -f $NsfPath, $TargetNsfPath, $CompareMode)
Write-Host ("matched={0} dxl_identical={1} missing={2} extra={3} dxl_changed={4} assert_fail={5} import_failed={6} sec={7}" -f `
    $cmp.matched, $cmp.dxlIdentical, $cmp.missing.Count, $cmp.extra.Count, $cmp.dxlChanged.Count, $cmp.assertFail.Count, $failedImport, [int]$sw.Elapsed.TotalSeconds)

$shown = 0
foreach ($m in $cmp.missing) {
  if ($shown -ge $MaxDiffs) { break }
  Write-Host "MISSING $m"
  $shown++
}
foreach ($e in $cmp.extra) {
  if ($shown -ge $MaxDiffs) { break }
  Write-Host "EXTRA   $e"
  $shown++
}
foreach ($d in $cmp.dxlChanged) {
  if ($shown -ge $MaxDiffs) { break }
  Write-Host ("CHANGED {0} beforeLen={1} afterLen={2}" -f $d.key, $d.beforeLen, $d.afterLen)
  $shown++
}
foreach ($a in $cmp.assertFail) {
  if ($shown -ge $MaxDiffs) { break }
  Write-Host ("ASSERT  {0} {1} before=[{2}] after=[{3}]" -f $a.key, $a.kind, $a.before, $a.after)
  $shown++
}

if ($TypedSpotCheck) {
  Write-Host ""
  Write-Host "======== TYPED FRAMESET SPOT-CHECK (on target) ========"
  $typed = Invoke-TypedFramesetSpotCheck $TargetNsfPath $beforeEls $headers
}

$byTypeOut = @{}
foreach ($k in @($cmp.byType.Keys)) { $byTypeOut[$k] = $cmp.byType[$k] }

function Cap-List($list, [int]$n) {
  $out = New-Object System.Collections.ArrayList
  if ($null -eq $list) { return @() }
  $i = 0
  foreach ($item in $list) {
    if ($i -ge $n) {
      [void]$out.Add("… +more")
      break
    }
    [void]$out.Add($item)
    $i++
  }
  return ,$out.ToArray()
}

$payload = [pscustomobject]@{
  mode = "bulk_export_import_$CompareMode"
  compareMode = $CompareMode
  source = $NsfPath
  target = $TargetNsfPath
  sourceElements = $beforeEls.Count
  targetElements = $(if ($afterEls) { $afterEls.Count } else { $null })
  sourceTypes = ($byType -join ", ")
  matched = [int]$cmp.matched
  dxlIdentical = [int]$cmp.dxlIdentical
  missingCount = $cmp.missing.Count
  extraCount = $cmp.extra.Count
  dxlChangedCount = $cmp.dxlChanged.Count
  assertFailCount = $cmp.assertFail.Count
  missing = @(Cap-List $cmp.missing $MaxDiffs)
  extra = @(Cap-List $cmp.extra $MaxDiffs)
  dxlChanged = @(Cap-List $cmp.dxlChanged $MaxDiffs)
  assertFail = @(Cap-List $cmp.assertFail $MaxDiffs)
  importFailed = $failedImport
  importImported = $imported
  importFailSamples = @($importFailRows)
  framesetChecked = $(if ($fsCheck) { $fsCheck.checked } else { $null })
  byType = $byTypeOut
  typedSpotCheck = @($typed)
  seconds = [int]$sw.Elapsed.TotalSeconds
}
New-Item -ItemType Directory -Force -Path (Split-Path $OutJson) | Out-Null
($payload | ConvertTo-Json -Depth 8) | Set-Content -Path $OutJson -Encoding UTF8
Write-Host "Wrote $OutJson"

if (-not $KeepTarget) {
  try {
    Invoke-Drapi -Method DELETE -Uri "$RestUrl/api/kyber-v1/database?$tq" -Headers $headers | Out-Null
    Write-Host "deleted $TargetNsfPath"
  } catch {
    Write-Host "leave ${TargetNsfPath}: $($_.Exception.Message)"
  }
}
if ($createdBlankSource -and -not $KeepTarget) {
  try {
    $sq = "nsfPath=$([uri]::EscapeDataString($NsfPath))"
    Invoke-Drapi -Method DELETE -Uri "$RestUrl/api/kyber-v1/database?$sq" -Headers $headers | Out-Null
    Write-Host "deleted $NsfPath"
  } catch {}
}

# Fail the script only on frameset size asserts (known fix contract).
# missing / importFailed / dxl_changed are reported for triage (Domino often rewrites DXL).
if ($cmp.assertFail.Count -gt 0) {
  throw "Bulk round-trip ASSERT failed (assert_fail=$($cmp.assertFail.Count)). See $OutJson"
}
Write-Host ("DONE bulk round-trip matched={0} dxl_identical={1} missing={2} extra={3} dxl_changed={4} assert_fail=0 import_failed={5}" -f `
    $cmp.matched, $cmp.dxlIdentical, $cmp.missing.Count, $cmp.extra.Count, $cmp.dxlChanged.Count, $failedImport)
