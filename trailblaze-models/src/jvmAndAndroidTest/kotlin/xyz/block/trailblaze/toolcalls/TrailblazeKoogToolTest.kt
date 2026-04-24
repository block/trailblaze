package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolParameterType
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.parseKoogParameterType
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
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
}
