package xyz.block.trailblaze.toolcalls

/**
 * A [TrailblazeTool] that includes an optional reasoning field for the LLM to explain its
 * decision-making.
 *
 * Tools that implement this interface will have a `reasoning` parameter in their LLM tool schema,
 * allowing the model to articulate *why* it is performing an action. This is valuable for:
 * - **Debugging**: Understanding the LLM's decision chain when tests fail.
 * - **Logging & Reports**: Surfacing reasoning in test reports for human review.
 * - **Prompt tuning**: Observing whether the model's understanding matches expectations.
 *
 * The `reasoning` field is nullable with a default of `null`, making it fully backward-compatible
 * with existing recordings and non-reasoning LLM calls.
 *
 * The purpose of the `reasoning` parameter is explained to the LLM via the system prompt rather
 * than per-tool `@LLMDescription` annotations. This avoids repeating the same description across
 * every tool schema and saves context window tokens.
 */
interface ReasoningTrailblazeTool : TrailblazeTool {
  val reasoning: String?
}
