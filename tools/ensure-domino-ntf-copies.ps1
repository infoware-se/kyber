<#
.SYNOPSIS
  Create KyberEnabled NSF copies of every Domino template (.ntf) on the server.

  Copy path: kyber_ref\rt_ntf_<basename>.nsf
  (e.g. mail145.ntf → kyber_ref\rt_ntf_mail145.nsf)
  Writes ignore/domino-ntf-copies.json with path mapping.
#>
param(
  [string]$Server = "Dantooine",
  [string]$Folder = "kyber_ref",
  [switch]$RefreshOnly,
  [string[]]$Exclude = @()
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
$listPath = Join-Path $RepoRoot "ignore\domino-ntf-list.tsv"
$outJson = Join-Path $RepoRoot "ignore\domino-ntf-copies.json"
New-Item -ItemType Directory -Force -Path (Split-Path $listPath) | Out-Null

$vbsList = @"
Set s = CreateObject("Notes.NotesSession")
Set dir = s.GetDbDirectory("$Server")
Set db = dir.GetFirstDatabase(1248)
Do Until db Is Nothing
  WScript.Echo db.FileName & vbTab & db.Title
  Set db = dir.GetNextDatabase
Loop
"@
Set-Content "$env:TEMP\kyber-list-ntf.vbs" $vbsList -Encoding ASCII
cscript //nologo "$env:TEMP\kyber-list-ntf.vbs" | Set-Content $listPath -Encoding UTF8
$ntfs = @(Get-Content $listPath | Where-Object { $_.Trim() } | ForEach-Object {
    $p = $_ -split "`t", 3
    [pscustomobject]@{ file = $p[0].Trim(); title = $(if ($p.Count -gt 1) { $p[1].Trim() } else { "" }) }
  })
Write-Host "Found $($ntfs.Count) NTF(s) → $listPath"

if ($RefreshOnly) {
  Write-Host "RefreshOnly — list updated, no copies."
  exit 0
}

$excludeSet = @{}
foreach ($e in $Exclude) { $excludeSet[$e.ToLowerInvariant()] = $true }

$vbsEnsure = @"
Set fso = CreateObject("Scripting.FileSystemObject")
Set session = CreateObject("Notes.NotesSession")
' Create folder under data dir if missing (path relative to Directory=)
On Error Resume Next
dataDir = session.GetEnvironmentString("Directory", True)
If dataDir = "" Then dataDir = "C:\Domino\Data"
folderPath = dataDir & "\$Folder"
If Not fso.FolderExists(folderPath) Then fso.CreateFolder folderPath
Err.Clear
On Error GoTo 0
Function EnsureOne(templateFile, newFile)
  On Error Resume Next
  Set dst = session.GetDatabase("$Server", newFile)
  If dst.IsOpen Then
    If InStr(1, dst.Categories, "KyberEnabled", 1) = 0 Then
      If Trim(dst.Categories) = "" Then
        dst.Categories = "KyberEnabled"
      Else
        dst.Categories = Trim(dst.Categories) & ", KyberEnabled"
      End If
    End If
    Call EnsureManagerAcl(dst)
    WScript.Echo "EXISTS " & newFile
    EnsureOne = True
    Exit Function
  End If
  Err.Clear
  On Error GoTo 0
  Set src = session.GetDatabase("$Server", templateFile)
  If Not src.IsOpen Then Call src.Open("", "")
  If Not src.IsOpen Then
    WScript.Echo "FAIL open " & templateFile
    EnsureOne = False
    Exit Function
  End If
  WScript.Echo "CREATE " & newFile & " from " & templateFile
  Set dst = src.CreateFromTemplate("$Server", newFile, False)
  If dst Is Nothing Then
    WScript.Echo "FAIL create " & newFile
    EnsureOne = False
    Exit Function
  End If
  If Not dst.IsOpen Then Call dst.Open("", "")
  dst.Categories = "KyberEnabled"
  On Error Resume Next
  Call EnsureManagerAcl(dst)
  Err.Clear
  On Error GoTo 0
  WScript.Echo "OK " & newFile & " title=[" & dst.Title & "]"
  EnsureOne = True
End Function

Sub EnsureManagerAcl(db)
  On Error Resume Next
  Dim acl, e
  Set acl = db.ACL
  ' Ensure LocalDomainServers / LocalDomainAdmins / -Default- so REST Manager can export/import.
  Call UpsertAclEntry(acl, "-Default-", 0)
  Call UpsertAclEntry(acl, "LocalDomainServers", 6)
  Call UpsertAclEntry(acl, "LocalDomainAdmins", 6)
  Call UpsertAclEntry(acl, "LocalKeepAdmins", 6)
  acl.Save
End Sub

Sub UpsertAclEntry(acl, entryName, level)
  On Error Resume Next
  Dim e
  Set e = acl.GetEntry(entryName)
  If e Is Nothing Then
    Set e = acl.CreateACLEntry(entryName, level)
  Else
    e.Level = level
  End If
  ' 6 = Manager. Also allow create/delete design-ish ops via standard Manager.
  Err.Clear
End Sub
"@

$calls = New-Object System.Collections.Generic.List[string]
$map = @()
foreach ($n in $ntfs) {
  $base = [IO.Path]::GetFileNameWithoutExtension($n.file)
  if ($excludeSet.ContainsKey($n.file.ToLowerInvariant()) -or $excludeSet.ContainsKey($base.ToLowerInvariant())) {
    Write-Host "SKIP excluded $($n.file)"
    continue
  }
  $copy = "$Folder\rt_ntf_$base.nsf"
  [void]$calls.Add("Call EnsureOne(`"$($n.file)`", `"$copy`")")
  $map += [pscustomobject]@{
    template = $n.file
    title = $n.title
    nsf = $copy
  }
}

$vbsBody = $vbsEnsure + "`r`n" + ($calls -join "`r`n") + "`r`n"
Set-Content "$env:TEMP\kyber-ensure-all-ntf.vbs" $vbsBody -Encoding ASCII
Write-Host "Ensuring $($map.Count) NSF copie(s)…"
cscript //nologo "$env:TEMP\kyber-ensure-all-ntf.vbs"
$map | ConvertTo-Json -Depth 4 | Set-Content $outJson -Encoding UTF8
Write-Host "Wrote $outJson ($($map.Count) entries)"
