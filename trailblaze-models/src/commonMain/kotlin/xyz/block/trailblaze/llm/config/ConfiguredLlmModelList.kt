package xyz.block.trailblaze.llm.config

import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * A [TrailblazeLlmModelList] backed by resolved YAML configuration.
 * Bridges the config system with existing consumers of [TrailblazeLlmModelList].
 */
class ConfiguredLlmModelList(
  override val provider: TrailblazeLlmProvider,
  override val entries: List<TrailblazeLlmModel>,
) : TrailblazeLlmModelList
