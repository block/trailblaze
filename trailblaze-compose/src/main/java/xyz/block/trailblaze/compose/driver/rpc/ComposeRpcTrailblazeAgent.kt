package xyz.block.trailblaze.compose.driver.rpc

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.TrailblazeAgentContext
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.compose.driver.ComposeViewHierarchyDetail
import xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
import xyz.block.trailblaze.compose.driver.tools.ComposeExecutableTool
import xyz.block.trailblaze.compose.driver.tools.ComposeRequestDetailsTool
import xyz.block.trailblaze.compose.driver.tools.ComposeScrollTool
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyElementVisibleTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyTextVisibleTool
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

/**
 * [TrailblazeAgent] that delegates tool execution to a remote Compose app via RPC.
 *
 * Parallel to [xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent] but sends tools over
 * HTTP to a [ComposeRpcServer] running inside the Compose application, rather than executing them
 * in-process against a [ComposeUiTest].
 *
 * After each tool execution batch, captures a screenshot and view hierarchy via RPC and logs a
 * [TrailblazeLog.AgentDriverLog] so the HTML report can visualize what happened on screen.
 */
class ComposeRpcTrailblazeAgent(
  private val rpcClient: ComposeRpcClient,
  override val trailblazeLogger: TrailblazeLogger,
  override val sessionProvider: TrailblazeSessionProvider,
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  override val memory: AgentMemory = AgentMemory(),
) : TrailblazeAgent,
  TrailblazeAgentContext,
  Closeable {

  fun clearMemory() {
    memory.clear()
  }

  private val pendingDetailRequests =
    AtomicReference<Set<ComposeViewHierarchyDetail>>(emptySet())

  val screenStateProvider: () -> ScreenState = {
    val details = pendingDetailRequests.getAndSet(emptySet())
    val screenStateResult = runBlocking { rpcClient.getScreenState(requestedDetails = details) }
    when (screenStateResult) {
      is RpcResult.Success -> ComposeRpcScreenState(screenStateResult.data)
      is RpcResult.Failure ->
        error("Failed to get screen state: ${screenStateResult.message}")
    }
  }

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeAgent.RunTrailblazeToolsResult {
    val resolvedTraceId = traceId ?: TraceId.generate(TraceOrigin.TOOL)
    val toolsExecuted = mutableListOf<TrailblazeTool>()
    var lastSuccessResult: TrailblazeToolResult = TrailblazeToolResult.Success()

    // Separate tools that must run locally from those that go over RPC.
    // Process them in order, batching consecutive ComposeExecutableTools for RPC.
    val overallStartTime = Clock.System.now()

    for (tool in tools) {
      when (tool) {
        is MemoryTrailblazeTool -> {
          toolsExecuted.add(tool)
          val result = tool.execute(memory = memory, elementComparator = elementComparator)
          if (!result.isSuccess()) {
            return TrailblazeAgent.RunTrailblazeToolsResult(
              inputTools = tools,
              executedTools = toolsExecuted,
              result = result,
            )
          }
          lastSuccessResult = result
        }

        is ComposeRequestDetailsTool -> {
          val toolStartTime = Clock.System.now()
          toolsExecuted.add(tool)
          pendingDetailRequests.set(tool.include.toSet())
          lastSuccessResult =
            TrailblazeToolResult.Success(
              message = "Requested details: ${tool.include.joinToString()}"
            )
          logToolExecution(tool, toolStartTime, resolvedTraceId, lastSuccessResult)
        }

        is ComposeExecutableTool -> {
          val toolStartTime = Clock.System.now()
          toolsExecuted.add(tool)
          val rpcTools = listOf(tool)
          var executionTimeMs: Long
          val rpcResult: RpcResult<ExecuteToolsResponse>
          executionTimeMs = measureTimeMillis {
            rpcResult = runBlocking { rpcClient.executeTools(ExecuteToolsRequest(tools = rpcTools)) }
          }

          when (rpcResult) {
            is RpcResult.Success -> {
              val result = rpcResult.data.results.firstOrNull() ?: TrailblazeToolResult.Success()
              logToolExecution(tool, toolStartTime, resolvedTraceId, result)
              if (!result.isSuccess()) {
                logScreenStateAfterExecution(
                  tools = tools,
                  startTime = overallStartTime,
                  executionTimeMs = executionTimeMs,
                  traceId = resolvedTraceId
                )
                return TrailblazeAgent.RunTrailblazeToolsResult(
                  inputTools = tools,
                  executedTools = toolsExecuted,
                  result = result,
                )
              }
              lastSuccessResult = result
            }

            is RpcResult.Failure -> {
              val errorMessage =
                "RPC call failed: ${rpcResult.message} (${rpcResult.errorType})" +
                    (rpcResult.details?.let { " — $it" } ?: "")
              return TrailblazeAgent.RunTrailblazeToolsResult(
                inputTools = tools,
                executedTools = toolsExecuted,
                result = TrailblazeToolResult.Error.ExceptionThrown(errorMessage),
              )
            }
          }
        }

        is ExecutableTrailblazeTool -> {
          val toolStartTime = Clock.System.now()
          toolsExecuted.add(tool)
          val context =
            TrailblazeToolExecutionContext(
              screenState = null,
              traceId = resolvedTraceId,
              trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
              sessionProvider = sessionProvider,
              screenStateProvider = screenStateProvider,
              trailblazeLogger = trailblazeLogger,
              memory = memory,
            )
          val result = runBlocking { tool.execute(context) }
          logToolExecution(
            tool = tool,
            timeBeforeExecution = toolStartTime,
            traceId = resolvedTraceId,
            result = result
          )
          if (!result.isSuccess()) {
            logScreenStateAfterExecution(
              tools = tools,
              startTime = overallStartTime,
              executionTimeMs = kotlin.time.Clock.System.now()
                .toEpochMilliseconds() - overallStartTime.toEpochMilliseconds(),
              traceId = resolvedTraceId,
            )
            return TrailblazeAgent.RunTrailblazeToolsResult(
              inputTools = tools,
              executedTools = toolsExecuted,
              result = result,
            )
          }
          lastSuccessResult = result
        }

        else -> {
          val errorMessage =
            "Unsupported tool type ${tool::class.simpleName} in ComposeRpcTrailblazeAgent."
          Console.log("Error: $errorMessage")
          toolsExecuted.add(tool)
          return TrailblazeAgent.RunTrailblazeToolsResult(
            inputTools = tools,
            executedTools = toolsExecuted,
            result = TrailblazeToolResult.Error.ExceptionThrown(errorMessage),
          )
        }
      }
    }

    // Capture screenshot + view hierarchy after execution for the HTML report
    val totalTimeMs =
      Clock.System.now().toEpochMilliseconds() - overallStartTime.toEpochMilliseconds()
    logScreenStateAfterExecution(tools, overallStartTime, totalTimeMs, resolvedTraceId)

    return TrailblazeAgent.RunTrailblazeToolsResult(
      inputTools = tools,
      executedTools = toolsExecuted,
      result = lastSuccessResult,
    )
  }

  /**
   * Captures the current screen state via RPC and logs a [TrailblazeLog.AgentDriverLog] with the
   * screenshot and view hierarchy. This is what the HTML report uses to visualize each step.
   *
   * Follows the same pattern as [LoggingDriver.logActionWithScreenshot] for Maestro-based drivers.
   */
  private fun logScreenStateAfterExecution(
    tools: List<TrailblazeTool>,
    startTime: kotlinx.datetime.Instant,
    executionTimeMs: Long,
    traceId: TraceId? = null,
  ) {
    try {
      val screenStateResult = runBlocking { rpcClient.getScreenState() }
      if (screenStateResult is RpcResult.Success) {
        val screenState = ComposeRpcScreenState(screenStateResult.data)
        val session = sessionProvider.invoke()

        val screenshotFilename =
          if (screenState.screenshotBytes != null) {
            trailblazeLogger.logScreenState(session, screenState)
          } else {
            null
          }

        val action = mapToolsToDriverAction(tools)

        val log =
          TrailblazeLog.AgentDriverLog(
            viewHierarchy = screenState.viewHierarchy,
            trailblazeNodeTree = screenState.trailblazeNodeTree,
            screenshotFile = screenshotFilename,
            action = action,
            durationMs = executionTimeMs,
            timestamp = startTime,
            session = session.sessionId,
            deviceWidth = screenState.deviceWidth,
            deviceHeight = screenState.deviceHeight,
            traceId = traceId,
          )
        trailblazeLogger.log(session, log)
      }
    } catch (e: Exception) {
      // Don't fail the tool execution just because screenshot logging failed
      Console.log("Warning: Failed to log screen state after tool execution: ${e.message}")
    }
  }

  /**
   * Maps the first tool in a batch to a [AgentDriverAction] for logging. Uses specific action
   * types where possible (e.g., [AgentDriverAction.EnterText] for type tools) and falls back
   * to [AgentDriverAction.OtherAction] for compose-specific tools.
   */
  private fun mapToolsToDriverAction(tools: List<TrailblazeTool>): AgentDriverAction {
    val firstTool = tools.firstOrNull() ?: return AgentDriverAction.OtherAction(AgentActionType.TAP_POINT)
    return when (firstTool) {
      is ComposeTypeTool -> AgentDriverAction.EnterText(firstTool.text)
      is ComposeClickTool -> AgentDriverAction.OtherAction(AgentActionType.TAP_POINT)
      is ComposeScrollTool -> AgentDriverAction.OtherAction(AgentActionType.SWIPE)
      is ComposeVerifyTextVisibleTool ->
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify text visible: ${firstTool.text}",
          x = 0,
          y = 0,
          isVisible = true,
          textToDisplay = firstTool.text,
        )

      is ComposeVerifyElementVisibleTool ->
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify element visible: ${firstTool.testTag}",
          x = 0,
          y = 0,
          isVisible = true,
        )

      else -> AgentDriverAction.OtherAction(AgentActionType.TAP_POINT)
    }
  }

  private fun logToolExecution(
    tool: TrailblazeTool,
    timeBeforeExecution: kotlinx.datetime.Instant,
    traceId: TraceId,
    result: TrailblazeToolResult,
  ) {
    val session = sessionProvider.invoke()
    val toolLog =
      TrailblazeLog.TrailblazeToolLog(
        trailblazeTool = tool,
        toolName = tool.getToolNameFromAnnotation(),
        exceptionMessage = (result as? TrailblazeToolResult.Error)?.errorMessage,
        successful = result.isSuccess(),
        durationMs =
          Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds(),
        timestamp = timeBeforeExecution,
        traceId = traceId,
        session = session.sessionId,
        isRecordable = tool.getIsRecordableFromAnnotation(),
      )

    trailblazeLogger.log(session, toolLog)
  }

  override fun close() {
    rpcClient.close()
  }
}
