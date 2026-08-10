package xyz.block.trailblaze.llm.config

import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaContextWindowTest {

  // Both values below are pinned as literals on purpose. They are external contracts — the
  // env var is a name users type, and the default is what reaches the Ollama wire — and
  // every other assertion here compares against the symbols, so a typo in either constant
  // would otherwise satisfy this suite. `apiCheck` doesn't catch it either: the baseline
  // records the field signatures, not the values.
  @Test
  fun `the requested default and env var name are the documented ones`() {
    assertEquals(65536L, OllamaContextWindow.DEFAULT_NUM_CTX)
    assertEquals("TRAILBLAZE_OLLAMA_NUM_CTX", OllamaContextWindow.ENV_VAR)
  }

  @Test
  fun `unset override resolves to the default`() {
    assertEquals(OllamaContextWindow.DEFAULT_NUM_CTX, OllamaContextWindow.resolveNumCtx(null))
  }

  @Test
  fun `a positive override is honored`() {
    assertEquals(32768, OllamaContextWindow.resolveNumCtx("32768"))
  }

  @Test
  fun `surrounding whitespace is tolerated`() {
    assertEquals(32768, OllamaContextWindow.resolveNumCtx(" 32768 "))
  }

  @Test
  fun `malformed and non-positive overrides fall back to the default`() {
    for (raw in listOf("", "  ", "abc", "64K", "0", "-1")) {
      assertEquals(
        OllamaContextWindow.DEFAULT_NUM_CTX,
        OllamaContextWindow.resolveNumCtx(raw),
        "raw override '$raw' must fall back to the default",
      )
    }
  }
}
