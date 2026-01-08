package xyz.block.trailblaze.maestro

import kotlinx.datetime.Clock
import maestro.Maestro
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.Ext.asJsonObject

/**
 * Encapsulates common Orchestra execution logic for running Maestro commands.
 * This utility eliminates code duplication across on-device and host runners.
 * 
 * Uses stateless logger with explicit session management via sessionProvider.
 */
object OrchestraRunner {

  /**
   * Runs a list of Maestro commands with standardized error handling and logging.
   *
   * @param maestro The Maestro instance to use for command execution
   * @param commands The list of commands to execute
   * @param traceId Optional trace ID for logging
   * @param trailblazeLogger Logger for capturing command results
   * @param sessionProvider Provides current session for logging operations (required)
   * @param screenStateProvider Function to provide screen state (for assertion logging)
   * @param orchestraFactory Factory function to create Orchestra instances
   * @return The result of the command execution
   */
  suspend fun runCommands(
    maestro: Maestro,
    commands: List<MaestroCommand>,
    traceId: TraceId?,
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
    screenStateProvider: (() -> ScreenState)?,
    orchestraFactory: (OrchestraCallbacks) -> OrchestraExecutor,
  ): TrailblazeToolResult {
    // Create assertion logger for visualization
    val assertionLogger = AssertionLogger(
      maestro = maestro,
      screenStateProvider = screenStateProvider,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider
    )

    commands.forEach { maestroCommand ->
      val maestroCommandJsonObj = maestroCommand.asJsonObject()
      val startTime = Clock.System.now()
      var result: TrailblazeToolResult = TrailblazeToolResult.Success

      // Create callbacks that handle the common logging logic
      val callbacks = OrchestraCallbacks(
        onCommandComplete = { _, command ->
          // Log successful assertion for visualization
          assertionLogger.logSuccessfulAssertionCommand(command)
        },
        onCommandFailed = { _, cmd, throwable ->
          // Log failed assertion for visualization
          assertionLogger.logFailedAssertionCommand(cmd)

          result = TrailblazeToolResult.Error.MaestroValidationError(
            commandJsonObject = maestroCommandJsonObj,
            errorMessage = "Failed to run command: $maestroCommandJsonObj. Error: ${throwable.message}",
          )
        },
      )

      // Execute command using provided Orchestra factory
      val runSuccess = orchestraFactory(callbacks).execute(listOf(maestroCommand))

      // Get current session and log command execution
      val session = sessionProvider.invoke()
      val log = TrailblazeLog.MaestroCommandLog(
        maestroCommandJsonObj = maestroCommandJsonObj,
        trailblazeToolResult = result,
        timestamp = startTime,
        durationMs = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds(),
        traceId = traceId,
        successful = result is TrailblazeToolResult.Success,
        session = session.sessionId,
      )
      trailblazeLogger.log(session, log)

      if (!runSuccess) {
        return result
      }
    }
    return TrailblazeToolResult.Success
  }

  /**
   * Callbacks for Orchestra command execution.
   */
  data class OrchestraCallbacks(
    val onCommandComplete: (Int, MaestroCommand) -> Unit,
    val onCommandFailed: (Int, MaestroCommand, Throwable) -> Unit,
  )

  /**
   * Interface for executing Orchestra commands with configurable callbacks.
   */
  interface OrchestraExecutor {
    suspend fun execute(commands: List<MaestroCommand>): Boolean
  }
}
