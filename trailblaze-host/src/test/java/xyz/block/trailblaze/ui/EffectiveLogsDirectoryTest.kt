package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.cli.CliConfigHelper
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * The two entry points that answer "where do session logs go" must answer the same thing.
 *
 * [TrailblazeDesktopUtil.getEffectiveLogsDirectory] is what the desktop app and the report/profile
 * CLI commands read; `CliConfigHelper` is what materializes the value into the settings file. They
 * used to disagree — a child of the app data dir here, a sibling of it there — so which directory a
 * clean install used depended on which process wrote the file first, and neither doc nor code named
 * a single default.
 */
class EffectiveLogsDirectoryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restoreAppDataDirProperty() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
  }

  /** An app-data dir shaped like production's: `<root>/.trailblaze`. */
  private fun appDataDir(name: String): File = tempFolder.newFolder(name, ".trailblaze")

  @Test
  fun `an unset logs directory derives to the app data directory's sibling`() {
    val appDataDir = appDataDir("workspace")

    val effective = TrailblazeDesktopUtil.getEffectiveLogsDirectory(
      SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        logsDirectory = null,
        appDataDirectory = appDataDir.absolutePath,
      ),
    )

    // The workspace layout the rest of the system assumes: `<root>/logs` beside
    // `<root>/.trailblaze`, NOT `<root>/.trailblaze/logs`.
    assertEquals(File(appDataDir.parentFile, "logs").canonicalPath, effective)
  }

  @Test
  fun `the reader and the CLI writer derive the same directory`() {
    val appDataDir = appDataDir("agreement")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)

    val written = CliConfigHelper.defaultConfig().logsDirectory
    val read = TrailblazeDesktopUtil.getEffectiveLogsDirectory(
      SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        logsDirectory = null,
        appDataDirectory = appDataDir.canonicalPath,
      ),
    )

    assertEquals(written, read)
  }

  @Test
  fun `the machine-global state dir keeps its logs inside it`() {
    // An installed binary has no repo to sit a `logs/` directory next to, and `~/.trailblaze/logs`
    // is what the getting-started docs publish. The sibling rule would give a bare `~/logs`.
    val globalStateDir = tempFolder.newFolder("fake-home", ".trailblaze")

    assertEquals(
      File(globalStateDir, "logs").canonicalPath,
      TrailblazeDesktopUtil.defaultLogsDirectory(globalStateDir, globalStateDir = globalStateDir),
    )
  }

  @Test
  fun `a repo-local state dir puts its logs beside it`() {
    // `<git root>/logs`, which is where the recording generators and a workspace switch both look.
    val repoStateDir = appDataDir("checkout")
    val globalStateDir = tempFolder.newFolder("unrelated-global", ".trailblaze")

    assertEquals(
      File(repoStateDir.parentFile, "logs").canonicalPath,
      TrailblazeDesktopUtil.defaultLogsDirectory(repoStateDir, globalStateDir = globalStateDir),
    )
  }

  @Test
  fun `an explicitly chosen logs directory wins over the derivation`() {
    val chosen = tempFolder.newFolder("somewhere-else", "logs")

    val effective = TrailblazeDesktopUtil.getEffectiveLogsDirectory(
      SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        logsDirectory = chosen.absolutePath,
        appDataDirectory = appDataDir("ignored").absolutePath,
      ),
    )

    assertEquals(chosen.absolutePath, effective)
  }
}
