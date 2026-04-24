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
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SetActiveToolSetsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.yaml.VerificationStep

class DynamicToolSetTest {

  // -- TrailblazeToolSetCatalog --

  @Test
  fun `formatCatalogSummary includes all YAML-defined toolsets`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val summary = TrailblazeToolSetCatalog.formatCatalogSummary(catalog)

    assertTrue(summary.contains("core_interaction"), "Should list core_interaction toolset")
    assertTrue(summary.contains("navigation"), "Should list navigation toolset")
    assertTrue(summary.contains("verification"), "Should list verification toolset")
    assertTrue(summary.contains("memory"), "Should list memory toolset")
    assertTrue(summary.contains("observation"), "Should list observation toolset")
    assertTrue(summary.contains("meta"), "Should list meta toolset")
    assertTrue(summary.contains("[always enabled]"), "Should mark always-enabled sets")
  }

  // -- TrailblazeToolRepo.setActiveToolSets --

  @Test
  fun `setActiveToolSets replaces tools with requested toolsets`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "core",
        toolClasses = coreTools.toolClasses,
        yamlToolNames = coreTools.yamlToolNames,
      ),
      toolSetCatalog = catalog,
    )

    // Initially the alwaysEnabled toolsets are live: `meta` + `core_interaction`.
    val initialToolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("tap" in initialToolNames, "core_interaction (alwaysEnabled) should include tap")
    assertTrue("inputText" in initialToolNames, "core_interaction should include inputText")
    assertTrue("pressKey" in initialToolNames, "core_interaction should include pressKey")
    assertTrue("swipe" in initialToolNames, "core_interaction should include swipe")
    assertTrue("setActiveToolSets" in initialToolNames, "meta (alwaysEnabled) should include setActiveToolSets")
    assertTrue("rememberText" !in initialToolNames, "memory toolset should not be active initially")

    // Enable memory toolset — adds rememberText / assertEquals / etc.
    val result = repo.setActiveToolSets(listOf("memory"))
    assertTrue(result.contains("Active tool sets updated"), "Should confirm update")

    val updatedToolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("rememberText" in updatedToolNames, "Memory tools should now be active")
    assertTrue("tap" in updatedToolNames, "core_interaction tools should still be present")
  }

  @Test
  fun `setActiveToolSets rejects unknown toolset IDs`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "core",
        toolClasses = coreTools.toolClasses,
        yamlToolNames = coreTools.yamlToolNames,
      ),
      toolSetCatalog = catalog,
    )

    val result = repo.setActiveToolSets(listOf("nonexistent"))
    assertTrue(result.contains("Unknown toolset IDs"), "Should reject unknown IDs")
  }

  @Test
  fun `setActiveToolSets with empty list resets to alwaysEnabled only`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "core",
        toolClasses = coreTools.toolClasses,
        yamlToolNames = coreTools.yamlToolNames,
      ),
      toolSetCatalog = catalog,
    )

    // Enable memory then reset
    repo.setActiveToolSets(listOf("memory"))
    repo.setActiveToolSets(emptyList())

    val toolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("rememberText" !in toolNames, "Memory tools should be removed after reset")
    assertTrue("tap" in toolNames, "core_interaction (alwaysEnabled) tools should remain")
    assertTrue("pressKey" in toolNames, "core_interaction (alwaysEnabled) tools should remain")
  }

  @Test
  fun `setActiveToolSets preserves extra tools not in catalog`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    // Simulate an app-specific custom tool added alongside catalog tools
    val customToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "core + custom",
      toolClasses = coreTools.toolClasses + InputTextTrailblazeTool::class, // InputText is already in core, but let's use a known tool
      yamlToolNames = coreTools.yamlToolNames,
    )
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = customToolSet,
      toolSetCatalog = catalog,
    )

    // Switch toolsets - extra (non-catalog) tools should be preserved
    repo.setActiveToolSets(listOf("memory"))
    val toolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("inputText" in toolNames, "core_interaction (alwaysEnabled) tool should still be present")
  }

  @Test
  fun `setActiveToolSets returns error when catalog not configured`() {
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        "minimal",
        setOf(TapTrailblazeTool::class),
      ),
    )

    val result = repo.setActiveToolSets(listOf("memory"))
    assertTrue(result.contains("not configured"), "Should indicate catalog is not configured")
  }

  // -- meta toolset includes meta tools --

  @Test
  fun `meta toolset includes setActiveToolSets and objectiveStatus`() {
    val metaEntry = TrailblazeToolSetCatalog.defaultEntries().first { it.id == "meta" }
    assertTrue(metaEntry.alwaysEnabled, "Meta toolset should be alwaysEnabled")
    val names = metaEntry.toolNames.toSet()
    assertTrue("setActiveToolSets" in names)
    assertTrue("objectiveStatus" in names)
  }

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
  fun `getToolDescriptorsForStep returns verify tools for VerificationStep`() {
    val repo = TrailblazeToolRepo.withDynamicToolSets()
    val verifyStep = xyz.block.trailblaze.yaml.VerificationStep(verify = "test")
    val verifyDescriptors = repo.getToolDescriptorsForStep(verifyStep).map { it.name }.toSet()

    assertTrue("assertNotVisibleWithText" in verifyDescriptors, "Should include assertNotVisibleWithText")
    assertTrue("objectiveStatus" in verifyDescriptors, "Should include objectiveStatus")

    // Verify tools should NOT be in getRegisteredTrailblazeTools (they're only for verification)
    val registeredToolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("assertNotVisibleWithText" !in registeredToolNames,
      "Verify tools should not be in registered tool classes with dynamic toolsets")
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
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): Set<String>? = null
      override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType) =
        setOf(MaestroTrailblazeTool::class, TapTrailblazeTool::class)
      override fun getExcludedToolsForDriver(driverType: TrailblazeDriverType) =
        setOf(TapTrailblazeTool::class)
    }

    val result = fakeTarget.getInitialToolClassesForDriver(
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    val names = result.map { it.toolName().toolName }.toSet()

    assertTrue("maestro" in names, "Target's custom overlay must surface in the result")
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
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): Set<String>? = null
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

  // -- entryToolClasses --

  @Test
  fun `entryToolClasses returns only the requested entry's tools, no alwaysEnabled leakage`() {
    // Invariant 3: resolve() auto-includes alwaysEnabled entries (meta, core_interaction);
    // entryToolClasses() must NOT. Otherwise VERIFY-hint progressive disclosure would leak
    // core_interaction tools into supposedly read-only contexts.
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

  @Test
  fun `setActiveToolSets persists tools across calls`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "core",
        toolClasses = coreTools.toolClasses,
        yamlToolNames = coreTools.yamlToolNames,
      ),
      toolSetCatalog = catalog,
    )

    // Enable navigation
    repo.setActiveToolSets(listOf("navigation"))
    val afterFirst = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("launchApp" in afterFirst, "Navigation tools should be active")

    // Tools should still be there without re-requesting
    val afterSecondCheck = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertEquals(afterFirst, afterSecondCheck, "Tools should persist without re-requesting")
  }

  // -- withDynamicToolSets(driverType) --
  //
  // The repo's default `always_enabled` core includes `core_interaction` (mobile-only). Without
  // a driver hint, that leaks into Playwright/Compose sessions — the LLM is advertised `tap`,
  // `hideKeyboard`, etc. even though they don't run on a web driver. Passing `driverType`
  // routes through `resolveForDriver`, filtering the core by YAML `drivers:`.

  @Test
  fun `withDynamicToolSets with driverType filters alwaysEnabled core to driver-compatible entries`() {
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
  fun `withDynamicToolSets without driverType keeps pre-existing behavior`() {
    // Regression guard: callers that don't pass driverType must see the unfiltered
    // `alwaysEnabled` core (meta + core_interaction). Android-context callers rely on this.
    val repo = TrailblazeToolRepo.withDynamicToolSets()
    val names = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("tap" in names, "Without driverType, core_interaction's 'tap' is still advertised")
    assertTrue("objectiveStatus" in names, "meta tools still present")
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
