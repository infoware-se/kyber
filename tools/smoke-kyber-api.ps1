<#
.SYNOPSIS
  Live smoke of kyber-v1 against DRAPI: defaults, CRUD status codes, XPages ensure.

.DESCRIPTION
  Auth via DOMINO_REST_* / REST_ADMIN_* or a gitignored .env. Notes COM uses DOMINO_SERVER.
  Uses kybertest.nsf (PUT /designdxl?dbTitle=... creates it when missing), then asserts:

  - GET /defaults for a few types
  - missing form → not found
  - every named CRUD collection: list, empty-body create or keep, 409, get, put, list contains name
  - Hello World written into each created element and read back
  - LotusScript, formula and simple-action agents are run; each writes Hello World
  - The LotusScript agent calls SmokeLibLs
  - Domino URL commands open the database, form, page, about, using, view, folder,
    navigator, frameset, icon, image, file, stylesheet, agents, web service and XPage
  - ?CreateDocument, ?OpenDocument, ?EditDocument and ?DeleteDocument on Hello World
  - kinded agents, script libraries and Java sources, one element per kind
  - singletons about, using, databasescript, xspproperties: create or keep, 409, get, put, Hello World
  - applicationproperties, dbicon and acl: get + put of the same DXL
  - a NSF created by this run has the embedded Kyber database icon
  - POST /xpagesproject once, then a second call that creates nothing
  - export-to-repo.ps1 writes kybertest.nsf to a temp folder, then import-from-repo.ps1
    restores that tree into kybersmoke-import.nsf. The repo domino/ templates are not touched.
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
#>
param(
  [string]$NsfPath = "",
  [string]$EnvFile = "",
  [switch]$SkipCertificateCheck,
  [switch]$SkipDelete
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
  $attr = [regex]"comment='[^']*'"
  if ($attr.IsMatch($Dxl)) { return $attr.Replace($Dxl, "comment='Hello World'", 1) }
  $attr2 = [regex]'comment="[^"]*"'
  if ($attr2.IsMatch($Dxl)) { return $attr2.Replace($Dxl, 'comment="Hello World"', 1) }
  $item = '<item name=''$Comment''><text>Hello World</text></item>'
  $existing = [regex]'(?s)<item name=''\$Comment''[^>]*>\s*<text>.*?</text>\s*</item>'
  if ($existing.IsMatch($Dxl)) { return $existing.Replace($Dxl, $item, 1) }
  $existing2 = [regex]'(?s)<item name="\$Comment"[^>]*>\s*<text>.*?</text>\s*</item>'
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
  $docType = if ($Kind -eq "lotusscript") { "runonce" } else { "all" }
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
<code event='action'><javaproject class='JavaAgent.class' codepath='c:\Program Files\HCL\Notes\Data'><java name='JavaAgent.java'>import lotus.domino.*;

public class JavaAgent extends AgentBase {

    public void NotesMain() {

      try {
          Session session = getSession();
          AgentContext agentContext = session.getAgentContext();
          Document doc = agentContext.getDocumentContext();
          if (doc == null) {
              doc = agentContext.getCurrentDatabase().createDocument();
              doc.replaceItemValue("Form", "SmokeHello");
          }
          doc.replaceItemValue("SmokeHelloJava", "Hello World");
          doc.save(true, false);
          System.out.println("Hello World");

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
    "java" { 'System.out.println("Hello World")' }
  }
  if ($back -notlike "*$needle*") { Fail "$Label hello source was not returned" }
  $agentName = ($Label -split "/")[-1] -replace '\s+\(.*$', ''
  $ran = Invoke-HelloAgent $agentName -ResetDocs:((-not $SkipDelete) -and ($Kind -eq "lotusscript"))
  if ($ran -notmatch '(?m)^RAN\s*$') { Fail "$Label did not run: $ran" }
  $field = switch ($Kind) {
    "lotusscript" { "smokehello" }
    "formula" { "smokehelloformula" }
    "simpleaction" { "smokehellosimple" }
    default { "" }
  }
  if ($field) {
    if ($ran -notmatch "(?m)^FIELD $field=Hello World\s*$") {
      Fail "$Label run did not write Hello World: $ran"
    }
  } else {
    $code = Invoke-Design GET $Url -OutFile $out
    Assert-Code "GET runlog $Label" $code @("200")
    $logDxl = (Get-ResponseText | ConvertFrom-Json).dxl
    if ($logDxl -notmatch 'Ran Java Agent Class') { Fail "$Label Java agent did not run" }
    if ($logDxl -match 'ERROR:') { Fail "$Label Java agent run failed" }
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
<code event='library'><javaproject class='' codepath='C:\Domino\Data'><java name='SmokeHello.java'>public class SmokeHello {
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
  $needle = if ($Kind -eq "lotusscript") { "Function SmokeHello" } else { "class SmokeHello" }
  if ($back -notlike "*$needle*" -or $back -notmatch 'Hello World') {
    Fail "$Label library hello was not returned"
  }
  Ok "hello $Label"
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
  $selection = [regex]"(?s)(<code event='selection'><formula>).*?(</formula></code>)"
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
  Call doc.Sign()
  Call doc.Save(True, False)
  signed = signed + 1
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
  Invoke-NotesSign @($xpage.Unid, $props.Unid, $service.Unid)
  if (-not $xpage.FileName) { Fail "XPage has no file name" }
  Assert-DominoHello "/$NsfPath/SmokeAgentLs?OpenAgent"
  Assert-DominoShows "/$NsfPath/SmokeAgentFormula?OpenAgent" "Agent done"
  Assert-DominoShows "/$NsfPath/SmokeAgentSimple?OpenAgent" "Agent done"
  Assert-DominoShows "/$NsfPath/SmokeAgentJava?OpenAgent" "Agent done"
  Assert-DominoShows "/$NsfPath/SmokeWebService?WSDL" "<definitions"
  Assert-DominoShows "/$NsfPath/SmokeWebService?OpenWebService" "HELLO"
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
    $deadline = (Get-Date).AddSeconds(20)
    do {
      $xpageCode = & curl.exe -sk -o $xpageFile -w "%{http_code}" -u "${user}:${pass}" --max-time 30 "$dominoHttp$xpageUrl"
      $xpageText = ""
      if (Test-Path $xpageFile) { $xpageText = Get-Content $xpageFile -Raw }
      if ($xpageCode -eq "200" -and $xpageText -match 'cookieEnabled') { $xpageOk = $true; break }
      Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
  }
  if (-not $xpageOk) { Fail "Domino $xpageUrl -> HTTP $xpageCode" }
  Ok "web $xpageUrl"

  Assert-DominoShows "/${NsfPath}?OpenDatabase" "Kyber Smoke Test"
  Assert-DominoShows "/${NsfPath}?OpenDatabase&Login" "Kyber Smoke Test"
  if ($replicaId) {
    Assert-DominoShows ("/__{0}.nsf?OpenDatabase" -f $replicaId) "Kyber Smoke Test"
  }
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
  Assert-DominoHello "/$NsfPath/SmokeView/`$first?OpenDocument"
  Assert-DominoHello "/$NsfPath/SmokeView/Hello%20World?OpenDocument"
  Assert-DominoHello "/$NsfPath/0/${unid}?OpenDocument"
  Assert-DominoHello "/$NsfPath/0/${unid}?EditDocument"
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
  $putFile = Save-DxlFromGet "GET $label"
  $code = Invoke-Design PUT (Design-Url $item) -BodyFile $putFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT $label" $code @("200")

  $code = Invoke-Design GET (Design-Url $Coll) -OutFile $out
  Assert-Code "LIST $label" $code @("200")
  if ((Get-ResponseText) -notmatch [regex]::Escape($Name)) {
    Fail "GET /$Coll does not list $Name"
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
  $putFile = Save-DxlFromGet "GET /$Path"
  $code = Invoke-Design PUT $url -BodyFile $putFile -ContentType "text/xml" -OutFile $out
  Assert-Code "PUT /$Path" $code @("200")
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

# --- Defaults ---
foreach ($q in @(
  "form",
  "agent?kind=lotusscript",
  "scriptlibrary?kind=java",
  "javasource?kind=class",
  "xpage"
)) {
  $code = Invoke-Design GET "$base/api/kyber-v1/defaults/$q" -OutFile $out
  if ($code -ne "200") { Fail "GET /defaults/$q -> HTTP $code" }
  $j = Get-Content $out -Raw | ConvertFrom-Json
  if (-not $j.dxl) { Fail "GET /defaults/$q missing dxl" }
  Ok "defaults/$q source=$($j.source) name=$($j.name)"
}

# --- Ensure NSF ---
$smokeTitle = "Kyber Smoke Test"
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

# --- XPages project ensure ---
$code = Invoke-Design POST (Design-Url "xpagesproject") -OutFile $out
if ($code -notin @("200", "201")) {
  Fail "POST /xpagesproject -> HTTP $code $(Get-Content $out -Raw)"
}
$xp = Get-Content $out -Raw | ConvertFrom-Json
if (-not $xp.ensured) { Fail "xpagesproject response missing ensured=true: $(Get-Content $out -Raw)" }
$createdCount = @($xp.created).Count
if ($bulkResp.created -and $createdCount -lt 1) {
  Fail "xpagesproject on fresh NSF should create skeleton files, created=$createdCount"
}
Ok "xpagesproject created=$createdCount skipped=$(@($xp.skipped).Count)"

$code = Invoke-Design POST (Design-Url "xpagesproject") -OutFile $out
if ($code -notin @("200", "201")) { Fail "POST /xpagesproject (2nd) -> HTTP $code" }
$xp2 = Get-Content $out -Raw | ConvertFrom-Json
$created2 = @($xp2.created).Count
if ($created2 -ne 0) { Fail "2nd xpagesproject should create 0, got $created2" }
Ok "xpagesproject idempotent (skipped=$(@($xp2.skipped).Count))"

foreach ($path in @("applicationproperties", "acl")) {
  Invoke-UpdateOnly $path
}
# A NSF created by PUT /designdxl receives the embedded icon, including kyber.nsf.
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
  & (Join-Path $PSScriptRoot "export-to-repo.ps1") @roundTrip -NsfPath $NsfPath -OutDir $exportDir
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

try {
  & (Join-Path $PSScriptRoot "import-from-repo.ps1") @roundTrip -NsfPath $importNsf -InDir $exportDir
} catch {
  Fail "import-from-repo: $($_.Exception.Message)"
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

Write-Host "SMOKE PASSED ($NsfPath kept, $($named.Count) named elements plus singletons)" -ForegroundColor Green
exit 0
