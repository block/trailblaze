package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.Serializable
import org.junit.Test
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector

/**
 * Pins the independence of [TrailblazeToolClass.surfaceToLlm] and
 * [TrailblazeToolClass.surfaceToScriptedTools]:
 *
 *  - `toKoogToolDescriptor()` gates on `surfaceToLlm` only.
 *  - `toScriptedToolDescriptor()` gates on `surfaceToScriptedTools` only.
 *
 * Both default to true. Three non-default combinations are exercised here so a future
 * change that re-conflates the two flags (a single `if` over the wrong field, an
 * accidental copy-paste in the gating block) regresses obviously rather than only
 * showing up downstream in `PerTrailmapClientDtsEmitter` or the agent toolbox composition.
 */
class SurfaceFlagFilteringTest {

  @Serializable
  @TrailblazeToolClass(name = "test_both_surfaces")
  @LLMDescription("Test tool visible to both the LLM and scripted-tool typed bindings.")
  private class BothSurfacesTool : TrailblazeTool

  @Serializable
  @TrailblazeToolClass(name = "test_llm_only", surfaceToScriptedTools = false)
  @LLMDescription("Test tool the LLM sees but scripted-tool authors don't.")
  private class LlmOnlyTool : TrailblazeTool

  @Serializable
  @TrailblazeToolClass(name = "test_scripted_only", surfaceToLlm = false)
  @LLMDescription("Test tool hidden from the LLM but typed for scripted-tool authors.")
  private class ScriptedOnlyTool : TrailblazeTool

  @Serializable
  @TrailblazeToolClass(name = "test_neither", surfaceToLlm = false, surfaceToScriptedTools = false)
  @LLMDescription("Test tool hidden from both surfaces — internal dispatcher only.")
  private class NeitherSurfaceTool : TrailblazeTool

  @Test
  fun `both true - surfaces in both LLM toolbox and scripted-tool bindings`() {
    assertNotNull(BothSurfacesTool::class.toKoogToolDescriptor())
    assertNotNull(BothSurfacesTool::class.toScriptedToolDescriptor())
  }

  @Test
  fun `surfaceToLlm only - surfaces in LLM toolbox but NOT scripted-tool bindings`() {
    assertNotNull(LlmOnlyTool::class.toKoogToolDescriptor())
    assertNull(LlmOnlyTool::class.toScriptedToolDescriptor())
  }

  @Test
  fun `surfaceToScriptedTools only - surfaces in scripted-tool bindings but NOT LLM toolbox`() {
    assertNull(ScriptedOnlyTool::class.toKoogToolDescriptor())
    assertNotNull(ScriptedOnlyTool::class.toScriptedToolDescriptor())
  }

  @Test
  fun `both false - hidden from both LLM toolbox and scripted-tool bindings`() {
    assertNull(NeitherSurfaceTool::class.toKoogToolDescriptor())
    assertNull(NeitherSurfaceTool::class.toScriptedToolDescriptor())
  }

  @Test
  fun `tapOnElementBySelector is surfaced to scripted tools but hidden from the LLM`() {
    // Regression for the real production flip in PR #3853: `tapOnElementBySelector` moved from
    // surfaceToScriptedTools=false to true so scripted authors can compose the selector-resolved
    // tap, while staying hidden from the LLM (which uses the friendlier `tap`). A future blanket
    // re-default that re-conflates the flags must not silently un-surface it.
    assertNotNull(TapOnByElementSelector::class.toScriptedToolDescriptor())
    assertNull(TapOnByElementSelector::class.toKoogToolDescriptor())
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Lowering-failure absorption in toScriptedToolDescriptor
  //
  // Real-world `surfaceToLlm=false, surfaceToScriptedTools=true` tools like
  // `AndroidSendBroadcastTrailblazeTool` have parameter shapes (`Map<String, ...>`) that
  // `asToolType` does not know how to lower. The LLM path historically hid this by
  // returning null at the `surfaceToLlm = false` gate before reaching the lowering; the
  // scripted-tool path cannot, so `toScriptedToolDescriptor` swallows the failure rather
  // than crashing all-trailmap codegen. These tests pin both halves of that contract: the
  // swallow happens for lowering failures, AND a tool with `surfaceToScriptedTools = true`
  // but no other issues still produces a descriptor.
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
