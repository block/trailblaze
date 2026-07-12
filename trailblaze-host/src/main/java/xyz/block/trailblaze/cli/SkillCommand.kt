package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.util.Console
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Read-side of the agent skill bundled into the CLI JAR (staged by the
 * `copyAgentSkillResources` task in this module's build file from `skills/trailblaze/`).
 *
 * The skill is a directory of markdown files (`SKILL.md`, `SETUP.md`, the `references/` docs)
 * that teaches a coding agent how to drive this CLI. JAR classpath "directories" can't be
 * enumerated at runtime, so the build stages a `manifest.txt` (one relative path per line)
 * alongside the files; [files] reads it and [readFile] only serves manifest-listed paths.
 *
 * The skill is versioned with the CLI binary — that's the single source of truth. `skill show`
 * always matches the running binary (zero drift); an installed copy is a snapshot that [installTo]
 * stamps with [version] so [installedVersionAt] can later detect when it's fallen behind the CLI.
 */
internal object BundledAgentSkill {
  const val SKILL_NAME = "trailblaze"

  /**
   * File written into an install directory recording the CLI [version] that produced it.
   * Not a shipped skill file (absent from the manifest) — it's install metadata the
   * staleness check reads back. Dot-prefixed so it stays out of the agent's way.
   */
  const val VERSION_STAMP_FILE = ".trailblaze-skill-version"

  private const val RESOURCE_PREFIX = "/xyz/block/trailblaze/skill"

  /** CLI version this binary was built at (e.g. `2026.07.12`), or `Developer Build` from a raw checkout. */
  val version: String get() = TrailblazeVersion.version

  /** False for a `Developer Build` (no release version) — staleness can't be judged, so we don't nudge. */
  val isVersioned: Boolean get() = version != "Developer Build"

  /**
   * Manifest parsed once — resources are fixed at build time, so re-reading per call only wastes
   * work. `by lazy` also means a corrupt/absent manifest is diagnosed on first touch, not per call.
   */
  private val manifest: List<String> by lazy {
    readResource("manifest.txt")
      ?.lineSequence()
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.toList()
      ?: emptyList()
  }

  /** Relative paths of the bundled skill files, e.g. `SKILL.md`, `references/drive-device.md`. */
  fun files(): List<String> = manifest

  /**
   * Content of one bundled skill file, or `null` if [relativePath] isn't in the manifest (a user
   * asking for a file that doesn't exist — MISUSE). A path that IS in the manifest but whose
   * resource is missing is a corrupt build, not user error: it throws [IllegalStateException] so
   * callers surface it as INFRA rather than silently degrading to "unknown file".
   */
  fun readFile(relativePath: String): String? =
    if (relativePath in manifest) {
      readResource(relativePath)
        ?: throw IllegalStateException(
          "bundled skill file '$relativePath' is in the manifest but missing from the JAR",
        )
    } else {
      null
    }

  /**
   * Write every bundled file plus the [VERSION_STAMP_FILE] into [dest]. Overwrites the shipped
   * files (install doubles as upgrade) but leaves user-added files under [dest] alone.
   * Throws [IllegalStateException] if the manifest is empty or a bundled path escapes [dest]
   * (a corrupt-build guard, not user error).
   */
  fun installTo(dest: Path) {
    val files = files()
    check(files.isNotEmpty()) { "this build has no bundled agent skill (missing JAR resources)" }
    dest.createDirectories()
    for (relativePath in files) {
      val target = dest.resolve(relativePath).normalize()
      check(target.startsWith(dest)) { "bundled path '$relativePath' escapes the destination directory" }
      target.parent?.createDirectories()
      target.writeText(readFile(relativePath)!!)
    }
    dest.resolve(VERSION_STAMP_FILE).writeText(version + "\n")
  }

  /** True if a skill copy exists at [dir] (its `SKILL.md` is present). */
  fun isInstalledAt(dir: Path): Boolean = dir.resolve("SKILL.md").exists()

  /** CLI version recorded by a prior [installTo] into [dir], or `null` if unstamped / not installed. */
  fun installedVersionAt(dir: Path): String? {
    val stamp = dir.resolve(VERSION_STAMP_FILE)
    if (!stamp.exists()) return null
    return runCatching { stamp.readText().trim() }.getOrNull()?.ifEmpty { null }
  }

  private fun readResource(relativePath: String): String? =
    BundledAgentSkill::class.java.getResourceAsStream("$RESOURCE_PREFIX/$relativePath")
      ?.bufferedReader(Charsets.UTF_8)
      ?.use { it.readText() }
}

/**
 * Where the skill installs. `SKILL.md` (YAML frontmatter + `references/`, progressive disclosure) is
 * an open standard, so the same files install unchanged for every agent — only the directory differs,
 * and it collapses to two buckets: Claude Code reads `.claude/skills/`, while Codex, Cursor, Gemini
 * CLI, Goose, and other standard-compliant agents all read the vendor-neutral `.agents/skills/`.
 * `--agent codex|cursor|goose|gemini|…` are friendly aliases that resolve to [AGENTS].
 */
internal enum class SkillTarget(
  /** Stable id — also an accepted `--agent` value and the key `skill status` reports under. */
  val id: String,
  /** Which agents read this location, for status output and post-install messaging. */
  val label: String,
  /** Path components below a project root or home dir — the only thing that differs between buckets. */
  private val relativeParts: List<String>,
) {
  CLAUDE("claude", "Claude Code", listOf(".claude", "skills", "trailblaze")),
  AGENTS(
    "agents",
    "Codex, Cursor, Gemini CLI, Goose, and other SKILL.md agents",
    listOf(".agents", "skills", "trailblaze"),
  ),
  ;

  private fun dirUnder(base: Path): Path = relativeParts.fold(base) { acc, p -> acc.resolve(p) }.normalize()

  /** Skill directory relative to a project root (resolved against the working directory). */
  fun projectDir(base: Path): Path = dirUnder(base)

  /** Skill directory under the user's home directory (`--global`). */
  fun globalDir(home: Path): Path = dirUnder(home)

  companion object {
    /**
     * `--agent` values → target bucket. The vendor names are aliases: every non-Claude agent reads
     * the shared `.agents/skills/` directory, so one install covers all of them.
     */
    private val ALIASES: Map<String, SkillTarget> = mapOf(
      "claude" to CLAUDE,
      "agents" to AGENTS,
      "standard" to AGENTS,
      "codex" to AGENTS,
      "cursor" to AGENTS,
      "gemini" to AGENTS,
      "goose" to AGENTS,
      "opencode" to AGENTS,
    )

    fun fromCliName(name: String): SkillTarget? = ALIASES[name.trim().lowercase()]

    /** Accepted `--agent` values, for help text and shell completion. */
    val agentNames: List<String> = ALIASES.keys.toList()
  }
}

/** Shell-completion candidates for `--agent` (the accepted agent names). */
internal class SkillAgentCandidates : Iterable<String> {
  override fun iterator(): Iterator<String> = SkillTarget.agentNames.iterator()
}

/** One resolved install destination — a [target] bucket (null for an explicit `--dir`) and its directory. */
internal data class SkillInstallTarget(val target: SkillTarget?, val dir: Path)

/**
 * Pure resolution of `skill install` flags to destination directories, kept side-effect-free so it
 * can be unit-tested with plain paths (no filesystem, no CWD dependence). The command validates flag
 * conflicts before calling; this assumes a legal combination.
 */
internal object SkillInstallPlanner {
  fun resolve(
    target: SkillTarget?,
    all: Boolean,
    global: Boolean,
    explicitDir: Path?,
    baseDir: Path,
    homeDir: Path,
  ): List<SkillInstallTarget> {
    if (explicitDir != null) return listOf(SkillInstallTarget(target = null, dir = explicitDir.normalize()))
    val targets = if (all) SkillTarget.entries.toList() else listOf(target ?: SkillTarget.CLAUDE)
    return targets.map { t ->
      SkillInstallTarget(t, if (global) t.globalDir(homeDir) else t.projectDir(baseDir))
    }
  }
}

/** State of an installed skill copy relative to the running CLI. */
internal enum class SkillVersionState { NOT_INSTALLED, NOT_STAMPED, CURRENT, STALE }

/** Pure staleness classification — no filesystem access, so it unit-tests with plain strings. */
internal object SkillVersionStatus {
  fun classify(
    present: Boolean,
    installedVersion: String?,
    currentVersion: String,
    currentIsVersioned: Boolean,
  ): SkillVersionState = when {
    !present -> SkillVersionState.NOT_INSTALLED
    // A Developer Build has no release version to compare against — never nag.
    !currentIsVersioned -> SkillVersionState.CURRENT
    installedVersion == null -> SkillVersionState.NOT_STAMPED
    installedVersion == currentVersion -> SkillVersionState.CURRENT
    else -> SkillVersionState.STALE
  }
}

/** An installed copy discovered by scanning the known skill directories. */
internal data class DetectedInstall(
  val target: SkillTarget,
  val dir: Path,
  val global: Boolean,
  val installedVersion: String?,
  val state: SkillVersionState,
)

/**
 * Scan each target's project and global directory for an installed skill copy and classify each
 * against the running CLI. Cheap `exists()` stat calls; used by both the bare `skill` nudge and
 * `skill status`.
 *
 * [currentVersion] / [currentIsVersioned] default to the running binary's; they're parameters so a
 * test can drive the STALE / NOT_STAMPED classification without needing a release-versioned build
 * (a `Developer Build` always classifies CURRENT).
 */
internal fun detectSkillInstalls(
  baseDir: Path,
  homeDir: Path,
  currentVersion: String = BundledAgentSkill.version,
  currentIsVersioned: Boolean = BundledAgentSkill.isVersioned,
): List<DetectedInstall> =
  SkillTarget.entries.flatMap { target ->
    listOf(target.projectDir(baseDir) to false, target.globalDir(homeDir) to true).mapNotNull { (dir, global) ->
      if (!BundledAgentSkill.isInstalledAt(dir)) {
        null
      } else {
        val installed = BundledAgentSkill.installedVersionAt(dir)
        DetectedInstall(
          target = target,
          dir = dir,
          global = global,
          installedVersion = installed,
          state = SkillVersionStatus.classify(
            present = true,
            installedVersion = installed,
            currentVersion = currentVersion,
            currentIsVersioned = currentIsVersioned,
          ),
        )
      }
    }
  }

/** Shared "the JAR is missing its skill resources" failure — a packaging bug, not user error. */
private fun reportMissingSkillResources(verb: String): Int {
  reportCliError(
    verb = verb,
    reason = "this build has no bundled agent skill (missing JAR resources)",
    hint = "reinstall the CLI, or read the skill online: https://github.com/block/trailblaze/tree/main/skills/trailblaze",
  )
  return TrailblazeExitCode.INFRA_FAILED.code
}

@Command(
  name = "skill",
  mixinStandardHelpOptions = true,
  description = ["Print or install the bundled agent skill that teaches a coding agent this CLI"],
  subcommands = [
    SkillShowCommand::class,
    SkillInstallCommand::class,
    SkillStatusCommand::class,
  ],
)
class SkillCommand : Callable<Int> {

  /** Overridable in tests so the stale-nudge scan can be driven without touching the real CWD/home. */
  internal var baseDir: Path = Path.of("").toAbsolutePath()
  internal var homeDir: Path = homeDir()
  internal var currentVersion: String = BundledAgentSkill.version
  internal var currentIsVersioned: Boolean = BundledAgentSkill.isVersioned

  override fun call(): Int {
    val files = BundledAgentSkill.files()
    if (files.isEmpty()) return reportMissingSkillResources("Skill")

    // Raw `println` (not Console.log) — this index is the command's output, so it must
    // reach stdout even in quiet mode.
    println(
      """
      |Trailblaze ships an agent skill — markdown instructions that teach a coding
      |agent (Claude Code, Codex, Cursor, Gemini CLI, Goose, ...) how to drive this
      |CLI, in the portable SKILL.md format they all read. It's versioned with this
      |binary (${BundledAgentSkill.version}), so `skill show` always matches the CLI
      |you're running.
      |
      |Bundled files:
      |${files.joinToString("\n") { "  $it" }}
      |
      |Print to stdout (agents: load SKILL.md into context, then references on demand):
      |  trailblaze skill show
      |  trailblaze skill show references/drive-device.md
      |
      |Install a copy your agent auto-discovers. Two locations cover the field:
      |  trailblaze skill install                 (Claude Code: .claude/skills/trailblaze/)
      |  trailblaze skill install --agent agents  (.agents/skills/trailblaze/ — Codex,
      |                                            Cursor, Gemini CLI, Goose, ...)
      |  trailblaze skill install --all           (both of the above)
      |  trailblaze skill install --global        (under your home dir, not this project)
      |  trailblaze skill install --dir <path>    (any other layout)
      |
      |(--agent also accepts codex/cursor/gemini/goose as aliases for the shared
      |.agents/skills location.)
      |
      |Check installed copies against this CLI:
      |  trailblaze skill status
      |
      |Docs: https://block.github.io/trailblaze
      """.trimMargin(),
    )

    val stale = runCatching {
      detectSkillInstalls(baseDir, homeDir, currentVersion, currentIsVersioned)
        .filter { it.state == SkillVersionState.STALE || it.state == SkillVersionState.NOT_STAMPED }
    }.getOrDefault(emptyList())
    if (stale.isNotEmpty()) {
      println()
      println("⚠ ${stale.size} installed skill copy(ies) don't match this CLI — run `trailblaze skill status`.")
    }
    return TrailblazeExitCode.SUCCESS.code
  }
}

@Command(
  name = "show",
  mixinStandardHelpOptions = true,
  description = ["Print a bundled skill file to stdout (defaults to SKILL.md)"],
)
class SkillShowCommand : Callable<Int> {

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<file>",
    description = ["Bundled file to print, e.g. references/drive-device.md. Run `trailblaze skill` for the list."],
  )
  var file: String = "SKILL.md"

  override fun call(): Int {
    val content = try {
      BundledAgentSkill.readFile(file)
    } catch (_: IllegalStateException) {
      // The file is in the manifest but its resource is absent — a corrupt build, not user error.
      return reportMissingSkillResources("Skill show")
    }
    if (content == null) {
      val known = BundledAgentSkill.files()
      if (known.isEmpty()) return reportMissingSkillResources("Skill show")
      reportCliError(
        verb = "Skill show",
        target = file,
        reason = "not a bundled skill file",
        hint = "bundled files: ${known.joinToString(", ")}",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    // Raw print (not Console.log): the file content is the command's output and an agent
    // may pipe it, so it must reach stdout verbatim even in quiet mode.
    print(content)
    if (!content.endsWith("\n")) println()
    return TrailblazeExitCode.SUCCESS.code
  }
}

@Command(
  name = "install",
  mixinStandardHelpOptions = true,
  description = ["Write the bundled skill into an agent's skill directory (defaults to Claude Code's)"],
)
class SkillInstallCommand : Callable<Int> {

  @Option(
    names = ["--agent"],
    paramLabel = "<agent>",
    completionCandidates = SkillAgentCandidates::class,
    description = [
      "Target agent: claude (.claude/skills) or agents (.agents/skills, shared by " +
        "Codex, Cursor, Gemini CLI, Goose, ...). codex/cursor/gemini/goose are aliases " +
        "for agents. Defaults to claude.",
    ],
  )
  var agentName: String? = null

  @Option(
    names = ["--all"],
    description = ["Install into both locations (.claude/skills and .agents/skills) in one go."],
  )
  var all: Boolean = false

  @Option(
    names = ["--global"],
    description = ["Install under your home directory instead of the current project."],
  )
  var global: Boolean = false

  @Option(
    names = ["--dir"],
    paramLabel = "<dir>",
    description = ["Explicit destination directory. Mutually exclusive with --agent/--all/--global."],
  )
  var dir: Path? = null

  /** Overridable in tests to keep filesystem writes out of the working tree. */
  internal var baseDir: Path = Path.of("").toAbsolutePath()
  internal var homeDir: Path = homeDir()

  override fun call(): Int {
    val files = BundledAgentSkill.files()
    if (files.isEmpty()) return reportMissingSkillResources("Skill install")

    if (dir != null && (agentName != null || all || global)) {
      reportCliError(
        verb = "Skill install",
        reason = "--dir can't be combined with --agent, --all, or --global",
        hint = "use --dir <path> for a custom location, or --agent/--all/--global for a known agent layout",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    if (all && agentName != null) {
      reportCliError(
        verb = "Skill install",
        reason = "--all can't be combined with --agent",
        hint = "drop one: --all installs into both locations, --agent <name> installs into one",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    val target = agentName?.let { name ->
      SkillTarget.fromCliName(name) ?: run {
        reportCliError(
          verb = "Skill install",
          target = name,
          reason = "unknown agent",
          hint = "accepted values: ${SkillTarget.agentNames.joinToString(", ")}",
        )
        return TrailblazeExitCode.MISUSE.code
      }
    }

    val destinations = SkillInstallPlanner.resolve(
      target = target,
      all = all,
      global = global,
      explicitDir = dir?.toAbsolutePath(),
      baseDir = baseDir,
      homeDir = homeDir,
    )

    val installed = mutableListOf<Path>()
    for (destination in destinations) {
      try {
        BundledAgentSkill.installTo(destination.dir)
      } catch (e: IllegalStateException) {
        // Corrupt build: empty manifest or a bundled path escaping the destination.
        reportPartialInstall(installed)
        reportCliError(
          verb = "Skill install",
          target = destination.dir.toString(),
          reason = e.message ?: "failed to write the skill",
          hint = "reinstall the CLI if this build's skill manifest is corrupt",
        )
        return TrailblazeExitCode.INFRA_FAILED.code
      } catch (e: IOException) {
        // The filesystem refused the write — permission denied, read-only location, or disk full.
        // This is the common real-world failure (especially --global), so it gets the same
        // structured envelope as the corrupt-build case rather than a bare unhandled trace.
        reportPartialInstall(installed)
        reportCliError(
          verb = "Skill install",
          target = destination.dir.toString(),
          reason = e.message ?: "could not write into the destination directory",
          hint = "check that the directory is writable, or pass --dir <path> to a location you own",
        )
        return TrailblazeExitCode.INFRA_FAILED.code
      }
      installed.add(destination.dir)
      Console.info(
        "Installed the '${BundledAgentSkill.SKILL_NAME}' skill " +
          "(${files.size} files, ${BundledAgentSkill.version}) into ${destination.dir}",
      )
      destination.target?.let { Console.info("  Read by: ${it.label}") }
    }
    return TrailblazeExitCode.SUCCESS.code
  }

  /** On a multi-target (`--all`) failure, name what already landed so a half-done install isn't silent. */
  private fun reportPartialInstall(done: List<Path>) {
    if (done.isNotEmpty()) {
      Console.info("Note: the skill was already installed into ${done.joinToString(", ")} before this failure.")
    }
  }
}

@Command(
  name = "status",
  mixinStandardHelpOptions = true,
  description = ["Report installed skill copies and whether they match this CLI"],
)
class SkillStatusCommand : Callable<Int> {

  /** Overridable in tests. */
  internal var baseDir: Path = Path.of("").toAbsolutePath()
  internal var homeDir: Path = homeDir()
  internal var currentVersion: String = BundledAgentSkill.version
  internal var currentIsVersioned: Boolean = BundledAgentSkill.isVersioned

  override fun call(): Int {
    val installs = detectSkillInstalls(baseDir, homeDir, currentVersion, currentIsVersioned)
    println("Bundled skill version (this CLI): $currentVersion")
    if (installs.isEmpty()) {
      println("No installed copies found in the known skill directories.")
      println("Install one with: trailblaze skill install [--agent agents] [--all] [--global]")
      return TrailblazeExitCode.SUCCESS.code
    }
    println()
    var anyStale = false
    for (install in installs) {
      val marker = when (install.state) {
        SkillVersionState.CURRENT -> "✓ up to date"
        SkillVersionState.STALE -> "✗ stale (installed ${install.installedVersion})".also { anyStale = true }
        SkillVersionState.NOT_STAMPED -> "? unstamped (pre-versioning install)".also { anyStale = true }
        SkillVersionState.NOT_INSTALLED -> "not installed"
      }
      val scope = if (install.global) "global" else "project"
      println("  ${install.dir} ($scope — ${install.target.label})")
      println("      $marker")
    }
    if (anyStale) {
      println()
      println("Refresh a stale copy by re-running its install, e.g. `trailblaze skill install` (doubles as upgrade).")
      println("Or skip the committed copy entirely: agents can `trailblaze skill show`, which always matches this CLI.")
    }
    return TrailblazeExitCode.SUCCESS.code
  }
}

/** The user's home directory, as a [Path]. Isolated so the version-status commands read it once. */
private fun homeDir(): Path = Path.of(System.getProperty("user.home") ?: "")
