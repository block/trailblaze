/**
 * Computes git-based version using the same logic as CI.
 * Format: YYYYMMDD.HHMMSS (without git hash for macOS package compatibility)
 *
 * Makes available via:
 * - rootProject.extra["gitVersion"] - Numeric timestamp for packageVersion
 * - rootProject.extra["gitHash"] - Short commit hash
 * - rootProject.extra["gitVersionFull"] - Full version with hash (e.g., 20251125.133303.a1b2c3d)
 */

// Compute git-based version (same as CI script, but without hash for macOS compatibility)
val gitVersion: String by lazy {
  providers.exec {
    commandLine("sh", "-c", "TZ=UTC0 git log -1 --date=local --pretty=\"format:%cd\" --date=\"format-local:%Y%m%d.%H%M%S\"")
  }.standardOutput.asText.get().trim()
}

// Compute git hash separately
val gitHash: String by lazy {
  providers.exec {
    commandLine("git", "log", "-1", "--pretty=format:%h")
  }.standardOutput.asText.get().trim()
}

// Full version with hash (same as CI)
val gitVersionFull: String by lazy {
  "$gitVersion.$gitHash"
}

// Make them available to all projects
rootProject.extra["gitVersion"] = gitVersion
rootProject.extra["gitHash"] = gitHash
rootProject.extra["gitVersionFull"] = gitVersionFull
