package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.LLMDescription
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.parseKoogParameterType
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogParameterTypePreservingComposites
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import kotlin.reflect.KClass
import kotlin.test.Test

/**
 * Directly pins the strict-vs-lenient policy split of the shared
 * [TrailblazeKoogTool.Companion.parseKoogParameterType] — the single decision point every
 * tool source (class-backed, YAML-defined, subprocess MCP) now routes through. Without a
 * test at this layer, an inverted `strict` argument at any call site would pass all indirect
 * tests silently.
 */
class TrailblazeKoogToolTest {

  @Test fun `known primitive type strings map the same regardless of strict mode`() {
    listOf(true, false).forEach { strict ->
      assertThat(parseKoogParameterType("string", strict)).isEqualTo(ToolParameterType.String)
      assertThat(parseKoogParameterType("integer", strict)).isEqualTo(ToolParameterType.Integer)
      assertThat(parseKoogParameterType("int", strict)).isEqualTo(ToolParameterType.Integer)
      assertThat(parseKoogParameterType("long", strict)).isEqualTo(ToolParameterType.Integer)
      assertThat(parseKoogParameterType("number", strict)).isEqualTo(ToolParameterType.Float)
      assertThat(parseKoogParameterType("float", strict)).isEqualTo(ToolParameterType.Float)
      assertThat(parseKoogParameterType("double", strict)).isEqualTo(ToolParameterType.Float)
      assertThat(parseKoogParameterType("boolean", strict)).isEqualTo(ToolParameterType.Boolean)
      assertThat(parseKoogParameterType("bool", strict)).isEqualTo(ToolParameterType.Boolean)
    }
  }

  @Test fun `case-insensitive match`() {
    assertThat(parseKoogParameterType("  STRING ", strict = true)).isEqualTo(ToolParameterType.String)
    assertThat(parseKoogParameterType("Integer", strict = true)).isEqualTo(ToolParameterType.Integer)
  }

  @Test fun `strict mode errors on an unknown type string`() {
    assertFailure { parseKoogParameterType("array", strict = true) }
      .messageContains("Unsupported tool parameter type 'array'")
    assertFailure { parseKoogParameterType("object", strict = true) }
      .messageContains("Supported: string, integer, number, boolean")
  }

  @Test fun `lenient mode falls back to String for unknown type strings`() {
    // Runtime-discovered MCP schemas can legitimately advertise array / object / null etc.;
    // the subprocess path uses strict=false so registration survives those instead of
    // aborting the session.
    assertThat(parseKoogParameterType("array", strict = false)).isEqualTo(ToolParameterType.String)
    assertThat(parseKoogParameterType("object", strict = false)).isEqualTo(ToolParameterType.String)
    assertThat(parseKoogParameterType("null", strict = false)).isEqualTo(ToolParameterType.String)
    assertThat(parseKoogParameterType("arbitrary-nonsense", strict = false)).isEqualTo(ToolParameterType.String)
  }

  @Test fun `toKoogToolDescriptor strict true surfaces bad parameter types loudly`() {
    val descriptor = TrailblazeToolDescriptor(
      name = "x",
      description = "test",
      requiredParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "p", type = "mystery", description = "oops"),
      ),
      optionalParameters = emptyList(),
    )
    assertFailure { descriptor.toKoogToolDescriptor(strict = true) }
      .messageContains("Unsupported tool parameter type 'mystery'")
  }

  @Test fun `toKoogToolDescriptor strict false preserves name + description and flattens unknown type`() {
    val descriptor = TrailblazeToolDescriptor(
      name = "subprocess_tool",
      description = "from an MCP server",
      requiredParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "payload", type = "object", description = "free-form payload"),
      ),
      optionalParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "retries", type = "integer", description = null),
      ),
    )
    val koog = descriptor.toKoogToolDescriptor(strict = false)
    assertThat(koog.name).isEqualTo("subprocess_tool")
    assertThat(koog.description).isEqualTo("from an MCP server")
    assertThat(koog.requiredParameters.map { it.name }).containsExactly("payload")
    assertThat(koog.requiredParameters.single().type).isEqualTo(ToolParameterType.String) // unknown → String
    assertThat(koog.optionalParameters.map { it.name }).containsExactly("retries")
    assertThat(koog.optionalParameters.single().type).isEqualTo(ToolParameterType.Integer) // known preserved
  }

  /**
   * Pins that an enum-typed Koog parameter surfaces its allowed entries on the Trailblaze
   * descriptor side as `validValues`. The recording Tool Palette routes its widget choice on
   * this field — non-null means "render a dropdown of these values", null means "render a
   * free-text input". Without this round-trip, a Koog enum collapsed to `type = "enum"` with
   * no values, leaving the dialog with nothing to drop down to.
   */
  @Test fun `toTrailblazeToolDescriptor preserves enum entries as validValues`() {
    val koog = ToolDescriptor(
      name = "press",
      description = "Press a direction",
      requiredParameters = listOf(
        ToolParameterDescriptor(
          name = "direction",
          description = "which direction",
          type = ToolParameterType.Enum(arrayOf("UP", "DOWN", "LEFT", "RIGHT")),
        ),
        ToolParameterDescriptor(
          name = "label",
          description = "free text",
          type = ToolParameterType.String,
        ),
      ),
      optionalParameters = emptyList(),
    )

    val descriptor = koog.toTrailblazeToolDescriptor()
    val direction = descriptor.requiredParameters.single { it.name == "direction" }
    val label = descriptor.requiredParameters.single { it.name == "label" }

    assertThat(direction.validValues).isNotNull()
      .containsExactly("UP", "DOWN", "LEFT", "RIGHT")
    // Non-enum parameters must NOT spuriously gain validValues — null vs non-null is the
    // signal the Tool Palette routes on, and the dialog should fall through to a text field
    // for plain Strings rather than rendering an empty dropdown.
    assertThat(label.validValues).isNull()
  }

  /**
   * The reverse direction of the round-trip the test above pins: a Trailblaze descriptor whose
   * parameter carries `validValues` must lower to a Koog [ToolParameterType.Enum] so the LLM-facing
   * function-call schema constrains the argument to those values (`KoogToMcpExt.fillJsonSchema`
   * renders `ToolParameterType.Enum` as `{"type":"string","enum":[…]}`).
   *
   * This is the downstream half of the scripted-tool enum fix: a `.ts` tool's `"UP" | "DOWN" | …`
   * union lowers to a JSON-Schema `{ "type": "string", "enum": [...] }`, which the descriptor
   * builders capture as `type = "string"` + `validValues`. Keying the Koog type off `validValues`
   * (not the `"string"` type label) is what makes the enum reach the LLM. Pinned for BOTH strict
   * modes since scripted tools register `strict = false` and YAML tools `strict = true`.
   */
  @Test fun `toKoogToolDescriptor lowers validValues to a Koog enum regardless of the type string`() {
    listOf(true, false).forEach { strict ->
      val descriptor = TrailblazeToolDescriptor(
        name = "directional_swipe",
        description = "Swipe",
        requiredParameters = listOf(
          // type = "string" with validValues set — exactly the shape the scripted/MCP descriptor
          // builders produce for a JSON-Schema enum. The "string" label must NOT win over the enum.
          TrailblazeToolParameterDescriptor(
            name = "direction",
            type = "string",
            description = "which direction",
            validValues = listOf("UP", "DOWN", "LEFT", "RIGHT"),
          ),
        ),
        optionalParameters = listOf(
          TrailblazeToolParameterDescriptor(name = "swipeOnElementText", type = "string", description = null),
        ),
      )

      val koog = descriptor.toKoogToolDescriptor(strict = strict)
      val direction = koog.requiredParameters.single { it.name == "direction" }
      assertThat(direction.type).isEqualTo(ToolParameterType.Enum(arrayOf("UP", "DOWN", "LEFT", "RIGHT")))
      // A plain string param (no validValues) must stay a String — no spurious enum.
      assertThat(koog.optionalParameters.single { it.name == "swipeOnElementText" }.type)
        .isEqualTo(ToolParameterType.String)
    }
  }

  /**
   * The sampling-source descriptor path (`KoogLlmSamplingSource`, `LocalLlmSamplingSource`,
   * `InnerLoopScreenAnalyzer`) builds its Koog parameter type via
   * [toKoogParameterTypePreservingComposites]. It must also honor `validValues` even when the
   * `type` string is `"string"` (the JSON-Schema-enum shape), not only when it's the literal
   * `"ENUM"` mirror — otherwise scripted/MCP enums would silently degrade to free-text on that path.
   */
  @Test fun `toKoogParameterTypePreservingComposites emits an enum from validValues even when type is string`() {
    val fromStringTypedEnum = TrailblazeToolParameterDescriptor(
      name = "direction",
      type = "string",
      validValues = listOf("UP", "DOWN"),
    ).toKoogParameterTypePreservingComposites()
    assertThat(fromStringTypedEnum).isEqualTo(ToolParameterType.Enum(arrayOf("UP", "DOWN")))

    // The legacy "ENUM" type label keeps working too.
    val fromEnumLabel = TrailblazeToolParameterDescriptor(
      name = "direction",
      type = "ENUM",
      validValues = listOf("LEFT", "RIGHT"),
    ).toKoogParameterTypePreservingComposites()
    assertThat(fromEnumLabel).isEqualTo(ToolParameterType.Enum(arrayOf("LEFT", "RIGHT")))

    // No validValues → plain string, no spurious empty enum.
    val plain = TrailblazeToolParameterDescriptor(name = "label", type = "string")
      .toKoogParameterTypePreservingComposites()
    assertThat(plain).isEqualTo(ToolParameterType.String)
  }

  // ---- selectorParamsForTs: recovers the selector params the Koog descriptor build strips ----
  //
  // These params are excluded from the descriptor (excludedParameterTypes) because their
  // self-referential grammar overflows Koog's lowering; selectorParamsForTs re-derives them typed
  // against the generated selectors.ts so the trail-recording validator can type-check recordings.

  @LLMDescription("Tap the element resolved by a selector.")
  @TrailblazeToolClass("requiredNodeSelectorTool")
  private data class RequiredNodeSelectorTool(
    @LLMDescription("The node selector identifying the element to tap.")
    val nodeSelector: TrailblazeNodeSelector,
    val longPress: Boolean = false,
  ) : TrailblazeTool

  @LLMDescription("Assert the element resolved by a selector is visible.")
  @TrailblazeToolClass("optionalNodeSelectorTool")
  private data class OptionalNodeSelectorTool(
    val nodeSelector: TrailblazeNodeSelector? = null,
  ) : TrailblazeTool

  @LLMDescription("Carries both the legacy and node selector.")
  @TrailblazeToolClass("bothSelectorsTool")
  private data class BothSelectorsTool(
    val selector: TrailblazeElementSelector? = null,
    val nodeSelector: TrailblazeNodeSelector? = null,
  ) : TrailblazeTool

  @LLMDescription("A tool with no selector params.")
  @TrailblazeToolClass("noSelectorTool")
  private data class NoSelectorTool(
    val text: String,
  ) : TrailblazeTool

  @Test fun `selectorParamsForTs types a required nodeSelector as non-optional TrailblazeNodeSelector`() {
    val p = RequiredNodeSelectorTool::class.selectorParamsForTs().single()
    assertThat(p.name).isEqualTo("nodeSelector")
    assertThat(p.tsType).isEqualTo("TrailblazeNodeSelector")
    assertThat(p.optional).isFalse()
    // Param-level @LLMDescription is surfaced as JSDoc on the emitted field.
    assertThat(p.description).isEqualTo("The node selector identifying the element to tap.")
  }

  @Test fun `selectorParamsForTs marks a nullable nodeSelector optional`() {
    val p = OptionalNodeSelectorTool::class.selectorParamsForTs().single()
    assertThat(p.name).isEqualTo("nodeSelector")
    assertThat(p.optional).isTrue()
  }

  @Test fun `selectorParamsForTs types the legacy element selector as unknown and always optional`() {
    val params = BothSelectorsTool::class.selectorParamsForTs()
    // Declaration order preserved; both selector types recovered.
    assertThat(params.map { it.name }).containsExactly("selector", "nodeSelector")
    val legacy = params.single { it.name == "selector" }
    assertThat(legacy.tsType).isEqualTo("unknown")
    assertThat(legacy.optional).isTrue()
    // Falls back to a default description when the param carries no @LLMDescription.
    assertThat(legacy.description).isNotNull()
    assertThat(params.single { it.name == "nodeSelector" }.tsType).isEqualTo("TrailblazeNodeSelector")
  }

  @Test fun `selectorParamsForTs returns empty for a tool with no selector params`() {
    assertThat(NoSelectorTool::class.selectorParamsForTs()).isEmpty()
  }

  // ---- A selector param can be a LIST of selectors ----
  //
  // The exclusion used to read only a param's top-level classifier, so `List<TrailblazeNodeSelector>`
  // read as `kotlin.collections.List` and was NOT stripped — asToolType's List branch then recursed
  // into the self-referential selector grammar and died with a StackOverflowError. That is an Error,
  // not an Exception, so toScriptedToolDescriptor's catch does not absorb it and per-trailmap codegen
  // crashes outright instead of skipping one tool.

  @LLMDescription("Resolve several selectors against one capture.")
  @TrailblazeToolClass("listNodeSelectorTool")
  private data class ListNodeSelectorTool(
    @LLMDescription("The selectors to resolve.")
    val selectors: List<TrailblazeNodeSelector>,
    val timeoutMs: Long? = null,
  ) : TrailblazeTool

  @LLMDescription("Carries a list of legacy selectors.")
  @TrailblazeToolClass("listElementSelectorTool")
  private data class ListElementSelectorTool(
    val selectors: List<TrailblazeElementSelector> = emptyList(),
  ) : TrailblazeTool

  @Test fun `a list of selectors is stripped from the descriptor rather than overflowing the stack`() {
    val descriptor = ListNodeSelectorTool::class.buildToolDescriptorIgnoringSurface()
    val paramNames = (descriptor.requiredParameters + descriptor.optionalParameters).map { it.name }
    // Stripped, so the recursive grammar never reaches the lowering — and the non-selector params
    // still flow through, which is what makes this a strip rather than a skip of the whole tool.
    assertThat(paramNames).containsExactly("timeoutMs")
  }

  @Test fun `the scripted-tool surface keeps a list-of-selectors tool instead of dying on it`() {
    // The production codegen path. It catches Exception, so this assertion is only meaningful
    // because a StackOverflowError would escape it and fail the test rather than return null.
    assertThat(ListNodeSelectorTool::class.toScriptedToolDescriptor()).isNotNull()
  }

  @Test fun `selectorParamsForTs re-emits a list of selectors as an array type`() {
    val p = ListNodeSelectorTool::class.selectorParamsForTs().single()
    assertThat(p.name).isEqualTo("selectors")
    // Scalar `TrailblazeNodeSelector` here would make every recorded call to the tool read as a
    // type error in the generated surface.
    assertThat(p.tsType).isEqualTo("TrailblazeNodeSelector[]")
    assertThat(p.optional).isFalse()
    assertThat(p.description).isEqualTo("The selectors to resolve.")

    val legacy = ListElementSelectorTool::class.selectorParamsForTs().single()
    assertThat(legacy.tsType).isEqualTo("unknown[]")
  }

  // ---- withSelectorParamsRestored: the same stripped params, for human-facing tool help ----
  //
  // A lookup that describes one tool to a person ("toolbox(name=tapOn)", "trailblaze tool tapOn
  // --help") has the opposite need from a toolbox the model picks from: a selector-only tool
  // otherwise describes as requiring nothing at all, and its rendered example cannot supply the
  // one argument it does not work without.

  private fun KClass<out TrailblazeTool>.helpDescriptor() =
    buildToolDescriptorIgnoringSurface().toTrailblazeToolDescriptor().withSelectorParamsRestored(this)

  @Test fun `withSelectorParamsRestored puts a required selector back as an OBJECT`() {
    val descriptor = RequiredNodeSelectorTool::class.helpDescriptor()

    val selector = descriptor.requiredParameters.single()
    assertThat(selector.name).isEqualTo("nodeSelector")
    // Flat `OBJECT`, not the expanded grammar — expanding it is what overflows the lowering. Also
    // matches `ToolParameterType.Object.name`, so a restored param reads like a lowered one.
    assertThat(selector.type).isEqualTo("OBJECT")
    assertThat(selector.description).isEqualTo("The node selector identifying the element to tap.")
    // Restores; does not replace. The params that survived stripping are still there.
    assertThat(descriptor.optionalParameters.map { it.name }).containsExactly("longPress")
  }

  @Test fun `withSelectorParamsRestored requires a node selector that Kotlin declares nullable`() {
    val descriptor = OptionalNodeSelectorTool::class.helpDescriptor()

    // `nodeSelector: TrailblazeNodeSelector? = null` is a deserialization concession for trails
    // recorded before the field existed, not permission to omit it: every such tool in the tree
    // rejects null at execution. Listing it as optional would document a call that always fails.
    assertThat(descriptor.requiredParameters.map { it.name }).containsExactly("nodeSelector")
    assertThat(descriptor.optionalParameters).isEmpty()
  }

  @Test fun `withSelectorParamsRestored describes a list of selectors as an ARRAY`() {
    val descriptor = ListNodeSelectorTool::class.helpDescriptor()

    val selectors = descriptor.requiredParameters.single()
    assertThat(selectors.name).isEqualTo("selectors")
    // `OBJECT` here would tell the reader to write a single mapping where a sequence goes.
    assertThat(selectors.type).isEqualTo("ARRAY")
  }

  @Test fun `withSelectorParamsRestored never asks a caller to fill in the legacy selector`() {
    val descriptor = BothSelectorsTool::class.helpDescriptor()

    // The modern selector is what a caller should supply, even on a tool that still carries both.
    assertThat(descriptor.requiredParameters.map { it.name }).containsExactly("nodeSelector")
    val legacy = descriptor.optionalParameters.single { it.name == "selector" }
    // Deprecated, so it is described but never presented as something to supply, and it says so
    // even though the tool itself documents nothing.
    assertThat(legacy.description).isNotNull()
  }

  @Test fun `withSelectorParamsRestored leaves a tool with no selector untouched`() {
    val plain = NoSelectorTool::class.buildToolDescriptorIgnoringSurface().toTrailblazeToolDescriptor()

    assertThat(plain.withSelectorParamsRestored(NoSelectorTool::class)).isEqualTo(plain)
  }
}
