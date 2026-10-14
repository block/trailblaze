# Trailblaze installer for Windows
#
# Downloads a Trailblaze release (JAR + Windows launcher) from GitHub. Windows runs web
# (Playwright) trails only; Android and iOS device control need a macOS or Linux host.
#
# Install (Windows PowerShell 5.1 or PowerShell 7):
#   irm https://raw.githubusercontent.com/block/trailblaze/main/install.ps1 | iex
#
# Environment variables:
#   TRAILBLAZE_VERSION  - Install a specific version (e.g. "0.3.0"). Default: latest.
#   TRAILBLAZE_DIR      - Install directory. Default: %USERPROFILE%\.trailblaze
#   TRAILBLAZE_ARTIFACT_DIR - Install trailblaze.jar + trailblaze.cmd from this local directory
#                         instead of downloading a release (CI installs its own build this way).

# `irm | iex` runs this in the caller's own session. The script block keeps its variables and
# preferences out of that session, and errors `throw` rather than `exit`, which would close it.
& {

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# Windows PowerShell 5.1 can default to TLS 1.0, which GitHub refuses.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Repo = 'block/trailblaze'
$InstallDir = if ($env:TRAILBLAZE_DIR) { $env:TRAILBLAZE_DIR } else { Join-Path $env:USERPROFILE '.trailblaze' }
$BinDir = Join-Path $InstallDir 'bin'

function Info($msg) { Write-Host "  $msg" }
function Fail($msg) { throw "ERROR: $msg" }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

if (-not [Environment]::Is64BitOperatingSystem) { Fail 'Trailblaze needs 64-bit Windows (x64).' }

# The same Java trailblaze.cmd runs with: JAVA_HOME's when it has one, else the one on PATH.
$java = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
  $java = Get-Command (Join-Path $env:JAVA_HOME 'bin\java.exe')
}
if (-not $java) { $java = Get-Command java -ErrorAction SilentlyContinue }
if (-not $java) { Fail "'java' is required but not found. Install JDK 17 or newer first." }
# `java -version` prints to stderr, which Windows PowerShell 5.1 turns into an error under 'Stop'.
$javaSettings = & {
  $ErrorActionPreference = 'Continue'
  (& $java.Source -XshowSettings:properties -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
}
if ($javaSettings -match 'version "(\d+)') {
  if ([int]$Matches[1] -lt 17) { Fail "Java 17+ is required (found Java $($Matches[1]))." }
}
# Trailblaze refuses to start on an arm64 JVM: two of its native libraries have no Windows arm64 build.
if ($javaSettings -match 'os\.arch = (\S+)' -and $Matches[1] -notin @('amd64', 'x86_64')) {
  Fail "Trailblaze needs an x64 Java on Windows (found $($Matches[1]) Java at $($java.Source))."
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
# Install
# ---------------------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
$jarPath = Join-Path $BinDir 'trailblaze.jar'
$launcherPath = Join-Path $BinDir 'trailblaze.cmd'

if ($env:TRAILBLAZE_ARTIFACT_DIR) {
  $Version = "from $($env:TRAILBLAZE_ARTIFACT_DIR)"
  foreach ($name in @('trailblaze.jar', 'trailblaze.cmd')) {
    $source = Join-Path $env:TRAILBLAZE_ARTIFACT_DIR $name
    if (-not (Test-Path $source)) { Fail "$source not found." }
    Copy-Item -Force $source (Join-Path $BinDir $name)
  }
  Info "Installed $Version"
} else {
  if ($env:TRAILBLAZE_VERSION) {
    $Tag = "v$($env:TRAILBLAZE_VERSION)"
    Info "Installing Trailblaze $Tag"
  } else {
    Info 'Fetching latest release...'
    $Tag = (Invoke-RestMethod -UseBasicParsing "https://api.github.com/repos/$Repo/releases/latest").tag_name
    if (-not $Tag) { Fail 'Could not determine latest release. Set TRAILBLAZE_VERSION manually.' }
    Info "Latest release: $Tag"
  }
  $Version = $Tag.TrimStart('v')
  $ReleaseUrl = "https://github.com/$Repo/releases/download/$Tag"

  # The launcher first: a release without one predates Windows support, and its JAR refuses to start.
  Info 'Downloading launcher...'
  try {
    Invoke-WebRequest -UseBasicParsing "$ReleaseUrl/trailblaze.cmd" -OutFile $launcherPath
  } catch {
    Fail "Trailblaze $Tag has no Windows launcher; it predates Windows support. Install a newer release."
  }

  Info 'Downloading trailblaze.jar...'
  Invoke-WebRequest -UseBasicParsing "$ReleaseUrl/trailblaze.jar" -OutFile $jarPath
}

# ---------------------------------------------------------------------------
# PATH setup
# ---------------------------------------------------------------------------

# The raw registry value, not [Environment]'s Path API: that one returns the value with %VAR%
# references expanded and writes it back as a plain string, freezing an entry like %JAVA_HOME%\bin
# at today's value.
$envKey = 'HKCU:\Environment'
$userPath = (Get-Item $envKey).GetValue('Path', '', 'DoNotExpandEnvironmentNames')
$onPath = ($userPath -split ';' | ForEach-Object { [Environment]::ExpandEnvironmentVariables($_) }) -contains $BinDir
if (-not $onPath) {
  $newPath = if ($userPath) { "$($userPath.TrimEnd(';'));$BinDir" } else { $BinDir }
  New-ItemProperty -Path $envKey -Name 'Path' -Value $newPath -PropertyType ExpandString -Force | Out-Null
  # A registry write alone reaches only programs started after the next sign-in. Tell Explorer the
  # environment changed, so the terminals it opens from now on see the new PATH.
  try {
    if (-not ('TrailblazeInstall.Native' -as [type])) {
      Add-Type -Namespace TrailblazeInstall -Name Native -MemberDefinition @'
[DllImport("user32.dll", CharSet = CharSet.Unicode)]
public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, UIntPtr wParam, string lParam, uint flags, uint timeout, out UIntPtr result);
'@
    }
    $broadcastResult = [UIntPtr]::Zero
    # HWND_BROADCAST, WM_SETTINGCHANGE, SMTO_ABORTIFHUNG
    [TrailblazeInstall.Native]::SendMessageTimeout([IntPtr]0xffff, 0x1A, [UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$broadcastResult) | Out-Null
  } catch {
    Write-Warning 'Could not announce the PATH change; sign out and back in if a new terminal does not find trailblaze.'
  }
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

}
