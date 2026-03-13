/**
 * Computes git-based version using the same logic as CI.
 * Format: YYYYMMDD.HHMMSS (without git hash for macOS package compatibility)
 *
 * Makes available via:
 * - rootProject.extra["gitVersion"] - Numeric timestamp for packageVersion
 * - rootProject.extra["gitHash"] - Short commit hash
 * - rootProject.extra["gitVersionFull"] - Full version with hash (e.g., 20251125.133303.a1b2c3d)
 * - rootProject.extra["gitTagVersion"] - Semver from v* tag on HEAD (e.g., 1.2.3), or ""
 *
 * When HEAD has a v* release tag, project.version is overridden for all projects
 * so that Maven publish coordinates use the semver (e.g., 0.0.5) instead of the
 * default from gradle.properties (0.0.1-SNAPSHOT).
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

// Detect release version from a git tag (e.g. v1.2.3 → 1.2.3).
// Returns the semver string when HEAD is tagged, empty string otherwise.
val gitTagVersion: String by lazy {
  try {
    providers.exec {
      commandLine("sh", "-c", "git describe --tags --exact-match --match 'v*' 2>/dev/null || true")
    }.standardOutput.asText.get().trim().let { tag ->
      if (tag.startsWith("v")) tag.removePrefix("v") else ""
    }
  } catch (_: Exception) {
    ""
  }
}

// Make them available to all projects
rootProject.extra["gitVersion"] = gitVersion
rootProject.extra["gitHash"] = gitHash
rootProject.extra["gitVersionFull"] = gitVersionFull
rootProject.extra["gitTagVersion"] = gitTagVersion

// When HEAD is on a release tag, override project.version for all projects so that
// Maven coordinates (publish) and any other version-dependent tasks use the semver
// from the tag instead of the default from gradle.properties.
if (gitTagVersion.isNotEmpty()) {
  rootProject.allprojects {
    version = gitTagVersion
  }
}
