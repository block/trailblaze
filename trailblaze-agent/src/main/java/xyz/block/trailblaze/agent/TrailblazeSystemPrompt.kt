package xyz.block.trailblaze.agent

import xyz.block.trailblaze.util.TemplatingUtil

/** The agent's system prompt: a fixed base, followed by a platform or app-specific section. */
object TrailblazeSystemPrompt {

  val basePrompt: String = TemplatingUtil.getResourceAsText(
    "trailblaze_base_system_prompt.md",
  )!!

  val defaultPlatformPrompt: String = TemplatingUtil.getResourceAsText(
    "trailblaze_system_prompt.md",
  )!!

  /**
   * The base prompt followed by [platformPrompt], or by [defaultPlatformPrompt] (the mobile prompt)
   * when none is given.
   */
  fun compose(
    platformPrompt: String? = null,
  ): String = buildString {
    append(basePrompt)
    append("\n\n")
    append(platformPrompt ?: defaultPlatformPrompt)
  }
}
