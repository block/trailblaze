package xyz.block.trailblaze

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * Abstract class for Trailblaze agents that handle Maestro commands.
 * This class provides a framework for executing Maestro commands and handling [TrailblazeTool]s.
 *
 * This is abstract because there can be both on-device and host implementations of this agent.
 * Uses stateless logger with explicit session management via sessionProvider.
 */
abstract class MaestroTrailblazeAgent(
  override val trailblazeLogger: TrailblazeLogger,
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  override val sessionProvider: TrailblazeSessionProvider,
  /** Controls nodeSelector vs legacy Maestro path for playback and recording. */
  val nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
  /**
   * Session tool repo — threaded to the base so `OtherTrailblazeTool` instances (e.g.
   * subprocess MCP tool names in a trail YAML) can resolve through
   * [xyz.block.trailblaze.toolcalls.TrailblazeToolRepo] before driver dispatch.
   *
   * Nullable with a default here on purpose: the host and test agents legitimately run without a
   * repo. The two on-device Android subclasses (`AndroidMaestroTrailblazeAgent`,
   * `AccessibilityTrailblazeAgent`) deliberately tighten this to a required, non-null constructor
   * param — two separate JUnit rules construct them and a forgotten repo regressed once (#3920), so
   * for those classes the compiler now forces the wiring. Don't "simplify" them back to a default.
   */
  trailblazeToolRepo: TrailblazeToolRepo? = null,
  /**
   * Optional shared [AgentMemory] — see [BaseTrailblazeAgent.memory]. Defaults to a fresh
   * instance for normal in-process construction; on-device RPC handlers pass an instance
   * pre-populated from the host's snapshot so writes are visible across the boundary.
   */
  memory: AgentMemory = AgentMemory(),
  /**
   * Mirrors `RunYamlRequest.config.captureNetworkTraffic`. Surfaced to tools via
   * [TrailblazeToolExecutionContext.captureNetworkTraffic] so capture-aware launch tools (e.g.
   * an Android target whose debug build needs SharedPref gates flipped before the first network
   * call so an out-of-band capture bridge can attach) can branch on the toggle without having to
   * plumb the request config through every nested helper. Defaults to `false` so existing rules
   * and tests stay unchanged.
   */
  val captureNetworkTraffic: Boolean = false,
  /**
   * The session's active target (trailmap manifest + device pair). Propagated to every
   * [TrailblazeToolExecutionContext] this agent builds so the `_meta.trailblaze.target`
   * envelope block populates, which is what makes `ctx.target?.resolveAppId()` work for
   * scripted tools dispatched through this agent.
   *
   * Null when the rule wasn't constructed from a
   * [xyz.block.trailblaze.model.TrailblazeHostAppTarget] — e.g. unit-test fixtures or a
   * target-agnostic rule. Scripted tools that need to handle both shapes should
   * optional-chain (`ctx.target?.resolveAppId(...)`).
   *
   * **Why this exists.** Scripted tools that run in-process on-device (via the QuickJS
   * bundle path, no host fallback) have no other way to learn which app to act on — they
   * can't probe the device themselves, and a hardcoded app-id list both duplicates trailmap.yaml
   * and turns into a per-call `mobile_listInstalledApps` round-trip. Surfacing the resolved
   * target on every context replaces that pattern with a data field the framework
   * populates once at session start. Square card-reader broadcast tools are the canonical
   * consumer today.
   */
  val resolvedTarget: ResolvedTarget? = null,
  /**
   * The session's resolved Android app id — already device-filtered (intersected against
   * installed packages) by whoever built this agent. Null when [resolvedTarget] is null OR
   * when no declared candidate is installed.
   *
   * Mirrors the host runner's pre-resolved string surfaced through
   * `TrailblazeToolExecutionContext.appId`. Threaded into the execution context
   * exactly like [resolvedTarget].
   *
   * **Naming note.** This is the *device-resolved* id and is nullable. The unrelated
   * `xyz.block.trailblaze.model.ResolvedTarget.appId` getter is the *declared-first*
   * id from the trailmap manifest and is non-null (throws if none declared). Don't confuse
   * `agent.appId` (this) with `resolvedTarget.appId` (declared-first) — they answer
   * different questions.
   */
  val appId: String? = null,
  /**
   * Maps a [SessionId] to its on-host session-log directory. Surfaced to tools via
   * [TrailblazeToolExecutionContext.sessionDirProvider] so host-side tools that read
   * capture artifacts written under the session dir (e.g. a host-side capture-reading tool that
   * reads the per-plugin network NDJSON the daemon writes to `<sessionDir>/events/`) can locate them.
   *
   * Null for on-device agents (there is no host session dir on the device) and for
   * target-agnostic tests. Host agents wire this from `LogsRepo::getSessionDir` at construction
   * — see `BaseHostTrailblazeTest` / `TrailblazeHostYamlRunner`.
   */
  val sessionDirProvider: ((SessionId) -> File)? = null,
) : BaseTrailblazeAgent(memory = memory) {

  override val trailblazeToolRepo: TrailblazeToolRepo? = trailblazeToolRepo

  /**
   * Whether this agent uses the accessibility driver instead of Maestro/UiAutomator.
   * Tools can check this to choose accessibility-friendly command paths.
   */
  open val usesAccessibilityDriver: Boolean = false

  protected abstract suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult

  /**
   * Executes a tap using the rich [TrailblazeNodeSelector], bypassing the Maestro command layer.
   *
   * Override in driver-specific agents (e.g., AccessibilityTrailblazeAgent) to provide
   * native resolution using [TrailblazeNode] trees.
   *
   * @return A [TrailblazeToolResult] if the driver handled the action, or null if the driver
   *   does not support [TrailblazeNodeSelector] (in which case the caller should fall back
   *   to the Maestro command path).
   */
  open suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult? = null

  /**
   * Asserts that an element matching the [nodeSelector] is visible, bypassing Maestro commands.
   *
   * Override in driver-specific agents (e.g., AccessibilityTrailblazeAgent) to provide
   * native resolution using [TrailblazeNodeSelector] trees.
   *
   * @return A [TrailblazeToolResult] if the driver handled the assertion, or null to fall back
   *   to the Maestro command path.
   */
  open suspend fun executeNodeSelectorAssertVisible(
    nodeSelector: TrailblazeNodeSelector,
    /**
     * How long to wait for the element to become visible. `null` means the call site is
     * not opinionated about timeout and each agent implementation should apply its own
     * idle/wait policy (typically a per-driver default).
     */
    timeoutMs: Long? = null,
    traceId: TraceId?,
  ): TrailblazeToolResult? = null

  /**
   * Asserts that no element matching the [nodeSelector] is visible, bypassing Maestro commands.
   *
   * Override in driver-specific agents (e.g., AccessibilityTrailblazeAgent) to provide
   * native resolution using [TrailblazeNodeSelector] trees.
   *
   * @return A [TrailblazeToolResult] if the driver handled the assertion, or null to fall back
   *   to the Maestro command path.
   */
  open suspend fun executeNodeSelectorAssertNotVisible(
    nodeSelector: TrailblazeNodeSelector,
    /**
     * How long to wait for the element to disappear. `null` means the call site is not
     * opinionated about timeout and each agent implementation should apply its own
     * idle/wait policy (typically a per-driver default).
     */
    timeoutMs: Long? = null,
    traceId: TraceId?,
  ): TrailblazeToolResult? = null

  /**
   * Waits until the on-device UI tree changes relative to a baseline captured at call entry,
   * then waits [quietWindowMs] of no further events to settle.
   *
   * Override in driver-specific agents that have event-driven quiescence (today: the Android
   * accessibility driver). The default returns null to signal "this driver doesn't support
   * change detection" so the calling tool can fall back to a plain timed wait.
   *
   * @return A [TrailblazeToolResult] if the driver handled the wait, or null when unsupported.
   */
  open suspend fun waitForTreeChange(
    timeoutMs: Long,
    quietWindowMs: Long,
    requireChange: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult? = null

  @Deprecated(
    message = "Use the suspend function runMaestroCommands() instead.",
    replaceWith = ReplaceWith("runMaestroCommands(maestroCommands, traceId)"),
  )
  fun runMaestroCommandsBlocking(
    maestroCommands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = runBlocking {
    runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = traceId,
    )
  }

  suspend fun runMaestroCommands(
    maestroCommands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    maestroCommands.forEach { command ->
      val result = executeMaestroCommands(
        commands = listOf(command),
        traceId = traceId,
      )
      if (!result.isSuccess()) {
        return result
      }
    }
    return TrailblazeToolResult.Success()
  }

  override fun buildExecutionContext(
    traceId: TraceId,
    screenState: ScreenState?,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolExecutionContext {
    val trailblazeDeviceInfo = trailblazeDeviceInfoProvider()
    lateinit var context: TrailblazeToolExecutionContext
    context = TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = traceId,
      trailblazeDeviceInfo = trailblazeDeviceInfo,
      sessionProvider = sessionProvider,
      screenStateProvider = screenStateProvider,
      androidDeviceCommandExecutor = AndroidDeviceCommandExecutor(trailblazeDeviceInfo.trailblazeDeviceId),
      trailblazeLogger = trailblazeLogger,
      memory = memory,
      maestroTrailblazeAgent = this,
      // See BaseTrailblazeAgent.nestedToolExecutorFor's kdoc for the full rationale (fixes the
      // nested-composition counterpart of #4506's clipboard bug).
      nestedToolExecutor = nestedToolExecutorFor { context },
      // Threads the agent's tool repo through so Kotlin tools composing framework
      // tools via `ctx.invokeFrameworkTool(...)` can resolve them by name. Without
      // this, the bridge throws "toolRepo not wired" on every Kotlin-side call site
      // in a Maestro-driven session even though the repo exists on the agent.
      toolRepo = trailblazeToolRepo,
      nodeSelectorMode = nodeSelectorMode,
      captureNetworkTraffic = captureNetworkTraffic,
      resolvedTarget = resolvedTarget,
      appId = appId,
      sessionDirProvider = sessionDirProvider,
    )
    return context
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    // Resolve `OtherTrailblazeTool` placeholders through the session's tool repo BEFORE the
    // type-discriminating `when` below. Static YAML deserialization always lands on
    // `OtherTrailblazeTool` for tool names the static catalog doesn't know, which includes
    // every dynamically-registered scripted tool (#2749 inline scripted tools registered by
    // `LazyYamlScriptedToolRegistration`, subprocess MCP tools registered by
    // `SubprocessToolRegistration`, etc.). Without this resolution, those tools landed in the
    // `is OtherTrailblazeTool ->` branch below and threw "Unknown tool '<name>' is not
    // registered" even when the session's repo had a valid registration for them.
    //
    // The base class's `runTrailblazeTools` already calls `resolveDynamicTool` once per
    // batch, so most callers hit this method with the resolved tool already. This second
    // resolution is the defense-in-depth contract `MaestroTrailblazeAgent.trailblazeToolRepo`'s
    // docstring promises: "threaded to the base so `OtherTrailblazeTool` instances can
    // resolve through `TrailblazeToolRepo` before driver dispatch." Direct callers of
    // `executeTool` (and subclass overrides that bypass `runTrailblazeTools`) need the same
    // resolution to honor the contract.
    // Two senses of "resolved" appear on this dispatch path — disambiguated by name so a reader
    // (or grep) can tell them apart:
    //  - `repoResolvedTool` (this binding) = `OtherTrailblazeTool` → concrete tool, via the
    //    session's `trailblazeToolRepo.toolCallToTrailblazeTool(...)` (dynamic-tool dispatch).
    //  - `memoryResolvedTool` (in `handleExecutableTool`) = string-interpolated args via
    //    `interpolateMemoryInTool(...)` — the memory boundary lives THERE, not here, so both
    //    directly-dispatched and delegating-expanded tools pass through it exactly once.
    val repoResolvedTool: TrailblazeTool = if (tool is OtherTrailblazeTool) {
      val repo = trailblazeToolRepo
      if (repo == null) {
        tool
      } else {
        try {
          repo.toolCallToTrailblazeTool(tool.toolName, tool.raw.toString())
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          // Fall through with the original `OtherTrailblazeTool` — the `is OtherTrailblazeTool`
          // branch below produces the contextual "Unknown tool" error with toolName + raw
          // args + the suggested fix (register via `getCustomToolsForDriver`).
          tool
        }
      }
    } else {
      tool
    }
    return when (repoResolvedTool) {
      is ExecutableTrailblazeTool -> {
        toolsExecuted.add(repoResolvedTool)
        handleExecutableToolBlocking(repoResolvedTool, context)
      }
      is DelegatingTrailblazeTool -> {
        executeDelegatingTool(repoResolvedTool, context, toolsExecuted) { mappedTool ->
          handleExecutableToolBlocking(mappedTool, context)
        }
      }
      is OtherTrailblazeTool -> throw TrailblazeException(
        // Align prose with `HostOnDeviceRpcTrailblazeAgent.executeTool`'s else-branch
        // diagnostic so an operator triaging a failed trail sees the same precise
        // taxonomy ("class-backed, YAML-defined, or dynamic scripted tool") regardless
        // of which agent fired. Drift between the two messages would make triage
        // depend on which dispatcher the trail's driver routes through — confusing
        // for a debugger who doesn't know that detail.
        message = buildString {
          appendLine(
            "Unknown tool '${repoResolvedTool.toolName}' is not registered in this session's " +
              "tool repo as a class-backed, YAML-defined, or dynamic scripted tool, and cannot " +
              "be executed.",
          )
          appendLine(
            "This usually means the tool's class is not in the custom tool classes for this app " +
              "target, OR the dynamic registration (#2749 inline scripted tools, subprocess MCP) " +
              "failed at session start (check earlier daemon log for a `Could not resolve` " +
              "breadcrumb).",
          )
          appendLine(
            "Ensure the app target is selected (e.g., in the UI or via CLI) and that it " +
              "registers this tool via getCustomToolsForDriver(), or that its scripted-tool " +
              "descriptor under <trailmap>/tools/ loaded cleanly.",
          )
          appendLine("Raw parameters: ${repoResolvedTool.raw}")
        },
      )
      else -> throw TrailblazeException(
        message = buildString {
          appendLine("Unhandled Trailblaze tool ${repoResolvedTool::class.java.simpleName} - ${repoResolvedTool}.")
          appendLine("Supported Trailblaze Tools must implement one of the following:")
          appendLine("- ${ExecutableTrailblazeTool::class.java.simpleName}")
          appendLine("- ${DelegatingTrailblazeTool::class.java.simpleName}")
        },
      )
    }
  }

  @Deprecated(
    message = "Use the suspend function handleExecutableTool() instead.",
    replaceWith = ReplaceWith("handleExecutableTool(trailblazeTool, trailblazeExecutionContext)"),
  )
  private fun handleExecutableToolBlocking(
    trailblazeTool: ExecutableTrailblazeTool,
    trailblazeExecutionContext: TrailblazeToolExecutionContext,
  ) = runBlocking {
    handleExecutableTool(trailblazeTool, trailblazeExecutionContext)
  }

  private suspend fun handleExecutableTool(
    trailblazeTool: ExecutableTrailblazeTool,
    trailblazeExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // THE memory-interpolation boundary for every Maestro-family driver (host Maestro, Android
    // instrumentation, on-device accessibility, iOS AXe): each concrete executable — whether
    // dispatched directly or expanded from a DelegatingTrailblazeTool — passes through here
    // exactly once, right before `execute()`. The tool executes with resolved args; the log
    // carries the resolved form plus the authored (token-bearing) form when they differ, which
    // is what lets recordings keep their `{{var}}` tokens.
    val memoryResolvedTool = interpolateMemoryInTool(trailblazeTool, memory)
    val timeBeforeToolExecution = Clock.System.now()
    val trailblazeToolResult = memoryResolvedTool.execute(
      toolExecutionContext = trailblazeExecutionContext,
    )
    logToolExecution(
      tool = memoryResolvedTool,
      timeBeforeExecution = timeBeforeToolExecution,
      context = trailblazeExecutionContext,
      result = trailblazeToolResult,
      rawTool = trailblazeTool.takeIf { it !== memoryResolvedTool },
    )
    return trailblazeToolResult
  }
}
