# Restart Domino REST API via Notes COM console:
#   tell restapi quit  -> swap JARs safely  -> load restapi
# Management :8889 is down while restapi is quit; COM still works.
#
# Usage:
#   .\tools\restart-restapi-com.ps1
#   .\tools\restart-restapi-com.ps1 -Server YourDominoServer
#   .\tools\restart-restapi-com.ps1 -QuitOnly
#   .\tools\restart-restapi-com.ps1 -LoadOnly
#
# Do NOT start Domino with nserver.exe by hand when the Windows service exists —
# that leaves HCLDominoServer running as nservice without a real server.
# Rapid quit/load cycles have panicked nrestapi (LockMemHandle 0x0 / LookupHandle
# out of range) on this host; this script cools down after quit and requires a
# stable HTTP window before reporting success.
# Also avoid empty PUT /import against a large existing NSF right after reload —
# that path used to compileAll and panic; Kyber now skips compile when nothing
# was written, and smoke skips ensure-import when the NSF already exists.

param(
  [string]$Server = $env:DOMINO_SERVER,
  [switch]$QuitOnly,
  [switch]$LoadOnly,
  [int]$StopTimeoutSec = 45,
  [int]$StartTimeoutSec = 120,
  # Extra idle after nrestapi exits before load — Domino/JNA handle cleanup.
  [int]$PostQuitCoolDownSec = 8,
  # Keep checking after first HTTP 200; panics often hit within ~10s of Started.
  [int]$StableWindowSec = 20
)

$ErrorActionPreference = "Stop"

function Get-EnvFileValue([string]$Path, [string]$Key) {
  if (-not $Path -or -not (Test-Path $Path)) { return $null }
  foreach ($line in Get-Content $Path) {
    if ($line -match "^\s*#" -or $line -notmatch "=") { continue }
    $k, $v = $line -split "=", 2
    if ($k.Trim() -eq $Key) { return $v.Trim().Trim('"').Trim("'") }
  }
  return $null
}

function Find-KyberEnvFile {
  foreach ($f in @((Join-Path $PSScriptRoot "..\.env"), ".\.env")) {
    if (Test-Path $f) { return (Resolve-Path $f).Path }
  }
  return $null
}

$KyberEnvFile = Find-KyberEnvFile
if (-not $Server) { $Server = $env:DOMINO_SERVER }
if (-not $Server) { $Server = Get-EnvFileValue $KyberEnvFile "DOMINO_SERVER" }
if (-not $Server) {
  throw "Set -Server or DOMINO_SERVER to the Notes server name used by SendConsoleCommand."
}

function Invoke-DominoConsole([string]$Command) {
  $session = New-Object -ComObject Notes.NotesSession
  try {
    try { $null = $session.Initialize() } catch { }
    $result = $session.SendConsoleCommand($Server, $Command)
    Write-Host "COM [$Server]: $Command"
    if ($result) { Write-Host "  -> $result" }
    return $result
  } finally {
    [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($session)
  }
}

function Wait-NrestapiStopped([int]$TimeoutSec) {
  for ($i = 1; $i -le $TimeoutSec; $i++) {
    if (-not (Get-Process nrestapi -ErrorAction SilentlyContinue)) {
      Write-Host "nrestapi stopped after ${i}s"
      return
    }
    Start-Sleep -Seconds 1
  }
  throw "nrestapi still running after ${TimeoutSec}s"
}

function Get-RestDataUrl {
  $url = $env:DOMINO_REST_API_DATA_URL
  if (-not $url) { $url = $env:DOMINO_REST_URL }
  if (-not $url) { $url = Get-EnvFileValue $KyberEnvFile "DOMINO_REST_API_DATA_URL" }
  if (-not $url) { $url = Get-EnvFileValue $KyberEnvFile "DOMINO_REST_URL" }
  if (-not $url) {
    throw "Set DOMINO_REST_API_DATA_URL or DOMINO_REST_URL so the restart can see that REST is up."
  }
  return $url.TrimEnd("/") + "/"
}

function Test-RestHttpOk([string]$Url) {
  try {
    $code = curl.exe -sk -m 5 -o NUL -w "%{http_code}" $Url
    return ($code -eq "200" -or $code -eq "401")
  } catch {
    return $false
  }
}

function Wait-RestapiStable([int]$TimeoutSec, [int]$StableSec) {
  $url = Get-RestDataUrl
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  $firstOk = $null
  $i = 0
  while ((Get-Date) -lt $deadline) {
    $i++
    if (-not (Get-Process nrestapi -ErrorAction SilentlyContinue)) {
      if ($firstOk) {
        throw "nrestapi exited after first HTTP OK (likely Domino PANIC / LockMemHandle). Check console.log / IBM_TECHNICAL_SUPPORT."
      }
      if ($i % 5 -eq 0) { Write-Host "waiting nrestapi process (${i}s)" }
      Start-Sleep -Seconds 1
      continue
    }
    $ok = Test-RestHttpOk $url
    if ($ok) {
      if (-not $firstOk) {
        $firstOk = Get-Date
        Write-Host "REST first OK after ${i}s — holding ${StableSec}s for stability"
      }
      $held = ((Get-Date) - $firstOk).TotalSeconds
      if ($held -ge $StableSec) {
        if (-not (Get-Process nrestapi -ErrorAction SilentlyContinue)) {
          throw "nrestapi died during stability window"
        }
        Write-Host "REST stable after ${i}s (HTTP OK for ${StableSec}s, nrestapi alive)"
        return
      }
    } else {
      if ($firstOk) {
        Write-Host "HTTP dropped after first OK — resetting stability window"
        $firstOk = $null
      }
      if ($i % 5 -eq 0) { Write-Host "waiting HTTP (${i}s)" }
    }
    Start-Sleep -Seconds 1
  }
  throw "REST API not stable after ${TimeoutSec}s ($url)"
}

if ($LoadOnly -and $QuitOnly) {
  throw "Use only one of -QuitOnly / -LoadOnly"
}

if (-not $LoadOnly) {
  Invoke-DominoConsole "tell restapi quit"
  Wait-NrestapiStopped $StopTimeoutSec
  if ($PostQuitCoolDownSec -gt 0) {
    Write-Host "Cool-down ${PostQuitCoolDownSec}s after quit (Notes handle cleanup)"
    Start-Sleep -Seconds $PostQuitCoolDownSec
  }
}

if ($QuitOnly) { return }

if (-not $QuitOnly) {
  Invoke-DominoConsole "load restapi"
  Wait-RestapiStable $StartTimeoutSec $StableWindowSec
}
