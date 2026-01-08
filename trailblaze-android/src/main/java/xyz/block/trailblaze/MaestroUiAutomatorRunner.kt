package xyz.block.trailblaze

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.android.maestro.MaestroAndroidUiAutomatorDriver
import xyz.block.trailblaze.android.maestro.orchestra.Orchestra
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.maestro.OrchestraRunner
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Allows us to run Maestro Commands using UiAutomator.
 * Uses stateless logger with explicit session management.
 */
object MaestroUiAutomatorRunner {

  suspend fun runCommands(
    commands: List<Command>,
    traceId: TraceId?,
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
  ): TrailblazeToolResult = runMaestroCommands(
    commands = commands.filterNot { it is ApplyConfigurationCommand }.map { MaestroCommand(it) },
    traceId = traceId,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  fun runCommandsBlocking(
    commands: List<Command>,
    traceId: TraceId?,
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
  ): TrailblazeToolResult = runBlocking {
    runCommands(
      commands = commands,
      traceId = traceId,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
    )
  }

  suspend fun runCommand(
    traceId: TraceId?,
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
    vararg command: Command,
  ): TrailblazeToolResult = runCommands(
    commands = command.toList(),
    traceId = traceId,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  private suspend fun runMaestroCommands(
    commands: List<MaestroCommand>,
    traceId: TraceId?,
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
  ): TrailblazeToolResult {
    val traceId = traceId ?: TraceId.generate(TraceOrigin.MAESTRO)

    val screenStateProvider = {
      AndroidOnDeviceUiAutomatorScreenState(
        setOfMarkEnabled = false, // We don't need this unless it's an LLM Request that requires it
      )
    }

    val maestro = Maestro(
      driver = LoggingDriver(
        delegate = MaestroAndroidUiAutomatorDriver,
        screenStateProvider = screenStateProvider,
        trailblazeLogger = trailblazeLogger,
        sessionProvider = sessionProvider,
      ),
    )

    // Use OrchestraRunner to execute commands with standardized callbacks
    return OrchestraRunner.runCommands(
      maestro = maestro,
      commands = commands,
      traceId = traceId,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
      screenStateProvider = screenStateProvider,
      orchestraFactory = { callbacks ->
        // Create Orchestra executor with standardized callbacks
        object : OrchestraRunner.OrchestraExecutor {
          override suspend fun execute(commands: List<MaestroCommand>): Boolean = Orchestra(
            maestro = maestro,
            onCommandComplete = callbacks.onCommandComplete,
            onCommandFailed = { index, command, throwable ->
              callbacks.onCommandFailed(index, command, throwable)
              Orchestra.ErrorResolution.FAIL
            },
          ).runFlow(commands)
        }
      },
    )
  }
}
