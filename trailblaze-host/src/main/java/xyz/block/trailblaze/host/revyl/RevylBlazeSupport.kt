package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.agent.AgentUiActionExecutor
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.blaze.BlazeGoalPlanner
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.RevylDefaults
import xyz.block.trailblaze.revyl.RevylScreenState

/**
 * Factory for wiring [BlazeGoalPlanner] to run on Revyl cloud devices.
 *
 * Blaze mode requires LLM infrastructure ([ScreenAnalyzer], [TrailblazeToolRepo],
 * [TrailblazeElementComparator]) that the host application must provide. This
 * utility bridges those host-level dependencies with the Revyl device layer
 * ([RevylTrailblazeAgent] + [RevylScreenState]).
 *
 * Usage:
 * ```
 * val cliClient = RevylCliClient()
 * cliClient.startSession(platform = "android", appUrl = "...")
 *
 * val planner = RevylBlazeSupport.createBlazeRunner(
 *   cliClient = cliClient,
 *   platform = "android",
 *   screenAnalyzer = myScreenAnalyzer,       // from your LLM config
 *   toolRepo = myToolRepo,                   // from TrailblazeToolSet
 *   elementComparator = myElementComparator,  // from your LLM config
 * )
 *
 * val result = planner.execute(BlazeState(objective = "Tap the Search tab"))
 * ```
 */
object RevylBlazeSupport {

  /**
   * Creates a [BlazeGoalPlanner] backed by Revyl cloud devices.
   *
   * Constructs [AgentUiActionExecutor] with a [RevylTrailblazeAgent] for
   * device actions and [RevylScreenState] for screenshot capture, then
   * wires them into a [BlazeGoalPlanner] ready for AI-driven exploration.
   *
   * @param cliClient Authenticated CLI client with an active session.
   * @param platform Device platform enum.
   * @param screenAnalyzer LLM-powered screen analyzer (provided by host).
   * @param toolRepo Tool repository for deserializing tool calls (provided by host).
   * @param elementComparator Element comparator for assertions (provided by host).
   * @param config Blaze exploration settings. Defaults to [BlazeConfig.DEFAULT].
   * @return A configured [BlazeGoalPlanner] targeting the Revyl cloud device.
   */
  fun createBlazeRunner(
    cliClient: RevylCliClient,
    platform: TrailblazeDevicePlatform,
    screenAnalyzer: ScreenAnalyzer,
    toolRepo: TrailblazeToolRepo,
    elementComparator: TrailblazeElementComparator,
    config: BlazeConfig = BlazeConfig.DEFAULT,
  ): BlazeGoalPlanner {
    val driverType = when (platform) {
      TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.REVYL_IOS
      else -> TrailblazeDriverType.REVYL_ANDROID
    }
    val defaultDimensions = when (platform) {
      TrailblazeDevicePlatform.IOS -> Pair(RevylDefaults.IOS_DEFAULT_WIDTH, RevylDefaults.IOS_DEFAULT_HEIGHT)
      else -> Pair(RevylDefaults.ANDROID_DEFAULT_WIDTH, RevylDefaults.ANDROID_DEFAULT_HEIGHT)
    }
    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "revyl-blaze", trailblazeDevicePlatform = platform),
      trailblazeDriverType = driverType,
      widthPixels = defaultDimensions.first,
      heightPixels = defaultDimensions.second,
    )
    val platformStr = when (platform) {
      TrailblazeDevicePlatform.IOS -> RevylCliClient.PLATFORM_IOS
      else -> RevylCliClient.PLATFORM_ANDROID
    }
    val agent = RevylTrailblazeAgent(
      cliClient = cliClient,
      platform = platformStr,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = { deviceInfo },
      sessionProvider = {
        TrailblazeSession(sessionId = SessionId("revyl-blaze"), startTime = kotlinx.datetime.Clock.System.now())
      },
    )
    val screenStateProvider = { RevylScreenState(cliClient, platformStr) }
    val executor = AgentUiActionExecutor(
      agent = agent,
      screenStateProvider = screenStateProvider,
      toolRepo = toolRepo,
      elementComparator = elementComparator,
    )
    return BlazeGoalPlanner(
      config = config,
      screenAnalyzer = screenAnalyzer,
      executor = executor,
    )
  }
}
