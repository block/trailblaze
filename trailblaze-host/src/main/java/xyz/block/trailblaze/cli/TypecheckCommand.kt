package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Type-check pack TypeScript / JavaScript tool sources from the terminal.
 *
 * `trailblaze typecheck` is the canonical terminal-side equivalent of the IDE's "save and
 * watch for red squiggles" loop: it spawns the framework-bundled `tsc` (extracted to
 * `<workspace>/trails/.trailblaze/typecheck/typescript/`) against each pack's
 * framework-generated `tools/tsconfig.json` and surfaces the compiler's diagnostics
 * verbatim. The bundled tsc gives a deterministic pinned version with no per-pack
 * `bun install` step, which was the forcing function — `bun install` inside a pack
 * dir fails when the pack's transitive npm closure can't be resolved through
 * corporate npm mirrors in some environments.
 *
 * **CLI shape.**
 *  - `trailblaze typecheck` (no args, from inside a pack tree): walks up from the caller's
 *    cwd to the nearest pack root (`pack.yaml` sibling), then to the workspace root, and
 *    typechecks that one pack.
 *  - `trailblaze typecheck <pack-id>`: typechecks the named pack in the discovered
 *    workspace, by directory name under `trails/config/packs/`. The id is rejected
 *    when it contains a path separator or `..` segment — see [validatePackId].
 *  - `trailblaze typecheck --all`: typechecks every workspace pack with a generated
 *    `tools/tsconfig.json`.
 *
 * **Exit codes.** Aggregated across packs into three buckets so shell consumers can
 * distinguish failure modes. The specific tsc exit code (which varies across TypeScript
 * versions — 5.x emits `1`, 6.x emits `2` for the same diagnostics) is normalized to a
 * single `EXIT_TYPE_ERROR` so callers don't have to track tsc-version drift:
 *  - `0` — every pack passed (or no packs found).
 *  - `1` — at least one pack reported tsc-side type errors, or the spawn failed.
 *  - `2` — usage error (missing workspace, unknown / invalid pack id, missing JS
 *    runtime, missing `tools/tsconfig.json` because the user forgot `trailblaze
 *    compile` first, etc.).
 *  - `3` — operational error (framework JAR missing the bundled tsc payload, I/O
 *    failure during workspace setup).
 *
 * **Runtime.** Spawns `bun <tscJs> ...` if `bun` is on PATH, else falls back to
 * `node <tscJs> ...`. Either works — tsc is plain Node-compatible JS — and we prefer bun
 * because it's the existing runtime requirement for `esbuild` (the daemon's scripted-tool
 * bundler), so a developer who can run a scripted tool already has bun installed.
 * PATH probing is `PATHEXT`-aware on Windows via [CliPathUtils.isCommandOnPath].
 *
 * **Bundled tsc not shipped?** The framework JAR's `processResources` step copies
 * `typescript@6.0.3`'s `_tsc.js` + `lib.*.d.ts` into the JAR. If a developer builds the
 * framework on a checkout without first running `bun install` in the SDK package, the
 * payload is empty and this command emits one actionable error rather than running with
 * a broken extraction. Pre-built distributions always ship the payload (CI runs
 * `bun install` before any Gradle task).
 *
 * **On-disk cache.** The bundled tsc lives at
 * `<workspace>/trails/.trailblaze/typecheck/typescript/` after first run (~6 MB). This
 * directory is a regeneratable cache — `rm -rf` it any time to force re-extraction on
 * the next `trailblaze typecheck` call. The framework prunes stale files on
 * version upgrades automatically (see [WorkspaceTypeScriptSetup.extractTypecheck]); the
 * manual cleanup is just for "I want a fresh extraction now" debugging.
 *
 * **Per-pack timeout.** Each `tsc` invocation is bounded by a 5-minute default that
 * catches infinite-include-glob misconfigurations without ever interrupting a real
 * type-check. Override via the `TRAILBLAZE_TYPECHECK_TIMEOUT_MS` env var (milliseconds)
 * for codebases with known-slow cyclic types — values below the 1-minute floor are
 * clamped up to keep `waitFor` semantically sane.
 *
 * **Out of scope.** Watch mode (`tsc --watch`) — the IDE owns the inner editing loop.
 * If anyone asks, the right shape is `trailblaze typecheck --watch` and that's a follow-up.
 *
 * **Discovery.** Workspace root is found via the shared [CliPathUtils.findWorkspaceRoot]
 * walk-up — the same primitive `trailblaze compile` uses — so the user can run this
 * from any subdirectory of a workspace, including a pack's own `tools/` dir.
 */
@Command(
  name = "typecheck",
  mixinStandardHelpOptions = true,
  description = ["Type-check pack TypeScript / JavaScript sources via the bundled tsc"],
)
class TypecheckCommand : Callable<Int> {

  /**
   * Label spliced into every user-facing `trailblaze <label>:` message this command emits.
   * Defaults to `typecheck` for direct invocation; [CheckCommand] sets it to `check` when
   * delegating so the user sees one consistent CLI verb regardless of which inner command
   * is currently raising. Internal only — not a picocli option.
   */
  internal var commandLabel: String = "typecheck"

  @Parameters(
    arity = "0..1",
    paramLabel = "<pack-id>",
    description = [
      "Name of the pack to type-check (directory name under <workspace>/trails/config/packs/). " +
        "Omit when running from inside a pack tree — the command walks up to the nearest " +
        "pack root and uses that. Mutually exclusive with --all.",
    ],
  )
  var packId: String? = null

  @Option(
    names = ["--all"],
    description = [
      "Type-check every pack in the discovered workspace. Mutually exclusive with the " +
        "positional <pack-id>.",
    ],
  )
  var typecheckAll: Boolean = false

  override fun call(): Int {
    if (typecheckAll && packId != null) {
      Console.error("trailblaze $commandLabel: --all and <pack-id> are mutually exclusive.")
      return EXIT_USAGE
    }
    packId?.let { id ->
      validatePackId(id)?.let { reason ->
        Console.error("trailblaze $commandLabel: invalid pack id '$id' — $reason.")
        return EXIT_USAGE
      }
    }

    val callerCwd = CliCallerContext.callerCwd()
    val workspaceRoot = CliPathUtils.findWorkspaceRoot(callerCwd)
    if (workspaceRoot == null) {
      val startAbs = callerCwd.toAbsolutePath().normalize()
      Console.error(
        "trailblaze $commandLabel: not inside a Trailblaze workspace. Walked up from " +
          "$startAbs to the filesystem root and found no `trails/config/packs/` marker. " +
          "Run from inside a workspace (so the walk-up can find the root).",
      )
      return EXIT_USAGE
    }

    // Resolve the pack list BEFORE any framework-side setup: caller-input validation
    // (unknown pack id, no enclosing pack, missing packs dir) is a USAGE error and must
    // take precedence over operational signals like "framework JAR shipped without a
    // bundled tsc payload" (which is exit 3). Otherwise a unit-test JVM whose JAR has
    // no tsc payload would mask a legitimate "you typed the wrong pack name" with an
    // operational exit, and TypecheckCommandTest pins that ordering.
    val packs = resolvePacksToCheck(workspaceRoot = workspaceRoot.toFile(), callerCwd = callerCwd)
      ?: return EXIT_USAGE
    if (packs.isEmpty()) {
      Console.log("trailblaze $commandLabel: no packs to type-check.")
      return EXIT_OK
    }

    // [WorkspaceTypeScriptSetup.setUp] writes its outputs relative to a `trails/` directory
    // (same convention `CompileCommand` follows — see CompileCommand.generatorRoot). The
    // workspace root from the walk-up is the directory containing `trails/config/packs/`,
    // so descend one level into `trails/` before handing it off — otherwise setUp would
    // emit `.trailblaze/sdk/` one level above where pack tsconfigs reference it.
    val trailsRoot = workspaceRoot.resolve(TrailblazeConfigPaths.WORKSPACE_TRAILS_DIR)

    // Set up the workspace's SDK declaration bundle. Cheap idempotent no-ops if
    // `trailblaze compile` already ran. Setup failures are operational (filesystem /
    // resource issues), not user input — map to EXIT_OPERATIONAL_ERROR so shell
    // consumers can distinguish "your invocation was wrong" (USAGE) from "the
    // framework couldn't do its work" (OPERATIONAL).
    try {
      WorkspaceTypeScriptSetup.setUp(workspaceRoot = trailsRoot)
    } catch (e: Exception) {
      Console.error(
        "trailblaze $commandLabel: TypeScript workspace setup failed: ${e.message ?: e.javaClass.simpleName}",
      )
      return EXIT_OPERATIONAL_ERROR
    }

    // The tsc payload is opt-in (NOT part of setUp): only `typecheck` needs it, and we
    // don't want every `trailblaze compile` paying the 6 MB extraction cost.
    val tscJs = try {
      WorkspaceTypeScriptSetup.extractTypecheck(workspaceRoot = trailsRoot)
    } catch (e: Exception) {
      Console.error(
        "trailblaze $commandLabel: tsc extraction failed: ${e.message ?: e.javaClass.simpleName}",
      )
      return EXIT_OPERATIONAL_ERROR
    }
    if (tscJs == null) {
      Console.error(
        "trailblaze $commandLabel: the framework JAR did not ship a bundled tsc payload. " +
          "If you're running a framework built from source, run `bun install` in " +
          "`sdks/typescript/` and rebuild — pre-built distributions always " +
          "ship tsc.",
      )
      return EXIT_OPERATIONAL_ERROR
    }

    val jsRuntime = resolveJsRuntime()
    if (jsRuntime == null) {
      Console.error(
        "trailblaze $commandLabel: no JavaScript runtime found on PATH. Install `bun` " +
          "(recommended — same runtime esbuild uses) or `node`, then re-run.",
      )
      return EXIT_USAGE
    }

    // Run tsc once per pack and aggregate exit codes. We do NOT short-circuit on the
    // first failure — CI cares about the full picture, and `tsc`'s per-file diagnostics
    // are independent across packs anyway. Failure precedence: USAGE (missing tsconfig)
    // beats TYPE_ERROR (tsc found problems) — if any pack is missing its tsconfig the
    // process exits USAGE so the operator knows to run `trailblaze check` first.
    // A final summary names every failed pack so the operator doesn't have to scan
    // the full log on `--all`.
    var sawTypeError = false
    var sawMissingTsconfig = false
    val failedPacks = mutableListOf<String>()
    for (pack in packs) {
      val tsconfig = pack.resolve("tools").resolve("tsconfig.json")
      if (!Files.isRegularFile(tsconfig)) {
        Console.error(
          "trailblaze $commandLabel: pack '${pack.fileName}' has no tools/tsconfig.json. " +
            "Run `trailblaze check` first to emit framework-managed tsconfigs.",
        )
        sawMissingTsconfig = true
        failedPacks += pack.fileName.toString()
        continue
      }
      Console.log("── typecheck: ${pack.fileName} ────")
      val exit = runTsc(jsRuntime = jsRuntime, tscJs = tscJs, tsconfig = tsconfig)
      if (exit != 0) {
        // Normalize tsc's specific exit value (which drifted from `1` in 5.x to `2`
        // in 6.x for the same diagnostics) to a single [EXIT_TYPE_ERROR] so shell
        // consumers see a stable contract regardless of tsc version.
        sawTypeError = true
        failedPacks += pack.fileName.toString()
      }
    }
    if (failedPacks.isNotEmpty() && packs.size > 1) {
      // One-line summary on multi-pack runs so a CI consumer can grep the tail of the
      // log and see exactly which packs failed. Single-pack runs skip the summary —
      // the per-pack header already named the failing pack.
      Console.error("trailblaze $commandLabel: failed packs: ${failedPacks.joinToString(", ")}")
    }
    return when {
      sawMissingTsconfig -> EXIT_USAGE
      sawTypeError -> EXIT_TYPE_ERROR
      else -> EXIT_OK
    }
  }

  /**
   * Resolve the list of pack directories the command should type-check. Mirrors
   * [CompileCommand]'s discovery shape — we don't reuse the project-config loader here
   * because typecheck only cares about per-pack tsconfig presence, not the full
   * dependency closure (transitive deps surface through the per-pack tsconfig's
   * generated `client.d.ts` already).
   *
   * Returns `null` when the user gave an unresolvable input (an unknown pack name, or no
   * pack at the caller's cwd) — caller maps that to [EXIT_USAGE].
   */
  internal fun resolvePacksToCheck(workspaceRoot: File, callerCwd: Path): List<Path>? {
    val packsDir = File(workspaceRoot, TrailblazeConfigPaths.WORKSPACE_PACKS_DIR)
    if (!packsDir.isDirectory) {
      Console.error(
        "trailblaze $commandLabel: no packs/ directory found at ${packsDir.absolutePath}.",
      )
      return null
    }

    if (typecheckAll) {
      val packDirs = packsDir.listFiles { f ->
        f.isDirectory && File(f, TrailblazeConfigPaths.PACK_MANIFEST_FILENAME).isFile
      }
      // `File.listFiles` returns `null` on I/O errors (permission denied, dir
      // disappeared mid-walk). Treating null as "no packs" would silently hide a real
      // filesystem problem — surface it as a usage error with a pointer to the dir.
      if (packDirs == null) {
        Console.error(
          "trailblaze $commandLabel: failed to list packs at ${packsDir.absolutePath} " +
            "(I/O or permission error).",
        )
        return null
      }
      return packDirs.sortedBy { it.name }.map { it.toPath() }
    }

    val explicit = packId
    if (explicit != null) {
      val target = File(packsDir, explicit)
      if (!File(target, TrailblazeConfigPaths.PACK_MANIFEST_FILENAME).isFile) {
        Console.error(
          "trailblaze $commandLabel: unknown pack '$explicit' — no pack.yaml at " +
            "${target.absolutePath}.",
        )
        return null
      }
      return listOf(target.toPath())
    }

    // No --all and no positional arg → walk up from cwd to find the enclosing pack.
    val pack = findEnclosingPack(callerCwd, packsDirAbs = packsDir.canonicalFile.toPath())
    if (pack == null) {
      Console.error(
        "trailblaze $commandLabel: no pack to type-check. Pass a pack id (e.g. `trailblaze " +
          "$commandLabel wikipedia`) or --all, or run from inside a pack's directory tree.",
      )
      return null
    }
    return listOf(pack)
  }

  /**
   * Walk up from [startPath] looking for the nearest ancestor containing a `pack.yaml`
   * sibling, stopping at [packsDirAbs] (we don't want to walk past `packs/` into the
   * workspace root — only directories under `packs/<id>/` are valid pack roots).
   *
   * Visible for testing.
   */
  internal fun findEnclosingPack(startPath: Path, packsDirAbs: Path): Path? {
    // Canonicalize both sides via `toRealPath()` so symlink-prefixed temp roots like
    // macOS's `/tmp -> /private/tmp` don't make the `parent == packsDirAbs` comparison
    // miss its target. Falls back to `toAbsolutePath().normalize()` if the path doesn't
    // exist yet — important for unit tests that pass paths constructed by string-
    // concatenation under a temp dir that hasn't been written to.
    val canonicalPacksDir = packsDirAbs.canonicalize()
    val startDir = startPath.canonicalize()
    var current: Path? = if (Files.isRegularFile(startDir)) startDir.parent else startDir
    while (current != null) {
      // A "pack root" is a directory directly under `packs/` (depth 1) that contains a
      // `pack.yaml` file. Detecting both conditions guards against the edge where a user
      // drops a stray `pack.yaml` somewhere unrelated under their workspace.
      val parentCanonical = current.parent?.canonicalize()
      if (Files.isRegularFile(current.resolve(TrailblazeConfigPaths.PACK_MANIFEST_FILENAME)) &&
        parentCanonical != null &&
        parentCanonical == canonicalPacksDir
      ) {
        return current
      }
      if (current.canonicalize() == canonicalPacksDir) return null
      current = current.parent
    }
    return null
  }

  /**
   * Canonicalize a path so it compares stably to other canonicalized paths. Uses
   * `toRealPath()` when the path exists (resolves symlinks like macOS's `/tmp ->
   * /private/tmp`); falls back to `toAbsolutePath().normalize()` otherwise so callers
   * can hand us paths constructed by string-concatenation under not-yet-materialized
   * directories.
   */
  private fun Path.canonicalize(): Path = try {
    this.toRealPath()
  } catch (_: java.io.IOException) {
    this.toAbsolutePath().normalize()
  }

  /**
   * Pick `bun` over `node` when both are on PATH. Bun's startup is faster and it's the
   * canonical runtime for the rest of the framework's TS pipeline (esbuild for scripted
   * tools — see [xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration]).
   * Falls back to `node` so a host that only has Node still works.
   *
   * Returns `null` when neither is on PATH. PATH probing delegates to
   * [CliPathUtils.isCommandOnPath] for cross-platform behavior (`.exe` resolution on
   * Windows via `PATHEXT`).
   */
  internal fun resolveJsRuntime(): String? {
    for (candidate in JS_RUNTIME_PREFERENCE) {
      if (CliPathUtils.isCommandOnPath(candidate)) return candidate
    }
    return null
  }

  /**
   * Spawn `<jsRuntime> <tscJs> --pretty --noEmit --project <tsconfig>` against [tsconfig]
   * and pipe the output to the caller's terminal verbatim. `--pretty` keeps tsc's
   * coloring; `--noEmit` is the standard "check only" flag that matches what the IDE's
   * language server does on save. We pass `--project` explicitly so tsc anchors its
   * include globs at the pack's `tools/` dir rather than the cwd.
   *
   * Returns the spawned process's exit code — tsc itself uses `0` for success and `1`
   * for type errors, which propagate upstream as [EXIT_TYPE_ERROR].
   */
  private fun runTsc(jsRuntime: String, tscJs: Path, tsconfig: Path): Int {
    val argv = listOf(
      jsRuntime,
      tscJs.toAbsolutePath().toString(),
      "--pretty",
      "--noEmit",
      "--project",
      tsconfig.toAbsolutePath().toString(),
    )
    val timeoutMs = resolveTscTimeoutMs()
    return try {
      val proc = ProcessBuilder(argv)
        .redirectErrorStream(true)
        // No `directory(...)` — `tsc --project <abs>` reads its include set from the
        // resolved tsconfig anchor, not the cwd.
        .inheritIO()
        .start()
      // Default cap is 5 minutes. tsc is usually well under 5 seconds even on cold
      // caches; a 5-minute ceiling catches pathological misconfigurations (infinite
      // include glob, runaway process) without ever interrupting a real type-check.
      // Override via [TIMEOUT_MS_ENV_VAR] when working with codebases that have known
      // pathological cyclic types — see [resolveTscTimeoutMs] for clamp behavior.
      if (proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        proc.exitValue()
      } else {
        Console.error(
          "trailblaze $commandLabel: tsc did not finish within ${timeoutMs}ms — killing. " +
            "Bump $TIMEOUT_MS_ENV_VAR if this is a known-slow codebase.",
        )
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        EXIT_TYPE_ERROR
      }
    } catch (e: Exception) {
      Console.error(
        "trailblaze $commandLabel: failed to spawn $jsRuntime: ${e.message ?: e.javaClass.simpleName}",
      )
      EXIT_TYPE_ERROR
    }
  }

  /**
   * Resolve the per-pack tsc subprocess timeout in milliseconds.
   *
   * Reads [TIMEOUT_MS_ENV_VAR] once. A missing / unparseable value falls back to
   * [DEFAULT_TSC_TIMEOUT_MS] (5 minutes). The result is clamped to a 1-minute lower
   * bound so a fat-fingered `=0` or `=1` doesn't make `waitFor` return false
   * immediately and report every typecheck as a timeout — same defensive clamp the
   * Gradle install-task helpers in this repo use for an analogous env var.
   *
   * Logged once per command invocation (only when overridden) so a CI consumer can
   * trace which value was actually in effect when an unexpected timeout fires.
   */
  internal fun resolveTscTimeoutMs(): Long {
    val raw = System.getenv(TIMEOUT_MS_ENV_VAR) ?: return DEFAULT_TSC_TIMEOUT_MS
    val parsed = raw.toLongOrNull()
    if (parsed == null) {
      Console.error(
        "trailblaze $commandLabel: $TIMEOUT_MS_ENV_VAR='$raw' is not a valid number of " +
          "milliseconds — using default ${DEFAULT_TSC_TIMEOUT_MS}ms.",
      )
      return DEFAULT_TSC_TIMEOUT_MS
    }
    val clamped = parsed.coerceAtLeast(MIN_TSC_TIMEOUT_MS)
    if (clamped != parsed) {
      Console.error(
        "trailblaze $commandLabel: $TIMEOUT_MS_ENV_VAR=${parsed}ms is below the 1-minute " +
          "floor — clamped to ${clamped}ms.",
      )
    }
    return clamped
  }

  internal companion object {
    /** Picocli OK. */
    const val EXIT_OK = 0

    /**
     * Type errors (or any tsc-side spawn failure). Maps to picocli convention `1`.
     * Matches tsc's own non-zero exit semantics so shell scripts that already check
     * `tsc && deploy` keep working when swapped to `trailblaze typecheck && deploy`.
     */
    const val EXIT_TYPE_ERROR = 1

    /** Usage errors (missing workspace, unknown / invalid pack, missing runtime, missing tsconfig). */
    const val EXIT_USAGE = 2

    /**
     * Operational failures: framework JAR missing the bundled tsc payload, filesystem
     * I/O errors during workspace setup, etc. Distinct from [EXIT_TYPE_ERROR] (which
     * is for tsc-reported failures) and [EXIT_USAGE] (which is for caller-side
     * mistakes), so a CI consumer can route "user error" vs "framework broken" vs
     * "code is broken" to different alerts. Maps to `3` to avoid colliding with the
     * other two exit codes.
     */
    const val EXIT_OPERATIONAL_ERROR = 3

    /**
     * PATH lookup order — bun first because it's the existing framework-wide TS runtime
     * (esbuild for scripted tools needs it too), so the developer already has it.
     */
    internal val JS_RUNTIME_PREFERENCE: List<String> = listOf("bun", "node")

    /**
     * Default per-pack tsc subprocess timeout. 5 minutes is more than 50× the typical
     * tsc cold-start + check time on a small pack; the override exists for codebases
     * with known pathological types (deeply-recursive zod schemas, cyclic interfaces).
     */
    internal const val DEFAULT_TSC_TIMEOUT_MS: Long = 5L * 60L * 1000L

    /**
     * Lower clamp for [TIMEOUT_MS_ENV_VAR]. A value below this is forced up to one
     * minute so a fat-fingered `=0` doesn't cause every type-check to report as a
     * timeout instantly (mirroring the same clamp `InstallAuthorToolDepsTask` uses
     * for its `trailblazeInstallTimeoutMinutes` override).
     */
    internal const val MIN_TSC_TIMEOUT_MS: Long = 60L * 1000L

    /**
     * Env var that overrides [DEFAULT_TSC_TIMEOUT_MS] in milliseconds. Read once per
     * pack via [resolveTscTimeoutMs]; an unparseable or below-floor value falls back
     * to the default / clamp with a single line of stderr telemetry so the operator
     * knows the override didn't take effect.
     */
    internal const val TIMEOUT_MS_ENV_VAR: String = "TRAILBLAZE_TYPECHECK_TIMEOUT_MS"

    /**
     * Reject pack ids that aren't a single directory-name segment under `packs/`. The
     * downstream `File(packsDir, packId)` would otherwise let an explicit `..` escape
     * the packs tree — the `pack.yaml` existence guard happens to catch the common
     * case ("no pack.yaml at /etc/foo/pack.yaml"), but defense-in-depth (and a clearer
     * error message) is cheap. Returns the reason as a human-readable string, or null
     * when the id is well-formed.
     */
    internal fun validatePackId(id: String): String? {
      if (id.isBlank()) return "pack id must not be blank"
      if (id.contains('/') || id.contains('\\')) return "pack id must not contain path separators"
      if (id == "." || id == ".." || id.split('/', '\\').any { it == ".." }) {
        return "pack id must not contain `..` segments"
      }
      // Absolute Unix path (`/etc/foo`) or Windows drive letter (`C:\…`).
      if (id.startsWith("/") || id.startsWith("\\") || (id.length >= 2 && id[1] == ':')) {
        return "pack id must be a single directory name under packs/, not an absolute path"
      }
      return null
    }
  }
}
