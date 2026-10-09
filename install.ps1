# Trailblaze installer for Windows
#
# Downloads a Trailblaze release (JAR + Windows launcher) from GitHub. Windows runs web
# (Playwright) trails only; Android and iOS device control need a macOS or Linux host.
#
# Install (PowerShell):
#   irm https://raw.githubusercontent.com/block/trailblaze/main/install.ps1 | iex
#
# Environment variables:
#   TRAILBLAZE_VERSION  - Install a specific version (e.g. "0.3.0"). Default: latest.
#   TRAILBLAZE_DIR      - Install directory. Default: %USERPROFILE%\.trailblaze

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Repo = 'block/trailblaze'
$InstallDir = if ($env:TRAILBLAZE_DIR) { $env:TRAILBLAZE_DIR } else { Join-Path $env:USERPROFILE '.trailblaze' }
$BinDir = Join-Path $InstallDir 'bin'

function Info($msg) { Write-Host "  $msg" }
function Fail($msg) { Write-Error "ERROR: $msg"; exit 1 }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

if (-not [Environment]::Is64BitOperatingSystem) { Fail 'Trailblaze needs 64-bit Windows (x64).' }

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java -and $env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
  $java = Get-Command (Join-Path $env:JAVA_HOME 'bin\java.exe')
}
if (-not $java) { Fail "'java' is required but not found. Install JDK 17 or newer first." }
$javaVersionLine = (& $java.Source -version 2>&1 | Select-Object -First 1).ToString()
if ($javaVersionLine -match '"(\d+)') {
  if ([int]$Matches[1] -lt 17) { Fail "Java 17+ is required (found Java $($Matches[1]))." }
}

foreach ($optional in @(
    @{ Name = 'bun'; Why = 'scripted-tool (.ts trailmap tool) authoring and dispatch will be unavailable. Install from https://bun.sh/' },
    @{ Name = 'ffmpeg'; Why = 'trail video capture will be skipped. Trails still run.' }
  )) {
  if (-not (Get-Command "$($optional.Name).exe" -ErrorAction SilentlyContinue)) {
    Write-Warning "'$($optional.Name)' is not on PATH - $($optional.Why)"
  }
}

# ---------------------------------------------------------------------------
# Resolve version
# ---------------------------------------------------------------------------

if ($env:TRAILBLAZE_VERSION) {
  $Tag = "v$($env:TRAILBLAZE_VERSION)"
  Info "Installing Trailblaze $Tag"
} else {
  Info 'Fetching latest release...'
  $Tag = (Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest").tag_name
  if (-not $Tag) { Fail 'Could not determine latest release. Set TRAILBLAZE_VERSION manually.' }
  Info "Latest release: $Tag"
}
$Version = $Tag.TrimStart('v')

# ---------------------------------------------------------------------------
# Download
# ---------------------------------------------------------------------------

$ReleaseUrl = "https://github.com/$Repo/releases/download/$Tag"
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null

Info 'Downloading trailblaze.jar...'
Invoke-WebRequest "$ReleaseUrl/trailblaze.jar" -OutFile (Join-Path $BinDir 'trailblaze.jar')

Info 'Downloading launcher...'
$launcherPath = Join-Path $BinDir 'trailblaze.cmd'
try {
  Invoke-WebRequest "$ReleaseUrl/trailblaze.cmd" -OutFile $launcherPath
} catch {
  # Releases cut before the Windows launcher shipped as an asset still carry it in the tagged tree.
  Invoke-WebRequest "https://raw.githubusercontent.com/$Repo/$Tag/scripts/trailblaze.cmd" -OutFile $launcherPath
}

# ---------------------------------------------------------------------------
# PATH setup
# ---------------------------------------------------------------------------

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$onPath = ($userPath -split ';') -contains $BinDir
if (-not $onPath) {
  $newPath = if ($userPath) { "$userPath;$BinDir" } else { $BinDir }
  [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
  Info "Added $BinDir to your user PATH"
}

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

Write-Host ''
Write-Host "Trailblaze $Version installed to $BinDir"
Write-Host ''
if (-not $onPath) {
  Write-Host 'Open a new terminal so the PATH change takes effect, then:'
} else {
  Write-Host 'Then:'
}
Write-Host '  trailblaze --help                       # See all commands'
Write-Host '  trailblaze run <your-web-trail>.trail.yaml'
