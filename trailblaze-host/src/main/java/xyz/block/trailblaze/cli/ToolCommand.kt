package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.project.WorkspaceRoot
import xyz.block.trailblaze.config.project.findWorkspaceRoot
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

/**
 * Invoke one or more tools by name with key=value arguments.
 *
 * Examples:
 *   trailblaze tool -d android/emulator-5554 tap ref=p386 -s "Tap the Sign In button"
 *   trailblaze tool -d ios/SIM-UUID inputText text="hello" -s "Type hello"
 *   trailblaze tool -d android tap --yaml "- tap:\n    ref: p386" -s "Tap sign in"
 */
@Command(
  name = "tool",
  mixinStandardHelpOptions = true,
  description = ["Run a Trailblaze tool by name (e.g., tap, inputText)"],
)
class ToolCommand : Callable<Int> {

  @Parameters(
    index = "0",
    description = ["Tool name (e.g., web_click, tap)"],
    arity = "0..1",
  )
  var toolName: String? = null

  @Parameters(
    index = "1..*",
    description = ["Tool arguments as key=value pairs (e.g., ref=\"Sign In\")"],
    arity = "0..*",
  )
  var argPairs: List<String> = emptyList()

  @Option(
    names = ["-s", "--step", "--objective", "-o"],
    description = [
      "Natural language step — describe what, not how.",
      "If the UI changes, Trailblaze uses this to retry the step with AI.",
      "'Navigate to Settings' survives a redesign; 'tap button at 200,400' does not.",
      "Optional by default; required when `trailblaze config require-steps true` is set.",
      "(`--objective` / `-o` are deprecated aliases of `--step` / `-s`.)",
    ],
  )
  var step: String? = null

  @Option(
    names = ["--yaml"],
    description = ["Raw YAML tool sequence (multiple tools in one call)"],
  )
  var yaml: String? = null

  @Option(
    names = ["-d", "--device"],
    description = [DEVICE_OPTION_DESCRIPTION],
  )
  var device: String? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"],
  )
  var verbose: Boolean = false

  @Option(
    names = ["--no-screenshots", "--text-only"],
    description = [
      "Skip screenshots — the LLM only sees the textual view hierarchy, no vision " +
        "tokens, and disk logging of screenshots is skipped too. Faster and cheaper " +
        "for short objectives where the visual layout doesn't matter; some tasks need " +
        "vision and will degrade without it."
    ],
  )
  var noScreenshots: Boolean = false

  @Option(
    names = ["--target"],
    description = [TARGET_OPTION_DESCRIPTION],
  )
  var target: String? = null

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    // Single source of truth for "did the user supply a --yaml body" — both the
    // fast-path gate and the downstream YAML builder consult this. Using one
    // expression at the top of `call()` prevents the two sites from drifting
    // (one staying `isNullOrBlank()`, the other becoming e.g. `!= null`) and
    // emitting subtly different rejection verdicts.
    val yamlIsProvided = !yaml.isNullOrBlank()

    // Local argument validation runs BEFORE the device wrapper so a syntactically
    // invalid `trailblaze tool` (no toolName, no --yaml) doesn't trigger daemon
    // autostart / device autodetect / session-state creation before reporting
    // the actual misuse. Also runs before require-steps enforcement so the
    // primary error a user sees on a bare `trailblaze tool` is "missing tool"
    // not "missing -s/--step" — the latter is correct but useless if you also
    // haven't said what tool to run.
    // `!yamlIsProvided` so `trailblaze tool --yaml ""` (or all-whitespace)
    // gets the same MISUSE as missing-yaml — a blank yaml string would otherwise
    // pass this gate, then build `toolsYaml = ""` and ship an empty `tools` arg
    // to the daemon, which is downstream noise rather than a useful invocation.
    if (toolName == null && !yamlIsProvided) {
      Console.error("Error: Either a tool name or --yaml must be provided.")
      return TrailblazeExitCode.MISUSE.code
    }
    // Pre-parse key=value pairs here too so a typo like `tap ref` (missing `=`)
    // surfaces before device binding for the same reason — it's a static misuse
    // we can detect from the parsed flags alone.
    val parsedToolArgs: Map<String, Any>? = if (!yamlIsProvided && toolName != null) {
      try {
        KeyValueParser.parse(argPairs)
      } catch (e: IllegalArgumentException) {
        Console.error("Error: ${e.message}")
        return TrailblazeExitCode.MISUSE.code
      }
    } else {
      null
    }
    // Fast-path pre-validation for an unmistakable typo. When the user passes a bare
    // tool name (not `--yaml`), check it against the locally-discovered tool registry
    // BEFORE paying the ~30s daemon round-trip on `client.callTool("step", ...)`. The
    // round-trip dominates because `cliReusableWithDevice` binds a device session and
    // the daemon walks its per-platform tool registry before emitting "Unknown tool",
    // which is a real OOBE drag on a typo like `tap_on_text` vs `tap`.
    //
    // Source of truth: [ToolNameResolver.fromBuiltInAndCustomTools] — the same union
    // every other name-resolving call site (toolset YAML loader, koog tool registry,
    // YAML decoder) uses. A name unknown to this resolver is also unknown to the
    // daemon's classpath catalog. Names that ARE locally known still go through the
    // daemon — it stays the source of truth for platform/target-specific filtering
    // (e.g. `openUrl` is in the registry but not valid on a Playwright web device).
    // This short-circuit only catches names that no toolset on the classpath knows
    // about; the daemon-side gates (unknown-tool, not-valid-for-device/target) keep
    // running for everything that passes.
    //
    // Ordered AFTER the missing-tool / KV-parser pre-flights so the most fundamental
    // misuse (no tool at all, malformed args) wins, and BEFORE require-steps so a
    // typo'd tool surfaces as "Unknown tool" rather than the less actionable
    // "missing -s/--step" when both apply.
    val typedToolName = toolName
    if (typedToolName != null && !yamlIsProvided && !LocalToolNameRegistry.isKnown(typedToolName)) {
      emitUnknownToolEnvelope(typedToolName, source = "fast-path")
      return TrailblazeExitCode.MISUSE.code
    }
    // `trim()` on the step so whitespace-only input (`-s "   "`) is treated as
    // empty — the recorded step would be useless either way, and require-steps
    // should reject it consistently rather than counting whitespace as a real
    // description.
    val effectiveStep = step?.trim().orEmpty()
    requireStepIfConfigured(effectiveStep, verb = "tool")?.let { return it }
    return cliReusableWithDevice(
      verbose = verbose,
      device = device,
      webHeadless = headlessOption.resolve(),
      target = target,
      verb = "Tool",
    ) { client ->
      // `yamlIsProvided` mirrors the top-of-call gate so a blank `--yaml` value
      // falls through to the `tap`-style toolName builder and the two input
      // paths agree on what counts as "yaml was provided."
      val toolsYaml = if (yamlIsProvided) yaml!! else ToolYamlBuilder.build(toolName!!, parsedToolArgs!!)
      // Wire key stays `"objective"` even though the user-facing flag is now `-s`/`--step`
      // and the wire tool is now named `"step"`. The daemon's `step` MCP tool defines
      // `objective` as the input contract — renaming the wire field would break recorded
      // session compatibility for users mid-upgrade. A future protocol bump will pick up
      // the field rename in lockstep with the session-replay surface.
      val arguments = mutableMapOf<String, Any?>("objective" to effectiveStep, "tools" to toolsYaml)
      if (noScreenshots) arguments["fast"] = true
      val result = client.callTool("step", arguments)
      // Enhance "Unknown tool" / "not valid for the current device/target" errors with
      // CLI-specific guidance. Both surface as plain text in result.content; matching on the
      // marker phrases keeps us decoupled from the rest of the markdown formatting.
      //
      // Exit code: MISUSE (3) — bad input. Per [TrailblazeExitCode] policy, unknown tool
      // names and tools-not-valid-for-this-platform are user mistakes (wrong argv), not
      // infrastructure failures. Previously this returned INFRA_FAILED (2), which conflated
      // "user typo" with "daemon unreachable" / "device gone" and made it impossible for
      // a script to tell whether to retry or surface the input mistake to its caller.
      //
      // The Tip and stripped-prefix output go to stderr (Console.error). Anything on stdout
      // here would be observed by callers piping the tool output into another command, and
      // we don't want a friendly tip to pollute their stream — they already get the loud
      // structured error and the exit code carries the verdict.
      // [isMisuseResult] gates the marker match on an error status: a SUCCESSFUL read/shell
      // tool now returns its real payload here, and a payload that merely contains a marker
      // phrase (e.g. command output mentioning "Unknown tool") must print normally, not exit 3.
      if (isMisuseResult(result.content)) {
        Console.error(result.content.replace(Regex("\\*\\*.*?\\*\\*\\s*—\\s*"), ""))
        emitToolboxTip()
        return@cliReusableWithDevice TrailblazeExitCode.MISUSE.code
      }
      formatBlazeResultAgent(result)
      blazeExitCode(result)
    }
  }

  /**
   * Emit the two-line unknown-tool envelope (primary `Unknown tool: <name>…` line +
   * actionable `Tip: Run 'trailblaze toolbox …'` line) used by both the fast-path
   * and the daemon-result post-processing block when the name is known-bad. The
   * envelope is identical at both sites by design — the user is meant to be unable
   * to tell which layer rejected them (constraint #2 in the original spec) — so a
   * shared helper keeps the two sites in lockstep against drift, same reason
   * [MISUSE_MARKERS] is a single constant.
   *
   * [source] is a debug breadcrumb ("fast-path" or "daemon") logged via
   * [Console.log] (verbose-only, doesn't reach stderr); it's *not* a user-facing
   * distinction. Workspace-tool authors hitting the documented false-negative
   * trade-off use this to verify which layer rejected them.
   */
  private fun emitUnknownToolEnvelope(name: String, source: String) {
    Console.log("[ToolCommand] $source rejected '$name' (not in local registry)")
    Console.error("Unknown tool: $name. Use toolbox() to see available tools.")
    emitToolboxTip()
  }

  private fun emitToolboxTip() {
    Console.error("Tip: Run 'trailblaze toolbox --device <platform> --target <target>' to see what's available.")
  }

  /**
   * Lazily-built snapshot of every tool name discoverable from the local classpath —
   * class-backed (`@TrailblazeToolClass`, declared via `.tool.yaml`) plus YAML-defined
   * (`tools:` mode). Built once per JVM via [ToolNameResolver.fromBuiltInAndCustomTools]
   * so repeated `trailblaze tool …` invocations within the same process (e.g. agent or
   * test runner contexts) pay the classpath scan only once.
   *
   * **Workspace-tool guard.** This snapshot covers the classpath only — workspace-
   * discovered tools (overlaid into the daemon's registry by `AppTargetDiscovery.discover()`
   * via `registerWorkspaceToolSets` / `registerWorkspaceYamlTools`) are NOT visible
   * in the CLI process. A workspace-defined tool that isn't also on the classpath
   * would, without containment, slip past the local check and get rejected with the
   * same `Unknown tool: …` envelope the daemon would have emitted for a real typo —
   * silently breaking the documented `trailblaze tool myapp_login …` workspace-tool
   * flow (see `docs/scripted_tools.md`).
   *
   * To prevent that, [isKnown] consults [workspaceHasToolDefinitions] once at first
   * use: if the user's CWD is inside a Configured workspace AND that workspace ships
   * any `.tool.yaml` or `.toolset.yaml` under `trails/config/`, the fast-path is
   * disabled and every name appears "known" so dispatch falls through to the daemon
   * (which IS workspace-aware). OOBE users — typing from outside any workspace —
   * still get the <1s typo response.
   *
   * **Failure mode.** [ToolNameResolver]'s `init` block throws on a class-backed /
   * YAML-defined name collision. Without containment that exception would be cached
   * by `by lazy` and re-thrown on every subsequent `tool` invocation in the same
   * JVM — preempting cleaner pre-flight errors (no tool name, malformed args). The
   * `runCatching` wraps that init so a resolver-construction failure degrades to
   * "treat every name as known" (i.e. the fast-path is silently disabled), letting
   * the daemon take over as it would have done pre-PR. The failure is logged once
   * via `Console.log` so operators can grep `[ToolCommand]` for the breakdown
   * without the CLI hard-failing.
   */
  private object LocalToolNameRegistry {
    private val resolver: ToolNameResolver? by lazy {
      runCatching { ToolNameResolver.fromBuiltInAndCustomTools() }
        .onFailure {
          Console.log(
            "[ToolCommand] Local tool registry init failed; fast-path disabled, " +
              "falling through to daemon: ${it::class.simpleName}: ${it.message}",
          )
        }
        .getOrNull()
    }

    private val workspaceMayHaveCustomTools: Boolean by lazy {
      val cwd = Paths.get(System.getProperty("user.dir") ?: ".")
      val present = workspaceHasToolDefinitions(cwd)
      if (present) {
        Console.log("[ToolCommand] Workspace tool/toolset YAML detected near $cwd; fast-path disabled")
      }
      present
    }

    /**
     * True if [name] is known on the classpath, or if the fast-path is disabled — either
     * because the registry failed to init (resolver `null`) or because the user's
     * workspace ships its own tool/toolset YAML that the daemon overlays but the CLI
     * can't see. Both fallbacks defer to the daemon, which is the workspace-aware
     * authority.
     */
    fun isKnown(name: String): Boolean {
      if (workspaceMayHaveCustomTools) return true
      return resolver?.isKnown(name) ?: true
    }
  }
}

/**
 * Returns true if a Configured workspace ([WorkspaceRoot.Configured], discovered by
 * walking up from [startPath]) ships any `.tool.yaml` or `.toolset.yaml` file under
 * `trails/config/trailmaps/`. Top-level `internal` so [ToolCommandTest] can drive the
 * check against a [java.nio.file.Files]-backed fixture workspace without touching the
 * JVM-global `user.dir`.
 *
 * The walk is bounded to depth 4 under `trailmaps/`, which covers
 * `trailmaps/<id>/{tools,toolsets}/<file>.<kind>.yaml` and one extra nesting level for
 * organizational subdirectories. On filesystem-level failure (permission denied,
 * symlink loop), returns `true` — the conservative answer is "defer to daemon" so a
 * detection blip can't strand a workspace user with a false-rejection envelope.
 *
 * Pure-ish: only reads the filesystem. Safe to cache via `by lazy` since CWD doesn't
 * change during a single CLI invocation.
 */
internal fun workspaceHasToolDefinitions(startPath: Path): Boolean = runCatching {
  val ws = findWorkspaceRoot(startPath)
  if (ws !is WorkspaceRoot.Configured) return@runCatching false
  val configDir = ws.dir.resolve(TrailblazeConfigPaths.WORKSPACE_CONFIG_SUBDIR)
  if (!Files.isDirectory(configDir)) return@runCatching false
  // The only authoring layout: `trailmaps/<id>/{tools,toolsets}/<file>.<kind>.yaml`.
  // Walked at depth 4 to catch `trailmaps/<id>/tools/<file>` plus one extra level of
  // organizational nesting, without descending into transitively-unbounded subtrees.
  hasToolFileUnder(configDir.resolve("trailmaps"), maxDepth = 4)
}.onFailure {
  Console.log("[ToolCommand] workspace tool-presence check failed: ${it::class.simpleName}: ${it.message}")
}.getOrDefault(true)

private fun hasToolFileUnder(dir: Path, maxDepth: Int): Boolean {
  if (!Files.isDirectory(dir)) return false
  return Files.walk(dir, maxDepth).use { stream ->
    stream.anyMatch { p ->
      val name = p.fileName?.toString() ?: return@anyMatch false
      if (!name.endsWith(".yaml")) return@anyMatch false
      // `*.tool.yaml` files define tool names by id (workspace-overlay registration).
      if (name.endsWith(".tool.yaml")) return@anyMatch true
      // Toolset YAMLs are plain `<id>.yaml` inside a `toolsets/` directory — no
      // filename-level discriminator, so the detection has to look one segment up.
      // A workspace with only toolsets still defines new dispatch surfaces (each toolset
      // lists tool names) and must disable the fast-path. Shortcut/trailhead YAMLs are
      // intentionally excluded: they're navigation operations on existing tools rather
      // than new tool-name registrations, and including them would false-detect any
      // workspace that ships waypoint navigation primitives.
      p.parent?.fileName?.toString() == "toolsets"
    }
  }
}
