package xyz.block.trailblaze.toolcalls

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
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
