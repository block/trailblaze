package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * Tests for [CheckCommand] — the unified compile-+-typecheck CLI entry point that
 * replaces the pre-issue-#3231 `trailblaze compile` + `trailblaze typecheck` two-step.
 *
 * Coverage focuses on the resolution surface (inside / outside / outside-with-pack-id /
 * --workspace) and the `--no-typecheck` materialize-only mode. The actual tsc invocation
 * is covered by [TypecheckCommandTest] (which CheckCommand delegates to); the actual
 * compile path is covered by [CompileCommandTest]. Pinning the orchestration surface
 * here keeps that delegation honest.
 */
class CheckCommandTest {

  private val workDir: File = createTempDirectory("trailblaze-check-command-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `--all and a positional pack id are mutually exclusive`() {
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha")

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute("alpha", "--all")
    }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `check from outside any workspace with no pack id exits EXIT_USAGE`() {
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val exit = CliCallerContext.withCallerCwd(isolated.toPath()) {
      CommandLine(CheckCommand()).execute()
    }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `check with an explicit --workspace pointing at a non-workspace dir exits EXIT_USAGE`() {
    val bogus = File(workDir, "bogus").apply { mkdirs() }

    val exit = CommandLine(CheckCommand()).execute("--workspace", bogus.absolutePath)
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `check with invalid pack id exits EXIT_USAGE`() {
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha")

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute("../escape")
    }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `check --no-typecheck materializes and exits without spawning tsc`() {
    // Materialize-only path. The post-compile assertion proves the compile half ran;
    // the absence of a `node` / `bun` binary on a CI agent without JS runtimes would
    // otherwise force a non-zero typecheck exit if the typecheck branch had been entered.
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha", withTarget = true)

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute("--no-typecheck")
    }
    assertEquals(0, exit, "Expected EXIT_OK from --no-typecheck materialize-only run")
    assertTrue(
      File(workspaceRoot, "trails/config/dist/targets/alpha.yaml").exists(),
      "alpha.yaml should be emitted by the compile half of `check --no-typecheck`",
    )
  }

  @Test
  fun `check --workspace anchors materialization at the given directory`() {
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha", withTarget = true)
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val exit = CliCallerContext.withCallerCwd(isolated.toPath()) {
      CommandLine(CheckCommand()).execute(
        "--workspace", workspaceRoot.absolutePath,
        "--no-typecheck",
      )
    }
    assertEquals(0, exit, "Expected EXIT_OK when --workspace anchors a real workspace root")
    assertTrue(
      File(workspaceRoot, "trails/config/dist/targets/alpha.yaml").exists(),
      "Compile output should land in --workspace's tree, not the caller cwd",
    )
  }

  @Test
  fun `check from outside any workspace with pack-id enumerates examples`() {
    // Lay out the "outside workspace, but examples/* has a matching pack" shape.
    val callerCwd = File(workDir, "outside").apply { mkdirs() }
    val examples = File(callerCwd, "examples").apply { mkdirs() }
    val exampleWorkspace = File(examples, "playwright-native").apply { mkdirs() }
    val packDir = File(exampleWorkspace, "trails/config/packs/wikipedia").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: wikipedia
      target:
        display_name: Wikipedia
        platforms:
          web:
            urls: [https://en.wikipedia.org/]
      """.trimIndent(),
    )

    val exit = CliCallerContext.withCallerCwd(callerCwd.toPath()) {
      CommandLine(CheckCommand()).execute("wikipedia", "--no-typecheck")
    }
    assertEquals(
      0,
      exit,
      "Expected EXIT_OK — enumeration should resolve `wikipedia` under examples/playwright-native",
    )
    assertTrue(
      File(exampleWorkspace, "trails/config/dist/targets/wikipedia.yaml").exists(),
      "Resolved workspace should have been compiled",
    )
  }

  @Test
  fun `--help exits zero`() {
    val exit = CommandLine(CheckCommand()).execute("--help")
    assertEquals(0, exit)
  }

  @Test
  fun `error messages from the inner CompileCommand carry the 'trailblaze check' prefix`() {
    // Routing check: the user typed `trailblaze check`, so every error line — even the
    // ones raised by the inner CompileCommand / TypecheckCommand classes — must read
    // `trailblaze check:`. A regression here means a follow-up commit hardcoded
    // `trailblaze compile:` or `trailblaze typecheck:` back into one of the inner
    // command's error paths instead of templating off the routed [CompileCommand.commandLabel]
    // / [TypecheckCommand.commandLabel].
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val packDir = File(workspaceRoot, "trails/config/packs/consumer").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: consumer
      dependencies:
        - missing-pack
      target:
        display_name: Consumer
        platforms:
          android:
            app_ids: [com.example]
      """.trimIndent(),
    )

    val (exit, stderr) = captureStderr {
      CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
        CommandLine(CheckCommand()).execute("--no-typecheck")
      }
    }
    assertEquals(1, exit, "Expected EXIT_COMPILE_ERROR from the missing-dep pack")
    assertTrue(
      stderr.contains("trailblaze check: compilation failed"),
      "Expected the inner CompileCommand's stderr to be routed under `trailblaze check:`. Got: $stderr",
    )
    assertTrue(
      !stderr.contains("trailblaze compile:"),
      "No `trailblaze compile:` prefix should survive once routed through CheckCommand. Got: $stderr",
    )
  }

  @Test
  fun `error messages from the inner TypecheckCommand carry the 'trailblaze check' prefix`() {
    // Symmetric to the CompileCommand routing test above. The cheapest TypecheckCommand
    // error path that doesn't depend on a JS runtime or the bundled tsc payload is the
    // `--all` + positional-pack-id mutex check at the top of `call()` — it returns
    // EXIT_USAGE before touching the filesystem or any external process. Driving it
    // directly with `commandLabel = "check"` set proves the inner command honors the
    // routed label end-to-end (Console.error → stderr), which is what CheckCommand
    // does inside its `apply { commandLabel = "check" }` block.
    val (exit, stderr) = captureStderr {
      val command = TypecheckCommand().apply { commandLabel = "check" }
      CommandLine(command).execute("some-pack", "--all")
    }
    assertEquals(TypecheckCommand.EXIT_USAGE, exit)
    assertTrue(
      stderr.contains("trailblaze check:"),
      "Expected the TypecheckCommand stderr to carry the routed `trailblaze check:` prefix. Got: $stderr",
    )
    assertTrue(
      !stderr.contains("trailblaze typecheck:"),
      "No `trailblaze typecheck:` prefix should survive once routed through CheckCommand. Got: $stderr",
    )
  }

  @Test
  fun `--workspace with unknown pack id fails fast and lists available packs`() {
    // Builds a workspace with `alpha`, then asks `check --workspace <ws> beta` — the
    // resolver should reject with `Available: alpha`, NOT silently fall through to a
    // less-actionable error from the inner TypecheckCommand.
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha")

    val (exit, stderr) = captureStderr {
      CommandLine(CheckCommand()).execute(
        "--workspace", workspaceRoot.absolutePath,
        "beta",
      )
    }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
    assertTrue(
      stderr.contains("pack 'beta' not found") && stderr.contains("Available: alpha"),
      "Expected the error to name the bad pack id AND list available packs. Got: $stderr",
    )
  }

  @Test
  fun `--workspace with unknown pack id and empty packs dir says 'Available -- none -- '`() {
    // Edge case: the workspace marker exists (`trails/config/packs/`) but no pack
    // subdirectories. The error should say `Available: <none>` instead of an empty
    // string after `Available:`.
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    File(workspaceRoot, "trails/config/packs").apply { mkdirs() }

    val (exit, stderr) = captureStderr {
      CommandLine(CheckCommand()).execute(
        "--workspace", workspaceRoot.absolutePath,
        "anything",
      )
    }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
    assertTrue(
      stderr.contains("Available: <none>"),
      "Expected the empty-packs-dir case to render `Available: <none>`. Got: $stderr",
    )
  }

  // -- decideTypecheckDispatch: the four branches of step 2's dispatch decision -------

  @Test
  fun `dispatch — resolved pack dir points cwd at the pack, not workspace root`() {
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val packDir = File(workspaceRoot, "trails/config/packs/alpha").apply { mkdirs() }
    val callerCwd = File(workDir, "elsewhere").apply { mkdirs() }.toPath()

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopePackId = "alpha",
      scopePackDir = packDir,
      forceAll = false,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = false,
    )
    assertEquals(packDir.toPath(), dispatch.cwd)
    assertEquals("alpha", dispatch.packId)
    assertEquals(false, dispatch.typecheckAll)
  }

  @Test
  fun `dispatch — explicit --all forces workspace-root cwd and typecheckAll=true`() {
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val callerCwd = File(workspaceRoot, "trails/config/packs/alpha").apply { mkdirs() }.toPath()

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopePackId = null,
      scopePackDir = null,
      forceAll = false,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = true,
    )
    assertEquals(workspaceRoot.toPath(), dispatch.cwd)
    assertEquals(null, dispatch.packId)
    assertEquals(true, dispatch.typecheckAll)
  }

  @Test
  fun `dispatch — forceAll (cwd-coerced) behaves like explicit --all`() {
    // The resolver promotes to `forceAll = true` when the caller cwd has no enclosing
    // pack (workspace root or anywhere above packs/). decideTypecheckDispatch should treat
    // this identically to the user passing `--all`.
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val callerCwd = workspaceRoot.toPath()

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopePackId = null,
      scopePackDir = null,
      forceAll = true,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = false,
    )
    assertEquals(workspaceRoot.toPath(), dispatch.cwd)
    assertEquals(null, dispatch.packId)
    assertEquals(true, dispatch.typecheckAll)
  }

  @Test
  fun `dispatch — enumeration-resolved pack id (outside-workspace path) routes cwd to the pack dir`() {
    // The enumeration branch of [resolveWorkspace] builds a WorkspaceResolution with
    // scopePackId + scopePackDir set and forceAll explicitly false. This dispatch test
    // closes the gap between the four `when` branches and the enumeration call site:
    // an accidental `forceAll = true` in that builder would silently mis-route step 2 to
    // the workspace root, which would NOT be caught by any of the four branch tests above.
    val outsideCwd = File(workDir, "outside").apply { mkdirs() }.toPath()
    val workspaceRoot = File(workDir, "outside/examples/wiki/trails")
      .apply { mkdirs() }.toPath().toFile()
    val packDir = File(workspaceRoot, "config/packs/wikipedia").apply { mkdirs() }

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopePackId = "wikipedia",
      scopePackDir = packDir,
      forceAll = false,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = outsideCwd,
      checkAll = false,
    )
    assertEquals(packDir.toPath(), dispatch.cwd)
    assertEquals("wikipedia", dispatch.packId)
    assertEquals(false, dispatch.typecheckAll)
  }

  @Test
  fun `WorkspaceResolution rejects scopePackDir-and-forceAll together at construction`() {
    // Forcing function: a scoped pack plus forceAll is meaningless and would slip through
    // [decideTypecheckDispatch] as `cwd = pack-dir` with `typecheckAll = true`, which the
    // inner TypecheckCommand then rejects with a less-actionable usage error. The data
    // class's `init` block catches it at construction.
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val packDir = File(workspaceRoot, "trails/config/packs/alpha").apply { mkdirs() }

    val ex = kotlin.runCatching {
      CheckCommand.WorkspaceResolution(
        workspaceRoot = workspaceRoot,
        scopePackId = "alpha",
        scopePackDir = packDir,
        forceAll = true,
      )
    }.exceptionOrNull()

    assertTrue(
      ex is IllegalArgumentException,
      "Expected IllegalArgumentException from require(), got ${ex?.javaClass?.simpleName}: ${ex?.message}",
    )
    assertTrue(
      ex.message?.contains("scopePackDir and forceAll are mutually exclusive") == true,
      "Expected the require message to name the conflicting fields. Got: ${ex.message}",
    )
  }

  @Test
  fun `cwd at packs-dir itself coerces forceAll=true (no-enclosing-pack edge case)`() {
    // Regression: previously [cwdHasNoEnclosingPack] used `!cwd.startsWith(packsDir)`
    // which is reflexively false when `cwd == packsDir`, claiming "has enclosing pack"
    // for the packs/ dir itself. That left forceAll=false, and the inner
    // TypecheckCommand's walk-up from packs/ would then fail. End-to-end test: run
    // `check --no-typecheck` from packs/ and assert it exits OK with materialization
    // landing in the workspace.
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha", withTarget = true)
    val packsDir = File(workspaceRoot, "trails/config/packs")

    val exit = CliCallerContext.withCallerCwd(packsDir.toPath()) {
      CommandLine(CheckCommand()).execute("--no-typecheck")
    }
    assertEquals(0, exit, "Expected EXIT_OK when invoked from the packs/ dir itself")
    assertTrue(
      File(workspaceRoot, "trails/config/dist/targets/alpha.yaml").exists(),
      "Materialization should have anchored to the workspace root, not failed at packs/",
    )
  }

  @Test
  fun `dispatch — no scope and no force defers to the original callerCwd`() {
    // The "inside pack X, no args" case: the resolver leaves scopePackDir null AND
    // forceAll false (because callerCwd was inside an enclosing pack tree). Step 2 should
    // hand TypecheckCommand the original callerCwd so its walk-up finds the same pack.
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val callerCwd = File(workspaceRoot, "trails/config/packs/alpha/tools")
      .apply { mkdirs() }.toPath()

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopePackId = null,
      scopePackDir = null,
      forceAll = false,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = false,
    )
    assertEquals(callerCwd, dispatch.cwd)
    assertEquals(null, dispatch.packId)
    assertEquals(false, dispatch.typecheckAll)
  }

  /**
   * Capture stderr around [block] so tests can assert on the actionable-error wording
   * without their assertions dragging the test runner's own log through.
   */
  private fun captureStderr(block: () -> Int): Pair<Int, String> {
    val originalErr = System.err
    val buffer = java.io.ByteArrayOutputStream()
    System.setErr(java.io.PrintStream(buffer, true, Charsets.UTF_8))
    try {
      val exit = block()
      return exit to buffer.toString(Charsets.UTF_8)
    } finally {
      System.setErr(originalErr)
    }
  }

  /**
   * Build a workspace with a single named pack. Returns the workspace root (the dir
   * whose `trails/config/packs/<packId>/pack.yaml` was just written). When
   * [withTarget] is true, the pack carries a `target:` block so the compiler emits
   * a materialized target yaml; otherwise the pack is a library and compilation
   * produces no targets (useful for argument-validation tests that don't care
   * about the compile half).
   */
  private fun newWorkspaceWithPack(packId: String, withTarget: Boolean = false): File {
    val workspaceRoot = File(workDir, "workspace").apply { mkdirs() }
    val packDir = File(workspaceRoot, "trails/config/packs/$packId").apply { mkdirs() }
    val manifest = if (withTarget) {
      """
      id: $packId
      target:
        display_name: ${packId.replaceFirstChar { it.titlecase() }}
        platforms:
          android:
            app_ids: [com.example.$packId]
      """.trimIndent()
    } else {
      "id: $packId\n"
    }
    File(packDir, "pack.yaml").writeText(manifest)
    return workspaceRoot
  }
}
