# Build and import correct minimal Java sources (with matching package/type).
$ErrorActionPreference = "Stop"

function Get-EnvFileValue([string]$Path, [string]$Key) {
  foreach ($line in Get-Content $Path) {
    if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
    $k, $v = $line -split '=', 2
    if ($k.Trim() -eq $Key) { return $v.Trim().Trim('"').Trim("'") }
  }
  return $null
}

function Write-Le32([byte[]]$Buf, [int]$Offset, [int]$Value) {
  $Buf[$Offset] = [byte]($Value -band 0xff)
  $Buf[$Offset + 1] = [byte](($Value -shr 8) -band 0xff)
  $Buf[$Offset + 2] = [byte](($Value -shr 16) -band 0xff)
  $Buf[$Offset + 3] = [byte](($Value -shr 24) -band 0xff)
}

function New-FileData([byte[]]$HeaderTemplate, [string]$Text) {
  $body = [Text.Encoding]::UTF8.GetBytes($Text)
  $out = New-Object byte[] (42 + $body.Length)
  [Array]::Copy($HeaderTemplate, 0, $out, 0, 42)
  [Array]::Copy($body, 0, $out, 42, $body.Length)
  Write-Le32 $out 8 $body.Length
  # mirror sizes commonly stored at 28/30
  Write-Le32 $out 28 $body.Length
  Write-Le32 $out 30 $body.Length
  # offset 26 often oldSize+18
  Write-Le32 $out 26 ($body.Length + 18)
  return $out
}

function New-JavaNoteDxl([string]$Wanted, [string]$Source, [byte[]]$FileHeader, [string]$Flags) {
@"
<?xml version='1.0' encoding='utf-8'?>
<!DOCTYPE note SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<note class='form' xmlns='http://www.lotus.com/dxl' version='14.5' maintenanceversion='1.0'>
<item name='`$Flags'><text>$Flags</text></item>
<item name='`$TITLE'><text>$Wanted</text></item>
<item name='`$DesignerVersion'><text>8.5.3</text></item>
<item name='`$FileSize' sign='true'><number>$($Source.Length)</number></item>
<item name='`$FileData' sign='true'>
<rawitemdata type='1'>
$([Convert]::ToBase64String((New-FileData $FileHeader $Source)))
</rawitemdata></item>
<item name='`$FileNames' sign='true'><text>$Wanted</text></item>
</note>
"@
}

if (-not ([System.Management.Automation.PSTypeName]'IwsTrustAllCertsFixB').Type) {
  Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class IwsTrustAllCertsFixB : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) { return true; }
}
"@
}
[System.Net.ServicePointManager]::CertificatePolicy = New-Object IwsTrustAllCertsFixB
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

# Extract header from embedded default
$tmpl = Get-Content -Raw "c:\Git\drapi-iws-design\extension\src\main\resources\defaults\javasource.dxl"
if ($tmpl -notmatch '(?s)<item name=''\$FileData''[^>]*>\s*<rawitemdata[^>]*>\s*([A-Za-z0-9+/=\s]+)\s*</rawitemdata>') {
  throw "no filedata in template"
}
$fileHeader = New-Object byte[] 42
[Array]::Copy([Convert]::FromBase64String(($Matches[1] -replace '\s','')), 0, $fileHeader, 0, 42)

$sources = @(
  @{
    Name = 'se/infoware/kyber/minimal/Minimal.java'
    Flags = '34567Cg~['
    Source = @"
package se.infoware.kyber.minimal;

/** Minimal Java class starter. */
public class Minimal {
	public static String hello(String who) {
		if (who == null || who.isEmpty()) {
			who = "World";
		}
		return "Hello, " + who + "!";
	}
}
"@
  },
  @{
    Name = 'se/infoware/kyber/minimal/MinimalInterface.java'
    Flags = '34567Cg~['
    Source = @"
package se.infoware.kyber.minimal;

/** Minimal Java interface starter. */
public interface MinimalInterface {
	String greet(String who);
}
"@
  },
  @{
    Name = 'se/infoware/kyber/minimal/MinimalEnum.java'
    Flags = '34567Cg~['
    Source = @"
package se.infoware.kyber.minimal;

/** Minimal Java enum starter. */
public enum MinimalEnum {
	DRAFT,
	OPEN,
	CLOSED
}
"@
  }
)

$envFile = "C:\Git\install-domino-restapi\.env"
$base = (Get-EnvFileValue $envFile "DOMINO_REST_API_DATA_URL").TrimEnd('/')
$user = Get-EnvFileValue $envFile "REST_ADMIN_USER"
$pass = Get-EnvFileValue $envFile "REST_ADMIN_PASSWORD"
$auth = Invoke-RestMethod -Method POST -Uri "$base/api/v1/auth" -ContentType "application/json" `
  -Body (@{ username = $user; password = $pass } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($auth.bearer)" }
$nsfQ = "nsfPath=" + [uri]::EscapeDataString("kyber.nsf")

$deletePayload = @{
  elements = @(
    @{ type = "javasource"; name = $sources[0].Name; delete = $true },
    @{ type = "javasource"; name = $sources[1].Name; delete = $true },
    @{ type = "javasource"; name = $sources[2].Name; delete = $true }
  )
} | ConvertTo-Json -Depth 5
try {
  $null = Invoke-RestMethod -Method PUT -Uri "$base/api/kyber-v1/designdxl?$nsfQ" -Headers $h -ContentType "application/json" -Body $deletePayload
} catch {}

$elements = @()
foreach ($s in $sources) {
  # Use UTF8 bytes length for FileSize (CRLF vs LF)
  $bytes = [Text.Encoding]::UTF8.GetBytes($s.Source)
  $dxl = New-JavaNoteDxl $s.Name $s.Source $fileHeader $s.Flags
  # Fix FileSize to actual UTF-8 byte length
  $dxl = $dxl -replace "<number>\d+</number>", "<number>$($bytes.Length)</number>"
  $elements += @{ type = "javasource"; name = $s.Name; dxl = $dxl }
  Write-Host "Prepared" $s.Name "bytes=$($bytes.Length)"
}

$payload = @{ elements = $elements } | ConvertTo-Json -Depth 6
$put = Invoke-RestMethod -Method PUT -Uri "$base/api/kyber-v1/designdxl?$nsfQ" -Headers $h -ContentType "application/json" -Body $payload
Write-Host "imported=$($put.imported) failed=$($put.failed)"
$put.results | ForEach-Object {
  Write-Host $_.type $_.name $_.success $_.message
}

$env:REST_ADMIN_USER = $user
$env:REST_ADMIN_PASSWORD = $pass
& "c:\Git\drapi-iws-design\tools\export-to-repo.ps1" -RestUrl $base -NsfPath "kyber.nsf" -OutDir "c:\Git\drapi-iws-design\kyber"

Get-ChildItem "c:\Git\drapi-iws-design\kyber\javasources" -Filter "*minimal*.dxl" | ForEach-Object {
  $dxl = Get-Content -Raw $_.FullName
  if ($dxl -match '(?s)<item name=''\$FileData''[^>]*>\s*<rawitemdata[^>]*>\s*([A-Za-z0-9+/=\s]+)') {
    $raw = [Convert]::FromBase64String(($Matches[1] -replace '\s',''))
    $src = [Text.Encoding]::UTF8.GetString($raw, 42, $raw.Length - 42)
    Write-Host "VERIFY" $_.Name
    Write-Host (($src -split "`n" | Select-Object -First 5) -join ' | ')
  }
}
