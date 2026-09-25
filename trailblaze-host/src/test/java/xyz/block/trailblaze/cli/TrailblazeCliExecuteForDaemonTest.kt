package xyz.block.trailblaze.cli

import kotlin.reflect.KClass
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.NoneTrailblazeLlmModelList
import xyz.block.trailblaze.logs.server.endpoints.CliExecRequest
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepoJvm
import java.io.File

/**
 * Tests for [TrailblazeCli.executeForDaemon]. Exercises the branches that
 * don't require a real [TrailblazeDesktopApp] / config to be wired:
 *  - subcommand not in allowlist → `forwarded = false`
 *  - empty argv → `forwarded = false`
 *  - allowlisted subcommand but providers never captured (run() not called) →
 *    `forwarded = true, exitCode = 1`, diagnostic on stderr
 *
 * The success and picocli-exception branches need real providers (and a live
 * device) to exercise meaningfully; covering them requires an integration
 * test harness that this unit suite does not set up.
 */
class TrailblazeCliExecuteForDaemonTest {

  @get:Rule val tempFolder = TemporaryFolder()

  /**
   * A daemon holds its app targets already. A `config` command forwarded to it must list those,
   * not build a second config and run discovery again — that rediscovery was six of the seven
   * seconds `trailblaze config show` took against a running daemon.
   *
   * The config here throws if anything reads its target set, and the live set holds a target
   * discovery could never produce, so the listing can only come from the providers the daemon
   * passed.
   */
  @Test fun `a forwarded config target listing reads the daemon's live targets, not a rebuilt config`() {
    val liveTargets = setOf(target("onlyTheDaemonKnowsThisOne"))
    val settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempFolder.root, "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { liveTargets },
      supportedDriverTypes = emptySet(),
    )
    val config = NoDiscoveryConfig(tempFolder.root, settingsRepo)

    // `config` returns instead of exiting the process only when it sees a daemon's settings
    // bridge, which is what this test is: a command running inside the daemon.
    val priorBridge = DaemonSettingsBridge.settingsRepo
    DaemonSettingsBridge.settingsRepo = settingsRepo
    val captured = try {
      captureConsole {
        TrailblazeCli.executeForDaemon(
          CliExecRequest(args = listOf("config", "target")),
          providers = TrailblazeCli.CliProviders(
            appProvider = { error("listing targets must not build the desktop app") },
            configProvider = { config },
            appTargetsProvider = { liveTargets },
          ),
        )
      }
    } finally {
      DaemonSettingsBridge.settingsRepo = priorBridge
    }

    val response = captured.result
    assertTrue(response.forwarded, "`config` is a forwarded subcommand")
    assertEquals(
      0,
      response.exitCode,
      "the listing must succeed from the daemon's providers alone. stderr: '${response.stderr}${captured.err}'",
    )
    assertContains(
      captured.out,
      "onlyTheDaemonKnowsThisOne",
      message = "the listing must show the daemon's live targets. Output: '${captured.out}'",
    )
  }

  private fun target(id: String): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(
    id = id,
    displayName = "Target $id",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  /**
   * A command outside the daemon gets a real factory for its config, and every config that factory
   * builds runs its own app-target discovery — discovery caches per config instance, not per JVM.
   * So one invocation must build one config, and the targets it lists must come off that same
   * instance. Reading the config twice and then the targets is what `config show` does.
   */
  @Test fun `a standalone command builds one config and reads its app targets off that one`() {
    val settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempFolder.root, "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = emptySet(),
    )
    var configsBuilt = 0
    val cli = TrailblazeCliCommand(
      appProvider = { error("reading config must not build the desktop app") },
      configProvider = {
        configsBuilt++
        NoDiscoveryConfig(tempFolder.root, settingsRepo, targets = setOf(target("theTargetOnThisConfig")))
      },
    )

    assertEquals(0, configsBuilt, "a command that has not asked for its config must not have built one")

    cli.configProvider()
    cli.configProvider()
    val targets = cli.appTargetsProvider()

    assertEquals(
      1,
      configsBuilt,
      "two config reads and a target listing in one invocation must share one config; built $configsBuilt",
    )
    assertEquals(
      setOf("theTargetOnThisConfig"),
      targets.map { it.id }.toSet(),
      "the listing must come off the command's own config",
    )
  }

  /**
   * A config whose app-target discovery is a test failure unless [targets] says otherwise.
   * Everything else is the smallest valid value.
   */
  private class NoDiscoveryConfig(
    dir: File,
    override val trailblazeSettingsRepo: TrailblazeSettingsRepo,
    private val targets: Set<TrailblazeHostAppTarget>? = null,
  ) : TrailblazeDesktopAppConfig(
    defaultLlmModel = TrailblazeLlmModel.fallback(TrailblazeLlmProvider.NONE, TrailblazeLlmProvider.NONE.id),
    defaultProviderModelList = NoneTrailblazeLlmModelList,
  ) {
    override val customEnvVarNames: List<String> = emptyList()
    override fun getAllSupportedLlmModelLists(): Set<TrailblazeLlmModelList> = setOf(NoneTrailblazeLlmModelList)
    override fun getCurrentlyAvailableLlmModelLists(): Set<TrailblazeLlmModelList> = setOf(NoneTrailblazeLlmModelList)
    override val logsDir: File = File(dir, "logs").also { it.mkdirs() }
    override val logsRepo: LogsRepo = LogsRepo(logsDir = logsDir, watchFileSystem = false)
    override val defaultAppDataDir: File = dir
    override val recordedTrailsRepo: RecordedTrailsRepo = RecordedTrailsRepoJvm(File(dir, "trails"))
    override val appIconProvider: AppIconProvider = AppIconProvider.DefaultAppIconProvider
    override val defaultAppTarget: TrailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
    override val availableAppTargets: Set<TrailblazeHostAppTarget>
      get() = targets ?: error("a command forwarded to the daemon must not run app-target discovery again")
    override fun rediscoverAppTargets(failFast: Boolean): Set<TrailblazeHostAppTarget> =
      error("a command forwarded to the daemon must not run app-target discovery again")
    override fun getInstalledAppIds(trailblazeDeviceId: TrailblazeDeviceId): Set<String> = emptySet()
  }

  @Test fun `empty args returns forwarded false`() {
    val response = TrailblazeCli.executeForDaemon(CliExecRequest(args = emptyList()))
    assertFalse(response.forwarded, "empty argv must not be forwarded")
    assertEquals(0, response.exitCode)
  }

  @Test fun `non-forwardable subcommand returns forwarded false`() {
    // `trail` resolves trail YAML against the caller's cwd, so it stays on the
    // JVM path — the shim falls back when forwarded=false. Use any other
    // non-allowlisted subcommand (`blaze`, `verify`, etc.) here if `trail`
    // ever joins the allowlist.
    val response = TrailblazeCli.executeForDaemon(CliExecRequest(args = listOf("trail", "some.trail.yaml")))
    assertFalse(response.forwarded)
    assertEquals(0, response.exitCode)
    // Response body should be empty — this path is a pure signal, no output.
    assertTrue(response.stdout.isEmpty())
    assertTrue(response.stderr.isEmpty())
  }

  @Test fun `tool subcommand is forwardable`() {
    // Regression for the FTUX-validator-reported `tool --yaml` failure mode:
    // before this PR, the daemon returned `forwarded = false` and the bash shim
    // fell through to a fresh-JVM spawn, which then collided with the running
    // daemon and bailed out with `failed to connect to Trailblaze daemon after
    // starting it`. Bare `tool web_navigate` happened to work because the
    // JVM-spawn fallback resolved the daemon connection a different way, but
    // `tool --yaml` would not. Routing `tool` through `/cli/exec` like
    // `snapshot` closes both paths consistently: `tool` is the same
    // single-MCP-call shape as `snapshot` and shares `cliReusableWithDevice`,
    // so there's no in-process-execution surprise.
    //
    // Asserts the same providers-null contract as the snapshot test below
    // (exitCode=1 + diagnostic stderr) so a future regression that breaks the
    // missing-providers branch specifically for `tool` would also fail here.
    val response = TrailblazeCli.executeForDaemon(CliExecRequest(args = listOf("tool", "tap", "ref=p1", "-o", "test")))
    assertTrue(
      response.forwarded,
      "`tool` must route through /cli/exec — without it, `tool --yaml` falls off the daemon path " +
        "and the JVM-spawn fallback collides with the running daemon. Got forwarded=${response.forwarded}, " +
        "stderr='${response.stderr}'",
    )
    assertEquals(1, response.exitCode, "missing-providers path must yield exitCode=1")
    assertTrue(
      response.stderr.contains("missing providers"),
      "stderr should indicate providers were never captured, got: '${response.stderr}'",
    )
  }

  @Test fun `allowlisted subcommand without providers returns exit 1 and diagnostic stderr`() {
    // `TrailblazeCli.run()` is never called in this unit test, so the
    // `appProviderRef` / `configProviderRef` remain null. `executeForDaemon`
    // must return `forwarded = true, exitCode = 1` with an explanatory
    // stderr rather than crashing or silently succeeding.
    //
    // NB: if any other test in this JVM does manage to call `run()`, this
    // test's invariant breaks. Keep `TrailblazeCli.run()` out of unit tests
    // or use separate JVMs per test class.
    val response = TrailblazeCli.executeForDaemon(CliExecRequest(args = listOf("snapshot")))
    assertTrue(response.forwarded, "allowlisted subcommand should be forwarded")
    assertEquals(1, response.exitCode, "missing-providers path must yield exitCode=1")
    assertTrue(
      response.stderr.contains("missing providers"),
      "stderr should indicate providers were never captured, got: '${response.stderr}'",
    )
  }
}
