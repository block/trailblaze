package xyz.block.trailblaze.cli

import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import picocli.CommandLine
import xyz.block.trailblaze.ui.TrailblazePortManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppCommandProcessTest {

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

  @Test
  fun `Trail Runner launch receives the effective daemon port`() {
    val launcher = File(System.getProperty("java.io.tmpdir"), "trailblaze")
    val builder = trailRunnerLaunchProcessBuilder(launcher, 53_053)

    assertEquals(listOf(launcher.absolutePath, "trailrunner"), builder.command())
    assertEquals("53053", builder.environment()[TrailblazePortManager.HTTP_PORT_ENV_VAR])
  }

  /**
   * The shell launcher cannot read `trailblaze-settings.json`, so it would poll the default port
   * while the daemon binds the saved one. Bare `app` goes through the JVM for exactly this.
   */
  @Test
  fun `bare app opens Trail Runner on a saved non-default port`() {
    val appDataDir = tempFolder.newFolder("appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
    CliConfigHelper.writeConfig(CliConfigHelper.defaultConfig().copy(serverPort = 51_234))

    val reentry = tempFolder.newFile("reentry.txt")
    val launcher = tempFolder.newFile("trailblaze").apply {
      writeText("#!/bin/bash\nprintf '%s %s' \"\$TRAILBLAZE_PORT\" \"\$*\" > '${reentry.absolutePath}'\n")
      setExecutable(true)
    }
    val root = CommandLine(TrailblazeCliCommand({ error("unused") }, { error("unused") }))
    root.subcommands.getValue("app").getCommand<AppCommand>().launcherFinder = { launcher }

    val exitCode = root.execute("app")

    assertEquals(TrailblazeExitCode.SUCCESS.code, exitCode)
    assertEquals("51234 trailrunner", reentry.readText())
  }
}
