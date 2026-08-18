package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.WorkspaceTrailsDeclaration
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Precedence coverage for [TrailblazeDesktopUtil.getEffectiveTrailsDirectory] — the one function
 * every trails-dir consumer routes through (Trails tab, Waypoints tab, save-recording target,
 * Trail Runner's `trailsRootProvider`, MCP's `trailsDirProvider`, and the workspace anchor seed).
 *
 * The rule: an explicit user choice wins; a workspace `trails:` declaration answers only when
 * nobody has chosen. That is what makes a clean install do the right thing the first time it
 * opens a workspace without ever overriding somebody who picked a directory on purpose.
 *
 * The subtlety these tests pin is that "nobody has chosen" must remain *representable*. The field
 * used to be materialized with the derived default on every config read, so it was never null and
 * a workspace could never answer. It is no longer written, and a legacy value equal to the default
 * is treated as "not chosen" so existing installs behave like fresh ones.
 */
class EffectiveTrailsDirectoryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun configWith(trailsDirectory: String?, appDataDirectory: String?) = SavedTrailblazeAppConfig(
    selectedTrailblazeDriverTypes = emptyMap(),
    trailsDirectory = trailsDirectory,
    appDataDirectory = appDataDirectory,
  )

  /** An app-data dir whose derived default trails directory is a sibling, matching production. */
  private fun appDataDir(name: String): File = tempFolder.newFolder(name, ".trailblaze")

  @Test
  fun `an explicit choice wins over a workspace declaration`() {
    val chosen = tempFolder.newFolder("chosen-by-me", "trails")
    val declared = tempFolder.newFolder("declaring-repo", "legacy-trails")

    val effective = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(
      appConfig = configWith(chosen.absolutePath, appDataDir("ad1").absolutePath),
      workspaceTrailsDirProvider = { declared },
    )

    assertEquals(chosen.absolutePath, effective)
  }

  @Test
  fun `a workspace declaration answers when nothing is chosen`() {
    // The clean-install case: first launch inside a workspace, no stored choice.
    val declared = tempFolder.newFolder("declaring-repo-2", "legacy-trails")

    val effective = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(
      appConfig = configWith(trailsDirectory = null, appDataDirectory = appDataDir("ad2").absolutePath),
      workspaceTrailsDirProvider = { declared },
    )

    assertEquals(declared.absolutePath, effective)
  }

  @Test
  fun `a stored value equal to the derived default does not count as a choice`() {
    // Settings files written before the field stopped being materialized carry the default as if
    // it were a choice. Honoring that would make the workspace rung unreachable for every
    // existing install.
    val appData = appDataDir("ad3")
    val materializedDefault = TrailblazeDesktopUtil.defaultTrailsDirectory(appData)
    val declared = tempFolder.newFolder("declaring-repo-3", "legacy-trails")

    val effective = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(
      appConfig = configWith(materializedDefault, appData.absolutePath),
      workspaceTrailsDirProvider = { declared },
    )

    assertEquals(declared.absolutePath, effective)
  }

  @Test
  fun `falls back to the derived default when nothing is chosen and nothing is declared`() {
    val appData = appDataDir("ad4")

    val effective = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(
      appConfig = configWith(trailsDirectory = null, appDataDirectory = appData.absolutePath),
      workspaceTrailsDirProvider = { null },
    )

    assertEquals(TrailblazeDesktopUtil.defaultTrailsDirectory(appData), effective)
  }

  @Test
  fun `the derived default is a sibling of the app data directory`() {
    // Pins the shape `CliConfigHelper` has always written. The desktop fallback used to build a
    // CHILD instead; that disagreement was invisible only while the CLI materialized its value
    // into the config, and became live the moment the field stopped being written.
    val appData = appDataDir("ad5")

    assertEquals(
      File(appData.parentFile, "trails").canonicalPath,
      TrailblazeDesktopUtil.defaultTrailsDirectory(appData),
    )
  }

  @Test
  fun `a blank stored value does not count as a choice`() {
    val declared = tempFolder.newFolder("declaring-repo-4", "legacy-trails")

    val effective = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(
      appConfig = configWith("   ", appDataDir("ad6").absolutePath),
      workspaceTrailsDirProvider = { declared },
    )

    assertEquals(declared.absolutePath, effective)
  }

  @Test
  fun `hasExplicitTrailsDirectory distinguishes a choice from a default`() {
    val appData = appDataDir("ad7")
    val default = TrailblazeDesktopUtil.defaultTrailsDirectory(appData)
    val chosen = tempFolder.newFolder("chosen-elsewhere").absolutePath

    assertFalse(TrailblazeDesktopUtil.hasExplicitTrailsDirectory(configWith(null, appData.absolutePath)))
    assertFalse(TrailblazeDesktopUtil.hasExplicitTrailsDirectory(configWith(default, appData.absolutePath)))
    assertTrue(TrailblazeDesktopUtil.hasExplicitTrailsDirectory(configWith(chosen, appData.absolutePath)))
  }

  @Test
  fun `the breadcrumb says a declaration was ignored when a choice outranks it`() {
    // The log line used to fire when the declaration was *resolved*, so it claimed "Using trails
    // directory <declared>" even while Settings pointed somewhere else entirely — and it fired for
    // config-dir lookups that never asked about trails. Resolving is not deciding.
    val repo = tempFolder.newFolder("declaring-repo-5")
    val declaration = WorkspaceTrailsDeclaration(
      trailsDir = File(repo, "legacy-trails").also { it.mkdirs() },
      configDir = File(repo, "trailblaze-config").also { it.mkdirs() },
      configFile = File(repo, "trailblaze-config/trailblaze.yaml").also { it.writeText("trails: legacy-trails\n") },
    )
    val chosen = tempFolder.newFolder("chosen-over-declaration")

    val whenIgnored = TrailblazeDesktopUtil.declarationOutcomeMessage(declaration, chosen.absolutePath)
    assertTrue(whenIgnored.startsWith("Ignoring the `trails:` declaration"), whenIgnored)
    assertTrue(whenIgnored.contains(chosen.absolutePath), "must name what won: $whenIgnored")
    assertTrue(whenIgnored.contains(declaration.configFile.absolutePath), "must name the file to edit: $whenIgnored")

    val whenUsed = TrailblazeDesktopUtil.declarationOutcomeMessage(
      declaration,
      declaration.trailsDir.absolutePath,
    )
    assertTrue(whenUsed.startsWith("Using trails directory"), whenUsed)
  }

  @Test
  fun `a null declaration is not memoized, so a later-appearing directory still resolves`() {
    // Caching a miss would be permanent for the process: a workspace whose declared directory
    // shows up after launch — a branch checkout, a clone still finishing, or a directory the
    // user creates after reading the log line — would never re-resolve.
    var callCount = 0
    val declared = tempFolder.newFolder("late-repo", "legacy-trails")
    val provider: () -> File? = {
      callCount++
      if (callCount == 1) null else declared
    }
    val config = configWith(trailsDirectory = null, appDataDirectory = appDataDir("ad8").absolutePath)

    val first = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(config, provider)
    val second = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(config, provider)

    assertEquals(TrailblazeDesktopUtil.defaultTrailsDirectory(File(config.appDataDirectory!!)), first)
    assertEquals(declared.absolutePath, second, "the directory appeared; it must be picked up")
  }
}
