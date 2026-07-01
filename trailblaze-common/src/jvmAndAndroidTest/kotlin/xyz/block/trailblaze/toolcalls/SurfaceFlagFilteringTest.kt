package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.Serializable
import org.junit.Test
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector

/**
 * Pins the gating contract of the two tool-descriptor converters:
 *
 *  - `toKoogToolDescriptor()` gates on `surfaceToLlm` — the LLM agent toolbox only advertises
 *    `surfaceToLlm = true` tools.
 *  - `toScriptedToolDescriptor()` does NOT gate on any visibility flag. Every class-backed tool
 *    is surfaced to scripted-tool (`.ts`) authors; it returns null only when the descriptor build
 *    itself fails — an unsupported parameter shape, OR a missing `@LLMDescription` / null parameter
 *    name (both exercised below) — and that failure is absorbed, not propagated.
 *
 * The two surfaces are deliberately decoupled: hiding a tool from the LLM (where a brittle
 * text-selector tool bites when picked autonomously) must NOT hide it from an expert TS author
 * who reaches for it explicitly — the same way a hand-edited trail may use any tool. A future
 * change that re-introduces a scripted-surface visibility gate (so a `surfaceToLlm = false` tool
 * stops surfacing to scripted authoring) must regress here obviously.
 */
class SurfaceFlagFilteringTest {

  @Serializable
  @TrailblazeToolClass(name = "test_llm_visible")
  @LLMDescription("Test tool visible to the LLM and to scripted-tool typed bindings.")
  private class LlmVisibleTool : TrailblazeTool

  @Serializable
  @TrailblazeToolClass(name = "test_llm_hidden", surfaceToLlm = false)
  @LLMDescription("Test tool hidden from the LLM but still typed for scripted-tool authors.")
  private class LlmHiddenTool : TrailblazeTool

  @Test
  fun `surfaceToLlm true - surfaces in both the LLM toolbox and scripted-tool bindings`() {
    assertNotNull(LlmVisibleTool::class.toKoogToolDescriptor())
    assertNotNull(LlmVisibleTool::class.toScriptedToolDescriptor())
  }

  @Test
  fun `surfaceToLlm false - hidden from the LLM toolbox but STILL surfaces to scripted-tool bindings`() {
    assertNull(LlmHiddenTool::class.toKoogToolDescriptor())
    assertNotNull(LlmHiddenTool::class.toScriptedToolDescriptor())
  }

  @Test
  fun `tapOnElementBySelector is surfaced to scripted tools but hidden from the LLM`() {
    // Real production tool: `surfaceToLlm = false` so the LLM uses the friendlier `tap`, while
    // scripted authors can compose the selector-resolved tap explicitly. Pins that the
    // LLM-hidden tool still surfaces to scripted authoring after the scripted-surface gate's
    // removal.
    assertNotNull(TapOnByElementSelector::class.toScriptedToolDescriptor())
    assertNull(TapOnByElementSelector::class.toKoogToolDescriptor())
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Lowering-failure absorption in toScriptedToolDescriptor
  //
  // Real-world `surfaceToLlm = false` tools like `AndroidSendBroadcastTrailblazeTool` have
  // parameter shapes (`Map<String, ...>`) that `asToolType` does not know how to lower. The LLM
  // path historically hid this by returning null at the `surfaceToLlm = false` gate before
  // reaching the lowering; the scripted-tool path has no visibility gate at all, so
  // `toScriptedToolDescriptor` swallows the lowering failure rather than crashing all-trailmap
  // codegen. These tests pin that absorption.
  // ─────────────────────────────────────────────────────────────────────────────

  @Serializable
  @TrailblazeToolClass(name = "test_unsupported_map_param", surfaceToLlm = false)
  @LLMDescription("Test tool whose param shape asToolType cannot lower.")
  private class UnsupportedParamShapeTool(
    @Suppress("unused") val extras: Map<String, String>,
  ) : TrailblazeTool

  @Test
  fun `toScriptedToolDescriptor swallows asToolType IllegalArgumentException`() {
    // The Map<String, String> parameter type makes `asToolType` throw
    // IllegalArgumentException. `toScriptedToolDescriptor` must return null instead of
    // letting the exception propagate to `PerTrailmapClientDtsEmitter`'s `mapNotNull`, which
    // would crash codegen for the entire trailmap.
    assertNull(UnsupportedParamShapeTool::class.toScriptedToolDescriptor())
  }

  @TrailblazeToolClass(name = "test_no_llm_description")
  private class NoLlmDescriptionTool : TrailblazeTool

  @Test
  fun `buildToolDescriptorIgnoringSurface throws IllegalStateException when @LLMDescription missing`() {
    // Pin the precondition that the catch in `toScriptedToolDescriptor` is load-bearing
    // for IllegalStateException too — a missing @LLMDescription throws via `error(...)`,
    // and we want the codegen path to absorb that failure exactly like it absorbs Map
    // lowering, rather than crashing the whole trailmap on a single broken tool annotation.
    assertFailsWith<IllegalStateException> {
      NoLlmDescriptionTool::class.buildToolDescriptorIgnoringSurface()
    }
    // ... and toScriptedToolDescriptor catches it.
    assertNull(NoLlmDescriptionTool::class.toScriptedToolDescriptor())
  }
}
