package xyz.block.trailblaze.llm.providers

import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Sentinel [TrailblazeLlmModelList] for [TrailblazeLlmProvider.NONE] — empty entry list, no
 * concrete models. Used by distributions that want "no LLM configured" as the *literal*
 * fallback in `getCurrentLlmModel()` rather than a real provider that would otherwise
 * silently auto-claim the user's environment-variable API keys.
 *
 * The OSS desktop config wires this as both `defaultLlmModel` (via
 * [TrailblazeLlmModel.fallback] with NONE) and `defaultProviderModelList`. The
 * [getCurrentLlmModel][xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig.getCurrentLlmModel]
 * early-return on `savedProviderId == NONE.id` already shadows these in the common case;
 * keeping the fallback NONE-shaped means a corrupted settings file or an unrecognized
 * provider id resolves to NONE rather than silently falling through to a real provider.
 */
object NoneTrailblazeLlmModelList : TrailblazeLlmModelList {

  override val entries: List<TrailblazeLlmModel> = emptyList()

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.NONE
}
