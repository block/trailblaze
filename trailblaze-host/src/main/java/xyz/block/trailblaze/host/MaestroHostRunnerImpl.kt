package xyz.block.trailblaze.host

import kotlinx.coroutines.runBlocking
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
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.maestro.OrchestraRunner
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File

class MaestroHostRunnerImpl(
  private val trailblazeDeviceId: TrailblazeDeviceId,
  setOfMarkEnabled: Boolean = true,
  val trailblazeLogger: TrailblazeLogger,
  /**
   * Providing the "App Target" can enable app specific functionality if provided
   */
  appTarget: TrailblazeHostAppTarget? = null,
) : MaestroHostRunner {
  val connectedDevice: TrailblazeConnectedDevice by lazy {
    TrailblazeDeviceService.getConnectedDevice(
      trailblazeDeviceId = trailblazeDeviceId,
      appTarget = appTarget
    ) ?: error(
      "No connected device matching $trailblazeDeviceId found.",
    )
  }

  val loggingDriver: LoggingDriver by lazy {
    connectedDevice.getLoggingDriver(trailblazeLogger)
  }

  companion object {
    var callCount = 0
  }

  override val screenStateProvider: () -> ScreenState = {
    callCount++
    println("screenStateProvider call count: $callCount")
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
      traceId = null,
    )
  }

  override fun runMaestroCommand(vararg commands: Command): TrailblazeToolResult = runMaestroCommands(
    commands = commands.toList(),
    traceId = null,
  )

  override fun runMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = runMaestroCommandsInternal(
    commands = commands.map { MaestroCommand(it) },
    traceId = traceId,
  )

  private fun runMaestroCommandsInternal(
    commands: List<MaestroCommand>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    // Use OrchestraRunner to execute commands with standardized callbacks
    return runBlocking {
      OrchestraRunner.runCommands(
        maestro = Maestro(loggingDriver),
        commands = commands,
        traceId = traceId,
        trailblazeLogger = trailblazeLogger,
        screenStateProvider = screenStateProvider,
        orchestraFactory = { callbacks ->
          // Create Orchestra executor with standardized callbacks
          object : OrchestraRunner.OrchestraExecutor {
            override suspend fun execute(commands: List<MaestroCommand>): Boolean = Orchestra(
              maestro = Maestro(loggingDriver),
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
}
