package xyz.block.trailblaze.toolcalls

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared "did you mean" matcher behind every surface that can reject a tool name.
 *
 * Two properties carry the feature: a name that IS close gets named, and a name that is NOT close
 * gets nothing. The second is the one worth guarding — a suggestion the reader will go try and
 * find equally absent is worse than the bare "not found" it replaced.
 */
class ToolNameSuggestionsTest {

  private val catalog = listOf(
    "tap",
    "tapOnPoint",
    "inputText",
    "swipe",
    "launchApp",
    "assertVisible",
  )

  @Test
  fun `the yaml dialect alias points at the real tool`() {
    // `trailblaze tool tapOn --help` is the reported case: `tapOn` runs (the YAML decoder knows
    // it) but has no descriptor to look up, so the lookup denies a name that works.
    assertEquals(listOf("tap", "tapOnPoint"), ToolNameSuggestions.suggestionsFor("tapOn", catalog))
  }

  @Test
  fun `a one character typo is repaired`() {
    assertEquals(listOf("swipe"), ToolNameSuggestions.suggestionsFor("swip", catalog))
  }

  @Test
  fun `a name nothing resembles gets no guess`() {
    assertEquals(emptyList(), ToolNameSuggestions.suggestionsFor("frobnicate", catalog))
  }

  @Test
  fun `suggestions are capped so the message stays readable`() {
    val crowded = List(10) { "tapVariant$it" }

    val suggestions = ToolNameSuggestions.suggestionsFor("tap", crowded)

    assertEquals(ToolNameSuggestions.MAX_SUGGESTIONS, suggestions.size)
  }

  @Test
  fun `a duplicated candidate is only offered once`() {
    // Callers concatenate several name universes (registered tools, YAML aliases, scripted tools)
    // and the same name appears in more than one of them.
    val suggestions = ToolNameSuggestions.suggestionsFor("tapOn", listOf("tap", "tap", "tap"))

    assertEquals(listOf("tap"), suggestions)
  }

  @Test
  fun `the did you mean clause is ready to concatenate onto a rejection`() {
    assertEquals(" Did you mean 'swipe'?", ToolNameSuggestions.didYouMeanSuffix("swip", catalog))
    assertEquals(
      " Did you mean 'tap', 'tapOnPoint'?",
      ToolNameSuggestions.didYouMeanSuffix("tapOn", catalog),
    )
    // Empty, not " Did you mean ?" — the caller concatenates unconditionally.
    assertEquals("", ToolNameSuggestions.didYouMeanSuffix("frobnicate", catalog))
  }

  @Test
  fun `matching ignores case`() {
    assertTrue(ToolNameSuggestions.isHighConfidenceMatch("INPUTTEXT", "inputText"))
  }
}
