package xyz.block.trailblaze.config

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.util.Console

/**
 * A repository that is the home of one or more targets — the place to go for its trailmap, its
 * trails, or both.
 *
 * A target's trailmap can live in a different repo than the Trailblaze binary that runs it, and its
 * trails almost always do. Without a record of that, an installation carrying neither can only say
 * "target not found, here are the ones I have" — which reads as "pick a different target" and sends
 * people down the wrong path. These records turn that into "it lives here, go get it."
 *
 * Declarative and inert: nothing here is fetched, cloned, or resolved. A record can only ever go
 * stale, never resolve the wrong thing. Whether a fetch mechanism ever exists is a separate
 * decision, and no consumer of this data assumes one.
 */
@Serializable
data class KnownTargetWorkspace(
  /** Clone URL, e.g. `git@github.com:example-org/app-trails.git`. */
  val repo: String,
  /** Optional browse URL for UIs that want a link. */
  val url: String? = null,
  /** One line naming what the repo holds, shown alongside it in listings. */
  val description: String? = null,
  /** Target ids this repo is the home of. */
  val targets: List<String> = emptyList(),
) {
  /**
   * `example-org/app-trails` — the short form every user-facing message uses, derived from [repo] so
   * a record can't disagree with itself.
   *
   * Handles SSH (`git@host:org/repo.git`) and HTTPS (`https://host/org/repo`) spellings, and keeps
   * the FULL path after the host so a nested group (`host:org/group/repo`) isn't truncated to its
   * last two segments — a truncated org silently points people at a repo that doesn't exist under
   * the name shown. The host segment is dropped only when it looks like one, so a record written as
   * a bare `org/repo` survives.
   *
   * Blank for a degenerate value like `/` or `git@host:`; [KnownTargetWorkspaceLoader] rejects those
   * records rather than rendering "lives in " with nothing after it.
   */
  val shortName: String
    get() = shortNameForRepo(repo)

  /** The directory a clone lands in — `app-trails`, the second half of [shortName]. */
  val directoryName: String get() = shortName.substringAfterLast('/')

  /** The command that gets someone from "not found" to a working workspace. */
  val cloneCommand: String get() = "git clone $repo && cd $directoryName"

  companion object {
    /**
     * The `org/repo` short form of any clone-URL spelling — the [shortName] parsing, exposed so a
     * git remote URL read off a live checkout normalizes identically to a registered [repo] value
     * and the two can be compared. One parser for both sides means an SSH-spelled record can never
     * mismatch an HTTPS-spelled remote (or vice versa) for the same repository.
     */
    fun shortNameForRepo(repo: String): String {
      val trimmed = repo.trim().trimEnd('/').removeSuffix(".git").trimEnd('/')
      val path = when {
        // A scheme makes the shape unambiguous: everything up to the first `/` is the authority
        // (`user@host`, `host:port`) and the rest is the repo path. Parsed structurally rather than
        // by guessing which segment is a host, so a single-label host (`https://git/org/repo`)
        // yields the same `org/repo` as its SSH spelling instead of keeping `git` as an org.
        trimmed.contains("://") ->
          trimmed.substringAfter("://").substringAfter('/', missingDelimiterValue = "")
        // scp-like `[user@]host:path` — everything after the `host:` separator is the repo path.
        trimmed.contains(':') -> trimmed.substringAfter(':')
        // No scheme and no separator: either a bare `org/repo` or a `host/org/repo`. Genuinely
        // ambiguous, so this is the one branch that has to guess — drop the first segment only when
        // it reads as a hostname.
        else -> {
          val segments = trimmed.substringAfter('@').split('/').filter { it.isNotBlank() }
          val looksLikeHost = segments.firstOrNull()
            ?.let { it.contains('.') || it == "localhost" } == true
          if (looksLikeHost) segments.drop(1).joinToString("/") else segments.joinToString("/")
        }
      }
      return path.split('/').filter { it.isNotBlank() }.joinToString("/")
    }
  }
}

/**
 * A YAML file's worth of [KnownTargetWorkspace] records. Lets one file inline several repos under
 * `workspaces:` instead of forcing a file per repo, so a module declaring a handful of them reads as
 * one reviewable list.
 */
@Serializable
data class KnownTargetWorkspaceFile(
  val workspaces: List<KnownTargetWorkspace> = emptyList(),
)

/**
 * Discovers [KnownTargetWorkspace] records from every YAML file in the
 * `trails/config/known-target-workspaces` resource directory. A file may hold one bare record or a
 * `workspaces:` list; both shapes are read, and all files are merged.
 *
 * Same classpath-discovery mechanism as [AppTargetYamlLoader], for the same reason: the framework
 * defines the shape and any module on the classpath — or a workspace — contributes the contents.
 *
 * A DIRECTORY rather than one fixed filename is load-bearing: [ConfigResourceSource] discovery keys
 * on resource path, and layered sources fold with last-source-wins, so a workspace shipping its own
 * file at the same path as a bundled one would silently REPLACE every bundled record instead of
 * adding to it. Distinct filenames merge. Splitting one file per record, on the other hand, was never
 * load-bearing — hence the list shape.
 */
object KnownTargetWorkspaceLoader {

  /** Cached classpath discovery. Volatile for thread-safe lazy init, mirroring the sibling loaders. */
  @Volatile private var cached: List<KnownTargetWorkspace>? = null

  /**
   * All declared workspace records, or an empty list when none are on the classpath — an
   * installation that ships no registry (every OSS install today) must behave exactly as it does
   * now, so every consumer treats "no records" as "say nothing extra".
   */
  fun discover(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): List<KnownTargetWorkspace> {
    if (resourceSource === ClasspathConfigResourceSource) {
      cached?.let { return it }
    }
    // Guarded like the sibling loaders (AppTargetYamlLoader, ToolSetYamlLoader): a discovery failure
    // degrades to "no records". Callers build these hints INSIDE error paths — one appends to an
    // exception message being thrown — so a throw from here would replace a precise "unknown target"
    // failure with an unrelated one.
    val contents = try {
      resourceSource.discoverAndLoad(
        directoryPath = TrailblazeConfigPaths.KNOWN_TARGET_WORKSPACES_DIR,
        suffix = ".yaml",
      )
    } catch (e: Exception) {
      Console.log(
        "Warning: Failed to discover known target workspaces: ${e::class.simpleName}: ${e.message}",
      )
      emptyMap()
    }
    return loadAllYamlWithErrorHandling(contents, "Known target workspace") { _, content ->
      decodeRecords(content)
    }
      .flatten()
      // A record whose repo doesn't yield an `org/repo` shape can only render "lives in " and
      // `git clone `. Drop it, but say so: silently vanishing is how a typo'd pointer becomes a
      // target that reports itself as simply unknown.
      .filter { record ->
        val usable = record.shortName.isNotBlank()
        if (!usable) {
          Console.log(
            "Warning: Ignoring known-target-workspace record with unusable repo '${record.repo}' " +
              "(expected an `org/repo` clone URL); targets ${record.targets} will report as unknown.",
          )
        }
        usable
      }
      .onEach { record ->
        // `strictMode = false` means a misspelled key (`target:` for `targets:`) parses into an
        // empty list, leaving an inert record that points nobody anywhere.
        if (record.targets.isEmpty()) {
          Console.log(
            "Warning: known-target-workspace record for '${record.shortName}' declares no targets; " +
              "check for a misspelled `targets:` key.",
          )
        }
      }
      .sortedBy { it.shortName }
      .also {
        if (resourceSource === ClasspathConfigResourceSource) {
          cached = it
        }
      }
  }

  /**
   * Reads one file's records, accepting either shape.
   *
   * Tries the `workspaces:` list first: `repo` has no default, so decoding a list-shaped file as a
   * bare record throws on the missing field, while `strictMode = false` makes the reverse harmless
   * (a bare record decodes to an empty list, which is the signal to retry as a single record).
   */
  private fun decodeRecords(content: String): List<KnownTargetWorkspace> {
    val asFile = TrailblazeConfigYaml.instance
      .decodeFromString(KnownTargetWorkspaceFile.serializer(), content)
    if (asFile.workspaces.isNotEmpty()) return asFile.workspaces
    return listOf(
      TrailblazeConfigYaml.instance.decodeFromString(KnownTargetWorkspace.serializer(), content),
    )
  }

  /**
   * The workspace that homes [targetId], or null when nothing declares it. Case-insensitive to
   * match how every other target-id surface resolves (ids widened to lowerCamelCase in 2026-05).
   */
  fun workspaceFor(
    targetId: String,
    workspaces: List<KnownTargetWorkspace> = discover(),
  ): KnownTargetWorkspace? = workspaces.firstOrNull { workspace ->
    workspace.targets.any { it.equals(targetId, ignoreCase = true) }
  }
}
