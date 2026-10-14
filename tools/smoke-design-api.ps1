<#
.SYNOPSIS
  Live smoke of design-v1 against DRAPI: defaults, CRUD status codes, XPages ensure.

.DESCRIPTION
  Auth via C:\Git\install-domino-restapi\.env (or DOMINO_REST_* / REST_ADMIN_*).
  Creates a throwaway NSF (PUT /designdxl?dbTitle=...), then asserts:

  - GET /defaults/{type} (named + kinded)
  - PUT/GET/DELETE missing named element → not found (HTTP 404 or KEEP-mapped 400)
  - empty-body POST create → 200/201, duplicate → 409
  - GET + PUT update round-trip on a form
  - GET applicationproperties + dbicon + acl on kyber.nsf; POST rejected on appprops/dbicon
  - POST /xpagesproject creates skeleton; second call skips; POST /xpages empty-body works

  Leaves the NSF on the server (design-v1 never deletes databases).

.PARAMETER NsfPath
  Target NSF. Default: smoke-design-api-<yyyyMMddHHmmss>.nsf (fresh each run).

.PARAMETER EnvFile
  Path to .env with DOMINO_REST_API_DATA_URL and REST_ADMIN_* credentials.
#>
param(
  [string]$NsfPath = "",
  [string]$EnvFile = "C:\Git\install-domino-restapi\.env"
)

$ErrorActionPreference = "Stop"

function Get-EnvFileValue([string]$Path, [string]$Key) {
  if (-not (Test-Path $Path)) { return $null }
  foreach ($line in Get-Content $Path) {
    if ($line -match "^\s*#" -or $line -notmatch "=") { continue }
    $k, $v = $line -split "=", 2
    if ($k.Trim() -eq $Key) { return $v.Trim().Trim('"').Trim("'") }
  }
  return $null
}

function Fail([string]$Msg) {
  Write-Host "FAIL: $Msg" -ForegroundColor Red
  exit 1
}

function Ok([string]$Msg) { Write-Host "OK  $Msg" -ForegroundColor Green }

function Assert-NotFound([string]$Label, [string]$Code, [string]$Body) {
  if ($Code -notin @("404", "400")) {
    Fail "$Label expected 404/400 not-found, got $Code $Body"
  }
  if ($Body -notmatch '(?i)not found') {
    Fail "$Label expected not-found message, got $Code $Body"
  }
  Ok "$Label -> $Code (not found)"
}


function Invoke-Design {
  param(
    [string]$Method,
    [string]$Url,
    [string]$BodyFile = $null,
    [string]$ContentType = $null,
    [string]$OutFile
  )
  # Do not use $args — it is an automatic variable in PowerShell and breaks splatting.
  $curlArgs = @("-sk", "-o", $OutFile, "-w", "%{http_code}", "-X", $Method,
    "-H", "Authorization: Bearer $token")
  if ($ContentType) { $curlArgs += @("-H", "Content-Type: $ContentType") }
  if ($BodyFile) { $curlArgs += @("--data-binary", "@$BodyFile") }
  return (& curl.exe @curlArgs $Url)
}

if (-not $NsfPath) {
  $NsfPath = "smoke-design-api-{0:yyyyMMddHHmmss}.nsf" -f (Get-Date)
}

$base = $env:DOMINO_REST_API_DATA_URL
if (-not $base) { $base = Get-EnvFileValue $EnvFile "DOMINO_REST_API_DATA_URL" }
if (-not $base) { Fail "DOMINO_REST_API_DATA_URL missing" }
$base = $base.TrimEnd("/")

$user = $env:REST_ADMIN_USER; if (-not $user) { $user = Get-EnvFileValue $EnvFile "REST_ADMIN_USER" }
$pass = $env:REST_ADMIN_PASSWORD; if (-not $pass) { $pass = Get-EnvFileValue $EnvFile "REST_ADMIN_PASSWORD" }
if (-not $user -or -not $pass) { Fail "REST admin credentials missing" }

@{ username = $user; password = $pass } | ConvertTo-Json -Compress |
  Set-Content -Encoding ascii "$env:TEMP\keep-auth-smoke.json"
$token = ((curl.exe -sk -X POST "$base/api/v1/auth" -H "Content-Type: application/json" `
  --data-binary "@$env:TEMP\keep-auth-smoke.json") | ConvertFrom-Json).bearer
if (-not $token) { Fail "auth failed" }
Ok "authenticated"
Write-Host "NSF: $NsfPath"

$out = Join-Path $env:TEMP "smoke-out.bin"
$empty = Join-Path $env:TEMP "smoke-empty.txt"
[IO.File]::WriteAllText($empty, "")

function Design-Url([string]$ApiPath, [string]$ExtraQuery = "") {
  $q = "nsfPath=$([uri]::EscapeDataString($NsfPath))"
  if ($ExtraQuery) { $q = "$q&$ExtraQuery" }
  return "$base/api/design-v1/${ApiPath}?$q"
}

# --- Defaults ---
foreach ($q in @(
  "form",
  "agent?kind=lotusscript",
  "scriptlibrary?kind=java",
  "javasource?kind=class",
  "xpage"
)) {
  $code = Invoke-Design GET "$base/api/design-v1/defaults/$q" -OutFile $out
  if ($code -ne "200") { Fail "GET /defaults/$q -> HTTP $code" }
  $j = Get-Content $out -Raw | ConvertFrom-Json
  if (-not $j.dxl) { Fail "GET /defaults/$q missing dxl" }
  Ok "defaults/$q source=$($j.source) name=$($j.name)"
}

# --- Ensure NSF ---
$smokeTitle = "Smoke Design API"
$bulkFile = Join-Path $env:TEMP "smoke-bulk.json"
[IO.File]::WriteAllText($bulkFile, (@{ nsfPath = $NsfPath; elements = @() } | ConvertTo-Json -Compress -Depth 5))
$code = Invoke-Design PUT (Design-Url "designdxl" "dbTitle=$([uri]::EscapeDataString($smokeTitle))") `
  -BodyFile $bulkFile -ContentType "application/json" -OutFile $out
if ($code -notin @("200", "201")) {
  Fail "PUT /designdxl (ensure NSF) -> HTTP $code $(Get-Content $out -Raw)"
}
$bulkResp = Get-Content $out -Raw | ConvertFrom-Json
if ($bulkResp.created -and $bulkResp.dbTitle -ne $smokeTitle) {
  Fail "expected dbTitle=$smokeTitle on create, got $($bulkResp.dbTitle)"
}
Ok "nsf ready created=$($bulkResp.created) dbTitle=$($bulkResp.dbTitle)"

# Verify title on application properties when freshly created
if ($bulkResp.created) {
  $code = Invoke-Design GET (Design-Url "applicationproperties") -OutFile $out
  if ($code -ne "200") { Fail "GET /applicationproperties -> HTTP $code" }
  $ap = Get-Content $out -Raw | ConvertFrom-Json
  if ($ap.dxl -notmatch [regex]::Escape($smokeTitle)) {
    Fail "appprops DXL missing title '$smokeTitle'"
  }
  if ($ap.dxl -match '<item name=.\$TITLE.><text/>') {
    Fail "appprops still has empty `$TITLE"
  }
  Ok "applicationproperties title=$smokeTitle"
}

# --- Always-present: GET on kyber.nsf (fresh empty NSF may lack $DBIcon) ---
$defaultNsf = "kyber.nsf"
function Design-UrlOn([string]$TargetNsf, [string]$ApiPath, [string]$ExtraQuery = "") {
  $q = "nsfPath=$([uri]::EscapeDataString($TargetNsf))"
  if ($ExtraQuery) { $q = "$q&$ExtraQuery" }
  return "$base/api/design-v1/${ApiPath}?$q"
}
foreach ($singleton in @("applicationproperties", "dbicon", "acl")) {
  $code = Invoke-Design GET (Design-UrlOn $defaultNsf $singleton) -OutFile $out
  if ($code -ne "200") { Fail "GET /$singleton on $defaultNsf -> HTTP $code $(Get-Content $out -Raw)" }
  Ok "GET /$singleton ($defaultNsf)"
}
foreach ($singleton in @("applicationproperties", "dbicon")) {
  $code = Invoke-Design POST (Design-UrlOn $defaultNsf $singleton) -BodyFile $empty `
    -ContentType "text/xml" -OutFile $out
  if ($code -notin @("404", "405", "400", "501")) {
    Fail "POST /$singleton should be rejected, got HTTP $code"
  }
  Ok "POST /$singleton rejected ($code)"
}

# Fresh NSF still has applicationproperties after create
$code = Invoke-Design GET (Design-Url "applicationproperties") -OutFile $out
if ($code -ne "200") { Fail "GET /applicationproperties on fresh NSF -> HTTP $code" }
Ok "GET /applicationproperties (fresh NSF)"

# --- Missing named element: GET / PUT / DELETE → not found (KEEP often maps NotFound → 400) ---
$missing = "DoesNotExistSmoke_$([guid]::NewGuid().ToString('N').Substring(0,8))"
$missingPath = "forms/$([uri]::EscapeDataString($missing))"
$minimalForm = @"
<?xml version='1.0'?>
<!DOCTYPE form SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<form name='$missing' xmlns='http://www.lotus.com/dxl' version='14.5'>
<body><richtext><pardef id='1'/><par def='1'/></richtext></body>
</form>
"@
$minimalFormFile = Join-Path $env:TEMP "smoke-missing-form.dxl"
[IO.File]::WriteAllText($minimalFormFile, $minimalForm)

$code = Invoke-Design GET (Design-Url $missingPath) -OutFile $out
Assert-NotFound "GET /forms/$missing" $code (Get-Content $out -Raw)

$code = Invoke-Design PUT (Design-Url $missingPath) -BodyFile $minimalFormFile `
  -ContentType "text/xml" -OutFile $out
Assert-NotFound "PUT /forms/$missing" $code (Get-Content $out -Raw)

$code = Invoke-Design DELETE (Design-Url $missingPath) -OutFile $out
Assert-NotFound "DELETE /forms/$missing" $code (Get-Content $out -Raw)

# --- Empty-body creates + 409 ---
$creates = @(
  @{ coll = "forms"; qs = "name=SmokeForm" },
  @{ coll = "agents"; qs = "kind=lotusscript&name=SmokeAgent" },
  @{ coll = "scriptlibraries"; qs = "kind=lotusscript&name=SmokeLib" },
  @{ coll = "javasources"; qs = "kind=class&name=se/infoware/kyber/SmokeJava.java" }
)
foreach ($c in $creates) {
  $url = Design-Url $c.coll $c.qs
  $code = Invoke-Design POST $url -BodyFile $empty -ContentType "text/xml" -OutFile $out
  if ($code -notin @("200", "201")) {
    Fail "POST /$($c.coll)?$($c.qs) -> HTTP $code $(Get-Content $out -Raw)"
  }
  Ok "created $($c.coll) $($c.qs)"

  $code2 = Invoke-Design POST $url -BodyFile $empty -ContentType "text/xml" -OutFile $out
  if ($code2 -ne "409") { Fail "expected 409 on duplicate $($c.coll), got $code2" }
  Ok "409 on duplicate $($c.coll)"
}

# --- Form GET + PUT round-trip ---
$formGet = Join-Path $env:TEMP "smoke-form.get.json"
$code = Invoke-Design GET (Design-Url "forms/SmokeForm") -OutFile $formGet
if ($code -ne "200") { Fail "GET /forms/SmokeForm -> HTTP $code" }
$formJson = Get-Content $formGet -Raw | ConvertFrom-Json
$formDxl = $formJson.dxl
if (-not $formDxl) { $formDxl = Get-Content $formGet -Raw }
if ($formDxl -notmatch "<form") { Fail "GET form response not DXL" }
$formPut = Join-Path $env:TEMP "smoke-form.put.dxl"
[IO.File]::WriteAllText($formPut, $formDxl)
$code = Invoke-Design PUT (Design-Url "forms/SmokeForm") -BodyFile $formPut `
  -ContentType "text/xml" -OutFile $out
if ($code -ne "200") { Fail "PUT /forms/SmokeForm -> HTTP $code $(Get-Content $out -Raw)" }
Ok "PUT /forms/SmokeForm update"

# --- XPages project ensure + create xpage ---
$code = Invoke-Design POST (Design-Url "xpagesproject") -OutFile $out
if ($code -notin @("200", "201")) {
  Fail "POST /xpagesproject -> HTTP $code $(Get-Content $out -Raw)"
}
$xp = Get-Content $out -Raw | ConvertFrom-Json
if (-not $xp.ensured) { Fail "xpagesproject response missing ensured=true: $(Get-Content $out -Raw)" }
$createdCount = @($xp.created).Count
if ($createdCount -lt 1) {
  Fail "xpagesproject on fresh NSF should create skeleton files, created=$createdCount"
}
Ok "xpagesproject created=$createdCount skipped=$(@($xp.skipped).Count)"

$code = Invoke-Design POST (Design-Url "xpagesproject") -OutFile $out
if ($code -notin @("200", "201")) { Fail "POST /xpagesproject (2nd) -> HTTP $code" }
$xp2 = Get-Content $out -Raw | ConvertFrom-Json
$created2 = @($xp2.created).Count
if ($created2 -ne 0) { Fail "2nd xpagesproject should create 0, got $created2" }
Ok "xpagesproject idempotent (skipped=$(@($xp2.skipped).Count))"

$xpageName = "SmokePage.xsp"
$code = Invoke-Design POST (Design-Url "xpages" "name=$([uri]::EscapeDataString($xpageName))") `
  -BodyFile $empty -ContentType "text/xml" -OutFile $out
if ($code -notin @("200", "201")) {
  Fail "POST /xpages?name=$xpageName -> HTTP $code $(Get-Content $out -Raw)"
}
Ok "created xpage $xpageName"

$code = Invoke-Design GET (Design-Url "xpages/$([uri]::EscapeDataString($xpageName))") -OutFile $out
if ($code -ne "200") { Fail "GET /xpages/$xpageName -> HTTP $code" }
Ok "GET /xpages/$xpageName"

# --- DELETE created named elements (cleanup inside NSF) ---
$deletes = @(
  @{ coll = "forms"; name = "SmokeForm" },
  @{ coll = "agents"; name = "SmokeAgent" },
  @{ coll = "scriptlibraries"; name = "SmokeLib" },
  @{ coll = "javasources"; name = "se/infoware/kyber/SmokeJava.java" },
  @{ coll = "xpages"; name = $xpageName }
)
foreach ($d in $deletes) {
  $item = "$($d.coll)/$([uri]::EscapeDataString($d.name))"
  $code = Invoke-Design DELETE (Design-Url $item) -OutFile $out
  if ($code -ne "200") {
    Fail "DELETE /$item -> HTTP $code $(Get-Content $out -Raw)"
  }
  Ok "deleted $item"

  $code = Invoke-Design GET (Design-Url $item) -OutFile $out
  Assert-NotFound "GET after DELETE $($d.name)" $code (Get-Content $out -Raw)
}

Write-Host "SMOKE PASSED (NSF left in place: $NsfPath)" -ForegroundColor Green
exit 0
