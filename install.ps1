# Trailblaze installer for Windows
#
# Downloads a Trailblaze release JAR from GitHub and extracts the Windows launcher the JAR
# carries (its `trailblaze-launcher.cmd` entry), so the two always match. Windows runs web
# (Playwright) trails only; Android and iOS device control need a macOS or Linux host.
#
# Install (Windows PowerShell 5.1 or PowerShell 7):
#   irm https://raw.githubusercontent.com/block/trailblaze/main/install.ps1 | iex
#
# Environment variables:
#   TRAILBLAZE_VERSION  - Install a specific version (e.g. "0.3.0"). Default: latest.
#   TRAILBLAZE_DIR      - Install directory. Default: %USERPROFILE%\.trailblaze
#   TRAILBLAZE_ARTIFACT_DIR - Install trailblaze.jar from this local directory instead of
#                         downloading a release (CI installs its own build this way).

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
# Absolute from here on: .NET resolves a relative path against the process directory, not PowerShell's.
$BinDir = (Get-Item -LiteralPath $BinDir).FullName
$jarPath = Join-Path $BinDir 'trailblaze.jar'
$launcherPath = Join-Path $BinDir 'trailblaze.cmd'

# Writes the JAR's `trailblaze-launcher.cmd` entry to $dest; $false if it cannot. A JAR is a zip,
# and Windows 10+ ships bsdtar as tar.exe, which reads one: no Java needed, and no Expand-Archive,
# which Windows PowerShell 5.1 refuses for any file not named .zip. tar.exe is called by its
# System32 path because Git for Windows puts a GNU tar that cannot read zips ahead of it on PATH,
# and through Sysnative from a 32-bit PowerShell, which Windows otherwise points at SysWOW64.
#
# Every path below is -LiteralPath: the install directory is the user's, and a `[` in it would
# otherwise be read as a wildcard.
function Extract-Launcher($jar, $dest) {
  $systemDir = if ([Environment]::Is64BitProcess) { 'System32' } else { 'Sysnative' }
  $tar = Join-Path $env:SystemRoot "$systemDir\tar.exe"
  if (-not (Test-Path -LiteralPath $tar)) { return $false }
  $stage = Join-Path (Split-Path $dest) '.trailblaze-launcher-extract'
  Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force -Path $stage | Out-Null
  try {
    # tar.exe reports a missing entry on stderr, which Windows PowerShell 5.1 turns into an error
    # under 'Stop'; the exit code is what decides.
    $tarExit = & {
      $ErrorActionPreference = 'Continue'
      & $tar -xf $jar -C $stage 'trailblaze-launcher.cmd' 2>&1 | Out-Null
      $LASTEXITCODE
    }
    $extracted = Join-Path $stage 'trailblaze-launcher.cmd'
    if ($tarExit -ne 0 -or -not (Test-Path -LiteralPath $extracted) -or (Get-Item -LiteralPath $extracted).Length -eq 0) { return $false }
    Move-Item -LiteralPath $extracted -Destination $dest -Force
    return $true
  } finally {
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
  }
}

# The new JAR and launcher are staged beside the installed ones and moved over them together, JAR
# first: a running Trailblaze keeps its JAR open and Windows will not move an open file, so a
# failure there leaves the old install whole rather than a new launcher on an old JAR. The old JAR
# is kept until the launcher is in place, and put back if either move after it fails.
$jarStaged = "$jarPath.download"
$launcherStaged = "$launcherPath.download"
$jarPrevious = "$jarPath.previous"
function Remove-Staged {
  foreach ($staged in @($jarStaged, $launcherStaged)) {
    Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue
  }
}
# Moves the old JAR back over whatever is at $jarPath; $false if that fails too, which leaves it
# at $jarPrevious rather than losing it.
function Restore-Previous {
  if (-not (Test-Path -LiteralPath $jarPrevious)) { return $true }
  try {
    Move-Item -LiteralPath $jarPrevious -Destination $jarPath -Force
    return $true
  } catch {
    return $false
  }
}

function Fail-AndRestore($what) {
  $restored = Restore-Previous
  Remove-Staged
  if ($restored) { Fail "$what, so the previous install was left in place. Close whatever has it open, then install again." }
  Fail "$what, and the previous JAR could not be put back: it is at $jarPrevious. Close whatever has $jarPath open, then install again."
}

function Install-Staged {
  # An install whose rollback could not finish left the working JAR aside: put it back first.
  if ((Test-Path -LiteralPath $jarPrevious) -and -not (Test-Path -LiteralPath $jarPath)) {
    Move-Item -LiteralPath $jarPrevious -Destination $jarPath
  }
  try {
    if (Test-Path -LiteralPath $jarPath) { Move-Item -LiteralPath $jarPath -Destination $jarPrevious -Force }
  } catch {
    Remove-Staged
    Fail "Could not replace $jarPath, which a running Trailblaze keeps open. Run 'trailblaze stop', then install again."
  }
  try {
    Move-Item -LiteralPath $jarStaged -Destination $jarPath
  } catch {
    Fail-AndRestore "Could not move the new JAR to $jarPath"
  }
  try {
    Move-Item -LiteralPath $launcherStaged -Destination $launcherPath -Force
  } catch {
    Fail-AndRestore "Could not replace $launcherPath"
  }
  Remove-Item -LiteralPath $jarPrevious -Force -ErrorAction SilentlyContinue
}

# One install at a time per directory: two would share the staging files and the old JAR's backup,
# and one could move away the JAR the other just put in place. The lock is a file opened with no
# sharing, so it is released when this install finishes or its process ends.
try {
  $installLock = [System.IO.File]::Open((Join-Path $BinDir '.install.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
} catch {
  Fail "Another Trailblaze install into $BinDir is running. Wait for it to finish, then install again."
}
try {
  if ($env:TRAILBLAZE_ARTIFACT_DIR) {
    $Version = "from $($env:TRAILBLAZE_ARTIFACT_DIR)"
    $source = Join-Path $env:TRAILBLAZE_ARTIFACT_DIR 'trailblaze.jar'
    if (-not (Test-Path -LiteralPath $source)) { Fail "$source not found." }
    Copy-Item -LiteralPath $source -Destination $jarStaged -Force
    if (-not (Extract-Launcher $jarStaged $launcherStaged)) {
      # A JAR built before the launcher was embedded: use the copy laid down beside it.
      $sibling = Join-Path $env:TRAILBLAZE_ARTIFACT_DIR 'trailblaze.cmd'
      if (-not (Test-Path -LiteralPath $sibling)) {
        Remove-Staged
        Fail "$source has no trailblaze-launcher.cmd entry, and $sibling is missing."
      }
      Copy-Item -LiteralPath $sibling -Destination $launcherStaged -Force
    }
    Install-Staged
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

    Info 'Downloading trailblaze.jar...'
    Invoke-WebRequest -UseBasicParsing "$ReleaseUrl/trailblaze.jar" -OutFile $jarStaged
    if (Extract-Launcher $jarStaged $launcherStaged) {
      Info 'Extracted the launcher from trailblaze.jar'
    } else {
      # Releases from before the launcher was embedded in the JAR publish it as its own asset, and a
      # release with neither predates Windows support: its JAR refuses to start.
      Info 'Downloading launcher...'
      try {
        Invoke-WebRequest -UseBasicParsing "$ReleaseUrl/trailblaze.cmd" -OutFile $launcherStaged
      } catch {
        Remove-Staged
        Fail "Trailblaze $Tag has no Windows launcher; it predates Windows support. Install a newer release."
      }
    }
    Install-Staged
  }
} finally {
  $installLock.Dispose()
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
