package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.contains
import xyz.block.trailblaze.devices.TrailblazeDriverType
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.Status
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.VerificationStep
import kotlin.test.Test

/**
 * Unit tests for [resolveKoogObjectiveResult] — the pure mapping from the Koog agent's terminal
 * `objectiveStatus` outcome to a [TrailblazeToolResult]. Exercises every branch without standing up
 * the Koog graph.
 */
class KoogStrategyGraphHostRunnerTest {

  @Test
  fun `COMPLETED maps to Success carrying the explanation`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.COMPLETED,
      explanation = "All goals met",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("All goals met")
  }

  @Test
  fun `IN_PROGRESS maps to Success`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.IN_PROGRESS,
      explanation = "still going",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `FAILED maps to an error mentioning the explanation`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.FAILED,
      explanation = "could not find the button",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .contains("could not find the button")
  }

  @Test
  fun `null outcome (finished without objectiveStatus) maps to an error, not a hollow pass`() {
    val result = resolveKoogObjectiveResult(
      outcome = null,
      explanation = null,
      finalMessage = "agent stopped here",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    // Surfaces the agent's final message so the failure is debuggable.
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .contains("agent stopped here")
  }

  @Test
  fun `null explanation falls back to the final message`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.COMPLETED,
      explanation = null,
      finalMessage = "fell back to this",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("fell back to this")
  }

  // ---------------------------------------------------------------------------------------------
  // refreshKoogToolSurface — runtime toolset switching under the Koog strategy-graph agent.
  //
  // These exercise the device-free core of the mid-run switch: when a setActiveToolSets-style
  // ConfigTrailblazeTool changes the active toolsets, the live Koog ToolRegistry must gain the
  // newly-active tool (so it can dispatch) AND the returned advertised descriptors must reflect
  // the new surface (so the LLM sees it). Neither the dispatcher nor the context provider is
  // invoked here — the refresh only rebuilds the registry view and reads descriptors.
  // ---------------------------------------------------------------------------------------------

  /** Dispatcher/context-provider stand-ins; refreshKoogToolSurface never invokes them. */
  private val noopDispatcher: suspend (TrailblazeTool) -> String = { "unused" }
  private val unusedContextProvider: () -> TrailblazeToolExecutionContext =
    { error("context provider should not be invoked by refreshKoogToolSurface") }

  /**
   * Two-entry catalog: an always-on `core` carrying the completion tool (modeling the real `meta`
   * always-enabled toolset) and one opt-in `typing` toolset that gates the class-backed
   * [InputTextTrailblazeTool]. Mirrors the real meta/core + opt-in split without coupling the test
   * to the full default catalog's driver logic.
   */
  private fun typingCatalog() = listOf(
    ToolSetCatalogEntry(
      id = "core",
      description = "always-on core",
      toolClasses = setOf(ObjectiveStatusTrailblazeTool::class),
      alwaysEnabled = true,
    ),
    ToolSetCatalogEntry(
      id = "typing",
      description = "text entry tools",
      toolClasses = setOf(InputTextTrailblazeTool::class),
      alwaysEnabled = false,
    ),
  )

  @Test
  fun `refreshKoogToolSurface activates a gated tool — registers it for dispatch and advertises it`() {
    val gatedName = InputTextTrailblazeTool::class.toolName().toolName
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = typingCatalog())
    val liveRegistry = repo.asToolRegistry(
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )

    // Before activation: the gated tool is neither registered nor advertised.
    assertThat(liveRegistry.getToolOrNull(gatedName)).isNull()

    // The LLM enables the toolset (this is what the intercepted ConfigTrailblazeTool does).
    repo.setActiveToolSets(listOf("typing"))

    val advertised = refreshKoogToolSurface(
      toolRepo = repo,
      liveRegistry = liveRegistry,
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )

    // Execution: the live registry — the same instance the running agent's environment resolves
    // against — can now find the newly-active tool by name.
    assertThat(liveRegistry.getToolOrNull(gatedName)).isNotNull()
    // Advertisement: the returned descriptors include the newly-active tool...
    assertThat(advertised.map { it.name }).contains(gatedName)
    // ...and never drop the always-on completion tool.
    assertThat(advertised.map { it.name }).contains(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
  }

  @Test
  fun `refreshKoogToolSurface after a reset narrows advertisement but keeps the tool registered`() {
    val gatedName = InputTextTrailblazeTool::class.toolName().toolName
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = typingCatalog())
    val liveRegistry = repo.asToolRegistry(
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )

    // Activate, then reset back to core only (setActiveToolSets([])).
    repo.setActiveToolSets(listOf("typing"))
    refreshKoogToolSurface(repo, liveRegistry, noopDispatcher, unusedContextProvider)
    repo.setActiveToolSets(emptyList())
    val narrowed = refreshKoogToolSurface(repo, liveRegistry, noopDispatcher, unusedContextProvider)

    // Advertisement narrows — the reset hid the tool from the LLM again.
    assertThat(narrowed.map { it.name }).doesNotContain(gatedName)
    // But the registry retains it: ToolRegistry has no remove, and an unadvertised-but-registered
    // tool is harmless (the LLM never calls a tool it can't see).
    assertThat(liveRegistry.getToolOrNull(gatedName)).isNotNull()
  }

  @Test
  fun `refreshKoogToolSurface is idempotent — re-running with no change re-yields the same surface`() {
    val gatedName = InputTextTrailblazeTool::class.toolName().toolName
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = typingCatalog())
    val liveRegistry = repo.asToolRegistry(
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )
    repo.setActiveToolSets(listOf("typing"))

    val first = refreshKoogToolSurface(repo, liveRegistry, noopDispatcher, unusedContextProvider)
    val firstRegistrySize = liveRegistry.tools.size
    val second = refreshKoogToolSurface(repo, liveRegistry, noopDispatcher, unusedContextProvider)

    // Same advertised name set both times.
    assertThat(second.map { it.name }.toSet()).isEqualTo(first.map { it.name }.toSet())
    assertThat(second.map { it.name }).contains(gatedName)
    // The registry top-up only adds names it doesn't already have, so a no-op refresh can't grow
    // the live registry with duplicates.
    assertThat(liveRegistry.tools.size).isEqualTo(firstRegistrySize)
  }

  // ---------------------------------------------------------------------------------------------
  // verifyScopedAdvertisedTools — scope a verification block to assertion/observation tools so the
  // Koog agent can't scroll/tap on a verify step and pollute state for a following step.
  // ---------------------------------------------------------------------------------------------

  /**
   * core (objectiveStatus, always-on), a `verification` toolset (so [TrailblazeToolRepo]'s
   * `verifyTools` resolves), and a non-verification `typing` toolset whose tool must NOT leak into a
   * verify step's advertised surface.
   */
  private fun verifyScopingCatalog() = listOf(
    ToolSetCatalogEntry(
      id = "core",
      description = "always-on core",
      toolClasses = setOf(ObjectiveStatusTrailblazeTool::class),
      alwaysEnabled = true,
    ),
    ToolSetCatalogEntry(
      id = "verification",
      description = "assertion tools",
      toolClasses = setOf(AssertVisibleBySelectorTrailblazeTool::class),
      alwaysEnabled = true,
    ),
    ToolSetCatalogEntry(
      id = "typing",
      description = "non-verification tool that must not leak into a verify step",
      toolClasses = setOf(InputTextTrailblazeTool::class),
      alwaysEnabled = true,
    ),
  )

  /** Verify-scoping only applies on the Android on-device drivers; default to accessibility. */
  private fun verifyRepo(driver: TrailblazeDriverType? = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY) =
    TrailblazeToolRepo.withDynamicToolSets(catalog = verifyScopingCatalog(), driverType = driver)

  @Test
  fun `verifyScopedAdvertisedTools scopes a verification step to verify tools plus objectiveStatus`() {
    val repo = verifyRepo()

    val scoped = verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "the title is visible")), repo)

    assertThat(scoped).isNotNull()
    val names = scoped!!.map { it.name }
    // Delegates to the same scoped surface the legacy/V3 runners advertise for a verify step.
    assertThat(names.toSet()).isEqualTo(repo.getToolDescriptorsForStep(VerificationStep(verify = "x")).map { it.name }.toSet())
    // objectiveStatus stays advertised so the forced-tool graph can still terminate the step...
    assertThat(names).contains(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
    // ...but a non-verification, state-mutating tool (here `inputText`) is NOT advertised — that's
    // the contract: a verify step can't mutate state (type/scroll/tap), only assert/observe.
    assertThat(names).doesNotContain(InputTextTrailblazeTool::class.toolName().toolName)
  }

  @Test
  fun `verifyScopedAdvertisedTools also scopes on the instrumentation driver`() {
    // Both members of VERIFY_SCOPE_DRIVERS scope identically; cover the instrumentation one too.
    val repo = verifyRepo(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)
    val scoped = verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "the title is visible")), repo)
    assertThat(scoped).isNotNull()
    assertThat(scoped!!.map { it.name }).contains(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
    assertThat(scoped.map { it.name }).doesNotContain(InputTextTrailblazeTool::class.toolName().toolName)
  }

  @Test
  fun `verifyScopedAdvertisedTools returns null for a direction step (full surface)`() {
    assertThat(verifyScopedAdvertisedTools(listOf(DirectionStep(step = "tap the login button")), verifyRepo())).isNull()
  }

  @Test
  fun `verifyScopedAdvertisedTools returns null for a mixed block`() {
    val mixed = listOf(VerificationStep(verify = "title visible"), DirectionStep(step = "tap"))
    assertThat(verifyScopedAdvertisedTools(mixed, verifyRepo())).isNull()
  }

  @Test
  fun `verifyScopedAdvertisedTools returns null for an empty block`() {
    assertThat(verifyScopedAdvertisedTools(emptyList(), verifyRepo())).isNull()
  }

  @Test
  fun `verifyScopedAdvertisedTools returns null on a non-mobile driver (preserves driver-specific verify tools)`() {
    // Playwright/web (and Revyl/iOS) build their verify path from a driver-specific verification
    // toolset that getToolDescriptorsForStep doesn't return; scoping there would drop those tools, so
    // a verify step keeps the full surface. Same for an unknown (null) driver.
    val webRepo = verifyRepo(TrailblazeDriverType.PLAYWRIGHT_NATIVE)
    assertThat(verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "the heading is visible")), webRepo)).isNull()

    val unknownDriverRepo = verifyRepo(driver = null)
    assertThat(verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "x")), unknownDriverRepo)).isNull()
  }

  // ---------------------------------------------------------------------------------------------
  // renderRememberedValuesSection — surface non-sensitive memory into the system prompt.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `renderRememberedValuesSection is empty when there are no variables`() {
    assertThat(renderRememberedValuesSection(emptyMap(), emptySet())).isEqualTo("")
  }

  @Test
  fun `renderRememberedValuesSection lists non-sensitive values sorted by key`() {
    val section = renderRememberedValuesSection(
      variables = mapOf("orderId" to "A-100", "city" to "Oakland"),
      sensitiveKeys = emptySet(),
    )
    assertThat(section).isEqualTo(
      "\n\n## Remembered values\n" +
        "Values remembered from earlier steps — use them to inform your actions; do not re-derive them:\n" +
        "- city: \"Oakland\"\n" +
        "- orderId: \"A-100\"",
    )
  }

  @Test
  fun `renderRememberedValuesSection omits sensitive keys (still available for interpolation, not the LLM)`() {
    val section = renderRememberedValuesSection(
      variables = mapOf("orderId" to "A-100", "pin" to "1234"),
      sensitiveKeys = setOf("pin"),
    )
    assertThat(section).contains("- orderId: \"A-100\"")
    assertThat(section).doesNotContain("1234")
    assertThat(section).doesNotContain("pin")
  }

  @Test
  fun `renderRememberedValuesSection is empty when every value is sensitive`() {
    assertThat(renderRememberedValuesSection(mapOf("pin" to "1234"), setOf("pin"))).isEqualTo("")
  }

  @Test
  fun `renderRememberedValuesSection escapes newlines, quotes, and backslashes so a value can't inject prompt structure`() {
    val section = renderRememberedValuesSection(mapOf("k" to "a\nb \"c\" \\d"), emptySet())
    assertThat(section).doesNotContain("a\nb") // no raw newline mid-value (would break the bullet / allow injection)
    assertThat(section).contains("a\\nb") // newline escaped
    assertThat(section).contains("\\\"c\\\"") // quotes escaped
    assertThat(section).contains("\\\\d") // backslash escaped
  }

  @Test
  fun `renderRememberedValuesSection truncates over-long values`() {
    val section = renderRememberedValuesSection(mapOf("k" to "x".repeat(250)), emptySet())
    assertThat(section).contains("…")
    assertThat(section).doesNotContain("x".repeat(201)) // capped at 200 chars
  }

  @Test
  fun `renderRememberedValuesSection caps the number of entries with an overflow note`() {
    val many = (1..60).associate { "k%02d".format(it) to "v$it" }
    val section = renderRememberedValuesSection(many, emptySet())
    assertThat(section).contains("(10 more value(s) not shown)")
    // 50 values shown + 1 overflow note bullet.
    assertThat(section.lines().count { it.startsWith("- ") }).isEqualTo(51)
  }

  @Test
  fun `renderRememberedValuesSection does not truncate a value exactly at the limit`() {
    val exactly200 = "x".repeat(200)
    val section = renderRememberedValuesSection(mapOf("k" to exactly200), emptySet())
    assertThat(section).contains("\"$exactly200\"") // full value, quoted
    assertThat(section).doesNotContain("…") // no ellipsis at the boundary
  }

  @Test
  fun `renderRememberedValuesSection renders an empty-string value cleanly`() {
    assertThat(renderRememberedValuesSection(mapOf("k" to ""), emptySet())).contains("- k: \"\"")
  }

  @Test
  fun `renderRememberedValuesSection escapes the key too so a key can't inject prompt structure`() {
    val section = renderRememberedValuesSection(mapOf("a\n## Evil" to "v"), emptySet())
    assertThat(section).doesNotContain("a\n## Evil") // key newline escaped, so no fake header
    assertThat(section).contains("a\\n## Evil")
  }
}
