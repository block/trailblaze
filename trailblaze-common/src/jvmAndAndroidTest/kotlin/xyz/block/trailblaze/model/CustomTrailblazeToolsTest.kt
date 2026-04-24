package xyz.block.trailblaze.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toolName

/**
 * Pins the driver-awareness of [CustomTrailblazeTools.initialToolRepoToolClasses]' default.
 * When a caller passes [CustomTrailblazeTools.driverType], the default is driver-filtered from
 * the catalog; without it, the pre-existing `DefaultLlmTrailblazeTools` surface is used.
 */
class CustomTrailblazeToolsTest {

  @Test
  fun `initialToolRepoToolClasses default drops mobile-only tools when driverType is Playwright`() {
    val tools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      config = TrailblazeConfig.DEFAULT,
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )
    val names = tools.initialToolRepoToolClasses.map { it.toolName().toolName }.toSet()

    // core_interaction.yaml lists only mobile drivers — Playwright should not see these.
    assertFalse("tap" in names, "Playwright must not get core_interaction 'tap'")
    assertFalse("hideKeyboard" in names, "Playwright must not get core_interaction 'hideKeyboard'")
    assertFalse("tapOnPoint" in names, "Playwright must not get core_interaction 'tapOnPoint'")
    // Driver-agnostic toolsets still flow through.
    assertTrue("objectiveStatus" in names, "meta tools are driver-agnostic; must remain")
    assertTrue("rememberText" in names, "memory tools are driver-agnostic; must remain")
  }

  @Test
  fun `initialToolRepoToolClasses default keeps mobile tools when driverType is Android`() {
    val tools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      config = TrailblazeConfig.DEFAULT,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    val names = tools.initialToolRepoToolClasses.map { it.toolName().toolName }.toSet()
    assertTrue("tap" in names, "Android is a compatible driver for core_interaction")
    assertTrue("hideKeyboard" in names, "Android sees core_interaction tools")
    assertTrue("objectiveStatus" in names, "meta still present")
  }

  @Test
  fun `initialToolRepoToolClasses default falls back to DefaultLlmTrailblazeTools without driverType`() {
    // Regression guard: callers that don't pass driverType keep the pre-existing behavior —
    // the full catalog surface, driver-blind.
    val tools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      config = TrailblazeConfig.DEFAULT,
    )
    val names = tools.initialToolRepoToolClasses.map { it.toolName().toolName }.toSet()
    val defaultNames = TrailblazeToolSet.DefaultLlmTrailblazeTools.map { it.toolName().toolName }.toSet()
    // Without driverType, every class in DefaultLlmTrailblazeTools should appear.
    assertTrue(
      defaultNames.all { it in names },
      "Without driverType, initialToolRepoToolClasses should contain DefaultLlmTrailblazeTools",
    )
  }

  // -- toTrailblazeToolRepo extension: pins the forward used by AndroidTrailblazeRule + BlockAndroidTrailblazeRule --

  @Test
  fun `toTrailblazeToolRepo forwards driverType so Playwright repo drops mobile-only tools`() {
    // If the rule wiring ever stops forwarding driverType to withDynamicToolSets, this test
    // fails. Isolated layer tests (CustomTrailblazeTools defaults, withDynamicToolSets in
    // isolation) would both still pass — that's the regression shape a prior review catch
    // guarded against. This pins the connective tissue.
    val tools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      config = TrailblazeConfig.DEFAULT,
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )
    val repo = tools.toTrailblazeToolRepo()
    val names = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertFalse("tap" in names, "driverType was not forwarded to withDynamicToolSets' core filter")
    assertFalse("hideKeyboard" in names, "driverType was not forwarded to withDynamicToolSets' core filter")
    assertTrue("objectiveStatus" in names, "meta still present (driver-agnostic)")
  }

  // -- YAML tool name symmetry (this PR's focus): first-class YAML-defined tools --
  //
  // `CustomTrailblazeTools` now carries YAML tool names alongside class-backed tools.
  // Rule authors who reference YAML-defined tools (e.g. `compose_click`, `eraseText`) can
  // add them to the initial surface directly, without going through a full toolset-id
  // indirection. These tests pin that the YAML fields flow through the same way class
  // fields do.

  @Test
  fun `initialToolRepoYamlToolNames default includes driver-compatible YAML tools`() {
    // Playwright ONLY gets driver-agnostic toolsets (meta, memory) at the alwaysEnabled level,
    // but driver-compatible YAML tools should still flow from the full catalog scan. On
    // Android, core_interaction contributes YAML-defined tools like `eraseText`.
    val androidTools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      config = TrailblazeConfig.DEFAULT,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    val androidYamlNames = androidTools.initialToolRepoYamlToolNames.map { it.toolName }.toSet()
    assertTrue(
      "eraseText" in androidYamlNames,
      "core_interaction YAML-defined 'eraseText' should appear for Android",
    )
  }

  @Test
  fun `initialToolRepoYamlToolNames default drops mobile-only YAML tools for Playwright`() {
    val playwrightTools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      config = TrailblazeConfig.DEFAULT,
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )
    val playwrightYamlNames = playwrightTools.initialToolRepoYamlToolNames.map { it.toolName }.toSet()
    assertFalse(
      "eraseText" in playwrightYamlNames,
      "core_interaction's 'eraseText' is mobile-only and must not appear on Playwright",
    )
  }

  @Test
  fun `registeredAppSpecificYamlToolNames flows into initialToolRepoYamlToolNames default`() {
    // Rule authors can add a YAML tool by name without needing a backing KClass.
    val tools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      registeredAppSpecificYamlToolNames = setOf(ToolName("my_custom_yaml_tool")),
      config = TrailblazeConfig.DEFAULT,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    val names = tools.initialToolRepoYamlToolNames.map { it.toolName }.toSet()
    assertTrue(
      "my_custom_yaml_tool" in names,
      "App-specific YAML tool name should flow into the default initial set",
    )
  }

  @Test
  fun `toTrailblazeToolRepo forwards YAML tool names so they surface in descriptors`() {
    // This is the end-to-end test: a rule author adds a custom YAML tool name via
    // CustomTrailblazeTools, and the resulting TrailblazeToolRepo advertises it as a
    // descriptor. If the extension drops `customYamlToolNames` when forwarding to
    // withDynamicToolSets, this test fails.
    val tools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      registeredAppSpecificYamlToolNames = setOf(ToolName("eraseText")),
      config = TrailblazeConfig.DEFAULT,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )
    val repo = tools.toTrailblazeToolRepo()
    val descriptorNames = repo.getCurrentToolDescriptors().map { it.name }.toSet()
    assertTrue(
      "eraseText" in descriptorNames,
      "Custom YAML tool name must reach the tool descriptor registry",
    )
  }

  @Test
  fun `toTrailblazeToolRepo forwards toolSetCatalog so custom catalogs drive resolution`() {
    // Two independent fields need to flow through the extension. If `toolSetCatalog` is
    // dropped from the forward, dynamic toolset switching (setActiveToolSets) silently
    // resolves against the classpath catalog instead of the caller's. To catch that, the
    // custom catalog must be *different* from the default — here we strip the `navigation`
    // entry. If the forward works, `navigation` is absent and `setActiveToolSets` reports
    // "Unknown toolset IDs". If the forward were dropped, the classpath default still has
    // `navigation` and the call would succeed — a failure of this test.
    val customCatalog = TrailblazeToolSetCatalog.defaultEntries().filter { it.id != "navigation" }
    val tools = CustomTrailblazeTools(
      registeredAppSpecificLlmTools = emptySet(),
      config = TrailblazeConfig.DEFAULT,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      toolSetCatalog = customCatalog,
    )
    val repo = tools.toTrailblazeToolRepo()
    val result = repo.setActiveToolSets(listOf("navigation"))
    assertTrue(
      "Unknown toolset IDs" in result,
      "toolSetCatalog was not forwarded to the resulting repo — navigation was stripped from " +
        "the custom catalog but setActiveToolSets resolved it anyway (result was: $result)",
    )
  }
}
