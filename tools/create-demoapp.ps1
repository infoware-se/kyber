# Build test/demoapp.nsf via keep-design bulk designdxl (creates NSF if missing).
$ErrorActionPreference = "Stop"

Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class KeepDemoTrust : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint sp, X509Certificate c, WebRequest r, int p) { return true; }
}
"@ -ErrorAction SilentlyContinue
[System.Net.ServicePointManager]::CertificatePolicy = New-Object KeepDemoTrust
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

function Import-EnvFile([string]$path) {
  if (-not (Test-Path $path)) { return }
  Get-Content $path | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $k, $v = $_.Split('=', 2)
    Set-Item -Path "env:$($k.Trim())" -Value $v.Trim().Trim('"').Trim("'")
  }
}

$RepoRoot = Split-Path $PSScriptRoot -Parent
foreach ($f in @(
  (Join-Path $RepoRoot ".env"),
  (Join-Path $RepoRoot ".env.drapi"),
  "C:\Git\install-domino-restapi\.env"
)) { Import-EnvFile $f }

$RestUrl = $env:DOMINO_REST_API_DATA_URL
if (-not $RestUrl) { $RestUrl = $env:DOMINO_REST_URL }
$user = $env:REST_ADMIN_USER; if (-not $user) { $user = $env:DOMINO_REST_USER }
$pass = $env:REST_ADMIN_PASSWORD; if (-not $pass) { $pass = $env:DOMINO_REST_PASSWORD }

$auth = Invoke-RestMethod -Method POST -Uri "$RestUrl/api/v1/auth" -ContentType "application/json" `
  -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($auth.bearer)"; "Content-Type" = "application/json" }
$nsf = "test/demoapp.nsf"
$base = "$RestUrl/api/design-v1"

function El([string]$type, [string]$dxl) {
  return @{ type = $type; dxl = $dxl }
}

$pageHome = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE page SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<page name='Home' xmlns='http://www.lotus.com/dxl' version='14.5' publicaccess='false' designerversion='8.5.3'>
<body><richtext>
<pardef id='1'/><par def='1'><run><font size='18pt' style='bold'/>Demo App</run></par>
<pardef id='2'/><par def='2'><run><font size='10pt'/>Welcome. Use the outline to open documents, tasks, and views.</run></par>
</richtext></body>
<item name='$$ScriptName' summary='false' sign='true'><text>Home</text></item>
</page>
'@

$pageAbout = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE page SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<page name='About' xmlns='http://www.lotus.com/dxl' version='14.5' publicaccess='false' designerversion='8.5.3'>
<body><richtext>
<pardef id='1'/><par def='1'><run><font size='14pt' style='bold'/>About this demo</run></par>
<pardef id='2'/><par def='2'><run><font size='10pt'/>Built with KEEP keep-design (Domino REST API).</run></par>
</richtext></body>
<item name='$$ScriptName' summary='false' sign='true'><text>About</text></item>
</page>
'@

$pageNav = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE page SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<page name='Nav' xmlns='http://www.lotus.com/dxl' version='14.5' publicaccess='false' designerversion='8.5.3'>
<body><richtext>
<pardef id='1'/><par def='1'/>
<pardef id='2'/><par def='2'><embeddedoutline name='MainOutline' outline='MainOutline' showtwistie='true' widthtype='fitwindow' heighttype='fitwindow' targetframe='Content'><title><layout labelalign='topleft'/></title><toplevel mouseovercolor='blue'><font size='9pt'/></toplevel><sublevel mouseovercolor='blue'><font size='9pt'/></sublevel></embeddedoutline></par>
</richtext></body>
<item name='$$ScriptName' summary='false' sign='true'><text>Nav</text></item>
</page>
'@

$formDoc = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE form SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<form name='Document' xmlns='http://www.lotus.com/dxl' version='14.5' publicaccess='false' designerversion='8.5.3' renderpassthrough='true'>
<body><richtext>
<pardef id='1'/><par def='1'><run><font size='12pt' style='bold'/>Document</run></par>
<pardef id='2'/><par def='2'><run><font size='10pt'/>Subject: </run><field type='text' kind='editable' name='Subject' width='40em'/></par>
<pardef id='3'/><par def='3'><run><font size='10pt'/>Category: </run><field type='text' kind='editable' name='Category' width='20em'/></par>
<pardef id='4'/><par def='4'><run><font size='10pt'/>Body</run></par>
<pardef id='5'/><par def='5'><field type='richtext' kind='editable' name='Body'/></par>
</richtext></body>
<item name='$$ScriptName' summary='false' sign='true'><text>Document</text></item>
</form>
'@

$formTask = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE form SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<form name='Task' xmlns='http://www.lotus.com/dxl' version='14.5' publicaccess='false' designerversion='8.5.3' renderpassthrough='true'>
<body><richtext>
<pardef id='1'/><par def='1'><run><font size='12pt' style='bold'/>Task</run></par>
<pardef id='2'/><par def='2'><run><font size='10pt'/>Title: </run><field type='text' kind='editable' name='Title' width='40em'/></par>
<pardef id='3'/><par def='3'><run><font size='10pt'/>Status: </run><field type='text' kind='editable' name='Status' width='15em'/></par>
<pardef id='4'/><par def='4'><run><font size='10pt'/>Due: </run><field type='datetime' kind='editable' name='DueDate'/></par>
</richtext></body>
<item name='$$ScriptName' summary='false' sign='true'><text>Task</text></item>
</form>
'@

$viewAll = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE view SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<view name='All Documents' xmlns='http://www.lotus.com/dxl' version='14.5' showinmenu='true' publicaccess='false' designerversion='8.5.3' unreadmarks='standard' onopengoto='lastopened' onrefresh='displayindicator' headers='simple' showmargin='true' extendlastcolumn='true' unreadcolor='maroon' rowlinecount='1' headerlinecount='1' rowspacing='1.25' bgcolor='white' altrowcolor='#f0f3f7' headerbgcolor='#d6e2f0' marginbgcolor='white' totalscolor='blue' allowcustomizations='true'>
<actionbar bgcolor='#4a6fa5' bordercolor='#2f4a6e'>
<actionbarstyle height='5.0000ex'><font size='9pt' color='white'/></actionbarstyle>
<actionbuttonstyle margintype='fixed' margin='6' bgcolor='#eef3f9' displayborder='notesstyle'/>
<font size='9pt' color='#1a2332'/>
<border width='0px 0px 1px' color='#2f4a6e'/>
<action title='New Document' icon='12'><code event='click'><formula>@Command([Compose];"";"Document")</formula></code></action>
<action title='New Task' icon='58'><code event='click'><formula>@Command([Compose];"";"Task")</formula></code></action>
</actionbar>
<code event='selection'><formula>SELECT Form = "Document"</formula></code>
<column itemname='Subject' width='30' resizable='true'><columnheader title='Subject'><font size='9pt'/></columnheader></column>
<column itemname='Category' width='15' resizable='true'><columnheader title='Category'><font size='9pt'/></columnheader></column>
</view>
'@

$viewByCat = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE view SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<view name='By Category' xmlns='http://www.lotus.com/dxl' version='14.5' showinmenu='true' publicaccess='false' designerversion='8.5.3' unreadmarks='standard' onopengoto='lastopened' onrefresh='displayindicator' headers='simple' showmargin='true' extendlastcolumn='true' unreadcolor='maroon' rowlinecount='1' headerlinecount='1' rowspacing='1.25' bgcolor='white' altrowcolor='#f0f3f7' headerbgcolor='#d6e2f0' marginbgcolor='white' totalscolor='blue' allowcustomizations='true' categorized='true'>
<actionbar bgcolor='#4a6fa5' bordercolor='#2f4a6e'>
<actionbarstyle height='5.0000ex'><font size='9pt' color='white'/></actionbarstyle>
<actionbuttonstyle margintype='fixed' margin='6' bgcolor='#eef3f9' displayborder='notesstyle'/>
<font size='9pt' color='#1a2332'/>
<border width='0px 0px 1px' color='#2f4a6e'/>
<action title='New Document' icon='12'><code event='click'><formula>@Command([Compose];"";"Document")</formula></code></action>
<action title='New Task' icon='58'><code event='click'><formula>@Command([Compose];"";"Task")</formula></code></action>
</actionbar>
<code event='selection'><formula>SELECT Form = "Document"</formula></code>
<column itemname='Category' width='20' resizable='true' categorized='true'><columnheader title='Category'><font size='9pt'/></columnheader></column>
<column itemname='Subject' width='30' resizable='true'><columnheader title='Subject'><font size='9pt'/></columnheader></column>
</view>
'@

$viewTasks = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE view SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<view name='Tasks' xmlns='http://www.lotus.com/dxl' version='14.5' showinmenu='true' publicaccess='false' designerversion='8.5.3' unreadmarks='standard' onopengoto='lastopened' onrefresh='displayindicator' headers='simple' showmargin='true' extendlastcolumn='true' unreadcolor='maroon' rowlinecount='1' headerlinecount='1' rowspacing='1.25' bgcolor='white' altrowcolor='#f0f3f7' headerbgcolor='#d6e2f0' marginbgcolor='white' totalscolor='blue' allowcustomizations='true'>
<actionbar bgcolor='#4a6fa5' bordercolor='#2f4a6e'>
<actionbarstyle height='5.0000ex'><font size='9pt' color='white'/></actionbarstyle>
<actionbuttonstyle margintype='fixed' margin='6' bgcolor='#eef3f9' displayborder='notesstyle'/>
<font size='9pt' color='#1a2332'/>
<border width='0px 0px 1px' color='#2f4a6e'/>
<action title='New Task' icon='58'><code event='click'><formula>@Command([Compose];"";"Task")</formula></code></action>
<action title='New Document' icon='12'><code event='click'><formula>@Command([Compose];"";"Document")</formula></code></action>
</actionbar>
<code event='selection'><formula>SELECT Form = "Task"</formula></code>
<column itemname='Title' width='30' resizable='true'><columnheader title='Title'><font size='9pt'/></columnheader></column>
<column itemname='Status' width='12' resizable='true'><columnheader title='Status'><font size='9pt'/></columnheader></column>
<column itemname='DueDate' width='12' resizable='true'><columnheader title='Due'><font size='9pt'/></columnheader></column>
</view>
'@

$outline = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE outline SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<outline name='MainOutline' alias='MainOutline' xmlns='http://www.lotus.com/dxl' version='14.5' publicaccess='false' designerversion='8.5.3'>
<outlineentry label='Home' alias='Home' type='namedelementlink' targetframe='Content'><namedelementlink type='page' name='Home'/></outlineentry>
<outlineentry label='All Documents' alias='AllDocs' type='namedelementlink' targetframe='Content'><namedelementlink type='view' name='All Documents'/></outlineentry>
<outlineentry label='By Category' alias='ByCat' type='namedelementlink' targetframe='Content'><namedelementlink type='view' name='By Category'/></outlineentry>
<outlineentry label='Tasks' alias='Tasks' type='namedelementlink' targetframe='Content'><namedelementlink type='view' name='Tasks'/></outlineentry>
<outlineentry label='New Document' alias='NewDoc' type='namedelementlink' targetframe='Content'><namedelementlink type='form' name='Document'/></outlineentry>
<outlineentry label='New Task' alias='NewTask' type='namedelementlink' targetframe='Content'><namedelementlink type='form' name='Task'/></outlineentry>
<outlineentry label='About' alias='About' type='namedelementlink' targetframe='Content'><namedelementlink type='page' name='About'/></outlineentry>
</outline>
'@

$frameset = @'
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE frameset SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<frameset name='MainFrameset' alias='MainFrameset' xmlns='http://www.lotus.com/dxl' version='14.5' publicaccess='false' designerversion='8.5.3' columns='220px 1' spacing='1px'>
<frame name='Nav' targetframe='Content'><namedelementlink type='page' name='Nav'/></frame>
<frame name='Content'><namedelementlink type='page' name='Home'/></frame>
</frameset>
'@

$elements = @(
  (El 'page' $pageHome),
  (El 'page' $pageAbout),
  (El 'page' $pageNav),
  (El 'form' $formDoc),
  (El 'form' $formTask),
  (El 'view' $viewAll),
  (El 'view' $viewByCat),
  (El 'view' $viewTasks),
  (El 'outline' $outline),
  (El 'frameset' $frameset)
)

$sb = New-Object System.Text.StringBuilder
[void]$sb.Append('{"elements":[')
for ($i = 0; $i -lt $elements.Count; $i++) {
  if ($i -gt 0) { [void]$sb.Append(',') }
  $t = $elements[$i].type
  $dJson = ($elements[$i].dxl | ConvertTo-Json -Compress)
  [void]$sb.Append(('{{"type":"{0}","dxl":{1}}}' -f $t, $dJson))
}
[void]$sb.Append(']}')
$jsonBody = $sb.ToString()

$uri = "$base/designdxl?nsfPath=$([uri]::EscapeDataString($nsf))"
Write-Host "PUT $uri ($($elements.Count) elements)"
$result = Invoke-RestMethod -Method PUT -Uri $uri -Headers $h -Body $jsonBody
Write-Host ("created={0} ok={1} failed={2}" -f $result.created, $result.ok, $result.failed)
if ($result.results) {
  $result.results | ForEach-Object {
    $ok = $_.success
    $msg = if ($_.name) { $_.name } elseif ($_.message) { $_.message } else { '' }
    Write-Host ("  [{0}] {1} {2}" -f $(if ($ok) {'OK'} else {'FAIL'}), $_.type, $msg)
  }
}

Write-Host "`n=== verify lists ==="
$authH = @{ Authorization = "Bearer $($auth.bearer)" }
foreach ($path in @('pagenames','formnames','viewnames','outlinenames','framesetnames')) {
  $r = Invoke-RestMethod -Uri "$base/${path}?nsfPath=$([uri]::EscapeDataString($nsf))" -Headers $authH
  Write-Host "$path => $($r | ConvertTo-Json -Compress -Depth 4)"
}
