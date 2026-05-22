package xyz.block.trailblaze.cli.shortcut

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import picocli.CommandLine
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Focused tests for the `WaypointShortcutProposeCommand` CLI internals — the
 * helpers that ferry data between the analyzer and the on-disk sidecar shape.
 * Tests target `loadSessions` (skip-tracking + warnings) and `writeSidecars`
 * (per-proposal try/catch + `EMIT_FAILED` rejection routing) directly via
 * `internal` visibility so the integration overhead of standing up a full
 * workspace + classpath-pack-aware discovery doesn't dominate the test.
 *
 * `--fingerprint-agreement` boundary validation is exercised via the picocli
 * `call()` entry point since the validation runs before any I/O.
 */
class WaypointShortcutProposeCommandTest {

  private val tempDir: File = createTempDirectory(prefix = "shortcut-propose-command-test-").toFile()

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  // --- loadSessions ---

  @Test
  fun `loadSessions returns one ordered session per subdir of _AgentDriverLog files`() {
    // Confirms the happy path stays intact: subdirectories that hold AgentDriverLog
    // files become sessions; each session's steps are loaded in timestamp order
    // (filename-fallback because our synthetic files have no parseable timestamp).
    val sessionDir = File(tempDir, "session-a").also { it.mkdirs() }
    writeAgentDriverLog(File(sessionDir, "001_AgentDriverLog.json"), "BackPress")
    writeAgentDriverLog(File(sessionDir, "002_AgentDriverLog.json"), "BackPress")
    val cmd = WaypointShortcutProposeCommand()
    val sessions = cmd.loadSessions(tempDir)
    assertEquals(1, sessions.size, "exactly one session expected")
    assertEquals(2, sessions[0].size, "session should carry both loaded steps")
  }

  @Test
  fun `loadSessions skips a malformed AgentDriverLog file and still loads the rest of the session`() {
    // Pin the lenient skip-on-bad-file policy. A single unparseable step does NOT
    // abort the whole session; the proposer simply works with the remaining ones.
    val sessionDir = File(tempDir, "session-b").also { it.mkdirs() }
    writeAgentDriverLog(File(sessionDir, "001_AgentDriverLog.json"), "BackPress")
    File(sessionDir, "002_AgentDriverLog.json").writeText("{ not valid json")
    writeAgentDriverLog(File(sessionDir, "003_AgentDriverLog.json"), "BackPress")
    val cmd = WaypointShortcutProposeCommand()
    val sessions = cmd.loadSessions(tempDir)
    assertEquals(1, sessions.size)
    assertEquals(
      2, sessions[0].size,
      "two loadable steps must survive even though step 002 is malformed",
    )
  }

  @Test
  fun `loadSessions excludes session subdirs whose only logs are all unparseable`() {
    // Edge: if every step in a session fails to load, the session contributes
    // nothing to the proposer's input set (skip-rate would be 100%, but the
    // proposer simply sees no session at all — no spurious empty list).
    val sessionDir = File(tempDir, "session-c").also { it.mkdirs() }
    File(sessionDir, "001_AgentDriverLog.json").writeText("{ not valid")
    File(sessionDir, "002_AgentDriverLog.json").writeText("{ not valid")
    val cmd = WaypointShortcutProposeCommand()
    val sessions = cmd.loadSessions(tempDir)
    assertTrue(
      sessions.isEmpty(),
      "all-unparseable session must produce no entries; got $sessions",
    )
  }

  // --- writeSidecars ---

  @Test
  fun `writeSidecars surfaces emit failures as EMIT_FAILED entries and keeps earlier sidecars`() {
    // Load-bearing safety net: a single broken proposal must NOT abort the run.
    // Construct a survivor list where the middle proposal carries an unsupported
    // (iosMaestro) selector — ShortcutYamlEmitter.requireSelectorIsEmittable will
    // throw on it. Assert: (a) the two sibling sidecars survive on disk, (b)
    // rejected.json records the failure with kind=EMIT_FAILED.
    val outDir = File(tempDir, "proposals")
    val cmd = WaypointShortcutProposeCommand()
    cmd.outDir = outDir
    cmd.targetId = "pack"

    val good1 = proposal("pack/from-a", "pack/to-a", "good-1")
    val bad = proposal(
      from = "pack/from-b",
      to = "pack/to-b",
      key = "bad",
      toolBody = ShortcutProposer.ToolBody.TapOnElementBySelector(
        // iosMaestro triggers requireSelectorIsEmittable inside the emitter.
        selector = TrailblazeNodeSelector(
          iosMaestro = DriverNodeMatch.IosMaestro(textRegex = "^Foo$"),
        ),
        selectorDescription = "iOS",
      ),
    )
    val good2 = proposal("pack/from-c", "pack/to-c", "good-2")

    cmd.writeSidecars(
      survivors = listOf(good1, bad, good2),
      deferred = emptyList(),
      rejections = emptyList(),
      skipped = emptyList(),
    )

    // The good proposals' sidecars are on disk in numbered subdirs; the bad one
    // produced no draft yaml (the emit threw before write).
    val proposalDirs = outDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
    val yamls = proposalDirs.mapNotNull { File(it, "draft.shortcut.yaml").takeIf { f -> f.exists() } }
    assertEquals(2, yamls.size, "two sibling sidecars should survive; got dirs=${proposalDirs.map { it.name }}")
    assertTrue(yamls.any { it.readText().contains("pack/from-a") }, "good-1 sidecar must survive")
    assertTrue(yamls.any { it.readText().contains("pack/from-c") }, "good-2 sidecar must survive")

    val rejectedJson = File(outDir, "rejected.json")
    assertTrue(rejectedJson.exists(), "rejected.json must be written")
    val body = rejectedJson.readText()
    assertTrue(body.contains("EMIT_FAILED"), "rejected.json must record the EMIT_FAILED kind; got: $body")
    assertTrue(body.contains("pack/from-b"), "rejected.json must name the failed proposal's from id")
  }

  // --- packRoot resolution / loadExistingShortcuts ---

  @Test
  fun `loadExistingShortcuts finds shortcut yamls under a pack root subdir`() {
    // Pins the load-bearing packRoot resolution behavior described inline at the
    // `packRoot = if (root.name == "waypoints" && ...) root.parentFile else root`
    // branch in `call()`. Without that climb-to-parent step, the proposer scans only
    // `packs/<id>/waypoints/` and silently misses everything under sibling
    // `packs/<id>/shortcuts/`, re-proposing already-merged shortcuts every run.
    //
    // Here we test `loadExistingShortcuts` directly with the *parent* pack root and
    // confirm it picks up the shortcut yaml sitting under `shortcuts/`. A complementary
    // test for the `--root` fallback (when name != "waypoints") sits below.
    val packRoot = File(tempDir, "packs/sample").also { it.mkdirs() }
    val shortcutsDir = File(packRoot, "shortcuts").also { it.mkdirs() }
    File(shortcutsDir, "auto-foo.shortcut.yaml").writeText(
      """
      id: auto-foo
      description: "test"
      shortcut:
        from: pack/from
        to: pack/to
      parameters: []
      tools:
        - pressBackButton: {}
      """.trimIndent(),
    )
    val cmd = WaypointShortcutProposeCommand()
    val found = cmd.loadExistingShortcuts(packRoot)
    assertEquals(1, found.size, "expected to discover the on-disk shortcut; got $found")
    assertEquals("pack/from", found[0].from)
    assertEquals("pack/to", found[0].to)
    assertEquals(null, found[0].variant)
  }

  @Test
  fun `loadExistingShortcuts returns empty when root is not a directory`() {
    // Edge: `--root` pointing at a missing path (e.g. brand-new pack) must not blow
    // up — return an empty existing-shortcut set so every proposal is allowed through
    // the sibling-collision guard.
    val cmd = WaypointShortcutProposeCommand()
    val missing = File(tempDir, "does-not-exist")
    val found = cmd.loadExistingShortcuts(missing)
    assertTrue(found.isEmpty(), "missing root must return empty; got $found")
  }

  @Test
  fun `loadExistingShortcuts skips unparseable shortcut yamls and keeps the rest`() {
    // Pins the lenient policy: a single broken `*.shortcut.yaml` must not abort
    // existing-shortcut discovery — the others should still be picked up. Without
    // this, one bad file in the pack's shortcut tree would silently empty the
    // collision set and cause every proposed (from, to) tuple to look "fresh."
    val packRoot = File(tempDir, "packs/lenient").also { it.mkdirs() }
    val shortcutsDir = File(packRoot, "shortcuts").also { it.mkdirs() }
    File(shortcutsDir, "good.shortcut.yaml").writeText(
      """
      id: good
      description: "ok"
      shortcut:
        from: pack/a
        to: pack/b
      parameters: []
      tools:
        - pressBackButton: {}
      """.trimIndent(),
    )
    File(shortcutsDir, "broken.shortcut.yaml").writeText("{ not valid yaml")
    val cmd = WaypointShortcutProposeCommand()
    val found = cmd.loadExistingShortcuts(packRoot)
    assertEquals(1, found.size, "broken file must be skipped, good file retained; got $found")
    assertEquals("pack/a", found[0].from)
  }

  // --- resolvePackRoot branch matrix ---

  @Test
  fun `resolvePackRoot climbs to the parent when rootOverride is null and root is a waypoints dir`() {
    // Target-resolved path: resolveWaypointRoot returned `<workspace>/packs/X/waypoints/`
    // (because the user passed `--target X`), and we need to find sibling shortcuts
    // under `<workspace>/packs/X/shortcuts/`. Climbing to the parent is correct.
    val packDir = File(tempDir, "packs/sample").also { it.mkdirs() }
    val waypointsDir = File(packDir, "waypoints").also { it.mkdirs() }
    val cmd = WaypointShortcutProposeCommand()
    val resolved = cmd.resolvePackRoot(root = waypointsDir, rootOverride = null)
    assertEquals(packDir.absolutePath, resolved.absolutePath, "target-resolved must climb to the pack root")
  }

  @Test
  fun `resolvePackRoot does NOT climb when rootOverride is explicit even if root ends in waypoints`() {
    // Explicit-override path: the user passed `--root <some path ending in waypoints>`
    // and we must honor that. Climbing to the parent would silently scan a different
    // directory than the one they pointed at — surprising behavior that breaks
    // hand-invocations of the tool against non-standard layouts.
    val standalone = File(tempDir, "scratch/waypoints").also { it.mkdirs() }
    val cmd = WaypointShortcutProposeCommand()
    val resolved = cmd.resolvePackRoot(root = standalone, rootOverride = standalone)
    assertEquals(
      standalone.absolutePath, resolved.absolutePath,
      "explicit --root must be authoritative; do not climb to the parent",
    )
  }

  @Test
  fun `resolvePackRoot returns root unchanged when name is not waypoints`() {
    // Any root that isn't named `waypoints` (because the user passed `--root` at a
    // pack root directly, or a hand-shaped layout puts everything in one dir) is
    // returned unchanged. The climb only fires for the target-resolved
    // `<pack>/waypoints/` convention.
    val plain = File(tempDir, "scratch/some-other-name").also { it.mkdirs() }
    val cmd = WaypointShortcutProposeCommand()
    assertEquals(plain.absolutePath, cmd.resolvePackRoot(root = plain, rootOverride = null).absolutePath)
    assertEquals(plain.absolutePath, cmd.resolvePackRoot(root = plain, rootOverride = plain).absolutePath)
  }

  // --- --fingerprint-agreement validation ---

  @Test
  fun `call returns USAGE when --fingerprint-agreement is out of range`() {
    // Pins the picocli @Option validation guard. The validation runs before any
    // I/O so a minimal sessions dir is sufficient to satisfy earlier checks.
    val sessionsDir = File(tempDir, "sessions").also { it.mkdirs() }
    File(sessionsDir, "001_AgentDriverLog.json").writeText("{}") // shape doesn't matter

    val cmd = WaypointShortcutProposeCommand()
    cmd.sessionsDir = sessionsDir
    cmd.targetId = "pack"
    cmd.fingerprintAgreement = 1.5
    cmd.outDir = File(tempDir, "out-1")
    assertEquals(CommandLine.ExitCode.USAGE, cmd.call(), "agreement=1.5 must return USAGE")

    cmd.fingerprintAgreement = -0.1
    cmd.outDir = File(tempDir, "out-2")
    assertEquals(CommandLine.ExitCode.USAGE, cmd.call(), "agreement=-0.1 must return USAGE")
  }

  // --- fixtures ---

  private fun proposal(
    from: String,
    to: String,
    key: String,
    toolBody: ShortcutProposer.ToolBody = ShortcutProposer.ToolBody.PressBack,
  ): ShortcutProposer.Proposal = ShortcutProposer.Proposal(
    fromWaypointId = from,
    toWaypointId = to,
    toolBody = toolBody,
    supportSessions = 3,
    supportSteps = 3,
    actionFingerprint = "fp-$key",
    proposalKey = "shortcut|$from|$to|fp-$key",
    rationale = "test fixture $key",
  )

  /**
   * Writes a minimal `_AgentDriverLog.json` whose `action` field carries the named
   * `AgentDriverAction` variant. `viewHierarchy` and `trailblazeNodeTree` are
   * absent (loader ignores via `ignoreUnknownKeys`). Filename ordering doubles as
   * timestamp ordering since these synthetic files have no `timestamp` field.
   */
  private fun writeAgentDriverLog(file: File, variantSimpleName: String) {
    file.writeText(
      """
      {
        "viewHierarchy": {},
        "trailblazeNodeTree": null,
        "action": {"class": "xyz.block.trailblaze.api.AgentDriverAction.$variantSimpleName"},
        "deviceWidth": 1080,
        "deviceHeight": 1920
      }
      """.trimIndent(),
    )
  }
}
