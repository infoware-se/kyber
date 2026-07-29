# Restart Domino REST API via Notes COM console:
#   tell restapi quit  -> swap JARs safely  -> load restapi
# Management :8889 is down while restapi is quit; COM still works.
#
# Usage:
#   .\tools\restart-restapi-com.ps1
#   .\tools\restart-restapi-com.ps1 -Server YourDominoServer
#   .\tools\restart-restapi-com.ps1 -QuitOnly
#   .\tools\restart-restapi-com.ps1 -LoadOnly

param(
  [string]$Server = $env:DOMINO_SERVER,
  [switch]$QuitOnly,
  [switch]$LoadOnly,
  [int]$StopTimeoutSec = 45,
  [int]$StartTimeoutSec = 90
)

$ErrorActionPreference = "Stop"

if (-not $Server) { $Server = "YourDominoServer" }

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

function Wait-RestapiHttp([int]$TimeoutSec) {
  $url = $env:DOMINO_REST_API_DATA_URL
  if (-not $url) { $url = $env:DOMINO_REST_URL }
  if (-not $url) { $url = "https://your-domino-server:8880" }
  $url = $url.TrimEnd("/") + "/"
  for ($i = 1; $i -le $TimeoutSec; $i++) {
    try {
      $code = curl.exe -sk -m 5 -o NUL -w "%{http_code}" $url
      if ($code -eq "200" -or $code -eq "401") {
        Write-Host "REST up after ${i}s (HTTP $code)"
        return
      }
      if ($i % 5 -eq 0) { Write-Host "waiting HTTP=$code (${i}s)" }
    } catch { }
    Start-Sleep -Seconds 1
  }
  throw "REST API not responding after ${TimeoutSec}s ($url)"
}

if ($LoadOnly -and $QuitOnly) {
  throw "Use only one of -QuitOnly / -LoadOnly"
}

if (-not $LoadOnly) {
  Invoke-DominoConsole "tell restapi quit"
  Wait-NrestapiStopped $StopTimeoutSec
}

if ($QuitOnly) { return }

if (-not $QuitOnly) {
  # brief pause so file locks clear after quit
  Start-Sleep -Seconds 2
  Invoke-DominoConsole "load restapi"
  Wait-RestapiHttp $StartTimeoutSec
}
