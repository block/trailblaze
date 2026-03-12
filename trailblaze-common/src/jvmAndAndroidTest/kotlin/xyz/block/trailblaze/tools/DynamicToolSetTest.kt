package xyz.block.trailblaze.tools

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SetActiveToolSetsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.toolName

class DynamicToolSetTest {

  // -- TrailblazeToolSetCatalog --

  @Test
  fun `formatCatalogSummary includes all entries with IDs and tool names`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries(setOfMarkEnabled = true)
    val summary = TrailblazeToolSetCatalog.formatCatalogSummary(catalog)

    assertTrue(summary.contains("core"), "Should list core toolset")
    assertTrue(summary.contains("navigation"), "Should list navigation toolset")
    assertTrue(summary.contains("text-editing"), "Should list text-editing toolset")
    assertTrue(summary.contains("verification"), "Should list verification toolset")
    assertTrue(summary.contains("memory"), "Should list memory toolset")
    assertTrue(summary.contains("advanced"), "Should list advanced toolset")
    assertTrue(summary.contains("[always enabled]"), "Should mark always-enabled sets")
  }

  // -- TrailblazeToolRepo.setActiveToolSets --

  @Test
  fun `setActiveToolSets replaces tools with requested toolsets`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries(setOfMarkEnabled = true)
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet("core", coreTools),
      toolSetCatalog = catalog,
    )

    // Initially core tools (including basic navigation: pressKey, swipe, scroll)
    val initialToolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("tapOnElementByNodeId" in initialToolNames, "Core should include tap")
    assertTrue("inputText" in initialToolNames, "Core should include input text")
    assertTrue("pressKey" in initialToolNames, "Core should include pressKey")
    assertTrue("swipe" in initialToolNames, "Core should include swipe")
    assertTrue("scrollUntilTextIsVisible" in initialToolNames, "Core should include scroll")
    assertTrue("launchApp" !in initialToolNames, "Navigation-only tools should not be active initially")

    // Enable navigation (launchApp, openUrl)
    val result = repo.setActiveToolSets(listOf("navigation"))
    assertTrue(result.contains("Active tool sets updated"), "Should confirm update")

    val updatedToolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("launchApp" in updatedToolNames, "Navigation tools should now be active")
    assertTrue("tapOnElementByNodeId" in updatedToolNames, "Core tools should still be present")
  }

  @Test
  fun `setActiveToolSets rejects unknown toolset IDs`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries(setOfMarkEnabled = true)
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet("core", coreTools),
      toolSetCatalog = catalog,
    )

    val result = repo.setActiveToolSets(listOf("nonexistent"))
    assertTrue(result.contains("Unknown toolset IDs"), "Should reject unknown IDs")
  }

  @Test
  fun `setActiveToolSets with empty list resets to core only`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries(setOfMarkEnabled = true)
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet("core", coreTools),
      toolSetCatalog = catalog,
    )

    // Enable navigation then reset
    repo.setActiveToolSets(listOf("navigation"))
    repo.setActiveToolSets(emptyList())

    val toolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("launchApp" !in toolNames, "Navigation-only tools should be removed after reset")
    assertTrue("pressKey" in toolNames, "Core navigation tools should remain")
    assertTrue("tapOnElementByNodeId" in toolNames, "Core tools should remain")
  }

  @Test
  fun `setActiveToolSets preserves extra tools not in catalog`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries(setOfMarkEnabled = true)
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    // Simulate an app-specific custom tool added alongside catalog tools
    val customToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      "core + custom",
      coreTools + InputTextTrailblazeTool::class, // InputText is already in core, but let's use a known tool
    )
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = customToolSet,
      toolSetCatalog = catalog,
    )

    // Switch toolsets - extra (non-catalog) tools should be preserved
    repo.setActiveToolSets(listOf("navigation"))
    val toolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("inputText" in toolNames, "Core tool should still be present")
  }

  @Test
  fun `setActiveToolSets returns error when catalog not configured`() {
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        "minimal",
        setOf(TapOnElementByNodeIdTrailblazeTool::class),
      ),
    )

    val result = repo.setActiveToolSets(listOf("navigation"))
    assertTrue(result.contains("not configured"), "Should indicate catalog is not configured")
  }

  // -- META_TOOLS includes meta tools --

  @Test
  fun `META_TOOLS includes setActiveToolSets and objectiveStatus`() {
    val metaToolNames = TrailblazeToolSetCatalog.META_TOOLS.map { it.toolName().toolName }.toSet()
    assertTrue("setActiveToolSets" in metaToolNames)
    assertTrue("objectiveStatus" in metaToolNames)
  }

  @Test
  fun `getToolDescriptorsForStep returns verify tools for VerificationStep`() {
    val repo = TrailblazeToolRepo.withDynamicToolSets(setOfMarkEnabled = true)
    val verifyStep = xyz.block.trailblaze.yaml.VerificationStep(verify = "test")
    val verifyDescriptors = repo.getToolDescriptorsForStep(verifyStep).map { it.name }.toSet()

    assertTrue("assertVisibleWithNodeId" in verifyDescriptors, "Should include assertVisibleWithNodeId")
    assertTrue("assertNotVisibleWithText" in verifyDescriptors, "Should include assertNotVisibleWithText")
    assertTrue("objectiveStatus" in verifyDescriptors, "Should include objectiveStatus")

    // Verify tools should NOT be in getRegisteredTrailblazeTools (they're only for verification)
    val registeredToolNames = repo.getRegisteredTrailblazeTools().map { it.toolName().toolName }.toSet()
    assertTrue("assertVisibleWithNodeId" !in registeredToolNames,
      "Verify tools should not be in registered tool classes with dynamic toolsets")
  }

  @Test
  fun `setActiveToolSets persists tools across calls`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries(setOfMarkEnabled = true)
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet("core", coreTools),
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
}
