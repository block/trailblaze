# Wikipedia trail on a Windows host: the PowerShell counterpart of pr_run_wikipedia_trails.sh.
#
# Runs the recorded web showcase trail through the Windows launcher (`trailblaze.cmd`), which the
# workflow puts on PATH from the `build-uber-jar` artifact. Unlike the Linux script, this does NOT
# start the daemon by hand: `trailblaze trail` starts it, which is the path a Windows user takes and
# the part of Windows support most worth covering.
#
# Logs are always collected into ./trailblaze-logs, even when the trail fails.

$ErrorActionPreference = 'Continue'
$failed = $false

$logsDir = Join-Path (Get-Location) 'trailblaze-logs'
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$stateDir = Join-Path $env:USERPROFILE '.trailblaze'

Write-Host '========================================='
Write-Host 'Starting Wikipedia Trail Execution (Windows)'
Write-Host "Working directory: $(Get-Location)"
Write-Host '========================================='

# esbuild for the trailmap's scripted tools; see pr_run_wikipedia_trails.sh.
Write-Host 'Installing TypeScript SDK devDependencies (esbuild)...'
Push-Location sdks/typescript
bun install --frozen-lockfile
if ($LASTEXITCODE -ne 0) { Write-Host 'ERROR: bun install failed in sdks/typescript'; $failed = $true }
Pop-Location

# Must be set before the first `trailblaze` call: the daemon that call starts inherits it.
$env:TRAILBLAZE_CONFIG_DIR = Join-Path (Get-Location) 'examples/wikipedia/trails/config'
Write-Host "TRAILBLAZE_CONFIG_DIR=$env:TRAILBLAZE_CONFIG_DIR"

if (-not $failed) {
  Write-Host 'Pre-installing Playwright Chromium...'
  bunx playwright@1.59.0 install chromium
  if ($LASTEXITCODE -ne 0) { Write-Host 'WARNING: Playwright pre-install failed - download will happen during trail execution' }
}

# Same lookup as showcase-trail.sh (`web:` -> `recording:`), done here because `bash` on a Windows
# runner can resolve to WSL rather than Git Bash.
function Get-ShowcaseTrail($platform) {
  $current = $null
  foreach ($line in Get-Content 'docs/showcase-trails.yml') {
    if ($line -match '^([a-z][a-z0-9_]*):\s*$') { $current = $Matches[1]; continue }
    if ($current -eq $platform -and $line -match '^\s+recording:\s*(\S+)\s*$') { return $Matches[1] }
  }
  return $null
}

if (-not $failed) {
  $trail = Get-ShowcaseTrail 'web'
  if (-not $trail) {
    Write-Host 'ERROR: could not resolve the web showcase trail from docs/showcase-trails.yml'
    $failed = $true
  } else {
    Write-Host "Web showcase trail (from docs/showcase-trails.yml): $trail"
    trailblaze trail $trail
    if ($LASTEXITCODE -ne 0) { $failed = $true }
  }
}

Write-Host '========================================='
Write-Host "Test execution completed (failed: $failed)"
Write-Host '========================================='

trailblaze status
trailblaze stop

$localLogs = Join-Path $stateDir 'logs'
if (Test-Path $localLogs) {
  Write-Host "Copying logs from $localLogs to $logsDir..."
  Get-ChildItem -Recurse -File $localLogs | Select-Object -ExpandProperty FullName
  Copy-Item -Recurse -Force (Join-Path $localLogs '*') $logsDir
} else {
  Write-Host "Directory $localLogs does not exist"
}

if ($failed) {
  Write-Host 'Tests failed - exiting with code 1'
  exit 1
}
exit 0
