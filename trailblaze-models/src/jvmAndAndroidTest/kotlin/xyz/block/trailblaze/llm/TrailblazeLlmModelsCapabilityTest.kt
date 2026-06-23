package xyz.block.trailblaze.llm

import ai.koog.prompt.llm.LLMCapability
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import kotlin.test.Test

/**
 * Guards the predefined model capability ids against the `"vision"` drift: the Koog vision capability
 * id is `"image"` (`LLMCapability.Vision.Image.id`), not `"vision"`. A model declaring `"vision"` reads
 * as non-vision (the id maps to nothing), which silently breaks vision-gated behavior such as the
 * screenshot attach on the Koog strategy-graph agent.
 */
class TrailblazeLlmModelsCapabilityTest {

  private val visionModels = listOf(
    TrailblazeLlmModels.GPT_4O_MINI,
    TrailblazeLlmModels.CLAUDE_HAIKU,
    TrailblazeLlmModels.GEMINI_FLASH,
    TrailblazeLlmModels.GPT_4O,
    TrailblazeLlmModels.CLAUDE_SONNET,
    TrailblazeLlmModels.GEMINI_PRO,
  )

  @Test
  fun `predefined vision models resolve to the Koog Vision Image capability`() {
    visionModels.forEach { model ->
      // Resolved capabilities (capabilityIds → LLMCapability via LlmCapabilitiesUtil); this is what
      // toKoogLlmModel() carries and what vision-gated behavior keys off.
      assertThat(model.capabilities).contains(LLMCapability.Vision.Image)
    }
  }

  @Test
  fun `predefined vision models use the Koog id 'image', never the bogus 'vision'`() {
    visionModels.forEach { model ->
      // Positive assertion against the canonical Koog id so the test follows a rename automatically.
      assertThat(model.capabilityIds).contains(LLMCapability.Vision.Image.id)
      // Negative assertion stays a literal on purpose: `"vision"` is the known-bad string we're
      // guarding against — there is no Koog constant for it (that's the whole bug).
      assertThat(model.capabilityIds).doesNotContain("vision")
    }
  }

  @Test
  fun `every predefined model's capability ids map to a real Koog capability (no bogus ids)`() {
    // Generic guard for the whole bogus-id class — catches not just `"vision"` but also the
    // `"reasoning"` id that mapped to nothing (LlmCapabilitiesUtil silently drops unmapped ids).
    // The failure message lists every "modelId: 'badId'" so a regression is immediately diagnosable.
    val bogus = TrailblazeLlmModels.ALL_MODELS.flatMap { model ->
      model.capabilityIds
        .filter { LlmCapabilitiesUtil.capabilityFromId(it) == null }
        .map { "${model.modelId}: '$it'" }
    }
    assertThat(bogus).isEmpty()
  }

  @Test
  fun `mcpSampling fallback model is vision-capable via the Koog 'image' id`() {
    // The mcpSampling fallback isn't one of the predefined constants, so it needs its own guard
    // against regressing back to the bogus `"vision"` id.
    val model = TrailblazeLlmModel.mcpSampling()
    assertThat(model.capabilities).contains(LLMCapability.Vision.Image)
    assertThat(model.capabilityIds).doesNotContain("vision")
  }
}
