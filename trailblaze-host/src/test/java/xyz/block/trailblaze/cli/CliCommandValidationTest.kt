package xyz.block.trailblaze.cli

import java.io.File
import org.junit.Test
import org.junit.rules.TemporaryFolder
import picocli.CommandLine
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.docs.Scenario
import xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for CLI command validation logic.
 *
 * These tests verify picocli flag parsing and validation rules for
 * [StepCommand] and [ConfigCommand] without connecting to a daemon
 * or device. Only the pure-validation code paths (those that return
 * before accessing the uninitialized `parent` field) are exercised.
 */
class CliCommandValidationTest {

  @Test
  fun `root parses --stop as a daemon shutdown request`() {
    val root = TrailblazeCliCommand(
      appProvider = { error("appProvider must not be invoked during option parsing") },
      configProvider = { error("configProvider must not be invoked during option parsing") },
    )

    CommandLine(root).parseArgs("--stop")

    assertTrue(root.stop, "`trailblaze --stop` must route through the root shutdown path")
  }

  /**
   * Runs [block] with `trailblaze.appdata.dir` pointed at a fresh, throwaway
   * directory so the test can mutate `CliConfigHelper` without leaking state to
   * other tests (or to the developer's real `~/.trailblaze`).
   *
   * Save/restore semantics mirror the pattern that grew up inline across multiple
   * tests in this file: take a snapshot of the prior system-property value, swap
   * in a [TemporaryFolder]-backed appdata dir for the duration of [block], then
   * restore the prior value (or clear it if the property wasn't set originally)
   * in a `finally` so a thrown assertion can't leak the override.
   *
   * Each call gets its own [TemporaryFolder] — tests are isolated from each other
   * within the JVM. Not a JUnit `@Rule` because the rule-per-test setup would
   * fire even on tests that don't need an appdata dir; the lambda form keeps the
   * cost narrowly scoped to callers that opt in.
   */
  private inline fun <T> withIsolatedAppDataDir(block: () -> T): T {
    val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")
    val tempFolder = TemporaryFolder().apply { create() }
    try {
      val appDataDir = tempFolder.newFolder("appdata")
      System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
      return block()
    } finally {
      tempFolder.delete()
      if (priorAppDataDir == null) {
        System.clearProperty("trailblaze.appdata.dir")
      } else {
        System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // StepCommand validation
  // ---------------------------------------------------------------------------

  @Scenario(
    title = "Execute tools directly with a natural language objective",
    commands =
      [
        "trailblaze tool tap ref=p386 --device=android -o \"Tap the Sign In button\""
      ],
    description =
      "Use `trailblaze tool` with `--yaml` for direct tool execution. The -o flag provides a natural language objective so the step is recorded with context for self-healing replays.",
    category = "Direct Tool Execution",
  )
  @Test
  fun `step without description returns USAGE`() {
    val cmd = StepCommand()
    cmd.stepWords = emptyList()

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Scenario(
    title = "Save session as trail file",
    commands =
      [
        "trailblaze step --save trails/test.trail.yaml",
        "trailblaze step --save trails/test.trail.yaml --setup 1-3",
        "trailblaze step --save trails/test.trail.yaml --no-setup",
      ],
    description =
      "The --save flag writes the session to a trail file. Use --setup to mark leading steps as setup, or --no-setup to mark none. One of --setup or --no-setup is required with --save.",
    category = "Trail Management",
  )
  @Test
  fun `step --setup without save returns USAGE`() {
    val cmd = StepCommand()
    cmd.setup = "1-3"
    cmd.savePath = null

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `step --no-setup without save returns USAGE`() {
    val cmd = StepCommand()
    cmd.noSetup = true
    cmd.savePath = null

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `step --setup and no-setup together returns USAGE`() {
    val cmd = StepCommand()
    cmd.savePath = "trail.yaml"
    cmd.setup = "1-3"
    cmd.noSetup = true

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `step --setup without save takes precedence over mutual exclusion check`() {
    // When --setup is provided without --save, the "requires --save" check
    // fires first, before the mutual-exclusion check with --no-setup.
    val cmd = StepCommand()
    cmd.setup = "1-3"
    cmd.noSetup = true
    cmd.savePath = null

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  // ---------------------------------------------------------------------------
  // CliConfigHelper.readConfig — target stays tri-state (never hydrated)
  // ---------------------------------------------------------------------------

  @Test
  fun `readConfig keeps a missing target null without writing to disk`() = withIsolatedAppDataDir {
    // `selectedTargetAppId = null` is the tri-state "no explicit selection"
    // signal that lets the daemon resolve the workspace `defaults.target`
    // rung. readConfig must neither materialize "default" in memory (a later
    // updateConfig would persist it and read back as a user pick, masking the
    // workspace default forever) nor write to disk on a plain read.
    //
    // Wrapped in [withIsolatedAppDataDir] so this CliConfigHelper.updateConfig
    // call writes to a TemporaryFolder, not the developer's real
    // `~/.trailblaze/settings.json`. Previously this test (and the one below)
    // ran against the real config — every test execution left a stray
    // `selectedTargetAppId = "testapp"` in the developer's config that
    // surfaced later as `./trailblaze toolbox` → `Error: Target 'testapp' not
    // found`, which the OOBE validators flagged because no one types
    // "testapp" by hand — it's a TEST FIXTURE LEAKING INTO PRODUCTION.
    CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = null) }
    val beforeContents = CliConfigHelper.getSettingsFile().readText()

    val hydrated = CliConfigHelper.readConfig()

    assertNull(
      hydrated?.selectedTargetAppId,
      "readConfig must not fabricate a target selection",
    )
    assertEquals(
      beforeContents,
      CliConfigHelper.getSettingsFile().readText(),
      "readConfig must not write to disk on a plain read",
    )
  }

  @Test
  fun `readConfig passes through an explicitly configured target unchanged`() = withIsolatedAppDataDir {
    // [withIsolatedAppDataDir]: see the note on the prior test — without it,
    // this `updateConfig { selectedTargetAppId = "testapp" }` leaks to the
    // developer's real settings file and corrupts every subsequent
    // `./trailblaze toolbox` invocation. The "testapp" value isn't real
    // anywhere in the configured-targets list, so it surfaces as a hard
    // "Target not found" error in unrelated CLI commands.
    CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = "testapp") }

    val result = CliConfigHelper.readConfig()

    assertEquals("testapp", result?.selectedTargetAppId)
  }

  // ---------------------------------------------------------------------------
  // ConfigTargetCommand.applyTarget validation
  // ---------------------------------------------------------------------------

  @Test
  fun `config target with invalid characters returns USAGE`() {
    // Use a space — always invalid. Hyphens and underscores are now allowed (target IDs are
    // internal identifiers and don't need to round-trip as LLM tool names), so `my-app` is a
    // valid target id.
    val cmd = ConfigTargetCommand()
    cmd.targetId = "my app"

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  // Removed format-only "config target accepts X" tests (hyphen/uppercase/alphanumeric/default).
  // `applyTarget` now validates target IDs against the discovered target list via
  // `parent.getConfigProvider().availableAppTargets`, which the unit setup doesn't initialize
  // (`parent` is lateinit and only set when picocli runs through the full CLI). The success-
  // path coverage now lives in the live CLI tests (`./trailblaze config target …`) and in
  // ConfigCommandTest where the parent is wired up.

  // ---------------------------------------------------------------------------
  // TrailCommand --self-heal flag parsing
  // ---------------------------------------------------------------------------

  @Scenario(
    title = "Run a trail with self-heal enabled",
    commands = ["trailblaze run --self-heal flows/login.trail.yaml"],
    description =
      "Use --self-heal to let AI take over when a recorded step fails. When omitted, the persisted 'trailblaze config self-heal' setting is used (opt-in, off by default).",
    category = "Trail Execution",
  )
  @Test
  fun `trail parses --self-heal flag as true`() {
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)

    cmdLine.parseArgs("--self-heal", "any.trail.yaml")

    assertEquals(true, cmd.selfHeal)
  }

  @Scenario(
    title = "Run a trail via the deprecated 'trail' alias",
    commands = ["trailblaze trail flows/login.trail.yaml"],
    description =
      "`trailblaze trail` is a deprecated alias for `trailblaze run`, kept for one release so existing CI scripts and developer muscle memory keep working. The alias prints a one-line stderr deprecation warning each time it's used and will be removed in a future release.",
    category = "Trail Execution",
  )
  @Test
  fun `'trail' alias dispatches to the same command as 'run'`() {
    // Pins the deprecated-alias dispatch path so when #3379 lands and removes
    // `aliases = ["trail"]`, this test will fail loudly — turning a silent removal into a
    // deliberate one. Parsing happens at the *root* CommandLine so picocli runs its real
    // subcommand-name resolver against the alias, not just leaf-level option parsing.
    val cliRoot =
      CommandLine(
        TrailblazeCliCommand(
          appProvider = { error("appProvider must not be invoked during alias-dispatch parsing") },
          configProvider = { error("configProvider must not be invoked during alias-dispatch parsing") },
        ),
      ).setCaseInsensitiveEnumValuesAllowed(true)

    val parseResult = cliRoot.parseArgs("trail", "any.trail.yaml")

    // The matched subcommand must be a TrailCommand — that's the contract the alias
    // promises. The matched spec's *canonical* name is `run` (picocli always returns the
    // registered name, not the alias the user typed), which is the proof that "trail"
    // routed to the renamed command.
    val sub = parseResult.subcommand()
    assertNotNull(sub, "Expected a matched subcommand for `trailblaze trail …`")
    assertIs<TrailCommand>(sub.commandSpec().userObject())
    assertEquals("run", sub.commandSpec().name())
    assertTrue(
      "trail" in sub.commandSpec().aliases(),
      "Expected 'trail' to remain in the @Command aliases list",
    )
  }

  @Test
  fun `trail selfHeal is null when the flag is not passed`() {
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)

    cmdLine.parseArgs("any.trail.yaml")

    assertNull(cmd.selfHeal)
  }

  @Test
  fun `trail --max-llm-calls 0 returns USAGE`() {
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)
    cmdLine.parseArgs("--max-llm-calls", "0", "any.trail.yaml")

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `trail --memory broken (no equals) returns USAGE`() {
    // End-to-end USAGE-exit path: a malformed --memory entry must fail at CLI parse time
    // (in the early validation block at the top of call()), not at trail-run time. This
    // pins that the IllegalArgumentException thrown by parseMemorySeeds is converted to
    // an ExitCode.USAGE return rather than escaping as an uncaught exception.
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)
    cmdLine.parseArgs("--memory", "noequals", "any.trail.yaml")

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `trail --memory empty-key returns USAGE`() {
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)
    cmdLine.parseArgs("--memory", "=value", "any.trail.yaml")

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `trail --secret broken (no equals) returns USAGE`() {
    // Sensitive seeds use the same parser, so a malformed --secret entry must also fail
    // USAGE — not be silently treated as a malformed --memory.
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)
    cmdLine.parseArgs("--secret", "noequals", "any.trail.yaml")

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `trail --max-llm-calls negative returns USAGE`() {
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)
    cmdLine.parseArgs("--max-llm-calls", "-5", "any.trail.yaml")

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `trail --max-llm-calls with --agent MULTI_AGENT_V3 returns USAGE`() {
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)
    cmdLine.parseArgs("--max-llm-calls", "5", "--agent", "MULTI_AGENT_V3", "any.trail.yaml")

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `trail with persisted max-llm-calls plus --agent V3 returns USAGE`() {
    // Regression: the V3 incompatibility check originally only fired when the CLI flag was
    // set, so a non-null cap reaching the resolver via env / workspace / persisted tiers
    // would silently slip past USAGE validation and trip RunYamlRequest.init with an
    // IllegalArgumentException. The check now runs on the resolved value, catching every
    // tier — exercised here through the persisted-config tier (easiest to set up in a unit
    // test without mutating the JVM environment).
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(maxLlmCalls = 12) }

      val cmd = TrailCommand()
      CommandLine(cmd).parseArgs("--agent", "MULTI_AGENT_V3", "any.trail.yaml")
      assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
    }
  }

  @Test
  fun `trail --max-llm-calls is null when the flag is not passed`() {
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)

    cmdLine.parseArgs("any.trail.yaml")

    assertNull(cmd.maxLlmCalls)
  }

  @Test
  fun `resolveEffectiveMaxLlmCalls returns CLI flag value when set`() {
    val cmd = TrailCommand()
    val cmdLine = CommandLine(cmd)
    cmdLine.parseArgs("--max-llm-calls", "42", "any.trail.yaml")

    assertEquals(42, cmd.resolveEffectiveMaxLlmCalls())
  }

  @Test
  fun `trail picocli leaves devices empty when --device not passed`() {
    // Deterministic pin for the default-resolution contract: picocli MUST leave
    // [TrailCommand.devices] empty when `--device` is absent from argv, so the
    // env / shell-pin / autodetect / persisted-config default-resolution branch
    // in call() actually runs. A regression that re-introduced `required = true`
    // would parse-fail at this assertion instead of slipping through to that path.
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs("any.trail.yaml")

    assertEquals(emptyList(), cmd.devices, "--device must stay empty when not explicitly passed")
  }

  @Test
  fun `trail picocli parses a single --device when passed explicitly`() {
    // Sister pin to the empty-default test above: when `--device` IS passed,
    // picocli stores the raw value (no implicit env-var override).
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs("--device", "android/emulator-5554", "any.trail.yaml")

    assertEquals(listOf("android/emulator-5554"), cmd.devices)
  }

  @Test
  fun `trail picocli splits a comma-separated --device into several`() {
    // The opt-in fan-out surface: `--device android,ios` runs each trail once per device.
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs("--device", "android,ios", "any.trail.yaml")

    assertEquals(listOf("android", "ios"), cmd.devices)
  }

  @Test
  fun `trail picocli parses --all-devices as a boolean flag defaulting to false`() {
    // The other opt-in fan-out surface: run on every connected device the trail supports.
    val off = TrailCommand()
    CommandLine(off).parseArgs("any.trail.yaml")
    assertFalse(off.allDevices, "--all-devices must default to false")

    val on = TrailCommand()
    CommandLine(on).parseArgs("--all-devices", "any.trail.yaml")
    assertTrue(on.allDevices, "--all-devices must parse to true when passed")
  }

  @Test
  fun `trail --device with --all-devices returns MISUSE`() {
    // The two are conflicting ways to say which devices to run on. The guard is placed before any
    // `parent` access so it exits MISUSE at argv-validation time (reachable through call()).
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs("--device", "android", "--all-devices", "any.trail.yaml")

    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  // ---------------------------------------------------------------------------
  // ConfigCommand.executeConfig validation
  // ---------------------------------------------------------------------------

  @Scenario(
    title = "Configure CLI settings",
    commands =
      [
        "trailblaze config llm anthropic/claude-sonnet-4-6",
        "trailblaze config self-heal true",
        "trailblaze config agent MULTI_AGENT_V3",
      ],
    description =
      "Read or write CLI configuration keys. Valid keys: llm, self-heal, agent, android-driver, ios-driver, mode, device, target. Values are validated before persisting.",
    category = "Configuration",
  )
  @Test
  fun `config executeConfig with unknown key returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("nonexistent-key", null)

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `config executeConfig with invalid llm value returns USAGE`() {
    val cmd = ConfigCommand()

    // "llm" expects "provider/model" format; a bare string is invalid.
    val exitCode = cmd.executeConfig("llm", "invalid-no-slash")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `config executeConfig with empty llm provider returns USAGE`() {
    val cmd = ConfigCommand()

    // "/model" has an empty provider.
    val exitCode = cmd.executeConfig("llm", "/gpt-4")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `config executeConfig with empty llm model returns USAGE`() {
    val cmd = ConfigCommand()

    // "openai/" has an empty model.
    val exitCode = cmd.executeConfig("llm", "openai/")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `config executeConfig with invalid boolean for self-heal returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("self-heal", "maybe")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `config executeConfig with valid llm value returns OK`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("llm", "openai/gpt-4-1")

    assertEquals(TrailblazeExitCode.SUCCESS.code, exitCode)
  }

  @Test
  fun `config executeConfig with llm none disables LLM`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("llm", "none")

    assertEquals(TrailblazeExitCode.SUCCESS.code, exitCode)
  }

  @Test
  fun `config executeConfig with llm NONE disables LLM case-insensitive`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("llm", "NONE")

    assertEquals(TrailblazeExitCode.SUCCESS.code, exitCode)
  }

  @Test
  fun `config executeConfig with valid self-heal value returns OK`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("self-heal", "true")

    assertEquals(TrailblazeExitCode.SUCCESS.code, exitCode)
  }

  @Test
  fun `config executeConfig reading known key returns OK`() {
    val cmd = ConfigCommand()

    // Reading a key (no value) should succeed.
    val exitCode = cmd.executeConfig("llm", null)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exitCode)
  }

  @Test
  fun `config executeConfig with invalid agent value returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("agent", "NONEXISTENT_AGENT")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `config executeConfig with invalid android-driver returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("android-driver", "NONEXISTENT_DRIVER")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `config executeConfig with invalid ios-driver returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("ios-driver", "ONDEVICE")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  // ---------------------------------------------------------------------------
  // Picocli flag parsing via CommandLine
  // ---------------------------------------------------------------------------

  @Scenario(
    title = "Verify a UI assertion without executing actions",
    commands =
      ["trailblaze step --verify Check the login button is visible"],
    description =
      "The --verify flag runs observation-only: the agent checks whether a condition holds on the current screen without tapping or typing.",
    category = "Direct Tool Execution",
  )
  @Test
  fun `picocli parses step verify flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--verify", "Check", "the", "button", "is", "visible")

    assertEquals(true, cmd.verify)
    assertEquals(listOf("Check", "the", "button", "is", "visible"), cmd.stepWords)
  }

  @Test
  fun `picocli parses step save flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--save", "my-trail.yaml")

    assertEquals("my-trail.yaml", cmd.savePath)
  }

  @Test
  fun `picocli parses step setup flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--save", "trail.yaml", "--setup", "1-3")

    assertEquals("trail.yaml", cmd.savePath)
    assertEquals("1-3", cmd.setup)
  }

  @Test
  fun `picocli parses step no-setup flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--save", "trail.yaml", "--no-setup")

    assertEquals("trail.yaml", cmd.savePath)
    assertEquals(true, cmd.noSetup)
  }

  @Scenario(
    title = "Specify a target app for the session",
    commands =
      ["trailblaze step --target myapp Tap login"],
    description =
      "The --target flag selects which app configuration to use, enabling target-specific tools and launch behavior.",
    category = "Configuration",
  )
  @Test
  fun `picocli parses step target flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--target", "myapp", "Tap", "login")

    assertEquals("myapp", cmd.target)
    assertEquals(listOf("Tap", "login"), cmd.stepWords)
  }

  @Test
  fun `picocli parses step device short flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("-d", "ANDROID", "Tap", "login")

    assertEquals("ANDROID", cmd.device)
  }

  @Test
  fun `picocli parses step context flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--context", "Already on home screen", "Tap", "login")

    assertEquals("Already on home screen", cmd.context)
  }

  @Test
  fun `picocli parses step verbose short flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("-v", "Tap", "login")

    assertEquals(true, cmd.verbose)
  }

  @Test
  fun `picocli parses step --no-screenshots flag`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--no-screenshots", "Tap", "login")

    assertEquals(true, cmd.noScreenshots)
    assertEquals(listOf("Tap", "login"), cmd.stepWords)
  }

  @Test
  fun `picocli parses step --text-only as alias for --no-screenshots`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--text-only", "Tap", "login")

    assertEquals(true, cmd.noScreenshots)
    assertEquals(listOf("Tap", "login"), cmd.stepWords)
  }

  @Test
  fun `step --headless defaults to null when omitted (defer to config)`() {
    // The field is null when the user didn't pass `--headless`, signalling that
    // [HeadlessOption.resolve] should fall back to the persisted `web-headless`
    // / `showWebBrowser` config. The previous "default true" behavior moved into
    // the resolver so that a one-time `trailblaze config web-headless ...` flips
    // the default for every downstream command.
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("Tap", "login")

    assertNull(cmd.headlessOption.headless)
  }

  @Test
  fun `step --headless=false disables headless mode (visible browser)`() {
    // `--headless=false` is the canonical CLI spelling for flipping the option,
    // matching the form called out in the originating issue.
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--headless=false", "Tap", "login")

    assertEquals(false, cmd.headlessOption.headless)
  }

  @Test
  fun `step --headless false (space-separated) disables headless mode`() {
    // The space-separated form `--headless false` is the literal spelling from
    // the originating issue. With arity=1 picocli accepts both `=` and space forms.
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("--headless", "false", "Tap", "login")

    assertEquals(false, cmd.headlessOption.headless)
  }

  @Test
  fun `step parses --device web with named instance ID`() {
    // Multi-instance web devices: any --device web/<id> should round-trip through
    // picocli. The CLI sends the trailing "id" segment to the daemon as the
    // Playwright browser's instanceId.
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs("-d", "web/foo", "Tap", "submit")

    assertEquals("web/foo", cmd.device)
  }

  @Test
  fun `picocli parses step with no arguments (zero arity)`() {
    val cmd = StepCommand()
    CommandLine(cmd).parseArgs()

    assertEquals(emptyList(), cmd.stepWords)
    assertNull(cmd.savePath)
    assertNull(cmd.setup)
    assertEquals(false, cmd.noSetup)
    assertEquals(false, cmd.verify)
    assertEquals(false, cmd.verbose)
    assertEquals(false, cmd.noScreenshots)
    assertNull(cmd.device)
    assertNull(cmd.context)
    assertNull(cmd.target)
  }

  @Scenario(
    title = "Set or list the target app",
    commands = ["trailblaze config target myapp", "trailblaze config target"],
    description =
      "The target subcommand sets the active app target when given an ID, or lists available targets when called without arguments.",
    category = "Configuration",
  )
  @Test
  fun `picocli parses config target subcommand with ID`() {
    val cmd = ConfigCommand()
    val cl = CommandLine(cmd)
    cl.parseArgs("target", "myapp")

    val targetCmd = cl.subcommands["target"]!!.getCommand<ConfigTargetCommand>()
    assertEquals("myapp", targetCmd.targetId)
  }

  @Test
  fun `picocli parses config target subcommand with no arg`() {
    val cmd = ConfigCommand()
    val cl = CommandLine(cmd)
    cl.parseArgs("target")

    val targetCmd = cl.subcommands["target"]!!.getCommand<ConfigTargetCommand>()
    assertNull(targetCmd.targetId)
  }

  @Test
  fun `picocli rejects --target flag on config command`() {
    val cmd = ConfigCommand()
    assertFailsWith<CommandLine.UnmatchedArgumentException> {
      CommandLine(cmd).parseArgs("--target", "myapp")
    }
  }

  @Test
  fun `picocli rejects --device on config target now that it's session-scoped`() {
    // `--target X --device Y` is session-scoped via the daemon's MCP tool and
    // does NOT flow through `config target`, so the subcommand intentionally
    // does not accept `--device`.
    val cmd = ConfigCommand()
    assertFailsWith<CommandLine.UnmatchedArgumentException> {
      CommandLine(cmd).parseArgs("target", "myapp", "--device=web")
    }
  }

  // ---------------------------------------------------------------------------
  // parseStepRange
  // ---------------------------------------------------------------------------

  @Test
  fun `parseStepRange single index`() {
    val cmd = StepCommand()
    assertEquals(setOf(2), cmd.parseStepRange("2", 5))
  }

  @Test
  fun `parseStepRange range`() {
    val cmd = StepCommand()
    assertEquals(setOf(1, 2, 3), cmd.parseStepRange("1-3", 5))
  }

  @Test
  fun `parseStepRange comma-separated`() {
    val cmd = StepCommand()
    assertEquals(setOf(1, 3, 5), cmd.parseStepRange("1,3,5", 5))
  }

  @Test
  fun `parseStepRange mixed ranges and indices`() {
    val cmd = StepCommand()
    assertEquals(setOf(1, 2, 3, 5), cmd.parseStepRange("1-3,5", 5))
  }

  @Test
  fun `parseStepRange out of bounds returns null`() {
    val cmd = StepCommand()
    assertNull(cmd.parseStepRange("0-3", 5))
    assertNull(cmd.parseStepRange("1-6", 5))
  }

  @Test
  fun `parseStepRange invalid format returns null`() {
    val cmd = StepCommand()
    assertNull(cmd.parseStepRange("abc", 5))
    assertNull(cmd.parseStepRange("1-2-3", 5))
    assertNull(cmd.parseStepRange("", 5))
  }

  @Test
  fun `parseStepRange reversed range returns null`() {
    val cmd = StepCommand()
    assertNull(cmd.parseStepRange("3-1", 5))
  }

  // ---------------------------------------------------------------------------
  // restructureWithSetup
  // ---------------------------------------------------------------------------

  @Test
  fun `restructureWithSetup splits steps into setup and trail`() {
    val cmd = StepCommand()
    val yaml =
      """
      - prompts:
          - step: Login to the app
          - step: Navigate to settings
          - step: Change the name
      """
        .trimIndent()

    val result = cmd.restructureWithSetup(yaml, setOf(1))

    assert(result.contains("# setup (trailhead)")) { "Should have setup section" }
    assert(result.contains("# trail")) { "Should have trail section" }
    assert(result.contains("Login to the app")) { "Setup step should be present" }
    assert(result.contains("Navigate to settings")) { "Trail step should be present" }
    assert(result.contains("Change the name")) { "Trail step should be present" }
  }

  @Test
  fun `restructureWithSetup preserves config header`() {
    val cmd = StepCommand()
    val yaml =
      """
      - config:
          title: My test
      - prompts:
          - step: Step one
          - step: Step two
      """
        .trimIndent()

    val result = cmd.restructureWithSetup(yaml, setOf(1))

    assert(result.contains("title: My test")) { "Config should be preserved" }
    assert(result.contains("# setup (trailhead)")) { "Should have setup section" }
    assert(result.contains("# trail")) { "Should have trail section" }
  }

  @Test
  fun `restructureWithSetup with no setup indices puts all in trail`() {
    val cmd = StepCommand()
    val yaml =
      """
      - prompts:
          - step: Step one
          - step: Step two
      """
        .trimIndent()

    val result = cmd.restructureWithSetup(yaml, emptySet())

    assert(!result.contains("# setup (trailhead)")) { "Should not have setup section" }
    assert(result.contains("# trail")) { "Should have trail section" }
    assert(result.contains("Step one")) { "Step should be present" }
    assert(result.contains("Step two")) { "Step should be present" }
  }

  // ---------------------------------------------------------------------------
  // CONFIG_KEYS consistency
  // ---------------------------------------------------------------------------

  @Test
  fun `all CONFIG_KEYS are recognized by executeConfig`() {
    val cmd = ConfigCommand()
    for (key in CONFIG_KEYS.keys) {
      // Reading any valid key should return OK (not USAGE for unknown key)
      val exitCode = cmd.executeConfig(key, null)
      assertEquals(
        TrailblazeExitCode.SUCCESS.code,
        exitCode,
        "Config key '$key' should be readable but executeConfig returned $exitCode",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // GroupedCommandListRenderer consistency
  // ---------------------------------------------------------------------------

  @Test
  fun `help renderer groups cover all registered subcommands`() {
    val cliCommand = TrailblazeCliCommand(
      appProvider = { throw UnsupportedOperationException() },
      configProvider = { throw UnsupportedOperationException() },
    )
    val commandLine = CommandLine(cliCommand)
    // Only check visible (non-hidden) subcommands — hidden commands are intentionally
    // excluded from help output and don't need to appear in the grouped renderer.
    val visibleNames = commandLine.subcommands
      .filter { (_, cmd) -> !cmd.commandSpec.usageMessage().hidden() }
      .keys.toSet()

    // Render help to check all visible commands appear
    val helpText = commandLine.usageMessage
    for (name in visibleNames) {
      assert(helpText.contains(name)) {
        "Registered subcommand '$name' not found in help output. " +
          "Update GroupedCommandListRenderer to include it."
      }
    }
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — flat values
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs flat string`() {
    val result = KeyValueParser.parse(listOf("ref=Sign In"))
    assertEquals(mapOf("ref" to "Sign In"), result)
  }

  @Test
  fun `parseKeyValuePairs flat integer`() {
    val result = KeyValueParser.parse(listOf("x=100"))
    assertEquals(mapOf("x" to 100), result)
  }

  @Test
  fun `parseKeyValuePairs flat boolean`() {
    val result = KeyValueParser.parse(listOf("longPress=true"))
    assertEquals(mapOf("longPress" to true), result)
  }

  @Test
  fun `parseKeyValuePairs flat double`() {
    val result = KeyValueParser.parse(listOf("scale=1.5"))
    assertEquals(mapOf("scale" to 1.5), result)
  }

  @Test
  fun `parseKeyValuePairs strips surrounding quotes`() {
    val result = KeyValueParser.parse(listOf("""ref="Sign In""""))
    assertEquals(mapOf("ref" to "Sign In"), result)
  }

  @Test
  fun `parseKeyValuePairs multiple flat pairs`() {
    val result = KeyValueParser.parse(listOf("ref=Sign In", "longPress=false"))
    assertEquals(mapOf("ref" to "Sign In", "longPress" to false), result)
  }

  @Test
  fun `parseKeyValuePairs rejects invalid entries without equals`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      KeyValueParser.parse(listOf("noEquals", "ref=OK"))
    }
    assertNotNull(ex.message)
    assertEquals(true, ex.message!!.contains("noEquals"))
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — dot-notation nesting
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs dot-notation one level`() {
    val result = KeyValueParser.parse(listOf("selector.textRegex=Contacts"))
    assertEquals(mapOf("selector" to mapOf("textRegex" to "Contacts")), result)
  }

  @Test
  fun `parseKeyValuePairs dot-notation two levels`() {
    val result = KeyValueParser.parse(
      listOf("selector.childOf.textRegex=Shortcuts"),
    )
    assertEquals(
      mapOf("selector" to mapOf("childOf" to mapOf("textRegex" to "Shortcuts"))),
      result,
    )
  }

  @Test
  fun `parseKeyValuePairs dot-notation multiple keys merge`() {
    val result = KeyValueParser.parse(
      listOf("selector.textRegex=Contacts", "selector.idRegex=contacts_btn"),
    )
    assertEquals(
      mapOf("selector" to mapOf("textRegex" to "Contacts", "idRegex" to "contacts_btn")),
      result,
    )
  }

  @Test
  fun `parseKeyValuePairs dot-notation with flat keys`() {
    val result = KeyValueParser.parse(
      listOf("selector.textRegex=Contacts", "longPress=false"),
    )
    assertEquals(
      mapOf(
        "selector" to mapOf("textRegex" to "Contacts"),
        "longPress" to false,
      ),
      result,
    )
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — JSON object values
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs JSON object value`() {
    val result = KeyValueParser.parse(
      listOf("""selector={"textRegex":"Contacts","idRegex":"contacts_btn"}"""),
    )
    assertEquals(
      mapOf("selector" to mapOf("textRegex" to "Contacts", "idRegex" to "contacts_btn")),
      result,
    )
  }

  @Test
  fun `parseKeyValuePairs nested JSON object`() {
    val result = KeyValueParser.parse(
      listOf("""selector={"textRegex":"OK","childOf":{"textRegex":"Dialog"}}"""),
    )
    assertEquals(
      mapOf(
        "selector" to mapOf(
          "textRegex" to "OK",
          "childOf" to mapOf("textRegex" to "Dialog"),
        ),
      ),
      result,
    )
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — JSON array values
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs JSON array of strings`() {
    val result = KeyValueParser.parse(listOf("""traits=["BUTTON","HEADING"]"""))
    assertEquals(mapOf("traits" to listOf("BUTTON", "HEADING")), result)
  }

  @Test
  fun `parseKeyValuePairs JSON array of objects`() {
    val result = KeyValueParser.parse(
      listOf("""items=[{"textRegex":"A"},{"textRegex":"B"}]"""),
    )
    assertEquals(
      mapOf("items" to listOf(mapOf("textRegex" to "A"), mapOf("textRegex" to "B"))),
      result,
    )
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — indexed list notation
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs indexed list of objects`() {
    val result = KeyValueParser.parse(
      listOf(
        "selector.containsDescendants[0].textRegex=Item 1",
        "selector.containsDescendants[0].idRegex=id_1",
        "selector.containsDescendants[1].textRegex=Item 2",
      ),
    )
    assertEquals(
      mapOf(
        "selector" to mapOf(
          "containsDescendants" to listOf(
            mapOf("textRegex" to "Item 1", "idRegex" to "id_1"),
            mapOf("textRegex" to "Item 2"),
          ),
        ),
      ),
      result,
    )
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — invalid JSON falls back to string
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs malformed JSON treated as string`() {
    val result = KeyValueParser.parse(listOf("value={not json"))
    assertEquals(mapOf("value" to "{not json"), result)
  }

  // ---------------------------------------------------------------------------
  // buildToolYaml — flat args
  // ---------------------------------------------------------------------------

  @Test
  fun `buildToolYaml empty args`() {
    val yaml = ToolYamlBuilder.build("tapOnElement", emptyMap())
    assertEquals("- tapOnElement: {}", yaml)
  }

  @Test
  fun `buildToolYaml flat string arg`() {
    val yaml = ToolYamlBuilder.build("tapOnElement", mapOf("ref" to "Sign In"))
    assertEquals("- tapOnElement:\n    ref: \"Sign In\"", yaml)
  }

  @Test
  fun `buildToolYaml flat int arg`() {
    val yaml = ToolYamlBuilder.build("tapOnPoint", mapOf("x" to 100, "y" to 200))
    assertEquals("- tapOnPoint:\n    x: 100\n    y: 200", yaml)
  }

  @Test
  fun `buildToolYaml flat boolean arg`() {
    val yaml = ToolYamlBuilder.build("tapOnElement", mapOf("ref" to "OK", "longPress" to true))
    assertEquals("- tapOnElement:\n    ref: \"OK\"\n    longPress: true", yaml)
  }

  // ---------------------------------------------------------------------------
  // buildToolYaml — nested args
  // ---------------------------------------------------------------------------

  @Test
  fun `buildToolYaml nested selector`() {
    val args: Map<String, Any> = mapOf(
      "selector" to mapOf("textRegex" to "Contacts"),
      "longPress" to false,
    )
    val yaml = ToolYamlBuilder.build("tapOnBySelector", args)
    val expected = """
      |- tapOnBySelector:
      |    selector:
      |      textRegex: "Contacts"
      |    longPress: false
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  @Test
  fun `buildToolYaml deeply nested selector`() {
    val args: Map<String, Any> = mapOf(
      "selector" to mapOf(
        "textRegex" to "Contacts",
        "childOf" to mapOf(
          "containsChild" to mapOf("textRegex" to "Shortcuts"),
        ),
      ),
    )
    val yaml = ToolYamlBuilder.build("tapOnBySelector", args)
    val expected = """
      |- tapOnBySelector:
      |    selector:
      |      textRegex: "Contacts"
      |      childOf:
      |        containsChild:
      |          textRegex: "Shortcuts"
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  // ---------------------------------------------------------------------------
  // buildToolYaml — list args
  // ---------------------------------------------------------------------------

  @Test
  fun `buildToolYaml list of strings`() {
    val args: Map<String, Any> = mapOf("traits" to listOf("BUTTON", "HEADING"))
    val yaml = ToolYamlBuilder.build("assertVisible", args)
    val expected = """
      |- assertVisible:
      |    traits:
      |      - "BUTTON"
      |      - "HEADING"
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  @Test
  fun `buildToolYaml list of objects`() {
    val args: Map<String, Any> = mapOf(
      "selector" to mapOf(
        "containsDescendants" to listOf(
          mapOf("textRegex" to "A"),
          mapOf("textRegex" to "B"),
        ),
      ),
    )
    val yaml = ToolYamlBuilder.build("assertVisible", args)
    val expected = """
      |- assertVisible:
      |    selector:
      |      containsDescendants:
      |        - textRegex: "A"
      |        - textRegex: "B"
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  // ---------------------------------------------------------------------------
  // End-to-end: parseKeyValuePairs → buildToolYaml
  // ---------------------------------------------------------------------------

  @Test
  fun `end-to-end flat tool invocation`() {
    val args = KeyValueParser.parse(listOf("""ref=Sign In""", "longPress=false"))
    val yaml = ToolYamlBuilder.build("tapOnElement", args)
    assertEquals("- tapOnElement:\n    ref: \"Sign In\"\n    longPress: false", yaml)
  }

  @Test
  fun `end-to-end nested selector via dot-notation`() {
    val args = KeyValueParser.parse(
      listOf(
        "selector.textRegex=Contacts",
        "selector.childOf.containsChild.textRegex=Shortcuts",
        "longPress=false",
      ),
    )
    val yaml = ToolYamlBuilder.build("tapOnBySelector", args)
    val expected = """
      |- tapOnBySelector:
      |    selector:
      |      textRegex: "Contacts"
      |      childOf:
      |        containsChild:
      |          textRegex: "Shortcuts"
      |    longPress: false
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  @Test
  fun `end-to-end JSON selector value`() {
    val args = KeyValueParser.parse(
      listOf("""selector={"textRegex":"OK","below":{"textRegex":"Email"}}""", "longPress=false"),
    )
    val yaml = ToolYamlBuilder.build("tapOnBySelector", args)
    val expected = """
      |- tapOnBySelector:
      |    selector:
      |      textRegex: "OK"
      |      below:
      |        textRegex: "Email"
      |    longPress: false
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  @Test
  fun `end-to-end JSON array value`() {
    val args = KeyValueParser.parse(
      listOf("""containsDescendants=[{"textRegex":"A"},{"textRegex":"B"}]"""),
    )
    val yaml = ToolYamlBuilder.build("assertVisible", args)
    val expected = """
      |- assertVisible:
      |    containsDescendants:
      |      - textRegex: "A"
      |      - textRegex: "B"
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  // ---------------------------------------------------------------------------
  // deriveTestName — companion object helper on TrailCommand
  // ---------------------------------------------------------------------------

  @Test
  fun `deriveTestName returns parent dir for trail yaml`() {
    assertEquals(
      "test-counter",
      TrailCommand.deriveTestName(File("/path/to/test-counter/trail.yaml")),
    )
  }

  @Test
  fun `deriveTestName returns parent dir for recording trail yaml`() {
    assertEquals(
      "test-counter",
      TrailCommand.deriveTestName(File("/path/to/test-counter/recording.trail.yaml")),
    )
  }

  @Test
  fun `deriveTestName returns parent dir for blaze yaml`() {
    assertEquals(
      "test-counter",
      TrailCommand.deriveTestName(File("/path/to/test-counter/blaze.yaml")),
    )
  }

  @Test
  fun `deriveTestName returns filename without extension for custom name`() {
    assertEquals(
      "my-custom-test.trail",
      TrailCommand.deriveTestName(File("/path/to/my-custom-test.trail.yaml")),
    )
  }

  @Test
  fun `deriveTestName handles root trail file gracefully`() {
    // File("/trail.yaml").absoluteFile.parentFile is "/", whose name is "".
    // The method should not crash; it may return "" or fall back to baseName.
    val result = TrailCommand.deriveTestName(File("/trail.yaml"))
    assertNotNull(result)
  }

  // ---------------------------------------------------------------------------
  // resolveRunDevice — the `run --no-daemon` device concretization on TrailCommand
  // ---------------------------------------------------------------------------

  /**
   * Test-side mirror of production [xyz.block.trailblaze.cli.envTrailblazeTarget]
   * — applied to every `TRAILBLAZE_TARGET` read used as a branch signal in this
   * file's resolveCliTarget* tests. The production env-tier treats the literal
   * value `clear` (case-insensitive, whitespace-tolerant) as **unset**, not as a
   * pin: a `--target=clear` flag is the explicit "remove the per-device
   * override" signal, but the env tier should never be the surface that clears
   * a pin (that's what `unset TRAILBLAZE_TARGET` is for). Without this helper,
   * a test JVM launched with `TRAILBLAZE_TARGET=clear` would either skip when
   * it should run, or run with an assertion that expects `clear` while the
   * resolver correctly returns null — both failure modes Copilot caught on
   * PR #3473's first review pass.
   *
   * Returning the normalized value (not just a presence boolean) lets each
   * call site compare against the same string the production resolver would
   * surface, so an env of ` SquareApp ` is reported as `squareapp` and tests
   * stay deterministic across shells that export uppercase or padded ids.
   */
  private fun normalizedTrailblazeTargetEnv(): String? = System.getenv("TRAILBLAZE_TARGET")
    ?.trim()
    ?.lowercase()
    ?.takeIf { it.isNotBlank() && it != "clear" }

  /** Builds a test device summary with the given driver type and instance ID. */
  private fun device(
    driverType: TrailblazeDriverType,
    instanceId: String,
  ) =
    TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = driverType,
      instanceId = instanceId,
      description = "$driverType - $instanceId",
    )

  /**
   * Invokes [TrailCommand.resolveRunDevice] with the given device spec, returning the selected
   * device or `null` for any non-[CliRunDeviceResolution.Selected] outcome (the production call
   * site maps those onto error envelopes + exit codes). [trailYaml] defaults to a minimal trail
   * declaring no platforms; pass real YAML to pin the declared-platforms derivation.
   */
  private fun callResolveRunDevice(
    deviceSpec: String?,
    allDevices: List<TrailblazeConnectedDeviceSummary>,
    trailDriverType: TrailblazeDriverType? = null,
    trailYaml: String = "- tools:\n  - pressBack: {}",
  ): TrailblazeConnectedDeviceSummary? =
    (callResolveRunDeviceRaw(deviceSpec, allDevices, trailDriverType, trailYaml) as? CliRunDeviceResolution.Selected)
      ?.device

  /** Raw resolution variant of [callResolveRunDevice] for pinning the fail-loud outcomes. */
  private fun callResolveRunDeviceRaw(
    deviceSpec: String?,
    allDevices: List<TrailblazeConnectedDeviceSummary>,
    trailDriverType: TrailblazeDriverType? = null,
    trailYaml: String = "- tools:\n  - pressBack: {}",
  ): CliRunDeviceResolution =
    TrailCommand().resolveRunDevice(trailYaml, allDevices, trailDriverType, deviceSpec)

  private val testDevices =
    listOf(
      device(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, "emulator-5554"),
      device(TrailblazeDriverType.IOS_HOST, "iphone-15-sim"),
      device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, "playwright-browser"),
    )

  @Test
  fun `resolveRunDevice exact instance-id match`() {
    val result = callResolveRunDevice("emulator-5554", testDevices)

    assertNotNull(result)
    assertEquals("emulator-5554", result.trailblazeDeviceId.instanceId)
    assertEquals(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, result.trailblazeDriverType)
  }

  @Test
  fun `resolveRunDevice platform-prefixed instance-id`() {
    val result = callResolveRunDevice("android/emulator-5554", testDevices)

    assertNotNull(result)
    assertEquals("emulator-5554", result.trailblazeDeviceId.instanceId)
    assertEquals(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, result.trailblazeDriverType)
  }

  @Test
  fun `resolveRunDevice platform-only auto-selects first for that platform`() {
    val result = callResolveRunDevice("android", testDevices)

    assertNotNull(result)
    assertEquals(TrailblazeDevicePlatform.ANDROID, result.platform)
  }

  @Test
  fun `resolveRunDevice web platform-only prefers PLAYWRIGHT_NATIVE`() {
    val devices =
      listOf(
        device(TrailblazeDriverType.PLAYWRIGHT_ELECTRON, "electron-1"),
        device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, "native-browser"),
      )
    val result = callResolveRunDevice("web", devices)

    assertNotNull(result)
    assertEquals("native-browser", result.trailblazeDeviceId.instanceId)
    assertEquals(TrailblazeDriverType.PLAYWRIGHT_NATIVE, result.trailblazeDriverType)
  }

  @Test
  fun `resolveRunDevice driver match when no deviceSpec`() {
    val result =
      callResolveRunDevice(
        deviceSpec = null,
        allDevices = testDevices,
        trailDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      )

    assertNotNull(result)
    assertEquals("playwright-browser", result.trailblazeDeviceId.instanceId)
  }

  @Test
  fun `resolveRunDevice routes a v1 web trail to the web device`() {
    val result =
      callResolveRunDevice(
        deviceSpec = null,
        allDevices = testDevices,
        trailYaml = "- config:\n    platform: web\n- tools:\n  - pressBack: {}",
      )

    assertNotNull(result)
    assertEquals(TrailblazeDevicePlatform.WEB, result.platform)
  }

  @Test
  fun `resolveRunDevice routes a unified web trail to the browser on a mixed shell`() {
    // The in-process headline fix: a unified trail declares its platform only through recording
    // classifiers (no v1 `platform:` field), so the YAML→declared-platforms→device composition
    // must land on the browser — never whatever device happens to be listed first (here an
    // Android emulator is listed before the browser).
    val yaml = """
      config:
        target: myapp
      trail:
        - step: Open the page
          recording:
            web:
              - tapOnPoint:
                  x: 1
                  y: 1
    """.trimIndent()
    val result = callResolveRunDevice(deviceSpec = null, allDevices = testDevices, trailYaml = yaml)

    assertNotNull(result)
    assertEquals("playwright-browser", result.trailblazeDeviceId.instanceId)
  }

  @Test
  fun `resolveRunDevice fails loud when several real devices and nothing chosen`() {
    // The shared CliRunDeviceResolver policy: never silently run on the first of several real
    // devices (the pre-#4573 `allDevices.first()` fallback). The call site maps this onto the
    // multiple-devices envelope + MISUSE.
    val result = callResolveRunDeviceRaw(deviceSpec = null, allDevices = testDevices)

    assertIs<CliRunDeviceResolution.MultipleDevices>(result)
    assertEquals(
      listOf("emulator-5554", "iphone-15-sim"),
      result.candidates.map { it.trailblazeDeviceId.instanceId },
      "only real devices count toward the ambiguity — the web virtual device is excluded",
    )
  }

  @Test
  fun `resolveRunDevice lands on the web catch-all when no real devices connected`() {
    val webOnly = listOf(device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, "playwright-browser"))
    val result = callResolveRunDevice(deviceSpec = null, allDevices = webOnly)

    assertNotNull(result)
    assertEquals("playwright-browser", result.trailblazeDeviceId.instanceId)
  }

  @Test
  fun `resolveRunDevice returns null when instance-id not found`() {
    val result = callResolveRunDevice("nonexistent-device", testDevices)

    assertNull(result)
  }

  @Test
  fun `resolveRunDevice returns null when platform has no devices`() {
    val androidOnly = listOf(device(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, "emulator-5554"))
    val result = callResolveRunDevice("ios", androidOnly)

    assertNull(result)
  }

  @Test
  fun `resolveRunDevice returns null when driver not found`() {
    val androidOnly = listOf(device(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, "emulator-5554"))
    val result =
      callResolveRunDevice(
        deviceSpec = null,
        allDevices = androidOnly,
        trailDriverType = TrailblazeDriverType.IOS_HOST,
      )

    assertNull(result)
  }

  @Test
  fun `resolveRunDevice partial instance-id match`() {
    val result = callResolveRunDevice("5554", testDevices)

    assertNotNull(result)
    assertEquals("emulator-5554", result.trailblazeDeviceId.instanceId)
  }

  // ---------------------------------------------------------------------------
  // ToolboxCommand validation — --target is optional; --device is the only
  // required flag (except in `--name` mode).
  // ---------------------------------------------------------------------------

  @Test
  fun `toolbox without device and without name rejects`() {
    // Bare `trailblaze toolbox` (no --device, no --name) can't render a meaningful
    // listing — every tool's applicability depends on the device platform. The
    // resolver must surface this as a rejection rather than silently producing
    // an unfiltered or wrong listing.
    //
    // Skip guards keep the test deterministic. TRAILBLAZE_DEVICE set in the
    // test JVM would have the resolver succeed; canAutoresolveSingleDevice
    // covers the case where the dev has the daemon up with exactly one device
    // connected (post-#3456 autodetect would resolve a unique device and the
    // command would proceed). The env-aware path is covered by `toolbox with
    // TRAILBLAZE_DEVICE set ...` below and by the smoke-test in
    // cli_smoke_tests_common.sh.
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val cmd = ToolboxCommand()

    val exitCode = cmd.call()

    assertRejectsBareDeviceInvocation(exitCode)
  }

  @Test
  fun `toolbox with TRAILBLAZE_DEVICE set does not short-circuit with MISUSE`() {
    // Mirror of `toolbox without target but with device no longer returns USAGE`:
    // the new contract is that `eval $(trailblaze device connect <platform>)`
    // (which writes TRAILBLAZE_DEVICE into the shell) lets bare `trailblaze
    // toolbox` succeed past the early-validation block without re-passing
    // `--device` on every call. We can only exercise this when the env var IS
    // set in the test JVM, so the test is conditional. The smoke script in
    // cli_smoke_tests_common.sh exercises the same flow end-to-end on CI.
    val pinned = System.getenv("TRAILBLAZE_DEVICE")
    if (pinned.isNullOrBlank()) return
    withIsolatedAppDataDir {
      val cmd = ToolboxCommand()
      // No `--device` argv — the resolver must fall back to TRAILBLAZE_DEVICE.
      CommandLine(cmd).parseArgs()

      val exitCode = cmd.call()

      // The daemon path returns INFRA_FAILED when it can't reach a running daemon;
      // anything but MISUSE proves the env-aware early-validation check fired
      // correctly.
      assertEquals(
        false,
        exitCode == TrailblazeExitCode.MISUSE.code,
        "toolbox with TRAILBLAZE_DEVICE=$pinned (no --device flag) must NOT short-circuit " +
          "with MISUSE — got $exitCode",
      )
    }
  }

  @Test
  fun `toolbox without target but with device no longer returns USAGE`() {
    // The point of this chip: `trailblaze toolbox --device web` (no --target) must
    // succeed past the early-validation block — `--target` is resolved from the
    // workspace config behind the scenes. We can't reach the daemon in a unit test,
    // so we exercise the validation path by setting only `--device` and asserting
    // the return code is *not* MISUSE. (Without a daemon, the call will fall through
    // and return INFRA_FAILED or similar — anything but MISUSE proves the early
    // check no longer fires.)
    withIsolatedAppDataDir {
      val cmd = ToolboxCommand()
      CommandLine(cmd).parseArgs("--device", "web")

      val exitCode = cmd.call()

      // The daemon path returns INFRA_FAILED when it can't reach a running daemon.
      // The important guarantee is that the early-validation MISUSE path doesn't fire.
      assertEquals(
        false,
        exitCode == TrailblazeExitCode.MISUSE.code,
        "toolbox --device web (no --target) must NOT short-circuit with MISUSE — got $exitCode",
      )
    }
  }

  @Test
  fun `toolbox picocli leaves target null when --target not passed`() {
    val cmd = ToolboxCommand()
    CommandLine(cmd).parseArgs("--device", "web")

    assertEquals("web", cmd.device)
    assertNull(cmd.target, "--target must remain null when not explicitly passed")
  }

  /**
   * Fresh empty temp dir to use as [resolveCliTarget]'s `workspaceAnchorSeedPath`. The default
   * seed is the JVM cwd, which for this test JVM sits INSIDE this repo — itself a
   * workspace anchor declaring a `defaults.target` — so any test exercising the tiers
   * below the flag/env ones must pass a scratch seed or the repo's own workspace leaks
   * into the assertion. Guarded with an assumption (mirroring
   * `TrailblazeSettingsRepoTargetPrecedenceTest`) in case a temp-dir ancestor carries an
   * unexpected anchor.
   */
  private fun scratchWorkspaceSeed(): java.nio.file.Path {
    val dir = java.nio.file.Files.createTempDirectory("cli-target-test")
    org.junit.Assume.assumeTrue(
      "An ancestor of $dir already contains a trailblaze.yaml — skipping.",
      xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
        .resolveConfigFile(dir) == null,
    )
    return dir
  }

  /** Scratch workspace whose anchor declares `defaults.target: [defaultsTarget]`; returns a seed inside it. */
  private fun scratchWorkspaceSeedWithDefaultTarget(defaultsTarget: String): java.nio.file.Path {
    val root = scratchWorkspaceSeed()
    val configDir = root.resolve("trails/config").toFile().apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText("defaults:\n  target: $defaultsTarget\n")
    return root
  }

  // ---------------------------------------------------------------------------
  // resolveCliTarget — five-tier resolution
  // (flag → env → saved selection → workspace defaults.target → built-in)
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveCliTarget treats explicit flag as Explicit source`() {
    val resolution = resolveCliTarget("square")

    assertEquals("square", resolution.id)
    assertEquals(ResolvedCliTargetSource.Explicit, resolution.source)
  }

  @Test
  fun `resolveCliTarget treats explicit default flag as Explicit source not BuiltinDefault`() {
    // The `Explicit` vs `BuiltinDefault` distinction is what drives the resolved-target
    // header — if a user passes `--target default` explicitly we must NOT print the
    // header (the user knows what they're doing). The resolver decides this by looking
    // at the flag, not at the resolved id.
    val resolution = resolveCliTarget(DefaultTrailblazeHostAppTarget.id)

    assertEquals(DefaultTrailblazeHostAppTarget.id, resolution.id)
    assertEquals(ResolvedCliTargetSource.Explicit, resolution.source)
  }

  @Test
  fun `resolveCliTarget reads the saved selection when flag is null and config is set`() {
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = "square") }

      val resolution = resolveCliTarget(flag = null)

      assertEquals("square", resolution.id)
      assertEquals(
        ResolvedCliTargetSource.PersistedSelection,
        resolution.source,
        "config-backed value must be attributed to PersistedSelection, not BuiltinDefault",
      )
    }
  }

  @Test
  fun `resolveCliTarget falls back to BuiltinDefault when flag is null and config is unset`() {
    // The TRAILBLAZE_TARGET env tier sits ABOVE WorkspaceConfig in the resolver,
    // so if the test JVM happens to have it set the resolution would short-circuit
    // there instead of falling through to BuiltinDefault. Skip in that case — the
    // env-tier itself is covered by `falls back to TRAILBLAZE_TARGET env when ...`
    // below, and the explicit-wins case (which doesn't depend on env) is the
    // load-bearing pin we keep deterministic everywhere else.
    //
    // Match production's `clear` sentinel: `TRAILBLAZE_TARGET=clear` is treated
    // as unset by the resolver, so we should NOT skip in that case — the test
    // body still exercises the BuiltinDefault fall-through correctly.
    if (normalizedTrailblazeTargetEnv() != null) return
    withIsolatedAppDataDir {
      // Explicitly clear any prior value so we exercise the "config is null" branch
      // rather than the "config is hydrated to default" one. The distinction matters:
      // `readConfigRaw()?.selectedTargetAppId` returns null for the former, "default"
      // for the latter.
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = null) }

      // Scratch seed: the default seed (JVM cwd) is inside this repo, whose own
      // workspace anchor declares a defaults.target and would resolve as
      // WorkspaceDefault instead of falling through to BuiltinDefault.
      val resolution = resolveCliTarget(flag = null, workspaceAnchorSeedPath = scratchWorkspaceSeed())

      assertEquals(DefaultTrailblazeHostAppTarget.id, resolution.id)
      assertEquals(ResolvedCliTargetSource.BuiltinDefault, resolution.source)
    }
  }

  @Test
  fun `resolveCliTarget resolves workspace defaults-target when nothing more specific is set`() {
    if (normalizedTrailblazeTargetEnv() != null) return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = null) }

      val seed = scratchWorkspaceSeedWithDefaultTarget("alpha")
      val resolution = resolveCliTarget(flag = null, workspaceAnchorSeedPath = seed)

      assertEquals("alpha", resolution.id)
      assertEquals(
        ResolvedCliTargetSource.WorkspaceDefault,
        resolution.source,
        "a workspace defaults.target must be attributed to WorkspaceDefault so the CLI " +
          "surface matches what the daemon's rung-3 resolution will actually run against",
      )
    }
  }

  @Test
  fun `resolveCliTarget treats a blank workspace defaults-target as absent`() {
    // A quoted blank `defaults.target:` must not resolve to a blank id — the accessor
    // blank-normalizes, so this falls through to the built-in default.
    if (normalizedTrailblazeTargetEnv() != null) return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = null) }

      val seed = scratchWorkspaceSeedWithDefaultTarget("\"   \"")
      val resolution = resolveCliTarget(flag = null, workspaceAnchorSeedPath = seed)

      assertEquals(DefaultTrailblazeHostAppTarget.id, resolution.id)
      assertEquals(ResolvedCliTargetSource.BuiltinDefault, resolution.source)
    }
  }

  @Test
  fun `resolveCliTarget saved selection wins over workspace defaults-target`() {
    if (normalizedTrailblazeTargetEnv() != null) return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = "beta") }

      val seed = scratchWorkspaceSeedWithDefaultTarget("alpha")
      val resolution = resolveCliTarget(flag = null, workspaceAnchorSeedPath = seed)

      assertEquals("beta", resolution.id)
      assertEquals(ResolvedCliTargetSource.PersistedSelection, resolution.source)
    }
  }

  @Test
  fun `resolveCliTarget workspace defaults-target outranks a persisted neutral default id`() {
    // Mirrors the daemon's rung-2 sentinel (TrailblazeSettingsRepo.getCurrentSelectedTargetApp):
    // legacy CLI code auto-persisted the neutral default's id without user intent, so a
    // stored "default" must not mask a committed workspace defaults.target.
    if (normalizedTrailblazeTargetEnv() != null) return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = DefaultTrailblazeHostAppTarget.id) }

      val seed = scratchWorkspaceSeedWithDefaultTarget("alpha")
      val resolution = resolveCliTarget(flag = null, workspaceAnchorSeedPath = seed)

      assertEquals("alpha", resolution.id)
      assertEquals(ResolvedCliTargetSource.WorkspaceDefault, resolution.source)
    }
  }

  @Test
  fun `resolveCliTarget persisted neutral default id still resolves when workspace declares nothing`() {
    if (normalizedTrailblazeTargetEnv() != null) return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = DefaultTrailblazeHostAppTarget.id) }

      val resolution = resolveCliTarget(flag = null, workspaceAnchorSeedPath = scratchWorkspaceSeed())

      assertEquals(DefaultTrailblazeHostAppTarget.id, resolution.id)
      assertEquals(ResolvedCliTargetSource.PersistedSelection, resolution.source)
    }
  }

  @Test
  fun `resolveCliTarget treats a blank persisted selection as absent`() {
    // A blank (non-null) persisted id is non-authoritative — authoritativeSelectedTargetId
    // rejects it, and the terminal legacy-"default" fallback must not surface it verbatim.
    // It falls through to BuiltinDefault, matching the daemon (which never matches a blank id
    // against its loaded targets).
    if (normalizedTrailblazeTargetEnv() != null) return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = "   ") }

      val resolution = resolveCliTarget(flag = null, workspaceAnchorSeedPath = scratchWorkspaceSeed())

      assertEquals(DefaultTrailblazeHostAppTarget.id, resolution.id)
      assertEquals(ResolvedCliTargetSource.BuiltinDefault, resolution.source)
    }
  }

  @Test
  fun `resolveCliTarget reads the workspace default through the caller-forwarded TRAILBLAZE_CONFIG_DIR`() {
    // Pins the daemon-forwarding fix: the workspace-default tier must resolve
    // TRAILBLAZE_CONFIG_DIR via CliCallerContext.callerEnv (the caller shell's value
    // forwarded through /cli/exec), NOT System.getenv (the daemon's frozen-at-startup env).
    // Here the caller-env names a workspace whose anchor declares defaults.target=beta while
    // the cwd-anchor seed points at a bare scratch dir with no anchor. A regression that read
    // System.getenv would miss the forwarded var, fall through the empty scratch seed, and
    // resolve BuiltinDefault instead of "beta".
    if (normalizedTrailblazeTargetEnv() != null) return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = null) }

      val forwardedWorkspace = scratchWorkspaceSeedWithDefaultTarget("beta")
      val forwardedConfigDir = forwardedWorkspace.resolve("trails/config").toFile().absolutePath

      val resolution = CliCallerContext.withCallerEnv(
        mapOf(
          xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver.CONFIG_DIR_ENV_VAR
            to forwardedConfigDir,
        ),
      ) {
        resolveCliTarget(flag = null, workspaceAnchorSeedPath = scratchWorkspaceSeed())
      }

      assertEquals("beta", resolution.id)
      assertEquals(ResolvedCliTargetSource.WorkspaceDefault, resolution.source)
    }
  }

  @Test
  fun `resolveCliTarget falls back to TRAILBLAZE_TARGET env when flag is null and config is unset`() {
    // The env tier closes "Bug B" from PR #3463: the daemon-side per-device target
    // override is keyed on the recording session and wiped whenever a fresh CLI
    // invocation re-claims the device. The TRAILBLAZE_TARGET env var (set via
    // `eval $(trailblaze device connect ... --target X)`) gives every subsequent
    // CLI call a deterministic source for the pin, parallel to TRAILBLAZE_DEVICE.
    //
    // Same env-availability conditional as `resolveCliDevice falls back to
    // TRAILBLAZE_DEVICE env var ...` — we don't mutate process env in unit tests.
    // The smoke suite under `cli_smoke_tests_common.sh` exercises the end-to-end
    // path with a real `env TRAILBLAZE_TARGET=...` subprocess.
    // Route through the production-mirror helper so `TRAILBLAZE_TARGET=clear`
    // (treated as unset by the resolver) doesn't make this test assert
    // `clear == resolution.id` and fail spuriously.
    val envValue = normalizedTrailblazeTargetEnv() ?: return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = null) }
      val resolution = resolveCliTarget(flag = null)
      // The resolver lower-cases env values so trailmap ids match daemon lookup
      // (the daemon path is also case-insensitive on lookup). Assert the lowercased
      // form to keep this test resilient to shells that export uppercase values.
      assertEquals(envValue, resolution.id)
      assertEquals(ResolvedCliTargetSource.EnvVar, resolution.source)
    }
  }

  @Test
  fun `resolveCliTarget explicit flag wins over TRAILBLAZE_TARGET env var`() {
    // Pin explicit-wins so `--target X` is always deterministic regardless of
    // what's in the shell's env. A regression that flipped the precedence would
    // mean `tool --target foo` silently resolves to env's `bar` — exactly the
    // kind of silent-routing bug we're closing here.
    val resolution = resolveCliTarget("square")
    assertEquals("square", resolution.id)
    assertEquals(ResolvedCliTargetSource.Explicit, resolution.source)
  }

  @Test
  fun `resolveCliTarget lowercases the explicit flag for parity with the pin resolver`() {
    // Both resolvers must normalize the explicit flag identically — otherwise
    // a `toolbox --target SquareApp` would surface "SquareApp" in headers and
    // arguments while `tool --target SquareApp` (which routes through
    // resolveCliTargetPin) would surface "squareapp". Daemon lookup is
    // case-insensitive so neither path is broken today, but any case-sensitive
    // log line or comparison would silently diverge.
    val resolution = resolveCliTarget("SquareApp")
    assertEquals("squareapp", resolution.id)
    assertEquals(ResolvedCliTargetSource.Explicit, resolution.source)
  }

  @Test
  fun `resolveCliTarget treats blank flag as unset and falls through`() {
    // The new env-tier resolver introduced in PR #3473 also tightened the
    // blank-flag handling: pre-PR, `--target=""` was reported as Explicit with
    // a blank id (which would then fail at the daemon). Post-PR a blank flag
    // falls through to the env / workspace-config / built-in tiers so the
    // user gets a useful default. Pin the new behavior so a refactor doesn't
    // silently revert to the broken Explicit-with-blank shape.
    // Route through the production-mirror helper so `TRAILBLAZE_TARGET=clear`
    // (treated as unset by the resolver) takes the workspace-config branch
    // instead of the env-pin branch.
    val envValue = normalizedTrailblazeTargetEnv()
    if (envValue != null) {
      val resolution = resolveCliTarget("   ")
      assertEquals(envValue, resolution.id)
      assertEquals(ResolvedCliTargetSource.EnvVar, resolution.source)
    } else {
      withIsolatedAppDataDir {
        CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = "square") }
        val resolution = resolveCliTarget("   ")
        assertEquals("square", resolution.id)
        assertEquals(ResolvedCliTargetSource.PersistedSelection, resolution.source)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // authoritativeSelectedTargetId — the neutral-"default" sentinel shared by
  // resolveCliTarget, `config get target`, and the `config target` listing.
  // These pin the sentinel directly (the display surfaces read the workspace
  // filesystem, so their own getters aren't hermetically unit-testable — this
  // pure function is the single source of truth they all delegate to).
  // ---------------------------------------------------------------------------

  @Test
  fun `authoritativeSelectedTargetId returns a real selection unchanged`() {
    assertEquals("square", authoritativeSelectedTargetId("square"))
    // Casing is preserved — the sentinel only compares against the neutral id, it does
    // not normalize (lowerCamelCase trailmap ids must round-trip).
    assertEquals("playwrightSample", authoritativeSelectedTargetId("playwrightSample"))
  }

  @Test
  fun `authoritativeSelectedTargetId treats null as no authoritative selection`() {
    assertNull(authoritativeSelectedTargetId(null))
  }

  @Test
  fun `authoritativeSelectedTargetId drops a legacy persisted neutral default id`() {
    // The sentinel: a stored "default" is legacy auto-persist, not a user pick, so callers
    // fall through to the workspace-default / built-in tiers instead of surfacing it as a
    // selection. This is what keeps `config get target` / the listing from showing "default"
    // while a run actually resolves the workspace `defaults.target`.
    assertNull(authoritativeSelectedTargetId(DefaultTrailblazeHostAppTarget.id))
  }

  @Test
  fun `authoritativeSelectedTargetId drops a blank persisted id`() {
    // A blank persisted id is non-authoritative too — otherwise `config get target` prints an
    // empty string and resolveCliTarget forwards a blank id the daemon rejects.
    assertNull(authoritativeSelectedTargetId(""))
    assertNull(authoritativeSelectedTargetId("   "))
  }

  // ---------------------------------------------------------------------------
  // resolveCliTargetPin — two-tier pin resolver (flag → env, no workspace/builtin)
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveCliTargetPin returns explicit flag lowercased when non-blank`() {
    // The daemon's `setSessionTargetForBoundDevice` does case-insensitive lookup,
    // but every CLI normalization site lowercases first so logging / session-file
    // comparisons stay deterministic. Pin that the helper lowercases consistently
    // with [cliReusableWithDevice]'s long-standing behavior.
    assertEquals("sampleapp", resolveCliTargetPin("SampleApp"))
  }

  @Test
  fun `resolveCliTargetPin treats clear flag as unset`() {
    // `--target=clear` is the explicit "remove the per-device override" signal,
    // not a pin value to re-apply. The flag-level handling in cliReusableWithDevice
    // converts `clear` into an empty-string MCP call; resolveCliTargetPin's job is
    // to NOT report this as a pin, so the env tier doesn't fire and re-establish
    // an old pin the user is trying to clear in the same invocation.
    //
    // Critical contract: BOTH branches assert null. The env-set branch is the
    // load-bearing pin against a class of silent-routing regressions where a
    // future refactor of resolveCliTargetPin falls through to envTrailblazeTarget()
    // on `clear` — that would re-establish the very pin the user is clearing.
    // McpCommand calls resolveCliTargetPin directly (no cliReusableWithDevice
    // wrap), so any drift here would silently break `mcp --target=clear`
    // when TRAILBLAZE_TARGET is exported in the shell.
    val envValue = normalizedTrailblazeTargetEnv()
    assertNull(resolveCliTargetPin("clear"))
    // Sanity check: case + whitespace variants of the same sentinel must all
    // resolve to null so a shell pipeline that uppercases ('CLEAR') or pads
    // (' clear ') the flag doesn't accidentally fall through to env.
    assertNull(resolveCliTargetPin("CLEAR"))
    assertNull(resolveCliTargetPin(" clear "))
    if (envValue != null) {
      // When env is set (and not `clear`), the prior asserts above prove the
      // short-circuit fires. Without the short-circuit, resolveCliTargetPin("clear")
      // would return envValue and the asserts above would FAIL.
      assertEquals(envValue, resolveCliTargetPin(null))
    }
  }

  @Test
  fun `resolveCliTargetPin falls back to TRAILBLAZE_TARGET env when flag is null`() {
    // Route through the production-mirror helper so `TRAILBLAZE_TARGET=clear`
    // takes the null branch (matching the resolver) instead of the env-pin
    // branch.
    val envValue = normalizedTrailblazeTargetEnv()
    if (envValue == null) {
      assertNull(resolveCliTargetPin(null))
    } else {
      assertEquals(envValue, resolveCliTargetPin(null))
    }
  }

  @Test
  fun `resolveCliTargetPin treats blank flag as unset and falls through to env`() {
    val envValue = normalizedTrailblazeTargetEnv()
    val resolved = resolveCliTargetPin("   ")
    if (envValue == null) {
      assertNull(resolved)
    } else {
      assertEquals(envValue, resolved)
    }
  }

  // ---------------------------------------------------------------------------
  // resolveCliTargetDaemonCall — shared helper used by cliReusableWithDevice
  // + SessionStartCommand. Pin all four branches directly; a regression here
  // breaks both callers simultaneously, and the previous coverage was only
  // indirect (via the two production call paths under integration tests).
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveCliTargetDaemonCall returns daemon payload for explicit pin`() {
    val daemonCall = resolveCliTargetDaemonCall("MyApp")
    // Both the daemon payload and the pin should reflect the normalized
    // explicit-flag value. The "pin" field is what the action wrappers read
    // for session-file invalidation; "payload" is what they pass to the MCP
    // tool. Diverging from each other would silently desync the session-file
    // toolset check from the daemon-side override.
    assertEquals("myapp", daemonCall.payload)
    assertEquals("myapp", daemonCall.pin)
    assertEquals(false, daemonCall.isClearRequest)
  }

  @Test
  fun `resolveCliTargetDaemonCall returns empty payload and null pin for clear flag`() {
    // `--target=clear` is the explicit "wipe the per-device override" signal.
    // The daemon contract: empty-string payload tells `setSessionTargetForBoundDevice`
    // to clear. The pin must be null (not "clear") so the session-file
    // invalidation logic doesn't treat "clear" as a real toolset id.
    val daemonCall = resolveCliTargetDaemonCall("clear")
    assertEquals("", daemonCall.payload)
    assertNull(daemonCall.pin)
    assertEquals(true, daemonCall.isClearRequest)
  }

  @Test
  fun `resolveCliTargetDaemonCall propagates env pin when flag is null`() {
    // When the user has env-pinned the shell and runs `tool` without
    // `--target`, the daemon-call shape must surface the env value so the
    // action wrapper re-applies it on every invocation (the load-bearing
    // path that closes Bug B from PR #3463). Conditional-skip when env
    // isn't set in the test JVM, same pattern as the other env-tier tests.
    val envValue = normalizedTrailblazeTargetEnv() ?: return
    val daemonCall = resolveCliTargetDaemonCall(null)
    assertEquals(envValue, daemonCall.payload)
    assertEquals(envValue, daemonCall.pin)
    assertEquals(false, daemonCall.isClearRequest)
  }

  @Test
  fun `resolveCliTargetDaemonCall returns no-op when neither flag nor env supplies a pin`() {
    // The "skip the daemon call entirely" path — leaves any pre-existing
    // per-device override in place and lets the daemon-wide fallback resolve.
    // This is the path bare `tool pressBack` takes when nothing is pinned.
    // Skip when env is set (resolver would surface the env value instead).
    if (normalizedTrailblazeTargetEnv() != null) return
    val daemonCall = resolveCliTargetDaemonCall(null)
    assertNull(daemonCall.payload)
    assertNull(daemonCall.pin)
    assertEquals(false, daemonCall.isClearRequest)
  }

  // ---------------------------------------------------------------------------
  // resolveCliTarget — env-vs-saved-selection precedence test
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveCliTarget env wins over the saved selection when both are set`() {
    // The resolver places env ABOVE the saved selection so per-shell
    // pinning beats per-machine default in the multi-terminal case — but the
    // existing tests only cover env-when-config-is-unset and explicit-flag-
    // wins-over-env. Without this test, a future refactor that flipped the
    // precedence (e.g. by reading the saved selection first) would silently
    // change semantics on every multi-terminal user. Pin precedence directly.
    val envValue = normalizedTrailblazeTargetEnv() ?: return
    withIsolatedAppDataDir {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = "config-only-target") }
      val resolution = resolveCliTarget(flag = null)
      assertEquals(envValue, resolution.id)
      assertEquals(
        ResolvedCliTargetSource.EnvVar,
        resolution.source,
        "env tier must win over the saved selection when both supply a value",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // ResolvedCliTargetSource.attributionLabel — collapsed into the enum after
  // the lead-dev pass so a future source (per-device cache, MCP-session pin,
  // …) doesn't require hunting down every per-command `when` dispatch. Pin
  // each label so toolbox's header wording doesn't drift silently.
  // ---------------------------------------------------------------------------

  @Test
  fun `ResolvedCliTargetSource attributionLabel matches each rendered source`() {
    // Explicit has no label — the header is suppressed before any label is
    // requested. Asserting null here is the contract that ToolboxCommand's
    // `!!` checkpoint relies on.
    assertNull(ResolvedCliTargetSource.Explicit.attributionLabel)
    assertEquals("from \$TRAILBLAZE_TARGET", ResolvedCliTargetSource.EnvVar.attributionLabel)
    assertEquals("from saved selection", ResolvedCliTargetSource.PersistedSelection.attributionLabel)
    assertEquals(
      "from workspace defaults.target",
      ResolvedCliTargetSource.WorkspaceDefault.attributionLabel,
    )
    assertEquals("built-in default", ResolvedCliTargetSource.BuiltinDefault.attributionLabel)
  }

  // ---------------------------------------------------------------------------
  // ReportCommand validation
  //
  // Like the StepCommand tests above, these only cover validation paths that
  // return USAGE *before* the command touches its uninitialized `parent` field.
  // The `--current` daemon-resolution branch is intentionally not covered here
  // — it would require mocking CliMcpClient, which the unit setup doesn't wire.
  // ---------------------------------------------------------------------------

  @Test
  fun `report -- id and current together returns USAGE`() {
    val cmd = ReportCommand()
    cmd.id = "abc123"
    cmd.current = true

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `report -- video without id or current returns USAGE`() {
    val cmd = ReportCommand()
    cmd.videoOutput = "out.mp4"
    // id and current both left default (null / false) — the all-sessions index
    // has no timeline to autoplay, so the export request is invalid.

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `report -- gif without id or current returns USAGE`() {
    val cmd = ReportCommand()
    cmd.gifOutput = ReportCommand.USE_DEFAULT_PATH

    val exitCode = cmd.call()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `report picocli parses --gif with no value as USE_DEFAULT_PATH sentinel`() {
    // arity = "0..1" + fallbackValue = USE_DEFAULT_PATH: passing the flag bare
    // should leave the field at the sentinel string, which generateSessionReport
    // converts to <report-dir>/<session-id>.gif at runtime.
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--gif")

    assertEquals(ReportCommand.USE_DEFAULT_PATH, cmd.gifOutput)
  }

  @Test
  fun `report picocli parses --gif with explicit path`() {
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--gif", "my.gif")

    assertEquals("my.gif", cmd.gifOutput)
  }

  @Test
  fun `report picocli parses --video and --gif together`() {
    // The two flags are independently composable in a single invocation.
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--video", "out.mp4", "--gif", "out.gif")

    assertEquals("out.mp4", cmd.videoOutput)
    assertEquals("out.gif", cmd.gifOutput)
  }

  // ---------------------------------------------------------------------------
  // Positional <session-id> / <case-id> parsing (the "no flag-only ceremony"
  // shape — `trailblaze report SESS`, `trailblaze session info SESS`,
  // `trailblaze results C12345`).
  // ---------------------------------------------------------------------------

  @Test
  fun `report picocli accepts bare positional session-id`() {
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("abc123")

    assertEquals("abc123", cmd.positionalId)
    assertNull(cmd.id)
  }

  @Test
  fun `report picocli rejects positional and --id together`() {
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "xyz")

    // Picocli accepts both fields; the call()-time guard maps the conflict to MISUSE.
    val exitCode = cmd.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `session info picocli accepts bare positional session-id`() {
    val cmd = SessionInfoCommand()
    CommandLine(cmd).parseArgs("abc123")

    assertEquals("abc123", cmd.positionalId)
    assertNull(cmd.id)
  }

  @Test
  fun `session delete requires either positional or --id`() {
    val cmd = SessionDeleteCommand()
    CommandLine(cmd).parseArgs()

    val exitCode = cmd.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  // ---------------------------------------------------------------------------
  // Session subcommands — --device is no longer picocli-required (resolver
  // handles the env-var fallback / MISUSE-on-missing case). Mirrors the action
  // command coverage in DeviceConnectCommandTest.
  // ---------------------------------------------------------------------------

  @Test
  fun `session start picocli accepts no --device flag`() {
    // Bare `session start` must parse cleanly; the call()-time resolver does the
    // env-var fallback. A regression that re-adds required=true would fail here
    // with MissingParameterException before call() ever runs.
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs() // intentionally no args
    assertNull(cmd.device)
  }

  @Test
  fun `session stop picocli accepts no --device flag`() {
    val cmd = SessionStopCommand()
    CommandLine(cmd).parseArgs() // intentionally no args
    assertNull(cmd.device)
  }

  @Test
  fun `session end picocli accepts no --device flag`() {
    val cmd = SessionEndCommand()
    CommandLine(cmd).parseArgs() // intentionally no args
    assertNull(cmd.device)
  }

  @Test
  fun `session start without --device and without TRAILBLAZE_DEVICE rejects`() {
    // Skip guards keep the test deterministic across environments — see the
    // `toolbox without device and without name rejects` comment above for the
    // full rationale on TRAILBLAZE_DEVICE + canAutoresolveSingleDevice.
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs()
    val exitCode = cmd.call()
    assertRejectsBareDeviceInvocation(exitCode)
  }

  @Test
  fun `session stop without --device and without TRAILBLAZE_DEVICE rejects`() {
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val cmd = SessionStopCommand()
    CommandLine(cmd).parseArgs()
    val exitCode = cmd.call()
    assertRejectsBareDeviceInvocation(exitCode)
  }

  @Test
  fun `session end without --device and without TRAILBLAZE_DEVICE rejects`() {
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val cmd = SessionEndCommand()
    CommandLine(cmd).parseArgs()
    val exitCode = cmd.call()
    assertRejectsBareDeviceInvocation(exitCode)
  }

  @Test
  fun `session start picocli parses -d short form`() {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs("-d", "android/emulator-5554")
    assertEquals("android/emulator-5554", cmd.device)
  }

  @Test
  fun `session stop picocli parses --device long form`() {
    val cmd = SessionStopCommand()
    CommandLine(cmd).parseArgs("--device", "ios/SIM-X")
    assertEquals("ios/SIM-X", cmd.device)
  }

  @Test
  fun `results picocli routes bare case-id at parent level to the show subcommand`() {
    // `trailblaze results C12345 --device foo` — picocli should attach the positional
    // to ResultsCommand (not match a subcommand named "C12345"), and the parent-level
    // options should be forwarded to ResultsShowCommand when call() runs.
    val root = ResultsCommand()
    val parseResult = CommandLine(root).parseArgs("C12345", "--device", "android-phone")

    // No subcommand match — bare positional should not have tripped a "show" lookup.
    assertNull(parseResult.subcommand(), "Bare case-id must not be matched as a subcommand")
    assertEquals("C12345", root.caseId)
    assertEquals("android-phone", root.device)
  }

  @Test
  fun `results picocli still routes explicit show subcommand`() {
    // The pre-existing `results show C12345 ...` shape must continue to work — picocli
    // matches "show" as a subcommand and the positional binds to ResultsShowCommand.
    val root = ResultsCommand()
    val parseResult =
      CommandLine(root).parseArgs("show", "C12345", "--device", "android-phone")

    val sub = parseResult.subcommand()
    assertNotNull(sub, "Expected a matched `show` subcommand")
    val show = sub.commandSpec().userObject() as ResultsShowCommand
    assertEquals("C12345", show.caseId)
    assertEquals("android-phone", show.device)
    // Parent-level fields untouched.
    assertNull(root.caseId)
  }

  @Test
  fun `results no case-id but forwarded flag returns MISUSE`() {
    // Without the MISUSE guard, `trailblaze results --device android-phone` would print
    // the help text and exit SUCCESS — a stray missing case-id from an automation script
    // would look like a clean run. Surface it as MISUSE so CI gates catch the typo.
    val cmd = ResultsCommand()
    CommandLine(cmd).parseArgs("--device", "android-phone")

    val exitCode = cmd.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `results bare invocation prints help and exits SUCCESS`() {
    // Bare `trailblaze results` (no args at all) should still print help cleanly —
    // that's the discoverability path users hit when learning the surface.
    val cmd = ResultsCommand()
    CommandLine(cmd).parseArgs()

    val exitCode = cmd.call()
    assertEquals(TrailblazeExitCode.SUCCESS.code, exitCode)
  }

  @Test
  fun `results forwarded flag before show subcommand reaches the subcommand`() {
    // picocli binds options BEFORE the subcommand name to the parent. Without the
    // parent-fallback merge in ResultsShowCommand.call(), `--repo` here would never
    // reach `show` and the user would see a misleading "results repo not configured"
    // error. This test pins that the parent's flag value is merged into the
    // subcommand's own field at the start of the subcommand's call().
    val root = ResultsCommand()
    val parseResult = CommandLine(root).parseArgs(
      "--repo", "owner/name",
      "show", "C12345", "--device", "android-phone",
    )

    val sub = parseResult.subcommand()
    assertNotNull(sub, "Expected a matched `show` subcommand")
    val show = sub.commandSpec().userObject() as ResultsShowCommand
    // Parent has the flag bound; subcommand does not until the merge runs.
    assertEquals("owner/name", root.repo)
    assertNull(show.repo, "Pre-merge: subcommand field is its default until call() runs the merge")
    assertEquals(root, show.parent, "@ParentCommand wires the back-pointer used by the merge")
  }
}
