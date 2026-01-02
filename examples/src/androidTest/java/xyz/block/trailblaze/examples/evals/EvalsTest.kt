package xyz.block.trailblaze.examples.evals

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.rules.ExamplesAndroidTrailblazeRule

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 */
class EvalsTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRule()

  @Test
  fun clickBackAi() = trailblazeRule.runFromAsset("evals/click-back")
}
