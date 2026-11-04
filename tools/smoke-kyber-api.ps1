<#
.SYNOPSIS
  Live smoke of kyber-v1 against DRAPI: CRUD status codes, and XPages.

.DESCRIPTION
  Auth via DOMINO_REST_* / REST_ADMIN_* or a gitignored .env. Notes COM uses DOMINO_SERVER.
  Uses kybertest.nsf (PUT /import?dbTitle=... creates it when missing), then asserts:

  - missing form → not found
  - every named CRUD collection: list, empty-body create or keep, 409, get, put, list contains name.
    Get, put, and the list element include noteId and unid. Kinded types return that kind.
    An XPage fileName is newxpage.xsp. A custom control fileName is newcustomcontrol.xsp
  - Hello World written into each created element and read back
  - LotusScript, formula, simple-action and Java agents are run
  - The LotusScript agent calls SmokeLibLs. The Java agent calls SmokeLibJava.text().
    Class and file names match the design element names Designer compiles.
    Each run must save Hello World (FIELD on the SmokeHello document)
  - Formula and simple-action agents save Hello World on the same document
  - After a Java create: exactly one plugin.xml and one .classpath; .classpath must not
    list Designer's temp Local/ source folder; plugin.xml must omit class=plugin.Activator.
  - With -Extended: deliberate Local/ pollution is healed on the next Java create
    (xpagesProject.repaired). Use that for nightly / pre-release; keep default smoke must-pass.
  - Domino URL commands open the database, form, page, about, using, view, folder,
    navigator, frameset, icon, image, file, stylesheet, agents, web service and XPage
  - ?CreateDocument, ?OpenDocument, ?EditDocument and ?DeleteDocument on Hello World
  - kinded agents, script libraries and Java sources, one element per kind
  - singletons about, using, databasescript, xspproperties: create or keep, 409, get, put, Hello World
  - applicationproperties, dbicon and acl: get + put of the same DXL
  - PUT /acl that drops -Default- or LocalDomainServers is 400 and the ACL is unchanged
  - GET /defaults and GET /defaults/{type} stay 404
  - POST /validate broken DXL returns errors with index and code=invalid_dxl
  - PUT /database?dbTitle= retitles kybertest.nsf and restores the smoke title
  - GET /acl returns structured entries (including -Default-) and roles
  - a NSF created by this run has the embedded Kyber database icon
  - export-to-repo.ps1 writes kybertest.nsf to a temp folder, then import-from-repo.ps1
    restores that tree into kybersmoke-import.nsf. The repo domino/ templates are not touched.
    GET /export replicaId matches the folder name. Each smoke agent noteId matches
    Name-noteid.dxl and the export element. The import replicaId is a different NSF.
  - GET /databases replicaId for kybertest.nsf matches that export.
  - POST /database refuses KeepConfig.nsf. A missing NSF is created, and a second create of that file is 409. The file stays.
  - DELETE /database refuses KeepConfig.nsf. A disposable NSF is created and then deleted. kybertest.nsf stays.
  - 0.26 product surface: lookup/search, copy/rename/patch, binary /content, /compile,
    /templates + /defaults/preview, /named/{type}, /designnotes list + missing get,
    /database/category on a disposable NSF, /database/fromTemplate (+ inherit when possible)
  - after that, DELETE each smoked element and the Hello World document, then create the
    design again so kybertest.nsf still holds it. -SkipDelete leaves everything in place.

  kybertest.nsf is left on the server with the created design still in it.

.PARAMETER NsfPath
  Target NSF. Default: kybertest.nsf. Reused on the next run.

.PARAMETER EnvFile
  Path to .env with DOMINO_REST_API_DATA_URL and REST_ADMIN_* credentials.
  When omitted, the script uses the repo-root .env (copy of .env.example).

.PARAMETER SkipCertificateCheck
  Pass curl -k. Leave this off for a certificate the machine trusts.

.PARAMETER SkipDelete
  Do not call DELETE. Existing design is updated and left in place, including the
  Hello World document created by the web checks. Without this switch the deletes
  run once, after export and import, and the design is created again afterwards.

.PARAMETER JavaHelloOnly
  Rewrite and run only SmokeLibJava and SmokeAgentJava, then stop.

.PARAMETER Extended
  Also run slower / healing probes (Local/ classpath pollution → ensure repair).
  Intended for nightly or pre-release; default smoke stays must-pass without this.
#>
param(
  [string]$NsfPath = "",
  [string]$EnvFile = "",
  [switch]$SkipCertificateCheck,
  [switch]$SkipDelete,
  [switch]$JavaHelloOnly,
  [switch]$Extended
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

function Assert-NoteId([string]$Label, $Value) {
  if ([string]$Value -notmatch '^[0-9a-f]{1,8}$') { Fail "$Label noteId '$Value'" }
}

function Assert-Unid([string]$Label, $Value) {
  if ([string]$Value -notmatch '^[0-9A-Fa-f]{32}$') { Fail "$Label unid '$Value'" }
}

function Assert-ReplicaId([string]$Label, $Value) {
  if ([string]$Value -notmatch '^[0-9A-F]{16}$') { Fail "$Label replicaId '$Value'" }
}

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
  # Truncate so HTTP 000 failures do not show a previous response body.
  if ($OutFile) { [IO.File]::WriteAllBytes($OutFile, [byte[]]@()) }
  $curlSsl = if ($SkipCertificateCheck) { "-sk" } else { "-s" }
  $curlArgs = @($curlSsl, "-o", $OutFile, "-w", "%{http_code}", "-X", $Method,
    "-H", "Authorization: Bearer $token")
  if ($ContentType) { $curlArgs += @("-H", "Content-Type: $ContentType") }
  if ($BodyFile) { $curlArgs += @("--data-binary", "@$BodyFile") }
  return (& curl.exe @curlArgs $Url)
}

function Get-ResponseText { Get-Content $out -Raw }

function Assert-Code([string]$Label, [string]$Code, [string[]]$Allowed) {
  if ($Code -notin $Allowed) {
    Fail "$Label -> HTTP $Code $(Get-ResponseText)"
  }
}

function Save-DxlFromGet([string]$Label) {
  $body = Get-ResponseText
  $dxl = $null
  try { $dxl = ($body | ConvertFrom-Json).dxl } catch { }
  if (-not $dxl) { Fail "$Label response missing dxl" }
  $putFile = Join-Path $env:TEMP "smoke-put.dxl"
  [IO.File]::WriteAllText($putFile, $dxl)
  return $putFile
}

# Designer keeps a comment attribute on typed notes and a $Comment item on raw notes.
function Set-HelloWorldDxl([string]$Dxl) {
  # Shared Actions keeps the note name $ACTIONS. Designer lists the action title.
  if ($Dxl -match '<sharedactions\b') {
    $Dxl = [regex]::Replace($Dxl, "title='[^']*'", "title='SmokeAction'", 1)
  }
  # A raw note keeps a previous $Comment unless the DXL comment attribute is set.
  $item = '<item name=''$Comment''><text>Hello World</text></item>'
  $existing = [regex]'(?s)<item name=''\$Comment''[^>]*>\s*<text>.*?</text>\s*</item>'
  $existing2 = [regex]'(?s)<item name="\$Comment"[^>]*>\s*<text>.*?</text>\s*</item>'
  if ($Dxl -match "comment='[^']*'") {
    $Dxl = [regex]::Replace($Dxl, "comment='[^']*'", "comment='Hello World'", 1)
  } elseif ($Dxl -match 'comment="[^"]*"') {
    $Dxl = [regex]::Replace($Dxl, 'comment="[^"]*"', 'comment="Hello World"', 1)
  } elseif ($Dxl -match '<note\b') {
    $Dxl = [regex]::Replace($Dxl, '<note\b', "<note comment='Hello World'", 1)
  }
  if ($existing.IsMatch($Dxl)) { return $existing.Replace($Dxl, $item, 1) }
  if ($existing2.IsMatch($Dxl)) { return $existing2.Replace($Dxl, $item, 1) }
  $close = [regex]'(?s)</(\w+)>\s*$'
  if (-not $close.IsMatch($Dxl)) { return $null }
  return $close.Replace($Dxl, ($item + '</${1}>' + [Environment]::NewLine), 1)
}

function Set-AgentHelloDxl([string]$Dxl, [string]$Kind) {
  $dxl = $Dxl
  if ($dxl -notmatch "runaswebuser='true'") {
    $dxl = [regex]::Replace($dxl, "<agent\b", "<agent runaswebuser='true'", 1)
  }
  $dxl = [regex]::Replace($dxl, "(?s)<code\b.*?</code>\s*", "")
  $dxl = [regex]::Replace($dxl, "<trigger\b[^>]*/>", "<trigger type='agentlist'/>", 1)
  $docType = if ($Kind -in @("lotusscript", "java")) { "runonce" } else { "all" }
  $dxl = [regex]::Replace($dxl, "<documentset\b[^>]*/>", "<documentset type='$docType'/>", 1)
  $code = switch ($Kind) {
    "lotusscript" {
@"
<code event='options'><lotusscript>Option Public
Option Declare
Use "SmokeLibLs"
</lotusscript></code>
<code event='initialize'><lotusscript>Sub Initialize
	Dim hello As String
	hello = SmokeHello()
	Print hello
	Dim session As New NotesSession
	Dim db As NotesDatabase
	Set db = session.CurrentDatabase
	Dim doc As NotesDocument
	Set doc = db.CreateDocument
	Call doc.ReplaceItemValue("Form", "SmokeHello")
	Call doc.ReplaceItemValue("SmokeHello", hello)
	Call doc.Save(True, False)
End Sub
</lotusscript></code>
"@
    }
    "formula" {
      "<code event='action'><formula>SELECT @All;`r`nFIELD SmokeHelloFormula := `"Hello World`"</formula></code>"
    }
    "simpleaction" {
      "<code event='action'><simpleaction action='modifyfield' field='SmokeHelloSimple' value='Hello World'/></code>"
    }
    "java" {
@"
<code event='action'><javaproject class='SmokeAgentJava.class' codepath='c:\Program Files\HCL\Notes\Data'><sharedlibraryref name='SmokeLibJava'/><java name='SmokeAgentJava.java'>import lotus.domino.*;

public class SmokeAgentJava extends AgentBase {

    public void NotesMain() {

      try {
          String hello = SmokeLibJava.text();
          Session session = getSession();
          AgentContext agentContext = session.getAgentContext();
          Document doc = agentContext.getCurrentDatabase().createDocument();
          doc.replaceItemValue("Form", "SmokeHello");
          doc.replaceItemValue("SmokeHelloJava", hello);
          doc.save(true, false);

      } catch(Exception e) {
          e.printStackTrace();
       }
   }
}</java></javaproject></code>
"@
    }
    default { $null }
  }
  if (-not $code) { return $null }
  $patched = [regex]::Replace($dxl, "(<documentset\b[^>]*/>)", "`$1$code", 1)
  if ($patched -eq $dxl) { return $null }
  return $patched
}

function Invoke-NotesVbs([string]$Body) {
  $cscript = Join-Path $env:WINDIR "SysWOW64\cscript.exe"
  if (-not (Test-Path $cscript)) { Fail "32-bit cscript is required to run Notes agents" }
  $path = Join-Path $env:TEMP "smoke-notes.vbs"
  $log = Join-Path $env:TEMP "smoke-notes.out"
  [IO.File]::WriteAllText($path, $Body)
  $proc = Start-Process -FilePath $cscript -ArgumentList @("//nologo", $path) -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err"
  if (-not $proc.WaitForExit(90000)) {
    Stop-Process -Id $proc.Id -Force
    Fail "Notes COM timed out"
  }
  $proc.WaitForExit() | Out-Null
  $text = ""
  if (Test-Path $log) { $text = Get-Content $log -Raw }
  $errText = ""
  if (Test-Path "$log.err") { $errText = Get-Content "$log.err" -Raw }
  if ($proc.ExitCode -and $proc.ExitCode -ne 0) { Fail "Notes COM failed: $text $errText" }
  return $text
}

function Invoke-HelloAgent([string]$AgentName, [switch]$ResetDocs) {
  $reset = ""
  if ($ResetDocs) {
    $reset = @'
Set dc = db.Search("Form = ""SmokeHello""", Nothing, 0)
Set doc = dc.GetFirstDocument()
Do While Not doc Is Nothing
  Set nxt = dc.GetNextDocument(doc)
  Call doc.Remove(True)
  Set doc = nxt
Loop
'@
  }
  $vbs = @"
Option Explicit
Dim session, db, agent, dc, doc, nxt, item
On Error Resume Next
Set session = CreateObject("Notes.NotesSession")
Set db = session.GetDatabase("$DominoServer", "kybertest.nsf")
If Not db.IsOpen Then
  WScript.Echo "OPEN FAIL " & Err.Description
  WScript.Quit 1
End If
$reset
Set agent = db.GetAgent("$AgentName")
If agent Is Nothing Then
  WScript.Echo "NO AGENT"
  WScript.Quit 2
End If
Err.Clear
Call agent.Run("")
If Err.Number <> 0 Then
  WScript.Echo "RUN FAIL " & Err.Number & " " & Err.Description
  WScript.Quit 3
End If
WScript.Echo "RAN"
Set dc = db.Search("Form = ""SmokeHello""", Nothing, 0)
WScript.Echo "DOCS " & dc.Count
Set doc = dc.GetFirstDocument()
Do While Not doc Is Nothing
  For Each item In doc.Items
    If Left(item.Name, 1) <> "`$" Then WScript.Echo "FIELD " & LCase(item.Name) & "=" & item.Text
  Next
  Set doc = dc.GetNextDocument(doc)
Loop
"@
  return Invoke-NotesVbs $vbs
}

function Get-SmokeJavac {
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\javac.exe") }
  $candidates += @(Get-ChildItem "C:\Program Files\Microsoft\jdk*\bin\javac.exe" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
  $found = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
  if (-not $found) { Fail "javac is required to compile the Java agent and script library" }
  return $found
}

function Get-SmokeNotesJar {
  foreach ($path in @(
    "C:\Domino\ndext\Notes.jar",
    "C:\Program Files\HCL\Notes\ndext\Notes.jar"
  )) {
    if (Test-Path $path) { return $path }
  }
  Fail "Notes.jar is required to compile the Java agent"
}

function Remove-JavaObjectJar([string]$Dxl) {
  return [regex]::Replace(
    $Dxl,
    "(?s)<item name='\$FILE'[^>]*>.*?<file\b[^>]*name='%%object%%\.jar'.*?</item>\s*",
    "")
}

function Add-ObjectJar([string]$Dxl, [string]$JarPath) {
  $Dxl = Remove-JavaObjectJar $Dxl
  $bytes = [IO.File]::ReadAllBytes($JarPath)
  $b64 = [Convert]::ToBase64String($bytes)
  $lines = for ($i = 0; $i -lt $b64.Length; $i += 72) {
    $b64.Substring($i, [Math]::Min(72, $b64.Length - $i))
  }
  $item = @"
<item name='`$FILE' summary='true' sign='true' seal='true'><object><file hosttype='msdos' compression='none' flags='sign' encoding='none' name='%%object%%.jar' size='$($bytes.Length)'>
<created><datetime dst='true'>20261018T120000,00+02</datetime></created>
<modified><datetime dst='true'>20261018T120000,00+02</datetime></modified>
<filedata>
$($lines -join "`n")
</filedata></file></object></item>
"@
  $close = [regex]::Match($Dxl, '(?s)</(agent|scriptlibrary)>\s*$')
  if (-not $close.Success) { return $null }
  return $Dxl.Substring(0, $close.Index) + $item + "</$($close.Groups[1].Value)>" + [Environment]::NewLine
}

function Compile-JavaHello {
  $dir = Join-Path $env:TEMP "kyber-smoke-java"
  if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
  New-Item -ItemType Directory -Path $dir | Out-Null
  @'
public class SmokeLibJava {
  public static String text() {
    return "Hello World";
  }
}
'@ | Set-Content -Encoding ascii (Join-Path $dir "SmokeLibJava.java")
  @'
import lotus.domino.*;

public class SmokeAgentJava extends AgentBase {
    public void NotesMain() {
      try {
          String hello = SmokeLibJava.text();
          Session session = getSession();
          AgentContext agentContext = session.getAgentContext();
          Document doc = agentContext.getCurrentDatabase().createDocument();
          doc.replaceItemValue("Form", "SmokeHello");
          doc.replaceItemValue("SmokeHelloJava", hello);
          doc.save(true, false);
      } catch(Exception e) {
          e.printStackTrace();
       }
   }
}
'@ | Set-Content -Encoding ascii (Join-Path $dir "SmokeAgentJava.java")
  $javac = Get-SmokeJavac
  $jar = Join-Path (Split-Path $javac) "jar.exe"
  $notes = Get-SmokeNotesJar
  & $javac -Xlint:-options --release 8 -cp "$notes;$dir" -d $dir (Join-Path $dir "SmokeLibJava.java") (Join-Path $dir "SmokeAgentJava.java")
  if ($LASTEXITCODE -ne 0) { Fail "javac could not compile the Java hello classes" }
  Push-Location $dir
  & $jar cf (Join-Path $dir "library-object.jar") SmokeLibJava.class
  & $jar cf (Join-Path $dir "agent-object.jar") SmokeAgentJava.class SmokeLibJava.class
  Pop-Location
  if (-not (Test-Path (Join-Path $dir "agent-object.jar"))) { Fail "the Java agent jar was not built" }
}

function Publish-JavaObjectJar([string]$Url, [string]$Dxl, [string]$JarPath) {
  $stamped = Add-ObjectJar $Dxl $JarPath
  if (-not $stamped) { Fail "could not attach the compiled Java jar" }
  $file = Join-Path $env:TEMP "smoke-java-jar.dxl"
  [IO.File]::WriteAllText($file, $stamped)
  $code = Invoke-Design PUT $Url -BodyFile $file -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT compiled Java $Url" $code @("200")
}

function Invoke-JavaHelloOnServer([string]$AgentName) {
  $vbs = @"
Option Explicit
Dim session, db, nc, nid, doc, scan, title, obj, agent, dc, item, logText
Set session = CreateObject("Notes.NotesSession")
Set db = session.GetDatabase("$DominoServer", "kybertest.nsf")
If Not db.IsOpen Then
  WScript.Echo "OPEN FAIL"
  WScript.Quit 1
End If
Set nc = db.CreateNoteCollection(False)
nc.SelectAgents = True
nc.SelectScriptLibraries = True
nc.BuildCollection
nid = nc.GetFirstNoteId()
Do While nid <> ""
  Set doc = db.GetDocumentByID(nid)
  title = ""
  For Each scan In doc.Items
    If scan.Name = "`$TITLE" Then title = scan.Text
  Next
  If title = "$AgentName" Or title = "($AgentName)" Or title = "SmokeLibJava" Then
    Do
      Set obj = Nothing
      On Error Resume Next
      Set obj = doc.GetAttachment("%%object%%.jar")
      On Error GoTo 0
      If obj Is Nothing Then Exit Do
      If obj.FileSize < 200 Then
        Call obj.Remove
        Call doc.Save(True, True)
      Else
        Exit Do
      End If
    Loop
    ' Sign after the jar is final. A later edit corrupts the library signature.
    Call doc.Sign()
    Call doc.Save(True, False)
  End If
  nid = nc.GetNextNoteId(nid)
Loop
Set agent = db.GetAgent("$AgentName")
If agent Is Nothing Then
  WScript.Echo "NO AGENT"
  WScript.Quit 2
End If
On Error Resume Next
logText = agent.RunOnServer()
If Err.Number <> 0 Then
  WScript.Echo "RUN FAIL " & Err.Number & " " & Err.Description
  WScript.Quit 3
End If
On Error GoTo 0
WScript.Echo "RAN"
Set dc = db.Search("Form = ""SmokeHello""", Nothing, 0)
Set doc = dc.GetFirstDocument()
Do While Not doc Is Nothing
  For Each item In doc.Items
    If Left(item.Name, 1) <> "`$" Then WScript.Echo "FIELD " & LCase(item.Name) & "=" & item.Text
  Next
  Set doc = dc.GetNextDocument(doc)
Loop
"@
  return Invoke-NotesVbs $vbs
}

function Assert-RunnableAgent([string]$Url, [string]$Label, [string]$Kind) {
  $code = Invoke-Design GET $Url -OutFile $out
  Assert-Code "GET $Label for hello" $code @("200")
  $dxl = $null
  try { $dxl = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
  if (-not $dxl) { Fail "$Label hello: response missing dxl" }
  $stamped = Set-AgentHelloDxl $dxl $Kind
  if (-not $stamped) { Fail "$Label hello: could not write agent code" }
  $helloFile = Join-Path $env:TEMP "smoke-hello.dxl"
  [IO.File]::WriteAllText($helloFile, $stamped)
  $code = Invoke-Design PUT $Url -BodyFile $helloFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT hello $Label" $code @("200")
  $code = Invoke-Design GET $Url -OutFile $out
  Assert-Code "GET hello $Label" $code @("200")
  $back = $null
  try { $back = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
  $needle = switch ($Kind) {
    "lotusscript" { 'Use "SmokeLibLs"' }
    "formula" { 'FIELD SmokeHelloFormula := "Hello World"' }
    "simpleaction" { "value='Hello World'" }
    "java" { 'SmokeLibJava.text()' }
  }
  if ($back -notlike "*$needle*") { Fail "$Label hello source was not returned" }
  $agentName = ($Label -split "/")[-1] -replace '\s+\(.*$', ''
  if ($Kind -eq "java") {
    Compile-JavaHello
    Publish-JavaObjectJar $Url $back (Join-Path $env:TEMP "kyber-smoke-java\agent-object.jar")
    $ran = Invoke-JavaHelloOnServer $agentName
  } else {
    $ran = Invoke-HelloAgent $agentName -ResetDocs:((-not $SkipDelete) -and ($Kind -eq "lotusscript"))
  }
  if ($ran -notmatch '(?m)^RAN\s*$') { Fail "$Label did not run: $ran" }
  $field = switch ($Kind) {
    "lotusscript" { "smokehello" }
    "formula" { "smokehelloformula" }
    "simpleaction" { "smokehellosimple" }
    "java" { "smokehellojava" }
  }
  if ($ran -notmatch "(?m)^FIELD $field=Hello World\s*$") {
    Fail "$Label run did not write Hello World: $ran"
  }
  Ok "ran hello $Label"
}

function Set-LibraryHelloDxl([string]$Dxl, [string]$Kind) {
  $code = switch ($Kind) {
    "lotusscript" {
@"
<code event='options'><lotusscript>Option Public
Option Declare

Function SmokeHello As String
	SmokeHello = "Hello World"
End Function
</lotusscript></code>
"@
    }
    "java" {
@"
<code event='library'><javaproject class='' codepath='c:\Program Files\HCL\Notes\Data'><java name='SmokeLibJava.java'>public class SmokeLibJava {
  public static String text() {
    return "Hello World";
  }
}</java></javaproject></code>
"@
    }
    default { $null }
  }
  if (-not $code) { return $null }
  $dxl = [regex]::Replace($Dxl, "(?s)<code\b.*?</code>\s*", "")
  $patched = [regex]::Replace($dxl, "<item\b", ($code + "<item"), 1)
  if ($patched -ne $dxl) { return $patched }
  $close = [regex]'(?s)</scriptlibrary>\s*$'
  if (-not $close.IsMatch($dxl)) { return $null }
  return $close.Replace($dxl, ($code + "</scriptlibrary>" + [Environment]::NewLine), 1)
}

function Assert-LibraryHello([string]$Url, [string]$Label, [string]$Kind) {
  $code = Invoke-Design GET $Url -OutFile $out
  Assert-Code "GET $Label for hello" $code @("200")
  $dxl = $null
  try { $dxl = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
  if (-not $dxl) { Fail "$Label hello: response missing dxl" }
  $stamped = Set-LibraryHelloDxl $dxl $Kind
  if (-not $stamped) { Fail "$Label hello: could not write library code" }
  $helloFile = Join-Path $env:TEMP "smoke-hello.dxl"
  [IO.File]::WriteAllText($helloFile, $stamped)
  $code = Invoke-Design PUT $Url -BodyFile $helloFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT hello $Label" $code @("200")
  $code = Invoke-Design GET $Url -OutFile $out
  Assert-Code "GET hello $Label" $code @("200")
  $back = $null
  try { $back = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
  $needle = if ($Kind -eq "lotusscript") { "Function SmokeHello" } else { "class SmokeLibJava" }
  if ($back -notlike "*$needle*" -or $back -notmatch 'Hello World') {
    Fail "$Label library hello was not returned"
  }
  if ($Kind -eq "java") {
    Compile-JavaHello
    Publish-JavaObjectJar $Url $back (Join-Path $env:TEMP "kyber-smoke-java\library-object.jar")
  }
  Ok "hello $Label"
}

function Assert-DxlContains([string]$Item, [string]$MustKeep) {
  $url = Design-Url $Item
  $code = Invoke-Design GET $url -OutFile $out
  Assert-Code "GET dxl $Item" $code @("200")
  $dxl = $null
  try { $dxl = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
  if (-not $dxl) { Fail "$Item response missing dxl" }
  if ($MustKeep -and $dxl -notmatch [regex]::Escape($MustKeep)) {
    Fail "$Item lost $MustKeep"
  }
  Ok "dxl $Item"
}

function Assert-HelloWorld([string]$Url, [string]$Label, [string]$Kind = "") {
  if ($Label -like "scriptlibraries/*" -and $Kind -in @("lotusscript", "java")) {
    Assert-LibraryHello $Url $Label $Kind
    return
  }
  if ($Label -like "agents/*" -and $Kind -in @("lotusscript", "formula", "simpleaction", "java")) {
    Assert-RunnableAgent $Url $Label $Kind
    return
  }
  $code = Invoke-Design GET $Url -OutFile $out
  Assert-Code "GET $Label for hello" $code @("200")
  $dxl = $null
  try { $dxl = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
  if (-not $dxl) { Fail "$Label hello: response missing dxl" }
  $stamped = Set-HelloWorldDxl $dxl
  if (-not $stamped -or $stamped -notmatch 'Hello World') {
    Fail "$Label hello: could not write Hello World into DXL"
  }
  $helloFile = Join-Path $env:TEMP "smoke-hello.dxl"
  [IO.File]::WriteAllText($helloFile, $stamped)
  $code = Invoke-Design PUT $Url -BodyFile $helloFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT hello $Label" $code @("200")
  $code = Invoke-Design GET $Url -OutFile $out
  Assert-Code "GET hello $Label" $code @("200")
  $back = $null
  try { $back = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
  if ($back -notmatch 'Hello World') { Fail "$Label hello world was not returned" }
  Ok "hello $Label"
}

function Set-RichtextHello([string]$Dxl, [switch]$Field) {
  $inner = if ($Field) {
@"
<pardef id='1'/>
<par def='1'><run><font size='9pt'/>Hello World</run> <field type='text' kind='editable' name='SmokeHello'><code event='defaultvalue'><formula>"Hello World"</formula></code></field></par>
"@
  } else {
@"
<pardef id='1'/>
<par def='1'><run><font size='9pt'/>Hello World</run></par>
"@
  }
  $re = [regex]'(?s)<richtext>.*?</richtext>'
  if (-not $re.IsMatch($Dxl)) { return $null }
  return $re.Replace($Dxl, "<richtext>$inner</richtext>", 1)
}

function Set-ColumnHello([string]$Dxl) {
  $dxl = [regex]::Replace($Dxl, "title='[^']*'", "title='Hello World'", 1)
  $dxl = [regex]::Replace($dxl, "itemname='[^']*'", "itemname='SmokeHello'", 1)
  if ($dxl -notmatch "sort='ascending'") {
    $dxl = [regex]::Replace($dxl, "<column\b", "<column sort='ascending'", 1)
  }
  $value = [regex]"(?s)(<code event='value'><formula>).*?(</formula></code>)"
  if ($value.IsMatch($dxl)) { $dxl = $value.Replace($dxl, '${1}SmokeHello${2}', 1) }
  # Exporter may break the opening tag across lines: <formula\n>
  $selection = [regex]"(?s)(<code event='selection'><formula\s*>).*?(</formula>\s*</code>)"
  if ($selection.IsMatch($dxl)) {
    $dxl = $selection.Replace($dxl, '${1}SELECT Form = "SmokeForm"${2}', 1)
  }
  if ($dxl -match "itemname='SmokeHello'" -and $dxl -match "title='Hello World'") { return $dxl }
  return $null
}

function Set-FileHello([string]$Dxl) {
  $bytes = [Text.Encoding]::UTF8.GetBytes("Hello World")
  $b64 = [Convert]::ToBase64String($bytes)
  $re = [regex]'(?s)<filedata\b[^>]*>.*?</filedata>'
  if (-not $re.IsMatch($Dxl)) { return $null }
  return $re.Replace($Dxl, "<filedata>$b64</filedata>", 1)
}

function Set-ImageWeb([string]$Dxl) {
  return [regex]::Replace($Dxl, "\s+hide='[^']*'", "", 1)
}

function Put-WebHello([string]$ApiPath, [string]$Mode) {
  $url = Design-Url $ApiPath
  $code = Invoke-Design GET $url -OutFile $out
  Assert-Code "GET $ApiPath for web hello" $code @("200")
  $dxl = $null
  try { $dxl = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
  if (-not $dxl) { Fail "$ApiPath web hello: response missing dxl" }
  if ($dxl -match "replicaid='([^']+)'") { $script:replicaId = $Matches[1] }
  $stamped = switch ($Mode) {
    "form" { Set-RichtextHello $dxl -Field }
    "column" { Set-ColumnHello $dxl }
    "file" { Set-FileHello $dxl }
    "image" { Set-ImageWeb $dxl }
    default { Set-RichtextHello $dxl }
  }
  if (-not $stamped -or $stamped -notmatch 'Hello World') {
    Fail "$ApiPath web hello: could not write visible text"
  }
  $file = Join-Path $env:TEMP "smoke-web.dxl"
  [IO.File]::WriteAllText($file, $stamped)
  $code = Invoke-Design PUT $url -BodyFile $file -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT web hello $ApiPath" $code @("200")
}

function Get-DominoResponse([string]$UrlPath) {
  $file = Join-Path $env:TEMP "smoke-domino.bin"
  $hdr = Join-Path $env:TEMP "smoke-domino.hdr"
  $pageUrl = "$dominoHttp$UrlPath"
  $code = & curl.exe -sk -D $hdr -o $file -w "%{http_code}" -u "${user}:${pass}" --max-time 30 $pageUrl
  $bytes = [IO.File]::ReadAllBytes($file)
  $text = [Text.Encoding]::UTF8.GetString($bytes)
  $type = ""
  $line = Select-String -Path $hdr -Pattern '(?i)^Content-Type:' | Select-Object -First 1
  if ($line) { $type = $line.Line.Trim() }
  if ($code -ne "200") { Fail "Domino $UrlPath -> HTTP $code" }
  if ($text -match '<title>\s*Login\s*</title>') { Fail "Domino $UrlPath asked for login" }
  return [pscustomobject]@{ Type = $type; Text = $text; Bytes = $bytes }
}

function Get-DominoPage([string]$UrlPath) { return (Get-DominoResponse $UrlPath).Text }

function Assert-DominoHello([string]$UrlPath) {
  $body = Get-DominoPage $UrlPath
  if ($body -notmatch 'Hello World') { Fail "Domino $UrlPath did not show Hello World" }
  Ok "web $UrlPath"
}

function Assert-DominoShows([string]$UrlPath, [string]$Needle) {
  $body = Get-DominoPage $UrlPath
  if ($body -notlike "*$Needle*") { Fail "Domino $UrlPath did not show $Needle" }
  Ok "web $UrlPath"
}

function Assert-DominoTyped([string]$UrlPath, [string]$TypeNeedle) {
  $page = Get-DominoResponse $UrlPath
  if ($page.Type -notmatch [regex]::Escape($TypeNeedle)) {
    Fail "Domino $UrlPath content-type $($page.Type)"
  }
  if ($page.Text -notmatch 'Hello World') { Fail "Domino $UrlPath did not show Hello World" }
  Ok "web $UrlPath"
}

function Assert-DominoImage([string]$UrlPath) {
  $page = Get-DominoResponse $UrlPath
  $png = $page.Bytes.Length -ge 4 -and $page.Bytes[0] -eq 0x89 -and $page.Bytes[1] -eq 0x50
  $gif = $page.Bytes.Length -ge 3 -and $page.Bytes[0] -eq 0x47 -and $page.Bytes[1] -eq 0x49
  if (-not $png -and -not $gif) { Fail "Domino $UrlPath was not an image ($($page.Type))" }
  Ok "web $UrlPath"
}

function Get-DesignNote([string]$ApiPath) {
  $code = Invoke-Design GET (Design-Url $ApiPath) -OutFile $out
  Assert-Code "GET $ApiPath for web" $code @("200")
  $dxl = (Get-ResponseText | ConvertFrom-Json).dxl
  $unid = [regex]::Match($dxl, "unid='([^']+)'").Groups[1].Value
  $file = [regex]::Match($dxl, '(?s)<item name=''\$FileNames''[^>]*>\s*<text>([^<]*)</text>').Groups[1].Value
  if (-not $unid) { Fail "$ApiPath has no unid" }
  return [pscustomobject]@{ Unid = $unid; FileName = $file }
}

function Invoke-NotesSign([string[]]$Unids) {
  $steps = ($Unids | ForEach-Object {
    "Set doc = db.GetDocumentByUNID(""$_"")`r`nCall doc.Sign()`r`nCall doc.Save(True, False)"
  }) -join "`r`n"
  $body = @"
Option Explicit
Dim session, db, doc
Set session = CreateObject("Notes.NotesSession")
Set db = session.GetDatabase("$DominoServer", "$NsfPath")
If Not db.IsOpen Then
  WScript.Echo "OPEN FAIL"
  WScript.Quit 1
End If
$steps
WScript.Echo "SIGNED"
"@
  $text = Invoke-NotesVbs $body
  if ($text -notmatch 'SIGNED') { Fail "could not sign for Domino URLs: $text" }
}

function Invoke-NotesSignDesign {
  $body = @"
Option Explicit
Dim session, db, nc, doc, id, signed
Set session = CreateObject("Notes.NotesSession")
Set db = session.GetDatabase("$DominoServer", "$NsfPath")
If Not db.IsOpen Then
  WScript.Echo "OPEN FAIL"
  WScript.Quit 1
End If
Set nc = db.CreateNoteCollection(False)
Call nc.SelectAllDesignElements(True)
Call nc.BuildCollection
signed = 0
id = nc.GetFirstNoteId
Do While id <> ""
  Set doc = db.GetDocumentByID(id)
  title = ""
  If doc.HasItem("`$TITLE") Then title = doc.GetFirstItem("`$TITLE").Text
  ' Signing an XPage drops the compiled class the URL needs. Signing a shared
  ' column over and over inflates $Collection past Domino's 32K field limit.
  If title <> "SmokeColumn" And (Len(title) < 4 Or LCase(Right(title, 4)) <> ".xsp") Then
    Call doc.Sign()
    Call doc.Save(True, False)
    signed = signed + 1
  End If
  id = nc.GetNextNoteId(id)
Loop
WScript.Echo "SIGNED " & signed
"@
  $text = Invoke-NotesVbs $body
  if ($text -notmatch 'SIGNED') { Fail "could not sign design for the XPage: $text" }
}

function Get-SmokeViewUnids {
  $body = Get-DominoPage "/$NsfPath/SmokeView?ReadViewEntries"
  if ($body -notmatch 'Hello World') { Fail "SmokeView ReadViewEntries did not show Hello World" }
  return @([regex]::Matches($body, 'unid="([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
}

function Invoke-DominoWebSmoke {
  $restUri = [Uri]$base
  $script:dominoHttp = $env:DOMINO_HTTP_URL
  if (-not $script:dominoHttp) { $script:dominoHttp = "https://$($restUri.Host)" }
  $script:dominoHttp = $script:dominoHttp.TrimEnd("/")

  Put-WebHello "forms/SmokeForm" "form"
  Put-WebHello "pages/SmokePage" "text"
  Put-WebHello "about" "text"
  Put-WebHello "using" "text"
  Put-WebHello "views/SmokeView" "column"
  Put-WebHello "folders/SmokeFolder" "column"
  Put-WebHello "fileresources/SmokeFile.txt" "file"
  Put-WebHello "stylesheets/Smoke.css" "file"
  Put-WebHello "images/SmokeImage.gif" "image"

  $xpage = Get-DesignNote "xpages/SmokeXPage.xsp"
  $props = Get-DesignNote "xspproperties"
  $service = Get-DesignNote "webservices/SmokeWebService"
  # Project files and the web service need the Notes user's signature first.
  # The XPage is signed last: signing other design after it leaves HTTP at 403.
  Invoke-NotesSign @($props.Unid, $service.Unid)
  if (-not $xpage.FileName) { Fail "XPage has no file name" }
  Assert-DominoHello "/$NsfPath/SmokeAgentLs?OpenAgent"
  Assert-DominoShows "/$NsfPath/SmokeAgentFormula?OpenAgent" "Agent done"
  Assert-DominoShows "/$NsfPath/SmokeAgentSimple?OpenAgent" "Agent done"
  Assert-DominoShows "/$NsfPath/SmokeAgentJava?OpenAgent" "Agent done"
  Assert-DominoShows "/$NsfPath/SmokeWebService?WSDL" "<definitions"
  Assert-DominoShows "/$NsfPath/SmokeWebService?OpenWebService" "HELLO"
  Invoke-NotesSign @($xpage.Unid)
  $xpageUrl = "/$NsfPath/$($xpage.FileName)"
  $xpageFile = Join-Path $env:TEMP "smoke-xpage.html"
  # Server-signed XPages project files (plugin, manifest, activator) stay forbidden
  # until every design note is signed by the Notes user.
  $xpageCode = & curl.exe -sk -o $xpageFile -w "%{http_code}" -u "${user}:${pass}" --max-time 30 "$dominoHttp$xpageUrl"
  $xpageText = ""
  if (Test-Path $xpageFile) { $xpageText = Get-Content $xpageFile -Raw }
  $xpageOk = $xpageCode -eq "200" -and $xpageText -match 'cookieEnabled'
  if (-not $xpageOk) {
    Invoke-NotesSignDesign
    $deadline = (Get-Date).AddSeconds(90)
    do {
      Invoke-NotesSign @($xpage.Unid)
      Start-Sleep -Seconds 3
      $xpageCode = & curl.exe -sk -o $xpageFile -w "%{http_code}" -u "${user}:${pass}" --max-time 30 "$dominoHttp$xpageUrl"
      $xpageText = ""
      if (Test-Path $xpageFile) { $xpageText = Get-Content $xpageFile -Raw }
      if ($xpageCode -eq "200" -and $xpageText -match 'cookieEnabled') { $xpageOk = $true; break }
    } while ((Get-Date) -lt $deadline)
  }
  if (-not $xpageOk) { Fail "Domino $xpageUrl -> HTTP $xpageCode" }
  Ok "web $xpageUrl"

  # Blank-app web launch opens $MainFrameset; the DB title lives in a child frame / About.
  Assert-DominoShows "/${NsfPath}?OpenDatabase" "`$AppTitle?OpenPage"
  Assert-DominoShows "/${NsfPath}?OpenDatabase&Login" "`$AppTitle?OpenPage"
  if ($replicaId) {
    Assert-DominoShows ("/__{0}.nsf?OpenDatabase" -f $replicaId) "`$AppTitle?OpenPage"
  }
  Assert-DominoShows "/${NsfPath}?OpenAbout" "Kyber Smoke Test"
  Assert-DominoImage "/$NsfPath/`$icon?OpenIcon"
  Assert-DominoHello "/$NsfPath/SmokeForm?OpenForm"
  Assert-DominoHello "/$NsfPath/SmokeForm?ReadForm"
  Assert-DominoHello "/$NsfPath/SmokePage?OpenPage"
  Assert-DominoHello "/$NsfPath/`$about?OpenAbout"
  Assert-DominoHello "/$NsfPath/`$help?OpenHelp"
  Assert-DominoHello "/$NsfPath/SmokeView?OpenView"
  Assert-DominoHello "/$NsfPath/SmokeView?OpenView&Start=1&Count=1"
  Assert-DominoHello "/$NsfPath/SmokeView?OpenView&CollapseView"
  Assert-DominoHello "/$NsfPath/SmokeView?OpenView&ExpandView"
  Assert-DominoShows "/$NsfPath/SmokeFolder?ReadViewEntries" "viewentries"
  Assert-DominoTyped "/$NsfPath/SmokeFile.txt?OpenFileResource" "text/plain"
  Assert-DominoTyped "/$NsfPath/Smoke.css?OpenFileResource" "text/css"
  Assert-DominoHello "/$NsfPath/Smoke.css?OpenCssResource"
  Assert-DominoImage "/$NsfPath/SmokeImage.gif?OpenImageResource"
  Assert-DominoShows "/$NsfPath/SmokeView?SearchView&Query=Hello" "Search Results"
  $before = Get-SmokeViewUnids

  $createFile = Join-Path $env:TEMP "smoke-create.txt"
  [IO.File]::WriteAllText($createFile, "SmokeHello=Hello+World")
  $createOut = Join-Path $env:TEMP "smoke-create.html"
  $createUrl = "$dominoHttp/$NsfPath/SmokeForm?CreateDocument"
  $createCode = & curl.exe -sk -o $createOut -w "%{http_code}" -u "${user}:${pass}" -X POST `
    -H "Content-Type: application/x-www-form-urlencoded" --data-binary "@$createFile" --max-time 30 $createUrl
  $createBody = Get-Content $createOut -Raw
  if ($createCode -ne "200" -or $createBody -notmatch 'Form processed') {
    Fail "CreateDocument -> HTTP $createCode $createBody"
  }
  Ok "web /$NsfPath/SmokeForm?CreateDocument"

  $after = Get-SmokeViewUnids
  $created = @($after | Where-Object { $before -notcontains $_ })
  if ($created.Count -lt 1) {
    Fail "CreateDocument did not add a SmokeView row (before $($before.Count), after $($after.Count))"
  }
  $unid = $created[-1]
  Assert-DominoShows "/$NsfPath/SmokeView?ReadViewEntries&OutputFormat=JSON" "Hello\u0020World"
  # Open the CreateDocument row by UNID (agents also write Form=SmokeHello docs into the view).
  Assert-DominoHello "/$NsfPath/0/${unid}?OpenDocument"
  Assert-DominoHello "/$NsfPath/0/${unid}?EditDocument"
  Assert-DominoHello "/$NsfPath/SmokeView/`$first?OpenDocument"
  Assert-DominoHello "/$NsfPath/SmokeView/Hello%20World?OpenDocument"
  Assert-DominoShows "/$NsfPath/SmokeFolder?OpenView" "SmokeFolder"
  Assert-DominoShows "/$NsfPath/SmokeNavigator?OpenNavigator" "SmokeNavigator"
  Assert-DominoShows "/$NsfPath/SmokeFrameset?OpenFrameset" "SmokeFrameset"
  $script:webDocUnid = $unid
  Ok "web /$NsfPath/SmokeView/${unid} kept until the delete pass"
}

function Invoke-NamedCrud([string]$Coll, [string]$Name, [string]$Kind = "") {
  $qs = "name=$([uri]::EscapeDataString($Name))"
  if ($Kind) { $qs = "kind=$Kind&$qs" }
  $item = "$Coll/$([uri]::EscapeDataString($Name))"
  $label = if ($Kind) { "$Coll/$Name ($Kind)" } else { "$Coll/$Name" }
  $createUrl = Design-Url $Coll $qs

  $code = Invoke-Design GET (Design-Url $Coll) -OutFile $out
  Assert-Code "GET /$Coll" $code @("200")

  $code = Invoke-Design POST $createUrl -BodyFile $empty -ContentType "text/xml" -OutFile $out
  if ($code -eq "409") {
    Ok "kept existing $label"
  } else {
    Assert-Code "POST $label" $code @("200", "201")
    Ok "created $label"
    $code = Invoke-Design POST $createUrl -BodyFile $empty -ContentType "text/xml" -OutFile $out
    Assert-Code "duplicate $label" $code @("409")
  }

  $code = Invoke-Design GET (Design-Url $item) -OutFile $out
  Assert-Code "GET $label" $code @("200")
  $got = Get-ResponseText | ConvertFrom-Json
  Assert-NoteId "GET $label" $got.noteId
  Assert-Unid "GET $label" $got.unid
  if ($Kind -and [string]$got.kind -ne $Kind) { Fail "$label kind '$($got.kind)'" }
  if ($Coll -eq "xpages") {
    if ($got.fileName -ne "newxpage.xsp") { Fail "XPage fileName '$($got.fileName)'" }
    if ([string]::IsNullOrWhiteSpace([string]$got.dxl)) { Fail "XPage DXL missing" }
    Ok "XPage fileName=$($got.fileName)"
  }
  if ($Coll -eq "customcontrols") {
    if ($got.fileName -ne "newcustomcontrol.xsp") { Fail "custom control fileName '$($got.fileName)'" }
    Ok "custom control fileName=$($got.fileName)"
  }
  $putFile = Save-DxlFromGet "GET $label"
  $code = Invoke-Design PUT (Design-Url $item) -BodyFile $putFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT $label" $code @("200")
  $putBody = Get-ResponseText | ConvertFrom-Json
  Assert-NoteId "PUT $label" $putBody.noteId
  Assert-Unid "PUT $label" $putBody.unid
  if ([string]$putBody.unid -ne [string]$got.unid) {
    Fail "$label put unid '$($putBody.unid)' get '$($got.unid)'"
  }
  $wroteId = [string]$putBody.noteId

  $code = Invoke-Design GET (Design-Url $Coll) -OutFile $out
  Assert-Code "LIST $label" $code @("200")
  $listed = Get-ResponseText | ConvertFrom-Json
  $row = @($listed.elements | Where-Object { $_.name -eq $Name })
  if ($row.Count -lt 1) { Fail "GET /$Coll does not list $Name" }
  Assert-NoteId "LIST $label" $row[0].noteId
  Assert-Unid "LIST $label" $row[0].unid
  if ([string]$row[0].noteId -ne $wroteId) {
    Fail "$label list noteId '$($row[0].noteId)' wrote '$wroteId'"
  }
  if ([string]$row[0].unid -ne [string]$got.unid) {
    Fail "$label list unid '$($row[0].unid)' get '$($got.unid)'"
  }
  if ($Kind -and [string]$row[0].kind -ne $Kind) { Fail "$label list kind '$($row[0].kind)'" }
  if ($Coll -eq "xpages" -and $row[0].fileName -ne "newxpage.xsp") {
    Fail "XPage list fileName '$($row[0].fileName)'"
  }
  if ($Coll -eq "customcontrols" -and $row[0].fileName -ne "newcustomcontrol.xsp") {
    Fail "custom control list fileName '$($row[0].fileName)'"
  }
  Ok "listed $label"
  Assert-HelloWorld (Design-Url $item) $label $Kind
  Ok "left $label"
}

function Remove-NamedThenRestore([string]$Coll, [string]$Name, [string]$Kind = "") {
  $qs = "name=$([uri]::EscapeDataString($Name))"
  if ($Kind) { $qs = "kind=$Kind&$qs" }
  $item = "$Coll/$([uri]::EscapeDataString($Name))"
  $label = if ($Kind) { "$Coll/$Name ($Kind)" } else { "$Coll/$Name" }
  $code = Invoke-Design DELETE (Design-Url $item) -OutFile $out
  Assert-Code "DELETE $label" $code @("200")
  $code = Invoke-Design GET (Design-Url $item) -OutFile $out
  Assert-NotFound "GET after DELETE $label" $code (Get-ResponseText)
  $code = Invoke-Design POST (Design-Url $Coll $qs) -BodyFile $empty -ContentType "text/xml" -OutFile $out
  Assert-Code "leave $label" $code @("200", "201")
  Assert-HelloWorld (Design-Url $item) $label $Kind
  Ok "restored $label"
}

function Invoke-SingletonCrud([string]$Path) {
  $url = Design-Url $Path
  $code = Invoke-Design POST $url -BodyFile $empty -ContentType "text/xml" -OutFile $out
  if ($code -eq "409") {
    Ok "kept existing /$Path"
  } else {
    Assert-Code "POST /$Path" $code @("200", "201")
    $code = Invoke-Design POST $url -BodyFile $empty -ContentType "text/xml" -OutFile $out
    Assert-Code "duplicate /$Path" $code @("409")
  }

  $code = Invoke-Design GET $url -OutFile $out
  Assert-Code "GET /$Path" $code @("200")
  Assert-NoteId "GET /$Path" ((Get-ResponseText | ConvertFrom-Json).noteId)
  $putFile = Save-DxlFromGet "GET /$Path"
  $code = Invoke-Design PUT $url -BodyFile $putFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT /$Path" $code @("200")
  Assert-NoteId "PUT /$Path" ((Get-ResponseText | ConvertFrom-Json).noteId)
  Assert-HelloWorld $url $Path
  Ok "left /$Path"
}

function Remove-SingletonThenRestore([string]$Path) {
  $url = Design-Url $Path
  $code = Invoke-Design DELETE $url -OutFile $out
  Assert-Code "DELETE /$Path" $code @("200")
  $code = Invoke-Design GET $url -OutFile $out
  Assert-NotFound "GET after DELETE /$Path" $code (Get-ResponseText)
  $code = Invoke-Design POST $url -BodyFile $empty -ContentType "text/xml" -OutFile $out
  Assert-Code "leave /$Path" $code @("200", "201")
  Assert-HelloWorld $url $Path
  Ok "restored /$Path"
}

function Invoke-UpdateOnly([string]$Path, [string]$TargetNsf = "") {
  $url = if ($TargetNsf) { Design-UrlOn $TargetNsf $Path } else { Design-Url $Path }
  $label = if ($TargetNsf) { "/$Path on $TargetNsf" } else { "/$Path" }
  $code = Invoke-Design GET $url -OutFile $out
  Assert-Code "GET $label" $code @("200")
  $putFile = Save-DxlFromGet "GET $label"
  $code = Invoke-Design PUT $url -BodyFile $putFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT $label" $code @("200")
  Ok "updated $label"
}

function Invoke-ProductSurfaceSmoke {
  # Requires SmokeForm + SmokeImage.gif already created in $NsfPath.
  $code = Invoke-Design GET (Design-Url "forms/SmokeForm") -OutFile $out
  Assert-Code "GET SmokeForm for product surface" $code @("200")
  $form = Get-ResponseText | ConvertFrom-Json
  Assert-Unid "SmokeForm" $form.unid
  Assert-NoteId "SmokeForm" $form.noteId

  $code = Invoke-Design GET (Design-Url "lookup" "unid=$($form.unid)") -OutFile $out
  Assert-Code "GET /lookup?unid=" $code @("200")
  $looked = Get-ResponseText | ConvertFrom-Json
  if ([string]$looked.unid -ne [string]$form.unid) {
    Fail "/lookup unid '$($looked.unid)' expected '$($form.unid)'"
  }
  Ok "GET /lookup by unid"
  $code = Invoke-Design GET (Design-Url "lookup" "noteId=$($form.noteId)") -OutFile $out
  Assert-Code "GET /lookup?noteId=" $code @("200")
  Ok "GET /lookup by noteId"
  $code = Invoke-Design GET (Design-Url "lookup" "unid=00000000000000000000000000000000") -OutFile $out
  Assert-NotFound "GET /lookup missing" $code (Get-ResponseText)

  $code = Invoke-Design GET (Design-Url "search" "q=SmokeForm&match=name") -OutFile $out
  Assert-Code "GET /search" $code @("200")
  $search = Get-ResponseText | ConvertFrom-Json
  $hits = @($search.elements | Where-Object { $_.name -eq "SmokeForm" })
  if ($hits.Count -lt 1) { Fail "/search did not find SmokeForm: $(Get-ResponseText)" }
  Ok "GET /search SmokeForm"

  $code = Invoke-Design GET (Design-Url "fields" "type=form&name=SmokeForm") -OutFile $out
  Assert-Code "GET /fields form" $code @("200")
  $fieldInv = Get-ResponseText | ConvertFrom-Json
  if ($null -eq $fieldInv.fields) { Fail "GET /fields missing fields[]: $(Get-ResponseText)" }
  Ok "GET /fields form count=$($fieldInv.count)"
  $code = Invoke-Design GET (Design-Url "fields" "type=form&name=NoSuchSmokeFieldsForm") -OutFile $out
  Assert-NotFound "GET /fields missing" $code (Get-ResponseText)

  $code = Invoke-Design GET (Design-Url "columns" "type=view&name=SmokeView") -OutFile $out
  Assert-Code "GET /columns view" $code @("200")
  $colInv = Get-ResponseText | ConvertFrom-Json
  if ($null -eq $colInv.columns) { Fail "GET /columns missing columns[]: $(Get-ResponseText)" }
  if ([int]$colInv.count -lt 1) { Fail "GET /columns expected at least one column: $(Get-ResponseText)" }
  Ok "GET /columns view count=$($colInv.count) title=$($colInv.columns[0].title)"
  $code = Invoke-Design GET (Design-Url "columns" "type=view&name=NoSuchSmokeColumnsView") -OutFile $out
  Assert-NotFound "GET /columns missing" $code (Get-ResponseText)

  $code = Invoke-Design GET (Design-Url "summary" "type=form&name=SmokeForm") -OutFile $out
  Assert-Code "GET /summary form" $code @("200")
  $sum = Get-ResponseText | ConvertFrom-Json
  if ([string]$sum.type -ne "form" -or [string]$sum.name -ne "SmokeForm") {
    Fail "GET /summary form unexpected: $(Get-ResponseText)"
  }
  if ($sum.PSObject.Properties.Name -contains "dxl") {
    Fail "GET /summary must omit dxl by default"
  }
  Ok "GET /summary form"
  $code = Invoke-Design GET (Design-Url "summary" "unid=$($form.unid)") -OutFile $out
  Assert-Code "GET /summary by unid" $code @("200")
  Ok "GET /summary by unid"
  $code = Invoke-Design GET (Design-Url "summary" "type=agent&name=SmokeAgentLs") -OutFile $out
  Assert-Code "GET /summary agent" $code @("200")
  $agentSum = Get-ResponseText | ConvertFrom-Json
  if ([string]::IsNullOrWhiteSpace([string]$agentSum.trigger)) {
    Fail "GET /summary agent missing trigger: $(Get-ResponseText)"
  }
  Ok "GET /summary agent trigger=$($agentSum.trigger)"
  $code = Invoke-Design GET (Design-Url "summary" "type=form&name=NoSuchSmokeSummaryForm") -OutFile $out
  Assert-NotFound "GET /summary missing" $code (Get-ResponseText)

  $code = Invoke-Design GET (Design-Url "named/form" "name=SmokeForm") -OutFile $out
  Assert-Code "GET /named/form" $code @("200")
  Ok "GET /named/form?name=SmokeForm"
  $code = Invoke-Design GET (Design-Url "named/form" "name=NoSuchSmokeForm") -OutFile $out
  Assert-NotFound "GET /named/form missing" $code (Get-ResponseText)

  $copyName = "SmokeFormCopy"
  $code = Invoke-Design POST (Design-Url "copy" "type=form&name=SmokeForm&toName=$copyName") -OutFile $out
  if ($code -eq "409") {
    $code = Invoke-Design DELETE (Design-Url "forms/$copyName") -OutFile $out
    Assert-Code "DELETE leftover $copyName" $code @("200")
    $code = Invoke-Design POST (Design-Url "copy" "type=form&name=SmokeForm&toName=$copyName") -OutFile $out
  }
  Assert-Code "POST /copy form" $code @("200")
  Ok "POST /copy SmokeForm -> $copyName"

  $renamed = "SmokeFormRenamed"
  $code = Invoke-Design POST (Design-Url "rename" "type=form&name=$copyName&newName=$renamed") -OutFile $out
  if ($code -eq "409") {
    Invoke-Design DELETE (Design-Url "forms/$renamed") -OutFile $out | Out-Null
    $code = Invoke-Design POST (Design-Url "rename" "type=form&name=$copyName&newName=$renamed") -OutFile $out
  }
  Assert-Code "POST /rename form" $code @("200")
  Ok "POST /rename $copyName -> $renamed"

  $patchFile = Join-Path $env:TEMP "smoke-patch.json"
  [IO.File]::WriteAllText($patchFile, '{"comment":"SmokePatch"}')
  $code = Invoke-Design PATCH (Design-Url "patch" "type=form&name=$renamed") `
    -BodyFile $patchFile -ContentType "application/json" -OutFile $out
  Assert-Code "PATCH /patch" $code @("200")
  $patched = Get-ResponseText | ConvertFrom-Json
  if ([string]$patched.dxl -notmatch "comment='SmokePatch'|comment=`"SmokePatch`"") {
    Fail "PATCH /patch did not set comment: $($patched.dxl.Substring(0, [Math]::Min(200, $patched.dxl.Length)))"
  }
  Ok "PATCH /patch comment"
  $code = Invoke-Design POST (Design-Url "copy" "type=form&name=NoSuchForm&toName=Nope") -OutFile $out
  Assert-NotFound "POST /copy missing source" $code (Get-ResponseText)

  $code = Invoke-Design DELETE (Design-Url "forms/$renamed") -OutFile $out
  Assert-Code "DELETE $renamed" $code @("200")
  Ok "cleaned copy/rename form"

  $code = Invoke-Design GET (Design-Url "content" "type=image&name=SmokeImage.gif") -OutFile $out
  Assert-Code "GET /content image" $code @("200")
  $bin = Get-ResponseText | ConvertFrom-Json
  if ([string]::IsNullOrWhiteSpace([string]$bin.contentBase64)) {
    Fail "GET /content missing contentBase64"
  }
  Ok "GET /content image"
  $contentFile = Join-Path $env:TEMP "smoke-content.json"
  $contentBody = @{ contentBase64 = [string]$bin.contentBase64 } | ConvertTo-Json -Compress
  [IO.File]::WriteAllText($contentFile, $contentBody)
  $code = Invoke-Design PUT (Design-Url "content" "type=image&name=SmokeImage.gif") `
    -BodyFile $contentFile -ContentType "application/json" -OutFile $out
  Assert-Code "PUT /content image" $code @("200")
  Ok "PUT /content image"
  $code = Invoke-Design GET (Design-Url "content" "type=image&name=NoSuchImage.gif") -OutFile $out
  Assert-NotFound "GET /content missing" $code (Get-ResponseText)

  $code = Invoke-Design POST (Design-Url "compile") -OutFile $out
  Assert-Code "POST /compile" $code @("200")
  $compiled = Get-ResponseText | ConvertFrom-Json
  if (-not $compiled.PSObject.Properties.Name.Contains("success")) {
    Fail "POST /compile missing success: $(Get-ResponseText)"
  }
  Ok "POST /compile success=$($compiled.success)"

  $code = Invoke-Design GET (Design-Url "templates" "type=form") -OutFile $out
  # /templates uses reader ACL and may omit nsfPath; Design-Url still sends kybertest.
  Assert-Code "GET /templates?type=form" $code @("200")
  Ok "GET /templates?type=form"

  $code = Invoke-Design POST (Design-Url "defaults/preview" "type=form&name=SmokePreview") -OutFile $out
  Assert-Code "POST /defaults/preview" $code @("200")
  $preview = Get-ResponseText | ConvertFrom-Json
  if ([string]::IsNullOrWhiteSpace([string]$preview.dxl)) {
    Fail "POST /defaults/preview missing dxl"
  }
  Ok "POST /defaults/preview"

  $code = Invoke-Design GET (Design-Url "designnotes") -OutFile $out
  Assert-Code "GET /designnotes" $code @("200")
  Ok "GET /designnotes"
  $code = Invoke-Design GET (Design-Url "designnotes/NoSuchDesignNote") -OutFile $out
  Assert-NotFound "GET /designnotes missing" $code (Get-ResponseText)

  $code = Invoke-Design GET (Design-Url "agents") -OutFile $out
  Assert-Code "GET /agents for trigger" $code @("200")
  $agents = Get-ResponseText | ConvertFrom-Json
  $ls = @($agents.elements | Where-Object { $_.name -eq "SmokeAgentLs" })
  if ($ls.Count -lt 1) { Fail "agent list missing SmokeAgentLs" }
  if ($ls[0].PSObject.Properties.Name -contains "trigger") {
    Ok "GET /agents trigger=$($ls[0].trigger)"
  } else {
    Ok "GET /agents (no trigger field on this agent)"
  }

  $catNsf = "kyber-category.nsf"
  $code = Invoke-Design POST (Design-UrlOn $catNsf "database" "dbTitle=Kyber%20Category") -OutFile $out
  if ($code -in @("200", "201", "409")) {
    $code = Invoke-Design PUT (Design-UrlOn $catNsf "database/category" "enabled=false") -OutFile $out
    Assert-Code "PUT /database/category enabled=false" $code @("200")
    $code = Invoke-Design PUT (Design-UrlOn $catNsf "database/category" "enabled=true") -OutFile $out
    Assert-Code "PUT /database/category enabled=true" $code @("200")
    Ok "PUT /database/category round-trip"
    $code = Invoke-Design DELETE (Design-UrlOn $catNsf "database") -OutFile $out
    if ($code -ne "200") { Fail "DELETE $catNsf -> HTTP $code $(Get-ResponseText)" }
    Ok "cleaned $catNsf"
  } elseif ($code -eq "403") {
    Ok "PUT /database/category skipped; Create databases & templates required"
  } else {
    Fail "POST /database $catNsf -> HTTP $code $(Get-ResponseText)"
  }

  $tplNsf = "kyber-fromtpl.nsf"
  $code = Invoke-Design POST (
    Design-UrlOn $tplNsf "database/fromTemplate" (
      "templateNsfPath=$([uri]::EscapeDataString($NsfPath))&dbTitle=Kyber%20FromTemplate"
    )
  ) -OutFile $out
  if ($code -in @("200", "201")) {
    $created = Get-ResponseText | ConvertFrom-Json
    if (-not $created.created) { Fail "fromTemplate did not set created" }
    Assert-ReplicaId "fromTemplate" $created.replicaId
    Ok "POST /database/fromTemplate created $tplNsf"
    $code = Invoke-Design POST (
      Design-UrlOn $tplNsf "database/fromTemplate" (
        "templateNsfPath=$([uri]::EscapeDataString($NsfPath))"
      )
    ) -OutFile $out
    if ($code -ne "409") { Fail "fromTemplate exists -> HTTP $code $(Get-ResponseText)" }
    Ok "POST /database/fromTemplate 409 when exists"
    $code = Invoke-Design POST (Design-UrlOn $tplNsf "database/inherit") -OutFile $out
    if ($code -eq "200") {
      Ok "POST /database/inherit"
    } elseif ($code -in @("403", "500")) {
      # Domino may refuse refresh without a design template link; must not be coerced to 400.
      Ok "POST /database/inherit -> $code (not coerced to 400)"
    } elseif ($code -eq "400" -and (Get-ResponseText) -match "(?i)required|invalid") {
      Fail "POST /database/inherit unexpected client 400: $(Get-ResponseText)"
    } else {
      Fail "POST /database/inherit -> HTTP $code $(Get-ResponseText)"
    }
    $code = Invoke-Design DELETE (Design-UrlOn $tplNsf "database") -OutFile $out
    if ($code -ne "200") { Fail "DELETE $tplNsf -> HTTP $code $(Get-ResponseText)" }
    Ok "cleaned $tplNsf"
  } elseif ($code -eq "409") {
    Ok "POST /database/fromTemplate left $tplNsf in place"
    Invoke-Design DELETE (Design-UrlOn $tplNsf "database") -OutFile $out | Out-Null
  } elseif ($code -eq "403") {
    Ok "POST /database/fromTemplate needs Create databases & templates"
  } else {
    Fail "POST /database/fromTemplate -> HTTP $code $(Get-ResponseText)"
  }
}

if (-not $NsfPath) {
  $NsfPath = "kybertest.nsf"
}

if (-not $EnvFile) {
  foreach ($f in @("$PSScriptRoot\..\.env", ".\.env")) {
    if (Test-Path $f) { $EnvFile = $f; break }
  }
}
$base = $env:DOMINO_REST_API_DATA_URL
if (-not $base) { $base = Get-EnvFileValue $EnvFile "DOMINO_REST_API_DATA_URL" }
if (-not $base) { $base = Get-EnvFileValue $EnvFile "DOMINO_REST_URL" }
if (-not $base) { Fail "DOMINO_REST_API_DATA_URL missing" }
$DominoServer = $env:DOMINO_SERVER
if (-not $DominoServer) { $DominoServer = Get-EnvFileValue $EnvFile "DOMINO_SERVER" }
if (-not $DominoServer) { Fail "DOMINO_SERVER missing" }
$base = $base.TrimEnd("/")

$user = $env:REST_ADMIN_USER; if (-not $user) { $user = Get-EnvFileValue $EnvFile "REST_ADMIN_USER" }
$pass = $env:REST_ADMIN_PASSWORD; if (-not $pass) { $pass = Get-EnvFileValue $EnvFile "REST_ADMIN_PASSWORD" }
if (-not $user -or -not $pass) { Fail "REST admin credentials missing" }

@{ username = $user; password = $pass } | ConvertTo-Json -Compress |
  Set-Content -Encoding ascii "$env:TEMP\keep-auth-smoke.json"
$authSsl = if ($SkipCertificateCheck) { "-sk" } else { "-s" }
$token = ((curl.exe $authSsl -X POST "$base/api/v1/auth" -H "Content-Type: application/json" `
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
  return "$base/api/kyber-v1/${ApiPath}?$q"
}

if ($JavaHelloOnly) {
  Assert-LibraryHello (Design-Url "scriptlibraries/SmokeLibJava") "scriptlibraries/SmokeLibJava" "java"
  Assert-RunnableAgent (Design-Url "agents/SmokeAgentJava") "agents/SmokeAgentJava" "java"
  Ok "java hello names match Designer"
  exit 0
}

# --- Ensure NSF ---
$smokeTitle = "Kyber Smoke Test"
$bulkFile = Join-Path $env:TEMP "smoke-bulk.json"
[IO.File]::WriteAllText($bulkFile, (@{ nsfPath = $NsfPath; elements = @() } | ConvertTo-Json -Compress -Depth 5))
# Empty PUT /import on an existing NSF has panicked nrestapi (LockMemHandle) on this host —
# only create when the NSF is missing.
$code = Invoke-Design GET (Design-Url "forms") -OutFile $out
$bulkResp = $null
if ($code -eq "200") {
  Ok "nsf ready (existing)"
  $bulkResp = @{ created = $false; nsfPath = $NsfPath }
} else {
  $code = Invoke-Design PUT (Design-Url "import" "dbTitle=$([uri]::EscapeDataString($smokeTitle))") `
    -BodyFile $bulkFile -ContentType "application/json" -OutFile $out
  if ($code -notin @("200", "201")) {
    Fail "PUT /import (ensure NSF) -> HTTP $code $(Get-Content $out -Raw)"
  }
  $bulkResp = Get-Content $out -Raw | ConvertFrom-Json
}
$validateFile = Join-Path $env:TEMP "smoke-validate.json"
[IO.File]::WriteAllText($validateFile, '{"elements":[{"type":"form","name":"Broken","dxl":"<form>"}]}')
$code = Invoke-Design POST (Design-Url "validate") -BodyFile $validateFile `
  -ContentType "application/json" -OutFile $out
Assert-Code "POST /validate broken" $code @("200")
$validated = Get-ResponseText | ConvertFrom-Json
if ($validated.valid) { Fail "POST /validate accepted broken DXL" }
if (@($validated.errors).Count -lt 1) { Fail "POST /validate returned no errors" }
foreach ($extra in @("log", "code")) {
  if ($validated.PSObject.Properties.Name -contains $extra) {
    Fail "POST /validate returned $extra"
  }
}
$broken = @($validated.errors)[0]
if ([string]$broken.type -ne "form" -or [string]::IsNullOrWhiteSpace([string]$broken.message)) {
  Fail "POST /validate error is missing type or message"
}
if ($null -eq $broken.index -or [string]$broken.code -ne "invalid_dxl") {
  Fail "POST /validate error missing index/code=invalid_dxl (index=$($broken.index) code=$($broken.code))"
}
[IO.File]::WriteAllText($validateFile, '{"elements":[{"type":"form","dxl":"<form name=''SmokeValidate'' xmlns=''http://www.lotus.com/dxl''/>"}]}')
$code = Invoke-Design POST (Design-Url "validate") -BodyFile $validateFile `
  -ContentType "application/json" -OutFile $out
Assert-Code "POST /validate form" $code @("200")
$validated = Get-ResponseText | ConvertFrom-Json
if (-not $validated.valid) { Fail "POST /validate rejected a form: $(Get-ResponseText)" }
foreach ($extra in @("log", "code")) {
  if ($validated.PSObject.Properties.Name -contains $extra) {
    Fail "POST /validate returned $extra"
  }
}
[IO.File]::WriteAllText($validateFile, "{}")
$code = Invoke-Design POST (Design-Url "validate") -BodyFile $validateFile `
  -ContentType "application/json" -OutFile $out
Assert-Code "POST /validate without elements" $code @("400")
Ok "POST /validate"
foreach ($gone in @("defaults", "defaults/form", "defaults/agent")) {
  $code = Invoke-Design GET (Design-Url $gone) -OutFile $out
  if ($code -ne "404") { Fail "GET /$gone should be gone, got HTTP $code" }
}
Ok "GET /defaults is gone"
if ($bulkResp.created -and $bulkResp.dbTitle -ne $smokeTitle) {
  Fail "expected dbTitle=$smokeTitle on create, got $($bulkResp.dbTitle)"
}
Ok "nsf ready created=$($bulkResp.created) dbTitle=$($bulkResp.dbTitle)"
$retitle = "Kyber Smoke Retitle"
$code = Invoke-Design PUT (Design-Url "database" "dbTitle=$([uri]::EscapeDataString($retitle))") -OutFile $out
Assert-Code "PUT /database dbTitle" $code @("200")
$titled = Get-ResponseText | ConvertFrom-Json
if (-not $titled.success -or [string]$titled.dbTitle -ne $retitle) {
  Fail "PUT /database dbTitle expected success+$retitle, got $(Get-ResponseText)"
}
$code = Invoke-Design PUT (Design-Url "database" "dbTitle=$([uri]::EscapeDataString($smokeTitle))") -OutFile $out
Assert-Code "PUT /database restore dbTitle" $code @("200")
Ok "PUT /database dbTitle"
$code = Invoke-Design GET (Design-Url "acl") -OutFile $out
Assert-Code "GET /acl structured" $code @("200")
$aclJson = Get-ResponseText | ConvertFrom-Json
if (-not $aclJson.entries -or @($aclJson.entries).Count -lt 1) {
  Fail "GET /acl missing entries"
}
if ($null -eq $aclJson.roles) {
  Fail "GET /acl missing roles"
}
$defaultEntry = @($aclJson.entries) | Where-Object { [string]$_.name -eq "-Default-" } | Select-Object -First 1
if (-not $defaultEntry -or [string]::IsNullOrWhiteSpace([string]$defaultEntry.level)) {
  Fail "GET /acl entries missing -Default- with level"
}
Ok "GET /acl entries/roles"

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

# --- kyber.nsf is the template database: properties, ACL, and the product icon ---
$defaultNsf = "kyber.nsf"
function Design-UrlOn([string]$TargetNsf, [string]$ApiPath, [string]$ExtraQuery = "") {
  $q = "nsfPath=$([uri]::EscapeDataString($TargetNsf))"
  if ($ExtraQuery) { $q = "$q&$ExtraQuery" }
  return "$base/api/kyber-v1/${ApiPath}?$q"
}

foreach ($singleton in @("applicationproperties", "acl")) {
  $code = Invoke-Design GET (Design-UrlOn $defaultNsf $singleton) -OutFile $out
  if ($code -ne "200") { Fail "GET /$singleton on $defaultNsf -> HTTP $code $(Get-Content $out -Raw)" }
  Ok "GET /$singleton ($defaultNsf)"
}
$code = Invoke-Design GET (Design-UrlOn $defaultNsf "dbicon") -OutFile $out
if ($code -ne "200") { Fail "GET /dbicon on $defaultNsf -> HTTP $code $(Get-Content $out -Raw)" }
if ((Get-Content $out -Raw) -notmatch [regex]::Escape("kyber-icon.png")) {
  Fail "GET /dbicon on $defaultNsf is missing the Kyber icon"
}
Ok "GET /dbicon ($defaultNsf)"
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

$script:missingFormPath = $missingPath

$probeName = ".settings/kyber-smoke.prefs"
$probeItem = "xpagesresources/" + [uri]::EscapeDataString($probeName)
$code = Invoke-Design POST (Design-Url "xpagesresources" ("name=" + [uri]::EscapeDataString($probeName))) `
  -BodyFile $empty -ContentType "text/xml" -OutFile $out
Assert-Code "POST xpagesresource probe" $code @("200", "201")
$code = Invoke-Design GET (Design-Url $probeItem) -OutFile $out
Assert-Code "GET xpagesresource probe" $code @("200")
$probe = Get-ResponseText | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace([string]$probe.dxl)) { Fail "xpagesresource probe dxl missing" }
$code = Invoke-Design DELETE (Design-Url $probeItem) -OutFile $out
Assert-Code "DELETE xpagesresource probe" $code @("200")
Ok "xpagesresource create/get/delete"

function Test-AclEntry([string]$Dxl, [string]$Name) {
  $quoted = [regex]::Escape($Name)
  return $Dxl -match "(?i)<aclentry\b[^>]*\bname='$quoted'" -or $Dxl -match "(?i)<aclentry\b[^>]*\bname=""$quoted"""
}

function Remove-AclEntry([string]$Dxl, [string]$Name) {
  $quoted = [regex]::Escape($Name)
  foreach ($pattern in @(
      "(?is)<aclentry\b[^>]*\bname='$quoted'[^>]*/>",
      "(?is)<aclentry\b[^>]*\bname=""$quoted""[^>]*/>",
      "(?is)<aclentry\b[^>]*\bname='$quoted'[^>]*>.*?</aclentry>",
      "(?is)<aclentry\b[^>]*\bname=""$quoted""[^>]*>.*?</aclentry>"
    )) {
    $stripped = [regex]::Replace($Dxl, $pattern, "", 1)
    if ($stripped -ne $Dxl) { return $stripped }
  }
  return $Dxl
}

function Assert-AclKeepsProtectedEntries {
  $url = Design-Url "acl"
  foreach ($entry in @("-Default-", "LocalDomainServers")) {
    $code = Invoke-Design GET $url -OutFile $out
    Assert-Code "GET /acl before dropping $entry" $code @("200")
    $original = (Get-ResponseText | ConvertFrom-Json).dxl
    if (-not (Test-AclEntry $original $entry)) { Fail "ACL is missing $entry before the drop check" }
    $stripped = Remove-AclEntry $original $entry
    if ($stripped -eq $original) { Fail "could not build an ACL without $entry" }
    $dropFile = Join-Path $env:TEMP "smoke-acl-drop.dxl"
    [IO.File]::WriteAllText($dropFile, $stripped)
    $code = Invoke-Design PUT $url -BodyFile $dropFile -ContentType "text/xml" -OutFile $out
    if ($code -ne "400") {
      [IO.File]::WriteAllText($dropFile, $original)
      Invoke-Design PUT $url -BodyFile $dropFile -ContentType "text/xml" -OutFile $out | Out-Null
      Fail "PUT /acl without $entry -> HTTP $code"
    }
    if ((Get-ResponseText) -notmatch "cannot be removed") {
      Fail "PUT /acl without $entry was not the protected-entry refusal"
    }
    $code = Invoke-Design GET $url -OutFile $out
    Assert-Code "GET /acl after dropping $entry" $code @("200")
    $after = (Get-ResponseText | ConvertFrom-Json).dxl
    foreach ($kept in @("-Default-", "LocalDomainServers")) {
      if (-not (Test-AclEntry $after $kept)) { Fail "ACL lost $kept after a refused drop" }
    }
    Ok "PUT /acl without $entry refused"
  }
}

foreach ($path in @("applicationproperties", "acl")) {
  Invoke-UpdateOnly $path
}
Assert-AclKeepsProtectedEntries

# A NSF created by PUT /import receives the embedded icon, including kyber.nsf.
# An existing smoke NSF keeps whatever icon it already has.
if ($bulkResp.created) {
  $iconRows = @($bulkResp.results | Where-Object { $_.type -eq "dbicon" -and $_.success })
  if ($iconRows.Count -lt 1) {
    Fail "new NSF did not receive the embedded database icon"
  }
}
$code = Invoke-Design GET (Design-Url "dbicon") -OutFile $out
if ($code -ne "200") { Fail "GET /dbicon on $NsfPath -> HTTP $code $(Get-ResponseText)" }
$iconDxl = Get-ResponseText
if ($iconDxl -notmatch [regex]::Escape("kyber-icon.png")) {
  Fail "GET /dbicon on $NsfPath is missing the Kyber icon"
}
Ok "GET /dbicon ($NsfPath)"
Invoke-UpdateOnly "dbicon"

foreach ($path in @("about", "using", "databasescript", "xspproperties")) {
  Invoke-SingletonCrud $path
}

$named = @(
  @{ coll = "forms"; name = "SmokeForm" },
  @{ coll = "views"; name = "SmokeView" },
  @{ coll = "folders"; name = "SmokeFolder" },
  @{ coll = "framesets"; name = "SmokeFrameset" },
  @{ coll = "pages"; name = "SmokePage" },
  @{ coll = "subforms"; name = "SmokeSubform" },
  @{ coll = "sharedfields"; name = "SmokeField" },
  @{ coll = "sharedcolumns"; name = "SmokeColumn" },
  @{ coll = "outlines"; name = "SmokeOutline" },
  @{ coll = "navigators"; name = "SmokeNavigator" },
  @{ coll = "images"; name = "SmokeImage.gif" },
  @{ coll = "fileresources"; name = "SmokeFile.txt" },
  @{ coll = "stylesheets"; name = "Smoke.css" },
  @{ coll = "themes"; name = "Smoke.theme" },
  @{ coll = "sharedactions"; name = '$ACTIONS' },
  @{ coll = "webservices"; name = "SmokeWebService" },
  @{ coll = "webserviceconsumers"; name = "SmokeWebServiceConsumer" },
  @{ coll = "dataconnections"; name = "SmokeConnection" },
  @{ coll = "jars"; name = "Smoke.jar" },
  @{ coll = "webcontent"; name = "SmokeWeb.txt" },
  @{ coll = "applets"; name = "SmokeApplet" },
  @{ coll = "customcontrols"; name = "SmokeControl.xsp" },
  @{ coll = "xpages"; name = "SmokeXPage.xsp" },
  @{ coll = "wiringproperties"; name = "SmokeWiring" },
  @{ coll = "compositeapplications"; name = "SmokeCompositeApp" },
  @{ coll = "compositecomponents"; name = "SmokeComposite.component" },
  @{ coll = "scriptlibraries"; name = "SmokeLibLs"; kind = "lotusscript" },
  @{ coll = "scriptlibraries"; name = "SmokeLibJs"; kind = "javascript" },
  @{ coll = "scriptlibraries"; name = "SmokeLibSsjs"; kind = "ssjs" },
  @{ coll = "scriptlibraries"; name = "SmokeLibJava"; kind = "java" },
  @{ coll = "agents"; name = "SmokeAgentLs"; kind = "lotusscript" },
  @{ coll = "agents"; name = "SmokeAgentFormula"; kind = "formula" },
  @{ coll = "agents"; name = "SmokeAgentSimple"; kind = "simpleaction" },
  @{ coll = "agents"; name = "SmokeAgentJava"; kind = "java" },
  @{ coll = "agents"; name = "SmokeAgentImported"; kind = "importedjava" },
  @{ coll = "javasources"; name = "se/infoware/kyber/SmokeClass.java"; kind = "class" },
  @{ coll = "javasources"; name = "se/infoware/kyber/SmokeInterface.java"; kind = "interface" },
  @{ coll = "javasources"; name = "se/infoware/kyber/SmokeEnum.java"; kind = "enum" }
)
foreach ($el in $named) {
  Invoke-NamedCrud $el.coll $el.name $(if ($el.kind) { $el.kind } else { "" })
}

$code = Invoke-Design GET (Design-Url "xpagesresources") -OutFile $out
Assert-Code "GET /xpagesresources" $code @("200")
if ((Get-ResponseText) -notmatch [regex]::Escape("WEB-INF/faces-config.xml")) {
  Fail "xpagesresources missing faces-config: $(Get-ResponseText)"
}
if ((Get-ResponseText) -notmatch [regex]::Escape("Local/plugin/Activator.java")) {
  Fail "xpagesresources missing Local/plugin/Activator.java after Java create: $(Get-ResponseText)"
}
$faces = "xpagesresources/" + [uri]::EscapeDataString("WEB-INF/faces-config.xml")
$code = Invoke-Design GET (Design-Url $faces) -OutFile $out
Assert-Code "GET faces-config" $code @("200")
$face = Get-ResponseText | ConvertFrom-Json
if ([string]$face.fileName -notmatch "faces-config") { Fail "faces-config fileName '$($face.fileName)'" }
if ([string]$face.dxl -notmatch "faces-config") { Fail "faces-config dxl missing" }
Ok "faces-config fileName=$($face.fileName)"

function Get-XpagesFilePayload([string]$Dxl) {
  if ($Dxl -notmatch '(?s)<rawitemdata[^>]*>\s*([A-Za-z0-9+/=\s]+)\s*</rawitemdata>') {
    return $null
  }
  $raw = [Convert]::FromBase64String(($Matches[1] -replace '\s', ''))
  if ($raw.Length -le 42) { return '' }
  return [Text.Encoding]::UTF8.GetString($raw, 42, $raw.Length - 42)
}

function Assert-XpagesProjectHealth {
  $code = Invoke-Design GET (Design-Url "xpagesresources") -OutFile $out
  Assert-Code "GET /xpagesresources (health)" $code @("200")
  $list = Get-ResponseText | ConvertFrom-Json
  $names = @($list.elements | ForEach-Object {
    if ($_.name) { [string]$_.name } elseif ($_.fileName) { [string]$_.fileName } else { "" }
  })
  $needEnsure = $false
  foreach ($title in @(".classpath", "plugin.xml")) {
    if (@($names | Where-Object { $_ -eq $title }).Count -lt 1) { $needEnsure = $true }
  }
  # Existing smoked NSFs may predate the full skeleton; "kept existing" Java agents skip ensure.
  if ($needEnsure) {
    $probe = "SmokeXPagesEnsure"
    Invoke-Design DELETE (Design-Url "agents/$probe") -OutFile $out | Out-Null
    $code = Invoke-Design POST (Design-Url "agents" "kind=java&name=$([uri]::EscapeDataString($probe))") `
      -BodyFile $empty -ContentType "text/xml" -OutFile $out
    Assert-Code "POST agents/$probe (ensure skeleton)" $code @("200", "201")
    Invoke-Design DELETE (Design-Url "agents/$probe") -OutFile $out | Out-Null
    $code = Invoke-Design GET (Design-Url "xpagesresources") -OutFile $out
    Assert-Code "GET /xpagesresources after ensure" $code @("200")
    $list = Get-ResponseText | ConvertFrom-Json
    $names = @($list.elements | ForEach-Object {
      if ($_.name) { [string]$_.name } elseif ($_.fileName) { [string]$_.fileName } else { "" }
    })
  }
  foreach ($title in @(".classpath", "plugin.xml")) {
    $n = @($names | Where-Object { $_ -eq $title }).Count
    if ($n -ne 1) { Fail "expected exactly one xpagesresource '$title', found $n (names=$($names -join ','))" }
  }

  $activatorPath = "xpagesresources/" + [uri]::EscapeDataString("Local/plugin/Activator.java")
  $code = Invoke-Design GET (Design-Url $activatorPath) -OutFile $out
  Assert-Code "GET Local/plugin/Activator.java" $code @("200")
  $activator = Get-ResponseText | ConvertFrom-Json
  $activatorDxl = [string]$activator.dxl
  if ($activatorDxl -notmatch [regex]::Escape("Local/plugin/Activator.java")) {
    Fail "Activator.java DXL missing Local/plugin/Activator.java title"
  }
  if ($activatorDxl -match '(?is)\$FlagsExt') {
    Fail "Local/plugin/Activator.java must not have `$FlagsExt (WebContent marker)"
  }
  $activatorSrc = Get-XpagesFilePayload $activatorDxl
  if ([string]::IsNullOrWhiteSpace($activatorSrc) -or $activatorSrc -notmatch 'kyber_nsf') {
    Fail "Activator.java source missing kyber_nsf PLUGIN_ID"
  }

  $cpPath = "xpagesresources/" + [uri]::EscapeDataString(".classpath")
  $code = Invoke-Design GET (Design-Url $cpPath) -OutFile $out
  Assert-Code "GET .classpath" $code @("200")
  $cp = Get-ResponseText | ConvertFrom-Json
  $cpText = Get-XpagesFilePayload ([string]$cp.dxl)
  if ([string]::IsNullOrWhiteSpace($cpText)) { Fail ".classpath `$FileData missing or empty" }
  if ($cpText -match 'path="Local') {
    Fail ".classpath must not list Designer's temp Local/ source folder: $cpText"
  }

  $pluginPath = "xpagesresources/" + [uri]::EscapeDataString("plugin.xml")
  $code = Invoke-Design GET (Design-Url $pluginPath) -OutFile $out
  Assert-Code "GET plugin.xml" $code @("200")
  $pluginObj = Get-ResponseText | ConvertFrom-Json
  $pluginText = Get-XpagesFilePayload ([string]$pluginObj.dxl)
  if ($pluginText -match 'class="plugin.Activator"') {
    Fail "plugin.xml must omit class=plugin.Activator (forces missing Local/): $pluginText"
  }
  if ($pluginText -notmatch 'id="kyber_nsf"') {
    Fail "plugin.xml missing id=kyber_nsf: $pluginText"
  }
  Ok "XPages project health (Activator, unique plugin/.classpath, no Local/)"
}

function Set-XpagesFilePayloadInDxl([string]$Dxl, [string]$NewText) {
  if ($Dxl -notmatch '(?s)(<rawitemdata[^>]*>\s*)([A-Za-z0-9+/=\s]+)(\s*</rawitemdata>)') {
    return $null
  }
  $raw = [Convert]::FromBase64String(($Matches[2] -replace '\s', ''))
  if ($raw.Length -le 42) { return $null }
  $body = [Text.Encoding]::UTF8.GetBytes($NewText)
  $oldSize = $raw.Length - 42
  $next = New-Object byte[] (42 + $body.Length)
  [Array]::Copy($raw, 0, $next, 0, 42)
  [Array]::Copy($body, 0, $next, 42, $body.Length)
  $writeLe = {
    param([byte[]]$b, [int]$o, [int]$v)
    [Array]::Copy([BitConverter]::GetBytes([int]$v), 0, $b, $o, 4)
  }
  $readLe = {
    param([byte[]]$b, [int]$o)
    return [int]($b[$o] -bor ($b[$o + 1] -shl 8) -bor ($b[$o + 2] -shl 16) -bor ($b[$o + 3] -shl 24))
  }
  & $writeLe $next 8 $body.Length
  $off26 = & $readLe $raw 26
  if ($off26 -eq ($oldSize + 18)) { & $writeLe $next 26 ($body.Length + 18) }
  elseif ($off26 -eq $oldSize) { & $writeLe $next 26 $body.Length }
  if ((& $readLe $raw 28) -eq $oldSize) { & $writeLe $next 28 $body.Length }
  if ((& $readLe $raw 30) -eq $oldSize) { & $writeLe $next 30 $body.Length }
  $b64 = [Convert]::ToBase64String($next)
  $wrapped = ($b64 -replace '(.{64})', "`$1`n").Trim()
  $outDxl = [regex]::Replace($Dxl, "(?s)(<item name=['\`"]\`$FileSize['\`"][^>]*>\s*<number>)\d+(</number>)", "`${1}$($body.Length)`${2}")
  return [regex]::Replace($outDxl, "(?s)(<rawitemdata[^>]*>)\s*[A-Za-z0-9+/=\s]+(\s*</rawitemdata>)", "`${1}`n$wrapped`n`${2}")
}

function Assert-XpagesProjectHeal {
  $cpPath = "xpagesresources/" + [uri]::EscapeDataString(".classpath")
  $code = Invoke-Design GET (Design-Url $cpPath) -OutFile $out
  Assert-Code "GET .classpath before pollute" $code @("200")
  $cp = Get-ResponseText | ConvertFrom-Json
  $clean = Get-XpagesFilePayload ([string]$cp.dxl)
  if ([string]::IsNullOrWhiteSpace($clean)) { Fail ".classpath payload empty before pollute" }
  # Same-length only: restore the Local/ source line into the space-padded hole left by the skeleton.
  $localLine = "  <classpathentry kind=`"src`" path=`"Local/`"/>`n"
  $localLineCr = "  <classpathentry kind=`"src`" path=`"Local/`"/>`r`n"
  $hole = ' ' * $localLine.Length
  $holeCr = ' ' * $localLineCr.Length
  $pollutedText = $null
  if ($clean.Contains($hole)) {
    $pollutedText = $clean.Replace($hole, $localLine)
  } elseif ($clean.Contains($holeCr)) {
    $pollutedText = $clean.Replace($holeCr, $localLineCr)
  } else {
    Fail "cannot same-length pollute .classpath (no Local/ hole); payload=$clean"
  }
  $badDxl = Set-XpagesFilePayloadInDxl ([string]$cp.dxl) $pollutedText
  if (-not $badDxl) { Fail "could not rewrite .classpath payload for heal probe" }
  # Set-XpagesFilePayloadInDxl may still resize CDFILE; prefer byte-identical header when lengths match.
  if ($pollutedText.Length -eq $clean.Length -and [string]$cp.dxl -match '(?s)(<rawitemdata[^>]*>\s*)([A-Za-z0-9+/=\s]+)(\s*</rawitemdata>)') {
    $raw = [Convert]::FromBase64String(($Matches[2] -replace '\s', ''))
    $nb = [Text.Encoding]::UTF8.GetBytes($pollutedText)
    if ($nb.Length -eq ($raw.Length - 42)) {
      $next = New-Object byte[] $raw.Length
      [Array]::Copy($raw, 0, $next, 0, 42)
      [Array]::Copy($nb, 0, $next, 42, $nb.Length)
      $b64 = [Convert]::ToBase64String($next)
      $wrapped = ($b64 -replace '(.{64})', "`$1`n").Trim()
      $badDxl = [regex]::Replace([string]$cp.dxl, "(?s)(<rawitemdata[^>]*>)\s*[A-Za-z0-9+/=\s]+(\s*</rawitemdata>)", "`${1}`n$wrapped`n`${2}")
    }
  }
  $badFile = Join-Path $env:TEMP "smoke-classpath-pollute.dxl"
  [IO.File]::WriteAllText($badFile, $badDxl)
  $code = Invoke-Design PUT (Design-Url $cpPath) -BodyFile $badFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT polluted .classpath" $code @("200")
  $code = Invoke-Design GET (Design-Url $cpPath) -OutFile $out
  Assert-Code "GET polluted .classpath" $code @("200")
  $afterPut = Get-XpagesFilePayload ([string]((Get-ResponseText | ConvertFrom-Json).dxl))
  if ($afterPut -notmatch 'path="Local') {
    Fail "pollute probe did not stick Local/ into .classpath: $afterPut"
  }

  # Next Java create runs XPagesProject.ensure → healClasspath.
  $healName = "SmokeXPagesHeal"
  $code = Invoke-Design DELETE (Design-Url "agents/$healName") -OutFile $out
  # 200 or 404 both fine
  $code = Invoke-Design POST (Design-Url "agents" "kind=java&name=$([uri]::EscapeDataString($healName))") `
    -BodyFile $empty -ContentType "text/xml" -OutFile $out
  Assert-Code "POST agents/$healName (heal trigger)" $code @("200", "201")
  $created = Get-ResponseText | ConvertFrom-Json
  $repaired = @()
  if ($created.xpagesProject -and $created.xpagesProject.repaired) {
    $repaired = @($created.xpagesProject.repaired)
  }
  if ($repaired -notcontains ".classpath") {
    Fail "Java create did not report xpagesProject.repaired=.classpath (install Kyber with ensure heal?): $($created | ConvertTo-Json -Compress -Depth 6)"
  }

  $code = Invoke-Design GET (Design-Url $cpPath) -OutFile $out
  Assert-Code "GET .classpath after heal" $code @("200")
  $healed = Get-XpagesFilePayload ([string]((Get-ResponseText | ConvertFrom-Json).dxl))
  if ($healed -match 'path="Local') {
    Fail ".classpath still lists Local/ after heal: $healed"
  }

  $code = Invoke-Design DELETE (Design-Url "agents/$healName") -OutFile $out
  Assert-Code "DELETE agents/$healName" $code @("200")
  Ok "XPages .classpath Local/ pollution healed on Java create"
}

Assert-XpagesProjectHealth
if ($Extended) {
  Assert-XpagesProjectHeal
} else {
  Ok "skipped XPages heal probe (pass -Extended for nightly)"
}

Assert-DxlContains "agents/SmokeAgentLs" "Use `"SmokeLibLs`""
Assert-DxlContains "webcontent/SmokeWeb.txt" ""
Assert-DxlContains "fileresources/SmokeFile.txt" ""
Assert-DxlContains "stylesheets/Smoke.css" ""
$code = Invoke-Design GET (Design-Url "forms") -OutFile $out
Assert-Code "GET /forms aliases" $code @("200")
$formRow = @((Get-ResponseText | ConvertFrom-Json).elements | Where-Object { $_.name -eq "SmokeForm" })
if ($formRow.Count -lt 1) { Fail "form list missing SmokeForm" }

Invoke-ProductSurfaceSmoke

Invoke-DominoWebSmoke

# Export the smoked NSF and import it into a second database. Repo domino/ stays the template tree.
$exportDir = Join-Path $env:TEMP "kyber-smoke-export"
if (Test-Path -LiteralPath $exportDir) { Remove-Item -LiteralPath $exportDir -Recurse -Force }
$importNsf = "kybersmoke-import.nsf"
$roundTrip = @{
  RestUrl = $base
  Token = $token
}
if ($SkipCertificateCheck) { $roundTrip.SkipCertificateCheck = $true }
try {
  # Domino's DXL exporter sometimes returns named shells without payload for stylesheet /
  # sharedcolumn; AllowSkipped keeps the round-trip covering the types smoke asserts below.
  & (Join-Path $PSScriptRoot "export-to-repo.ps1") @roundTrip -NsfPath $NsfPath -OutDir $exportDir -AllowSkipped
} catch {
  Fail "export-to-repo: $($_.Exception.Message)"
}
$manifestFile = @(Get-ChildItem -LiteralPath $exportDir -Recurse -Filter manifest.json -File)
if ($manifestFile.Count -ne 1) { Fail "export did not write one manifest.json under $exportDir" }
$manifestPath = $manifestFile[0].FullName
$exportDir = $manifestFile[0].DirectoryName
$manifest = Get-Content -Raw $manifestPath | ConvertFrom-Json
foreach ($need in @("form", "webcontent", "fileresource", "xpage", "acl", "image")) {
  if (@($manifest.elements | Where-Object { $_.type -eq $need }).Count -lt 1) {
    Fail "export missing type $need"
  }
}
$webExport = @($manifest.elements | Where-Object { $_.type -eq "webcontent" -and $_.name -eq "SmokeWeb.txt" })
if ($webExport.Count -lt 1) { Fail "export missing WebContent SmokeWeb.txt" }
if ($webExport[0].path -notlike "WebContent/*") {
  Fail "SmokeWeb.txt exported to $($webExport[0].path)"
}
$formExport = @($manifest.elements | Where-Object { $_.type -eq "form" -and $_.name -eq "SmokeForm" })
if ($formExport.Count -lt 1 -or $formExport[0].path -notlike "Forms/*") {
  Fail "SmokeForm was not exported under Forms/"
}
Ok "exported $($manifest.elementCount) elements"

$dirName = Split-Path $exportDir -Leaf
if ($dirName -notmatch '-([0-9A-F]{16})$') { Fail "export folder '$dirName' has no replica id" }
$folderReplica = $Matches[1]
$code = Invoke-Design GET (Design-Url "export") -OutFile $out
if ($code -ne "200") { Fail "GET /export -> HTTP $code" }
$export = Get-Content $out -Raw | ConvertFrom-Json
if ([string]$export.nsfPath -ne $NsfPath) { Fail "export nsfPath '$($export.nsfPath)'" }
Assert-ReplicaId "export" $export.replicaId
if ([string]$export.replicaId -ne $folderReplica) { Fail "export replicaId does not match the folder" }
$script:exportReplicaId = [string]$export.replicaId
foreach ($agentName in @(
  "SmokeAgentFormula", "SmokeAgentImported", "SmokeAgentJava", "SmokeAgentLs", "SmokeAgentSimple"
)) {
  $agentFile = @(Get-ChildItem -LiteralPath (Join-Path $exportDir "Code\Agents") -Filter "$agentName-*.dxl")
  if ($agentFile.Count -ne 1) { Fail "export file count for $agentName is $($agentFile.Count)" }
  if ($agentFile[0].BaseName -notmatch '-([0-9a-f]{1,8})$') {
    Fail "export file '$($agentFile[0].Name)' has no note id"
  }
  $suffix = $Matches[1]
  $code = Invoke-Design GET (Design-Url "agents/$([uri]::EscapeDataString($agentName))") -OutFile $out
  Assert-Code "GET $agentName noteId" $code @("200")
  $agent = Get-Content $out -Raw | ConvertFrom-Json
  if ([string]$agent.noteId -ne $suffix) { Fail "$agentName noteId '$($agent.noteId)' file $suffix" }
  $expEl = @($export.elements | Where-Object { $_.type -eq "agent" -and $_.name -eq $agentName })
  if ($expEl.Count -ne 1 -or [string]$expEl[0].noteId -ne $suffix) {
    Fail "export element $agentName noteId '$($expEl[0].noteId)' file $suffix"
  }
}
Ok "export replicaId and agent noteId match the files"

$importLines = $null
try {
  $importLines = & (Join-Path $PSScriptRoot "import-from-repo.ps1") @roundTrip -NsfPath $importNsf -InDir $exportDir
} catch {
  Fail "import-from-repo: $($_.Exception.Message)"
}
$importText = if ($importLines -is [string]) { $importLines } else { ($importLines | Out-String) }
$importJson = $importText | ConvertFrom-Json
if ([string]$importJson.nsfPath -ne $importNsf) { Fail "import nsfPath '$($importJson.nsfPath)'" }
Assert-ReplicaId "import $importNsf" $importJson.replicaId
if ([string]$importJson.replicaId -eq $script:exportReplicaId) {
  Fail "import replicaId matches the source NSF"
}
foreach ($check in @(
  @{ path = "forms/SmokeForm"; label = "form" },
  @{ path = "webcontent/SmokeWeb.txt"; label = "webcontent" }
)) {
  $code = Invoke-Design GET (Design-UrlOn $importNsf $check.path) -OutFile $out
  if ($code -ne "200") {
    Fail "GET /$($check.label) on $importNsf -> HTTP $code $(Get-ResponseText)"
  }
}
$iconCode = Invoke-Design GET (Design-UrlOn $importNsf "dbicon") -OutFile $out
if ($iconCode -ne "200") { Fail "GET /dbicon on $importNsf -> HTTP $iconCode $(Get-ResponseText)" }
if ((Get-ResponseText) -notmatch [regex]::Escape("kyber-icon.png")) {
  Fail "imported $importNsf is missing the Kyber icon"
}
$formCode = Invoke-Design GET (Design-UrlOn $importNsf "forms/SmokeForm") -OutFile $out
if ($formCode -ne "200") { Fail "GET imported form -> HTTP $formCode $(Get-ResponseText)" }
$importDxl = $null
try { $importDxl = (Get-ResponseText | ConvertFrom-Json).dxl } catch { }
if (-not $importDxl -or $importDxl -notmatch "replicaid='([^']+)'") {
  Fail "imported form is missing its own replica id"
}
$importReplica = $Matches[1]
if ($script:replicaId -and $importReplica -eq $script:replicaId) {
  Fail "imported $importNsf kept source replica id $importReplica"
}
Ok "imported $importNsf replica id differs"
Ok "imported $importNsf"

if ($SkipDelete) {
  Ok "delete pass skipped"
} else {
  if ($script:webDocUnid) {
    Get-DominoPage "/$NsfPath/SmokeView/$($script:webDocUnid)?DeleteDocument" | Out-Null
    $left = Get-SmokeViewUnids
    if ($left -contains $script:webDocUnid) {
      Fail "DeleteDocument left $($script:webDocUnid) in SmokeView"
    }
    Ok "web /$NsfPath/SmokeView/$($script:webDocUnid)?DeleteDocument"
  }
  foreach ($el in $named) {
    Remove-NamedThenRestore $el.coll $el.name $(if ($el.kind) { $el.kind } else { "" })
  }
  foreach ($path in @("about", "using", "databasescript", "xspproperties")) {
    Remove-SingletonThenRestore $path
  }
  $code = Invoke-Design DELETE (Design-Url $script:missingFormPath) -OutFile $out
  Assert-NotFound "DELETE missing form" $code (Get-ResponseText)
  Ok "delete pass restored the design"
}

$code = Invoke-Design GET "$base/api/kyber-v1/databases?nsfPath=$([uri]::EscapeDataString($NsfPath))" -OutFile $out
if ($code -ne "200") { Fail "GET /databases -> HTTP $code $(Get-ResponseText)" }
$listed = Get-Content $out -Raw | ConvertFrom-Json
if ($listed.fileType -ne "database") { Fail "GET /databases fileType=$($listed.fileType)" }
$listedPaths = @($listed.databases | ForEach-Object { $_.nsfPath })
if ($listedPaths -notcontains $NsfPath) { Fail "GET /databases did not include $NsfPath" }
$listedRow = @($listed.databases | Where-Object { $_.nsfPath -eq $NsfPath })
Assert-ReplicaId "GET /databases $NsfPath" $listedRow[0].replicaId
if ([string]$listedRow[0].replicaId -ne $script:exportReplicaId) {
  Fail "GET /databases replicaId does not match the export"
}
Ok "GET /databases fileType=database more=$($listed.more) count=$($listedPaths.Count)"
$code = Invoke-Design GET "$base/api/kyber-v1/databases?nsfPath=$([uri]::EscapeDataString($NsfPath))&fileType=template" -OutFile $out
if ($code -ne "200") { Fail "GET /databases?fileType=template -> HTTP $code $(Get-ResponseText)" }
$templates = Get-Content $out -Raw | ConvertFrom-Json
if ($templates.fileType -ne "template") { Fail "GET /databases template fileType=$($templates.fileType)" }
Ok "GET /databases fileType=template more=$($templates.more)"
$code = Invoke-Design GET "$base/api/kyber-v1/databases?nsfPath=$([uri]::EscapeDataString($NsfPath))&fileType=1245" -OutFile $out
if ($code -ne "200") { Fail "GET /databases?fileType=1245 -> HTTP $code $(Get-ResponseText)" }
$replicas = Get-Content $out -Raw | ConvertFrom-Json
if ($replicas.fileType -ne "replica_candidate") { Fail "GET /databases 1245 fileType=$($replicas.fileType)" }
Ok "GET /databases fileType=replica_candidate more=$($replicas.more)"
$code = Invoke-Design GET "$base/api/kyber-v1/databases?nsfPath=$([uri]::EscapeDataString($NsfPath))&fileType=1246" -OutFile $out
if ($code -ne "200") { Fail "GET /databases?fileType=1246 -> HTTP $code $(Get-ResponseText)" }
$candidates = Get-Content $out -Raw | ConvertFrom-Json
if ($candidates.fileType -ne "template_candidate") { Fail "GET /databases 1246 fileType=$($candidates.fileType)" }
Ok "GET /databases fileType=template_candidate more=$($candidates.more)"
$code = Invoke-Design GET "$base/api/kyber-v1/databases?nsfPath=$([uri]::EscapeDataString($NsfPath))&fileType=box" -OutFile $out
if ($code -ne "400") { Fail "GET /databases?fileType=box -> HTTP $code $(Get-ResponseText)" }
Ok "GET /databases rejects fileType=box"

$code = Invoke-Design POST (Design-UrlOn "KeepConfig.nsf" "database") -OutFile $out
if ($code -ne "400" -or (Get-ResponseText) -notmatch "KeepConfig") {
  Fail "POST /database KeepConfig -> HTTP $code $(Get-ResponseText)"
}
Ok "POST /database refuses KeepConfig.nsf"

$tempNsf = "kyber-create.nsf"
$code = Invoke-Design POST (Design-UrlOn $tempNsf "database" "dbTitle=Kyber%20Create") -OutFile $out
if ($code -in @("200", "201")) {
  $createdDb = Get-Content $out -Raw | ConvertFrom-Json
  if (-not $createdDb.created) { Fail "POST /database did not create $tempNsf" }
  if ([string]$createdDb.nsfPath -ne $tempNsf) { Fail "POST /database nsfPath '$($createdDb.nsfPath)'" }
  Assert-ReplicaId "POST /database" $createdDb.replicaId
  Ok "POST /database created $tempNsf"
  $code = Invoke-Design POST (Design-UrlOn $tempNsf "database" "dbTitle=Kyber%20Create") -OutFile $out
  if ($code -ne "409") { Fail "POST /database (exists) -> HTTP $code $(Get-ResponseText)" }
  Ok "POST /database 409 when the file exists"
} elseif ($code -eq "409") {
  Ok "POST /database left $tempNsf in place"
} elseif ($code -eq "403") {
  Ok "POST /database needs Create databases & templates"
} else {
  Fail "POST /database -> HTTP $code $(Get-ResponseText)"
}

$code = Invoke-Design DELETE (Design-UrlOn "KeepConfig.nsf" "database") -OutFile $out
if ($code -ne "400" -or (Get-ResponseText) -notmatch "KeepConfig") {
  Fail "DELETE /database KeepConfig -> HTTP $code $(Get-ResponseText)"
}
Ok "DELETE /database refuses KeepConfig.nsf"

$deleteNsf = "kyber-delete.nsf"
$code = Invoke-Design POST (Design-UrlOn $deleteNsf "database" "dbTitle=Kyber%20Delete") -OutFile $out
if ($code -in @("200", "201", "409")) {
  $code = Invoke-Design DELETE (Design-UrlOn $deleteNsf "database") -OutFile $out
  if ($code -ne "200") { Fail "DELETE /database $deleteNsf -> HTTP $code $(Get-ResponseText)" }
  $deletedDb = Get-Content $out -Raw | ConvertFrom-Json
  if (-not $deletedDb.success) { Fail "DELETE /database did not report success" }
  if ([string]$deletedDb.nsfPath -ne $deleteNsf) { Fail "DELETE /database nsfPath '$($deletedDb.nsfPath)'" }
  Assert-ReplicaId "DELETE /database" $deletedDb.replicaId
  $code = Invoke-Design POST (Design-UrlOn $deleteNsf "database" "dbTitle=Kyber%20Delete") -OutFile $out
  if ($code -notin @("200", "201")) {
    Fail "DELETE /database left $deleteNsf in place -> HTTP $code $(Get-ResponseText)"
  }
  $code = Invoke-Design DELETE (Design-UrlOn $deleteNsf "database") -OutFile $out
  if ($code -ne "200") { Fail "DELETE /database second $deleteNsf -> HTTP $code $(Get-ResponseText)" }
  Ok "DELETE /database removed $deleteNsf"
} elseif ($code -eq "403") {
  Ok "DELETE /database skipped; POST /database needs Create databases & templates"
} else {
  Fail "POST /database $deleteNsf -> HTTP $code $(Get-ResponseText)"
}

Write-Host "SMOKE PASSED ($NsfPath kept, $($named.Count) named elements plus singletons)" -ForegroundColor Green
exit 0
