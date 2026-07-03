package xyz.block.trailblaze.tools

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.getInitialToolClassesForDriver
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.yaml.VerificationStep

/**
 * Covers the catalog-backed tool surface: driver-scoped resolution, the all-tools-on initial
 * repo surface built by [TrailblazeToolRepo.withDynamicToolSets], and verify-step scoping. There
 * is no longer a runtime toolset switch (`setActiveToolSets`) — every driver-compatible tool is
 * advertised up front.
 */
class DynamicToolSetTest {

  // -- withDynamicToolSets: everything the driver supports is advertised up front --

  @Test
  fun `getCurrentToolDescriptors surfaces YAML-defined tools alongside class-backed tools`() {
    // Regression test for the empty-descriptors bug: before the fix, YAML-defined tools like
    // `eraseText` were silently dropped from `getCurrentToolDescriptors()` because it only
    // iterated class-backed `KClass` entries. A target whose toolset ended up containing only
    // YAML-defined tools would send zero descriptors to the LLM, which combined with
    // `toolChoice=Required` produced an opaque OpenAI 400.
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "probe",
        toolClasses = TrailblazeToolSet.DefaultLlmTrailblazeTools,
        yamlToolNames = setOf(ToolName("eraseText")),
      ),
    )
    val names = repo.getCurrentToolDescriptors().map { it.name }.toSet()
    assertTrue("eraseText" in names, "YAML-defined eraseText must surface in tool descriptors")
    assertTrue("hideKeyboard" in names, "class-backed hideKeyboard must surface in tool descriptors")
  }

  @Test
  fun `toolCallToTrailblazeTool can deserialize YAML-defined tool calls`() {
    // Pair to the descriptor test above — keeps advertise/execute in sync. If
    // getCurrentToolDescriptors() advertises a YAML-defined tool like `eraseText`, the LLM can
    // legally select it, and the executor path (AgentUiActionExecutor / HostAccessibilityRpcClient)
    // routes through toolCallToTrailblazeTool() to deserialize the tool call. That deserialization
    // used to only resolve class-backed tools, so YAML picks would crash the executor with
    // "Could not find Trailblaze tool class". Now YAML tool names resolve via the registered
    // ToolYamlConfig + YamlDefinedToolSerializer.
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "probe",
        toolClasses = TrailblazeToolSet.DefaultLlmTrailblazeTools,
        yamlToolNames = setOf(ToolName("eraseText")),
      ),
    )
    val tool = repo.toolCallToTrailblazeTool(
      toolName = "eraseText",
      toolContent = """{"charactersToErase":3}""",
    )
    assertNotNull(tool, "Should deserialize YAML tool call without throwing")
    assertTrue(
      tool is xyz.block.trailblaze.config.YamlDefinedTrailblazeTool,
      "Expected YamlDefinedTrailblazeTool, got ${tool::class.simpleName}",
    )
  }

  @Test
  fun `getToolDescriptorsForStep scopes a VerificationStep to the verify surface`() {
    // Verify-step scoping is the one narrowing that survives: a `verify:` step advertises only the
    // assertion tools + objectiveStatus so the agent can't tap/scroll during a verification. The
    // full surface (including the verify tools) is still on for DirectionSteps.
    val repo = TrailblazeToolRepo.withDynamicToolSets()
    val verifyStep = VerificationStep(verify = "test")
    val verifyDescriptors = repo.getToolDescriptorsForStep(verifyStep).map { it.name }.toSet()

    assertTrue("assertNotVisibleWithText" in verifyDescriptors, "Should include assertNotVisibleWithText")
    assertTrue("objectiveStatus" in verifyDescriptors, "Should include objectiveStatus")
    assertFalse("tap" in verifyDescriptors, "A verify step must not advertise interaction tools like tap")
  }

  // -- defaultToolClassesForDriver --

  @Test
  fun `defaultToolClassesForDriver excludes mobile-only tools on Playwright`() {
    // core_interaction.yaml declares drivers: [android-ondevice, ios-host] — Playwright is
    // NOT in that list, so `tap`, `hideKeyboard`, `tapOnPoint`, etc. must not surface in a
    // PLAYWRIGHT_NATIVE session. Driver-agnostic toolsets (meta, memory) should still appear.
    val playwrightClasses = TrailblazeToolSetCatalog
      .defaultToolClassesForDriver(TrailblazeDriverType.PLAYWRIGHT_NATIVE)
      .map { it.toolName().toolName }
      .toSet()
    assertFalse("tap" in playwrightClasses, "core_interaction 'tap' is mobile-only")
    assertFalse("hideKeyboard" in playwrightClasses, "core_interaction 'hideKeyboard' is mobile-only")
    assertFalse("tapOnPoint" in playwrightClasses, "core_interaction 'tapOnPoint' is mobile-only")
    assertTrue("objectiveStatus" in playwrightClasses, "meta is driver-agnostic")
    assertTrue("rememberText" in playwrightClasses, "memory is driver-agnostic")
  }

  @Test
  fun `defaultToolClassesForDriver includes core_interaction for mobile drivers`() {
    val androidClasses = TrailblazeToolSetCatalog
      .defaultToolClassesForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)
      .map { it.toolName().toolName }
      .toSet()
    assertTrue("tap" in androidClasses, "core_interaction 'tap' should be present on Android")
    assertTrue("hideKeyboard" in androidClasses, "core_interaction 'hideKeyboard' should be present on Android")
    assertTrue("objectiveStatus" in androidClasses, "meta should still be present")
  }

  // -- TrailblazeHostAppTarget.getInitialToolClassesForDriver (target + driver aware) --

  @Test
  fun `getInitialToolClassesForDriver layers base plus custom minus excluded`() {
    // Verify the composite layering semantics in isolation:
    //   1. driver-compatible base — provides `objectiveStatus` (meta) and `tap` (core_interaction)
    //   2. `+` target custom — adds `MaestroTrailblazeTool` (deliberately not in any YAML toolset,
    //      so this assertion actually proves the custom contribution reaches the result — a
    //      tool already in the base would pass the assertion regardless).
    //   3. `-` target excluded — removes `tap`
    //   4. Invariant: excluded wins over custom. `TapTrailblazeTool` appears in BOTH the
    //      target's custom and excluded sets; the result must not contain it.
    val fakeTarget = object : TrailblazeHostAppTarget(id = "fake", displayName = "Fake") {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null
      override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType) =
        setOf(MaestroTrailblazeTool::class, TapTrailblazeTool::class)
      override fun getExcludedToolsForDriver(driverType: TrailblazeDriverType) =
        setOf(TapTrailblazeTool::class)
    }

    val result = fakeTarget.getInitialToolClassesForDriver(
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    val names = result.map { it.toolName().toolName }.toSet()

    assertTrue("mobile_maestro" in names, "Target's custom overlay must surface in the result")
    assertFalse(
      "tap" in names,
      "Excluded wins over custom: tap is in both custom and excluded, must NOT be in result",
    )
    assertTrue("objectiveStatus" in names, "Driver-compatible base (meta) should still be present")
  }

  @Test
  fun `driver-aware helpers forward the catalog parameter`() {
    // Pin the public `catalog:` override on both driver-aware tool-class helpers. Without
    // these assertions, a future refactor that stops threading the parameter through would
    // silently pass every other test (they all use the default classpath-discovered catalog).
    assertTrue(
      TrailblazeToolSetCatalog
        .defaultToolClassesForDriver(
          driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
          catalog = emptyList(),
        )
        .isEmpty(),
      "defaultToolClassesForDriver with empty catalog should yield empty result",
    )

    // For the target+driver extension: an empty base catalog leaves only the target's own
    // custom overlay (minus any target excludes). This proves the `catalog` arg is forwarded
    // to the base computation AND that custom contributions survive a zeroed-out base.
    val fakeTarget = object : TrailblazeHostAppTarget(id = "fake-catalog", displayName = "Fake") {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null
      override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType) =
        setOf(MaestroTrailblazeTool::class)
    }
    val result = fakeTarget.getInitialToolClassesForDriver(
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      catalog = emptyList(),
    )
    assertEquals(
      expected = setOf(MaestroTrailblazeTool::class),
      actual = result,
      message = "Empty base catalog: only the target's custom contribution should survive",
    )
  }

  // -- Driver-scoped catalog resolution --

  @Test
  fun `resolveForDriver does not leak core_interaction tools to non-mobile drivers`() {
    // Invariant: core_interaction.yaml is alwaysEnabled for mobile (android/ios) drivers
    // but must NOT bleed into Compose / Playwright / Revyl driver sessions. Those drivers
    // have their own interaction vocabulary and tap/inputText/swipe etc. would both inflate
    // the LLM surface and offer tools that don't exist on those runtimes.
    val composeResolved =
      TrailblazeToolSetCatalog.resolveForDriver(TrailblazeDriverType.COMPOSE, requestedIds = emptyList())
    val composeNames =
      (
        composeResolved.toolClasses.map { it.toolName().toolName } +
          composeResolved.yamlToolNames.map { it.toolName }
        ).toSet()
    assertFalse("tap" in composeNames, "core_interaction 'tap' must not leak into COMPOSE resolution")
    assertFalse("inputText" in composeNames, "core_interaction 'inputText' must not leak into COMPOSE resolution")
    assertTrue("objectiveStatus" in composeNames, "meta 'objectiveStatus' is always enabled for every driver")
  }

  @Test
  fun `resolveForDriver includes core_interaction for mobile drivers`() {
    val mobileResolved = TrailblazeToolSetCatalog.resolveForDriver(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      requestedIds = emptyList(),
    )
    val mobileNames =
      (
        mobileResolved.toolClasses.map { it.toolName().toolName } +
          mobileResolved.yamlToolNames.map { it.toolName }
        ).toSet()
    assertTrue("tap" in mobileNames, "Mobile drivers should receive core_interaction 'tap'")
    assertTrue("inputText" in mobileNames, "Mobile drivers should receive core_interaction 'inputText'")
  }

  @Test
  fun `resolveForSession with the full id list stays driver-aware`() {
    // This is the exact contract both the MCP tool registration (TrailblazeMcpServer.registerTools)
    // and the initial repo surface (withDynamicToolSets) now rely on: resolve EVERY catalog id, but
    // still filter by the session's driver. The existing leak tests use an empty requestedIds list
    // (i.e. only always-enabled entries), so this pins the all-ids + driver combination that the
    // "advertise everything up front" model introduced. A null driver returns the full cross-driver
    // set (the "see everything" pre-connect default an external MCP client gets).
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val allIds = catalog.map { it.id }

    fun classNames(driver: TrailblazeDriverType?) =
      TrailblazeToolSetCatalog.resolveForSession(driver, allIds, catalog)
        .toolClasses.map { it.toolName().toolName }.toSet()

    val playwright = classNames(TrailblazeDriverType.PLAYWRIGHT_NATIVE)
    assertFalse("tap" in playwright, "Playwright session must not get mobile-only core_interaction 'tap' even with all ids")
    assertFalse("hideKeyboard" in playwright, "Playwright session must not get mobile-only 'hideKeyboard'")
    assertTrue("objectiveStatus" in playwright, "driver-agnostic meta tool is still present")

    val android = classNames(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)
    assertTrue("tap" in android, "Android session keeps mobile core_interaction 'tap'")

    val noDriver = classNames(driver = null)
    assertTrue("tap" in noDriver, "A null driver falls back to the full cross-driver set")
  }

  // -- DefaultLlmTrailblazeTools (catalog-derived) --

  @Test
  fun `DefaultLlmTrailblazeTools includes memory and verification tool classes`() {
    // Regression for slice 3: after retiring Kotlin RememberTrailblazeToolSet / VerifyToolSet,
    // DefaultLlmTrailblazeTools derives from the YAML catalog directly. If a future edit drops
    // memory or verification from the catalog, the full-tool-set surface
    // (ToolSetCategory.ALL + CustomTrailblazeTools.allForSerializationTools) silently loses
    // those tools. Pin here.
    val names = TrailblazeToolSet.DefaultLlmTrailblazeTools
      .map { it.toolName().toolName }
      .toSet()
    assertTrue("rememberText" in names, "memory tool 'rememberText' must be in DefaultLlmTrailblazeTools")
    assertTrue("assertEquals" in names, "memory tool 'assertEquals' must be in DefaultLlmTrailblazeTools")
    assertTrue("assertNotVisibleWithText" in names, "verification tool must be in DefaultLlmTrailblazeTools")
    assertTrue("tap" in names, "core_interaction tool must be in DefaultLlmTrailblazeTools")
    assertTrue("tapOnPoint" in names, "device_control tool must be in DefaultLlmTrailblazeTools")
  }

  @Test
  fun `VerificationStep descriptors fall back to the global catalog when repo has no toolSetCatalog`() {
    // Companion to the custom-catalog test below: if a repo is constructed without a
    // `toolSetCatalog` (common for minimal test setups), `verifyTools` should still resolve
    // from the global default catalog rather than returning an empty list. Explicit pin so
    // the `toolSetCatalog ?: TrailblazeToolSetCatalog.defaultEntries()` fallback is covered
    // by name, not just indirectly.
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "probe",
        toolClasses = setOf(TapTrailblazeTool::class),
      ),
      // no toolSetCatalog — falls back to TrailblazeToolSetCatalog.defaultEntries()
    )

    val verifyNames = repo.getToolDescriptorsForStep(
      VerificationStep(verify = "probe"),
    ).map { it.name }.toSet()

    assertTrue(
      "assertNotVisibleWithText" in verifyNames,
      "Fallback catalog should still provide the default verification tools",
    )
    assertTrue(
      "objectiveStatus" in verifyNames,
      "VerificationStep always includes objectiveStatus so the step can complete",
    )
  }

  @Test
  fun `VerificationStep descriptors respect the repo's configured custom catalog`() {
    // Regression: TrailblazeToolRepo.verifyTools must resolve via the repo's `toolSetCatalog`
    // when present, not silently fall through to the global default. A test wiring up a
    // custom catalog with a replaced `verification` entry should see ITS tools in the
    // VerificationStep descriptor path — otherwise any app-specific overlay would be a
    // quiet no-op.
    val defaultEntries = TrailblazeToolSetCatalog.defaultEntries()
    val customVerification = ToolSetCatalogEntry(
      id = "verification",
      description = "Test-only verification with a single marker tool",
      toolClasses = setOf(AssertEqualsTrailblazeTool::class),
    )
    val customCatalog = defaultEntries.filter { it.id != "verification" } + customVerification

    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "probe",
        toolClasses = setOf(TapTrailblazeTool::class),
      ),
      toolSetCatalog = customCatalog,
    )

    val verifyNames = repo.getToolDescriptorsForStep(
      VerificationStep(verify = "probe"),
    ).map { it.name }.toSet()

    assertTrue(
      "assertEquals" in verifyNames,
      "VerificationStep should see the custom catalog's verification tools",
    )
    assertTrue(
      "objectiveStatus" in verifyNames,
      "VerificationStep always includes objectiveStatus so the step can complete",
    )
    assertFalse(
      "assertNotVisibleWithText" in verifyNames,
      "Default-catalog verification tools should NOT leak in when a custom catalog overrides",
    )
  }

  // -- getToolDescriptorsForStep(VerificationStep) driver-awareness --

  /**
   * A multi-driver catalog modeling the real verification-toolset layout: a generic `verification`
   * toolset for Android on-device + iOS host, plus driver-specific `web_verification` (Playwright)
   * and `revyl_verification` (Revyl). Each carries a DISTINCT verify tool so a leak across drivers
   * is detectable. (All three stand-in tools are `surfaceToLlm = true` so they actually appear in
   * the advertised descriptors.)
   */
  private fun multiDriverVerifyCatalog() = listOf(
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
  )

  private fun verifyDescriptorNamesForDriver(driver: TrailblazeDriverType?): Set<String> =
    TrailblazeToolRepo.withDynamicToolSets(catalog = multiDriverVerifyCatalog(), driverType = driver)
      .getToolDescriptorsForStep(VerificationStep(verify = "probe"))
      .map { it.name }
      .toSet()

  @Test
  fun `VerificationStep advertises the generic verification toolset on Android on-device`() {
    val names = verifyDescriptorNamesForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY)
    assertTrue("assertVisible" in names, "Android should see the generic verification tool")
    assertTrue("objectiveStatus" in names, "objectiveStatus is always included")
    // Driver-specific verify tools from OTHER drivers must not leak in.
    assertFalse("assertEquals" in names, "web verification tool must not leak onto Android")
    assertFalse("assertNotVisibleWithText" in names, "revyl verification tool must not leak onto Android")
  }

  @Test
  fun `VerificationStep advertises the web verification toolset on Playwright`() {
    val names = verifyDescriptorNamesForDriver(TrailblazeDriverType.PLAYWRIGHT_NATIVE)
    assertTrue("assertEquals" in names, "Playwright should see the web verification tool")
    assertTrue("objectiveStatus" in names, "objectiveStatus is always included")
    assertFalse("assertVisible" in names, "generic Android verification tool must not leak onto web")
    assertFalse("assertNotVisibleWithText" in names, "revyl verification tool must not leak onto web")
  }

  @Test
  fun `VerificationStep advertises the revyl verification toolset on Revyl`() {
    val names = verifyDescriptorNamesForDriver(TrailblazeDriverType.REVYL_IOS)
    assertTrue("assertNotVisibleWithText" in names, "Revyl should see the revyl verification tool")
    assertTrue("objectiveStatus" in names, "objectiveStatus is always included")
    assertFalse("assertVisible" in names, "generic Android verification tool must not leak onto Revyl")
    assertFalse("assertEquals" in names, "web verification tool must not leak onto Revyl")
  }

  @Test
  fun `VerificationStep with no driver falls back to the generic verification toolset`() {
    // A null driver can't pick a driver-specific toolset, so it preserves the historical behavior:
    // the generic `verification` toolset only (not every driver's verification toolset).
    val names = verifyDescriptorNamesForDriver(driver = null)
    assertTrue("assertVisible" in names, "null driver falls back to the generic verification tool")
    assertTrue("objectiveStatus" in names, "objectiveStatus is always included")
    assertFalse("assertEquals" in names, "driver-specific web tool must not appear for a null driver")
    assertFalse("assertNotVisibleWithText" in names, "driver-specific revyl tool must not appear for a null driver")
  }

  // -- entryToolClasses --

  @Test
  fun `entryToolClasses returns only the requested entry's tools, no alwaysEnabled leakage`() {
    // Invariant 3: resolve() auto-includes alwaysEnabled entries (meta, core_interaction);
    // entryToolClasses() must NOT. Otherwise a single-entry lookup would leak core_interaction
    // tools into a context that asked for one entry in isolation.
    val verificationOnly = TrailblazeToolSetCatalog.entryToolClasses("verification")
    val verificationNames = verificationOnly.map { it.toolName().toolName }.toSet()

    assertTrue(
      "assertNotVisibleWithText" in verificationNames,
      "verification entry should include its own tools",
    )
    assertFalse(
      "tap" in verificationNames,
      "entryToolClasses must not pull in alwaysEnabled core_interaction 'tap'",
    )
    assertFalse(
      "objectiveStatus" in verificationNames,
      "entryToolClasses must not pull in alwaysEnabled meta 'objectiveStatus'",
    )
  }

  @Test
  fun `entryToolClasses returns empty set for unknown id rather than throwing`() {
    assertTrue(TrailblazeToolSetCatalog.entryToolClasses("not_a_real_toolset_id").isEmpty())
  }

  // -- withDynamicToolSets(driverType) --
  //
  // Every driver-compatible tool is advertised up front. Passing `driverType` filters the surface
  // by each toolset's YAML `drivers:`, so mobile-only tools (core_interaction's `tap`,
  // `hideKeyboard`, …) don't leak into Playwright/Compose sessions.

  @Test
  fun `withDynamicToolSets with driverType filters the surface to driver-compatible entries`() {
    val playwrightRepo = TrailblazeToolRepo.withDynamicToolSets(
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )
    val playwrightNames = playwrightRepo.getRegisteredTrailblazeTools()
      .map { it.toolName().toolName }.toSet()
    assertFalse("tap" in playwrightNames, "core_interaction 'tap' must not appear on Playwright")
    assertFalse("hideKeyboard" in playwrightNames, "core_interaction 'hideKeyboard' is mobile-only")
    assertTrue("objectiveStatus" in playwrightNames, "meta (driver-agnostic) must remain")
  }

  @Test
  fun `withDynamicToolSets without driverType advertises the full mobile surface`() {
    // Without a driver hint the repo carries every catalog tool, including the driver-agnostic
    // and mobile ones. Memory / verification tools that used to require an explicit opt-in are
    // now on from the start.
    val repo = TrailblazeToolRepo.withDynamicToolSets()
    val names = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("tap" in names, "core_interaction 'tap' is advertised")
    assertTrue("objectiveStatus" in names, "meta tools still present")
    assertTrue("rememberText" in names, "memory tools are on up front (no opt-in)")
  }

  @Test
  fun `withDynamicToolSets with Android driverType keeps mobile tools`() {
    val androidRepo = TrailblazeToolRepo.withDynamicToolSets(
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    val androidNames = androidRepo.getRegisteredTrailblazeTools()
      .map { it.toolName().toolName }.toSet()
    assertTrue("tap" in androidNames, "Android is a compatible driver for core_interaction")
    assertTrue("hideKeyboard" in androidNames, "Android sees core_interaction tools")
  }

  // -- YAML tool name symmetry --

  @Test
  fun `defaultYamlToolNamesForDriver mirrors defaultToolClassesForDriver for YAML tools`() {
    // Symmetric with defaultToolClassesForDriver. Android sees core_interaction's YAML tools
    // (like `eraseText`); Playwright does not because core_interaction is mobile-only.
    val android = TrailblazeToolSetCatalog
      .defaultYamlToolNamesForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)
      .map { it.toolName }.toSet()
    assertTrue("eraseText" in android, "core_interaction YAML-defined 'eraseText' should appear for Android")

    val playwright = TrailblazeToolSetCatalog
      .defaultYamlToolNamesForDriver(TrailblazeDriverType.PLAYWRIGHT_NATIVE)
      .map { it.toolName }.toSet()
    assertFalse("eraseText" in playwright, "core_interaction's 'eraseText' is mobile-only")
  }

  @Test
  fun `withDynamicToolSets forwards customYamlToolNames into the initial repo surface`() {
    // Rule authors contributing a YAML-defined tool name should see it advertised to the LLM
    // without needing a backing KClass — symmetric with customToolClasses for class-backed
    // tools. If the repo drops customYamlToolNames from the forward, this test fails.
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      customYamlToolNames = setOf(ToolName("eraseText")),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    val descriptorNames = repo.getCurrentToolDescriptors().map { it.name }.toSet()
    assertTrue(
      "eraseText" in descriptorNames,
      "customYamlToolNames must reach the tool descriptor registry",
    )
  }
}
