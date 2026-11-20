package xyz.block.trailblaze.host.lease

import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.AndroidAccessibilityServiceDrivers
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.mergeDeviceMemory
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.logs.client.SessionSecrets
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.toolcalls.requiresHostInstance
import xyz.block.trailblaze.toolcalls.sensitiveLiteralValues
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.fromTrailblazeTool
import java.io.File
import kotlin.reflect.KClass

/**
 * Drives a device borrowed from another machine ([RemoteDevicePools]). The agent, its memory and
 * the session log are here; each tool that acts on the device goes to the lender whole, which runs
 * it with its own driver for the device, so any platform the lender drives works the same.
 *
 * A host-local tool (scripted, subprocess, `requiresHost`) runs here, where the trail's files and
 * memory are, and the device tools it calls go to the lender like any other; so does a named
 * snapshot, which belongs to this session's report. A tool is sent with
 * memory already filled in, and with the memory itself, its secrets marked, so the lender's logs keep
 * them redacted; what the tool remembers there comes back into this memory.
 */
class RemoteTrailblazeAgent(
  private val device: TrailblazeDeviceId,
  /** The driver the lender runs the device with. */
  driverType: TrailblazeDriverType,
  /** Sends one call to the lender for [device]. */
  private val call: (RemoteDeviceCall) -> RemoteDeviceReply,
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
  /** Every tool class this session may send, so each encodes for the lender. */
  customToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
  trailblazeToolRepo: TrailblazeToolRepo? = null,
  resolvedTarget: ResolvedTarget? = null,
  appId: String? = null,
  sessionDirProvider: ((SessionId) -> File)? = null,
  workingDirectory: File? = null,
  memory: AgentMemory = AgentMemory(),
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  trailblazeToolRepo = trailblazeToolRepo,
  memory = memory,
  resolvedTarget = resolvedTarget,
  appId = appId,
  sessionDirProvider = sessionDirProvider,
  workingDirectory = workingDirectory,
) {

  override val usesAccessibilityDriver: Boolean = AndroidAccessibilityServiceDrivers.includes(driverType)

  private val trailblazeYaml = createTrailblazeYaml(customToolClasses)

  /** Sent with every call, so the lender runs tools under this run's target. */
  private val targetId: String? = resolvedTarget?.id

  /** The device's screen, captured by the lender. */
  val screenStateProvider: () -> ScreenState = {
    call(RemoteDeviceCall(device.toFullyQualifiedDeviceId(), RemoteDeviceCall.Op.SCREEN_STATE, target = targetId)).screenState?.toScreenState()
      ?: error("${device.toFullyQualifiedDeviceId()}'s lender sent no screen state")
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    val resolved = resolveThroughRepo(tool)
    return when {
      resolved is ExecutableTrailblazeTool && runsHere(resolved) -> super.executeTool(resolved, context, toolsExecuted)
      resolved is ExecutableTrailblazeTool -> {
        toolsExecuted.add(resolved)
        sendLogged(resolved, context)
      }
      resolved is DelegatingTrailblazeTool ->
        executeDelegatingTool(resolved, context, toolsExecuted) { expanded ->
          if (runsHere(expanded)) super.executeTool(expanded, context, mutableListOf()) else sendLogged(expanded, context)
        }
      // A name only the lender knows, such as a tool of its own target: sent as written.
      else -> {
        toolsExecuted.add(resolved)
        send(resolved)
      }
    }
  }

  override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult {
    if (commands.isEmpty()) return TrailblazeToolResult.Success()
    // Tools reach the lender as themselves; raw Maestro commands have no wire to it.
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "Maestro commands can't be sent to ${device.toFullyQualifiedDeviceId()}'s lender " +
        "(${commands.joinToString { it::class.simpleName.orEmpty() }}). Send the tool instead.",
    )
  }

  /**
   * Host-local tools, and [TakeSnapshotTool]: a snapshot belongs to this session's report, so it is
   * captured here from the lender's screen rather than saved into the lender's logs.
   */
  private fun runsHere(tool: ExecutableTrailblazeTool): Boolean =
    tool is HostLocalExecutableTrailblazeTool || tool is TakeSnapshotTool || tool.requiresHostInstance()

  /** Fills in memory, sends [tool], and logs it in this session as a device-side dispatch. */
  private fun sendLogged(tool: ExecutableTrailblazeTool, context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val memoryResolved = interpolateMemoryInTool(tool, memory, context.trailblazeDeviceInfo)
    val startedAt = Clock.System.now()
    val result = send(memoryResolved)
    logToolExecution(
      tool = memoryResolved,
      timeBeforeExecution = startedAt,
      context = context,
      result = result,
      rawTool = tool.takeIf { it !== memoryResolved },
    )
    return result
  }

  private fun send(tool: TrailblazeTool): TrailblazeToolResult = try {
    val reply = call(
      RemoteDeviceCall(
        device = device.toFullyQualifiedDeviceId(),
        op = RemoteDeviceCall.Op.RUN_TOOL,
        toolYaml = trailblazeYaml.encodeTools(listOf(fromTrailblazeTool(tool))),
        target = targetId,
        memory = memory.variables.toMap(),
        sensitiveMemoryKeys = memory.sensitiveKeys.toList(),
        args = TrailArgBinder.encodeProvided(memory.args),
        sensitiveArgNames = memory.sensitiveArgNames.toList(),
        secrets = (SessionSecrets.valuesFor(sessionProvider.invoke().sessionId) + sensitiveLiteralValues(memory)).distinct(),
      ),
    )
    reply.memory?.let { memory.mergeDeviceMemory(it, reply.memoryDeletions, reply.sensitiveMemoryKeys) }
    reply.toolResult?.toToolResult() ?: TrailblazeToolResult.Error.FatalError("the lender sent no result for ${tool::class.simpleName}")
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (e: Exception) {
    // Unreachable lender, or it took the device back: nothing further can run on this device.
    TrailblazeToolResult.Error.FatalError(e.message ?: e::class.java.simpleName, e.stackTraceToString())
  }

  private fun resolveThroughRepo(tool: TrailblazeTool): TrailblazeTool {
    if (tool !is OtherTrailblazeTool) return tool
    val repo = trailblazeToolRepo ?: return tool
    return runCatching { repo.toolCallToTrailblazeTool(tool.toolName, tool.raw.toString()) }
      .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
      .getOrDefault(tool)
  }
}
