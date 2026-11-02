<#
.SYNOPSIS
  Move root-level rt_ntf_*.nsf copies into kyber_ref\ (CreateFromTemplate + remove old).
  Updates ignore/domino-ntf-copies.json paths.
#>
param(
  [string]$Server = "Dantooine",
  [string]$Folder = "kyber_ref",
  [string]$DataDir = "C:\Domino\Data"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
$mapPath = Join-Path $RepoRoot "ignore\domino-ntf-copies.json"
$destDir = Join-Path $DataDir $Folder
New-Item -ItemType Directory -Force -Path $destDir | Out-Null

$rootCopies = @(Get-ChildItem -Path $DataDir -Filter "rt_ntf_*.nsf" -File -ErrorAction SilentlyContinue)
Write-Host ("Root rt_ntf_*.nsf count={0} → {1}\" -f $rootCopies.Count, $Folder)
if ($rootCopies.Count -eq 0) {
  Write-Host "Nothing to move at data root."
  if (Test-Path $mapPath) {
    $map = Get-Content $mapPath -Raw | ConvertFrom-Json
    $need = @($map | Where-Object { $_.nsf -notlike "$Folder\*" })
    if ($need.Count -eq 0) {
      Write-Host "Map already under $Folder\"
      exit 0
    }
  }
}

$vbs = @"
Set session = CreateObject("Notes.NotesSession")
Function MigrateOne(oldPath, newPath)
  On Error Resume Next
  Set dst = session.GetDatabase("$Server", newPath)
  If dst.IsOpen Then
    WScript.Echo "DEST_EXISTS " & newPath
  Else
    Err.Clear
    On Error GoTo 0
    Set src = session.GetDatabase("$Server", oldPath)
    If Not src.IsOpen Then Call src.Open("", "")
    If Not src.IsOpen Then
      WScript.Echo "FAIL open " & oldPath
      MigrateOne = False
      Exit Function
    End If
    WScript.Echo "CREATE " & newPath & " from " & oldPath
    Set dst = src.CreateFromTemplate("$Server", newPath, False)
    If dst Is Nothing Then
      WScript.Echo "FAIL create " & newPath
      MigrateOne = False
      Exit Function
    End If
    If Not dst.IsOpen Then Call dst.Open("", "")
    dst.Categories = "KyberEnabled"
    WScript.Echo "OK " & newPath
  End If
  On Error Resume Next
  Set oldDb = session.GetDatabase("$Server", oldPath)
  If oldDb.IsOpen Then
    Call oldDb.Remove
    If Err.Number <> 0 Then
      WScript.Echo "WARN remove " & oldPath & " " & Err.Description
      Err.Clear
    Else
      WScript.Echo "REMOVED " & oldPath
    End If
  Else
    ' try open then remove
    Call oldDb.Open("", "")
    If oldDb.IsOpen Then
      Call oldDb.Remove
      If Err.Number <> 0 Then
        WScript.Echo "WARN remove " & oldPath & " " & Err.Description
        Err.Clear
      Else
        WScript.Echo "REMOVED " & oldPath
      End If
    End If
  End If
  MigrateOne = True
End Function
"@

$calls = New-Object System.Collections.Generic.List[string]
$newMap = @()

if (Test-Path $mapPath) {
  $map = @(Get-Content $mapPath -Raw | ConvertFrom-Json)
} else {
  $map = @($rootCopies | ForEach-Object {
    [pscustomobject]@{ template = ""; title = ""; nsf = $_.Name }
  })
}

foreach ($row in $map) {
  $leaf = [IO.Path]::GetFileName([string]$row.nsf)
  if ($leaf -notlike "rt_ntf_*.nsf") { continue }
  $oldPath = $leaf
  $newPath = "$Folder\$leaf"
  # Prefer migrating from current map path if it is still root-level
  $from = [string]$row.nsf
  if ($from -like "*\*" -or $from -like "*/*") {
    # already nested — only migrate if basename also exists at root
    if (-not (Test-Path (Join-Path $DataDir $leaf))) {
      $newMap += [pscustomobject]@{ template = $row.template; title = $row.title; nsf = $newPath }
      continue
    }
    $from = $leaf
  }
  [void]$calls.Add("Call MigrateOne(`"$from`", `"$newPath`")")
  $newMap += [pscustomobject]@{ template = $row.template; title = $row.title; nsf = $newPath }
}

# Any leftover root files not in map
foreach ($f in $rootCopies) {
  $leaf = $f.Name
  $newPath = "$Folder\$leaf"
  if (-not ($newMap | Where-Object { $_.nsf -eq $newPath })) {
    [void]$calls.Add("Call MigrateOne(`"$leaf`", `"$newPath`")")
    $newMap += [pscustomobject]@{ template = ""; title = ""; nsf = $newPath }
  }
}

if ($calls.Count -eq 0) {
  Write-Host "No migrate calls."
} else {
  $body = $vbs + "`r`n" + ($calls -join "`r`n") + "`r`n"
  $vbsPath = "$env:TEMP\kyber-migrate-kyber-ref.vbs"
  Set-Content $vbsPath -Value $body -Encoding ASCII
  Write-Host "Migrating $($calls.Count) NSF(s)…"
  cscript //nologo $vbsPath
}

$newMap | ConvertTo-Json -Depth 4 | Set-Content $mapPath -Encoding UTF8
$left = @(Get-ChildItem -Path $DataDir -Filter "rt_ntf_*.nsf" -File -ErrorAction SilentlyContinue).Count
$inFolder = @(Get-ChildItem -Path $destDir -Filter "rt_ntf_*.nsf" -File -ErrorAction SilentlyContinue).Count
Write-Host "Wrote $mapPath  in_${Folder}=$inFolder  root_left=$left"
