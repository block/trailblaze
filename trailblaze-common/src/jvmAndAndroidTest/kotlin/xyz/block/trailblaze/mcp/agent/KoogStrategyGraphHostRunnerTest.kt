package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.contains
import xyz.block.trailblaze.devices.TrailblazeDriverType
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.startsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.resolveToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.Status
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.VerificationStep
import kotlin.test.Test
import kotlin.test.assertFailsWith

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
  fun `IN_PROGRESS maps to an error, not a hollow pass`() {
    // Two verify steps ended on objectiveStatus(IN_PROGRESS) with zero assertions and scored
    // complete. The graph now loops on IN_PROGRESS, so this is the backstop.
    val result = resolveKoogObjectiveResult(
      outcome = Status.IN_PROGRESS,
      explanation = "still going",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage).contains("still going")
  }

  // ---------------------------------------------------------------------------------------------
  // countsAsAssertionEvidence — what the COMPLETED gate is allowed to rest on.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `a successful assertion is evidence`() {
    assertThat(
      countsAsAssertionEvidence(
        AssertVisibleTrailblazeTool(ref = "y778"),
        TrailblazeToolResult.Success(),
      ),
    ).isEqualTo(true)
  }

  @Test
  fun `a successful device handover is not evidence, though a verify step can call it`() {
    // switchDevice rides a verify step's surface so a cross-device claim can be authored. It
    // asserts nothing, so counting it would let COMPLETED through with no claim checked.
    assertThat(
      countsAsAssertionEvidence(
        SwitchDeviceTrailblazeTool(name = "buyer"),
        TrailblazeToolResult.Success(),
      ),
    ).isEqualTo(false)
  }

  @Test
  fun `a failed assertion is not evidence`() {
    assertThat(
      countsAsAssertionEvidence(
        AssertVisibleTrailblazeTool(ref = "y778"),
        TrailblazeToolResult.Error.ExceptionThrown("not visible"),
      ),
    ).isEqualTo(false)
  }

  // ---------------------------------------------------------------------------------------------
  // objectiveStatusDisposition — what the dispatcher records, refuses, and tells the model.
  // ---------------------------------------------------------------------------------------------

  private fun status(status: Status, explanation: String = "because") =
    ObjectiveStatusTrailblazeTool(explanation = explanation, status = status)

  @Test
  fun `COMPLETED on a direction step is recorded and ends the objective`() {
    val d = objectiveStatusDisposition(status(Status.COMPLETED), verificationBlock = false, passedAssertions = 0)
    assertThat(d).isInstanceOf(ObjectiveStatusDisposition.Recorded::class)
    assertThat((d as ObjectiveStatusDisposition.Recorded).status).isEqualTo(Status.COMPLETED)
    assertThat(d.endsObjective).isEqualTo(true)
    assertThat(d.messageToLlm).contains("because")
  }

  @Test
  fun `IN_PROGRESS is recorded but does not end the objective, and the model is told so`() {
    val d = objectiveStatusDisposition(status(Status.IN_PROGRESS), verificationBlock = true, passedAssertions = 0)
    assertThat(d).isInstanceOf(ObjectiveStatusDisposition.Recorded::class)
    assertThat((d as ObjectiveStatusDisposition.Recorded).status).isEqualTo(Status.IN_PROGRESS)
    assertThat(d.endsObjective).isEqualTo(false)
    assertThat(d.messageToLlm).contains("still open")
  }

  @Test
  fun `COMPLETED on a verify step with no passing assertion is refused`() {
    val d = objectiveStatusDisposition(status(Status.COMPLETED), verificationBlock = true, passedAssertions = 0)
    assertThat(d).isInstanceOf(ObjectiveStatusDisposition.Refused::class)
    assertThat(d.endsObjective).isEqualTo(false)
    assertThat(d.messageToLlm).contains("no assertion has passed")
  }

  @Test
  fun `COMPLETED on a verify step with a passing assertion is recorded`() {
    val d = objectiveStatusDisposition(status(Status.COMPLETED), verificationBlock = true, passedAssertions = 1)
    assertThat(d).isInstanceOf(ObjectiveStatusDisposition.Recorded::class)
    assertThat(d.endsObjective).isEqualTo(true)
  }

  @Test
  fun `FAILED on a verify step is never refused - giving up is a valid verification result`() {
    val d = objectiveStatusDisposition(status(Status.FAILED), verificationBlock = true, passedAssertions = 0)
    assertThat(d).isInstanceOf(ObjectiveStatusDisposition.Recorded::class)
    assertThat((d as ObjectiveStatusDisposition.Recorded).status).isEqualTo(Status.FAILED)
    assertThat(d.endsObjective).isEqualTo(true)
  }

  @Test
  fun `the passing-assertion rule applies only to verification blocks`() {
    // A direction step ("tap X") legitimately completes without any assertion.
    val d = objectiveStatusDisposition(status(Status.COMPLETED), verificationBlock = false, passedAssertions = 0)
    assertThat(d).isInstanceOf(ObjectiveStatusDisposition.Recorded::class)
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

  @Test
  fun `the multi-device handover does not suppress that fallback`() {
    // Same catalog as above, on a session that registered `switchDevice`. The handover joins a
    // verify surface, so it could make the objectiveStatus-only test above pass with a tool that
    // can neither assert nor observe — scoping to `switchDevice` + objectiveStatus and stranding
    // the agent under ToolChoice.Required on the one path built to avoid exactly that.
    val coreOnlyCatalog = verifyScopingCatalog().filterNot { it.id.endsWith("verification") }
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      customToolClasses = setOf(SwitchDeviceTrailblazeTool::class),
      catalog = coreOnlyCatalog,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )

    assertThat(verifyScopedAdvertisedTools(listOf(VerificationStep(verify = "x")), repo)).isNull()
  }

  // ---------------------------------------------------------------------------------------------
  // resolveVerifyBlockSetup — the COMPLETED evidence gate must not ride on the tool surface.
  // Every reason below leaves the surface full on a block that is still all assertions.
  // ---------------------------------------------------------------------------------------------

  private val oneVerifyStep = listOf(VerificationStep(verify = "the title is visible"))

  @Test
  fun `the kill switch widens the tool surface but keeps the evidence gate`() {
    val setup = resolveVerifyBlockSetup(oneVerifyStep, verifyRepo(), scopeDisabled = true, evidenceDisabled = false)
    assertThat(setup.advertisedTools).isNull()
    assertThat(setup.scopeOverridden).isEqualTo(true)
    assertThat(setup.evidenceGateApplies).isEqualTo(true)
  }

  @Test
  fun `a driver outside the scope roll-out keeps the evidence gate`() {
    // Compose is deliberately not in VERIFY_SCOPE_DRIVERS yet, so its verify steps keep the full
    // surface — which must not also hand them a free COMPLETED.
    val setup = resolveVerifyBlockSetup(oneVerifyStep, verifyRepo(TrailblazeDriverType.COMPOSE), scopeDisabled = false, evidenceDisabled = false)
    assertThat(setup.advertisedTools).isNull()
    // Nothing was taken away, so there is nothing to log — but it is still a verification block.
    assertThat(setup.scopeOverridden).isEqualTo(false)
    assertThat(setup.evidenceGateApplies).isEqualTo(true)
  }

  @Test
  fun `a scoped verify block reports both the narrowed surface and the gate`() {
    val setup = resolveVerifyBlockSetup(oneVerifyStep, verifyRepo(), scopeDisabled = false, evidenceDisabled = false)
    assertThat(setup.advertisedTools).isNotNull()
    assertThat(setup.scopeOverridden).isEqualTo(false)
    assertThat(setup.evidenceGateApplies).isEqualTo(true)
  }

  @Test
  fun `a direction step is not a verification block, kill switch or not`() {
    val steps = listOf(DirectionStep(step = "tap the login button"))
    assertThat(resolveVerifyBlockSetup(steps, verifyRepo(), scopeDisabled = false, evidenceDisabled = false).evidenceGateApplies)
      .isEqualTo(false)
    assertThat(resolveVerifyBlockSetup(steps, verifyRepo(), scopeDisabled = true, evidenceDisabled = false).evidenceGateApplies)
      .isEqualTo(false)
  }

  @Test
  fun `a mixed block is not a verification block`() {
    val mixed = listOf(VerificationStep(verify = "title visible"), DirectionStep(step = "tap"))
    assertThat(isVerificationBlock(mixed)).isEqualTo(false)
  }

  @Test
  fun `an empty block is not a verification block`() {
    assertThat(isVerificationBlock(emptyList())).isEqualTo(false)
  }

  @Test
  fun `the evidence kill switch drops the gate without widening the tool surface`() {
    // The two switches answer different questions, so turning the gate off must leave a verify
    // block scoped to its assertion tools — otherwise the escape hatch for one is an escape hatch
    // for the other.
    val setup = resolveVerifyBlockSetup(oneVerifyStep, verifyRepo(), scopeDisabled = false, evidenceDisabled = true)
    assertThat(setup.evidenceGateApplies).isEqualTo(false)
    assertThat(setup.evidenceGateOverridden).isEqualTo(true)
    assertThat(setup.advertisedTools).isNotNull()
    assertThat(setup.scopeOverridden).isEqualTo(false)
  }

  @Test
  fun `the evidence kill switch has nothing to override on a direction step`() {
    // Reported so the disable is logged only where it changed something — a direction step never
    // had the gate to begin with.
    val steps = listOf(DirectionStep(step = "tap the login button"))
    val setup = resolveVerifyBlockSetup(steps, verifyRepo(), scopeDisabled = false, evidenceDisabled = true)
    assertThat(setup.evidenceGateApplies).isEqualTo(false)
    assertThat(setup.evidenceGateOverridden).isEqualTo(false)
  }

  // ---------------------------------------------------------------------------------------------
  // ObjectiveStatusGate — the graph's completion answer may not outlive the turn that produced it.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `the gate answers with what the status just dispatched meant`() {
    val gate = ObjectiveStatusGate()
    assertThat(gate.endsObjective).isEqualTo(false)
    gate.record(endsObjective = true)
    assertThat(gate.endsObjective).isEqualTo(true)
    gate.record(endsObjective = false)
    assertThat(gate.endsObjective).isEqualTo(false)
  }

  @Test
  fun `a recorded terminal status cannot end a LATER turn's objective`() {
    // A status batched with other tool calls records that it would end the objective while the
    // graph deliberately keeps looping. If a later objectiveStatus never reaches the dispatcher —
    // malformed arguments, so Koog rejects it before the host sees it — the edge guard reads
    // whatever is left standing. Clearing on the way back to the LLM is what stops that stale
    // answer from ending the objective on a report the graph already declined to act on.
    val gate = ObjectiveStatusGate()
    gate.record(endsObjective = true)
    gate.clearForNextTurn()
    assertThat(gate.endsObjective).isEqualTo(false)
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

  @Test
  fun `a tool that returns an Error is recorded even though the agent is told in plain text`() {
    // The production shape of a driver-tool failure: it comes back as an Error result, gets turned
    // into ordinary text, and Koog is handed a successful String. Koog's own onToolCallFailed never
    // fires for it, so if this dispatch didn't record it the recovered-failure count would read zero
    // for every real session.
    val instrumentation = KoogRunInstrumentation()

    val text = runBlocking {
      describeToolDispatch("tapOnElementWithText", instrumentation) {
        TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "no such element")
      }
    }

    assertThat(instrumentation.recoveredToolFailureCount).isEqualTo(1)
    assertThat(instrumentation.lastRecoveredToolFailure!!.toolName).isEqualTo("tapOnElementWithText")
    assertThat(instrumentation.lastRecoveredToolFailure!!.summary).isEqualTo("no such element")
    // Still handed back as an ordinary result, so the caller appends the fresh screen to it.
    assertThat(text).contains("no such element")
  }

  @Test
  fun `a tool that throws is recorded too, since the throw is swallowed here`() {
    val instrumentation = KoogRunInstrumentation()

    val text = runBlocking {
      describeToolDispatch("tapOnElementWithText", instrumentation) {
        throw IllegalStateException("stale ref [42]")
      }
    }

    assertThat(instrumentation.recoveredToolFailureCount).isEqualTo(1)
    assertThat(instrumentation.lastRecoveredToolFailure!!.summary).contains("stale ref [42]")
    assertThat(text).contains("failed")
  }

  @Test
  fun `a tool that succeeds records no failure`() {
    val instrumentation = KoogRunInstrumentation()

    runBlocking {
      describeToolDispatch("tapOnElementWithText", instrumentation) {
        TrailblazeToolResult.Success(message = "tapped")
      }
    }

    assertThat(instrumentation.recoveredToolFailureCount).isEqualTo(0)
    assertThat(instrumentation.lastRecoveredToolFailure).isNull()
  }

  @Test
  fun `a YAML-authored tool is recorded under its own id, not its shared class`() {
    // Every `tools:`-authored tool is the SAME YamlDefinedTrailblazeTool class, so recording
    // `tool::class.simpleName` would name all of them "YamlDefinedTrailblazeTool" and a failure
    // report could not say which one broke. resolveToolName resolves the instance name first.
    val yamlTool = YamlDefinedTrailblazeTool(config = ToolYamlConfig(id = "logInAsMerchant"), params = emptyMap())

    assertThat(yamlTool.resolveToolName()).isEqualTo("logInAsMerchant")

    val instrumentation = KoogRunInstrumentation()
    runBlocking {
      describeToolDispatch(yamlTool.resolveToolName(), instrumentation) {
        TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "login timed out")
      }
    }

    assertThat(instrumentation.lastRecoveredToolFailure!!.toolName).isEqualTo("logInAsMerchant")
  }

  @Test
  fun `a class-backed tool still records its annotation name`() {
    // The other half of the rule: no instance name, so the annotation's name is used — NOT the
    // class simpleName, which is a different string for most tools.
    assertThat(InputTextTrailblazeTool(text = "hi").resolveToolName())
      .isEqualTo(InputTextTrailblazeTool::class.toolName().toolName)
  }

  @Test
  fun `cancellation propagates rather than being recorded as a tool failure`() {
    // Structured-concurrency cancellation is not a tool failure; swallowing it here would both
    // mis-attribute the run and break cancellation of the enclosing scope.
    val instrumentation = KoogRunInstrumentation()

    assertFailsWith<CancellationException> {
      runBlocking {
        describeToolDispatch("tapOnElementWithText", instrumentation) { throw CancellationException("cancelled") }
      }
    }

    assertThat(instrumentation.recoveredToolFailureCount).isEqualTo(0)
  }

  // --- retry after FAILED: which blocks get one, and what earns it ---

  @Test
  fun `a direction block may be retried`() {
    assertThat(objectiveRetryApplies(listOf(DirectionStep(step = "tap Settings")))).isEqualTo(true)
  }

  @Test
  fun `a block mixing directions and verifications may be retried, since it does something`() {
    assertThat(
      objectiveRetryApplies(listOf(DirectionStep(step = "tap Settings"), VerificationStep(verify = "Settings is open"))),
    ).isEqualTo(true)
  }

  @Test
  fun `a pure verification block is never retried, because its FAILED is the assertion result`() {
    assertThat(objectiveRetryApplies(listOf(VerificationStep(verify = "the title is visible")))).isEqualTo(false)
  }

  @Test
  fun `an empty block has nothing to retry`() {
    assertThat(objectiveRetryApplies(emptyList())).isEqualTo(false)
  }

  @Test
  fun `only a FAILED report earns retry feedback, and it quotes the model's reason`() {
    val feedback = retryFeedbackFor(Status.FAILED, "no such button")

    assertThat(feedback).isNotNull()
    assertThat(feedback!!).contains("\"no such button\"")
    assertThat(retryFeedbackFor(Status.COMPLETED, "done")).isNull()
    assertThat(retryFeedbackFor(Status.IN_PROGRESS, "still going")).isNull()
    // No report at all means the run threw before objectiveStatus — not the model giving up.
    assertThat(retryFeedbackFor(null, null)).isNull()
  }
}
