package xyz.block.trailblaze.examples.clock

import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.rules.ExamplesAndroidTrailblazeRule
import xyz.block.trailblaze.llm.providers.OllamaTrailblazeLlmModelList

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 */
class ClockTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRule(
    trailblazeLlmModel = OllamaTrailblazeLlmModelList.OLLAMA_QWEN3_VL_2B
  )

  @Ignore
  @Test
  fun setAnAlarmRecorded() = trailblazeRule.runFromAsset()

  @Ignore
  @Test
  fun setAnAlarmAi() = trailblazeRule.runFromAsset()

  @Test
  fun clickBackAi() = trailblazeRule.runFromAsset()
}
