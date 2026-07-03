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
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
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

  // ---------------------------------------------------------------------------------------------
  // verifyScopedAdvertisedTools — scope a verification block to assertion/observation tools so the
  // Koog agent can't scroll/tap on a verify step and pollute state for a following step.
  // ---------------------------------------------------------------------------------------------

  /**
   * A multi-driver catalog modeling the real verification-toolset layout, so verify-step scoping can
   * be exercised PER driver. Each driver builds its verify surface from a different verification
   * toolset, scoped by `compatibleDriverTypes` (the YAML `drivers:` list):
   *  - `verification` — generic, Android on-device + iOS host (`assertVisible`).
   *  - `web_verification` — Playwright drivers only (`assertEquals` standing in for `web_verifyTextVisible`).
   *  - `revyl_verification` — Revyl drivers only (`assertNotVisibleWithText` standing in for `revyl_assert`).
   *  - `compose_verification` — Compose only (reuses `assertVisible`; only the roll-out gate is asserted for Compose).
   * Plus always-on `core` (objectiveStatus) and a non-verification `typing` toolset whose tool must
   * NEVER leak into a verify step's advertised surface.
   *
   * All stand-in verify tools are `surfaceToLlm = true` so they actually appear in the advertised
   * descriptors (a `surfaceToLlm = false` tool yields no descriptor, leaving an objectiveStatus-only
   * surface that the hardened gate treats as "no real verify tool" → full surface).
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
      description = "generic assertion tools (android on-device + ios host)",
      toolClasses = setOf(AssertVisibleTrailblazeTool::class),
      compatibleDriverTypes = setOf(
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        TrailblazeDriverType.IOS_HOST,
      ),
    ),
    ToolSetCatalogEntry(
      id = "web_verification",
      description = "web assertion tools (playwright)",
      toolClasses = setOf(AssertEqualsTrailblazeTool::class),
      compatibleDriverTypes = setOf(
        TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
      ),
    ),
    ToolSetCatalogEntry(
      id = "revyl_verification",
      description = "revyl assertion tools",
      toolClasses = setOf(AssertNotVisibleWithTextTrailblazeTool::class),
      compatibleDriverTypes = setOf(
        TrailblazeDriverType.REVYL_ANDROID,
        TrailblazeDriverType.REVYL_IOS,
      ),
    ),
    ToolSetCatalogEntry(
      id = "compose_verification",
      description = "compose assertion tools",
      toolClasses = setOf(AssertVisibleTrailblazeTool::class),
      compatibleDriverTypes = setOf(TrailblazeDriverType.COMPOSE),
    ),
    ToolSetCatalogEntry(
      id = "typing",
      description = "non-verification tool that must not leak into a verify step",
      toolClasses = setOf(InputTextTrailblazeTool::class),
      alwaysEnabled = true,
    ),
  )

  private val GENERIC_VERIFY_TOOL = AssertVisibleTrailblazeTool::class.toolName().toolName
  private val WEB_VERIFY_TOOL = AssertEqualsTrailblazeTool::class.toolName().toolName
  private val REVYL_VERIFY_TOOL = AssertNotVisibleWithTextTrailblazeTool::class.toolName().toolName
  private val TYPING_TOOL = InputTextTrailblazeTool::class.toolName().toolName

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
    // The driver's verify tool is advertised...
    assertThat(names).contains(GENERIC_VERIFY_TOOL)
    // ...and objectiveStatus, so the forced-tool graph can still terminate the step...
    assertThat(names).contains(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
    // ...but a non-verification, state-mutating tool (here `inputText`) is NOT advertised — that's
    // the contract: a verify step can't mutate state (type/scroll/tap), only assert/observe.
    assertThat(names).doesNotContain(TYPING_TOOL)
  }

  @Test
  fun `verifyScopedAdvertisedTools also scopes on the instrumentation driver`() {
    // Both Android on-device members of VERIFY_SCOPE_DRIVERS scope to the generic verification toolset.
    val repo = verifyRepo(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)
    val scoped = verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "the title is visible")), repo)
    assertThat(scoped).isNotNull()
    assertThat(scoped!!.map { it.name }).contains(GENERIC_VERIFY_TOOL)
    assertThat(scoped.map { it.name }).contains(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
    assertThat(scoped.map { it.name }).doesNotContain(TYPING_TOOL)
  }

  @Test
  fun `verifyScopedAdvertisedTools scopes a web verify step to the web verification toolset`() {
    // Driver-awareness: a Playwright verify step advertises web_verification's tools — NOT the
    // generic Android verification tools (which would be wrong on web), and not the typing tool.
    val repo = verifyRepo(TrailblazeDriverType.PLAYWRIGHT_NATIVE)
    val scoped = verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "the heading is visible")), repo)
    assertThat(scoped).isNotNull()
    val names = scoped!!.map { it.name }
    assertThat(names).contains(WEB_VERIFY_TOOL)
    assertThat(names).contains(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
    assertThat(names).doesNotContain(GENERIC_VERIFY_TOOL)
    assertThat(names).doesNotContain(REVYL_VERIFY_TOOL)
    assertThat(names).doesNotContain(TYPING_TOOL)
  }

  @Test
  fun `verifyScopedAdvertisedTools scopes a Revyl verify step to the revyl verification toolset`() {
    val repo = verifyRepo(TrailblazeDriverType.REVYL_ANDROID)
    val scoped = verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "the row is gone")), repo)
    assertThat(scoped).isNotNull()
    val names = scoped!!.map { it.name }
    assertThat(names).contains(REVYL_VERIFY_TOOL)
    assertThat(names).contains(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
    assertThat(names).doesNotContain(GENERIC_VERIFY_TOOL)
    assertThat(names).doesNotContain(WEB_VERIFY_TOOL)
  }

  @Test
  fun `asToolRegistry tops up the driver's class-backed verify tool, not other drivers'`() {
    // Registry top-up (verifyTools) must use the SAME driver-scoped selection as the advertised
    // surface, so a driver-specific class-backed verify tool is dispatchable — otherwise a verify
    // step could advertise a tool that isn't in the registry and strand the agent under
    // ToolChoice.Required. A Revyl repo's registry must carry revyl_verification's class tool, and
    // NOT the generic / web verification classes (which aren't compatible with Revyl).
    val repo = verifyRepo(TrailblazeDriverType.REVYL_ANDROID)
    val registry = repo.asToolRegistry(
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )
    assertThat(registry.getToolOrNull(REVYL_VERIFY_TOOL)).isNotNull()
    assertThat(registry.getToolOrNull(GENERIC_VERIFY_TOOL)).isNull()
    assertThat(registry.getToolOrNull(WEB_VERIFY_TOOL)).isNull()
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
  fun `verifyScopedAdvertisedTools returns null on an unknown (null) driver`() {
    // No driver ⇒ not in VERIFY_SCOPE_DRIVERS ⇒ keep the full surface.
    val unknownDriverRepo = verifyRepo(driver = null)
    assertThat(verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "x")), unknownDriverRepo)).isNull()
  }

  @Test
  fun `verifyScopedAdvertisedTools falls back to the full surface when no verification toolset resolves`() {
    // A driver in VERIFY_SCOPE_DRIVERS whose verify toolset isn't in the catalog: getToolDescriptorsForStep
    // returns objectiveStatus only. Advertising that under ToolChoice.Required would strand the agent
    // (can neither assert nor observe), so the hardened gate falls back to the full surface (null).
    val coreOnlyCatalog = verifyScopingCatalog().filterNot { it.id.endsWith("verification") }
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = coreOnlyCatalog,
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )
    assertThat(verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "x")), repo)).isNull()
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

  @Test
  fun `verifyScopedAdvertisedTools returns null for the Compose driver (not in the roll-out set yet)`() {
    // getToolDescriptorsForStep IS now driver-aware for Compose (it returns compose_verification's
    // tools), but Compose is deliberately NOT in VERIFY_SCOPE_DRIVERS yet — verify scoping is rolled
    // out per driver as it's validated on the matching pipeline, and Compose hasn't been. So a Compose
    // verify step keeps the full surface. Pin that until Compose verify scoping is validated.
    val composeRepo = verifyRepo(TrailblazeDriverType.COMPOSE)
    assertThat(verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "the title is visible")), composeRepo)).isNull()
  }
}
