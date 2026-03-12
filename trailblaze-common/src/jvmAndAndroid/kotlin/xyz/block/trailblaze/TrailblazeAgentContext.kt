package xyz.block.trailblaze

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.util.Console

/**
 * Common context properties shared by all [TrailblazeAgent] implementations.
 *
 * Both [MaestroTrailblazeAgent] and PlaywrightTrailblazeAgent implement this
 * interface, allowing [TrailblazeKoogLlmClientHelper] to work with any agent
 * without a hard cast to a specific implementation.
 */
interface TrailblazeAgentContext {
  val trailblazeLogger: TrailblazeLogger
  val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo
  val sessionProvider: TrailblazeSessionProvider
  val memory: AgentMemory
}

fun TrailblazeAgentContext.logToolExecution(
  tool: ExecutableTrailblazeTool,
  timeBeforeExecution: Instant,
  context: TrailblazeToolExecutionContext,
  result: TrailblazeToolResult,
) {
  val toolLog =
    TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = tool,
      toolName = tool.getToolNameFromAnnotation(),
      exceptionMessage = (result as? TrailblazeToolResult.Error)?.errorMessage,
      successful = result.isSuccess(),
      durationMs =
        Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds(),
      timestamp = timeBeforeExecution,
      traceId = context.traceId,
      session = sessionProvider.invoke().sessionId,
      isRecordable = tool.getIsRecordableFromAnnotation(),
    )
  val toolLogJson = TrailblazeJsonInstance.encodeToString(toolLog)
  Console.log("toolLogJson: $toolLogJson")

  val session = sessionProvider.invoke()
  trailblazeLogger.log(session, toolLog)
}

fun TrailblazeAgentContext.logDelegatingTool(
  tool: DelegatingTrailblazeTool,
  traceId: TraceId?,
  executableTools: List<ExecutableTrailblazeTool>,
) {
  val session = sessionProvider.invoke()
  trailblazeLogger.log(
    session,
    TrailblazeLog.DelegatingTrailblazeToolLog(
      trailblazeTool = tool,
      toolName = tool.getToolNameFromAnnotation(),
      executableTools = executableTools,
      session = session.sessionId,
      traceId = traceId,
      timestamp = Clock.System.now(),
    ),
  )
}
