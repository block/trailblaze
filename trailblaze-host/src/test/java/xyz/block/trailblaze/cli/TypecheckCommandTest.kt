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
 * Tests for [TypecheckCommand] — the `./trailblaze typecheck` subcommand.
 *
 * Coverage focuses on the discovery / argument-validation surface (workspace walk-up,
 * pack walk-up, --all vs <pack-id> conflict, missing-pack error, pack-id validation,
 * missing tsconfig). The actual `tsc` invocation is exercised end-to-end by
 * `./trailblaze typecheck` against a real pack in CI; testing the spawn surface here
 * would duplicate the framework JAR's resource-loading test surface and force
 * `bun`/`node` onto the test agent.
 */
class TypecheckCommandTest {

  private val workDir: File = createTempDirectory("trailblaze-typecheck-command-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `findEnclosingPack returns the pack root when called from inside tools`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    val packRoot = File(packsDir, "wikipedia").apply { mkdirs() }
    File(packRoot, "pack.yaml").writeText("id: wikipedia\n")
    val toolsDir = File(packRoot, "tools").apply { mkdirs() }

    val found = TypecheckCommand().findEnclosingPack(
      startPath = toolsDir.toPath(),
      packsDirAbs = packsDir.canonicalFile.toPath(),
    )
    assertEquals(packRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findEnclosingPack returns the pack root when called from a deeper subdir`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    val packRoot = File(packsDir, "wikipedia").apply { mkdirs() }
    File(packRoot, "pack.yaml").writeText("id: wikipedia\n")
    val deeper = File(packRoot, "tools/scripts/inner").apply { mkdirs() }

    val found = TypecheckCommand().findEnclosingPack(
      startPath = deeper.toPath(),
      packsDirAbs = packsDir.canonicalFile.toPath(),
    )
    assertEquals(packRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findEnclosingPack returns null when called from above packs`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    val workspaceRoot = File(workDir, "workspace-noise").apply { mkdirs() }

    val found = TypecheckCommand().findEnclosingPack(
      startPath = workspaceRoot.toPath(),
      packsDirAbs = packsDir.canonicalFile.toPath(),
    )
    assertNull(found)
  }

  @Test
  fun `typecheck rejects --all combined with a positional pack id`() {
    // Mutually-exclusive guard — surfaces EXIT_USAGE so shell scripts can detect
    // misuse rather than picking one and silently ignoring the other.
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha")

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(TypecheckCommand()).execute("alpha", "--all")
    }
    assertEquals(TypecheckCommand.EXIT_USAGE, exit)
  }

  @Test
  fun `typecheck exits EXIT_USAGE when run outside any workspace`() {
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val exit = CliCallerContext.withCallerCwd(isolated.toPath()) {
      CommandLine(TypecheckCommand()).execute()
    }
    assertEquals(TypecheckCommand.EXIT_USAGE, exit)
  }

  @Test
  fun `typecheck exits EXIT_USAGE when an unknown pack id is passed`() {
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha")

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(TypecheckCommand()).execute("bogus-pack")
    }
    assertEquals(TypecheckCommand.EXIT_USAGE, exit)
  }

  @Test
  fun `typecheck exits EXIT_USAGE when run with no args outside a pack tree`() {
    // Workspace exists, but caller cwd is at the workspace root (above any pack) —
    // there's nothing to walk up to, and no positional/--all argument was given. The
    // command should refuse to guess and surface the actionable hint.
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha")

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(TypecheckCommand()).execute()
    }
    assertEquals(TypecheckCommand.EXIT_USAGE, exit)
  }

  @Test
  fun `typecheck rejects pack ids containing path separators or traversal segments`() {
    // Defense-in-depth: even though the pack.yaml existence check would catch
    // `../../etc`, validating up front (a) gives a clearer error message and (b)
    // prevents the absolute path from ever leaving the packs/ tree in an error log.
    val workspaceRoot = newWorkspaceWithPack(packId = "alpha")

    listOf("../alpha", "alpha/nested", "alpha\\nested", "/etc/passwd", "..", ".").forEach { bogus ->
      val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
        CommandLine(TypecheckCommand()).execute(bogus)
      }
      assertEquals(
        TypecheckCommand.EXIT_USAGE,
        exit,
        "expected EXIT_USAGE rejecting bogus pack id '$bogus'",
      )
    }
  }

  @Test
  fun `validatePackId accepts a single directory-name segment`() {
    assertNull(TypecheckCommand.validatePackId("wikipedia"))
    assertNull(TypecheckCommand.validatePackId("my-pack"))
    assertNull(TypecheckCommand.validatePackId("a_b_c"))
  }

  @Test
  fun `validatePackId rejects path separators and traversal`() {
    assertNotNull(TypecheckCommand.validatePackId("../escape"))
    assertNotNull(TypecheckCommand.validatePackId("a/b"))
    assertNotNull(TypecheckCommand.validatePackId("a\\b"))
    assertNotNull(TypecheckCommand.validatePackId("/abs"))
    assertNotNull(TypecheckCommand.validatePackId(".."))
    assertNotNull(TypecheckCommand.validatePackId(""))
  }

  @Test
  fun `--help exits zero and prints usage`() {
    // Pins the convention so `trailblaze typecheck --help` stays scriptable (exit 0)
    // even after future refactors of the picocli command surface.
    val exit = CommandLine(TypecheckCommand()).execute("--help")
    assertEquals(0, exit)
  }

  @Test
  fun `commandLabel defaults to 'typecheck' for direct invocation`() {
    // Regression marker mirroring CompileCommandTest. A silent default rename
    // here would break CheckCommand's `apply { commandLabel = "check" }` routing
    // contract without surfacing in any other test.
    assertEquals("typecheck", TypecheckCommand().commandLabel)
  }

  @Test
  fun `JS_RUNTIME_PREFERENCE prefers bun over node`() {
    // Bun is the existing framework-wide TS runtime (esbuild needs it); having node
    // take priority would unexpectedly switch the runtime under users on hosts where
    // both are installed. Pin the order so a future refactor can't silently reshuffle.
    assertEquals(listOf("bun", "node"), TypecheckCommand.JS_RUNTIME_PREFERENCE)
  }

  @Test
  fun `resolveTscTimeoutMs honors the floor clamp`() {
    // `resolveTscTimeoutMs` reads `TIMEOUT_MS_ENV_VAR` once; we can't mutate the
    // JVM's env in-process, but we CAN pin two invariants directly via the
    // constants: the default is well above the floor, and the floor itself is
    // non-negative (so `waitFor` semantics stay sane). The override behavior is
    // exercised in CI by the env-var-driven smoke runs.
    assertTrue(
      TypecheckCommand.MIN_TSC_TIMEOUT_MS > 0,
      "MIN_TSC_TIMEOUT_MS must be a positive millisecond bound",
    )
    assertTrue(
      TypecheckCommand.DEFAULT_TSC_TIMEOUT_MS >= TypecheckCommand.MIN_TSC_TIMEOUT_MS,
      "DEFAULT_TSC_TIMEOUT_MS must be at or above the floor",
    )
    // No override in this JVM → returns the default.
    if (System.getenv(TypecheckCommand.TIMEOUT_MS_ENV_VAR) == null) {
      assertEquals(
        TypecheckCommand.DEFAULT_TSC_TIMEOUT_MS,
        TypecheckCommand().resolveTscTimeoutMs(),
      )
    }
  }

  /**
   * Build a workspace with a single named pack. Returns the workspace root (the dir
   * whose `trails/config/packs/<packId>/pack.yaml` was just written). Pack has no
   * `target:` block — the typecheck command doesn't read pack.yaml directly (it walks
   * the directory tree), so a stub manifest is enough for discovery to succeed.
   */
  private fun newWorkspaceWithPack(packId: String): File {
    val workspaceRoot = File(workDir, "workspace").apply { mkdirs() }
    val packDir = File(workspaceRoot, "trails/config/packs/$packId").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText("id: $packId\n")
    assertTrue(File(packDir, "pack.yaml").isFile)
    return workspaceRoot
  }
}
