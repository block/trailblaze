package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import picocli.CommandLine
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointLoader
import xyz.block.trailblaze.waypoint.WaypointMatcher

/**
 * Idempotence pin and contract pins for `trailblaze waypoint locate --session <dir>`
 * (batch mode), introduced as phase (4) of the waypoint-trailmap maintenance loop
 * (devlog 2026-05-18). The pipeline shard depends on the TSV stream being
 * machine-readable: one row per outcome, tab-delimited, no banner chatter.
 *
 * Validation strategy: drive batch mode through the real CLI surface, then for each
 * step file in the same session compute the "truth" match set via a direct
 * [WaypointMatcher.match] call and assert per-step equality. Going via the matcher
 * rather than parsing the per-file CLI's human-readable stdout avoids a
 * test-ordering-dependent flake (the human-readable stdout path passes through
 * [xyz.block.trailblaze.util.Console] whose JVM impl caches `System.out` at class
 * init; when another test loads it before [CliOutCapture.install] runs, `Console.log`
 * bypasses the test's capture). Comparing against the matcher directly is also a
 * stronger invariant — it pins batch mode to truth, not to the legacy CLI rendering.
 *
 * The rest of the suite covers each branch in `runBatch` (ERROR row emission, exit
 * code policy, NONE-vs-multi-match, `--rel-base` override/fallback, empty session
 * dir) plus the new fail-fast `validateArgs` guards.
 */
class WaypointLocateBatchTest {

  private val tempDirs = mutableListOf<File>()
  private lateinit var capturedOut: ByteArrayOutputStream
  private lateinit var capturedErr: ByteArrayOutputStream

  @BeforeTest
  fun setUp() {
    CliOutCapture.install()
    capturedOut = ByteArrayOutputStream()
    capturedErr = ByteArrayOutputStream()
  }

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `batch mode TSV per-step match set equals direct matcher result`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(
      workspace = workspace,
      stepsWithMatchingText = listOf("Welcome screen", "Other content", "Welcome screen again"),
    )

    // --- Batch mode: one CLI invocation, TSV on stdout ---
    val batchExit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, batchExit, "batch run must exit OK; stderr=${stderr()}")
    val batchByStep = parseTsv(stdout())
    assertTrue(batchByStep.isNotEmpty(), "batch mode must emit at least one row; got: ${stdout()}")

    // --- Oracle: run the matcher directly on each step file ---
    val defs = WaypointLoader.loadAllResilient(waypointRoot).definitions
    assertEquals(1, defs.size, "expected exactly the one staged waypoint; got: ${defs.map { it.id }}")
    val stepFiles = sessionDir.listFiles { f -> f.name.endsWith("_AgentDriverLog.json") }
      ?.sortedBy { it.name }
      ?.toList()
      .orEmpty()
    assertEquals(3, stepFiles.size, "session must have 3 step files; got: ${stepFiles.map { it.name }}")
    val truthByStep: Map<String, Set<String>> = stepFiles.associate { stepFile ->
      val screen = SessionLogScreenState.loadStep(stepFile)
      val matches = defs
        .map { WaypointMatcher.match(it, screen, target = null) }
        .filter { it.matched }
        .map { it.definitionId }
        .toSet()
      val relPath = "${sessionDir.name}/${stepFile.name}"
      relPath to matches
    }

    // The two maps must agree key-for-key. Batch keys live under `<session-name>/<filename>`
    // because relBase defaults to the session dir's parent (see runBatch in
    // WaypointLocateCommand).
    assertEquals(
      truthByStep.keys,
      batchByStep.keys,
      "batch mode must emit one row group per step; got batch=${batchByStep.keys}, truth=${truthByStep.keys}",
    )
    for ((step, expected) in truthByStep) {
      assertEquals(
        expected,
        batchByStep[step] ?: emptySet(),
        "step=$step: batch match set must equal matcher's truth; batch=$batchByStep; truth=$truthByStep",
      )
    }

    // Independent sanity: the staged data was designed so two of three steps match.
    val matchingSteps = truthByStep.values.count { it.isNotEmpty() }
    assertEquals(2, matchingSteps, "expected 2 of 3 staged steps to match the synthetic waypoint")
  }

  @Test
  fun `batch mode emits exactly one TSV row per non-matching step (NONE)`() {
    // Pinned because the wrapper script's accounting assumes one row per non-match (it pipes
    // the TSV straight into `cat *.tsv > combined.tsv` and treats each row as one step).
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(
      workspace = workspace,
      stepsWithMatchingText = listOf("Nothing matches here", "Still nothing"),
    )

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    val rows = stdout().lines().filter { '\t' in it }
    assertEquals(2, rows.size, "expected one NONE row per step; got: $rows")
    assertTrue(rows.all { it.endsWith("\tNONE") }, "all rows should be NONE; got: $rows")
  }

  @Test
  fun `single-step --file mode still resolves and exits OK (backwards compat)`() {
    // Phase 4's contract: `--file <log>` mode keeps working unchanged for pipelines/skills
    // that still drive it per-step. We just check the exit code + that batch mode's plumbing
    // doesn't accidentally short-circuit the file path.
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(
      workspace = workspace,
      stepsWithMatchingText = listOf("Welcome screen"),
    )
    val stepFile = sessionDir.listFiles { f -> f.name.endsWith("_AgentDriverLog.json") }!!.first()

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--file", stepFile.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "--file mode must still exit OK; stderr=${stderr()}")
  }

  @Test
  fun `malformed step JSON yields an ERROR row and the loop continues`() {
    // The ERROR catch in runBatch is load-bearing for the pipeline: a single corrupt log
    // file inside a session shouldn't blow up the whole shard. Pin both halves of the
    // contract: (a) an ERROR row is emitted, (b) subsequent good steps are still processed.
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = File(workspace, "logs/20260518_partial").apply { mkdirs() }
    File(sessionDir, "001_step_AgentDriverLog.json").writeText("{ this is not json")
    writeSyntheticStep(sessionDir, index = 2, text = "Welcome screen") // matches

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    // Partial failure stays OK; pipeline sees the ERROR row but the shard isn't aborted.
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "partial failure must keep exit OK; stderr=${stderr()}")
    val rows = stdout().lines().filter { '\t' in it }
    val errorRows = rows.filter { it.endsWith("\tERROR") }
    val matchRows = rows.filter { !it.endsWith("\tERROR") && !it.endsWith("\tNONE") }
    assertEquals(1, errorRows.size, "expected exactly one ERROR row; got: $rows")
    assertEquals(1, matchRows.size, "expected the good step to still emit a MATCH row; got: $rows")
  }

  @Test
  fun `session of all errored steps exits SOFTWARE`() {
    // The exit-code policy: `errorCount == logs.size → SOFTWARE`. Pin both edges —
    // single-step-all-failed (this test, logs.size=1) and multi-step-all-failed
    // (next test). Prior implementation only handled the size==1 case, silently
    // greenlit fully-failed multi-step sessions; that was flagged in PR #3082 review.
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = File(workspace, "logs/20260518_allbad").apply { mkdirs() }
    File(sessionDir, "001_step_AgentDriverLog.json").writeText("garbage {")

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, exit, "single bad step must surface SOFTWARE; stderr=${stderr()}")
  }

  @Test
  fun `multi-step session where every step errors exits SOFTWARE`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = File(workspace, "logs/20260518_multibad").apply { mkdirs() }
    File(sessionDir, "001_step_AgentDriverLog.json").writeText("garbage {")
    File(sessionDir, "002_step_AgentDriverLog.json").writeText("also garbage")
    File(sessionDir, "003_step_AgentDriverLog.json").writeText("{ \"trailblazeNodeTree\":")

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, exit, "all-errored multi-step must exit SOFTWARE; stderr=${stderr()}")
  }

  @Test
  fun `step matching multiple waypoints emits one TSV row per matched waypoint`() {
    // Test 1 only stages a single waypoint; this pins the multi-match branch
    // (`for (r in matched) println(...)`) explicitly.
    val workspace = newTempDir()
    val waypointRoot = File(workspace, "trails").apply { mkdirs() }
    File(waypointRoot, "welcome.waypoint.yaml").writeText(
      """
      id: "testpack/welcome"
      android:
        required:
          - selector:
              androidAccessibility:
                textRegex: "Welcome.*"
      """.trimIndent(),
    )
    File(waypointRoot, "screen.waypoint.yaml").writeText(
      """
      id: "testpack/any-screen"
      android:
        required:
          - selector:
              androidAccessibility:
                textRegex: ".+"
      """.trimIndent(),
    )
    val sessionDir = stageSession(
      workspace = workspace,
      stepsWithMatchingText = listOf("Welcome screen"),
    )

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    val matchedIds = stdout().lines()
      .filter { '\t' in it }
      .map { it.substringAfter('\t') }
      .filter { it != "NONE" && it != "ERROR" }
      .toSet()
    assertEquals(
      setOf("testpack/welcome", "testpack/any-screen"),
      matchedIds,
      "both staged waypoints should match this step",
    )
  }

  @Test
  fun `--rel-base overrides the path prefix in batch output`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
        "--rel-base", sessionDir.absolutePath, // use session dir itself → step filename only
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    val paths = stdout().lines().filter { '\t' in it }.map { it.substringBefore('\t') }.toSet()
    assertEquals(
      setOf("001_step_AgentDriverLog.json"),
      paths,
      "--rel-base set to the session dir should yield bare step filename",
    )
  }

  @Test
  fun `--rel-base pointing outside the log tree falls back to absolute path`() {
    val workspace = newTempDir()
    val unrelated = newTempDir() // sits at a different filesystem location
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
        "--rel-base", unrelated.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    val paths = stdout().lines().filter { '\t' in it }.map { it.substringBefore('\t') }
    assertTrue(
      paths.all { it.startsWith("/") },
      "logs outside --rel-base should fall back to absolute paths; got: $paths",
    )
  }

  @Test
  fun `empty session directory exits USAGE`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = File(workspace, "logs/empty_session").apply { mkdirs() }

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "empty session must be USAGE; stderr=${stderr()}")
    assertTrue(
      "No screen-state log files" in stderr(),
      "stderr should explain why; got: ${stderr()}",
    )
  }

  @Test
  fun `--session and --file together fail fast with USAGE`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))
    val stepFile = sessionDir.listFiles { f -> f.name.endsWith("_AgentDriverLog.json") }!!.first()

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
        "--file", stepFile.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "should reject mutex flags; stderr=${stderr()}")
    assertTrue(
      "mutually exclusive" in stderr(),
      "stderr should call out the conflict; got: ${stderr()}",
    )
  }

  @Test
  fun `neither --session nor --file fails fast with USAGE before doing discovery`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)

    val exit = runCapturing {
      execute("waypoint", "locate", "--root", waypointRoot.absolutePath)
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "missing-required-args must be USAGE; stderr=${stderr()}")
    assertTrue(
      "Provide either --file or --session" in stderr(),
      "stderr should ask for one of the flags; got: ${stderr()}",
    )
  }

  @Test
  fun `--rel-base without batch mode fails fast`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))
    val stepFile = sessionDir.listFiles { f -> f.name.endsWith("_AgentDriverLog.json") }!!.first()

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--file", stepFile.absolutePath,
        "--rel-base", workspace.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "--rel-base outside batch mode must be USAGE; stderr=${stderr()}")
    assertTrue(
      "--rel-base is only valid in batch mode" in stderr(),
      "stderr should explain; got: ${stderr()}",
    )
  }

  @Test
  fun `--rel-base must be an existing directory`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
        "--rel-base", File(workspace, "does-not-exist").absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "bad --rel-base must be USAGE; stderr=${stderr()}")
    assertTrue(
      "--rel-base must be an existing directory" in stderr(),
      "stderr should explain; got: ${stderr()}",
    )
  }

  @Test
  fun `--session alone (no --step) enters batch mode emitting TSV not human prose`() {
    // Phase 4 changed the semantic of `--session` without `--step`: previously this picked
    // the last LlmRequestLog and produced human-readable MATCH/near-miss output. It now
    // enters batch mode. Pin the new semantic so anyone who tries to revert it sees this
    // test break.
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(
      workspace = workspace,
      stepsWithMatchingText = listOf("Welcome screen", "Welcome screen again"),
    )

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    val tsvRows = stdout().lines().filter { '\t' in it }
    assertEquals(2, tsvRows.size, "expected one TSV row per step (batch mode); got: $tsvRows")
    assertNotEquals(
      true,
      stdout().contains("  MATCH "),
      "batch mode must not emit the human-readable '  MATCH ' format; got stdout=${stdout()}",
    )
  }

  @Test
  fun `escapeTsvField C-style-escapes backslash first then tab newline carriage-return`() {
    // Unit-tests the escape helper directly so a future refactor of `emitTsvRow` (or its
    // call sites) can't silently drop the protection. The backslash-first ordering is
    // load-bearing: it preserves round-trippability between a real tab (becomes `\t`) and
    // a literal `\t` two-char input (becomes `\\t`). Without that ordering, both would
    // collapse to `\t` on output and a TSV consumer couldn't tell them apart.
    val cmd = WaypointLocateCommand()
    assertEquals(
      "no-special-chars",
      cmd.escapeTsvField("no-special-chars"),
      "plain ASCII should pass through unchanged",
    )
    assertEquals(
      "with\\there",
      cmd.escapeTsvField("with\there"),
      "real tab becomes literal two-char `\\t`",
    )
    assertEquals(
      "line\\nbreak",
      cmd.escapeTsvField("line\nbreak"),
      "real newline becomes literal two-char `\\n`",
    )
    assertEquals(
      "cr\\rhere",
      cmd.escapeTsvField("cr\rhere"),
      "real CR becomes literal two-char `\\r`",
    )
    assertEquals(
      "path\\\\with\\\\backslash",
      cmd.escapeTsvField("path\\with\\backslash"),
      "real backslash becomes literal two-char `\\\\`",
    )
    assertEquals(
      "literal\\\\twins",
      cmd.escapeTsvField("literal\\twins"),
      "a literal backslash-t pair (NOT a tab) becomes `\\\\t` — distinguishable from a real tab's `\\t`",
    )
  }

  @Test
  fun `--log-suffix filters to only matching log files in batch mode`() {
    // Pipeline contract: pin batch mode to AgentDriverLog files so incidental SnapshotLog
    // or LlmRequestLog files in the session don't add unexpected rows to the shard TSV.
    // Without the filter, batch mode walks all three screen-state log types (cf.
    // SessionLogScreenState.listScreenStateLogs).
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = File(workspace, "logs/20260519_mixed").apply { mkdirs() }
    writeSyntheticStep(sessionDir, index = 1, text = "Welcome screen")
    // Mixed-type sibling — without --log-suffix this would emit its own TSV row.
    writeSyntheticStep(sessionDir, index = 2, text = "Welcome screen", suffix = "_TrailblazeSnapshotLog.json")

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
        "--log-suffix", "_AgentDriverLog.json",
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    val paths = stdout().lines().filter { '\t' in it }.map { it.substringBefore('\t') }.toSet()
    assertEquals(
      setOf("20260519_mixed/001_step_AgentDriverLog.json"),
      paths,
      "only the AgentDriverLog should produce a row; SnapshotLog must be filtered out",
    )
  }

  @Test
  fun `--log-suffix outside batch mode fails fast`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))
    val stepFile = sessionDir.listFiles { f -> f.name.endsWith("_AgentDriverLog.json") }!!.first()

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--file", stepFile.absolutePath,
        "--log-suffix", "_AgentDriverLog.json",
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "--log-suffix outside batch must be USAGE; stderr=${stderr()}")
    assertTrue(
      "--log-suffix is only valid in batch mode" in stderr(),
      "stderr should explain; got: ${stderr()}",
    )
  }

  @Test
  fun `--log-suffix with no matching files exits USAGE with a pointed message`() {
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
        "--log-suffix", "_NoSuchType.json",
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stderr=${stderr()}")
    assertTrue(
      "No log files matching --log-suffix" in stderr(),
      "stderr should mention the filter; got: ${stderr()}",
    )
  }

  @Test
  fun `--log-suffix with empty string fails fast`() {
    // Empty suffix would make `String.endsWith("")` true for every log — a no-op
    // filter that silently looks like it's working. Reject explicitly so a user
    // passing `--log-suffix ""` (maybe thinking it disables filtering) sees the
    // misuse immediately.
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
        "--log-suffix", "",
      )
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "empty --log-suffix must be USAGE; stderr=${stderr()}")
    assertTrue(
      "--log-suffix must be non-empty" in stderr(),
      "stderr should call out the empty-suffix misuse; got: ${stderr()}",
    )
  }

  @Test
  fun `--log-suffix and --rel-base compose cleanly in batch mode`() {
    // Both flags are batch-only and orthogonal — pin the combined behavior so neither
    // flag's validation accidentally short-circuits the other.
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = File(workspace, "logs/20260519_combo").apply { mkdirs() }
    writeSyntheticStep(sessionDir, index = 1, text = "Welcome screen")
    writeSyntheticStep(sessionDir, index = 2, text = "Welcome screen", suffix = "_TrailblazeSnapshotLog.json")

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
        "--rel-base", sessionDir.absolutePath, // bare filenames in output
        "--log-suffix", "_AgentDriverLog.json", // SnapshotLog filtered out
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "combined flags must compose; stderr=${stderr()}")
    val paths = stdout().lines().filter { '\t' in it }.map { it.substringBefore('\t') }.toSet()
    assertEquals(
      setOf("001_step_AgentDriverLog.json"),
      paths,
      "expected the rel-base to strip the session prefix AND log-suffix to filter to AgentDriverLog only",
    )
  }

  @Test
  fun `finally block restores Console quiet mode after a batch invocation`() {
    // The try/finally in `call()` toggles `Console.enableQuietMode()` for the duration of a
    // batch run and restores via `Console.disableQuietMode()`. The load-bearing assertion
    // is that `Console.isQuietMode()` returns to `false` after the call — TSV emission
    // alone doesn't prove it (TSV uses `println` which is independent of Console state).
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = stageSession(workspace = workspace, stepsWithMatchingText = listOf("Welcome screen"))

    // Pre-condition: Console must not already be in quiet mode (would mask any bug).
    val wasQuietBefore = Console.isQuietMode()
    if (wasQuietBefore) Console.disableQuietMode()
    try {
      assertEquals(false, Console.isQuietMode(), "test prereq: Console must start non-quiet")

      val exit1 = runCapturing {
        execute(
          "waypoint", "locate",
          "--root", waypointRoot.absolutePath,
          "--session", sessionDir.absolutePath,
        )
      }
      assertEquals(TrailblazeExitCode.SUCCESS.code, exit1, "first batch run must exit OK; stderr=${stderr()}")
      assertEquals(
        false,
        Console.isQuietMode(),
        "finally block must restore quiet mode to false after first batch invocation",
      )

      // Second invocation also restores correctly.
      capturedOut.reset()
      capturedErr.reset()
      val exit2 = runCapturing {
        execute(
          "waypoint", "locate",
          "--root", waypointRoot.absolutePath,
          "--session", sessionDir.absolutePath,
        )
      }
      assertEquals(TrailblazeExitCode.SUCCESS.code, exit2, "second batch run must exit OK; stderr=${stderr()}")
      assertEquals(
        false,
        Console.isQuietMode(),
        "finally block must restore quiet mode to false after second batch invocation too",
      )
    } finally {
      // Defensively restore the prior Console state so a regression in this test
      // can't poison subsequent tests' Console.log expectations.
      if (wasQuietBefore && !Console.isQuietMode()) Console.enableQuietMode()
      else if (!wasQuietBefore && Console.isQuietMode()) Console.disableQuietMode()
    }
  }

  @Test
  fun `batch mode without --log-suffix emits rows for every screen-state log type (framework default)`() {
    // Pin the framework default: `SessionLogScreenState.listScreenStateLogs()` returns
    // AgentDriverLog + TrailblazeSnapshotLog + TrailblazeLlmRequestLog. When no
    // `--log-suffix` is supplied, batch mode walks all three. The pipeline opts out
    // by pinning `--log-suffix _AgentDriverLog.json`; this test pins the OTHER half
    // of the contract (the no-flag default).
    val workspace = newTempDir()
    val waypointRoot = stageWaypointRoot(workspace)
    val sessionDir = File(workspace, "logs/20260519_default").apply { mkdirs() }
    writeSyntheticStep(sessionDir, index = 1, text = "Welcome screen")
    writeSyntheticStep(sessionDir, index = 2, text = "Welcome screen", suffix = "_TrailblazeSnapshotLog.json")
    writeSyntheticStep(sessionDir, index = 3, text = "Welcome screen", suffix = "_TrailblazeLlmRequestLog.json")

    val exit = runCapturing {
      execute(
        "waypoint", "locate",
        "--root", waypointRoot.absolutePath,
        "--session", sessionDir.absolutePath,
      )
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    val paths = stdout().lines().filter { '\t' in it }.map { it.substringBefore('\t') }.toSet()
    assertEquals(
      setOf(
        "20260519_default/001_step_AgentDriverLog.json",
        "20260519_default/002_TrailblazeSnapshotLog.json",
        "20260519_default/003_TrailblazeLlmRequestLog.json",
      ),
      paths,
      "framework default walks all three screen-state log types — one row per type",
    )
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  /**
   * Decode captured output as UTF-8 explicitly. ByteArrayOutputStream.toString() without
   * args uses the platform default charset; on a JVM whose `file.encoding` is anything
   * other than UTF-8 (some CI images), non-ASCII content would garble. CliOutCapture pins
   * the WRITE side to UTF-8; pin the READ side here too.
   */
  private fun stdout(): String = capturedOut.toString(Charsets.UTF_8)
  private fun stderr(): String = capturedErr.toString(Charsets.UTF_8)

  private fun parseTsv(stdoutText: String): Map<String, Set<String>> =
    stdoutText.lines()
      .filter { '\t' in it }
      .map { line -> line.split('\t', limit = 2).let { it[0] to it[1] } }
      .groupBy({ it.first }, { it.second })
      .mapValues { (_, statuses) ->
        statuses.filter { it != "NONE" && it != "ERROR" }.toSet()
      }

  @Test
  fun `a legacy v1 waypoint is rejected by the loader with an actionable failure`() {
    // Hard cut: the v1 top-level shape (required:/forbidden: with no classifier block) no longer
    // loads. WaypointLoader surfaces it as a per-file failure — not a silent drop and not a silent
    // fold — so a stray un-migrated file is caught loudly rather than quietly mis-keyed.
    val workspace = newTempDir()
    val root = File(workspace, "trails").apply { mkdirs() }
    File(root, "welcome-v1.waypoint.yaml").writeText(
      """
      id: "testpack/welcome-v1"
      required:
        - selector:
            androidAccessibility:
              textRegex: "Welcome.*"
      """.trimIndent(),
    )

    val result = WaypointLoader.loadAllResilient(root)
    assertTrue(
      result.definitions.isEmpty(),
      "a v1 file must not load; got: ${result.definitions.map { it.id }}",
    )
    assertEquals(1, result.failures.size, "the v1 file must surface as a load failure")
    assertTrue(
      result.failures.single().cause.message!!.contains("legacy v1"),
      "the failure should explain the v1 rejection: ${result.failures.single().cause.message}",
    )
  }

  private fun newTempDir(): File =
    createTempDirectory(prefix = "waypoint-locate-batch-").toFile().also { tempDirs += it }

  /**
   * Stages a workspace `--root` carrying one waypoint that matches any tree containing
   * a node whose text matches `Welcome.*`. The matcher's `textRegex` is bounded (not
   * anchored — convention 57803a76b) so partial-substring matches via the
   * `Welcome screen` / `Welcome screen again` step texts both trigger.
   */
  private fun stageWaypointRoot(workspace: File): File {
    val root = File(workspace, "trails").apply { mkdirs() }
    File(root, "welcome.waypoint.yaml").writeText(
      """
      id: "testpack/welcome"
      description: "Synthetic waypoint that matches any screen containing 'Welcome' text"
      android:
        required:
          - selector:
              androidAccessibility:
                textRegex: "Welcome.*"
      """.trimIndent(),
    )
    return root
  }

  /**
   * Builds a session dir with one `*_AgentDriverLog.json` per entry in
   * [stepsWithMatchingText]. Each log carries a synthetic trailblazeNodeTree whose
   * single child is an AndroidAccessibility node with the given text — that's the
   * minimum the matcher needs to evaluate a textRegex selector.
   */
  private fun stageSession(workspace: File, stepsWithMatchingText: List<String>): File {
    val sessionDir = File(workspace, "logs/20260518_session").apply { mkdirs() }
    stepsWithMatchingText.forEachIndexed { i, text ->
      writeSyntheticStep(sessionDir, index = i + 1, text = text)
    }
    return sessionDir
  }

  private fun writeSyntheticStep(
    sessionDir: File,
    index: Int,
    text: String,
    suffix: String = "_step_AgentDriverLog.json",
  ): File {
    val nodeJson = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(
      TrailblazeNode.serializer(),
      TrailblazeNode(
        nodeId = 1,
        driverDetail = DriverNodeDetail.AndroidAccessibility(),
        children = listOf(
          TrailblazeNode(
            nodeId = 2,
            driverDetail = DriverNodeDetail.AndroidAccessibility(text = text),
          ),
        ),
      ),
    )
    // Pad index to keep filename-sort matching emit order; readTimestamp returns null on
    // these (no `timestamp` field) so listScreenStateLogs falls back to filename sort.
    val padded = index.toString().padStart(3, '0')
    return File(sessionDir, "${padded}${suffix}").apply {
      writeText("""{"trailblazeNodeTree":$nodeJson}""")
    }
  }

  private fun execute(vararg args: String): Int {
    val cliRoot = TrailblazeCliCommand(
      appProvider = { error("appProvider should not be invoked in waypoint locate path") },
      configProvider = { error("configProvider should not be invoked in waypoint locate path") },
    )
    return CommandLine(cliRoot).setCaseInsensitiveEnumValuesAllowed(true).execute(*args)
  }

  private fun runCapturing(block: () -> Int): Int =
    CliOutCapture.withCapture(capturedOut, capturedErr, block)
}
