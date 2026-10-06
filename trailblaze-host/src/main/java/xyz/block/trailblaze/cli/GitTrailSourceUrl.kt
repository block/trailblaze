package xyz.block.trailblaze.cli

import java.io.File
import xyz.block.trailblaze.report.gitHubTrailSourceUrl
import xyz.block.trailblaze.usages.GitRefTree

internal object GitTrailSourceUrl {
  fun capture(
    file: File,
    originalYaml: String,
    envReader: (String) -> String? = System::getenv,
  ): String? {
    // Only CI's existing Git-source selection supplies provenance; arbitrary local runs stay optional.
    if (envReader("TRAIL_FILES").isNullOrBlank()) return null
    val sourceFile = file.canonicalFile
    val root = GitRefTree.gitRootOf(sourceFile.parentFile) ?: return null
    val path = root.toPath().relativize(sourceFile.toPath()).toString().replace('\\', '/')
    val commit = GitRefTree.resolveCommit(root, "HEAD") ?: return null
    val remote = GitRefTree.runGit(root, "remote", "get-url", "origin").getOrNull()?.trim() ?: return null
    val url = gitHubTrailSourceUrl(remote, commit, path) ?: return null
    val requestedRepo = envReader("TRAIL_SOURCE_REPO")?.takeIf { it.isNotBlank() }
    if (requestedRepo != null && gitHubTrailSourceUrl(requestedRepo, commit, path) != url) return null
    val requestedCommit = envReader("TRAIL_SOURCE_REF")
    if (requestedCommit != null && Regex("[0-9a-f]{40}").matches(requestedCommit) && requestedCommit != commit) return null
    val committedYaml = GitRefTree.runGit(root, "show", "$commit:$path").getOrNull() ?: return null
    return url.takeIf { committedYaml == originalYaml }
  }
}
