package xyz.block.trailblaze.toolcalls.commands

import xyz.block.trailblaze.toolcalls.toScriptedToolDescriptor
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * `findSelectorMatches` is the first tool whose selector parameter is a LIST of selectors, and
 * the descriptor builder's exclusion set was keyed on a parameter's top-level classifier —
 * `kotlin.collections.List`, not the item type. The selector grammar is self-referential
 * (`childOf`, `containsDescendants`), so a `List<TrailblazeNodeSelector>` that reached the type
 * lowering recursed without a floor and died with a `StackOverflowError` — an `Error`, which
 * [toScriptedToolDescriptor]'s `catch (Exception)` does not contain. The whole per-trailmap codegen
 * failed rather than skipping one tool.
 *
 * The generic fix and its sharp unit tests (the param IS stripped, the TS type IS an array) live in
 * `TrailblazeKoogToolTest` against fixture classes. This is the real tool standing on that fix, and
 * the property it pins is only that codegen **terminates** — because this tool deliberately carries
 * no class-level `@LLMDescription` (it is hand-curated in the SDK's `built-in-tools.ts` instead), so
 * a null return is the correct, expected answer. A `StackOverflowError` is not caught, so it fails
 * this test rather than passing as a skip.
 *
 * There was a second case here, an N=1 control over the single-selector `findMatches` tool. It went
 * when that tool did; the fixture-class coverage in `TrailblazeKoogToolTest` is what still separates
 * "the exclusion set generally" from "the list-typed parameter".
 */
class FindSelectorMatchesDescriptorTest {

  @Test
  fun `codegen skips the list-of-selectors tool instead of crashing on it`() {
    assertNull(FindSelectorMatchesTrailblazeTool::class.toScriptedToolDescriptor())
  }
}
