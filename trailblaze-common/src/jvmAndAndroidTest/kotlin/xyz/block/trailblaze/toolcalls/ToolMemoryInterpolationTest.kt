package xyz.block.trailblaze.toolcalls

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.messageContains
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.MalformedArgTokenException

/**
 * Contract tests for the dispatch-boundary memory helpers in `ToolMemoryInterpolation.kt`:
 *
 * - [interpolateMemoryInTool] — the single interpolation boundary every agent dispatch loop runs a
 *   tool through right before `execute()`. The load-bearing part of the contract is REFERENTIAL
 *   IDENTITY: callers detect "interpolation rewrote this tool" via `!==`, which drives the
 *   raw/resolved split on `TrailblazeLog.TrailblazeToolLog` — so the same-instance cases here are
 *   as important as the rewrite cases.
 * - [buildLogSafeResolvedPayload] — the scrub that keeps `rememberSensitive` values out of the
 *   persisted resolved payload.
 * - [withAuthoredCommandIdentity] — the failure-metadata swap that keeps resolved values out of
 *   LLM-facing error content.
 * - [scrubSensitiveValues] / [withAuthoredFailureContent] — the free-form-string scrub that keeps a
 *   resolved secret a tool spliced into an error message out of that same content.
 *
 * End-to-end behavior through a real agent dispatch loop is covered by
 * [xyz.block.trailblaze.ToolDispatchMemoryBoundaryTest].
 */
class ToolMemoryInterpolationTest {

  private val memory = AgentMemory().apply {
    remember("user", "sam")
    remember("domain", "example.com")
  }

  // -- interpolateMemoryInTool: rewrites --

  @Test
  fun `typed tool string fields resolve both token syntaxes`() {
    val authored = InputTextTrailblazeTool(text = "Hi {{user}} at \${domain}")

    val dispatched = interpolateMemoryInTool(authored, memory)

    assertThat(dispatched.text).isEqualTo("Hi sam at example.com")
    assertThat(dispatched).isNotSameInstanceAs(authored)
    // The authored instance is untouched — it is what toolsExecuted / logs / recordings carry.
    assertThat(authored.text).isEqualTo("Hi {{user}} at \${domain}")
  }

  @Test
  fun `unknown token stays literal when memory is non-empty`() {
    // Memory is non-empty so the same-instance short-circuit doesn't apply — this pins
    // AgentMemory's leave-literal semantics holding at the dispatch boundary: a typo'd
    // token surfaces visibly in the dispatched args instead of silently blanking.
    val dispatched = interpolateMemoryInTool(InputTextTrailblazeTool(text = "{{missing}}!"), memory)

    assertThat(dispatched.text).isEqualTo("{{missing}}!")
  }

  @Test
  fun `nested selector fields resolve too`() {
    // Pinned deliberately as an intended behavior change: selector fields
    // (`textRegex: '${ticketName}'`) interpolate on EVERY path now. Direct paths previously
    // never resolved them (only the RPC paths did) — uniform interpolation is the fix, and
    // this test is the statement that the behavior change is intended.
    val authored = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(textRegex = "\${user} tickets"),
      ),
    )

    val dispatched = interpolateMemoryInTool(authored, memory)

    assertThat(dispatched.nodeSelector!!.androidAccessibility!!.textRegex)
      .isEqualTo("sam tickets")
    assertThat(authored.nodeSelector!!.androidAccessibility!!.textRegex)
      .isEqualTo("\${user} tickets")
  }

  @Test
  fun `yaml-defined tool params resolve as a tree`() {
    val config = ToolYamlConfig(id = "myYamlTool")
    val authored = YamlDefinedTrailblazeTool(
      config = config,
      params = mapOf(
        "text" to JsonPrimitive("{{user}}"),
        "nested" to buildJsonObject { put("host", "\${domain}") },
      ),
    )

    val dispatched = interpolateMemoryInTool(authored, memory)

    assertThat(dispatched).isNotSameInstanceAs(authored)
    assertThat(dispatched.params["text"]!!.jsonPrimitive.content).isEqualTo("sam")
    assertThat(dispatched.params["nested"]!!.jsonObject["host"]!!.jsonPrimitive.content)
      .isEqualTo("example.com")
    // The config rides along unchanged — only string scalars in the params tree are rewritten.
    assertThat(dispatched.config).isSameInstanceAs(config)
    assertThat(authored.params["text"]!!.jsonPrimitive.content).isEqualTo("{{user}}")
  }

  @Test
  fun `a second pass over an already-resolved tool is a no-op`() {
    // Idempotence keeps double-interpolation safe during transition windows (e.g. a new host
    // driving an old on-device build): the second boundary sees no tokens and returns the
    // same instance.
    val once = interpolateMemoryInTool(InputTextTrailblazeTool(text = "{{user}}"), memory)

    val twice = interpolateMemoryInTool(once, memory)

    assertThat(twice).isSameInstanceAs(once)
    assertThat(twice.text).isEqualTo("sam")
  }

  // -- transition-window double interpolation (the mixed-version re-scan edge) --
  //
  // The boundary runs ONCE per dispatch in steady state, but during a mixed-version window
  // (new host driving an old on-device build, or vice versa) one dispatched instance can cross
  // TWO boundaries. For plain values that's a no-op (pinned above); the edge is a memory VALUE
  // that itself contains token-like text. These pin the actual semantics so any future change
  // (e.g. escaping injected text) is a deliberate one.

  @Test
  fun `a memory value containing token-like text is injected literally within one pass`() {
    // The {{}} pass matches against the string as it stood when the pass started, so text a
    // replacement injects is NOT rescanned by the same pass.
    val memory = AgentMemory().apply {
      remember("greeting", "hi {{name}}")
      remember("name", "sam")
    }

    val dispatched = interpolateMemoryInTool(InputTextTrailblazeTool(text = "{{greeting}}"), memory)

    assertThat(dispatched.text).isEqualTo("hi {{name}}")
  }

  @Test
  fun `dollar-brace values feed the double-brace pass within one call`() {
    // The ${} pass runs BEFORE the {{}} pass, so a ${}-injected value containing {{token}}
    // text IS resolved by the same call — one boundary pass is enough to trigger the re-scan
    // when the syntaxes differ.
    val memory = AgentMemory().apply {
      remember("greeting", "hi {{name}}")
      remember("name", "sam")
    }

    val dispatched = interpolateMemoryInTool(InputTextTrailblazeTool(text = "\${greeting}"), memory)

    assertThat(dispatched.text).isEqualTo("hi sam")
  }

  @Test
  fun `a second boundary pass resolves token-like text injected by the first`() {
    // The transition-window caveat: double interpolation is idempotent ONLY for values without
    // token-like text. A surviving {{name}} from the first pass reads as an ordinary token to
    // the second. TRAILBLAZE_DISABLE_BOUNDARY_MEMORY_INTERPOLATION is the escape hatch if a
    // pipeline hits this with real data during a mixed-version rollout.
    val memory = AgentMemory().apply {
      remember("greeting", "hi {{name}}")
      remember("name", "sam")
    }
    val afterFirstBoundary = interpolateMemoryInTool(InputTextTrailblazeTool(text = "{{greeting}}"), memory)

    val afterSecondBoundary = interpolateMemoryInTool(afterFirstBoundary, memory)

    assertThat(afterFirstBoundary.text).isEqualTo("hi {{name}}")
    assertThat(afterSecondBoundary.text).isEqualTo("hi sam")
  }

  // -- interpolateMemoryInTool: same-instance short circuits --

  @Test
  fun `token-free tool returns the same instance`() {
    val authored = InputTextTrailblazeTool(text = "plain text")

    assertThat(interpolateMemoryInTool(authored, memory)).isSameInstanceAs(authored)
  }

  @Test
  fun `empty memory returns the same instance and tokens stay literal`() {
    val authored = InputTextTrailblazeTool(text = "{{user}}")

    // Nothing remembered → nothing can resolve; the token staying literal (rather than
    // becoming "") is deliberate, so a missing `remember` step is visible in what executes.
    assertThat(interpolateMemoryInTool(authored, AgentMemory())).isSameInstanceAs(authored)
  }

  @Test
  fun `kill-switch returns the same instance`() {
    val authored = InputTextTrailblazeTool(text = "{{user}}")

    assertThat(interpolateMemoryInTool(authored, memory, disabled = true))
      .isSameInstanceAs(authored)
  }

  // -- args-only memory (no remembered variables) --
  //
  // Regression coverage: the boundary's same-instance short circuit must consult BOTH memory
  // stores. A parameterized trail with no `config.memory:` and no `remember()` calls has an
  // EMPTY `variables` map for its whole run — checking only `variables.isEmpty()` here previously
  // skipped interpolation entirely, so `{{args.x}}` never resolved at real dispatch even though
  // direct `AgentMemory` unit tests (which call `interpolateVariables` directly, bypassing this
  // boundary) never exercised the gate and so never caught it.

  @Test
  fun `args-only memory still resolves a typed tool's string field`() {
    val argsOnlyMemory = AgentMemory().apply {
      seedArgs(mapOf("recipient" to JsonPrimitive("sam")))
    }
    val authored = InputTextTrailblazeTool(text = "Hi {{args.recipient}}")

    val dispatched = interpolateMemoryInTool(authored, argsOnlyMemory)

    assertThat(dispatched.text).isEqualTo("Hi sam")
    assertThat(dispatched).isNotSameInstanceAs(authored)
  }

  @Test
  fun `args-only memory still resolves a yaml-defined tool's params tree`() {
    val argsOnlyMemory = AgentMemory().apply {
      seedArgs(mapOf("recipient" to JsonPrimitive("sam")))
    }
    val authored = YamlDefinedTrailblazeTool(
      config = ToolYamlConfig(id = "myYamlTool"),
      params = mapOf("text" to JsonPrimitive("{{args.recipient}}")),
    )

    val dispatched = interpolateMemoryInTool(authored, argsOnlyMemory)

    assertThat(dispatched.params["text"]!!.jsonPrimitive.content).isEqualTo("sam")
  }

  @Test
  fun `a whole-scalar integer arg in a typed String field dispatches its text, not the literal token`() {
    // A whole-scalar {{args.count}} substitutes the arg's NATIVE number into the encoded tree;
    // a String-typed Kotlin field can't decode an unquoted scalar, and before the text-rendered
    // retry that decode failure fell into the round-trip catch and dispatched the literal token.
    val argsOnlyMemory = AgentMemory().apply { seedArgs(mapOf("count" to JsonPrimitive(3))) }
    val authored = InputTextTrailblazeTool(text = "{{args.count}}")

    val dispatched = interpolateMemoryInTool(authored, argsOnlyMemory)

    assertThat(dispatched.text).isEqualTo("3")
  }

  @Test
  fun `a malformed args token hard-errors through the class-serializer round-trip, never dispatches as-authored`() {
    // The round-trip catch below this call site is broad on purpose (it also legitimately
    // swallows "this tool's shape can't encode"), but a malformed-token hard-error must never
    // land in that bucket — that would silently dispatch the tool with the broken token literally
    // intact instead of failing the run.
    val argsOnlyMemory = AgentMemory().apply { seedArgs(mapOf("count" to JsonPrimitive(1))) }
    val authored = InputTextTrailblazeTool(text = "{{args.count + 1}}")

    assertFailure {
      interpolateMemoryInTool(authored, argsOnlyMemory)
    }.isInstanceOf(MalformedArgTokenException::class)
      .messageContains("dotted paths only")
  }

  @Test
  fun `OtherTrailblazeTool placeholder passes through untouched`() {
    // An unresolved placeholder: whichever executor resolves it to a concrete tool re-enters a
    // dispatch boundary with that concrete tool, and resolving there keeps the raw form
    // available to that executor's log.
    val authored = OtherTrailblazeTool(
      toolName = "someDynamicTool",
      raw = buildJsonObject { put("text", "{{user}}") },
    )

    val dispatched = interpolateMemoryInTool(authored, memory)

    assertThat(dispatched).isSameInstanceAs(authored)
    assertThat(dispatched.raw["text"]!!.jsonPrimitive.content).isEqualTo("{{user}}")
  }

  @Test
  fun `RawArgumentTrailblazeTool passes through untouched`() {
    // Scripted tools hold live runtime handles a serializer round-trip can't reconstruct; the
    // boundary must never rebuild them. They resolve their args JSON at the engine boundary
    // themselves (the drift test's ALLOWED_SELF_INTERPOLATING_FILES).
    val authored = FakeScriptedTool(
      instanceToolName = "myScriptedTool",
      rawToolArguments = buildJsonObject { put("text", "{{user}}") },
    )

    assertThat(interpolateMemoryInTool(authored, memory)).isSameInstanceAs(authored)
  }

  @Test
  fun `tool without a serializer passes through as authored`() {
    // The class-serializer round-trip can't encode this shape; the boundary dispatches
    // as-authored instead of failing the run.
    val authored = NonSerializableTool(text = "{{user}}")

    val dispatched = interpolateMemoryInTool(authored, memory)

    assertThat(dispatched).isSameInstanceAs(authored)
    assertThat(dispatched.text).isEqualTo("{{user}}")
  }

  // -- buildLogSafeResolvedPayload --

  @Test
  fun `sensitive tokens stay literal while non-sensitive tokens resolve`() {
    val memory = AgentMemory().apply {
      remember("user", "sam")
      rememberSensitive("pin", "9999")
    }
    val rawPayload = payload("inputText") {
      put("text", "{{user}}:{{pin}}")
    }

    val resolved = buildLogSafeResolvedPayload(rawPayload, memory)

    assertThat(resolved.raw["text"]!!.jsonPrimitive.content).isEqualTo("sam:{{pin}}")
    assertThat(resolved.toolName).isEqualTo("inputText")
  }

  @Test
  fun `dollar-brace sensitive token canonicalizes to double-brace form`() {
    val memory = AgentMemory().apply { rememberSensitive("pin", "9999") }
    val rawPayload = payload("inputText") { put("text", "\${pin}") }

    val resolved = buildLogSafeResolvedPayload(rawPayload, memory)

    assertThat(resolved.raw["text"]!!.jsonPrimitive.content).isEqualTo("{{pin}}")
  }

  @Test
  fun `token-free payload returns the same instance`() {
    val rawPayload = payload("inputText") { put("text", "plain") }

    // Callers use `!=` between raw and resolved to elide the raw payload from the log —
    // a no-op scrub must therefore hand back the identical payload.
    assertThat(buildLogSafeResolvedPayload(rawPayload, memory)).isSameInstanceAs(rawPayload)
  }

  @Test
  fun `empty memory returns the same payload instance`() {
    val rawPayload = payload("inputText") { put("text", "{{user}}") }

    assertThat(buildLogSafeResolvedPayload(rawPayload, AgentMemory())).isSameInstanceAs(rawPayload)
  }

  @Test
  fun `args tokens resolve in the log-safe payload, matching what was dispatched`() {
    // Args are non-sensitive by design; the persisted resolved payload must show the value the
    // driver actually received — including when args are the ONLY seeded store (no variables).
    val argsOnlyMemory = AgentMemory().apply {
      seedArgs(mapOf("recipient" to JsonPrimitive("sam")))
    }
    val rawPayload = payload("inputText") { put("text", "Hi {{args.recipient}}") }

    val resolved = buildLogSafeResolvedPayload(rawPayload, argsOnlyMemory)

    assertThat(resolved.raw["text"]!!.jsonPrimitive.content).isEqualTo("Hi sam")
  }

  @Test
  fun `an arg that laundered in a sensitive value stays a token in the log-safe payload`() {
    // --secret redaction must survive the args namespace: an arg whose value resolved FROM
    // sensitive memory ('{{memory.pin}}') dispatches with the real value, but the persisted
    // payload keeps the token form — same contract as sensitive variables.
    val memory = AgentMemory().apply {
      rememberSensitive("pin", "9876")
      seedArgs(mapOf("vaultCode" to JsonPrimitive("{{memory.pin}}")))
    }
    val rawPayload = payload("inputText") { put("text", "{{args.vaultCode}}") }

    val resolved = buildLogSafeResolvedPayload(rawPayload, memory)

    assertThat(resolved.raw["text"]!!.jsonPrimitive.content).isEqualTo("{{args.vaultCode}}")
    // The dispatch boundary, by contrast, resolves the real value.
    val dispatched = interpolateMemoryInTool(InputTextTrailblazeTool(text = "{{args.vaultCode}}"), memory)
    assertThat(dispatched.text).isEqualTo("9876")
  }

  // -- withAuthoredCommandIdentity --

  @Test
  fun `swaps a resolved same-class command back to the authored instance`() {
    val authored = InputTextTrailblazeTool(text = "{{pin}}")
    val resolved = InputTextTrailblazeTool(text = "9999")
    val error = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "boom",
      command = resolved,
    )

    val swapped = error.withAuthoredCommandIdentity(authored)

    assertThat(swapped.command).isSameInstanceAs(authored)
    assertThat(swapped.errorMessage).isEqualTo("boom")
  }

  @Test
  fun `leaves a command of a different class untouched`() {
    // A wrapper tool reporting its INNER tool as the failing command is not the boundary's
    // rewrite — swapping it would misattribute the failure.
    val authored = InputTextTrailblazeTool(text = "{{pin}}")
    val error = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "boom",
      command = NonSerializableTool(text = "inner"),
    )

    assertThat(error.withAuthoredCommandIdentity(authored)).isSameInstanceAs(error)
  }

  @Test
  fun `leaves success results and command-less errors untouched`() {
    val authored = InputTextTrailblazeTool(text = "{{pin}}")
    val success = TrailblazeToolResult.Success(message = "ok")
    val commandless = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "boom")

    assertThat(success.withAuthoredCommandIdentity(authored)).isSameInstanceAs(success)
    assertThat(commandless.withAuthoredCommandIdentity(authored)).isSameInstanceAs(commandless)
    assertThat(commandless.command).isNull()
  }

  @Test
  fun `leaves an already-authored command untouched`() {
    // No interpolation happened → the tool stamped the authored instance itself.
    val authored = InputTextTrailblazeTool(text = "plain")
    val error = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "boom",
      command = authored,
    )

    assertThat(error.withAuthoredCommandIdentity(authored)).isSameInstanceAs(error)
  }

  // -- scrubSensitiveValues --

  @Test
  fun `replaces a resolved sensitive value with its token`() {
    val memory = AgentMemory().apply {
      remember("user", "sam")
      rememberSensitive("pin", "9999")
    }

    val scrubbed = scrubSensitiveValues("Failed for user sam with pin 9999", memory)

    // Only the SENSITIVE value is masked back to its token; a non-sensitive remembered value
    // (`sam`) is a legitimate diagnostic detail and stays.
    assertThat(scrubbed).isEqualTo("Failed for user sam with pin {{pin}}")
  }

  @Test
  fun `is a no-op when there are no sensitive keys`() {
    val memory = AgentMemory().apply { remember("user", "sam") }
    val text = "Failed for user sam"

    assertThat(scrubSensitiveValues(text, memory)).isSameInstanceAs(text)
  }

  @Test
  fun `does not replace on an empty sensitive value`() {
    // A sensitive key marked before its value lands (mark-before-store) has an empty/absent value;
    // replacing "" would corrupt the message, so it's skipped.
    val memory = AgentMemory().apply { markSensitive("pin") }
    val text = "Failed with pin 9999"

    assertThat(scrubSensitiveValues(text, memory)).isEqualTo("Failed with pin 9999")
  }

  // -- withAuthoredFailureContent --

  @Test
  fun `swaps the command and scrubs the resolved secret from the error message`() {
    val memory = AgentMemory().apply { rememberSensitive("pin", "9999") }
    val authored = InputTextTrailblazeTool(text = "{{pin}}")
    val resolved = InputTextTrailblazeTool(text = "9999")
    val error = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "Failed to find element for prompt: 9999",
      command = resolved,
    )

    val safe = error.withAuthoredFailureContent(authored, memory)

    assertThat(safe.command).isSameInstanceAs(authored)
    assertThat(safe.errorMessage).isEqualTo("Failed to find element for prompt: {{pin}}")
  }

  @Test
  fun `returns the identity-restored result unchanged when the message has no secret`() {
    val memory = AgentMemory().apply { rememberSensitive("pin", "9999") }
    val authored = InputTextTrailblazeTool(text = "{{pin}}")
    val resolved = InputTextTrailblazeTool(text = "9999")
    val error = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "boom",
      command = resolved,
    )

    val safe = error.withAuthoredFailureContent(authored, memory)

    assertThat(safe.command).isSameInstanceAs(authored)
    assertThat(safe.errorMessage).isEqualTo("boom")
  }

  @Test
  fun `leaves a success result untouched`() {
    val memory = AgentMemory().apply { rememberSensitive("pin", "9999") }
    val success = TrailblazeToolResult.Success(message = "ok")

    assertThat(success.withAuthoredFailureContent(InputTextTrailblazeTool(text = "{{pin}}"), memory))
      .isSameInstanceAs(success)
  }

  // -- fixtures --

  private fun payload(toolName: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    OtherTrailblazeTool(toolName = toolName, raw = buildJsonObject(build))

  /** Minimal [RawArgumentTrailblazeTool] — pins the type-dispatch contract, not scripted-tool behavior. */
  private class FakeScriptedTool(
    override val instanceToolName: String,
    override val rawToolArguments: JsonObject,
  ) : RawArgumentTrailblazeTool

  /** Deliberately NOT `@Serializable`: drives the round-trip-failure pass-through path. */
  private class NonSerializableTool(val text: String) : TrailblazeTool
}
