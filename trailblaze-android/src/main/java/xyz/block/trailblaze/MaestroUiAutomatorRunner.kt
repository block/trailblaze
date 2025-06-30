package xyz.block.trailblaze

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
import xyz.block.trailblaze.maestro.MaestroYamlParser
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.Ext.asJsonObject

/**
 * Allows us to run Maestro Commands using UiAutomator.
 */
object MaestroUiAutomatorRunner {

  fun runCommands(
    commands: List<Command>,
    llmResponseId: String?,
  ): TrailblazeToolResult = runMaestroYaml(
    commands = commands.filterNot { it is ApplyConfigurationCommand }.map { MaestroCommand(it) },
    llmResponseId = llmResponseId,
  )

  fun runCommand(
    vararg command: Command,
  ): TrailblazeToolResult = runCommands(
    commands = command.toList(),
    llmResponseId = null,
  )

  fun runYamlResource(resourcePath: String): TrailblazeToolResult {
    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    val yaml = object {}.javaClass.classLoader.getResource(resourcePath)?.readText()
    return runMaestroYaml(yaml ?: error("Resource not found: $resourcePath"))
  }

  fun runMaestroYaml(appId: String, yamlStringWithoutConfig: String): TrailblazeToolResult {
    val maestroCommandsFromYaml: List<MaestroCommand> = MaestroYamlParser.parseYaml(
      yaml = yamlStringWithoutConfig,
      appId = appId,
    ).map {
      MaestroCommand(it)
    }
    return runMaestroYaml(
      maestroCommandsFromYaml,
      null,
    )
  }

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

  private fun runMaestroYaml(yamlString: String): TrailblazeToolResult {
    val commands: List<Command> = MaestroYamlParser.parseYaml(
      yamlString,
    )
    val result = runCommands(commands, null)
    return result
  }

  private fun runMaestroYaml(
    commands: List<MaestroCommand>,
    llmResponseId: String?,
  ): TrailblazeToolResult {
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
          llmResponseId = llmResponseId,
          successful = result is TrailblazeToolResult.Success,
          session = TrailblazeLogger.getCurrentSessionId(),
        ),
      )

      if (runSuccess == false) {
        return result
      }
    }
    return TrailblazeToolResult.Success
  }
}
