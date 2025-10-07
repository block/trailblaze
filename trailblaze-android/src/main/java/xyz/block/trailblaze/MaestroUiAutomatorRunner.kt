package xyz.block.trailblaze

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.Maestro
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.android.maestro.MaestroAndroidUiAutomatorDriver
import xyz.block.trailblaze.android.maestro.orchestra.Orchestra
import xyz.block.trailblaze.android.maestro.orchestra.Orchestra.ErrorResolution
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.Ext.asJsonObject

/**
 * Allows us to run Maestro Commands using UiAutomator.
 */
object MaestroUiAutomatorRunner {

  suspend fun runCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = runMaestroCommands(
    commands = commands.filterNot { it is ApplyConfigurationCommand }.map { MaestroCommand(it) },
    traceId = traceId,
  )

  fun runCommandsBlocking(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = runBlocking {
    runCommands(
      commands = commands,
      traceId = traceId,
    )
  }

  suspend fun runCommand(
    traceId: TraceId?,
    vararg command: Command,
  ): TrailblazeToolResult = runCommands(
    commands = command.toList(),
    traceId = traceId,
  )

  private val maestro = Maestro(
    driver = LoggingDriver(
      delegate = MaestroAndroidUiAutomatorDriver(),
      screenStateProvider = {
        AndroidOnDeviceUiAutomatorScreenState(
          setOfMarkEnabled = false, // We don't need this unless it's an LLM Request that requires it
        )
      },
    ),
  )

  private suspend fun runMaestroCommands(
    commands: List<MaestroCommand>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val traceId = traceId ?: TraceId.generate(TraceOrigin.MAESTRO)

    commands.forEach { maestroCommand ->
      val maestroCommandJsonObj = maestroCommand.asJsonObject()
      val startTime = Clock.System.now()
      // Run Flow
      var result: TrailblazeToolResult = TrailblazeToolResult.Success
      val runSuccess: Boolean = Orchestra(
        maestro = maestro,
        onCommandFailed = { index: Int, maestroCommand: MaestroCommand, throwable: Throwable ->
          val commandJson = TrailblazeJsonInstance.encodeToString(maestroCommand.asJsonObject())
          result = TrailblazeToolResult.Error.MaestroValidationError(
            commandJsonObject = maestroCommandJsonObj,
            errorMessage = "Failed to run command: $commandJson.  Error: ${throwable.message}",
          )
          ErrorResolution.FAIL
        },
      ).runFlow(listOf(maestroCommand))

      TrailblazeLogger.log(
        TrailblazeLog.MaestroCommandLog(
          maestroCommandJsonObj = maestroCommandJsonObj,
          trailblazeToolResult = result,
          timestamp = startTime,
          durationMs = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds(),
          traceId = traceId,
          successful = result is TrailblazeToolResult.Success,
          session = TrailblazeLogger.getCurrentSessionId(),
        ),
      )

      if (!runSuccess) {
        return result
      }
    }
    return TrailblazeToolResult.Success
  }
}
