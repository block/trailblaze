package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds
import xyz.block.trailblaze.config.project.TrailDiscovery
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailDeviceSelection
import xyz.block.trailblaze.devices.TrailDeviceSelector
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.desktop.LlmTokenStatus
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.findById
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.recordings.UnifiedRecordingWriter
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.yaml.toRecordingTrailConfig
import xyz.block.trailblaze.revyl.tools.RevylToolSetIds
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Run one or more trail files (`.trail.yaml` or `blaze.yaml`) on a connected device.
 * Accepts explicit file arguments, shell globs, and directory arguments (directories are
 * expanded recursively to every named unified trail plus one pick per legacy trail — the
 * platform-specific recording preferred over the NL `blaze.yaml` when both are present).
 */
@Command(
  name = "run",
  aliases = ["trail"],
  mixinStandardHelpOptions = true,
  description = [
    "Run a trail file (.trail.yaml) — execute a scripted test on a device.",
    "",
    "Accepts files, shell globs, or directories. Directory arguments expand recursively to " +
      "every named unified trail (`<scenario>.trail.yaml`), plus one pick per legacy trail " +
      "(recording preferred over NL when both are present).",
    "",
    "Trail-level metadata honored by the runner:",
    "  - `tags:` (list of strings) — filtered via --tags.",
    "  - `skip:` (reason string)   — reported as skipped (reason printed, contributes to the " +
      "`N skipped` summary tally) and exits 0 for that file's slot. Blank/whitespace `skip:` " +
      "is ignored. To run a skipped trail, remove its `skip:` line.",
    "",
    "Note: `trailblaze trail` is a deprecated alias for `trailblaze run` and will be removed " +
      "in a future release.",
  ],
)
// Class name retained as `TrailCommand` (not `RunCommand`) during the one-release
// deprecation window of the `trail` → `run` rename. Renaming the class would churn 6+
// test classes (`TrailCommandPlanTrailExecutionTest`,
// `TrailCommandResolveDefaultTrailDirTest`, `TrailCommandResolveMaxLlmCallsTest`,
// `TrailCommandSaveRecordingTest`, `TrailCommandAliasWarningTest`, etc.) — deferred to
// the same release that removes `aliases = ["trail"]`. See
// `docs/internal/devlog/2026-05-26-cli-trail-to-run-rename.md` for the rationale and
// the removal recipe. Once removed, follow the established `StepCommand` /
// `AskCommand` / `VerifyCommand` convention and rename to `RunCommand`.
open class TrailCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  // Injected by picocli at parse time. Used by [wasInvokedViaTrailAlias] to detect when
  // the user typed the deprecated `trail` name instead of the new `run` name, so we can
  // print a one-line stderr deprecation warning. Picocli's [CommandLine.ParseResult]
  // exposes the original args list on the root, which we walk to here.
  @CommandLine.Spec(CommandLine.Spec.Target.SELF)
  private lateinit var commandSpec: CommandLine.Model.CommandSpec

  /** Set by the shutdown hook on Ctrl+C to stop processing further trail files. */
  @Volatile private var cancelled = false

  @Parameters(
    index = "0..*",
    arity = "0..*",
    paramLabel = "<trailFile>",
    description = [
      "Trail files (.trail.yaml or blaze.yaml), shell globs, or directories. Directories " +
        "expand recursively to every named unified trail, plus one pick per legacy trail " +
        "(recording preferred over NL when both are present). Bare `trailblaze run` with no " +
        "arguments is rejected as a misuse — pass a `.trail.yaml` path or name a directory " +
        "(e.g. `trails/`) to fan out under a workspace's trails directory.",
    ],
  )
  var trailFiles: List<File> = emptyList()

  @Option(
    names = ["--tags"],
    paramLabel = "<name>",
    split = ",",
    description = [
      "Only run trails whose `config.tags:` list contains at least one of the given names. " +
        "Repeatable (`--tags smoke --tags login`) or comma-separated (`--tags smoke,login`). " +
        "Match is OR across tags. Untagged trails are excluded when --tags is specified.",
    ],
  )
  var includeTags: List<String> = emptyList()

  @Option(
    names = ["-d", "--device"],
    paramLabel = "<device>",
    split = ",",
    description = [
      "Device(s) to run on: `<platform>` (e.g. android), `<platform>/<instanceId>`, or a bare " +
        "instanceId. Comma-separated or repeatable to run each trail on SEVERAL devices " +
        "(`--device android,ios` → one run per device). When omitted, resolves to a pinned " +
        "(`trailblaze device connect` / `TRAILBLAZE_DEVICE`) or single connected device; when 2+ " +
        "devices are connected and none is pinned, the run fails and asks you to pass this (or " +
        "`--driver` / `--all-devices`). See also --all-devices.",
    ],
  )
  var devices: List<String> = emptyList()

  @Option(
    names = ["--all-devices"],
    description = [
      "Run each trail on EVERY connected device whose platform the trail supports (its " +
        "`platform:`/`driver:` for v1, or its `devices:`/recording classifiers for the unified " +
        "format). The opt-in way to exercise a multi-target trail across platforms in one command. " +
        "Mutually exclusive with `--device` (passing both is rejected). Connected devices that " +
        "don't match any supported platform are skipped.",
    ],
  )
  var allDevices: Boolean = false

  @Option(
    names = ["-a", "--agent"],
    description = ["Agent: TRAILBLAZE_RUNNER, MULTI_AGENT_V3. Default: ${AgentImplementation.DEFAULT_NAME}"]
  )
  var agent: String = AgentImplementation.DEFAULT.name

  @Option(
    names = ["--use-recorded-steps"],
    description = [
      "Three-way switch for replay vs. AI-driven execution:",
      "  --use-recorded-steps      Force replay mode (use the trail's `recording:` tools verbatim).",
      "  --no-use-recorded-steps   Force AI mode (ignore any recordings; LLM drives each step from `step:` NL).",
      "  (unset, default)          Auto-detect: AI mode if no `recording:` blocks present, replay if they are.",
      "Use --no-use-recorded-steps to re-run a trail with stale selectors and let the agent re-pick selectors from current page state.",
    ],
    negatable = true,
  )
  var useRecordedSteps: Boolean? = null

  @Option(
    names = ["--self-heal"],
    description = [
      "When a recorded step fails, let AI take over and continue. " +
        "Overrides the persisted 'trailblaze config self-heal' setting for this run. " +
        "Omit to inherit the saved setting (opt-in, off by default)."
    ],
  )
  var selfHeal: Boolean? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"]
  )
  var verbose: Boolean = false

  @Option(
    names = ["--driver"],
    description = ["Driver type to use (e.g., PLAYWRIGHT_NATIVE, ANDROID_ONDEVICE_INSTRUMENTATION). Overrides driver from trail config."]
  )
  var driverType: String? = null

  // Legacy flag — kept for back-compat with existing scripts. New users should
  // prefer `--headless=false`. Hidden from `--help` so the new flag is the
  // single discoverable spelling.
  @Option(
    names = ["--show-browser"],
    description = ["[Deprecated] Show the browser window. Prefer --headless=false."],
    hidden = true,
  )
  var showBrowser: Boolean = false

  @Option(
    names = ["--headless"],
    description = [
      "Launch the Playwright browser headless (default true). Pass --no-headless or " +
        "--headless=false to surface a visible window. Equivalent to --show-browser when negated.",
    ],
    negatable = true,
  )
  var headless: Boolean = true

  @Option(
    names = ["--llm"],
    description = ["LLM provider/model shorthand (e.g., openai/gpt-4-1). " +
        "Mutually exclusive with --llm-provider and --llm-model."]
  )
  var llm: String? = null

  @Option(
    names = ["--llm-provider"],
    description = ["LLM provider override (e.g., openai, anthropic, google)"]
  )
  var llmProvider: String? = null

  @Option(
    names = ["--llm-model"],
    description = ["LLM model ID override (e.g., gemini-3-flash, gpt-4-1)"]
  )
  var llmModel: String? = null

  @Option(
    names = ["--memory"],
    paramLabel = "KEY=VAL",
    arity = "1",
    description = [
      "Pre-populate trail memory with KEY=VAL before any step runs. Repeatable " +
        "(`--memory user=sam --memory accountTier=PRO`). Overrides any value with the " +
        "same key in the trail YAML's `config.memory:` block. Values are strings; keys " +
        "must be non-empty. Visible to `{{name}}` interpolation and to scripted tools via " +
        "`ctx.memory.get(name)`.",
      "Values are logged in cleartext and persisted into the session-start snapshot — " +
        "use --secret for passwords, tokens, or other sensitive data."
    ],
  )
  var memorySeeds: List<String> = emptyList()

  @Option(
    names = ["--secret"],
    paramLabel = "KEY=VAL",
    arity = "1",
    description = [
      "Pre-populate trail memory with a SENSITIVE KEY=VAL before any step runs. Same " +
        "shape as --memory; the value is redacted in logs (via `rememberSensitive`), " +
        "excluded from the scripting envelope, and omitted from the session-start " +
        "snapshot. Only the KEY appears in `Started.sensitiveMemoryKeys` so replay " +
        "knows it must re-supply the value. Repeatable. Use for passwords, tokens, " +
        "API keys, PII."
    ],
  )
  var sensitiveSeeds: List<String> = emptyList()

  @Option(
    names = ["--max-llm-calls"],
    description = [
      "Cap the number of LLM calls per objective for the legacy TRAILBLAZE_RUNNER agent. " +
        "Useful on metered or expensive providers to cut off a stuck self-heal loop. " +
        "Must be a positive integer. Default: 50 (the runner's built-in cap). " +
        "Not compatible with --agent MULTI_AGENT_V3."
    ]
  )
  var maxLlmCalls: Int? = null

  @Option(
    names = ["--no-report"],
    description = ["Skip HTML report generation after execution"]
  )
  var noReport: Boolean = false

  // Nullable + negatable matches the precedent of `--use-recorded-steps` above: three
  // tri-state values (positive flag → true, negative flag → false, no flag → null) let us
  // distinguish "user explicitly opted in/out" from "user didn't say". With a non-nullable
  // Boolean + `negatable = true` + a default of `true`, picocli 4.7.7 inverts the option
  // semantics (passing `--save-recording` ends up setting the field to false) — the
  // nullable form avoids that footgun. The default-on behaviour lives in
  // [resolveEffectiveSaveRecording] instead of the field default.
  @Option(
    names = ["--save-recording"],
    description = [
      "Save the recording back to the trail source directory after a successful run. " +
        "Default: on. Use --no-save-recording to skip. " +
        "Even when on, the recording is only saved when --self-heal was enabled OR this device " +
        "isn't recorded yet — deterministic re-runs no-op the write so they can't clobber a " +
        "hand-edited source. See --unified-recordings for the on-disk format."
    ],
    negatable = true,
  )
  var saveRecording: Boolean? = null

  // Gate for the unified-format recorder (tri-state like --save-recording): flag >
  // TRAILBLAZE_UNIFIED_RECORDINGS env var > persisted `trailblaze config unified-recordings` >
  // on. Resolved via [resolveEffectiveUnifiedRecordings]; when opted out, save-back behavior is
  // byte-identical to the pre-unified recorder.
  @Option(
    names = ["--unified-recordings"],
    description = [
      "Save new recordings in the unified format: the device's slot is merged into the unified " +
        "trail.yaml (a directory that still has legacy <classifier>.trail.yaml files keeps using " +
        "them). Default: on. Opt out with --no-unified-recordings, " +
        "TRAILBLAZE_UNIFIED_RECORDINGS=0, or 'trailblaze config unified-recordings false' to " +
        "save legacy <classifier>.trail.yaml siblings instead — nothing is ever written next to " +
        "an existing unified trail.yaml."
    ],
    negatable = true,
  )
  var unifiedRecordings: Boolean? = null

  // Deprecated alias. Kept for one cycle so existing scripts that pass --no-record
  // keep working — but with a one-time stderr warning so users notice during the
  // deprecation window. Removal targets the next minor release after callers
  // (cli_smoke_tests_common.sh, skill docs) migrate to --no-save-recording.
  @Option(
    names = ["--no-record"],
    description = ["[Deprecated] Alias for --no-save-recording."],
    hidden = true,
  )
  @Suppress("unused")
  fun setNoRecordDeprecated(value: Boolean) {
    // Picocli invokes this setter with `true` when the bare flag is present, and with
    // whatever the user passed when it's written as `--no-record=<value>`. Only flip the
    // toggle when the user actually asked to disable saves — `--no-record=false` shouldn't
    // implicitly re-enable saves, so we just no-op.
    if (value) {
      saveRecording = false
      Console.error(
        "[Deprecated] --no-record is replaced by --no-save-recording; the old name " +
          "will be removed in a future release.",
      )
    }
  }

  @Option(
    names = ["--no-logging"],
    description = ["Disable session logging — no files written to logs/, session does not appear in Sessions tab"]
  )
  var noLogging: Boolean = false

  @Option(
    names = ["--markdown"],
    description = ["Generate a markdown report after execution"]
  )
  var markdown: Boolean = false

  @Option(
    names = ["--no-daemon"],
    description = ["Run in-process without delegating to or starting a persistent daemon. " +
        "The server shuts down when the run completes."]
  )
  var noDaemon: Boolean = false

  @Option(
    names = ["--compose-port"],
    description = ["RPC port for Compose driver connections (default: ${ComposeRpcServer.COMPOSE_DEFAULT_PORT})"]
  )
  var composePort: Int = ComposeRpcServer.COMPOSE_DEFAULT_PORT

  @Option(
    names = ["--capture-video"],
    description = ["Record device screen video for the session (on by default, use --no-capture-video to disable)"],
    negatable = true,
  )
  var captureVideo: Boolean = true

  @Option(
    names = ["--capture-logcat"],
    description = [
      "Capture Android logcat (filtered to the app under test) to <session-dir>/device.log " +
        "(only takes effect on Android). On by default; use --no-capture-logcat to disable.",
    ],
    negatable = true,
  )
  var captureLogcat: Boolean = true

  @Option(
    names = ["--capture-ios-logs"],
    description = [
      "Capture the iOS Simulator system log via `xcrun simctl spawn log stream` to " +
        "<session-dir>/device.log (only takes effect on iOS). On by default; the stream is " +
        "scoped to the app under test (the logcat-equivalent app log, not the system firehose). " +
        "Use --no-capture-ios-logs to disable.",
    ],
    negatable = true,
  )
  var captureIosLogs: Boolean = true

  @Option(
    names = ["--capture-network"],
    description = [
      "Auto-capture network requests/responses to <session-dir>/network.ndjson on " +
        "supported devices (web today; mobile devices added as engines land). " +
        "Mirrors the desktop-app \"Capture Network Traffic\" toggle. On by default; " +
        "use --no-capture-network to disable. When neither flag is passed, inherits the " +
        "desktop app's saved setting.",
    ],
    negatable = true,
  )
  var captureNetwork: Boolean? = null

  @Option(
    names = ["--capture-all"],
    description = ["Enable all capture streams: video, logcat, iOS logs, network (local dev mode)"]
  )
  var captureAll: Boolean = false

  @Option(
    names = ["--test-name"],
    description = [
      "Override the test name used as the session ID seed. When set, replaces the default " +
        "name derived from the trail filename. Useful in CI environments where the caller " +
        "can supply a richer identifier (e.g. including suite/section/case context).",
    ],
  )
  var testNameOverride: String? = null

  private val captureOptions: CaptureOptions get() {
    return CaptureOptions(
      captureVideo = captureVideo || captureAll,
      captureLogcat = captureLogcat || captureAll,
      captureIosLogs = captureIosLogs || captureAll,
    )
  }

  override fun call(): Int {
    // Suppress internal debug logs unless --verbose is passed.
    // Console.info() and Console.error() remain visible for user-facing output.
    if (!verbose) {
      Console.enableQuietMode()
    }

    // Emit the `trail` → `run` deprecation warning BEFORE the bare-args rejection so a
    // user who fat-fingers `trailblaze trail` (with no args) still sees the deprecation
    // signal. Pre-PR the warning fired on every invocation regardless of arg count;
    // suppressing it here would silently erode the deprecation signal on the bare-args
    // path. `wasInvokedViaTrailAlias()` is hardened against an uninitialized
    // `commandSpec`, so the unit-test path (which constructs TrailCommand directly) is
    // still safe.
    if (wasInvokedViaTrailAlias()) {
      Console.error(
        "Warning: 'trailblaze trail' is deprecated and will be removed in a future release. " +
          "Use 'trailblaze run' instead.",
      )
    }

    // Reject bare `trailblaze run` (no positional argument) before any other work — this
    // used to default to `<workspace>/trails/` and silently fan out across every trail
    // it found, which is a footgun for a curious "what does this do?" tap. Make the
    // user opt in to fan-out explicitly by naming a directory (e.g. `trailblaze run
    // trails/`). Uses the shared `Trail run` verb to match every other
    // `reportCliError` callsite in this file.
    if (trailFiles.isEmpty()) {
      reportCliError(
        verb = "Trail run",
        reason = "no trail file or directory specified",
        hint = "pass a .trail.yaml path (e.g. `trailblaze run flows/login.trail.yaml`) " +
          "or a directory to fan out (e.g. `trailblaze run trails/`)",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    // Resolve --llm shorthand: splits "provider/model" into llmProvider + llmModel.
    // `--llm=none` is a sentinel for "no LLM configured" — recordings-only mode where the
    // tool stack runs without inference. Resolve it to (LLM_NONE, LLM_NONE) so downstream
    // code matches the same shape `trailblaze config llm none` writes (see CliConfigHelper).
    if (llm != null) {
      if (llmProvider != null || llmModel != null) {
        Console.error("Error: --llm is mutually exclusive with --llm-provider and --llm-model.")
        return TrailblazeExitCode.MISUSE.code
      }
      if (llm.equals(LLM_NONE, ignoreCase = true)) {
        llmProvider = LLM_NONE
        llmModel = LLM_NONE
      } else {
        val parts = llm!!.split("/", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
          Console.error("Error: --llm must be in provider/model format (e.g., openai/gpt-4-1) or 'none' for recordings-only mode.")
          return TrailblazeExitCode.MISUSE.code
        }
        llmProvider = parts[0]
        llmModel = parts[1]
      }
    }

    // Validate --memory / --secret KEY=VAL entries early so a typo (e.g. `--memory invalid`)
    // exits USAGE before any device/LLM resolution happens. Each entry must contain a `=`
    // with a non-empty key — empty values are allowed (a deliberate sentinel for some
    // flows). Sensitive seeds use the same shape so we reuse the same parser. Parse once
    // here and cache so the request-build sites at the in-process and daemon paths below
    // don't re-walk the input lists; the helper is cheap but the duplicate-parse cost
    // is also a duplicate-error-surface that could drift between validation and use.
    val parsedMemorySeeds: Map<String, String>
    val parsedSensitiveSeeds: Map<String, String>
    try {
      parsedMemorySeeds = parseMemorySeeds(memorySeeds)
      parsedSensitiveSeeds = parseMemorySeeds(sensitiveSeeds)
    } catch (e: IllegalArgumentException) {
      Console.error("Error: ${e.message}")
      return TrailblazeExitCode.MISUSE.code
    }
    this.resolvedMemorySeeds = parsedMemorySeeds
    this.resolvedSensitiveSeeds = parsedSensitiveSeeds

    // Validate --max-llm-calls flag value early so a CLI typo (e.g. `--max-llm-calls 0`)
    // exits USAGE rather than later tripping the model-layer require with an
    // IllegalArgumentException. Non-flag tiers (env / workspace / persisted) get their
    // own warn-and-fall-through validation inside resolveEffectiveMaxLlmCalls().
    maxLlmCalls?.let { cap ->
      if (cap <= 0) {
        Console.error("Error: --max-llm-calls must be a positive integer (got $cap).")
        return TrailblazeExitCode.MISUSE.code
      }
    }

    // Agent-incompatibility check fires on the *resolved* value — any tier that contributes
    // a non-null cap (CLI flag, env var, workspace yaml, persisted config) combined with
    // MULTI_AGENT_V3 produces the same friendly USAGE exit instead of an
    // IllegalArgumentException from RunYamlRequest.init. The resolver is cheap enough to call
    // twice (once here, once at request construction); workspace yaml lookup is the only I/O
    // and it's a one-off file read.
    if (resolveEffectiveMaxLlmCalls() != null &&
      agent.equals(AgentImplementation.MULTI_AGENT_V3.name, ignoreCase = true)
    ) {
      Console.error(
        "Error: max-llm-calls is not supported with --agent ${AgentImplementation.MULTI_AGENT_V3.name}. " +
          "The V3 agent has its own iteration limits; use those instead. " +
          "Clear the cap by omitting --max-llm-calls, unsetting TRAILBLAZE_MAX_LLM_CALLS, " +
          "removing defaults.max-llm-calls from trailblaze.yaml, or running " +
          "`trailblaze config max-llm-calls unset`.",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    // `--device` may name several (comma-split); non-blank entries only. One entry = a single run
    // (as before); several = explicit fan-out (one run per device). Empty = resolve a default.
    val explicitDevices = devices.map { it.trim() }.filter { it.isNotBlank() }

    // `--device` and `--all-devices` are conflicting ways to say which devices to run on — reject
    // the combination (an explicit list would otherwise silently win and no-op `--all-devices`).
    // Validated here, before any `parent` access, so it exits MISUSE up front like the other
    // argv guards above (and stays unit-testable through `call()`).
    if (explicitDevices.isNotEmpty() && allDevices) {
      reportCliError(
        verb = "Trail run",
        reason = "--device and --all-devices are mutually exclusive",
        hint = "pass --all-devices to run on every supported connected device, OR --device to name " +
          "specific one(s) — not both",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    // Resolve --device with a four-tier fallback that mirrors every other action command:
    //   1. Explicit `--device` flag (wins)
    //   2. TRAILBLAZE_DEVICE env var — pinned per-shell via
    //      `eval $(trailblaze device connect <platform>)` so an agent running a sequence
    //      of trails in one shell doesn't have to repeat `--device` on every call
    //   3. Autodetect when exactly one device is connected — closes the single-device
    //      OOBE gap (zero setup needed); emits a stderr notice so the user sees the pick
    //   4. Persisted `trailblaze config device` (legacy convenience for users who set
    //      a default device once via `trailblaze config`)
    //
    // `trail` (replay mode) still differs from `tool`/`step`/`snapshot`/`ask`/`verify` in
    // one respect — each replay spawns a fresh session rather than reattaching to the
    // shell's bound one — but the *device-id* should resolve the same way regardless.
    //
    // We don't use the shared `resolveDeviceOrErrorBlocking` here because `trail` has a
    // legitimate fifth tier (the persisted CLI config) the shared resolver doesn't know about.
    // When 2+ devices are connected and none is pinned, [resolveDevicesForFile] in the run loops
    // fails loud (emits `emitMultipleDevicesEnvelope`, exits MISUSE) asking for `--device` — same
    // as every other device command, instead of silently picking the first. It does NOT narrow by
    // the trail's declared platforms (a trail can run as natural language on any device, so those
    // aren't a hard target); only the explicit `--driver` / `--all-devices` signals narrow.
    //
    // The inlined tier order mirrors `resolveDeviceWithAutodetect`:
    //   1. `--device` flag (already handled above when non-blank)
    //   2. `TRAILBLAZE_DEVICE` env var
    //   3. This terminal's file-pin (added so `trailblaze device connect X` followed
    //      by `trailblaze run …` inherits the device — pre-fix, the resolver skipped
    //      straight from env to autodetect and a pinned multi-device shell hit the
    //      multi-device error)
    //   4. Autodetect (exactly one connected device)
    //   5. Workspace config's `cliDevicePlatform` (trail-runner-specific)
    //
    // Guard on `isNullOrBlank()` (not `== null`) so a stray `--device ""` /
    // `--device " "` falls through to env/pin/autodetect/config rather than being
    // forwarded to the runner verbatim — letting a blank string slip through
    // would surface as a cryptic "Device '' not found" mid-pipeline. Same
    // treatment `resolveCliDevice` applies to the env var.
    val port = parent.getEffectivePort()

    // Resolve a single DEFAULT device only when the user gave neither an explicit `--device` list
    // nor `--all-devices`. Same tiers as before: env var, shell pin, autodetect (single connected
    // device), persisted config. `Resolved` feeds the default; `Multiple` falls through so the
    // per-file target-aware selection can narrow (or fail loud) in the run loops.
    var autodetect: DeviceAutodetectResult? = null
    var defaultDevice: String? = null
    if (explicitDevices.isEmpty() && !allDevices) {
      defaultDevice = resolveCliDevice(null)
        ?: readShellPinDevice(port)
        ?: run {
          autodetect = runBlocking { autodetectSingleConnectedDevice(port) }
          (autodetect as? DeviceAutodetectResult.Resolved)?.let {
            reportAutodetectedDevice(it.deviceSpec)
            it.deviceSpec
          }
        }
        ?: CliConfigHelper.readConfig()?.cliDevicePlatform
    }
    // `--all-devices` needs the full connected list even when the tiers above didn't autodetect
    // (e.g. a device is pinned). Enumerate it now if we haven't already.
    if (allDevices && autodetect == null) {
      autodetect = runBlocking { autodetectSingleConnectedDevice(port) }
    }
    // `--all-devices` derives its whole workload from that enumeration, so if it failed (daemon
    // unreachable / list errored), fail as INFRA rather than letting it collapse to "no devices
    // connected" below. The non-all-devices paths tolerate a null list (they defer to downstream
    // device loading, which reports its own errors).
    (autodetect as? DeviceAutodetectResult.DaemonUnreachable)?.let { unreachable ->
      if (allDevices) {
        if (!unreachable.alreadyReported) {
          reportDaemonUnreachable("daemon device listing failed — cannot resolve --all-devices")
        }
        return TrailblazeExitCode.INFRA_FAILED.code
      }
    }

    // The connected-device specs (fully-qualified), used by `--all-devices` fan-out and by the
    // multi-device narrowing when no default was pinned. Captured once here so the in-process and
    // daemon-delegated loops share the same list. See [resolveDevicesForFile].
    val connectedSpecs: List<String>? = when (val a = autodetect) {
      is DeviceAutodetectResult.Multiple -> a.specs
      is DeviceAutodetectResult.Resolved -> listOf(a.deviceSpec)
      else -> null
    }

    // Validate every argument: must exist and be either a recognized trail file
    // (`*.trail.yaml` or `blaze.yaml`) or a directory (expanded recursively below).
    for (file in trailFiles) {
      if (!file.exists()) {
        // Bad path → MISUSE (caller supplied a path that doesn't exist), not infra:
        // the daemon, device, and network are all irrelevant here.
        reportCliError(
          verb = "Trail run",
          target = file.absolutePath,
          reason = "trail file does not exist",
        )
        return TrailblazeExitCode.MISUSE.code
      }
      if (file.isDirectory) continue
      if (!file.isFile) {
        reportCliError(
          verb = "Trail run",
          target = file.absolutePath,
          reason = "not a regular file or directory",
        )
        return TrailblazeExitCode.MISUSE.code
      }
      if (!TrailRecordings.isTrailFile(file.name)) {
        reportCliError(
          verb = "Trail run",
          target = file.name,
          reason = "expected a trail file (${TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX} or blaze.yaml)",
        )
        return TrailblazeExitCode.MISUSE.code
      }
    }

    // Check if a daemon is already running — delegate to it to avoid starting a second
    // Gradle JVM. This is critical for CI where multiple trails run sequentially.
    // --no-daemon skips this check and always runs in-process.
    val daemonPort = parent.getEffectivePort()
    val daemon = DaemonClient(port = daemonPort)
    if (!noDaemon) {
      if (daemon.isRunningBlocking()) {
        return delegateToDaemon(daemon, explicitDevices, defaultDevice, connectedSpecs)
      }
    } else if (daemon.isRunningBlocking()) {
      // Shut down existing daemon so this process can bind the port.
      Console.info("Shutting down existing daemon on port $daemonPort...")
      daemon.shutdownBlocking()
    }

    // Build the execution plan up front: expand directories, parse each trail's config once,
    // apply --tags, classify `skip:` markers. Doing this before the run lets the "Running
    // N trail file(s)" header reflect the actual workload (post-filter) rather than the
    // raw argument count. Device classifiers feed [findBestTrailResourcePath] so a directory
    // containing both `blaze.yaml` and a classifier-matched recording resolves to the
    // recording (no LLM call), not both. The resolver probes the OS (xcrun/adb) so a
    // `--device ios/<UDID>` pointing at an iPhone sim yields `[ios, iphone]` and picks
    // `ios-iphone.trail.yaml` — closing the load-vs-save filename gap the runtime save
    // path has been writing recordings for.
    //
    // The plan (directory expansion + skip/tags) resolves against a single representative device:
    // the one target when there is exactly one (a single `--device` or the resolved default), else
    // empty (a fan-out run is device-agnostic at plan time, same as today's multi-device case).
    val planDevice: String? = when {
      explicitDevices.size == 1 -> explicitDevices.single()
      explicitDevices.isEmpty() -> defaultDevice
      else -> null
    }
    val deviceClassifiers = DeviceClassifierResolver.resolveFromSpec(planDevice)
    if (deviceClassifiers.isNotEmpty()) {
      Console.info("Device classifiers: ${deviceClassifiers.joinToString(", ") { it.classifier }}")
    }
    // Skip/tags resolve against the device classifiers, or — for a `--driver`-only run with no
    // `--device` — the driver's platform classifier. Logged symmetrically with the device line
    // above so "why was my trail (not) skipped?" is answerable straight from the run output.
    val configClassifiers = deviceClassifiers.ifEmpty { DeviceClassifierResolver.fromDriver(driverType) }
    if (deviceClassifiers.isEmpty() && configClassifiers.isNotEmpty()) {
      Console.info("Skip/tags classifiers (from --driver): ${configClassifiers.joinToString(", ") { it.classifier }}")
    }
    val plan = planTrailExecution(trailFiles, includeTags, deviceClassifiers, configClassifiers = configClassifiers)
    if (plan.filteredOutByTag > 0) {
      Console.info("Filtered ${plan.filteredOutByTag} trail(s) by --tags")
    }
    if (plan.items.isEmpty()) {
      Console.info("No trail files to run after filtering. Exiting.")
      return TrailblazeExitCode.SUCCESS.code
    }
    Console.info("Running ${plan.items.size} trail file(s)")
    Console.info(SECTION_DIVIDER)

    var passed = 0
    var failed = 0
    var skipped = 0
    // Track the "worst" per-file exit code so a batch with one INFRA failure and
    // one ASSERTION failure exits 2 (INFRA), but a batch with only ASSERTION
    // failures exits 1. Without this, the final `exitProcess` below would
    // collapse every non-zero outcome into INFRA_FAILED and defeat the split.
    var worstExitCode = TrailblazeExitCode.SUCCESS.code
    val allNewSessionIds = mutableListOf<SessionId>()

    // Initialize the app once for all files
    val app = parent.appProvider()

    // Apply port overrides from env vars / saved settings
    if (parent.hasPortOverride()) {
      app.applyPortOverrides(httpPort = parent.getEffectivePort(), httpsPort = parent.getEffectiveHttpsPort())
    }
    app.ensureServerRunning()

    // Register shutdown hook for Ctrl+C
    Runtime.getRuntime().addShutdownHook(Thread {
      cancelled = true
      Console.info("\n\nExecution stopped by user.")
    })

    for ((index, item) in plan.items.withIndex()) {
      if (cancelled) break
      val total = plan.items.size
      when (item) {
        is TrailExecutionItem.Skip -> {
          Console.info("\n[${index + 1}/$total] Skipping: ${item.file.name}")
          Console.info(ITEM_DIVIDER)
          Console.info("Skipped: ${item.reason}")
          skipped++
        }
        is TrailExecutionItem.Run -> {
          val deviceSpecs = resolveRunDeviceSpecsOrReport(
            item.file, "${index + 1}/$total", explicitDevices, defaultDevice, connectedSpecs,
          )
          if (deviceSpecs == null) {
            failed++
            worstExitCode = chooseWorseExitCode(worstExitCode, TrailblazeExitCode.MISUSE.code)
            continue
          }
          // One run per resolved device (usually one; several with --device a,b / --all-devices).
          for (deviceSpec in deviceSpecs) {
            if (cancelled) break
            val runLabel = if (deviceSpecs.size > 1) {
              "${item.file.name} on ${deviceSpec ?: "default device"}"
            } else {
              item.file.name
            }
            Console.info("\n[${index + 1}/$total] Running: $runLabel")
            Console.info(ITEM_DIVIDER)
            val (exitCode, sessionIds) = runSingleTrailFile(item.file, deviceSpec, app)
            allNewSessionIds.addAll(sessionIds)
            if (exitCode == TrailblazeExitCode.SUCCESS.code) {
              passed++
              // Save recording to trail source directory on success — gating delegated to
              // shouldSaveRecording so all three call sites (this one, the daemon delegate
              // below, and the in-process generation inside runSingleTrailFile) share one
              // heuristic and can't drift.
              if (sessionIds.isNotEmpty()) {
                val logsRepo = app.deviceManager.logsRepo
                for (sessionId in sessionIds) {
                  val classifiers = logsRepo.getSessionInfo(sessionId)
                    ?.trailblazeDeviceInfo?.classifiers?.map { it.classifier } ?: emptyList()
                  if (shouldSaveRecording(item.file, classifiers)) {
                    saveRecordingToTrailDirectory(item.file, sessionId, classifiers)
                  } else {
                    logSkippedRecording(item.file, classifiers)
                  }
                }
              }
            } else {
              failed++
              worstExitCode = chooseWorseExitCode(worstExitCode, exitCode)
            }
          }
        }
      }
    }

    // Print summary
    Console.info("\n" + SECTION_DIVIDER)
    Console.info("Results: $passed passed, $failed failed, $skipped skipped (${passed + failed} run(s) across ${plan.items.size} trail file(s))")

    // Generate combined report
    if (!noReport && allNewSessionIds.isNotEmpty()) {
      try {
        val logsRepo = app.deviceManager.logsRepo
        val reportGenerator = app.createCliReportGenerator()
        reportGenerator.printSummary(logsRepo, allNewSessionIds)
        val reportFile = reportGenerator.generateReport(logsRepo, allNewSessionIds)
        if (reportFile != null) {
          Console.info("\nReport: file://${reportFile.absolutePath}")
        }
        val interactiveReport = reportGenerator.generateInteractiveReport(logsRepo, allNewSessionIds)
        if (interactiveReport != null) {
          Console.info("Interactive report: file://${interactiveReport.absolutePath}")
        }
      } catch (e: Exception) {
        Console.error("Failed to generate report: ${e.message}")
      }
    }

    // Generate markdown report if --markdown was specified
    if (markdown && allNewSessionIds.isNotEmpty()) {
      try {
        val logsRepo = app.deviceManager.logsRepo
        val reportGenerator = app.createCliReportGenerator()
        val markdownFile = reportGenerator.generateMarkdownReport(logsRepo, allNewSessionIds)
        if (markdownFile != null) {
          Console.info("Markdown: file://${markdownFile.absolutePath}")
        }
      } catch (e: Exception) {
        Console.error("Failed to generate markdown report: ${e.message}")
      }
    }

    exitProcess(if (failed > 0) worstExitCode else TrailblazeExitCode.SUCCESS.code)
  }

  /**
   * Delegates trail execution to a running daemon via HTTP, one file at a time.
   *
   * This avoids starting a second Gradle JVM — the daemon already has device
   * discovery, LLM config, and the trail runner ready to go.
   *
   * [explicitDevices], [defaultDevice], and [connectedSpecs] are threaded from [call] so per-file
   * device selection (incl. `--device a,b` / `--all-devices` fan-out) is identical to the
   * in-process path.
   */
  private fun delegateToDaemon(
    daemon: DaemonClient,
    explicitDevices: List<String>,
    defaultDevice: String?,
    connectedSpecs: List<String>?,
  ): Int {
    // Same plan-then-iterate shape as the in-process path so the daemon-delegated and
    // in-process flows produce identical headers, filter counts, and summaries. Device
    // discovery via xcrun/adb is read-only OS state — safe to run from this client
    // process even while the daemon is using the same devices for execution.
    val planDevice: String? = when {
      explicitDevices.size == 1 -> explicitDevices.single()
      explicitDevices.isEmpty() -> defaultDevice
      else -> null
    }
    val deviceClassifiers = DeviceClassifierResolver.resolveFromSpec(planDevice)
    if (deviceClassifiers.isNotEmpty()) {
      Console.info("Device classifiers: ${deviceClassifiers.joinToString(", ") { it.classifier }}")
    }
    // Skip/tags resolve against the device classifiers, or — for a `--driver`-only run with no
    // `--device` — the driver's platform classifier. Logged symmetrically with the device line
    // above so "why was my trail (not) skipped?" is answerable straight from the run output.
    val configClassifiers = deviceClassifiers.ifEmpty { DeviceClassifierResolver.fromDriver(driverType) }
    if (deviceClassifiers.isEmpty() && configClassifiers.isNotEmpty()) {
      Console.info("Skip/tags classifiers (from --driver): ${configClassifiers.joinToString(", ") { it.classifier }}")
    }
    val plan = planTrailExecution(trailFiles, includeTags, deviceClassifiers, configClassifiers = configClassifiers)
    if (plan.filteredOutByTag > 0) {
      Console.info("Filtered ${plan.filteredOutByTag} trail(s) by --tags")
    }
    if (plan.items.isEmpty()) {
      Console.info("No trail files to run after filtering. Exiting.")
      return TrailblazeExitCode.SUCCESS.code
    }
    Console.info("Delegating ${plan.items.size} trail file(s) to running Trailblaze daemon...")
    Console.info(SECTION_DIVIDER)

    var passed = 0
    var failed = 0
    var skipped = 0
    // Same per-file worst-code tracking as the in-process path above. The daemon
    // RPC currently only signals success/failure (boolean `response.success`), so
    // we don't yet have a per-file ASSERTION-vs-INFRA distinction from this side;
    // map every failure to ASSERTION_FAILED for now so a real trail-assertion
    // failure stays distinguishable from a daemon outage (which surfaces as an
    // exception from `daemon.runSync` and routes to INFRA via the IO envelope).
    // When the daemon RPC grows a typed exit code in its response, swap this in.
    var worstExitCode = TrailblazeExitCode.SUCCESS.code

    // Register Ctrl+C handler to cancel the in-flight daemon run
    Runtime.getRuntime().addShutdownHook(Thread {
      val runId = daemon.currentRunId
      if (runId != null) {
        Console.info("\nCancelling run...")
        daemon.cancelRunBlocking(runId)
      }
    })

    // Capture is handled by the daemon's DesktopYamlRunner — running two screenrecord/simctl
    // processes on the same device causes conflicts, so we skip capture in the CLI delegate path.
    for ((index, item) in plan.items.withIndex()) {
      val total = plan.items.size
      when (item) {
        is TrailExecutionItem.Skip -> {
          Console.info("\n[${index + 1}/$total] Skipping: ${item.file.name}")
          Console.info(ITEM_DIVIDER)
          Console.info("Skipped: ${item.reason}")
          skipped++
        }
        is TrailExecutionItem.Run -> {
          val file = item.file
          val deviceSpecs = resolveRunDeviceSpecsOrReport(
            file, "${index + 1}/$total", explicitDevices, defaultDevice, connectedSpecs,
          )
          if (deviceSpecs == null) {
            failed++
            worstExitCode = chooseWorseExitCode(worstExitCode, TrailblazeExitCode.MISUSE.code)
            continue
          }
          // One daemon run per resolved device (usually one; several with --device a,b / --all-devices).
          for (resolvedDeviceSpec in deviceSpecs) {
            val runLabel = if (deviceSpecs.size > 1) {
              "${file.name} on ${resolvedDeviceSpec ?: "default device"}"
            } else {
              file.name
            }
            Console.info("\n[${index + 1}/$total] Running: $runLabel")
            Console.info(ITEM_DIVIDER)

            val rawYaml = file.readText()
            val yamlContent = TrailYamlTemplateResolver.resolve(rawYaml, file)
            val testName = testNameOverride?.trim()?.takeIf { it.isNotBlank() } ?: deriveTestName(file)

            // Surface the effective replay-vs-AI mode to the user before the run kicks off.
            // See [logReplayModeBanner] for the three-state rationale and wording.
            val effectiveUseRecordedSteps = resolveUseRecordedSteps(yamlContent)
            logReplayModeBanner(file, yamlContent, effectiveUseRecordedSteps)

            val request = CliRunRequest(
              yamlContent = yamlContent,
              trailFilePath = file.absolutePath,
              testName = testName,
              driverType = driverType,
              deviceId = resolvedDeviceSpec,
              llmProvider = llmProvider,
              llmModel = llmModel,
              useRecordedSteps = effectiveUseRecordedSteps,
              // --show-browser is the legacy flag; --headless is the new spelling. Either
              // produces a visible browser when explicitly requested. Both are off by default.
              showBrowser = showBrowser || !headless,
              noLogging = noLogging,
              agentImplementation = agent.takeIf { it != AgentImplementation.DEFAULT.name },
              selfHeal = selfHeal,
              captureVideo = captureVideo || captureAll,
              captureLogcat = captureLogcat || captureAll,
              captureIosLogs = captureIosLogs || captureAll,
              // Tri-state: forward the explicit flag value when the user passed
              // --capture-network / --no-capture-network, else null so the daemon inherits its
              // saved "Capture Network Traffic" setting (TrailblazeDesktopApp resolves
              // `request.captureNetworkTraffic ?: appConfig`). --capture-all forces it on.
              captureNetworkTraffic = if (captureAll) true else captureNetwork,
              maxLlmCalls = resolveEffectiveMaxLlmCalls(),
              initialMemorySeeds = parsedMemorySeeds(),
              initialMemorySensitiveSeeds = parsedSensitiveSeeds(),
            )

            var lastProgress: String? = null
            val response = daemon.runSync(request) { progress ->
              Console.info(progress)
              lastProgress = progress
            }

            if (response.success) {
              Console.info("✅ PASSED")
              passed++
              // Generate recording from session logs, then save to trail source directory —
              // gating delegated to shouldSaveRecording. See the in-process loop above and
              // the mirror site inside runSingleTrailFile below for the full heuristic.
              val sid = response.sessionId
              if (sid != null) {
                if (shouldSaveRecording(file, response.deviceClassifiers)) {
                  val sessionId = SessionId(sid)
                  generateRecordingForSession(sessionId)
                  saveRecordingToTrailDirectory(
                    file, sessionId, response.deviceClassifiers,
                  )
                } else {
                  logSkippedRecording(file, response.deviceClassifiers)
                }
              }
            } else {
              val err = response.error ?: "Unknown error"
              if (failureBodyAlreadyStreamed(lastProgress, err)) {
                Console.error("❌ FAILED")
              } else {
                Console.error("❌ FAILED: $err")
              }
              failed++
              worstExitCode = chooseWorseExitCode(worstExitCode, TrailblazeExitCode.ASSERTION_FAILED.code)
            }
          }
        }
      }
    }

    Console.info("\n" + SECTION_DIVIDER)
    Console.info("Results: $passed passed, $failed failed, $skipped skipped (${passed + failed} run(s) across ${plan.items.size} trail file(s))")
    // Match the success marker emitted by the in-process path at the per-file `onComplete`
    // site, so `./trailblaze run <file>` prints the same phrase regardless of whether it
    // runs in-process or via the daemon. Suppress the marker when nothing actually ran
    // (e.g., every trail was skipped) — exit-code 0 is still correct but the phrase would
    // mis-imply work was done.
    if (failed == 0 && passed > 0) {
      Console.info("\n✅ Trail completed successfully!")
    }

    // Flush output before exiting so error messages are visible in CI logs
    System.out.flush()
    System.err.flush()
    exitProcess(if (failed > 0) worstExitCode else TrailblazeExitCode.SUCCESS.code)
  }

  /**
   * Returns true when the run's terminal error body was already streamed via the
   * progress callback. The runner emits the failure exception as a
   * `[<device>] Error: <body>` progress message AND sets the same `<body>` on
   * `response.error` / `TrailExecutionResult.Failed.errorMessage` — without
   * this guard the `❌ FAILED:` line reprinted the full multi-line status
   * block (prompt JSON, Status Type, Status JSON) verbatim.
   *
   * Matches when the trimmed progress message ENDS WITH the trimmed error
   * body. The streamed progress is `[<device>] Error: <body>` and the
   * response error is `<body>` alone, so a suffix match against the full
   * body is strictly stronger than a first-line `contains` check (which
   * would false-positive when a generic first line — "Error", "Failed",
   * "Timeout" — appears anywhere in the progress stream). Prefix tolerance
   * for the `[<device>] Error: ` head comes for free from `endsWith`.
   */
  internal fun failureBodyAlreadyStreamed(lastProgress: String?, error: String): Boolean {
    if (lastProgress.isNullOrBlank() || error.isBlank()) return false
    val trimmedError = error.trim()
    return trimmedError.isNotEmpty() && lastProgress.trim().endsWith(trimmedError)
  }

  /**
   * Resolve which device spec(s) a plan item runs on, handling the non-runnable outcomes inline so
   * the in-process and daemon-delegated loops share identical fail-loud behavior: on
   * [TrailDeviceSelection.Ambiguous] / [TrailDeviceSelection.NoDevices] it prints the item header
   * plus the matching error envelope and returns `null` (the caller counts a MISUSE failure and
   * skips the item). On success it returns the spec(s) to run — one per device (a `null` element
   * means "let downstream device loading resolve it").
   */
  private fun resolveRunDeviceSpecsOrReport(
    file: File,
    indexLabel: String,
    explicitDevices: List<String>,
    defaultDevice: String?,
    connectedSpecs: List<String>?,
  ): List<String?>? =
    when (
      val selection =
        resolveDevicesForFile(file, explicitDevices, allDevices, defaultDevice, driverType, connectedSpecs)
    ) {
      is TrailDeviceSelection.Resolved -> selection.deviceSpecs
      is TrailDeviceSelection.Ambiguous -> {
        Console.info("\n[$indexLabel] ${file.name}")
        Console.info(ITEM_DIVIDER)
        if (allDevices) {
          // Under `--all-devices`, Ambiguous specifically means "none of the connected devices
          // match the trail's supported platforms" (the zero-connected case is NoDevices). The
          // "pick one of several" envelope would misdescribe that, so report the real reason.
          reportCliError(
            verb = "Trail run",
            reason = "no connected device matches the trail's supported platforms",
            hint = "connect a supported device, or pass `--device <spec>` to run one anyway " +
              "(natural-language mode): ${selection.candidateSpecs.joinToString(", ")}",
          )
        } else {
          emitMultipleDevicesEnvelope(verb = "Trail run", specs = selection.candidateSpecs)
        }
        null
      }
      TrailDeviceSelection.NoDevices -> {
        Console.info("\n[$indexLabel] ${file.name}")
        Console.info(ITEM_DIVIDER)
        emitNoDevicesEnvelope(verb = "Trail run")
        null
      }
    }

  /**
   * Per-file device selection for `run`, applied in both the in-process and daemon-delegated
   * loops so they behave identically. Defers web/compose (virtual-device) trails to downstream
   * routing, then — for real devices — resolves the platform filter from explicit signals only
   * (`--driver`'s platform, or the trail's declared platforms under `--all-devices`) and delegates
   * the actual choice to the pure [TrailDeviceSelector.selectDevicesToRun].
   */
  internal fun resolveDevicesForFile(
    file: File,
    explicitDevices: List<String>,
    allDevices: Boolean,
    defaultDevice: String?,
    driverType: String?,
    connectedSpecs: List<String>?,
  ): TrailDeviceSelection {
    val driverPlatform = driverType?.let { TrailblazeDriverType.fromString(it)?.platform }

    // Read the trail's declared platforms whenever the choice depends on them: no `--device` /
    // `--driver`, AND at least one autodetected real device (`connectedSpecs` non-empty). That
    // covers (a) routing a web/compose trail to its virtual device regardless of the connected
    // mobile devices — single OR several — and (b) narrowing an `--all-devices` fan-out. The
    // 0-device and explicitly-pinned paths (`connectedSpecs == null`) skip the read: they defer
    // downstream or honor the pin below. `--device` / `--driver` also skip it.
    val needsTrailPlatforms = driverPlatform == null && explicitDevices.isEmpty() &&
      !connectedSpecs.isNullOrEmpty()
    val trailPlatforms: Set<TrailblazeDevicePlatform>? = if (needsTrailPlatforms) {
      val yaml = runCatching {
        TrailYamlTemplateResolver.resolve(file.readText(), file)
      }.getOrNull()
      // Unreadable trail with real devices attached → no basis to route/narrow → ask for --device.
      if (yaml == null) {
        Console.log(
          "[device-select] couldn't read ${file.name} to determine supported platforms; " +
            "asking for an explicit --device among $connectedSpecs",
        )
        return TrailDeviceSelection.Ambiguous(connectedSpecs!!)
      }
      supportedPlatformsForTrail(yaml)
    } else {
      null
    }

    // A web/compose trail runs on a virtual device that downstream device loading provisions on
    // demand, independent of the connected real devices autodetect enumerates — mirroring
    // `loadConnectedDevices`, which already ignores real devices for WEB/COMPOSE. So defer to
    // downstream routing uniformly (whether 1 or several mobile devices are attached) rather than
    // pinning the autodetected mobile device or failing on the device count.
    if (trailPlatforms != null && trailPlatforms.isNotEmpty() &&
      trailPlatforms.all { it.usesVirtualDevice }
    ) {
      Console.log(
        "[device-select] ${file.name}: web/compose trail → deferring to downstream " +
          "virtual-device routing (ignoring connected real devices $connectedSpecs)",
      )
      return TrailDeviceSelection.Resolved(listOf(null))
    }

    // The platform filter applied to the connected set comes ONLY from explicit signals:
    //  - `--driver` forces a single platform → narrow the pick to it.
    //  - `--all-devices` fans out across the trail's declared platforms.
    // The bare default path deliberately does NOT narrow by the trail's declared platforms: a
    // trail runs as natural language on any device where it has no recording, so those platforms
    // aren't a hard target — merely where recordings exist. With 2+ devices connected and no
    // explicit choice we therefore ask for `--device` (like `snapshot`/`tool`) rather than guess
    // which device the author meant. Empty `supported` = "any", so the pure selector's single-vs-
    // several check below becomes a strict connected-device-count check on the default path.
    val supported: Set<TrailblazeDevicePlatform> = when {
      driverPlatform != null -> setOf(driverPlatform)
      allDevices -> trailPlatforms.orEmpty()
      else -> emptySet()
    }
    val selection =
      TrailDeviceSelector.selectDevicesToRun(explicitDevices, allDevices, defaultDevice, connectedSpecs, supported)
    // Trace how a multi-device shell was resolved so a CI run's device choice is debuggable.
    if (!connectedSpecs.isNullOrEmpty() && connectedSpecs.size > 1) {
      when (selection) {
        is TrailDeviceSelection.Resolved ->
          Console.log(
            "[device-select] ${file.name}: ${connectedSpecs.size} devices connected → running on " +
              selection.deviceSpecs.joinToString(", ") { it ?: "<default>" },
          )
        is TrailDeviceSelection.Ambiguous ->
          Console.log("[device-select] ${file.name}: ambiguous among ${selection.candidateSpecs}; asking for --device")
        is TrailDeviceSelection.NoDevices -> Unit
      }
    }
    return selection
  }

  /**
   * The set of device platforms a trail declares support for. Delegates to the shared
   * [TrailDeviceSelector.supportedPlatformsForTrail] with this process's full tool codec.
   */
  internal fun supportedPlatformsForTrail(yaml: String): Set<TrailblazeDevicePlatform> =
    TrailDeviceSelector.supportedPlatformsForTrail(createTrailblazeYaml(), yaml)

  /** Moves a file, falling back to copy+delete when renameTo fails (e.g., cross-filesystem). */
  private fun moveFile(src: File, dest: File): Boolean {
    if (src.renameTo(dest)) return true
    // renameTo fails across filesystems; fall back to copy + delete
    return try {
      src.copyTo(dest, overwrite = true)
      src.delete()
      true
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Builds the list of available devices for trail execution.
   *
   * Compose and web trails use virtual devices (no scanning needed).
   * Mobile trails scan for physically connected Android/iOS devices.
   * Returns null (after printing an error) if no devices are found.
   */
  private fun loadConnectedDevices(
    trailDriverType: TrailblazeDriverType?,
    trailPlatform: TrailblazeDevicePlatform?,
    app: TrailblazeDesktopApp,
    composePort: Int,
  ): List<TrailblazeConnectedDeviceSummary>? {
    if (trailDriverType == TrailblazeDriverType.COMPOSE) {
      Console.log("Detected compose trail — using Compose RPC driver (port $composePort)")
      return listOf(
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.COMPOSE,
          instanceId = "compose",
          description = "Compose (RPC)",
        ),
      )
    }
    if (trailPlatform == TrailblazeDevicePlatform.WEB) {
      Console.info("Detected web trail — using Playwright browser")
      return listOf(
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          instanceId = TrailblazeDeviceManager.PLAYWRIGHT_NATIVE_INSTANCE_ID,
          description = "Playwright Browser (Native)",
        ),
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
          instanceId = TrailblazeDeviceManager.PLAYWRIGHT_ELECTRON_INSTANCE_ID,
          description = "Playwright Electron (CDP)",
        ),
      )
    }
    Console.info("Loading connected devices...")
    val scannedDevices = runBlocking {
      app.deviceManager.loadDevicesSuspend(applyDriverFilter = false)
    }
    if (scannedDevices.isEmpty()) {
      Console.error("Error: No devices connected.")
      Console.error("  Android: Connect via USB or start an emulator")
      Console.error("  iOS: Start an iOS simulator via Xcode")
      Console.error("  Web: Add 'driver: PLAYWRIGHT_NATIVE' to your trail config")
      return null
    }
    return scannedDevices
  }

  /**
   * Resolves which device to run the trail on by delegating to [CliRunDeviceResolver] — the same
   * concretization the daemon's `/cli/run` handler applies — so the in-process (`--no-daemon`) and
   * daemon-delegated paths cannot drift. The trail's declared platforms come from the same
   * version-aware [supportedPlatformsForTrail] read the daemon feeds the resolver, so a unified
   * web trail (whose platform is derivable only from its recordings, not a v1 `platform:` field)
   * routes to the browser here too; the read is folded in here so tests pin the
   * YAML→platforms→device composition, not just the resolver policy. Notably that policy fails
   * loud with [CliRunDeviceResolution.MultipleDevices] when several real devices could run and
   * nothing picked one, instead of the old silent first-pick. Mostly a backstop here:
   * [resolveDevicesForFile] already applied the same fail-loud policy upstream, so by this point
   * [deviceSpec] is either concrete or a deliberate "defer to downstream" `null` (web/compose
   * trails, zero autodetected devices).
   */
  internal fun resolveRunDevice(
    yamlContent: String,
    connectedDevices: List<TrailblazeConnectedDeviceSummary>,
    trailDriverType: TrailblazeDriverType?,
    deviceSpec: String?,
  ): CliRunDeviceResolution = CliRunDeviceResolver.resolve(
    devices = connectedDevices,
    requestedDeviceId = deviceSpec,
    requestedDriverType = trailDriverType,
    trailPlatforms = supportedPlatformsForTrail(yamlContent),
  )

  private fun printAvailableDevices(devices: List<TrailblazeConnectedDeviceSummary>) {
    Console.error("Available devices:")
    devices.forEach { d ->
      Console.error("  - ${d.trailblazeDeviceId.instanceId} (${d.platform.displayName}, ${d.trailblazeDriverType})")
    }
  }

  /**
   * Resolves the LLM model from CLI flags or saved settings.
   * Returns null (after printing an error with provider status) if no model is available.
   */
  private fun resolveLlmModel(
    config: TrailblazeDesktopAppConfig,
    llmProvider: String?,
    llmModelId: String?,
  ): TrailblazeLlmModel? {
    if (llmProvider != null || llmModelId != null) {
      return config.resolveLlmModel(llmProvider, llmModelId)
        ?: run {
          Console.error("Error: Could not resolve LLM model for provider='$llmProvider', model='$llmModelId'.")
          Console.error("Ensure the provider has a configured API key and the model ID is valid.")
          null
        }
    }
    return try {
      config.getCurrentLlmModel()
    } catch (e: Exception) {
      Console.error("Error: No AI provider configured.")
      Console.error("")
      val tokenStatuses = config.getAllLlmTokenStatuses()
      if (tokenStatuses.isNotEmpty()) {
        Console.error("AI providers:")
        for ((provider, status) in tokenStatuses.entries.sortedBy { it.key.display }) {
          val (icon, text) = when (status) {
            is LlmTokenStatus.Available -> "+" to "Available"
            is LlmTokenStatus.Expired -> "!" to "Expired (may need refresh)"
            is LlmTokenStatus.NotAvailable -> {
              val envVar = LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(provider)
              "-" to "Not configured${if (envVar != null) " (set $envVar)" else ""}"
            }
          }
          Console.error("  [$icon] ${provider.display}: $text")
        }
        Console.error("")
      }
      Console.error("Set up a provider:    trailblaze config llm <provider/model>")
      Console.error("Or skip AI entirely:  trailblaze tool <name> (direct device control)")
      null
    }
  }

  /**
   * Runs a single .trail.yaml file and returns the exit code and any new session IDs created.
   *
   * [deviceSpec] is the device this file resolved to — an explicit `--device`/pin, the single
   * connected device, or (on a multi-device shell) the one connected device whose platform the
   * trail supports. `null` means "let downstream device loading resolve it" (e.g. web/compose
   * trails that use virtual devices, or the no-devices error path).
   */
  private fun runSingleTrailFile(
    file: File,
    deviceSpec: String?,
    app: TrailblazeDesktopApp,
  ): Pair<Int, List<SessionId>> {
    // Read the YAML file and resolve template variables (e.g., {{CWD}}, {{BASE_URL}})
    val rawYaml = file.readText()
    val yamlContent = TrailYamlTemplateResolver.resolve(rawYaml, file)
    if (verbose) {
      Console.log("Reading trail file: ${file.absolutePath}")
      Console.log("YAML content (${yamlContent.length} bytes)")
    }

    // Resolve the device's classifiers from the resolved device spec (cached; the plan step
    // already warmed this) so a unified trail's per-classifier `devices:` driver pin resolves
    // closest-wins BEFORE device selection. Without this, `extractTrailConfig` has no device to
    // resolve the map against, returns a null driver, and the trail silently runs on the
    // device's default driver instead of the one it pins (e.g. ANDROID_ONDEVICE_ACCESSIBILITY).
    val deviceClassifiersForDriver = DeviceClassifierResolver.resolveFromSpec(deviceSpec)

    // Parse trail config to extract driver and platform hints (before device loading so we can
    // short-circuit for web/compose trails that don't need Android/iOS device discovery).
    val trailConfig = try {
      createTrailblazeYaml().extractTrailConfig(yamlContent, deviceClassifiersForDriver)
    } catch (_: Exception) {
      null
    }

    // Resolve driver type: CLI --driver flag > trail config driver field > app setting.
    // The app-setting lookup keys on platform. v1 configs carry `platform:` directly; unified
    // configs never do (it's derived), so fall back to the platform implied by the resolved
    // device classifiers — otherwise the saved per-platform driver (e.g. the user's
    // `selectedTrailblazeDriverTypes[ANDROID]`) is silently ignored for every unified trail.
    val resolvedPlatform = trailConfig?.platform?.let { TrailblazeDevicePlatform.fromString(it) }
      ?: deviceClassifiersForDriver.firstNotNullOfOrNull { TrailblazeDevicePlatform.fromString(it.classifier) }
    val appSettingDriverType = resolvedPlatform?.let { platform ->
      app.deviceManager.settingsRepo.serverStateFlow.value
        .appConfig.selectedTrailblazeDriverTypes[platform]
    }
    val driverString = driverType ?: trailConfig?.driver ?: appSettingDriverType?.name
    val trailDriverType = driverString?.let { ds ->
      TrailblazeDriverType.fromString(ds)
        ?: run {
          reportCliError(
            verb = "Trail run",
            reason = "unknown driver type '$ds'",
            hint = "valid driver types: ${TrailblazeDriverType.entries.joinToString { it.name }}",
          )
          return TrailblazeExitCode.MISUSE.code to emptyList()
        }
    }

    // Derive platform: driver takes precedence, then fall back to config platform string.
    val trailPlatform = trailDriverType?.platform
      ?: trailConfig?.platform?.let { TrailblazeDevicePlatform.fromString(it) }

    // Renamed from `allDevices` to avoid shadowing the `--all-devices` command field.
    val connectedDevices = loadConnectedDevices(trailDriverType, trailPlatform, app, composePort)
      ?: return TrailblazeExitCode.INFRA_FAILED.code to emptyList()

    // Outcome mapping mirrors the daemon's `/cli/run` handler (TrailblazeDesktopApp) — same
    // resolver, different presentation surface (envelopes + exit codes here, HTTP payload there).
    val targetDevice = when (
      val resolution = resolveRunDevice(yamlContent, connectedDevices, trailDriverType, deviceSpec)
    ) {
      is CliRunDeviceResolution.Selected -> resolution.device.also {
        Console.log(
          "[device-select] ${file.name}: deviceId=${deviceSpec ?: "<none>"} " +
            "driver=${trailDriverType ?: "<none>"} → ${it.trailblazeDeviceId.toFullyQualifiedDeviceId()}",
        )
      }
      is CliRunDeviceResolution.MultipleDevices -> {
        emitMultipleDevicesEnvelope(
          verb = "Trail run",
          specs = resolution.candidates.map { it.trailblazeDeviceId.toFullyQualifiedDeviceId() },
        )
        return TrailblazeExitCode.MISUSE.code to emptyList()
      }
      is CliRunDeviceResolution.NoMatch -> {
        reportCliError(
          verb = "Trail run",
          reason = resolution.reason,
          hint = "connect a matching device, or pick one below with --device <platform>/<instance-id>",
        )
        printAvailableDevices(connectedDevices)
        return TrailblazeExitCode.INFRA_FAILED.code to emptyList()
      }
    }

    Console.info("Target device: ${targetDevice.trailblazeDeviceId.instanceId} (${targetDevice.platform.displayName})")
    Console.info("Driver: ${trailDriverType ?: targetDevice.trailblazeDriverType}")

    // Parse agent implementation
    val agentImpl = try {
      AgentImplementation.valueOf(agent.uppercase())
    } catch (e: IllegalArgumentException) {
      reportCliError(
        verb = "Trail run",
        reason = "invalid agent implementation '$agent'",
        hint = "valid options: ${AgentImplementation.entries.joinToString(", ") { it.name }}",
      )
      return TrailblazeExitCode.MISUSE.code to emptyList()
    }

    val config = parent.configProvider()
    val llmModel = resolveLlmModel(config, llmProvider, llmModel)
      ?: return TrailblazeExitCode.INFRA_FAILED.code to emptyList()

    Console.info("Using LLM: ${llmModel.trailblazeLlmProvider.id}/${llmModel.modelId}")
    Console.info("Agent: $agentImpl")

    val testName = testNameOverride?.trim()?.takeIf { it.isNotBlank() } ?: deriveTestName(file)

    val effectiveUseRecordedSteps = resolveUseRecordedSteps(yamlContent)
    logReplayModeBanner(file, yamlContent, effectiveUseRecordedSteps)

    // Pin a session ID upfront so the post-completion status check can target THIS
    // trail's session rather than enumerating every "new" session in the logs repo.
    // See SessionId.pinnedFor — same construction is used in handleCliRunRequest.
    val pinnedSessionId = SessionId.pinnedFor(testName)

    // Create the run request
    val runYamlRequest = RunYamlRequest(
      testName = testName,
      yaml = yamlContent,
      trailFilePath = file.absolutePath,
      targetAppName = trailConfig?.target,
      useRecordedSteps = effectiveUseRecordedSteps,
      trailblazeDeviceId = targetDevice.trailblazeDeviceId,
      trailblazeLlmModel = llmModel,
      driverType = trailDriverType,
      config = TrailblazeConfig(
        // Either --show-browser or --no-headless (i.e. headless=false) makes the browser
        // visible. Both default to off (browser stays headless).
        browserHeadless = !showBrowser && headless,
        selfHeal = resolveEffectiveSelfHeal(),
        overrideSessionId = pinnedSessionId,
        // Tri-state: the explicit flag wins; when omitted, inherit the desktop app's saved
        // "Capture Network Traffic" setting (this in-process path has no daemon to fall back
        // to, so resolve appConfig here directly). --capture-all forces it on.
        captureNetworkTraffic =
          if (captureAll) {
            true
          } else {
            captureNetwork
              ?: config.trailblazeSettingsRepo.serverStateFlow.value.appConfig.captureNetworkTraffic
          },
        // Honor the persisted desktop-app "agent execution location" setting (Settings →
        // preferHostAgent) so a CLI run replays the same way the desktop app and CI do.
        // Without this the CLI silently used the model default (true = host-driven via RPC),
        // diverging from a user who has toggled on-device execution. Matches the recording-tab
        // path (DeviceConnectionService) which already threads appConfig.preferHostAgent.
        preferHostAgent =
          config.trailblazeSettingsRepo.serverStateFlow.value.appConfig.preferHostAgent,
      ),
      referrer = TrailblazeReferrer(id = "cli", display = "CLI"),
      agentImplementation = agentImpl,
      maxLlmCalls = resolveEffectiveMaxLlmCalls(),
      initialMemorySeeds = parsedMemorySeeds(),
      initialMemorySensitiveSeeds = parsedSensitiveSeeds(),
    )

    return executeTrailAndCollectResults(app, runYamlRequest, trailConfig, config, file, pinnedSessionId)
  }

  /**
   * Executes a trail via the desktop YAML runner and collects results.
   *
   * Handles building [DesktopAppRunYamlParams], running the trail, waiting for completion,
   * cross-checking session status from logs, and generating recordings.
   *
   * @return exit code and list of new session IDs created during execution
   */
  private fun executeTrailAndCollectResults(
    app: TrailblazeDesktopApp,
    runYamlRequest: RunYamlRequest,
    trailConfig: TrailConfig?,
    config: TrailblazeDesktopAppConfig,
    file: File,
    pinnedSessionId: SessionId,
  ): Pair<Int, List<SessionId>> {
    // Latch to wait for completion
    val completionLatch = CountDownLatch(1)
    var exitCode = TrailblazeExitCode.SUCCESS.code
    // Tracks the last progress message streamed via `onProgressMessage` so the
    // terminal `onComplete = Failed` branch can avoid restating the full
    // exception body — the runner emits it as a progress update and ALSO sets
    // it on `TrailExecutionResult.Failed.errorMessage`, which previously
    // produced a duplicate multi-line failure block.
    var lastProgress: String? = null

    // Resolve target app: prefer trail config's `target` field, fall back to settings selection.
    // This ensures custom tools (e.g., myApp_launchSignedIn) are registered for the
    // correct app even when the desktop UI has a different app selected.
    //
    // Deliberately does NOT consult the per-terminal target pin (`resolveCliTargetPin`).
    // Trails are reusable artifacts that travel between users and CI — if a trail needs
    // a target, it declares one in its config. Inheriting the caller's terminal pin would
    // make the same trail behave differently between developers, which defeats the
    // determinism contract. The device pin IS consulted (above, in the resolver chain)
    // because the device a trail runs on is operator-scoped, not trail-scoped.
    val targetTestApp = trailConfig?.target?.let { config.availableAppTargets.findById(it) }
      ?: app.deviceManager.getCurrentSelectedTargetApp()
    if (verbose) {
      Console.log("Target app: ${targetTestApp?.displayName ?: "None (using built-in tools only)"}")
    }

    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = false,
      runYamlRequest = runYamlRequest,
      targetTestApp = targetTestApp,
      onProgressMessage = { message ->
        Console.info(message)
        lastProgress = message
      },
      onConnectionStatus = { status ->
        when (status) {
          is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure -> {
            Console.error("Connection failed: ${status.errorMessage ?: "unknown cause"}")
            exitCode = TrailblazeExitCode.INFRA_FAILED.code
            completionLatch.countDown()
          }
          is DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning -> {
            if (verbose) {
              Console.log("Instrumentation running on device: ${status.trailblazeDeviceId.instanceId}")
            }
          }
          else -> {
            if (verbose) {
              Console.log("Connection status: $status")
            }
          }
        }
      },
      // Pull instrumentation args from the configured app — the desktop UI flow does the same
      // via `desktopAppConfig.additionalInstrumentationArgs()`, but the CLI path was passing
      // emptyMap() and dropping any args the config wanted to forward (LLM auth tokens, the
      // migration-mode dual-tree capture flag, etc.). Calling the same provider keeps env-var
      // bridging consistent across CLI and UI invocations.
      additionalInstrumentationArgs = runBlocking { config.additionalInstrumentationArgs() },
      composeRpcPort = composePort,
      // Forward the CLI capture flags into the in-process (`--no-daemon`) run path. The daemon
      // path forwards these via `CliRunRequest`; without this the no-daemon path dropped them
      // and `DesktopYamlRunner` fell back to the persisted desktop appConfig — so
      // `trailblaze run --no-daemon --capture-ios-logs` (and `--no-capture-video` /
      // `--capture-logcat`) silently had no effect. `null` would mean "use appConfig"; pass the
      // resolved flag instead. `--capture-all` folds into each via `captureOptions`.
      captureVideo = captureOptions.captureVideo,
      captureLogcat = captureOptions.captureLogcat,
      captureIosLogs = captureOptions.captureIosLogs,
      onComplete = { result ->
        when (result) {
          is TrailExecutionResult.Success -> {
            Console.info("\n✅ Trail completed successfully!")
            exitCode = TrailblazeExitCode.SUCCESS.code
          }
          is TrailExecutionResult.Failed -> {
            // Trail-execution failure means an objective or recorded assertion did not pass
            // — that's an ASSERTION_FAILED (1) outcome, not an infra (2) one. Infra failures
            // are surfaced via the `onConnectionStatus` callback above (daemon/device
            // unreachable) and the interrupted/cancelled paths below.
            val err = result.errorMessage ?: "Unknown error"
            if (failureBodyAlreadyStreamed(lastProgress, err)) {
              Console.error("\n❌ Trail failed")
            } else {
              Console.error("\n❌ Trail failed: $err")
            }
            exitCode = TrailblazeExitCode.ASSERTION_FAILED.code
          }
          is TrailExecutionResult.Cancelled -> {
            Console.info("\n⚠️ Trail was cancelled")
            exitCode = TrailblazeExitCode.INFRA_FAILED.code
          }
        }
        completionLatch.countDown()
      },
    )

    Console.info("\nStarting trail execution...")
    Console.info(SECTION_DIVIDER)

    // Snapshot existing session IDs so we can identify new ones after execution
    val logsRepo = app.deviceManager.logsRepo
    val existingSessionIds = logsRepo.getSessionIds().toSet()

    // Run the trail
    app.desktopYamlRunner.runYaml(params)

    // Wait for completion
    try {
      completionLatch.await()
    } catch (e: InterruptedException) {
      Console.info("Execution interrupted")
      exitCode = TrailblazeExitCode.INFRA_FAILED.code
    }

    // Identify sessions created during this run and wait for their logs to stabilize.
    // The completion callback fires as soon as the trail finishes, but the device-side
    // agent may still be streaming log data (LLM requests, tool logs, session status).
    // Without waiting, exitProcess() kills the HTTP server before logs are persisted.
    val newSessionIds = awaitLogStability(logsRepo, existingSessionIds)

    // Cross-check session status from logs. The TrailExecutionResult callback may report
    // success even when individual objectives failed (e.g., the runner completed without
    // crashing but the session ended with a failure status). For on-device instrumentation,
    // the RPC call returns immediately so the TrailExecutionResult is always Success.
    // The session logs are the source of truth for pass/fail.
    //
    // Only inspect THIS trail's pinned session — `newSessionIds` enumerates every session
    // that landed in the repo during this run, which includes sibling trails when several
    // run in parallel against the same daemon (the benchmark fan-out). Without the pin,
    // a sibling's TimeoutReached gets mis-attributed to this trail and we report 0/3 even
    // when two trails actually passed (observed during testing on haiku-4-5).
    if (exitCode == TrailblazeExitCode.SUCCESS.code) {
      val status = logsRepo.getLogsForSession(pinnedSessionId).getSessionStatus()
      if (status is SessionStatus.Unknown) {
        // "No logs received" looks like infra (the device-side runner never reported in),
        // but the run did start, the daemon was reachable, the device was bound — the trail
        // simply didn't finish. INFRA_FAILED is right when the path to the device broke;
        // ASSERTION_FAILED is right when the trail's outcome is unknown but the wiring worked.
        // Treat as ASSERTION_FAILED to keep `trailblaze run` reserving exit 2 for "you can't
        // even attempt this run."
        Console.error("Session $pinnedSessionId has no status (no logs received)")
        exitCode = TrailblazeExitCode.ASSERTION_FAILED.code
      } else if (status is SessionStatus.Ended && status !is SessionStatus.Ended.Succeeded &&
        status !is SessionStatus.Ended.SucceededWithSelfHeal) {
        Console.error("Session $pinnedSessionId ended with status: ${status::class.simpleName}")
        exitCode = TrailblazeExitCode.ASSERTION_FAILED.code
      } else if (status !is SessionStatus.Ended) {
        Console.error("Session $pinnedSessionId did not complete (status: ${status::class.simpleName})")
        exitCode = TrailblazeExitCode.ASSERTION_FAILED.code
      }
    }

    // Generate recording YAML from session logs so saveRecordingToTrailDirectory can find it.
    // For host-mode runs, TrailblazeHostYamlRunner already generates this file. For on-device
    // instrumentation runs, the recording is not generated during execution, so we generate
    // it here from the session logs. Same parallel-safety reasoning as the status check
    // above — only generate for THIS trail's pinned session, not sibling sessions that
    // happen to be in the repo. Skip generation when shouldSaveRecording would no-op the
    // eventual copy anyway — no point burning the log-stability wait when the result
    // will be discarded.
    val classifiers = app.deviceManager.logsRepo.getSessionInfo(pinnedSessionId)
      ?.trailblazeDeviceInfo?.classifiers?.map { it.classifier } ?: emptyList()
    if (shouldSaveRecording(file, classifiers)) {
      generateRecordingForSession(pinnedSessionId)
    }

    return exitCode to listOf(pinnedSessionId)
  }

  /**
   * Generates a `recording.trail.yaml` file in the session's log directory from
   * the session logs, if one doesn't already exist.
   *
   * Reads logs directly from disk rather than from the cached flow, because for
   * on-device instrumentation runs the flow cache may not have all logs yet.
   */
  private fun generateRecordingForSession(sessionId: SessionId) {
    try {
      val gitRoot = GitUtils.getGitRootViaCommand() ?: return
      val sessionDir = File(File(gitRoot, "logs"), sessionId.value)
      val recordingFile = File(sessionDir, "recording.trail.yaml")
      if (recordingFile.exists()) return // Already generated (e.g., by host-mode runner)

      // Wait for all selector-based TrailblazeToolLog entries to arrive on disk.
      // On-device instrumentation logs the DelegatingTrailblazeToolLog (nodeId-based)
      // immediately but the corresponding TrailblazeToolLog (selector-based, same
      // traceId) is flushed later — sometimes 10-30s after the Ended status.
      // Poll until every DelegatingTrailblazeToolLog has a matching TrailblazeToolLog.
      val maxWaitMs = RECORDING_LOG_STABILITY_MAX_WAIT_MS
      val pollMs = RECORDING_LOG_STABILITY_POLL_MS
      val waitStart = System.currentTimeMillis()

      fun readLogs(): List<xyz.block.trailblaze.logs.client.TrailblazeLog> {
        val logFiles = sessionDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        return logFiles.mapNotNull { file ->
          try {
            xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
              .decodeFromString<xyz.block.trailblaze.logs.client.TrailblazeLog>(file.readText())
          } catch (_: Exception) {
            null
          }
        }.sortedBy { it.timestamp }
      }

      var logs = readLogs()
      while (System.currentTimeMillis() - waitStart < maxWaitMs) {
        val delegatingTraceIds = logs
          .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.DelegatingTrailblazeToolLog>()
          .mapNotNull { it.traceId }
          .toSet()
        val selectorTraceIds = logs
          .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog>()
          .mapNotNull { it.traceId }
          .toSet()
        val missingTraceIds = delegatingTraceIds - selectorTraceIds
        if (missingTraceIds.isEmpty()) break
        Console.log("Recording: waiting for ${missingTraceIds.size} TrailblazeToolLog(s) to arrive...")
        Thread.sleep(pollMs)
        logs = readLogs()
      }

      if (logs.isEmpty()) {
        Console.log("No logs found for session ${sessionId.value}, skipping recording generation")
        return
      }

      // Extract session config from the Started status log
      val startedStatus = logs
        .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog>()
        .map { it.sessionStatus }
        .filterIsInstance<SessionStatus.Started>()
        .firstOrNull()

      val sessionTrailConfig = startedStatus?.toRecordingTrailConfig()

      // Include driver-specific tool classes so the YAML serializer recognizes
      // all tools that may appear in the session logs (e.g., Playwright tools are
      // not in AllBuiltInTrailblazeToolsForSerialization).
      val driverType = startedStatus?.trailblazeDeviceInfo?.trailblazeDriverType
      val customToolClasses = when (driverType) {
        TrailblazeDriverType.PLAYWRIGHT_NATIVE ->
          TrailblazeToolSetCatalog.resolveForDriver(
            driverType, WebToolSetIds.ALL,
          ).toolClasses
        TrailblazeDriverType.PLAYWRIGHT_ELECTRON ->
          TrailblazeToolSetCatalog.resolveForDriver(
            driverType, WebToolSetIds.ALL,
          ).toolClasses + BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES
        TrailblazeDriverType.COMPOSE ->
          TrailblazeToolSetCatalog.resolveForDriver(
            driverType, ComposeToolSetIds.ALL,
          ).toolClasses
        TrailblazeDriverType.REVYL_ANDROID,
        TrailblazeDriverType.REVYL_IOS ->
          TrailblazeToolSetCatalog.resolveForDriver(
            driverType, RevylToolSetIds.ALL,
          ).toolClasses
        else -> emptySet()
      }

      val recordingYaml = logs.generateRecordedYaml(
        sessionTrailConfig = sessionTrailConfig,
        customToolClasses = customToolClasses,
      )
      if (recordingYaml.isBlank()) {
        Console.log("No recording data for session ${sessionId.value}, skipping")
        return
      }

      recordingFile.writeText(recordingYaml)
      Console.log("Recording generated: ${recordingFile.absolutePath}")
    } catch (e: Exception) {
      Console.log("Failed to generate recording for session ${sessionId.value}: ${e.message}")
    }
  }

  /**
   * Waits for session logs to reach a terminal state before returning.
   *
   * For on-device instrumentation, the RPC call returns immediately so this method
   * effectively waits for the entire trail execution to complete. For host-mode execution,
   * it waits for any trailing log data after the runner finishes.
   *
   * This method polls the logs directory until:
   * 1. At least one new session directory appears (up to [sessionDetectionTimeoutMs])
   * 2. All sessions reach a terminal [SessionStatus.Ended] status, then waits a short
   *    buffer for any trailing files
   * 3. Falls back to [maxWaitMs] timeout if the Ended status never arrives
   *
   * Sessions that exceed the [maxWaitMs] without reaching Ended status are treated as
   * failures by the caller's cross-check logic.
   */
  private fun awaitLogStability(
    logsRepo: LogsRepo,
    existingSessionIds: Set<SessionId>,
    sessionDetectionTimeoutMs: Long = 10_000,
    postEndedBufferMs: Long = 3_000,
    maxWaitMs: Long = 600_000,
    pollIntervalMs: Long = 500,
  ): List<SessionId> {
    // Phase 1: Wait for at least one new session directory to appear.
    val detectionStart = System.currentTimeMillis()
    var newSessionIds = emptyList<SessionId>()
    while (System.currentTimeMillis() - detectionStart < sessionDetectionTimeoutMs) {
      newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }
      if (newSessionIds.isNotEmpty()) break
      Thread.sleep(pollIntervalMs)
    }
    if (newSessionIds.isEmpty()) return emptyList()

    // Phase 2: Wait for all sessions to reach a terminal status (Ended).
    // The on-device agent sends the Ended status log after completing execution, so this
    // is the definitive signal that the test is done and all logs have been generated.
    val waitStart = System.currentTimeMillis()
    var allEnded = false

    while (System.currentTimeMillis() - waitStart < maxWaitMs) {
      // Re-check for session IDs in case more appear.
      newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }

      allEnded = newSessionIds.all { sessionId ->
        val status = logsRepo.getLogsForSession(sessionId).getSessionStatus()
        status is SessionStatus.Ended
      }

      if (allEnded) break
      Thread.sleep(pollIntervalMs)
    }

    // Phase 3: Short buffer after Ended status for any trailing files (screenshots, etc.).
    if (allEnded) {
      Thread.sleep(postEndedBufferMs)
      // Re-check for any new sessions that appeared during the buffer.
      newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }
    }

    return newSessionIds
  }

  /**
   * Saves the session recording back to the trail source directory. The on-disk format is chosen
   * by [recordingSaveFormat]: a [RecordingSaveFormat.UNIFIED] target merges this device's classifier
   * slot into the unified trail resolved by [UnifiedRecordingWriter.unifiedRecordingTarget] (the executed unified file
   * itself, or the directory's shared `trail.yaml`); a [RecordingSaveFormat.LEGACY] target writes a
   * platform `<classifier>.trail.yaml` sibling (e.g. `android.trail.yaml`, `ios-iphone.trail.yaml`).
   *
   * @param trailFile The trail file or directory that was executed
   * @param sessionId The session ID from the completed run
   * @param deviceClassifiers Device classifiers for the recorded slot / filename
   */
  private fun saveRecordingToTrailDirectory(
    trailFile: File,
    sessionId: SessionId,
    deviceClassifiers: List<String>,
  ) {
    try {
      val gitRoot = GitUtils.getGitRootViaCommand() ?: return
      val recordingFile = File(File(File(gitRoot, "logs"), sessionId.value), "recording.trail.yaml")
      if (!recordingFile.exists()) {
        Console.log("No recording found for session ${sessionId.value}")
        return
      }

      when (recordingSaveFormat(trailFile, deviceClassifiers)) {
        RecordingSaveFormat.LEGACY -> {
          val targetFile = computeRecordingTargetFile(trailFile, deviceClassifiers) ?: return
          recordingFile.copyTo(targetFile, overwrite = true)
          Console.info("Recording saved to: ${targetFile.absolutePath}")
        }
        RecordingSaveFormat.UNIFIED -> saveRecordingAsUnified(trailFile, recordingFile, deviceClassifiers)
      }
    } catch (e: Exception) {
      Console.error("Failed to save recording to trail directory: ${e.message}")
    }
  }

  /**
   * Merge one device's freshly-recorded session into the unified trail resolved by
   * [UnifiedRecordingWriter.unifiedRecordingTarget] — the executed file itself when it is a unified document (bare or
   * named), otherwise the directory's shared `trail.yaml`. Writes only this device's
   * [deviceClassifiers] slot and preserves every other classifier already on disk. The recording
   * is a v1 file (`recording.trail.yaml`); the merge primitive folds its steps/trailhead/driver
   * into the (optional) existing unified trail. Creates the file on a first write.
   *
   * The fan-out run loop is sequential (one device at a time), so merging `ios` after `android` in
   * the same `trailblaze run android,ios` reads the android-merged file and keeps both slots. Two
   * SEPARATE processes recording the same trail concurrently could still race the read-modify-write;
   * a cross-process file lock is deferred (see the unified-syntax devlog's concurrency note).
   *
   * `internal` so a temp-directory unit test can assert the write contract (fresh file created; an
   * existing classifier slot preserved) without a device, daemon, or git root.
   */
  internal fun saveRecordingAsUnified(
    trailFile: File,
    recordingFile: File,
    deviceClassifiers: List<String>,
  ) {
    val classifier = deviceClassifiers.joinToString("-")
    val recordedItems = createTrailblazeYaml().decodeTrail(recordingFile.readText())
    when (val outcome = UnifiedRecordingWriter.mergeIntoUnified(trailFile, recordedItems, classifier)) {
      is UnifiedRecordingWriter.MergeOutcome.Merged ->
        Console.info("Recording merged into ${outcome.target.absolutePath} (classifier `$classifier`)")

      is UnifiedRecordingWriter.MergeOutcome.MultiToolTrailheadUnsupported -> {
        // The unified trailhead is one tool per classifier; a v1 recording whose trailhead captured
        // more than one tool has no unified representation. Preserve it as a legacy
        // `<classifier>.trail.yaml` sibling so it stays on disk and runnable rather than being lost —
        // but NOT when a unified trail already exists here, since that sibling would shadow it. In
        // that case refuse and leave the recording in logs/ for a manual migration.
        val legacyTarget = computeRecordingTargetFile(trailFile, deviceClassifiers)
        if (legacyTarget == null || UnifiedRecordingWriter.unifiedTrailPresent(trailFile)) {
          Console.error(
            "✗ Recording not saved: recorded trailhead has ${outcome.toolCount} tools, which the " +
              "unified one-tool-per-classifier trailhead can't represent, and a legacy sibling would " +
              "shadow the unified trail (or no legacy target path is available).\n" +
              "  hint: migrate the multi-tool trailhead by hand — this run's recording is preserved at " +
              "${recordingFile.absolutePath}",
          )
          return
        }
        recordingFile.copyTo(legacyTarget, overwrite = true)
        Console.info(
          "[unified-record] recorded trailhead has ${outcome.toolCount} tools; the unified format is " +
            "one tool per classifier, so saved as legacy ${legacyTarget.absolutePath} instead of merging into the unified trail.",
        )
      }

      is UnifiedRecordingWriter.MergeOutcome.RefusedCorrupt ->
        Console.error(
          "✗ Recording not saved: existing unified trail is unreadable\n" +
            "  file: ${outcome.target.absolutePath}\n" +
            "  reason: ${outcome.reason}\n" +
            "  hint: fix or delete that file, then re-run — this run's recording is preserved at " +
            "${recordingFile.absolutePath}",
        )

      is UnifiedRecordingWriter.MergeOutcome.SkippedEmpty ->
        Console.log("[unified-record] recording for `$classifier` has no steps to merge; skipping unified write.")

      is UnifiedRecordingWriter.MergeOutcome.NoTarget ->
        // Only reachable for an orphan path with no directory (routing sends those to LEGACY, so this
        // is defensive) — log rather than dropping the recording silently.
        Console.log("[unified-record] no unified target resolved for ${trailFile.path}; recording left at ${recordingFile.absolutePath}.")
    }
  }

  /** How a recording save-back should be written to the trail source directory. */
  internal enum class RecordingSaveFormat { UNIFIED, LEGACY }

  /**
   * Decide whether a recording save-back for [trailFile] writes a unified trail (merging this
   * device's classifier slot) or a legacy `<classifier>.trail.yaml` sibling:
   *
   *  - The executed file IS a unified trail — the bare `trail.yaml` by name, or a NAMED file
   *    (e.g. `login.trail.yaml`) whose CONTENT is the unified format →
   *    [RecordingSaveFormat.UNIFIED] (merge into the executed file itself). The content check
   *    matters because the unified corpus is mostly named files sharing a directory with other
   *    tests; keying on the filename alone would misroute them to a legacy sibling that shadows
   *    resolution and doesn't identify which test it recorded.
   *  - The target dir already has a bare `trail.yaml` → [RecordingSaveFormat.UNIFIED] (merge
   *    into it — never drop a legacy sibling beside it).
   *  - The dir already holds legacy `<classifier>.trail.yaml` sibling(s) and no unified file →
   *    [RecordingSaveFormat.LEGACY] (don't fork a half-migrated directory; migrating those to
   *    unified is a separate, deliberate step). A v1-content file named like a trail counts as
   *    its own legacy sibling, so executing one keeps routing LEGACY.
   *  - Greenfield (neither present) → [RecordingSaveFormat.UNIFIED] (new recordings default to
   *    the unified format).
   *  - Empty [deviceClassifiers] → [RecordingSaveFormat.LEGACY] (no classifier to key a unified
   *    slot; falls back to the classifier-agnostic `recording.trail.yaml`).
   *
   * The unified file this decision writes/reads is resolved by [UnifiedRecordingWriter.unifiedRecordingTarget] — the
   * two must be consulted together so the router and the writer never disagree on the target.
   *
   * `internal` so unit tests can exercise each branch directly against a temp directory.
   */
  internal fun recordingSaveFormat(trailFile: File, deviceClassifiers: List<String>): RecordingSaveFormat =
    // Routing (incl. the rollout gate and empty-classifier / legacy-sibling / greenfield branches)
    // lives in the shared writer so the CLI, MCP, and desktop surfaces can't diverge. The CLI keeps
    // its own enum; shouldSaveRecording separately restores the pre-unified refusal to write a
    // legacy sibling next to — or in place of — a unified trail.
    if (UnifiedRecordingWriter.shouldRouteUnified(
        trailFile,
        deviceClassifiers.joinToString("-"),
        resolveEffectiveUnifiedRecordings(),
      )
    ) {
      RecordingSaveFormat.UNIFIED
    } else {
      RecordingSaveFormat.LEGACY
    }

  /**
   * Computes the target path where a recording would land for the given trail + classifiers,
   * mirroring the path logic inside [saveRecordingToTrailDirectory]. Returns `null` when the
   * trail file has no parent directory (e.g. the root) — same early-return behaviour as the
   * save path, so the two stay in lockstep.
   *
   * Used by the save-decision logic to test "does a recording already exist next to the
   * source?" before clobbering it.
   *
   * `internal` so unit tests can exercise its branches (empty vs. non-empty classifiers,
   * directory vs. file, no parent) directly instead of through the whole save pipeline.
   */
  internal fun computeRecordingTargetFile(
    trailFile: File,
    deviceClassifiers: List<String>,
  ): File? {
    val targetDir = if (trailFile.isDirectory) trailFile else trailFile.parentFile ?: return null
    val recordingFileName = if (deviceClassifiers.isNotEmpty()) {
      deviceClassifiers.joinToString("-") + TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX
    } else {
      "recording${TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX}"
    }
    return File(targetDir, recordingFileName)
  }

  /**
   * Single source of truth for "should this run write a recording back to the trail source
   * directory?" — used by all three call sites (in-process loop, daemon delegate, and the
   * in-process generation inside [runSingleTrailFile]) so the heuristic can't drift between
   * paths. Returns `true` when:
   *
   *  - the user hasn't opted out via `--no-save-recording`, AND
   *  - either `--self-heal` was enabled (the AI may have changed the recorded tool sequence
   *    so the new recording is genuinely different from what's on disk) OR no recording
   *    exists yet next to the source (first-time authoring).
   *
   * Deterministic re-runs where a recording already exists return `false` so the source
   * isn't silently clobbered. To forcibly regenerate, delete the file first.
   *
   * When the existence check is skipped because [trailFile] has no parent (returns
   * `null` from [computeRecordingTargetFile]), only the self-heal arm of the OR can fire.
   *
   * No side effects — callers (the outer save sites) are responsible for any logging.
   * The internal generation site uses this purely to short-circuit work, so it should not
   * emit user-visible output that the outer save site will repeat one moment later.
   */
  internal fun shouldSaveRecording(trailFile: File, deviceClassifiers: List<String>): Boolean {
    if (!resolveEffectiveSaveRecording()) return false
    // Unified-recordings gate OFF → pre-unified behavior: never write a legacy
    // `<classifier>.trail.yaml` next to a unified `trail.yaml` (the legacy save-back can't update
    // it, so it would only drop a divergent sibling), regardless of --self-heal. The executed-file
    // check is content-aware ([UnifiedRecordingWriter.executedFileIsUnified]) — the one deliberate deviation from
    // byte-identical-old: a NAMED unified file (e.g. `login.trail.yaml` in a shared multi-test
    // directory) used to get a v1 sibling raw-copied beside it, which damages the shared directory
    // rather than preserving anything. Refusing adds no new write path.
    if (!resolveEffectiveUnifiedRecordings() && UnifiedRecordingWriter.unifiedTrailPresent(trailFile)) {
      return false
    }
    if (resolveEffectiveSelfHeal()) return true
    // Deterministic re-run guard: skip when this device's recording already exists on disk, so a
    // plain re-run never clobbers a (possibly hand-edited) source. "Already exists" is per-format:
    //  - LEGACY: the `<classifier>.trail.yaml` sibling exists (also skip when no target resolves —
    //    an orphan file with no parent — matching prior behavior).
    //  - UNIFIED: this classifier's slot already carries a recording in the unified target file
    //    ([UnifiedRecordingWriter.unifiedRecordingTarget]). A missing file (greenfield) or an absent slot means "not
    //    recorded yet" → save.
    return when (recordingSaveFormat(trailFile, deviceClassifiers)) {
      RecordingSaveFormat.LEGACY -> {
        val targetFile = computeRecordingTargetFile(trailFile, deviceClassifiers) ?: return false
        !targetFile.exists()
      }
      RecordingSaveFormat.UNIFIED ->
        !UnifiedRecordingWriter.unifiedClassifierAlreadyRecorded(trailFile, deviceClassifiers.joinToString("-"))
    }
  }

  /** Resolves the nullable `--[no-]save-recording` flag to its effective on/off value.
   * Defaults to `true` (save by default) when the user didn't specify either form. */
  internal fun resolveEffectiveSaveRecording(): Boolean = saveRecording ?: true

  /**
   * Resolves the unified-recordings gate, same precedence as self-heal:
   *   1. `--[no-]unified-recordings` CLI flag (explicit per-run intent)
   *   2. `TRAILBLAZE_UNIFIED_RECORDINGS` env var (CI / pipeline override)
   *   3. Persisted `trailblaze config unified-recordings` setting (user's local default)
   *   4. On — unified is the default save format. Setting any tier to false is the opt-out
   *      that restores the byte-identical pre-unified legacy save-back.
   */
  internal fun resolveEffectiveUnifiedRecordings(): Boolean =
    // The CLI is the only surface with a flag tier; the shared host helper layers it over
    // env > persisted config (MCP/desktop pass no flag).
    CliConfigHelper.resolveUnifiedRecordingsGate(flagOverride = unifiedRecordings)

  /**
   * Companion of [shouldSaveRecording]. Emits a single user-visible info line when a save
   * was skipped *because* the target already exists — the only non-explicit skip reason
   * worth surfacing. The explicit opt-out (`--no-save-recording`) is silent because the
   * user already knows they asked for it.
   *
   * Called only from the outer save sites (in-process loop, daemon delegate). The inner
   * generation site inside [runSingleTrailFile] deliberately omits the call so a single
   * skipped trail produces at most one log line per session.
   */
  private fun logSkippedRecording(trailFile: File, deviceClassifiers: List<String>) {
    if (!resolveEffectiveSaveRecording()) return // user explicitly opted out — silent skip
    // Gate-off refusal next to a unified trail: the legacy save-back can't update `trail.yaml`, so
    // the save is refused rather than dropping a shadowing sibling. Surface it — the MCP/desktop
    // surfaces return an explicit error here, so the CLI shouldn't silently drop the recording.
    // Checked before the self-heal short-circuit to match shouldSaveRecording's guard order (the
    // refusal fires regardless of self-heal).
    if (!resolveEffectiveUnifiedRecordings() && UnifiedRecordingWriter.unifiedTrailPresent(trailFile)) {
      Console.info(
        "Recording not saved: this directory has a unified trail.yaml and the legacy save-back can't " +
          "update it. Enable unified recordings (--unified-recordings, TRAILBLAZE_UNIFIED_RECORDINGS=1, " +
          "or `trailblaze config unified-recordings true`) to merge this device's slot instead.",
      )
      return
    }
    if (resolveEffectiveSelfHeal()) return // shouldSaveRecording would have been true
    val skippedTarget: String = when (recordingSaveFormat(trailFile, deviceClassifiers)) {
      RecordingSaveFormat.LEGACY -> {
        val targetFile = computeRecordingTargetFile(trailFile, deviceClassifiers) ?: return
        if (!targetFile.exists()) return // not the "existing target" skip reason
        targetFile.absolutePath
      }
      RecordingSaveFormat.UNIFIED -> {
        val classifier = deviceClassifiers.joinToString("-")
        if (!UnifiedRecordingWriter.unifiedClassifierAlreadyRecorded(trailFile, classifier)) return
        val unifiedFile = UnifiedRecordingWriter.unifiedRecordingTarget(trailFile) ?: return
        "${unifiedFile.absolutePath} (classifier `$classifier`)"
      }
    }
    Console.info(
      "Recording not overwritten (target exists; pass --self-heal to regenerate, or " +
        "re-run with --use-recorded-steps to replay the recorded tools instead of " +
        "re-driving every step via the LLM): " +
        skippedTarget,
    )
  }

  /**
   * Resolves the effective `self-heal` setting for this run, honoring the usual precedence:
   *   1. `--self-heal` CLI flag (explicit per-run intent).
   *   2. `TRAILBLAZE_SELF_HEAL_ENABLED` env var (CI / pipeline intent — a CI pipeline runner
   *      may set this on runner steps when the pipeline config opts in).
   *   3. Persisted `trailblaze config self-heal` setting (user's local default).
   *   4. Opt-in default (off).
   *
   * Companion resolver for JUnit runs: [xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest]`
   * .resolveSelfHealFromEnvOrConfig()`, which is a 2-tier (env → config) subset — tests have no
   * CLI flag to honor.
   */
  internal fun resolveEffectiveSelfHeal(): Boolean =
    selfHeal
      ?: System.getenv("TRAILBLAZE_SELF_HEAL_ENABLED")?.lowercase()?.toBooleanStrictOrNull()
      ?: CliConfigHelper.readConfig()?.selfHealEnabled
      ?: false

  /**
   * Resolves the effective per-objective LLM call cap for this run, honoring:
   *   1. `--max-llm-calls` CLI flag (explicit per-run intent).
   *   2. `TRAILBLAZE_MAX_LLM_CALLS` env var (CI / pipeline cap — a build runner sets this
   *      once on shared steps rather than passing the flag per invocation).
   *   3. Workspace `trailblaze.yaml` `defaults.maxLlmCalls` (committed team-wide default —
   *      everyone in the workspace inherits the same cap without per-machine setup).
   *   4. Persisted per-machine `trailblaze config max-llm-calls` setting (individual
   *      developer's local fallback when the workspace file is silent).
   *   5. `null` — the legacy [xyz.block.trailblaze.agent.TrailblazeRunner] falls back to its
   *      own built-in [xyz.block.trailblaze.agent.TrailblazeRunner.DEFAULT_MAX_STEPS].
   *
   * Returns `null` (not a default integer) so the model-layer guard on `RunYamlRequest.init`
   * sees "unspecified" rather than a sentinel and only the runner constructor materializes
   * the default. Each tier is independently validated: malformed values (non-integer or
   * non-positive) fall through to the next tier with a single warning rather than crashing
   * the invocation.
   */
  internal fun resolveEffectiveMaxLlmCalls(): Int? = resolveEffectiveMaxLlmCalls(
    cwd = Paths.get(""),
    envReader = { name -> System.getenv(name) },
    persistedConfigReader = { CliConfigHelper.readConfig()?.maxLlmCalls },
  )

  /**
   * Testable overload — callers can inject [cwd] (used to discover the workspace
   * `trailblaze.yaml`), an [envReader] for the env-var tier, and a
   * [persistedConfigReader] for the persisted-config tier. The default-arg overload above
   * binds these to real process state.
   */
  internal fun resolveEffectiveMaxLlmCalls(
    cwd: java.nio.file.Path,
    envReader: (String) -> String?,
    persistedConfigReader: () -> Int?,
  ): Int? =
    maxLlmCalls
      ?: parsePositiveIntOrWarn(envReader("TRAILBLAZE_MAX_LLM_CALLS"), "TRAILBLAZE_MAX_LLM_CALLS env var")
      ?: workspaceMaxLlmCalls(cwd)
      ?: parsePositiveIntOrWarn(
        persistedConfigReader(),
        "persisted `trailblaze config max-llm-calls` value",
      )

  /**
   * Reads `defaults.maxLlmCalls` from the workspace `trailblaze.yaml` discovered from
   * [cwd], or null when no workspace file resolves or the field is absent / invalid.
   * Loader failures are caught and logged so a parse error in the workspace file never
   * blocks a CLI invocation — the cap falls through to the persisted-config tier instead.
   */
  private fun workspaceMaxLlmCalls(cwd: java.nio.file.Path): Int? {
    val resolved = try {
      TrailblazeWorkspaceConfigResolver.resolve(cwd)
    } catch (e: Exception) {
      Console.log("Skipping workspace trailblaze.yaml for max-llm-calls: ${e.message}")
      return null
    }
    val configFile = resolved.configFile ?: return null
    val rawValue = try {
      TrailblazeProjectConfigLoader.load(configFile)?.raw?.defaults?.maxLlmCalls
    } catch (e: Exception) {
      Console.log("Skipping workspace trailblaze.yaml for max-llm-calls: ${e.message}")
      return null
    }
    return parsePositiveIntOrWarn(rawValue, "${configFile.absolutePath} defaults.max-llm-calls")
  }

  private fun parsePositiveIntOrWarn(raw: String?, source: String): Int? {
    if (raw == null) return null
    val parsed = raw.toIntOrNull()
    if (parsed == null || parsed <= 0) {
      Console.error("Ignoring $source=\"$raw\" — expected a positive integer.")
      return null
    }
    return parsed
  }

  private fun parsePositiveIntOrWarn(raw: Int?, source: String): Int? {
    if (raw == null) return null
    if (raw <= 0) {
      Console.error("Ignoring $source=$raw — expected a positive integer.")
      return null
    }
    return raw
  }

  /**
   * Memoized result of [parseMemorySeeds] for `--memory KEY=VAL` entries — populated by
   * the early validation block at the top of [call] so the in-process and daemon
   * request-build sites can both consume it without re-parsing. Empty when [call] has
   * not yet validated this run (test-only path; production callers always go through
   * [call] first).
   */
  private var resolvedMemorySeeds: Map<String, String> = emptyMap()

  /** Sensitive counterpart of [resolvedMemorySeeds] for `--secret KEY=VAL` entries. */
  private var resolvedSensitiveSeeds: Map<String, String> = emptyMap()

  /**
   * Returns the resolved `--memory KEY=VAL` map for the in-flight [call]. Walks the raw
   * `memorySeeds` list as a fallback for test paths that construct a [TrailCommand]
   * outside `CommandLine` parsing — production callers always reach this through [call],
   * which populates [resolvedMemorySeeds] at validation time.
   *
   * Cache invariant: once [call] populates [resolvedMemorySeeds], later mutations of
   * [memorySeeds] (a test-only path) are NOT reflected by this accessor. Tests that need
   * to swap input lists must do so before [call] (or construct a fresh [TrailCommand]).
   * The check below short-circuits two equivalent "use the cache" cases — cache populated,
   * OR input empty (in which case the cache and a fresh parse would both be `emptyMap()`).
   */
  internal fun parsedMemorySeeds(): Map<String, String> =
    if (resolvedMemorySeeds.isNotEmpty() || memorySeeds.isEmpty()) {
      resolvedMemorySeeds
    } else {
      parseMemorySeeds(memorySeeds)
    }

  /** Sensitive counterpart of [parsedMemorySeeds] for `--secret KEY=VAL` entries. */
  internal fun parsedSensitiveSeeds(): Map<String, String> =
    if (resolvedSensitiveSeeds.isNotEmpty() || sensitiveSeeds.isEmpty()) {
      resolvedSensitiveSeeds
    } else {
      parseMemorySeeds(sensitiveSeeds)
    }

  /**
   * Returns `true` when the user typed the deprecated `trail` alias instead of the canonical
   * `run` name. Walks from the injected [commandSpec] up to the root [CommandLine] and reads
   * its [picocli.CommandLine.ParseResult.originalArgs] — the first non-option token there is
   * the subcommand name as typed (option flags on the parent are filtered out so a future
   * top-level switch wouldn't shadow the detection).
   *
   * Returns `false` defensively when picocli hasn't populated the parse result yet (e.g. in
   * unit tests that construct the command directly) so the production-only warning never
   * fires from test paths.
   *
   * Caveat: picocli short-circuits `mixinStandardHelpOptions` (`-h`/`--help`) and `--version`
   * before [call] runs, so `trailblaze trail --help` renders usage as `Usage: trailblaze run …`
   * but never reaches this helper — no deprecation warning fires on those paths. That's the
   * right behaviour for `--help` (users expect instant, side-effect-free help output) and
   * matches every other deprecated CLI surface in this repo.
   *
   * `internal` (not `private`) so `TrailCommandAliasWarningTest` can drive each input shape
   * directly after a real picocli parse — the in-process [call] path covers too much
   * surface (LLM resolution, memory parsing, daemon delegation) to be a clean test driver
   * for this one heuristic.
   *
   * **Intentionally hardcoded, not a reusable pattern.** The literal `"trail"` is the only
   * deprecated alias in the CLI today, and this is a single-release deprecation. Don't
   * generalize into `wasInvokedViaAlias(name: String)` until there's a second alias to
   * motivate the shape — premature generalization here would also pull the `@Spec(SELF)
   * commandSpec` field below into wherever the generic helper lives. Removal trigger:
   * same trigger that drops `aliases = ["trail"]`; see
   * `docs/internal/devlog/2026-05-26-cli-trail-to-run-rename.md`.
   */
  internal fun wasInvokedViaTrailAlias(): Boolean {
    // Tests that construct TrailCommand directly (instead of going through picocli's
    // CommandLine.execute) never populate the @Spec-injected `commandSpec` lateinit
    // var — touching it would throw UninitializedPropertyAccessException. Treat the
    // uninitialized case as "definitely not via the alias" so the early-warning block
    // in [call] stays safe to call from direct-construction tests.
    if (!::commandSpec.isInitialized) return false
    var root: CommandLine? = commandSpec.commandLine() ?: return false
    while (root?.parent != null) {
      root = root.parent
    }
    val args = root?.parseResult?.originalArgs() ?: return false
    val subcommand = args.firstOrNull { !it.startsWith("-") } ?: return false
    return subcommand == "trail"
  }

  /**
   * Three-way resolution for replay vs. AI-driven execution:
   *   `--use-recorded-steps`      → force replay (use the trail's `recording:` tools verbatim).
   *   `--no-use-recorded-steps`   → force AI (ignore recordings, drive every step via LLM).
   *   (unset, null)               → auto-detect: AI mode if no `recording:` blocks present, replay if they are.
   *
   * Use `--no-use-recorded-steps` to re-run a trail with stale selectors and let the agent
   * re-pick selectors from current page state (which writes a fresh enriched recording).
   */
  private fun resolveUseRecordedSteps(yamlContent: String): Boolean =
    useRecordedSteps ?: createTrailblazeYaml().hasRecordedSteps(yamlContent)

  /**
   * Emits an author-facing banner describing the run's effective replay-vs-AI mode.
   *
   * Authors who pass `--use-recorded-steps` for deterministic replay otherwise have no
   * signal that the flag actually engaged — silent LLM calls during a supposedly-
   * deterministic run are an expensive surprise. Three states:
   *
   *   1. Flag set + loaded YAML has `recording:` blocks → "Replay mode: ..." (info).
   *      Per-step `runRecordedPrompt` is invoked for steps that have a usable recording.
   *      Caveats acknowledged in the wording: `--self-heal` can fall back to the LLM
   *      when a recorded tool fails, and steps without a `recording:` block fall back to
   *      `runAiPrompt` even with the flag set.
   *
   *   2. Flag set + loaded YAML has no `recording:` blocks → loud `Console.error` warning.
   *      The flag has no effect because there's nothing to replay; every step will go
   *      through `runAiPrompt`. Common cause is the directory-expansion lookup picking
   *      `blaze.yaml` over a sibling `<device-classifiers>.trail.yaml` because the
   *      on-disk filename's classifier list doesn't match the `--device <platform>`
   *      request — surface that diagnosis directly so the user can re-point at the file.
   *
   *   3. Flag unset/off → silent. The default AI-driven path is what the help text
   *      describes; no banner needed for the unsurprising case.
   *
   * Called from both the daemon-delegated path ([runViaDaemon]) and the in-process loop
   * ([runInProcess]) so the message appears regardless of which route the run took.
   */
  private fun logReplayModeBanner(
    file: File,
    yamlContent: String,
    effectiveUseRecordedSteps: Boolean,
  ) {
    val yamlHasRecording: Boolean = createTrailblazeYaml().hasRecordedSteps(yamlContent)
    if (useRecordedSteps == true && !yamlHasRecording) {
      Console.error(
        "Warning: --use-recorded-steps was set but the loaded trail (${file.name}) has no " +
          "`recording:` blocks. Per-step LLM rounds will still fire. " +
          "Check that the recording file (e.g. `<platform>.trail.yaml`) lives in the same " +
          "directory and matches the device classifiers from --device.",
      )
    } else if (effectiveUseRecordedSteps && yamlHasRecording) {
      // Wording is intentionally conditional. Replay mode short-circuits the LLM for steps
      // with a usable recording, but: (a) steps without `recording:` blocks still call
      // `runAiPrompt`, and (b) `--self-heal` can hand off to the LLM when a recorded tool
      // fails. Calling out both so the banner doesn't over-promise determinism.
      Console.info(
        "Replay mode: using each step's `recording:` tools verbatim (no LLM round-trips " +
          "for those steps). Steps without `recording:` blocks still call the LLM, and " +
          "`--self-heal` can fall back to the LLM if a recorded tool fails. Pass " +
          "--no-use-recorded-steps to force AI mode for every step.",
      )
    }
  }

  companion object {
    /**
     * Parses `--memory KEY=VAL` (and `--secret KEY=VAL`) entries into a flat string
     * map. Split on the first `=` so values may contain `=` (e.g.
     * `--memory token=abc=def`). Requires a non-empty key — `--memory =foo` or
     * `--memory bar` (no `=`) throws so a typo fails fast at CLI parse rather than
     * silently dropping a seed. On repeated keys, later wins.
     *
     * Intentionally flat-strings-only — distinct from [KeyValueParser], which models
     * dot-notation nesting, JSON value inference, and indexed lists for general
     * structured CLI input. Memory seeds are always `Map<String, String>` end-to-end
     * (interpolation surface, scripting `ctx.memory.get(name)`, log persistence), so
     * fattening this parser with type inference would let a typed value silently land
     * in a downstream consumer that's typed as `String`.
     */
    internal fun parseMemorySeeds(raw: List<String>): Map<String, String> {
      val out = LinkedHashMap<String, String>()
      for (entry in raw) {
        val eq = entry.indexOf('=')
        require(eq > 0) {
          "Invalid --memory entry \"$entry\" — expected KEY=VAL with a non-empty KEY."
        }
        val key = entry.substring(0, eq)
        val value = entry.substring(eq + 1)
        out[key] = value
      }
      return out
    }

    /**
     * Derives a meaningful test name from a trail file.
     *
     * For the standard subdirectory layout (e.g., `test-counter/trail.yaml` or
     * `test-counter/blaze.yaml`), uses the parent directory name. Otherwise falls
     * back to the filename without extension.
     */
    fun deriveTestName(file: File): String {
      val baseName = file.nameWithoutExtension
      return if (baseName == "trail" || baseName == "recording.trail" || baseName == "blaze") {
        file.absoluteFile.parentFile?.name ?: baseName
      } else {
        baseName
      }
    }

    /**
     * Reads [file], resolves `{{var}}` template placeholders via [TrailYamlTemplateResolver],
     * and returns the parsed [TrailConfig] — or null if the file can't be read, the templates
     * can't be resolved, or the resolved YAML can't be decoded. Use this anywhere a pre-pass
     * needs to inspect config metadata before the runner takes over: the runner itself parses
     * the same resolved YAML, so pre-pass decisions stay consistent with what actually executes.
     *
     * Without template resolution, a placeholder that breaks raw-YAML syntax (e.g., an unquoted
     * `{{VAR}}` that the parser reads as a flow mapping) would fail to decode here while
     * succeeding at run time — silently bypassing any pre-pass gate (skip detection, tag filter)
     * the caller depends on.
     */
    fun readResolvedTrailConfig(
      file: File,
      deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    ): TrailConfig? = try {
      val rawYaml = file.readText()
      val resolvedYaml = TrailYamlTemplateResolver.resolve(rawYaml, file)
      // Pass the device's classifiers so a unified trail's per-classifier `devices:`/`skip:` pins
      // resolve for the device under test (empty list → device-agnostic: any-classifier skip fires).
      createTrailblazeYaml().extractTrailConfig(resolvedYaml, deviceClassifiers)
    } catch (_: Exception) {
      null
    }

    /**
     * Returns the trimmed `skip:` reason from the trail's `config:` block, or null if the trail
     * is not marked skipped. A blank reason (`skip: ""`) is treated as not-skipped so an empty
     * value doesn't silently disable a trail. Read-or-parse failures return null and defer error
     * reporting to the runner, which produces a clearer "failed to decode" message in context.
     */
    fun readSkipReason(file: File): String? =
      readResolvedTrailConfig(file)?.skip?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Expands directory arguments to their contained trail files (`*.trail.yaml` + `blaze.yaml`)
     * recursively. Plain file arguments pass through unchanged.
     *
     * **One pick per distinct trail, not per directory.** A directory can hold either
     * (a) several DISTINCT scenarios, each a self-contained unified `<scenario>.trail.yaml`
     * carrying its own `config.id` (the canonical named-file layout — e.g. `catalog/` holding
     * `conditional-item.trail.yaml`, `multiple-items.trail.yaml`, `overlay-tap.trail.yaml`), or
     * (b) the classifier variants of a SINGLE trail — a `blaze.yaml` (NL source of truth)
     * plus one or more platform-specific recordings (`<classifiers>.trail.yaml`) that share one
     * identity. Case (a) must expand to EVERY scenario; case (b) must collapse to one pick
     * (returning every file would double-bill — the NL file gets an AI run, then the recording
     * gets a deterministic replay).
     *
     * We distinguish the two per file by content (see [isDistinctNamedScenario]): a file whose
     * content is unified-format — and isn't the bare `trail.yaml` — is a self-contained
     * named trail, and every one expands. Everything else — the bare `trail.yaml`, NL definitions,
     * and the v1 `<classifier>.trail.yaml` recordings — collapses into one legacy bucket for the
     * directory, from which [resolveSingleTrail] delegates to
     * [TrailRecordings.findBestTrailResourcePath] — the same resolver
     * `BaseHostTrailblazeTest.runFromResource` and the eval harnesses use — so the
     * classifier-priority order (e.g. `web.trail.yaml` &gt; `blaze.yaml` when `--device web`) is
     * picked exactly once. Nested directories are still walked, so a workspace `trails/` directory
     * expands to one file per distinct trail, never 2·N.
     *
     * [deviceClassifiers] threads through from the resolved `--device` so the resolver picks
     * a classifier-matched recording when one exists. Production callers use
     * [DeviceClassifierResolver.resolveFromSpec] to enrich the list with a phone-vs-tablet
     * category by probing `xcrun simctl` / `adb`, so `--device ios/<UDID>` against an iPhone
     * sim yields `[ios, iphone]` and picks `ios-iphone.trail.yaml` over a platform-level
     * `ios.trail.yaml`. Tests that call this function directly typically pass the pure-parse
     * result from [parseDeviceClassifiersFromSpec] (platform-only) and pin the platform-level
     * fallback contract.
     *
     * When [deviceClassifiers] is empty (no `--device`, or a device spec we can't parse), the
     * resolver only matches `blaze.yaml` / `trailblaze.yaml`. If the legacy bucket has *only*
     * platform-specific recordings in that case, we fall back to the first alphabetically and
     * emit a one-line warning — naming the recordings that were skipped if there's more than
     * one, or noting that the lone recording is being run regardless of device classifiers if
     * there's only one. Pass `--device` to pick the right one. Named-scenario groups (case (a))
     * never warn: a unified file resolves its own per-classifier recording internally, and its
     * `<scenario>.trail.yaml` name is not classifier-derived, so the resolver legitimately finds
     * no classifier match and we just run the file.
     *
     * The walk itself is [TrailDiscovery.discoverTrailFiles] — the same discovery the desktop
     * scanner, MCP trail listing, and `trailblaze check` use — so directory expansion inherits
     * its exclude set ([TrailDiscovery.DEFAULT_EXCLUDED_DIRS]: `build/`, `.gradle/`, `.git/`,
     * `node_modules/`, `.trailblaze/`, `.claude/`) and its no-follow symlink policy. Two
     * consequences worth calling out: a trail sitting under an excluded directory (a stale
     * copy in `build/`, a sibling agent's WIP in `.claude/worktrees/`) is not expanded, and
     * an in-tree symlinked directory is not traversed (intentional — see the [TrailDiscovery]
     * KDoc). A directory the user names *explicitly* is always scanned, whatever it's called:
     * the exclude set only prunes subdirectories, and a symlinked argument is canonicalized
     * before the walk so it still resolves — with results mapped back into the argument's
     * own path frame, so de-duplication against overlapping file arguments and reported
     * paths stay in the frame the user typed.
     *
     * Results are sorted by absolute path for stable run order and de-duplicated so passing
     * both a directory and a file under it (or two overlapping globs) doesn't run the same
     * trail twice.
     */
    fun expandTrailFiles(
      files: List<File>,
      deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    ): List<File> {
      val expanded = mutableListOf<File>()
      for (arg in files) {
        if (arg.isDirectory) {
          // [TrailDiscovery]'s walk refuses to descend through a symlinked *root* (its
          // no-follow policy reads the root's own attributes too). `arg.isDirectory` above
          // follows links, so an explicitly-passed symlinked directory lands here —
          // canonicalize it for the walk; only in-tree symlinks stay untraversed. If both
          // canonicalization attempts fail (a race with deletion, permission revocation),
          // warn instead of silently expanding to nothing — the un-canonicalized root
          // yields an empty walk under TrailDiscovery's symlinked-root policy.
          val argPath = arg.toPath()
          val walkRoot = if (Files.isSymbolicLink(argPath)) {
            runCatching { argPath.toRealPath() }
              .recoverCatching { arg.canonicalFile.toPath() }
              .getOrElse {
                Console.error(
                  "Warning: cannot resolve symlinked directory ${arg.path}; trail discovery " +
                    "does not follow symlinked roots, so no trails will be expanded from it.",
                )
                argPath
              }
          } else {
            argPath
          }
          val discovered = TrailDiscovery.discoverTrailFiles(walkRoot)
          // Map results back into the argument's own path frame (`trails-link/foo` instead
          // of the resolved real path) so the final `distinctBy { it.absoluteFile }` still
          // collapses an overlapping file argument passed through the same symlink, and
          // reported paths stay consistent with what the user typed.
          val inArgFrame = if (walkRoot == argPath) {
            discovered
          } else {
            discovered.map { argPath.resolve(walkRoot.relativize(it.toPath())).toFile() }
          }
          val byParent: Map<File, List<File>> = inArgFrame
            // [TrailDiscovery] matches `trailblaze.yaml` because that name is also used as
            // an alias for `blaze.yaml` in the NL-definition list, and its own anchor rule
            // only excludes the single workspace config resolved via `findWorkspaceRoot`.
            // The CLI is stricter: a `trailblaze.yaml` under ANY `config/` directory is
            // treated as workspace config, never a runnable trail — scratch directories
            // (no `trails/config/` anchor) hold config files too. Other `trailblaze.yaml`
            // files (e.g. NL definitions outside a `config/` directory) pass through
            // unchanged.
            .filterNot { it.parentFile?.name == "config" && it.name == "trailblaze.yaml" }
            // `parentFile` is non-null here: the walk root is a directory, and discovery
            // only emits regular files strictly below it, so every entry has a directory
            // parent.
            .groupBy { it.parentFile.absoluteFile }
          val sortedDirs = byParent.keys.sortedBy { it.absolutePath }
          for (dir in sortedDirs) {
            // Split the directory's files into distinct named scenarios vs the legacy
            // single-trail bucket (see [isDistinctNamedScenario]). Named scenarios each expand;
            // the legacy bucket — NL definitions, the unified `trail.yaml`, and classifier-variant
            // recordings of ONE trail — collapses to a single classifier-priority pick.
            val (namedScenarioFiles, legacyFiles) = byParent.getValue(dir)
              .partition { isDistinctNamedScenario(it) }
            // Each named scenario is a self-contained trail; run every one (sorted for stable
            // order). No classifier resolution or device-mismatch warning: a unified file lowers
            // its own per-classifier recording at run time, and its name is not classifier-derived.
            namedScenarioFiles.sortedBy { it.absolutePath }.forEach { expanded += it }
            if (legacyFiles.isNotEmpty()) {
              resolveSingleTrail(dir, legacyFiles, deviceClassifiers)?.let { expanded += it }
            }
          }
        } else {
          expanded += arg
        }
      }
      return expanded.distinctBy { it.absoluteFile }
    }

    /**
     * True when [file] is a **named unified trail**: its content is unified-format
     * ([TrailRecordings.isUnifiedTrailContent] — the shared discriminator the requireRecordings
     * gate and the coverage scripts also use) AND its name is not the bare
     * [TrailRecordings.UNIFIED_TRAIL_FILENAME] (`trail.yaml`). Several such files can share one
     * directory — the sample-app `catalog/` holds `conditional-item.trail.yaml`,
     * `multiple-items.trail.yaml`, …; the eval suite holds `tenKey.trail.yaml`, `openUrl.trail.yaml`,
     * … — and each is its own runnable trail, so directory expansion must yield every one.
     *
     * Everything else is a single-trail representative that collapses into the directory's legacy
     * bucket: the bare `trail.yaml` (a directory's canonical unified trail), NL definitions
     * (`blaze.yaml` / `trailblaze.yaml`), and the v1 `<classifier>.trail.yaml` recordings the legacy
     * save path emits — v1 documents are root-level *lists* and never carry the column-0 `trail:`
     * key, so the two shapes never collide. Detecting by content rather than by filename or
     * `config.id` is deliberate — it expands named unified trails that carry no id (the eval suite)
     * that a filename/id heuristic would miss, and never splits a trail's v1 classifier variants
     * apart even when they were stamped with mutually inconsistent ids.
     *
     * The line scan needs no `{{var}}` template resolution (the `trail:` key is structural, not a
     * templated value), so a unified file whose unquoted template is invalid as raw YAML still
     * expands. A file that scans as unified but fails to *decode* also expands on its own — the
     * runner then surfaces the decode error against that file, rather than the expander silently
     * folding it into the legacy bucket. Unreadable files fall through to the legacy bucket.
     */
    private fun isDistinctNamedScenario(file: File): Boolean {
      // NL definitions are guarded by name as well as the bare `trail.yaml`: both are
      // single-trail representatives whatever their content, so neither may bypass the
      // legacy bucket's classifier resolution (an NL file that somehow carried a root
      // `trail:` key would otherwise expand AND remain the bucket's NL fallback —
      // double-billing the trail).
      if (TrailRecordings.isUnifiedTrailFile(file.name)) return false
      if (TrailRecordings.isNlDefinitionFile(file.name)) return false
      return runCatching { TrailRecordings.isUnifiedTrailContent(file.readText()) }
        .getOrDefault(false)
    }

    /**
     * Resolves ONE runnable trail file for the legacy single-trail bucket of a directory — the
     * classifier-variant recordings plus any NL definition (`blaze.yaml` / `trailblaze.yaml`) /
     * unified `trail.yaml` that all represent the same trail. Named scenarios are handled directly
     * in [expandTrailFiles] and never reach here.
     *
     * Delegates to [TrailRecordings.findBestTrailResourcePath] (classifier-priority order), with
     * [doesResourceExist] gated on the bucket's own filenames. Without that gate, a `config/`
     * directory holding both the workspace config (`trailblaze.yaml`) and a sibling recording
     * would let the resolver probe the pre-filtered-out `trailblaze.yaml` on disk and resurrect
     * it — so constraining the probe to files that survived the walk keeps the exclusion
     * authoritative even if the resolver's candidate list widens in the future.
     *
     * When the resolver returns an NL fallback despite a `<platform>-*.trail.yaml` being present,
     * we prefer the platform-prefixed recording — a workaround for the CLI's single-classifier
     * limitation (see [parseDeviceClassifiersFromSpec]). The host driver saves recordings using
     * the device's *full* classifier list (an iPhone run writes `ios-iphone.trail.yaml`, not
     * `ios.trail.yaml`), but the CLI computes `[ios]` at plan time, so the resolver's candidate
     * list (`[ios.trail.yaml, trail.yaml, blaze.yaml, …]`) can't match the multi-segment name.
     * "Most specific" is approximated by longest filename (more hyphenated classifier segments),
     * tie-broken alphabetically so multiple same-width captures in one dir pick deterministically.
     *
     * If nothing matches, we still return the alphabetically-first file so the CLI surface stays
     * usable, and warn on stderr that the requested `--device` was ignored (a recording for a
     * different device is running).
     */
    private fun resolveSingleTrail(
      dir: File,
      bucketFiles: List<File>,
      deviceClassifiers: List<TrailblazeDeviceClassifier>,
    ): File? {
      if (bucketFiles.isEmpty()) return null
      val allowedNames = bucketFiles.map { it.name }.toSet()
      val picked = TrailRecordings.findBestTrailResourcePath(
        path = dir.absolutePath,
        deviceClassifiers = deviceClassifiers,
        doesResourceExist = { File(it).name in allowedNames && File(it).exists() },
      )
      val finalPicked: String? = if (
        picked != null &&
        deviceClassifiers.isNotEmpty() &&
        TrailRecordings.isNlDefinitionFile(File(picked).name)
      ) {
        val platformPrefix = "${deviceClassifiers.first().classifier}-"
        val platformRecording = bucketFiles
          .asSequence()
          .filter { TrailRecordings.isRecordingFile(it.name) }
          .filter { it.name.startsWith(platformPrefix) }
          .sortedWith(compareByDescending<File> { it.name.length }.thenBy { it.name })
          .firstOrNull()
        platformRecording?.absolutePath ?: picked
      } else {
        picked
      }
      if (finalPicked != null) return File(finalPicked)

      // No NL definition and no classifier-matched recording in this bucket. Run the
      // alphabetically-first file (all share this parent dir, so absolute-path order equals
      // name order) so the CLI surface stays usable, and warn that the requested device was
      // silently ignored. Route via [Console.error] (stderr) so the warning survives
      // `--quiet`/JSON modes, matching the device-resolution error envelopes elsewhere in this
      // file. Phrase the hint as a "platform-prefixed `--device`" so a user who passed
      // `--device <instance-id>` (no platform prefix) understands the platform segment is what
      // disambiguates here.
      val candidates = bucketFiles.sortedBy { it.absolutePath }
      val hint = "Pass a platform-prefixed `--device <platform>` (e.g. `--device android`) " +
        "to pick the right one."
      if (candidates.size > 1) {
        Console.error(
          "Warning: ${dir.absolutePath} contains multiple recordings and no `blaze.yaml`; " +
            "running ${candidates.first().name} and skipping " +
            "${candidates.drop(1).joinToString(", ") { it.name }}. $hint",
        )
      } else {
        Console.error(
          "Warning: ${dir.absolutePath} contains only ${candidates.first().name} and no " +
            "`blaze.yaml`; running it regardless of the requested device classifiers. $hint",
        )
      }
      return candidates.first()
    }

    /**
     * Pure-parse `--device` → platform-only classifier list. Used by tests that exercise
     * [expandTrailFiles] directly without spinning up an actual device. Production code
     * uses [DeviceClassifierResolver.resolveFromSpec] instead, which probes the OS
     * (`xcrun simctl` / `adb`) to enrich the result with a phone-vs-tablet category
     * classifier so [findBestTrailResourcePath] can pick a `<platform>-<category>.trail.yaml`
     * recording — matching what the runtime save path writes.
     */
    internal fun parseDeviceClassifiersFromSpec(
      deviceSpec: String?,
    ): List<TrailblazeDeviceClassifier> {
      val platform = deviceSpec?.let { TrailblazeDevicePlatform.fromString(it) } ?: return emptyList()
      return listOf(platform.asTrailblazeDeviceClassifier())
    }

    /**
     * Builds the per-run [TrailExecutionPlan] from raw CLI arguments. Each input path is expanded
     * (directories → recursive trail-file walk), then parsed once to extract `tags:` and `skip:`.
     * `--tags` (OR include across the supplied names) is applied first, then `skip:` markers are
     * classified as [TrailExecutionItem.Skip]. Skip is intentionally not overridable from the
     * CLI — to run a skipped trail, remove its `skip:` field. This matches the JUnit / pytest /
     * JS convention where re-enabling a disabled test is a source edit.
     *
     * Parse failures are surfaced to the runner downstream, not here. A trail whose config can't
     * be decoded is treated as having no tags and no skip marker, so it flows into the `Run`
     * bucket; the actual decode error then surfaces with full context when the runner attempts
     * to execute it. This keeps the planner from masking the runner's clearer error message.
     */
    internal fun planTrailExecution(
      files: List<File>,
      includeTags: List<String>,
      deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
      configClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    ): TrailExecutionPlan {
      val expanded = expandTrailFiles(files, deviceClassifiers)
      // [configClassifiers] resolves per-classifier `skip:`/`tags:`; it's the device classifiers,
      // or — for a `--driver`-only run with no `--device` — the driver's platform classifier
      // (see [DeviceClassifierResolver.fromDriver]). Without it, `resolveSkip` would take the
      // device-agnostic any-skip fallback and an `android`-only `skip:` would wrongly halt a run
      // forced to `--driver=IOS_HOST`. It's a SEPARATE param from [deviceClassifiers] (which feeds
      // [expandTrailFiles] recording-file selection) on purpose: skip/tags gain the driver
      // fallback while recording selection stays device-only. Empty [configClassifiers] falls back
      // to [deviceClassifiers] so direct callers (e.g. tests) that pass only the device list are
      // unchanged.
      val skipTagClassifiers = configClassifiers.ifEmpty { deviceClassifiers }
      val items = mutableListOf<TrailExecutionItem>()
      var filteredOutByTag = 0
      for (file in expanded) {
        // Resolve templates before reading metadata so a `{{var}}` in the `config:` block
        // doesn't trip up the planner while the runtime would have substituted it cleanly.
        // See [readResolvedTrailConfig] for the consistency rationale.
        val config = readResolvedTrailConfig(file, skipTagClassifiers)
        val tags = config?.tags.orEmpty()

        if (includeTags.isNotEmpty() && tags.none { it in includeTags }) {
          filteredOutByTag++
          continue
        }

        val skipReason = config?.skip?.trim()?.takeIf { it.isNotEmpty() }
        items += if (skipReason != null) {
          TrailExecutionItem.Skip(file, skipReason)
        } else {
          TrailExecutionItem.Run(file)
        }
      }
      return TrailExecutionPlan(items = items, filteredOutByTag = filteredOutByTag)
    }
  }
}

/**
 * One entry in a planned trail run — either an executable trail or a deferred-skipped one with
 * the reason ready to print. Built up front by [TrailCommand.planTrailExecution] so the per-file
 * loop is a single iteration with no further parsing or filter checks.
 */
internal sealed class TrailExecutionItem {
  abstract val file: File
  data class Run(override val file: File) : TrailExecutionItem()
  data class Skip(override val file: File, val reason: String) : TrailExecutionItem()
}

/**
 * Result of [TrailCommand.planTrailExecution]. [items] is the in-order run plan (both runnables
 * and skipped, since skipped trails still produce a per-line console entry). [filteredOutByTag]
 * is the count of trails removed by `--tags` — surfaced as a one-line CI note
 * so a missing trail in the summary is explained rather than mysterious.
 */
internal data class TrailExecutionPlan(
  val items: List<TrailExecutionItem>,
  val filteredOutByTag: Int,
)

