package xyz.block.trailblaze.config.project

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths

/**
 * Tests for [TrailblazeWorkspaceConfigResolver.workspaceTrailsDir] — the `trails:` key that lets
 * a workspace name the directory holding its `.trail.yaml` files.
 *
 * The motivating case is a repo whose trails are NOT under `<root>/trails` — one that keeps them
 * in, say, `legacy-trails`. Without a declaration, every consumer either guesses the `trails/`
 * convention or falls back to a per-machine setting that names a different repo entirely.
 *
 * Two properties carry most of the weight:
 *  - one committed string means the same directory under either config-dir layout, so a repo
 *    migrating between layouts doesn't silently re-point at a different tree; and
 *  - anything unusable (no declaration, missing directory, no workspace) returns null rather
 *    than a best guess, because null means "caller keeps its own root" while a wrong non-null
 *    would strand the app somewhere it can't read.
 */
class WorkspaceTrailsDirTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Before
  fun assumeTempFolderIsScratch() {
    // Walk-up reaches the filesystem root; skip if an ancestor of the temp dir already carries a
    // trailblaze.yaml, which would anchor these workspaces somewhere unintended.
    Assume.assumeTrue(
      "An ancestor of ${tempFolder.root} already contains a trailblaze.yaml — skipping.",
      findWorkspaceRoot(tempFolder.root.toPath()) is WorkspaceRoot.Scratch,
    )
  }

  /** Creates `<root>/trailblaze-config/trailblaze.yaml` (standalone layout) with [config] as its body. */
  private fun newStandaloneWorkspace(name: String, config: String): File {
    val workspace = tempFolder.newFolder(name)
    File(workspace, TrailblazeConfigPaths.WORKSPACE_STANDALONE_CONFIG_DIR).apply { mkdirs() }
      .resolve(TrailblazeConfigPaths.CONFIG_FILENAME)
      .writeText(config)
    return workspace
  }

  /** Creates `<root>/trails/config/trailblaze.yaml` (legacy layout) with [config] as its body. */
  private fun newLegacyWorkspace(name: String, config: String): File {
    val workspace = tempFolder.newFolder(name)
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).apply {
      parentFile.mkdirs()
      writeText(config)
    }
    return workspace
  }

  private fun File.canonical(): File = toPath().toRealPath().toFile()

  private fun trailsDirFor(startDir: File): File? = TrailblazeWorkspaceConfigResolver.workspaceTrailsDir(
    fromPath = startDir.toPath(),
    consumer = "test",
    envReader = { null },
  )

  @Test
  fun `declared directory resolves against the workspace root in the standalone layout`() {
    val workspace = newStandaloneWorkspace("standalone-layout", "trails: legacy-trails")
    val legacyTrails = File(workspace, "legacy-trails").apply { mkdirs() }

    assertEquals(legacyTrails.canonical(), trailsDirFor(workspace))
  }

  @Test
  fun `the same declaration names the same directory in the legacy layout`() {
    // `trails:` resolves against the repo root under BOTH layouts, even though the legacy
    // layout's own workspace anchor sits one level deeper. A repo that moves its config dir
    // from `trails/config/` to `trailblaze-config/` must keep browsing the same trails.
    val workspace = newLegacyWorkspace("legacy-layout", "trails: legacy-trails")
    val legacyTrails = File(workspace, "legacy-trails").apply { mkdirs() }

    assertEquals(legacyTrails.canonical(), trailsDirFor(workspace))
  }

  @Test
  fun `declaration is honored from a subdirectory deep inside the workspace`() {
    // The desktop daemon anchors at its launch cwd, which is wherever the user happened to be
    // in the repo — not necessarily the root.
    val workspace = newStandaloneWorkspace("nested-start", "trails: legacy-trails")
    val legacyTrails = File(workspace, "legacy-trails").apply { mkdirs() }
    val deepDir = File(workspace, "sources/pos/checkout").apply { mkdirs() }

    assertEquals(legacyTrails.canonical(), trailsDirFor(deepDir))
  }

  @Test
  fun `an absolute declaration is used as-is`() {
    val elsewhere = tempFolder.newFolder("trails-elsewhere")
    val workspace = newStandaloneWorkspace("absolute-decl", "trails: ${elsewhere.absolutePath}")

    assertEquals(elsewhere.canonical(), trailsDirFor(workspace))
  }

  @Test
  fun `no declaration returns null rather than guessing the trails convention`() {
    // A workspace that says nothing keeps whatever root the caller already had. Falling back to
    // `<root>/trails` here would let merely launching inside any workspace re-anchor a user who
    // deliberately pointed their app somewhere else.
    val workspace = newStandaloneWorkspace("no-declaration", "defaults:\n  target: my-app\n")
    File(workspace, "trails").mkdirs()

    assertNull(trailsDirFor(workspace))
  }

  @Test
  fun `a declared directory that does not exist is ignored`() {
    // A typo, or a path only valid on a teammate's machine, must not strand the app pointing at
    // a directory it cannot read.
    val workspace = newStandaloneWorkspace("missing-dir", "trails: not-here")

    assertNull(trailsDirFor(workspace))
  }

  @Test
  fun `a declaration naming a file rather than a directory is ignored`() {
    val workspace = newStandaloneWorkspace("file-not-dir", "trails: notes.txt")
    File(workspace, "notes.txt").writeText("x")

    assertNull(trailsDirFor(workspace))
  }

  @Test
  fun `a blank declaration is treated as absent`() {
    val workspace = newStandaloneWorkspace("blank-decl", "trails: \"   \"")

    assertNull(trailsDirFor(workspace))
  }

  @Test
  fun `a scratch directory with no workspace returns null`() {
    val scratch = tempFolder.newFolder("scratch")

    assertNull(trailsDirFor(scratch))
  }

  @Test
  fun `a malformed workspace file degrades to null instead of throwing`() {
    // This sits on the desktop app's launch path — a broken committed file must not take the
    // app down, it must leave the user on their previous trails root.
    val workspace = newStandaloneWorkspace("malformed", "trails: [unclosed\n\t\tbroken: :")

    assertNull(trailsDirFor(workspace))
  }

  @Test
  fun `an arbitrarily-named TRAILBLAZE_CONFIG_DIR resolves against its own parent`() {
    // `TRAILBLAZE_CONFIG_DIR` accepts a config dir of ANY name, and `workspaceRootFromConfigDir`
    // sets `dir` to that dir's parent. Keying the layout check off "not named trailblaze-config"
    // would classify this as legacy and hand back a root one level too high — resolving
    // `trails: my-trails` to `<tmp>/my-trails` instead of `<tmp>/repo/my-trails`.
    val repo = tempFolder.newFolder("custom-config-dir-repo")
    val configDir = File(repo, "cfg").apply { mkdirs() }
    File(configDir, TrailblazeConfigPaths.CONFIG_FILENAME).writeText("trails: my-trails")
    val trails = File(repo, "my-trails").apply { mkdirs() }
    // The decoy the buggy resolution would have picked.
    File(tempFolder.root, "my-trails").mkdirs()

    val resolved = TrailblazeWorkspaceConfigResolver.workspaceTrailsDir(
      fromPath = repo.toPath(),
      consumer = "test",
      envReader = { configDir.absolutePath },
    )

    assertEquals(trails.canonical(), resolved)
  }

  @Test
  fun `the declaration carries the config dir that declared it`() {
    // A declaration decouples the trails dir from the config dir — they can be siblings — so
    // callers must not recover one by walking up from the other.
    val workspace = newStandaloneWorkspace("carries-anchor", "trails: legacy-trails")
    File(workspace, "legacy-trails").mkdirs()

    val declaration = TrailblazeWorkspaceConfigResolver.workspaceTrailsDeclaration(
      fromPath = workspace.toPath(),
      consumer = "test",
      envReader = { null },
    )

    assertEquals(
      File(workspace, TrailblazeConfigPaths.WORKSPACE_STANDALONE_CONFIG_DIR).canonical(),
      declaration?.configDir?.canonical(),
    )
    assertEquals(File(workspace, "legacy-trails").canonical(), declaration?.trailsDir)
  }

  @Test
  fun `the legacy layout's declaration carries its nested config dir, not the trails dir`() {
    // The regression this guards: deriving the config dir by walking up from a declared
    // `legacy-trails/` finds no `trailblaze-config/` and lands on `legacy-trails/config`,
    // which doesn't exist — silently emptying target and trailmap discovery.
    val workspace = newLegacyWorkspace("legacy-anchor", "trails: legacy-trails")
    File(workspace, "legacy-trails").mkdirs()

    val declaration = TrailblazeWorkspaceConfigResolver.workspaceTrailsDeclaration(
      fromPath = workspace.toPath(),
      consumer = "test",
      envReader = { null },
    )

    assertEquals(
      File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR).canonical(),
      declaration?.configDir?.canonical(),
    )
  }

  @Test
  fun `a relative declaration escaping the workspace root is ignored`() {
    // The file is committed and shared by everyone who clones the repo; `../..` would point the
    // whole team's app — and its recording writes — outside their checkout.
    val outside = tempFolder.newFolder("outside-trails")
    val workspace = newStandaloneWorkspace("escaping", "trails: ../outside-trails")
    assertTrue(outside.isDirectory, "the escape target must exist, else this passes vacuously")

    assertNull(trailsDirFor(workspace))
  }

  @Test
  fun `an absolute declaration outside the workspace root is allowed`() {
    // Absolute can't be portable across machines, so it is unambiguously deliberate.
    val outside = tempFolder.newFolder("deliberate-outside-trails")
    val workspace = newStandaloneWorkspace("absolute-outside", "trails: ${outside.absolutePath}")

    assertEquals(outside.canonical(), trailsDirFor(workspace))
  }

  @Test
  fun `a declaration naming the filesystem root is ignored`() {
    // Recursive trail scanning from `/` is never what anyone meant.
    val workspace = newStandaloneWorkspace("fs-root", "trails: /")

    assertNull(trailsDirFor(workspace))
  }

  @Test
  fun `a dot declaration resolves to the workspace root itself`() {
    // `.` is contained and a real directory, so it is honored rather than rejected — a repo
    // whose trails sit at top level is a legitimate, if unusual, layout.
    val workspace = newStandaloneWorkspace("dot-decl", "trails: .")

    assertEquals(workspace.canonical(), trailsDirFor(workspace))
  }

  @Test
  fun `TRAILBLAZE_CONFIG_DIR anchors the declaration at the env-named workspace`() {
    // The env var is authoritative for the whole workspace, so the `trails:` it declares wins
    // over whatever the cwd's own workspace says — consistent with how `resolve` treats targets.
    val cwdWorkspace = newStandaloneWorkspace("cwd-ws", "trails: cwd-trails")
    File(cwdWorkspace, "cwd-trails").mkdirs()
    val envWorkspace = newStandaloneWorkspace("env-ws", "trails: env-trails")
    val envTrails = File(envWorkspace, "env-trails").apply { mkdirs() }
    val envConfigDir = File(envWorkspace, TrailblazeConfigPaths.WORKSPACE_STANDALONE_CONFIG_DIR)

    val resolved = TrailblazeWorkspaceConfigResolver.workspaceTrailsDir(
      fromPath = cwdWorkspace.toPath(),
      consumer = "test",
      envReader = { envConfigDir.absolutePath },
    )

    assertEquals(envTrails.canonical(), resolved)
  }
}
