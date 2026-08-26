package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Guards the contract that lets an *installed* CLI auto-start its own daemon.
 *
 * A packaged install can put a wrapper script on PATH under the name `trailblaze`, and a wrapper
 * that builds its command tree from `trailblaze --describe-commands` may treat any command with
 * published children as a group — answering a flags-only invocation of a group with its own
 * usage text and exit 0, never reaching the JVM. So two independent properties have to hold for
 * daemon auto-start to work through such a wrapper, and each is asserted here:
 *
 *  1. `app` publishes no children, so `trailblaze app --headless` / `--stop` / `--status`
 *     still reach picocli.
 *  2. Every daemon spawn names the `start` subcommand explicitly, so it routes even if some
 *     future layer does dispatch on subcommand tokens.
 *
 * Both regressed together in 2026.08.18: `app start` gained a visible subcommand, `app` began
 * publishing as a group, and the spawned child wrote wrapper usage into `daemon.log` and exited
 * while the parent polled for two minutes.
 */
class DaemonSpawnArgvTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun rootCommandLine(): CommandLine = CommandLine(
    TrailblazeCliCommand(
      appProvider = { error("appProvider must not be invoked while inspecting the command model") },
      configProvider = { error("configProvider must not be invoked while inspecting the command model") },
    ),
  )

  @Test
  fun `describe-commands publishes app as a leaf so a group dispatcher forwards its flags`() {
    val described = Json.parseToJsonElement(rootCommandLine().describeCommands()).jsonObject
    val app = described["commands"]!!.jsonArray
      .map { it.jsonObject }
      .single { it["name"]!!.jsonPrimitive.content == "app" }

    val publishedChildren = app["commands"]?.jsonArray.orEmpty()
      .map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue(
      publishedChildren.isEmpty(),
      "`app` must publish no subcommands — anything here lets a wrapper treat `app` as a group " +
        "and swallow `trailblaze app --headless` / `--stop` / `--status`. Published: $publishedChildren",
    )
  }

  @Test
  fun `app start remains parseable even though it is not published`() {
    // The synonym is hidden from the published tree, NOT removed: ~24 references across docs and
    // recovery hints spell daemon startup `app start`, and the spawn argv below relies on it.
    val app = rootCommandLine().subcommands["app"]
      ?: error("expected an `app` subcommand on the root command")
    assertTrue(
      "start" in app.subcommands.keys,
      "`app start` must stay registered; got ${app.subcommands.keys}",
    )
    assertTrue(
      app.subcommands["start"]!!.commandSpec.usageMessage().hidden(),
      "`app start` must be hidden so describeCommands() omits it",
    )
  }

  @Test
  fun `spawn argv names the start subcommand before any flag`() {
    val launcher = tempFolder.newFile("trailblaze-launcher")

    val argv = daemonSpawnArgv(launcher, foreground = true, headless = true)

    assertEquals(
      listOf(launcher.absolutePath, "app", "start", "--foreground", "--headless"),
      argv,
    )
  }

  @Test
  fun `spawn argv omits flags the caller did not ask for`() {
    val launcher = tempFolder.newFile("trailblaze-launcher")

    // The GUI launch path spawns without --headless; it must not acquire one by accident.
    assertEquals(
      listOf(launcher.absolutePath, "app", "start", "--foreground"),
      daemonSpawnArgv(launcher, foreground = true, headless = false),
    )
    assertEquals(
      listOf(launcher.absolutePath, "app", "start"),
      daemonSpawnArgv(launcher, foreground = false, headless = false),
    )
  }

  @Test
  fun `resolveLauncherBesideJar finds a packaged install's private lib layout`() {
    // Mirrors a packaged install: a private lib directory holds the uber JAR and the launcher
    // extracted from it under the name `trailblaze-launcher`. There is no `libexec/trailblaze`,
    // which is what used to push resolution out to PATH — where an installed CLI can find a
    // wrapper script rather than a launcher it can hand picocli argv to.
    val libexec = tempFolder.newFolder("libexec")
    File(libexec, "trailblaze.jar").writeText("not a real jar")
    val sibling = File(libexec, "trailblaze-launcher").apply {
      writeText("#!/bin/bash\n")
      setExecutable(true)
    }
    assertFalse(
      File(libexec, "trailblaze").exists(),
      "fixture sanity: this layout has no `trailblaze` beside the JAR",
    )

    assertEquals(sibling, resolveLauncherBesideJar(libexec))
  }

  @Test
  fun `resolveLauncherBesideJar prefers a source checkout's trailblaze wrapper`() {
    // A source/dev install ships `trailblaze` beside the JAR; it must keep winning so the
    // sibling name added for packaged installs doesn't reorder an already-working layout.
    val dir = tempFolder.newFolder("devinstall")
    val wrapper = File(dir, "trailblaze").apply {
      writeText("#!/bin/bash\n")
      setExecutable(true)
    }
    File(dir, "trailblaze-launcher").apply {
      writeText("#!/bin/bash\n")
      setExecutable(true)
    }

    assertEquals(wrapper, resolveLauncherBesideJar(dir))
  }

  @Test
  fun `resolveLauncherBesideJar ignores a non-executable candidate`() {
    val dir = tempFolder.newFolder("jaronly")
    File(dir, "trailblaze").writeText("not executable")

    // Falling through to PATH is correct here; returning a file we can't exec would turn a
    // recoverable miss into a spawn failure.
    assertEquals(null, resolveLauncherBesideJar(dir))
  }
}
