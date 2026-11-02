<#
.SYNOPSIS
  Progress ticks for long bulk round-trip runs (default every 120s).

  Emits AGENT_LOOP_TICK_rtprogress lines for a Cursor agent loop to pick up.
  Standard cadence for multi-NSF / AllDominoNtf smokes: 120 seconds.

.EXAMPLE
  # While run-bulk-roundtrip-advanced.ps1 is running (pid known):
  .\tools\watch-roundtrip-progress.ps1 -RtPid 25820 -LogPath ignore\roundtrip-all-domino-ntf-2.log

.PARAMETER IntervalSec
  Seconds between ticks. Default 120 (std for longer runs). Use 60 only when
  actively babysitting a short window.
#>
param(
  [Parameter(Mandatory = $true)]
  [int]$RtPid,
  [string]$LogPath = "",
  [int]$IntervalSec = 120,
  [int]$Total = 102
)

$ErrorActionPreference = "Continue"
$RepoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogPath) {
  $LogPath = Join-Path $RepoRoot "ignore\roundtrip-all-domino-ntf-2.log"
}
if (-not [IO.Path]::IsPathRooted($LogPath)) {
  $LogPath = Join-Path $RepoRoot $LogPath
}

Write-Host ("watch-roundtrip-progress RtPid={0} interval={1}s log={2}" -f $RtPid, $IntervalSec, $LogPath)
$prompt = 'Update AllDominoNtf RT progress visualization from the active roundtrip log (pid ' + $RtPid + '). Write a new visualizations/alldomino-rt-progress-N.html snapshot and emit its content-reference tag. If the RT process is finished, post final summary (failedOrImportIssues) and stop this loop.'

while ($true) {
  Start-Sleep -Seconds $IntervalSec
  if (-not (Get-Process -Id $RtPid -ErrorAction SilentlyContinue)) {
    Write-Output ('AGENT_LOOP_TICK_rtprogress {"prompt":"' + $prompt.Replace('"','\"') + '","done":true}')
    break
  }
  Write-Output ('AGENT_LOOP_TICK_rtprogress {"prompt":"' + $prompt.Replace('"','\"') + '"}')
}
