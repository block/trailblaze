package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * Single-command entry point that materializes a workspace and type-checks its
 * TypeScript / JavaScript pack tools — the consolidation of the pre-issue-#3231
 * `trailblaze compile` + `trailblaze typecheck` two-step.
 *
 * Resolves the workspace in three ways:
 *  1. From inside a workspace tree (CWD or any subdirectory has
 *     `trails/config/packs/` as an ancestor): walk-up finds the workspace root.
 *     If CWD is inside a specific pack, that pack is the default typecheck scope;
 *     otherwise, all packs are checked.
 *  2. From outside any workspace, with `<pack-id>`: enumerate known workspace
 *     locations (currently the per-example `examples/[name]/trails/config/packs/`
 *     trees) and resolve the id against the union. A unique match is used;
 *     ambiguity / no-match is an error naming the candidates.
 *  3. From outside any workspace, no `<pack-id>`: emit an actionable error.
 *
 * `--workspace <dir>` pins the workspace root explicitly — used by CI scripts
 * (e.g. `pr_validate_ts_tooling.sh` under `scripts/`) that run from a fixed
 * working directory and can't rely on the walk-up.
 *
 * Materialization (the compile equivalent) is delegated to [CompileCommand];
 * typechecking is delegated to [TypecheckCommand]. Both inner commands are still
 * the source of truth for their respective behavior — `CheckCommand` only orchestrates
 * resolution and ordering. The compile-only and typecheck-only picocli surfaces are
 * unregistered from [TrailblazeCli] (hard cut per the pre-external-users policy);
 * the Kotlin classes remain so internal callers and this orchestrator can use them.
 */
@Command(
  name = "check",
  mixinStandardHelpOptions = true,
  description = [
    "Materialize pack manifests + type-check pack TypeScript/JavaScript sources",
  ],
)
class CheckCommand : Callable<Int> {

  @Parameters(
    arity = "0..1",
    paramLabel = "<pack-id>",
    description = [
      "Name of the pack to scope the type-check to (directory name under " +
        "<workspace>/trails/config/packs/). Omit when running from inside a pack " +
        "tree (auto-detected) or pass --all to type-check every pack. Mutually " +
        "exclusive with --all.",
    ],
  )
  var packId: String? = null

  @Option(
    names = ["--all"],
    description = [
      "Type-check every pack in the discovered workspace, even when running from " +
        "inside a specific pack tree. Mutually exclusive with the positional " +
        "<pack-id>.",
    ],
  )
  var checkAll: Boolean = false

  @Option(
    names = ["--workspace"],
    description = [
      "Pin the workspace root explicitly (the directory containing `trails/config/packs/`). " +
        "Used by CI scripts that run with a fixed cwd; interactive users should rely on " +
        "the cwd walk-up instead.",
    ],
  )
  var workspaceDir: File? = null

  @Option(
    names = ["--no-typecheck"],
    description = [
      "Skip the bundled-tsc typecheck pass — only materialize the workspace's SDK + " +
        "per-pack typed bindings. Intended for CI scripts that run tsc with custom " +
        "settings (e.g., excluding legacy embedded sub-projects); interactive users " +
        "should leave this off.",
    ],
  )
  var noTypecheck: Boolean = false

  override fun call(): Int {
    if (checkAll && packId != null) {
      Console.error("trailblaze check: --all and <pack-id> are mutually exclusive.")
      return CommandLine.ExitCode.USAGE
    }
    packId?.let { id ->
      TypecheckCommand.validatePackId(id)?.let { reason ->
        Console.error("trailblaze check: invalid pack id '$id' — $reason.")
        return CommandLine.ExitCode.USAGE
      }
    }

    val callerCwd = CliCallerContext.callerCwd()
    val resolved = resolveWorkspace(callerCwd) ?: return CommandLine.ExitCode.USAGE

    // Step 1: materialize. CompileCommand discovers the workspace via the same walk-up
    // shape we just used, so setting `inputDir` explicitly keeps it from re-walking
    // (and gives a stable anchor when --workspace was passed).
    val compileExit = CliCallerContext.withCallerCwd(resolved.workspaceRoot.toPath()) {
      CompileCommand().apply {
        inputDir = File(resolved.workspaceRoot, TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR)
        commandLabel = "check"
      }.call()
    }
    if (compileExit != 0) return compileExit
    if (noTypecheck) return 0

    // Step 2: typecheck. Decision logic is in [decideTypecheckDispatch] so it can be
    // unit-tested without spawning the inner TypecheckCommand (which itself needs
    // bun/node on PATH and a real workspace marker). See that function's kdoc for the
    // three dispatch branches (resolved-pack-dir / forceAll / fallback-to-callerCwd).
    val dispatch = decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = checkAll,
    )
    val typecheckExit = CliCallerContext.withCallerCwd(dispatch.cwd) {
      TypecheckCommand().apply {
        this.packId = dispatch.packId
        this.typecheckAll = dispatch.typecheckAll
        this.commandLabel = "check"
      }.call()
    }
    return typecheckExit
  }

  private fun resolveWorkspace(callerCwd: Path): WorkspaceResolution? {
    // --workspace wins over walk-up. Reject if the explicit dir doesn't carry the
    // workspace marker — failing here is friendlier than letting CompileCommand
    // surface a missing-packs error later.
    workspaceDir?.let { explicit ->
      val packsDir = File(explicit, TrailblazeConfigPaths.WORKSPACE_PACKS_DIR)
      if (!packsDir.isDirectory) {
        Console.error(
          "trailblaze check: --workspace ${explicit.absolutePath} does not contain " +
            "`${TrailblazeConfigPaths.WORKSPACE_PACKS_DIR}/`. Pass a directory whose " +
            "subtree has the workspace marker.",
        )
        return null
      }
      return resolveScopeInWorkspace(workspaceRoot = explicit.canonicalFile, callerCwd = callerCwd)
    }

    val walkedUp = CliPathUtils.findWorkspaceRoot(callerCwd)
    if (walkedUp != null) {
      return resolveScopeInWorkspace(workspaceRoot = walkedUp.toFile().canonicalFile, callerCwd = callerCwd)
    }

    // Outside any workspace.
    val pid = packId
    if (pid == null) {
      val startAbs = callerCwd.toAbsolutePath().normalize()
      Console.error(
        "trailblaze check: not inside a Trailblaze workspace. Walked up from " +
          "$startAbs to the filesystem root and found no `${TrailblazeConfigPaths.WORKSPACE_PACKS_DIR}/` " +
          "marker. To resolve, either: (a) `cd` into a workspace tree, " +
          "(b) pass `<pack-id>` to enumerate workspaces under `./examples/`, or " +
          "(c) pass `--workspace <dir>` to pin the workspace root explicitly.",
      )
      return null
    }

    // Enumerate candidate workspaces under CWD and pick the one (or fail with the
    // candidate list) that carries the requested pack id. Today's enumeration is
    // narrow on purpose — `./examples/*/trails/` — which mirrors how the docs +
    // CI scripts arrange example workspaces. The npm-distribution future can extend
    // this with `./node_modules/*/trails/` without breaking the existing path.
    val candidates = enumerateWorkspaces(callerCwd)
    val matches = candidates.filter {
      File(it, "${TrailblazeConfigPaths.WORKSPACE_PACKS_DIR}/$pid/${TrailblazeConfigPaths.PACK_MANIFEST_FILENAME}").isFile
    }
    if (matches.isEmpty()) {
      val cwdAbs = callerCwd.toAbsolutePath().normalize()
      if (candidates.isEmpty()) {
        Console.error(
          "trailblaze check: pack '$pid' not found — no workspaces enumerable under $cwdAbs. " +
            "Run from a directory whose `examples/*/trails/config/packs/$pid/` subtree exists, " +
            "or pass `--workspace <dir>`.",
        )
      } else {
        Console.error(
          "trailblaze check: pack '$pid' not found in any enumerated workspace. " +
            "Searched: ${candidates.joinToString(", ") { it.absolutePath }}",
        )
      }
      return null
    }
    if (matches.size > 1) {
      Console.error(
        "trailblaze check: pack '$pid' is ambiguous — found in: " +
          matches.joinToString(", ") { it.absolutePath } +
          ". Pass `--workspace <dir>` to disambiguate.",
      )
      return null
    }
    val workspaceRoot = matches.single()
    val scopePackDir = File(
      workspaceRoot,
      "${TrailblazeConfigPaths.WORKSPACE_PACKS_DIR}/$pid",
    )
    return WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopePackId = pid,
      scopePackDir = scopePackDir,
      forceAll = false,
    )
  }

  /**
   * Decide the typecheck scope inside an already-located workspace. Three outputs to set,
   * one per branch:
   *  - **Explicit pack id**: fail fast if the pack dir doesn't exist (avoids a less
   *    actionable error from a deeper layer); otherwise resolve with that scope.
   *  - **--all**: workspace-wide scope, no pack dir.
   *  - **Neither**: defer to TypecheckCommand's cwd walk-up. If the caller's cwd sits at or
   *    above the workspace's `packs/` dir (i.e. there's no enclosing pack for the walk-up
   *    to latch onto), promote the resolution to `forceAll = true` so the caller doesn't
   *    have to re-run with an explicit flag. That promotion is the reason this function
   *    can't be inlined into [resolveWorkspace] — it's the only place that needs the
   *    caller cwd in addition to the workspace root.
   */
  private fun resolveScopeInWorkspace(workspaceRoot: File, callerCwd: Path): WorkspaceResolution? {
    val explicitPackId = packId
    val packsDir = File(workspaceRoot, TrailblazeConfigPaths.WORKSPACE_PACKS_DIR)
    if (explicitPackId != null) {
      val packDir = File(packsDir, explicitPackId)
      if (!packDir.isDirectory) {
        val availablePacks = packsDir.listFiles { f -> f.isDirectory }
          ?.map { it.name }
          ?.sorted()
          .orEmpty()
        val availableLabel = if (availablePacks.isEmpty()) "<none>" else availablePacks.joinToString(", ")
        Console.error(
          "trailblaze check: pack '$explicitPackId' not found in workspace " +
            "${workspaceRoot.absolutePath}. Available: $availableLabel",
        )
        return null
      }
      return WorkspaceResolution(
        workspaceRoot = workspaceRoot,
        scopePackId = explicitPackId,
        scopePackDir = packDir,
        forceAll = false,
      )
    }
    if (checkAll) {
      return WorkspaceResolution(
        workspaceRoot = workspaceRoot,
        scopePackId = null,
        scopePackDir = null,
        forceAll = true,
      )
    }
    // No explicit scope — let TypecheckCommand's walk-up decide based on cwd. If the
    // walk-up wouldn't find an enclosing pack (because the cwd is at or above the workspace
    // root, or sits in a sibling of `packs/`), promote to `forceAll = true` here so the
    // user doesn't have to re-run with a flag.
    val forceAll = callerCwd.cwdHasNoEnclosingPack(workspaceRoot.toPath())
    return WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopePackId = null,
      scopePackDir = null,
      forceAll = forceAll,
    )
  }

  /**
   * Walk-down enumeration of candidate workspace roots, used only when the cwd-walkup
   * found nothing AND the user supplied a pack id. Looks under each `<cwd>/examples/[name]`
   * directory for the `trails/config/packs/` marker. Limited to one filesystem depth so
   * a misplaced invocation at `$HOME` doesn't scan the whole tree.
   */
  internal fun enumerateWorkspaces(callerCwd: Path): List<File> {
    val examplesDir = File(callerCwd.toFile(), "examples")
    if (!examplesDir.isDirectory) return emptyList()
    val children = examplesDir.listFiles { f -> f.isDirectory } ?: return emptyList()
    return children
      .filter { File(it, TrailblazeConfigPaths.WORKSPACE_PACKS_DIR).isDirectory }
      .map { it.canonicalFile }
      .sortedBy { it.name }
  }

  /**
   * True when [callerCwd] is positioned such that [TypecheckCommand]'s no-arg walk-up
   * would NOT find an enclosing pack — either because the cwd is the workspace root
   * itself, or because it sits in a sibling of `packs/` (e.g. `trails/config/dist/`,
   * `trails/scripts/`). Used to decide whether to promote a no-args invocation to
   * `forceAll = true` so the user doesn't have to re-run with an explicit flag.
   *
   * Precondition: the caller has already verified [callerCwd] is somewhere under
   * [workspaceRoot] (the only call site runs after `findWorkspaceRoot` succeeded).
   * Outside-workspace inputs would also return true here, which is technically wrong
   * but unreachable from the resolver.
   */
  private fun Path.cwdHasNoEnclosingPack(workspaceRoot: Path): Boolean {
    val canonicalCwd = try {
      this.toRealPath()
    } catch (_: java.io.IOException) {
      this.toAbsolutePath().normalize()
    }
    val canonicalWs = try {
      workspaceRoot.toRealPath()
    } catch (_: java.io.IOException) {
      workspaceRoot.toAbsolutePath().normalize()
    }
    // Three ways `forceAll` should fire (TypecheckCommand's walk-up has nothing to
    // latch onto): cwd is the workspace root, cwd is `packs/` itself, or cwd doesn't
    // sit under `packs/` at all (e.g. `trails/config/dist/`, `trails/scripts/`).
    // `Path.startsWith` is reflexive so the explicit `cwd == packsDir` case must
    // come before the `!startsWith` fallback — otherwise it'd be silently swallowed.
    val packsDir = canonicalWs.resolve(TrailblazeConfigPaths.WORKSPACE_PACKS_DIR)
    return canonicalCwd == canonicalWs ||
      canonicalCwd == packsDir ||
      !canonicalCwd.startsWith(packsDir)
  }

  /**
   * Result of [resolveWorkspace]. Carries everything step 2 needs to dispatch the
   * inner [TypecheckCommand] without re-deriving from cwd.
   *
   * - [scopePackId] / [scopePackDir]: the explicit pack the user asked for (or that we
   *   resolved by enumeration), or null when no specific pack was scoped.
   * - [forceAll]: true when the resolver determined the typecheck should run against every
   *   pack — either because `--all` was passed, or because the caller cwd has no enclosing
   *   pack for the inner walk-up to find. Lifting this here keeps the call site readable
   *   (`checkAll || resolved.forceAll`) and puts the decision next to the rest of the
   *   workspace-shape logic.
   */
  internal data class WorkspaceResolution(
    val workspaceRoot: File,
    val scopePackId: String?,
    val scopePackDir: File?,
    val forceAll: Boolean,
  ) {
    init {
      // Forcing function: a scoped pack already implies "typecheck this one pack", while
      // `forceAll` implies workspace-wide. The combination is meaningless and would feed
      // [decideTypecheckDispatch] a state that produces an internally inconsistent
      // dispatch (pack-dir cwd with `typecheckAll = true`), which the inner
      // TypecheckCommand then rejects as a usage error. Catch it at construction so the
      // invariant lives next to the data instead of leaking out as a confusing error.
      require(!(scopePackDir != null && forceAll)) {
        "WorkspaceResolution: scopePackDir and forceAll are mutually exclusive — got " +
          "scopePackDir=$scopePackDir, forceAll=$forceAll. A scoped pack already implies " +
          "per-pack typecheck; forceAll implies workspace-wide. Pick one."
      }
    }
  }

  /**
   * What step 2 actually hands to [TypecheckCommand]: a cwd to root its walk-up against,
   * a pack-id (or null), and a typecheck-all flag. Pure data — exposed for unit tests
   * of [decideTypecheckDispatch].
   */
  internal data class TypecheckDispatch(
    val cwd: Path,
    val packId: String?,
    val typecheckAll: Boolean,
  )

  internal companion object {
    /**
     * Decide how to drive [TypecheckCommand] for the given resolution + caller state.
     * Pure function; no I/O. The branches:
     *  - **Resolved a pack directory** (explicit pack-id, or enumeration found one):
     *    point cwd at the pack so TypecheckCommand's no-arg walk-up latches onto it.
     *    `typecheckAll = false`.
     *  - **`--all` (explicit or coerced via [WorkspaceResolution.forceAll])**: point cwd
     *    at the workspace root, set `typecheckAll = true`.
     *  - **Otherwise** (no scope, no force): leave cwd as the caller's original cwd —
     *    TypecheckCommand's walk-up will find whatever enclosing pack the cwd sits in.
     *    `typecheckAll = false`.
     */
    internal fun decideTypecheckDispatch(
      resolved: WorkspaceResolution,
      callerCwd: Path,
      checkAll: Boolean,
    ): TypecheckDispatch {
      val typecheckAll = checkAll || resolved.forceAll
      val cwd = when {
        resolved.scopePackDir != null -> resolved.scopePackDir.toPath()
        typecheckAll -> resolved.workspaceRoot.toPath()
        else -> callerCwd
      }
      return TypecheckDispatch(
        cwd = cwd,
        packId = resolved.scopePackId,
        typecheckAll = typecheckAll,
      )
    }
  }
}
