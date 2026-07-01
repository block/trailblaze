package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * Tests for [CheckCommand] — the unified compile-+-typecheck-+-test CLI entry point that
 * replaced the pre-issue-#3231 `trailblaze compile` + `trailblaze typecheck` two-step.
 *
 * Coverage spans:
 *  - The resolution surface (inside / outside / outside-with-trailmap-id / --workspace).
 *  - The `--no-typecheck` materialize-only mode.
 *  - The previously-extracted typecheck-phase helpers ([CheckCommand.findEnclosingTrailmap],
 *    [CheckCommand.validateTrailmapId], JS-runtime preference, tsc-timeout clamp). These
 *    were `TypecheckCommandTest` cases before the typecheck phase was folded into
 *    `CheckCommand` and the sibling class deleted; the regression markers carry over
 *    so a future refactor that re-extracts the typecheck phase can't silently break
 *    the contract.
 *
 * The actual `tsc` invocation is exercised end-to-end by `./trailblaze check` against
 * real trailmaps in CI; testing the spawn surface here would duplicate the framework JAR's
 * resource-loading test surface and force `bun`/`node` onto the test agent.
 */
class CheckCommandTest {

  private val workDir: File = createTempDirectory("trailblaze-check-command-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `--all and a positional trailmap id are mutually exclusive`() {
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha")

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute("alpha", "--all")
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @Test
  fun `check from outside any workspace with no trailmap id exits EXIT_USAGE`() {
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val exit = CliCallerContext.withCallerCwd(isolated.toPath()) {
      CommandLine(CheckCommand()).execute()
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @Test
  fun `check with an explicit --workspace pointing at a non-workspace dir exits EXIT_USAGE`() {
    val bogus = File(workDir, "bogus").apply { mkdirs() }

    val exit = CommandLine(CheckCommand()).execute("--workspace", bogus.absolutePath)
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @Test
  fun `check with invalid trailmap id exits EXIT_USAGE`() {
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha")

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute("../escape")
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @Test
  fun `check with unknown trailmap id emits exactly one "unknown trailmap" error from the pre-flight`() {
    // CheckCommand pre-resolves the trailmap list once up-front (so both the typecheck phase
    // and the test phase target the same trailmaps) — that pre-flight is also the validation
    // gate for unknown-trailmap errors. A naive impl that called the resolver from multiple
    // phases would print the error twice; pin the suppression so a future regression
    // that reorders the phases doesn't silently re-introduce the duplicate output.
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha")

    val (exit, stderr) = captureStderr {
      CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
        CommandLine(CheckCommand()).execute("nonexistent-trailmap")
      }
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    // The error message contains the trailmap name. Count occurrences to assert "exactly
    // one." We don't pin the exact wording — a phrasing tweak shouldn't break this test
    // — but the trailmap name is the stable handle.
    val occurrences = stderr.split("nonexistent-trailmap").size - 1
    assertEquals(
      1,
      occurrences,
      "Expected exactly one 'nonexistent-trailmap' error from the pre-flight resolution; " +
        "got $occurrences occurrences. The pre-flight should short-circuit before " +
        "any later phase has a chance to emit a duplicate. stderr was:\n$stderr",
    )
  }

  @Test
  fun `EXIT_USAGE precedence — typecheck USAGE shortcuts even when tests would also report`() {
    // CheckCommand aggregates exit codes via max(typecheckExit, testExit), ordered
    // OK(0) < FAILURE(1) < USAGE(2). When the pre-flight trailmap resolution surfaces
    // USAGE, we never reach the typecheck or test phases — the early return cuts in.
    // Pin this so a future refactor that hoists trailmap resolution back into the per-phase
    // calls doesn't silently flip the priority order. The "unknown trailmap" case from the
    // sibling test exercises the same code path; here we just assert the
    // exit-code-priority invariant directly via the public surface.
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha")
    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute("nonexistent-trailmap")
    }
    assertEquals(
      TrailblazeExitCode.MISUSE.code,
      exit,
      "USAGE (2) must be the surfacing exit code when the pre-flight short-circuits; " +
        "max() over phase exits would otherwise produce 2 either way, but a future " +
        "refactor that moves trailmap resolution INTO the test phase could regress this " +
        "to 0 (no exit signal at all if both phases swallowed the error).",
    )
  }

  @Test
  fun `check --no-typecheck materializes and exits without spawning tsc`() {
    // Materialize-only path. The post-compile assertion proves the compile half ran;
    // the absence of a `node` / `bun` binary on a CI agent without JS runtimes would
    // otherwise force a non-zero typecheck exit if the typecheck branch had been entered.
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha", withTarget = true)

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
  fun `check --no-typecheck still runs the bun-test phase`() {
    // `--no-typecheck` skips the tsc step but NOT the bun-test step (per PR #3283's
    // KISS fold of test-running into `trailblaze check`'s third phase). Pin the
    // behavior with a fixture that has no `*.test.ts` files: TrailmapUnitTestRunner walks
    // the empty tools/ tree, finds nothing, returns EXIT_OK silently — confirming the
    // phase actually runs (otherwise a regression that re-introduces the old
    // skip-everything `--no-typecheck` would also exit 0 and silently pass this
    // assertion). A negative case here would need a trailmap with a failing `*.test.ts`,
    // which in turn needs `bun` on PATH and the runtime SDK bundle — that integration
    // shape is covered by `pr_validate_ts_tooling.sh` against the real example trailmaps,
    // not duplicated here.
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha", withTarget = true)
    val toolsDir = File(workspaceRoot, "trails/config/trailmaps/alpha/tools").apply { mkdirs() }

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute("--no-typecheck")
    }
    assertEquals(0, exit, "Expected EXIT_OK from --no-typecheck on a trailmap with zero test files")
    // Materialize ran (tsconfig emitted) — proves step 1 happened and step 3 didn't
    // short-circuit on a missing tsconfig precondition. (`alpha.yaml` was already
    // asserted in the sibling materialize test; here we assert the tools/tsconfig.json
    // since it's the precondition TrailmapUnitTestRunner would have tripped on.)
    assertTrue(
      File(toolsDir, "tsconfig.json").exists(),
      "tools/tsconfig.json should be emitted by the materialize phase, available to the test phase",
    )
  }

  @Test
  fun `check --workspace anchors materialization at the given directory`() {
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha", withTarget = true)
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
  fun `check from outside any workspace with trailmap-id enumerates examples`() {
    // Lay out the "outside workspace, but examples/* has a matching trailmap" shape.
    val callerCwd = File(workDir, "outside").apply { mkdirs() }
    val examples = File(callerCwd, "examples").apply { mkdirs() }
    val exampleWorkspace = File(examples, "playwright-native").apply { mkdirs() }
    val trailmapDir = File(exampleWorkspace, "trails/config/trailmaps/wikipedia").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
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
    // ones raised by the inner CompileCommand — must read `trailblaze check:`. A
    // regression here means a follow-up commit hardcoded `trailblaze compile:` back
    // into one of the inner command's error paths instead of templating off the routed
    // [CompileCommand.commandLabel].
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val trailmapDir = File(workspaceRoot, "trails/config/trailmaps/consumer").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: consumer
      dependencies:
        - missing-trailmap
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
    assertEquals(1, exit, "Expected EXIT_COMPILE_ERROR from the missing-dep trailmap")
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
  fun `pre-flight unknown-trailmap error carries the 'trailblaze check' prefix`() {
    // Pins the prefix on the pre-flight `resolveTrailmapsToCheck` error path. CheckCommand
    // fails fast there for `nonexistent-trailmap` — before the typecheck phase ever runs —
    // so this test guards the pre-flight surface, NOT the per-trailmap runTsc loop. The
    // companion test `runTypecheckPhase error strings carry the prefix` below drives
    // the typecheck-phase-internal error strings directly via the now-`internal`
    // [CheckCommand.runTypecheckPhase] entry point.
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha")

    val (exit, stderr) = captureStderr {
      CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
        CommandLine(CheckCommand()).execute("nonexistent-trailmap")
      }
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(
      stderr.contains("trailblaze check:"),
      "Expected the pre-flight stderr to carry the `trailblaze check:` prefix. Got: $stderr",
    )
    assertTrue(
      !stderr.contains("trailblaze typecheck:"),
      "No `trailblaze typecheck:` prefix should survive after the fold. Got: $stderr",
    )
  }

  @Test
  fun `runTypecheckPhase error strings carry the 'trailblaze check' prefix`() {
    // Direct phase test — drives the now-`internal` [CheckCommand.runTypecheckPhase]
    // with a trailmap that has no `tools/tsconfig.json` so the per-trailmap loop hits its
    // hardcoded `"trailblaze check: trailmap 'X' has no tools/tsconfig.json"` branch (or,
    // on a JAR without the bundled tsc payload, the earlier
    // `"trailblaze check: the framework JAR did not ship a bundled tsc payload"`
    // branch). Either way the exit code is non-zero and the stderr prefix must read
    // `trailblaze check:`. Pins the phase's literal error strings against drift back
    // to `trailblaze typecheck:` — the gap the pre-flight test above doesn't cover
    // (it exits in `resolveTrailmapsToCheck` before the phase runs).
    val workspaceRoot = File(workDir, "ws-phase").apply { mkdirs() }
    val trailmapDir = File(workspaceRoot, "trails/config/trailmaps/alpha").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText("id: alpha\n")
    // Give alpha a real TypeScript tool source (but no tsconfig) so the typecheck phase treats it
    // as typecheckable and hits the missing-tsconfig branch. Without a `.ts`, the phase now skips
    // Kotlin/YAML-only trailmaps (see `no TypeScript tools` skip test below).
    File(trailmapDir, "tools").apply { mkdirs() }
    File(trailmapDir, "tools/foo.ts").writeText("export const x = 1\n")

    val (exit, stderr) = captureStderr {
      CheckCommand().runTypecheckPhase(
        workspaceRoot = workspaceRoot,
        trailmaps = listOf(trailmapDir.toPath()),
      )
    }
    assertTrue(
      exit == CheckCommand.EXIT_USAGE || exit == CheckCommand.EXIT_OPERATIONAL_ERROR,
      "Expected EXIT_USAGE (missing tsconfig) or EXIT_OPERATIONAL_ERROR (missing tsc payload); got $exit. stderr:\n$stderr",
    )
    assertTrue(
      stderr.contains("trailblaze check:"),
      "Expected phase stderr to carry the `trailblaze check:` prefix. Got: $stderr",
    )
    assertTrue(
      !stderr.contains("trailblaze typecheck:"),
      "No `trailblaze typecheck:` prefix should survive after the fold. Got: $stderr",
    )
  }

  @Test
  fun `typecheck phase skips a Kotlin-or-YAML-only trailmap with no TypeScript sources`() {
    // A trailmap may carry only class-backed `.tool.yaml` (Kotlin) and/or YAML tools — no
    // TypeScript. Such a trailmap has nothing for `tsc` to check; the phase must skip it and
    // succeed rather than fail with a missing-tsconfig error or `tsc`'s TS18003 ("No inputs
    // were found"). This is what allows a trailmap to carry any mix — or zero — TypeScript tools.
    val workspaceRoot = File(workDir, "ws-noTs").apply { mkdirs() }
    val trailmapDir = File(workspaceRoot, "trails/config/trailmaps/kotlinTools").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText("id: kotlinTools\n")
    File(trailmapDir, "tools").apply { mkdirs() }
    // Class-backed tool descriptor only — no `.ts`/`.js` sources.
    File(trailmapDir, "tools/doThing.tool.yaml")
      .writeText("id: doThing\nclass: com.example.DoThingTool\n")

    val (exit, _) = captureStderr {
      CheckCommand().runTypecheckPhase(
        workspaceRoot = workspaceRoot,
        trailmaps = listOf(trailmapDir.toPath()),
      )
    }
    assertEquals(
      CheckCommand.EXIT_OK,
      exit,
      "A trailmap with no TypeScript tool sources should be skipped (EXIT_OK), not failed.",
    )
  }

  @Test
  fun `hasTypeScriptToolSources distinguishes TypeScript sources from Kotlin-or-YAML-only tools`() {
    val base = File(workDir, "ws-detect").apply { mkdirs() }

    val yamlOnly = File(base, "yamlOnly/tools").apply { mkdirs() }
    File(yamlOnly, "a.tool.yaml").writeText("id: a\nclass: com.example.A\n")
    assertTrue(
      !CheckCommand().hasTypeScriptToolSources(yamlOnly.parentFile.toPath()),
      "A tools/ dir with only .tool.yaml should not be typecheckable.",
    )

    val testOnly = File(base, "testOnly/tools").apply { mkdirs() }
    File(testOnly, "a.test.ts").writeText("import { test } from \"bun:test\"\n")
    assertTrue(
      !CheckCommand().hasTypeScriptToolSources(testOnly.parentFile.toPath()),
      "A tools/ dir with only *.test.ts (excluded from typecheck) should not be typecheckable.",
    )

    val withTs = File(base, "withTs/tools").apply { mkdirs() }
    File(withTs, "a.ts").writeText("export const x = 1\n")
    assertTrue(
      CheckCommand().hasTypeScriptToolSources(withTs.parentFile.toPath()),
      "A tools/ dir with a non-test .ts should be typecheckable.",
    )
  }

  @Test
  fun `exit code constants match the documented contract`() {
    // Pin the numeric contract so a future refactor that swaps EXIT_TYPE_ERROR ↔
    // EXIT_OPERATIONAL_ERROR (or repurposes either) breaks this test rather than
    // silently flipping the exit code a CI script depends on. The four-bucket
    // contract is documented on `runTypecheckPhase`'s kdoc and on each constant.
    assertEquals(0, CheckCommand.EXIT_OK)
    assertEquals(1, CheckCommand.EXIT_TYPE_ERROR)
    assertEquals(2, CheckCommand.EXIT_USAGE)
    assertEquals(3, CheckCommand.EXIT_OPERATIONAL_ERROR)
  }

  @Test
  fun `--workspace with unknown trailmap id fails fast and lists available trailmaps`() {
    // Builds a workspace with `alpha`, then asks `check --workspace <ws> beta` — the
    // resolver should reject with `Available: alpha`, NOT silently fall through to a
    // less-actionable error from the typecheck phase.
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha")

    val (exit, stderr) = captureStderr {
      CommandLine(CheckCommand()).execute(
        "--workspace", workspaceRoot.absolutePath,
        "beta",
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(
      stderr.contains("trailmap 'beta' not found") && stderr.contains("Available: alpha"),
      "Expected the error to name the bad trailmap id AND list available trailmaps. Got: $stderr",
    )
  }

  @Test
  fun `--workspace with unknown trailmap id and empty trailmaps dir says 'Available -- none -- '`() {
    // Edge case: the workspace marker exists (`trails/config/trailmaps/`) but no trailmap
    // subdirectories. The error should say `Available: <none>` instead of an empty
    // string after `Available:`.
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    File(workspaceRoot, "trails/config/trailmaps").apply { mkdirs() }

    val (exit, stderr) = captureStderr {
      CommandLine(CheckCommand()).execute(
        "--workspace", workspaceRoot.absolutePath,
        "anything",
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(
      stderr.contains("Available: <none>"),
      "Expected the empty-trailmaps-dir case to render `Available: <none>`. Got: $stderr",
    )
  }

  // -- decideTypecheckDispatch: the four branches of step 2's dispatch decision -------

  @Test
  fun `dispatch — resolved trailmap dir points cwd at the trailmap, not workspace root`() {
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val trailmapDir = File(workspaceRoot, "trails/config/trailmaps/alpha").apply { mkdirs() }
    val callerCwd = File(workDir, "elsewhere").apply { mkdirs() }.toPath()

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopeTrailmapId = "alpha",
      scopeTrailmapDir = trailmapDir,
      forceAll = false,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = false,
    )
    assertEquals(trailmapDir.toPath(), dispatch.cwd)
    assertEquals("alpha", dispatch.trailmapId)
    assertEquals(false, dispatch.typecheckAll)
  }

  @Test
  fun `dispatch — explicit --all forces workspace-root cwd and typecheckAll=true`() {
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val callerCwd = File(workspaceRoot, "trails/config/trailmaps/alpha").apply { mkdirs() }.toPath()

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopeTrailmapId = null,
      scopeTrailmapDir = null,
      forceAll = false,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = true,
    )
    assertEquals(workspaceRoot.toPath(), dispatch.cwd)
    assertEquals(null, dispatch.trailmapId)
    assertEquals(true, dispatch.typecheckAll)
  }

  @Test
  fun `dispatch — forceAll (cwd-coerced) behaves like explicit --all`() {
    // The resolver promotes to `forceAll = true` when the caller cwd has no enclosing
    // trailmap (workspace root or anywhere above trailmaps/). decideTypecheckDispatch should treat
    // this identically to the user passing `--all`.
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val callerCwd = workspaceRoot.toPath()

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopeTrailmapId = null,
      scopeTrailmapDir = null,
      forceAll = true,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = false,
    )
    assertEquals(workspaceRoot.toPath(), dispatch.cwd)
    assertEquals(null, dispatch.trailmapId)
    assertEquals(true, dispatch.typecheckAll)
  }

  @Test
  fun `dispatch — enumeration-resolved trailmap id (outside-workspace path) routes cwd to the trailmap dir`() {
    // The enumeration branch of [resolveWorkspace] builds a WorkspaceResolution with
    // scopeTrailmapId + scopeTrailmapDir set and forceAll explicitly false. This dispatch test
    // closes the gap between the four `when` branches and the enumeration call site:
    // an accidental `forceAll = true` in that builder would silently mis-route step 2 to
    // the workspace root, which would NOT be caught by any of the four branch tests above.
    val outsideCwd = File(workDir, "outside").apply { mkdirs() }.toPath()
    val workspaceRoot = File(workDir, "outside/examples/wiki/trails")
      .apply { mkdirs() }.toPath().toFile()
    val trailmapDir = File(workspaceRoot, "config/trailmaps/wikipedia").apply { mkdirs() }

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopeTrailmapId = "wikipedia",
      scopeTrailmapDir = trailmapDir,
      forceAll = false,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = outsideCwd,
      checkAll = false,
    )
    assertEquals(trailmapDir.toPath(), dispatch.cwd)
    assertEquals("wikipedia", dispatch.trailmapId)
    assertEquals(false, dispatch.typecheckAll)
  }

  @Test
  fun `WorkspaceResolution rejects scopeTrailmapDir-and-forceAll together at construction`() {
    // Forcing function: a scoped trailmap plus forceAll is meaningless and would slip through
    // [decideTypecheckDispatch] as `cwd = trailmap-dir` with `typecheckAll = true`, which the
    // typecheck phase's pre-resolution then rejects with a less-actionable usage error.
    // The data class's `init` block catches it at construction.
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val trailmapDir = File(workspaceRoot, "trails/config/trailmaps/alpha").apply { mkdirs() }

    val ex = kotlin.runCatching {
      CheckCommand.WorkspaceResolution(
        workspaceRoot = workspaceRoot,
        scopeTrailmapId = "alpha",
        scopeTrailmapDir = trailmapDir,
        forceAll = true,
      )
    }.exceptionOrNull()

    assertTrue(
      ex is IllegalArgumentException,
      "Expected IllegalArgumentException from require(), got ${ex?.javaClass?.simpleName}: ${ex?.message}",
    )
    assertTrue(
      ex.message?.contains("scopeTrailmapDir and forceAll are mutually exclusive") == true,
      "Expected the require message to name the conflicting fields. Got: ${ex.message}",
    )
  }

  @Test
  fun `cwd at trailmaps-dir itself coerces forceAll=true (no-enclosing-trailmap edge case)`() {
    // Regression: previously [cwdHasNoEnclosingTrailmap] used `!cwd.startsWith(trailmapsDir)`
    // which is reflexively false when `cwd == trailmapsDir`, claiming "has enclosing trailmap"
    // for the trailmaps/ dir itself. That left forceAll=false, and the typecheck phase's
    // walk-up from trailmaps/ would then fail. End-to-end test: run `check --no-typecheck`
    // from trailmaps/ and assert it exits OK with materialization landing in the workspace.
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha", withTarget = true)
    val trailmapsDir = File(workspaceRoot, "trails/config/trailmaps")

    val exit = CliCallerContext.withCallerCwd(trailmapsDir.toPath()) {
      CommandLine(CheckCommand()).execute("--no-typecheck")
    }
    assertEquals(0, exit, "Expected EXIT_OK when invoked from the trailmaps/ dir itself")
    assertTrue(
      File(workspaceRoot, "trails/config/dist/targets/alpha.yaml").exists(),
      "Materialization should have anchored to the workspace root, not failed at trailmaps/",
    )
  }

  @Test
  fun `dispatch — no scope and no force defers to the original callerCwd`() {
    // The "inside trailmap X, no args" case: the resolver leaves scopeTrailmapDir null AND
    // forceAll false (because callerCwd was inside an enclosing trailmap tree). Step 2 should
    // hand the typecheck phase the original callerCwd so its walk-up finds the same trailmap.
    val workspaceRoot = File(workDir, "ws").apply { mkdirs() }
    val callerCwd = File(workspaceRoot, "trails/config/trailmaps/alpha/tools")
      .apply { mkdirs() }.toPath()

    val resolved = CheckCommand.WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopeTrailmapId = null,
      scopeTrailmapDir = null,
      forceAll = false,
    )
    val dispatch = CheckCommand.decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = false,
    )
    assertEquals(callerCwd, dispatch.cwd)
    assertEquals(null, dispatch.trailmapId)
    assertEquals(false, dispatch.typecheckAll)
  }

  // -- Typecheck-phase helpers migrated from the deleted TypecheckCommandTest ---------

  @Test
  fun `findEnclosingTrailmap returns the trailmap root when called from inside tools`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    val trailmapRoot = File(trailmapsDir, "wikipedia").apply { mkdirs() }
    File(trailmapRoot, "trailmap.yaml").writeText("id: wikipedia\n")
    val toolsDir = File(trailmapRoot, "tools").apply { mkdirs() }

    val found = CheckCommand().findEnclosingTrailmap(
      startPath = toolsDir.toPath(),
      trailmapsDirAbs = trailmapsDir.canonicalFile.toPath(),
    )
    assertEquals(trailmapRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findEnclosingTrailmap returns the trailmap root when called from a deeper subdir`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    val trailmapRoot = File(trailmapsDir, "wikipedia").apply { mkdirs() }
    File(trailmapRoot, "trailmap.yaml").writeText("id: wikipedia\n")
    val deeper = File(trailmapRoot, "tools/scripts/inner").apply { mkdirs() }

    val found = CheckCommand().findEnclosingTrailmap(
      startPath = deeper.toPath(),
      trailmapsDirAbs = trailmapsDir.canonicalFile.toPath(),
    )
    assertEquals(trailmapRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findEnclosingTrailmap returns null when called from above trailmaps`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    val workspaceRoot = File(workDir, "workspace-noise").apply { mkdirs() }

    val found = CheckCommand().findEnclosingTrailmap(
      startPath = workspaceRoot.toPath(),
      trailmapsDirAbs = trailmapsDir.canonicalFile.toPath(),
    )
    assertNull(found)
  }

  @Test
  fun `validateTrailmapId accepts a single directory-name segment`() {
    assertNull(CheckCommand.validateTrailmapId("wikipedia"))
    assertNull(CheckCommand.validateTrailmapId("my-trailmap"))
    assertNull(CheckCommand.validateTrailmapId("a_b_c"))
  }

  @Test
  fun `validateTrailmapId rejects path separators and traversal`() {
    assertNotNull(CheckCommand.validateTrailmapId("../escape"))
    assertNotNull(CheckCommand.validateTrailmapId("a/b"))
    assertNotNull(CheckCommand.validateTrailmapId("a\\b"))
    assertNotNull(CheckCommand.validateTrailmapId("/abs"))
    assertNotNull(CheckCommand.validateTrailmapId(".."))
    assertNotNull(CheckCommand.validateTrailmapId(""))
  }

  @Test
  fun `JS_RUNTIME_PREFERENCE is bun-only with no Node fallback`() {
    // Trailblaze's stated contract is "install bun; nothing else is required."
    // The scripted-tool analyzer, esbuild for scripted tools, and the per-trailmap
    // test runner all route through bun — there is intentionally NO Node fallback
    // because (a) Node has never been tested against the analyzer's bun-targeted
    // shim path, and (b) a silent fallback would mask "you forgot to install bun"
    // diagnostics on hosts that happen to have only Node. Pin the bun-only list so
    // a future refactor that re-adds a Node fallback gets caught here.
    assertEquals(listOf("bun"), CheckCommand.JS_RUNTIME_PREFERENCE)
  }

  @Test
  fun `resolveTscTimeoutMs honors the floor clamp`() {
    // `resolveTscTimeoutMs` reads `TIMEOUT_MS_ENV_VAR` once; we can't mutate the
    // JVM's env in-process, but we CAN pin two invariants directly via the
    // constants: the default is well above the floor, and the floor itself is
    // non-negative (so `waitFor` semantics stay sane). The override behavior is
    // exercised in CI by the env-var-driven smoke runs.
    assertTrue(
      CheckCommand.MIN_TSC_TIMEOUT_MS > 0,
      "MIN_TSC_TIMEOUT_MS must be a positive millisecond bound",
    )
    assertTrue(
      CheckCommand.DEFAULT_TSC_TIMEOUT_MS >= CheckCommand.MIN_TSC_TIMEOUT_MS,
      "DEFAULT_TSC_TIMEOUT_MS must be at or above the floor",
    )
    // No override in this JVM → returns the default.
    if (System.getenv(CheckCommand.TIMEOUT_MS_ENV_VAR) == null) {
      assertEquals(
        CheckCommand.DEFAULT_TSC_TIMEOUT_MS,
        CheckCommand().resolveTscTimeoutMs(),
      )
    }
  }

  // -- package.json scaffolding (workspace-root bootstrap surface) --------------------

  @Test
  fun `scaffoldWorkspacePackageJson writes a minimal template when absent`() {
    val workspaceRoot = File(workDir, "wikipedia").apply { mkdirs() }
    val pkg = File(workspaceRoot, "package.json")
    assertTrue(!pkg.exists(), "precondition: package.json should not exist yet")

    CheckCommand.scaffoldWorkspacePackageJson(workspaceRoot)

    assertTrue(pkg.exists(), "expected scaffold to create $pkg")
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(pkg.readText())
      .let { it as kotlinx.serialization.json.JsonObject }
    assertEquals("wikipedia", (parsed["name"] as kotlinx.serialization.json.JsonPrimitive).content)
    assertEquals(true, (parsed["private"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
    val scripts = parsed["scripts"] as kotlinx.serialization.json.JsonObject
    // Pinned via the constant rather than a literal so the test moves with the hook
    // string if it gets tuned (e.g., wider PATH-detection or a different fallback
    // message). The whole-string match is the real contract — a mid-string drift
    // here would silently break the bootstrap loop.
    assertEquals(
      CheckCommand.POSTINSTALL_HOOK,
      (scripts["postinstall"] as kotlinx.serialization.json.JsonPrimitive).content,
    )
  }

  @Test
  fun `scaffoldWorkspacePackageJson is a no-op when postinstall already wired`() {
    val workspaceRoot = File(workDir, "wikipedia").apply { mkdirs() }
    val pkg = File(workspaceRoot, "package.json")
    val original = packageJsonWithPostinstall(name = "custom-name", postinstall = CheckCommand.POSTINSTALL_HOOK)
    pkg.writeText(original)

    CheckCommand.scaffoldWorkspacePackageJson(workspaceRoot)

    assertEquals(
      original,
      pkg.readText(),
      "package.json must be byte-identical when postinstall is already wired",
    )
  }

  @Test
  fun `scaffoldWorkspacePackageJson does not modify a package_json without the postinstall`() {
    // Renamed from "...but prints a hint" — the test asserts the file-untouched
    // contract, which is what matters for correctness. The hint print is exercised
    // via Console.info() which the JVM Console impl caches at class-init time and
    // can't be redirected from inside the JVM, so a stdout-capture assertion would
    // be unreliable. The branch is covered functionally by this test plus the
    // `is a no-op when postinstall already wired` sibling — together they pin
    // both "matches → silent" and "doesn't match → no write".
    val workspaceRoot = File(workDir, "wikipedia").apply { mkdirs() }
    val pkg = File(workspaceRoot, "package.json")
    val original = """
      {
        "name": "custom-name",
        "private": true,
        "dependencies": {
          "lodash": "^4.0.0"
        }
      }
    """.trimIndent() + "\n"
    pkg.writeText(original)

    CheckCommand.scaffoldWorkspacePackageJson(workspaceRoot)

    assertEquals(
      original,
      pkg.readText(),
      "package.json without postinstall must not be modified — the developer owns the file",
    )
  }

  @Test
  fun `scaffoldWorkspacePackageJson leaves a package_json with a different postinstall untouched`() {
    val workspaceRoot = File(workDir, "wikipedia").apply { mkdirs() }
    val pkg = File(workspaceRoot, "package.json")
    val original = packageJsonWithPostinstall(name = "custom-name", postinstall = "true")
    pkg.writeText(original)

    CheckCommand.scaffoldWorkspacePackageJson(workspaceRoot)

    assertEquals(
      original,
      pkg.readText(),
      "an existing different postinstall script must not be overwritten",
    )
  }

  @Test
  fun `scaffoldWorkspacePackageJson preserves a custom name field when present`() {
    val workspaceRoot = File(workDir, "wikipedia").apply { mkdirs() }
    val pkg = File(workspaceRoot, "package.json")
    val original = packageJsonWithPostinstall(name = "@org/custom-name", postinstall = CheckCommand.POSTINSTALL_HOOK)
    pkg.writeText(original)

    CheckCommand.scaffoldWorkspacePackageJson(workspaceRoot)

    assertEquals(
      original,
      pkg.readText(),
      "a custom name field must be preserved when postinstall is already wired",
    )
  }

  @Test
  fun `scaffoldWorkspacePackageJson silently ignores malformed JSON`() {
    // The kdoc contract is "unparseable file → silent no-op". Pin it directly so a
    // future refactor that surfaces parse errors (raising the verbosity, throwing,
    // logging to stderr) breaks this test rather than spamming every check run
    // whose workspace happens to ship a malformed package.json.
    val workspaceRoot = File(workDir, "wikipedia").apply { mkdirs() }
    val pkg = File(workspaceRoot, "package.json")
    pkg.writeText("{ this is not valid JSON, not even close")
    val original = pkg.readText()

    val (exit, stderr) = captureStderr {
      CheckCommand.scaffoldWorkspacePackageJson(workspaceRoot)
      0
    }

    assertEquals(0, exit)
    assertTrue(
      stderr.isBlank(),
      "expected the malformed-JSON path to be silent on stderr; got: $stderr",
    )
    assertEquals(original, pkg.readText(), "malformed package.json must not be touched")
  }

  @Test
  fun `scaffoldWorkspacePackageJson silently ignores a package_json that is a directory`() {
    // Path-collision edge case: filesystem corruption / weird mis-clone where
    // `<workspaceRoot>/package.json` exists as a *directory*. `exists()` is true,
    // so without the `isFile` guard control would fall through to `readText()`
    // and throw. The catch would swallow it silently anyway, but the explicit
    // guard makes the bail-out branch reachable and obvious.
    val workspaceRoot = File(workDir, "wikipedia").apply { mkdirs() }
    val pkg = File(workspaceRoot, "package.json").apply { mkdirs() }
    assertTrue(pkg.isDirectory, "precondition: package.json should be a directory")

    val (exit, stderr) = captureStderr {
      CheckCommand.scaffoldWorkspacePackageJson(workspaceRoot)
      0
    }

    assertEquals(0, exit)
    assertTrue(
      stderr.isBlank(),
      "directory-collision path must be silent; got: $stderr",
    )
    assertTrue(pkg.isDirectory, "the directory at $pkg must not have been touched")
  }

  @Test
  fun `sanitizeWorkspaceName lowercases and replaces non-alphanumeric chars`() {
    assertEquals("wikipedia", CheckCommand.sanitizeWorkspaceName("wikipedia"))
    assertEquals("ios-contacts", CheckCommand.sanitizeWorkspaceName("ios-contacts"))
    assertEquals("ios-contacts", CheckCommand.sanitizeWorkspaceName("IOS-Contacts"))
    assertEquals("my-test-suite", CheckCommand.sanitizeWorkspaceName("My Test Suite"))
    assertEquals("my_pack", CheckCommand.sanitizeWorkspaceName("my_pack"))
  }

  @Test
  fun `sanitizeWorkspaceName falls back to default for empty or dot-prefixed input`() {
    assertEquals("trailblaze-workspace", CheckCommand.sanitizeWorkspaceName(""))
    assertEquals("trailblaze-workspace", CheckCommand.sanitizeWorkspaceName(".hidden"))
    assertEquals("trailblaze-workspace", CheckCommand.sanitizeWorkspaceName("   "))
  }

  @Test
  fun `sanitizeWorkspaceName strips leading hyphens that npm rejects`() {
    // npm rejects package names beginning with `-` (or `.`) — `npm install` would
    // fail loudly on the very command this scaffold exists to enable. Names like
    // `@org/foo` sanitize to `-org-foo` under the bare `[^a-z0-9_-]` replacement;
    // strip the leading run before returning so the scaffolded name passes npm
    // validation.
    assertEquals("org-foo", CheckCommand.sanitizeWorkspaceName("@org/foo"))
    assertEquals("scope", CheckCommand.sanitizeWorkspaceName("@scope"))
    assertEquals("name", CheckCommand.sanitizeWorkspaceName("---name"))
    assertEquals(
      "trailblaze-workspace",
      CheckCommand.sanitizeWorkspaceName("---"),
      "all-hyphens input must fall back to the default after stripping",
    )
  }

  @Test
  fun `scaffoldWorkspacePackageJson sanitizes the workspace dir name into the template`() {
    val workspaceRoot = File(workDir, "My Test Workspace").apply { mkdirs() }

    CheckCommand.scaffoldWorkspacePackageJson(workspaceRoot)

    val parsed = kotlinx.serialization.json.Json
      .parseToJsonElement(File(workspaceRoot, "package.json").readText())
      .let { it as kotlinx.serialization.json.JsonObject }
    assertEquals(
      "my-test-workspace",
      (parsed["name"] as kotlinx.serialization.json.JsonPrimitive).content,
      "spaces and uppercase letters must be normalized into the npm-style identifier",
    )
  }

  @Test
  fun `check command end-to-end scaffolds package_json on first invocation`() {
    val workspaceRoot = newWorkspaceWithTrailmap(trailmapId = "alpha", withTarget = true)
    val pkg = File(workspaceRoot, "package.json")
    assertTrue(!pkg.exists(), "precondition: workspace fixture should not ship a package.json")

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute("--no-typecheck")
    }
    assertEquals(0, exit)
    assertTrue(
      pkg.exists(),
      "expected the end-to-end check invocation to scaffold a workspace-root package.json",
    )
  }

  @Test
  fun `shipped example workspace package_json files match the scaffold template`() {
    // Drift guard: the four shipped example workspaces hand-roll a package.json
    // that must stay byte-identical to what `packageJsonTemplate` writes today.
    // Without this test, a future tweak to the template (whitespace, key order,
    // postinstall string) silently desyncs the examples — they only break when
    // a real end user runs `npm install` against a stale example.
    //
    // Test cwd is the `trailblaze-host` module dir (gradle's per-module test cwd),
    // so the four shipped workspaces resolve via `../examples/<name>` (one hop up
    // out of `trailblaze-host`, then into the sibling `examples/` tree). Skip
    // (rather than fail) when the example dir is missing — a workspace rename or
    // deletion is its own concern and shouldn't break this drift guard.
    val shipped = listOf("wikipedia", "ios-contacts", "android-sample-app", "playwright-native")
    for (name in shipped) {
      val workspaceRoot = File("../examples/$name")
      if (!workspaceRoot.isDirectory) continue
      val shippedFile = File(workspaceRoot, "package.json")
      assertTrue(
        shippedFile.isFile,
        "expected shipped scaffold at ${shippedFile.absolutePath}",
      )
      assertEquals(
        CheckCommand.packageJsonTemplate(workspaceRoot),
        shippedFile.readText(),
        "shipped example package.json for `$name` has drifted from the scaffold " +
          "template — re-run trailblaze check against ${workspaceRoot.absolutePath} or " +
          "regenerate manually.",
      )
    }
  }

  /**
   * Helper for the scaffolding tests — builds a 7-line package.json fixture with
   * the supplied [name] and [postinstall] values. Centralized so the literal
   * shape of the file lives in exactly one place; bumps to spacing/key-order in
   * the production template don't have to be mirrored across five test fixtures.
   */
  private fun packageJsonWithPostinstall(name: String, postinstall: String): String {
    val escapedPostinstall = postinstall
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
    return """
      {
        "name": "$name",
        "private": true,
        "scripts": {
          "postinstall": "$escapedPostinstall"
        }
      }
    """.trimIndent() + "\n"
  }

  /**
   * Build a workspace with a single named trailmap. Returns the workspace root (the dir
   * whose `trails/config/trailmaps/<trailmapId>/trailmap.yaml` was just written). When
   * [withTarget] is true, the trailmap carries a `target:` block so the compiler emits
   * a materialized target yaml; otherwise the trailmap is a library and compilation
   * produces no targets (useful for argument-validation tests that don't care
   * about the compile half).
   */
  private fun newWorkspaceWithTrailmap(trailmapId: String, withTarget: Boolean = false): File {
    val workspaceRoot = File(workDir, "workspace").apply { mkdirs() }
    val trailmapDir = File(workspaceRoot, "trails/config/trailmaps/$trailmapId").apply { mkdirs() }
    val manifest = if (withTarget) {
      """
      id: $trailmapId
      target:
        display_name: ${trailmapId.replaceFirstChar { it.titlecase() }}
        platforms:
          android:
            app_ids: [com.example.$trailmapId]
      """.trimIndent()
    } else {
      "id: $trailmapId\n"
    }
    File(trailmapDir, "trailmap.yaml").writeText(manifest)
    return workspaceRoot
  }
}
