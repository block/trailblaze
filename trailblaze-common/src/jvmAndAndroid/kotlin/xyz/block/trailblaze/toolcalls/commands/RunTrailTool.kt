package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.io.File
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Runs all recorded steps from another .trail.yaml file in-place.
 *
 * All recorded tool calls in the referenced trail are replayed deterministically — no AI or
 * Screen Analyzer involvement. Useful for reusable setup sequences (app launch + sign in,
 * navigation to a known screen) that should precede test-specific steps.
 */
@Serializable
@TrailblazeToolClass("runTrail")
@LLMDescription(
  """
Run all recorded steps from a referenced .trail.yaml file.
Use this for deterministic setup sequences — app launch, sign-in, navigation to a known
screen — that should execute before the main test objectives. Steps run from the recording;
no LLM involvement during playback.
  """
)
data class RunTrailTool(
  @param:LLMDescription(
    "Path to the .trail.yaml file to run. Resolved against the current working directory."
  )
  val path: String,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val trailFile = File(path)
    if (!trailFile.exists()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "runTrail: trail file not found: $path",
        command = this,
      )
    }

    val trailblazeYaml = createTrailblazeYaml()
    val items = trailblazeYaml.decodeTrail(trailFile.readText())

    val recordedSteps =
      items
        .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
        .flatMap { it.promptSteps }
        .filter { it.recording != null && it.recording!!.tools.isNotEmpty() }

    if (recordedSteps.isEmpty()) {
      return TrailblazeToolResult.Success()
    }

    // Deterministic replay does not invoke LLM-based element evaluation. This stub satisfies
    // the required interface; the methods throw if unexpectedly invoked so failures surface clearly.
    val replayElementComparator =
      object : ElementComparator {
        override fun getElementValue(prompt: String): String? = null

        override fun evaluateBoolean(statement: String): BooleanAssertionTrailblazeTool =
          error("RunTrailTool: ElementComparator.evaluateBoolean should not be called during recorded replay")

        override fun evaluateString(query: String): StringEvaluationTrailblazeTool =
          error("RunTrailTool: ElementComparator.evaluateString should not be called during recorded replay")

        override fun extractNumberFromString(input: String): Double? = null
      }

    for (step in recordedSteps) {
      val tools = step.recording!!.tools.map { it.trailblazeTool }
      val agent =
        toolExecutionContext.maestroTrailblazeAgent
          ?: return TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "runTrail: no Maestro agent available — runTrail requires a Maestro-backed driver",
            command = this,
          )
      val runResult =
        agent.runTrailblazeTools(
          tools = tools,
          traceId = toolExecutionContext.traceId,
          screenState = toolExecutionContext.screenState,
          elementComparator = replayElementComparator,
          screenStateProvider = toolExecutionContext.screenStateProvider,
        )
      if (runResult.result is TrailblazeToolResult.Error) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage =
            "runTrail: step '${step.prompt}' failed in $path: " +
              "${(runResult.result as TrailblazeToolResult.Error).errorMessage}",
          command = this,
        )
      }
    }

    return TrailblazeToolResult.Success()
  }
}
