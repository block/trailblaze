package xyz.block.trailblaze.scripting.callback

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Test
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool

/**
 * Focused unit tests for [JsScriptingCallbackArgumentValidator] and the
 * [TrailblazeToolRepo.expectedArgumentKeysFor] resolution chain it relies on. Locks down
 * branches the dispatcher / binding round-trip tests can't reach directly — non-object root
 * payloads, malformed JSON, unknown tool names, `@SerialName`-renamed properties, and the
 * dynamic-tool registration branch. The fixes for these are load-bearing: a refactor that
 * accidentally tightens the skip paths would turn legitimate calls into runtime rejections
 * across every scripted-tool transport.
 */
class JsScriptingCallbackArgumentValidatorTest {

  // Tool with a `@SerialName`-renamed property. Locks down that the validator compares the
  // wire (serialized) name, not the Kotlin property name — otherwise authors using the
  // typed `wire_key` would see false rejections, and authors using the Kotlin name (which
  // wouldn't actually decode) would slip past the gate.
  @Serializable
  @TrailblazeToolClass("renamed_key_tool")
  private data class RenamedKeyTool(
    @SerialName("wire_key") val kotlinKey: String,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
      TrailblazeToolResult.Success(message = kotlinKey)
  }

  private fun repoWith(vararg classes: kotlin.reflect.KClass<out TrailblazeTool>): TrailblazeToolRepo =
    TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "validator-test-toolset",
        toolClasses = classes.toSet(),
        yamlToolNames = emptySet(),
      ),
    )

  private fun repoWithYaml(vararg yamlNames: String): TrailblazeToolRepo =
    TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "validator-test-yaml-toolset",
        toolClasses = emptySet(),
        yamlToolNames = yamlNames.map { ToolName(it) }.toSet(),
      ),
    )

  @Test
  fun `validate returns null when payload is JSON null`() {
    // Non-object root payloads are downstream-deserializer territory — the validator should
    // pass them through unmodified rather than synthesizing a confusing unknown-key error.
    val repo = repoWith(InputTextTrailblazeTool::class)
    val result = JsScriptingCallbackArgumentValidator.validate(repo, "inputText", "null")
    assertThat(result).isNull()
  }

  @Test
  fun `validate returns null when payload is a JSON array`() {
    // Same rationale as the null case: bound the validator's responsibility to top-level
    // key inspection; let the deserializer report shape errors.
    val repo = repoWith(InputTextTrailblazeTool::class)
    val result = JsScriptingCallbackArgumentValidator.validate(repo, "inputText", """["a","b"]""")
    assertThat(result).isNull()
  }

  @Test
  fun `validate returns null when payload is malformed JSON`() {
    // Malformed JSON should fall through to the downstream deserializer, which produces a
    // decode-failure error message. The validator catching the SerializationException
    // silently is the load-bearing behavior here.
    val repo = repoWith(InputTextTrailblazeTool::class)
    val result = JsScriptingCallbackArgumentValidator.validate(repo, "inputText", """{"text":""")
    assertThat(result).isNull()
  }

  @Test
  fun `validate returns null when the tool name is unknown`() {
    // No schema → no contract to enforce. The downstream resolver in [TrailblazeToolRepo]
    // will produce its own "tool not found" error, which is more informative than a
    // synthetic unknown-key message.
    val repo = repoWith(InputTextTrailblazeTool::class)
    val result = JsScriptingCallbackArgumentValidator.validate(repo, "no_such_tool", """{"foo":1}""")
    assertThat(result).isNull()
  }

  @Test
  fun `validate compares wire names not Kotlin names for SerialName-renamed properties`() {
    // The wire name `wire_key` is what `kotlinx.serialization` actually expects when
    // decoding — `getElementName(0)` returns the `@SerialName` value. The Kotlin property
    // name `kotlinKey` would fail to decode (no matching wire key), so it must be flagged
    // as unknown here so the author sees a clear error rather than a deserializer crash.
    val repo = repoWith(RenamedKeyTool::class)

    val wireOk = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "renamed_key_tool",
      """{"wire_key":"hello"}""",
    )
    assertThat(wireOk).isNull()

    val kotlinNameRejected = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "renamed_key_tool",
      """{"kotlinKey":"hello"}""",
    )
    assertThat(kotlinNameRejected).isNotNull()
    assertThat(kotlinNameRejected!!).contains("\"kotlinKey\"")
    assertThat(kotlinNameRejected).contains("wire_key")
  }

  @Test
  fun `validate enforces empty-arg contract for class-backed tools with no properties`() {
    // A `@TrailblazeToolClass` with no constructor args has an exhaustively-declared empty
    // schema — any incoming key is unknown. Pairs with the YAML strict-on-empty fix; both
    // author-controlled empty schemas must reject extra keys, only dynamic / subprocess
    // schemas get the lenient "skip on empty" treatment.
    val repo = repoWith(NoArgTool::class)

    val emptyOk = JsScriptingCallbackArgumentValidator.validate(repo, "no_arg_tool", "{}")
    assertThat(emptyOk).isNull()

    val rejected = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "no_arg_tool",
      """{"unexpected":"value"}""",
    )
    assertThat(rejected).isNotNull()
    assertThat(rejected!!).contains("\"unexpected\"")
    assertThat(rejected).contains("accepts no arguments")
  }

  @Test
  fun `expectedArgumentKeysFor surfaces declared params for dynamic tool registrations`() {
    // The dispatcher's round-trip tests register dynamic tools but never assert against the
    // repo's introspection helper directly. Cover the dynamic branch so a refactor that
    // re-routes the lookup can't silently regress the unknown-key gate for subprocess MCP.
    val repo = repoWith()
    val descriptor = TrailblazeToolDescriptor(
      name = "dynamic_probe",
      description = "Tool registered through a DynamicTrailblazeToolRegistration.",
      requiredParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "url", type = "string"),
      ),
      optionalParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "reasoning", type = "string"),
      ),
    )
    repo.addDynamicTools(
      listOf(
        object : DynamicTrailblazeToolRegistration {
          override val name: ToolName = ToolName("dynamic_probe")
          override val trailblazeDescriptor: TrailblazeToolDescriptor = descriptor
          override fun buildKoogTool(
            trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
          ): TrailblazeKoogTool<out TrailblazeTool> =
            error("buildKoogTool not exercised by the validator test path")
          override fun decodeToolCall(argumentsJson: String): TrailblazeTool =
            error("decodeToolCall not exercised by the validator test path")
        },
      ),
    )

    val keys = repo.expectedArgumentKeysFor("dynamic_probe")
    assertThat(keys).isNotNull()
    val rejected = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "dynamic_probe",
      """{"url":"https://example.com","element":"hint"}""",
    )
    assertThat(rejected).isNotNull()
    assertThat(rejected!!).contains("\"element\"")
    assertThat(rejected).contains("url")
    assertThat(rejected).contains("reasoning")
  }

  @Test
  fun `validate enforces empty-arg contract for YAML tools with empty parameters list`() {
    // The other half of the strict-empty fix: YAML tools that exhaustively declare
    // `parameters: []` (the shipped `pressBack.tool.yaml` is the canonical example) must
    // reject extra keys, not skip the gate like dynamic tools do. The class-backed
    // `NoArgTool` test pins the same behavior for the Kotlin path; this test pins it for
    // the YAML path so a refactor that re-introduces `.ifEmpty { null }` on the YAML
    // branches can't slip through unnoticed.
    val repo = repoWithYaml("pressBack")

    val emptyOk = JsScriptingCallbackArgumentValidator.validate(repo, "pressBack", "{}")
    assertThat(emptyOk).isNull()

    val rejected = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "pressBack",
      """{"element":"hint"}""",
    )
    assertThat(rejected).isNotNull()
    assertThat(rejected!!).contains("\"element\"")
    assertThat(rejected).contains("accepts no arguments")
    assertThat(rejected).contains("client.tools.pressBack")
  }

  @Test
  fun `expectedArgumentKeysFor falls back to global class registry when tool is unregistered in the session`() {
    // The unfiltered fallback path is reachable through `toolCallToTrailblazeToolUnfiltered`
    // (scripted-bundle composition of host-side helpers) but never through the standard
    // session resolution chain. Without this test, a refactor that drops the global lookup
    // would silently turn the gate into a no-op for those composition calls. `inputText` is
    // globally registered via `@TrailblazeToolClass`, so resolving it on an empty session
    // forces the lookup through the global-class fallback branch.
    val emptySessionRepo = repoWith() // no tool classes registered for this session

    val keys = emptySessionRepo.expectedArgumentKeysFor("inputText")
    assertThat(keys).isNotNull()

    val rejected = JsScriptingCallbackArgumentValidator.validate(
      emptySessionRepo,
      "inputText",
      """{"text":"hello","element":"hint"}""",
    )
    assertThat(rejected).isNotNull()
    assertThat(rejected!!).contains("\"element\"")
    assertThat(rejected).contains("text")
  }

  @Serializable
  @TrailblazeToolClass("no_arg_tool")
  private class NoArgTool : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
      TrailblazeToolResult.Success(message = "ran no_arg_tool")
  }

  @Test
  fun `validate accepts dynamic tool payload that omits an optional parameter`() {
    // Regression for #3261: a scripted tool declaring `required: false` for a parameter
    // must accept invocations that omit that key. The handler should receive `undefined`
    // for the missing key on the JS side — matching the TS `?:` semantics emitted by the
    // per-pack `client.d.ts` generator.
    val repo = repoWithDynamicTool(
      name = "search_tool",
      requiredParams = mapOf("query" to "string"),
      optionalParams = mapOf("fuzzy" to "boolean"),
    )

    val omittedOk = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "search_tool",
      """{"query":"einstein"}""",
    )
    assertThat(omittedOk).isNull()

    // Sanity: passing the optional explicitly is also fine.
    val bothOk = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "search_tool",
      """{"query":"einstein","fuzzy":false}""",
    )
    assertThat(bothOk).isNull()
  }

  @Test
  fun `validate rejects dynamic tool payload that omits a required parameter`() {
    // The other half of the #3261 contract: `required: true` (the YAML default) must
    // surface a directed error when the caller forgets the arg. Without this check the
    // dispatch would route into the JS handler with `args.query === undefined`, the
    // handler would throw an opaque "missing argument" runtime error somewhere deep in
    // the body, and the trail-author debugging surface area would be the bundle stack
    // trace rather than the tool's schema. The validator gives the author an actionable
    // message before any handler code runs.
    val repo = repoWithDynamicTool(
      name = "search_tool",
      requiredParams = mapOf("query" to "string"),
      optionalParams = mapOf("fuzzy" to "boolean"),
    )

    val rejected = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "search_tool",
      """{"fuzzy":true}""",
    )
    assertThat(rejected).isNotNull()
    assertThat(rejected!!).contains("\"query\"")
    assertThat(rejected).contains("Required: query")
    assertThat(rejected).contains("client.tools.search_tool")
  }

  @Test
  fun `validate rejects unknown keys before flagging missing required keys`() {
    // Order rationale documented on `JsScriptingCallbackArgumentValidator.validate`:
    // when the caller misspells a required key, the typo is closer to the actual fix
    // than the resulting absence is. Surface the misspelling first.
    val repo = repoWithDynamicTool(
      name = "search_tool",
      requiredParams = mapOf("query" to "string"),
      optionalParams = emptyMap(),
    )

    val rejected = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "search_tool",
      """{"querry":"einstein"}""",
    )
    assertThat(rejected).isNotNull()
    assertThat(rejected!!).contains("\"querry\"")
    // Should NOT mention missing-required — the typo path takes precedence.
    assertThat(rejected).doesNotContain("Required:")
  }

  @Test
  fun `requiredArgumentKeysFor surfaces declared required params for dynamic tools`() {
    // Pins the resolution-chain wiring: the validator's missing-required gate calls into
    // `TrailblazeToolRepo.requiredArgumentKeysFor`, which mirrors the dynamic-tool branch
    // in `expectedArgumentKeysFor`. Without this test, a refactor that re-routes the
    // dynamic lookup (e.g. through a different snapshot accessor) could silently regress
    // the contract for subprocess MCP scripted tools.
    val repo = repoWithDynamicTool(
      name = "search_tool",
      requiredParams = mapOf("query" to "string", "lang" to "string"),
      optionalParams = mapOf("fuzzy" to "boolean"),
    )
    val required = repo.requiredArgumentKeysFor("search_tool")
    assertThat(required).isNotNull()
    assertThat(required!!).contains("query")
    assertThat(required).contains("lang")
    assertThat(required).doesNotContain("fuzzy")
  }

  // Class-backed tool with a defaulted optional property. Locks down the
  // `classRequiredKeys` introspection path: kotlinx-serialization marks `optional` as
  // `isElementOptional = true` because it has a default value, so the validator must
  // accept calls that omit it. Pairs with the dynamic-tool tests above so both
  // resolution tiers (dynamic descriptor vs. `SerialDescriptor` introspection) have
  // coverage for the #3261 contract.
  @Serializable
  @TrailblazeToolClass("mixed_required_class_tool")
  private data class MixedRequiredClassTool(
    val mandatory: String,
    val optional: String = "default",
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
      TrailblazeToolResult.Success(message = "$mandatory|$optional")
  }

  @Test
  fun `requiredArgumentKeysFor treats class-backed property with default as optional`() {
    // Verifies the `SerialDescriptor.isElementOptional` claim load-bearing for class-backed
    // tools — a Kotlin property with a default value must surface as optional, never as
    // required. A regression here would re-introduce the silent rejection the validator's
    // missing-required gate is meant to prevent for tools whose schemas come from the
    // class declaration rather than a YAML/JSON-Schema authored shape.
    val repo = repoWith(MixedRequiredClassTool::class)
    val required = repo.requiredArgumentKeysFor("mixed_required_class_tool")
    assertThat(required).isNotNull()
    assertThat(required!!).contains("mandatory")
    assertThat(required).doesNotContain("optional")
  }

  @Test
  fun `validate accepts class-backed tool payload that omits a defaulted property`() {
    // End-to-end on the class-backed path: a payload that omits the defaulted property
    // must reach the deserializer (which knows how to fill the default), not the
    // missing-required rejection. Without this, every class-backed tool that relies on
    // Kotlin defaults would have its callers forced to spell every field — exactly the
    // workaround #3261 set out to delete.
    val repo = repoWith(MixedRequiredClassTool::class)
    val ok = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "mixed_required_class_tool",
      """{"mandatory":"value"}""",
    )
    assertThat(ok).isNull()
  }

  @Test
  fun `validate rejects class-backed tool payload that omits a non-defaulted property`() {
    // Inverse of the previous test: omitting the non-defaulted property must surface a
    // directed error rather than letting the deserializer's `MissingFieldException` bubble
    // up. The validator's pre-deserialization message names the missing key + points at
    // `client.tools.<name>`; the deserializer's exception would not.
    val repo = repoWith(MixedRequiredClassTool::class)
    val rejected = JsScriptingCallbackArgumentValidator.validate(
      repo,
      "mixed_required_class_tool",
      """{"optional":"value"}""",
    )
    assertThat(rejected).isNotNull()
    assertThat(rejected!!).contains("\"mandatory\"")
    assertThat(rejected).contains("Required: mandatory")
  }

  /**
   * Helper that registers a dynamic tool with split required/optional parameters. Each
   * entry's value is the parameter's declared JSON-Schema type (`string`, `boolean`,
   * `integer`, …) — keep this honest so the fixture doesn't lie about its schema even
   * though the validator only checks keys today.
   */
  private fun repoWithDynamicTool(
    name: String,
    requiredParams: Map<String, String>,
    optionalParams: Map<String, String>,
  ): TrailblazeToolRepo {
    val repo = repoWith()
    val descriptor = TrailblazeToolDescriptor(
      name = name,
      description = "Dynamic tool fixture for required-field validator tests.",
      requiredParameters = requiredParams.map { (n, t) -> TrailblazeToolParameterDescriptor(name = n, type = t) },
      optionalParameters = optionalParams.map { (n, t) -> TrailblazeToolParameterDescriptor(name = n, type = t) },
    )
    repo.addDynamicTools(
      listOf(
        object : DynamicTrailblazeToolRegistration {
          override val name: ToolName = ToolName(name)
          override val trailblazeDescriptor: TrailblazeToolDescriptor = descriptor
          override fun buildKoogTool(
            trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
          ): TrailblazeKoogTool<out TrailblazeTool> =
            error("buildKoogTool not exercised by the validator test path")
          override fun decodeToolCall(argumentsJson: String): TrailblazeTool =
            error("decodeToolCall not exercised by the validator test path")
        },
      ),
    )
    return repo
  }
}
