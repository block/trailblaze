package xyz.block.trailblaze.host

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.Maestro
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.yaml.YamlCommandReader
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.Ext.asJsonObject
import java.io.File

class MaestroHostRunnerImpl(
  requestedPlatform: TrailblazeDevicePlatform? = null,
  setOfMarkEnabled: Boolean = true,
) : MaestroHostRunner {
  val connectedDevice: TrailblazeConnectedDevice by lazy {
    if (requestedPlatform == null) {
      TrailblazeDeviceService.getFirstConnectedDevice()
    } else {
      TrailblazeDeviceService.getConnectedDevice(requestedPlatform) ?: error("No connected devices found.")
    }
  }

  val connectedTrailblazeDriverType: TrailblazeDriverType = connectedDevice.trailblazeDriverType

  val loggingDriver: LoggingDriver by lazy {
    connectedDevice.loggingDriver
  }

  override val screenStateProvider: () -> ScreenState = {
    HostMaestroDriverScreenState(
      maestroDriver = loggingDriver,
      setOfMarkEnabled = setOfMarkEnabled,
    )
  }

  override fun runMaestroYaml(yaml: String): TrailblazeToolResult {
    val flowFile = File.createTempFile("flow", ".yaml").also {
      it.writeText(
        yaml,
      )
    }
    return runFlowFile(flowFile)
  }

  override fun runFlowFile(flowFile: File): TrailblazeToolResult {
    val env: Map<String, String> = emptyMap()
    val maestroCommands: List<MaestroCommand> = YamlCommandReader.readCommands(flowFile.toPath())
      .withEnv(env.withInjectedShellEnvVars().withDefaultEnvVars(flowFile))
    return runMaestroCommandsInternal(
      commands = maestroCommands,
      llmResponseId = null,
    )
  }

  override fun runMaestroCommand(vararg commands: Command): TrailblazeToolResult = runMaestroCommands(
    commands = commands.toList(),
    llmResponseId = null,
  )

  override fun runMaestroCommands(
    commands: List<Command>,
    llmResponseId: String?,
  ): TrailblazeToolResult = runMaestroCommandsInternal(
    commands = commands.map { MaestroCommand(it) },
    llmResponseId = llmResponseId,
  )

  private fun runMaestroCommandsInternal(
    commands: List<MaestroCommand>,
    llmResponseId: String?,
  ): TrailblazeToolResult {
    commands.forEach { maestroCommand ->
      val startTime = Clock.System.now()
      var result: TrailblazeToolResult = TrailblazeToolResult.Success

      Orchestra(
        maestro = Maestro(loggingDriver),
        onCommandFailed = { index: Int, maestroCommand: MaestroCommand, throwable: Throwable ->
          val commandJson = maestroCommand.asJsonObject()
          result = TrailblazeToolResult.Error.MaestroValidationError(
            commandJsonObject = commandJson,
            errorMessage = "Failed to run command: $commandJson.  Error: ${throwable.message}",
          )
          Orchestra.ErrorResolution.FAIL
        },
      ).also { orchestra ->
        // TODO runBlocking
        runBlocking { orchestra.runFlow(listOf(maestroCommand)) }
      }

      TrailblazeLogger.log(
        TrailblazeLog.MaestroCommandLog(
          maestroCommandJsonObj = maestroCommand.asJsonObject(),
          trailblazeToolResult = result,
          timestamp = startTime,
          durationMs = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds(),
          llmResponseId = llmResponseId,
          successful = result is TrailblazeToolResult.Success,
          session = TrailblazeLogger.getCurrentSessionId(),
        ),
      )
      if (result != TrailblazeToolResult.Success) {
        return result
      }
    }
    return TrailblazeToolResult.Success
  }

  fun withSetOfMarkEnabled(setOfMarkEnabled: Boolean): MaestroHostRunner = object : MaestroHostRunner {
    override val screenStateProvider: () -> ScreenState = {
      HostMaestroDriverScreenState(
        maestroDriver = loggingDriver,
        setOfMarkEnabled = setOfMarkEnabled,
      )
    }

    override fun runMaestroYaml(yaml: String) = this@MaestroHostRunnerImpl.runMaestroYaml(yaml)
    override fun runFlowFile(flowFile: File) = this@MaestroHostRunnerImpl.runFlowFile(flowFile)
    override fun runMaestroCommand(vararg commands: Command) = this@MaestroHostRunnerImpl.runMaestroCommand(*commands)

    override fun runMaestroCommands(commands: List<Command>, llmResponseId: String?) = this@MaestroHostRunnerImpl.runMaestroCommands(commands, llmResponseId)
  }
}
