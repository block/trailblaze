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
}
