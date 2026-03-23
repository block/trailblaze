package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.agent.AgentUiActionExecutor
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.blaze.BlazeGoalPlanner
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

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
   * @param platform Device platform ("ios" or "android").
   * @param screenAnalyzer LLM-powered screen analyzer (provided by host).
   * @param toolRepo Tool repository for deserializing tool calls (provided by host).
   * @param elementComparator Element comparator for assertions (provided by host).
   * @param config Blaze exploration settings. Defaults to [BlazeConfig.DEFAULT].
   * @return A configured [BlazeGoalPlanner] targeting the Revyl cloud device.
   */
  fun createBlazeRunner(
    cliClient: RevylCliClient,
    platform: String,
    screenAnalyzer: ScreenAnalyzer,
    toolRepo: TrailblazeToolRepo,
    elementComparator: TrailblazeElementComparator,
    config: BlazeConfig = BlazeConfig.DEFAULT,
  ): BlazeGoalPlanner {
    val agent = RevylTrailblazeAgent(cliClient, platform)
    val screenStateProvider = { RevylScreenState(cliClient, platform) }
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
