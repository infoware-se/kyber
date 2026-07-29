# Builds keep-design-*.jar (KEEP extension — NOT an HCL product).
# Requires: JDK 21, Maven 3.9+, HCL keep-core + keep-extension + keep-jnx-ext JARs.
#
# Usage (this PC):
#   $env:DRAPI_HOME = "C:\Domino\restapi"
#   .\build.ps1
#   .\build.ps1 -InstallToDrapi

param(
    [string]$KeepJarDir = $env:DRAPI_HOME,
    [string]$InstallerPath,
    [switch]$SkipInstallKeepJars,
    [switch]$InstallToDrapi
)

$ErrorActionPreference = "Stop"
$ExtensionRoot = $PSScriptRoot
$RepoRoot = Split-Path $ExtensionRoot -Parent
$ToolsMaven = Join-Path $RepoRoot ".tools\apache-maven-3.9.9\bin\mvn.cmd"

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    $candidates = Get-ChildItem "C:\Program Files\Microsoft\jdk-*" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending
    foreach ($dir in $candidates) {
        if (Test-Path (Join-Path $dir.FullName "bin\java.exe")) {
            return $dir.FullName
        }
    }
    return $null
}

function Resolve-Mvn {
    if (Get-Command mvn -ErrorAction SilentlyContinue) {
        return "mvn"
    }
    if (Test-Path $ToolsMaven) {
        return $ToolsMaven
    }
    # Reuse sibling deploy repo Maven if present
    $siblingMaven = Join-Path (Split-Path $RepoRoot -Parent) "drapi-iws-deploy\.tools\apache-maven-3.9.9\bin\mvn.cmd"
    if (Test-Path $siblingMaven) {
        return $siblingMaven
    }
    return $null
}

function Find-KeepJars([string]$Dir) {
    if (-not $Dir -or -not (Test-Path $Dir)) { return $null }
    $core = Get-ChildItem -Path $Dir -Filter "keep-core-*.jar" -File |
        Where-Object { $_.Name -notmatch "javadoc" } |
        Select-Object -First 1
    $ext = Get-ChildItem -Path $Dir -Filter "keep-extension-*.jar" -File |
        Where-Object { $_.Name -notmatch "javadoc" } |
        Select-Object -First 1
    $jnx = Get-ChildItem -Path $Dir -Filter "keep-jnx-ext-*.jar" -File |
        Where-Object { $_.Name -notmatch "javadoc" } |
        Select-Object -First 1
    if ($core -and $ext -and $jnx) {
        return @{
            Core = $core.FullName
            Extension = $ext.FullName
            JnxExt = $jnx.FullName
            Version = ($core.BaseName -replace "keep-core-", "")
            Dir = $Dir
        }
    }
    if ($core -and $ext -and -not $jnx) {
        return @{
            Missing = "keep-jnx-ext-$($core.BaseName -replace 'keep-core-', '').jar"
            Version = ($core.BaseName -replace "keep-core-", "")
            Dir = $Dir
        }
    }
    return $null
}

function Install-AllKeepJarsFromDir([string]$Mvn, [string]$Dir) {
    Get-ChildItem -Path $Dir -Filter "keep-*.jar" -File |
        Where-Object { $_.Name -notmatch "javadoc" } |
        ForEach-Object {
            if ($_.BaseName -match '^(keep(?:-[a-z0-9]+)*)-(\d+\.\d+\.\d+)$') {
                Install-KeepJar $Mvn $_.FullName $Matches[1] $Matches[2]
            }
        }
}

function Install-KeepJar([string]$Mvn, [string]$File, [string]$ArtifactId, [string]$Version) {
    Write-Host "Installing $ArtifactId $Version to local Maven..."
    & $Mvn install:install-file `
        "-Dfile=$File" `
        "-DgroupId=com.hcl.domino.keep" `
        "-DartifactId=$ArtifactId" `
        "-Dversion=$Version" `
        "-Dpackaging=jar"
    if ($LASTEXITCODE -ne 0) { throw "mvn install:install-file failed for $ArtifactId" }
}

function Extract-KeepFromInstaller([string]$InstallerPath) {
    $javaHome = Resolve-JavaHome
    if (-not $javaHome) {
        throw "JDK 21 required to run HCL restapiInstall.jar"
    }
    $java = Join-Path $javaHome "bin\java.exe"
    $extractDir = Join-Path $ExtensionRoot "lib\extracted"
    if (Test-Path $extractDir) {
        Remove-Item $extractDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $extractDir -Force | Out-Null

    Write-Host "Extracting HCL DRAPI SDK from installer to: $extractDir"
    & $java -jar $InstallerPath -a -n -s -p="$extractDir"
    if ($LASTEXITCODE -ne 0) {
        throw "Installer extract failed (exit $LASTEXITCODE). Check -InstallerPath and JDK."
    }

    $found = Find-KeepJars $extractDir
    if (-not $found) {
        $found = Get-ChildItem $extractDir -Filter "keep-core-*.jar" -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch "javadoc" } |
            Select-Object -First 1
        if ($found) {
            $dir = $found.Directory.FullName
            return Find-KeepJars $dir
        }
    }
    return $found
}

# --- JDK
$javaHome = Resolve-JavaHome
if (-not $javaHome) {
    Write-Error @"
JDK 21 not found.

Install Microsoft OpenJDK 21, then re-run:
  winget install Microsoft.OpenJDK.21
"@
}
$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
Write-Host "Using JAVA_HOME: $javaHome"

# --- Maven
$mvn = Resolve-Mvn
if (-not $mvn) {
    Write-Error @"
Maven (mvn) not found.

Run from repo root (downloads Maven to .tools/):
  Invoke-WebRequest -Uri 'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' -OutFile .tools\maven.zip
  Expand-Archive .tools\maven.zip .tools -Force

Or install Maven 3.9+ globally and re-run build.ps1
"@
}
Write-Host "Using Maven: $mvn"

# --- Optional: extract keep JARs from HCL installer
if ($InstallerPath) {
    if (-not (Test-Path $InstallerPath)) {
        throw "Installer not found: $InstallerPath"
    }
    $extracted = Extract-KeepFromInstaller $InstallerPath
    if (-not $extracted) {
        throw "keep-core / keep-extension not found after installer extract"
    }
    $KeepJarDir = $extracted.Dir
    Write-Host "Found HCL keep JARs (version $($extracted.Version)) in extracted SDK"
}

# --- Install HCL keep JARs to ~/.m2
if (-not $SkipInstallKeepJars) {
    $libDir = Join-Path $ExtensionRoot "lib"
    $candidates = @(
        $KeepJarDir,
        $libDir,
        "C:\Domino\restapi",
        "C:\Program Files\HCL\restapi",
        "C:\Program Files\HCL\RESTAPI",
        "/opt/hcl/keep"
    ) | Where-Object { $_ }

    $found = $null
    foreach ($dir in $candidates) {
        $found = Find-KeepJars $dir
        if ($found) {
            Write-Host "Found HCL keep JARs in: $dir (version $($found.Version))"
            break
        }
    }

    if ($found -and $found.Missing) {
        Write-Error @"
HCL keep JARs incomplete in $($found.Dir).

Found keep-core and keep-extension ($($found.Version)) but missing:
  $($found.Missing)

Copy it from the same DRAPI folder into extension/lib/, then re-run:
  .\build.ps1
"@
    }

    if (-not $found) {
        Write-Error @"
HCL keep-core / keep-extension / keep-jnx-ext JARs not found.

Copy these three files from the DRAPI install folder into extension/lib/:

  keep-core-<version>.jar
  keep-extension-<version>.jar
  keep-jnx-ext-<version>.jar

Typical paths:
  C:\Domino\restapi\
  C:\Program Files\HCL\restapi\

Then re-run:
  cd extension
  .\build.ps1

Or set DRAPI_HOME:
  `$env:DRAPI_HOME = 'C:\Domino\restapi'
  .\build.ps1
"@
    }

    $pomPath = Join-Path $ExtensionRoot "pom.xml"
    $pom = Get-Content $pomPath -Raw
    if ($pom -match '<keep\.version>([^<]+)</keep\.version>' -and $Matches[1] -ne $found.Version) {
        Write-Host "Updating pom.xml keep.version: $($Matches[1]) -> $($found.Version)" -ForegroundColor Yellow
        $pom = $pom -replace '<keep\.version>[^<]+</keep\.version>', "<keep.version>$($found.Version)</keep.version>"
        Set-Content -Path $pomPath -Value $pom -NoNewline
    }

    Install-AllKeepJarsFromDir $mvn $found.Dir
}

Push-Location $ExtensionRoot
try {
    Write-Host "`nBuilding KEEP keep-design.jar..."
    & $mvn package -q
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed (exit $LASTEXITCODE)" }

    $jar = Get-ChildItem -Path "target" -Filter "keep-design-*.jar" -File |
        Where-Object { $_.Name -notmatch "sources|javadoc" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jar) {
        throw "Build finished but no keep-design-*.jar in target/"
    }

    Write-Host ""
    Write-Host "SUCCESS - KEEP extension JAR (install THIS on Domino REST):" -ForegroundColor Green
    Write-Host "  $($jar.FullName)" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next steps:"
    Write-Host "  1. .\build.ps1 -InstallToDrapi   (quit via COM, swap JAR, load via COM)"
    Write-Host "  2. Or manually: tools\restart-restapi-com.ps1 after copying the JAR"
    Write-Host "  3. Verify /api/design-v1/webservicenames in Swagger"
    Write-Host ""

    $installRoot = if ($KeepJarDir) { $KeepJarDir } else { "C:\Domino\restapi" }
    if ($InstallToDrapi -and (Test-Path (Join-Path $installRoot "libs"))) {
        $restartScript = Join-Path $RepoRoot "tools\restart-restapi-com.ps1"
        Write-Host "Stopping restapi via Notes COM (so libs JAR can be replaced)..." -ForegroundColor Yellow
        & $restartScript -QuitOnly
        if ($LASTEXITCODE -ne 0) { throw "restart-restapi-com.ps1 -QuitOnly failed" }

        $libs = Join-Path $installRoot "libs"
        Get-ChildItem -Path $libs -Filter "iws-design-*.jar" -File -ErrorAction SilentlyContinue |
            ForEach-Object {
                Write-Host "Removing old JAR: $($_.FullName)" -ForegroundColor Yellow
                Remove-Item $_.FullName -Force
            }
        Get-ChildItem -Path $libs -Filter "keep-design-*.jar" -File -ErrorAction SilentlyContinue |
            ForEach-Object {
                Write-Host "Removing previous keep-design JAR: $($_.FullName)" -ForegroundColor Yellow
                Remove-Item $_.FullName -Force
            }
        $dest = Join-Path $libs $jar.Name
        Copy-Item $jar.FullName $dest -Force
        Write-Host "Copied to: $dest" -ForegroundColor Yellow

        Write-Host "Starting restapi via Notes COM..." -ForegroundColor Yellow
        & $restartScript -LoadOnly
        if ($LASTEXITCODE -ne 0) { throw "restart-restapi-com.ps1 -LoadOnly failed" }
    }
}
finally {
    Pop-Location
}
