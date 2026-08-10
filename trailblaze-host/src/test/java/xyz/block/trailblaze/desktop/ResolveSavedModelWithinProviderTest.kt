package xyz.block.trailblaze.desktop

import org.junit.Test
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins how a persisted LLM selection survives a catalog change.
 *
 * Retiring a model id is routine — the shipped catalog tracks what providers actually serve.
 * What must not happen is a user who picked a now-retired id silently losing their LLM on
 * upgrade, which is what happens when this resolution falls through to the caller's global
 * default (`NONE` in the OSS desktop distribution).
 */
class ResolveSavedModelWithinProviderTest {

  private fun model(id: String) = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = id,
    inputCostPerOneMillionTokens = 1.0,
    outputCostPerOneMillionTokens = 1.0,
    contextLength = 128_000,
    maxOutputTokens = 8_192,
    capabilityIds = emptyList(),
  )

  private val entries = listOf(model("gpt-5.6-sol"), model("gpt-5.6-terra"), model("gpt-5.6-luna"))

  @Test
  fun `an id the provider still offers resolves to itself`() {
    assertEquals(
      "gpt-5.6-luna",
      resolveSavedModelWithinProvider(
        entries = entries,
        savedModelId = "gpt-5.6-luna",
        providerDefaultModelId = "gpt-5.6-terra",
      )?.modelId,
      "An exact match must win over the provider default — the user's pick is honored " +
        "whenever it is still available.",
    )
  }

  @Test
  fun `a retired id falls back to the provider default, not to no-LLM`() {
    assertEquals(
      "gpt-5.6-terra",
      resolveSavedModelWithinProvider(
        entries = entries,
        savedModelId = "gpt-4.1",
        providerDefaultModelId = "gpt-5.6-terra",
      )?.modelId,
      "A saved id the catalog dropped must move the user to the current default for the " +
        "provider they chose. Returning null here disables their LLM on upgrade.",
    )
  }

  @Test
  fun `no provider default leaves the caller's fallback in charge`() {
    assertNull(
      resolveSavedModelWithinProvider(
        entries = entries,
        savedModelId = "gpt-4.1",
        providerDefaultModelId = null,
      ),
      "With nothing to fall back to within the provider, this must not invent a choice — " +
        "the caller's own default decides.",
    )
  }

  @Test
  fun `a provider default the entries do not contain is not fabricated`() {
    assertNull(
      resolveSavedModelWithinProvider(
        entries = entries,
        savedModelId = "gpt-4.1",
        providerDefaultModelId = "gpt-9-not-shipped",
      ),
      "A default_model naming an id absent from the resolved entries (e.g. a workspace " +
        "config narrowed the provider) must not resolve to a model that isn't there.",
    )
  }

  @Test
  fun `an empty provider resolves to nothing`() {
    assertNull(
      resolveSavedModelWithinProvider(
        entries = emptyList(),
        savedModelId = "gpt-5.6-terra",
        providerDefaultModelId = "gpt-5.6-terra",
      ),
      "A provider offering no models has nothing to select.",
    )
  }
}
