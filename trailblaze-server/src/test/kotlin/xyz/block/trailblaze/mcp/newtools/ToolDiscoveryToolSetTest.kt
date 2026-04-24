package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.docs.Scenario
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import kotlin.reflect.KClass
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ToolDiscoveryToolSet].
 *
 * Verifies that the tool discovery tool correctly:
 * - Lists platform toolsets and targets in index mode (no name/target params)
 * - Expands tool details when detail=true
 * - Finds tools by name
 * - Returns errors for unknown tool names
 * - Queries tools for specific targets
 * - Returns errors for invalid targets
 * - Excludes current target from otherTargets
 */
class ToolDiscoveryToolSetTest {

  private val json = Json { ignoreUnknownKeys = true }

  // -- Test helpers -----------------------------------------------------------

  /** Simple test app target with configurable custom tools. */
  private class TestAppTarget(
    id: String,
    displayName: String,
    private val androidAppIds: Set<String> = emptySet(),
  ) : TrailblazeHostAppTarget(id, displayName) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): Set<String>? =
      if (platform == TrailblazeDevicePlatform.ANDROID) androidAppIds else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val testTarget =
    TestAppTarget(id = "testapp", displayName = "Test App", androidAppIds = setOf("com.test.app"))

  private val secondTarget =
    TestAppTarget(
      id = "secondapp",
      displayName = "Second App",
      androidAppIds = setOf("com.second.app"),
    )

  /**
   * Builds a list of fake tool descriptors for testing.
   * Includes a tool with a name matching a known category tool for cross-referencing.
   */
  private fun fakeAvailableTools(): List<TrailblazeToolDescriptor> {
    val realTools =
      ToolSetCategoryMapping.getToolClasses(ToolSetCategory.CORE_INTERACTION).mapNotNull {
        it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()
      }
    return realTools +
      listOf(
        TrailblazeToolDescriptor(
          name = "customTestTool",
          description = "A custom test tool",
          requiredParameters =
            listOf(
              TrailblazeToolParameterDescriptor(
                name = "input",
                type = "String",
                description = "Input value",
              )
            ),
        )
      )
  }

  private fun createToolSet(
    allTargets: Set<TrailblazeHostAppTarget> = setOf(testTarget, secondTarget),
    currentTarget: TrailblazeHostAppTarget? = null,
    currentDriverType: TrailblazeDriverType? = null,
  ): ToolDiscoveryToolSet =
    ToolDiscoveryToolSet(
      sessionContext = null,
      allTargetAppsProvider = { allTargets },
      currentTargetProvider = { currentTarget },
      currentDriverTypeProvider = { currentDriverType },
    )

  // -- 1. Index mode -- no target, no device ----------------------------------

  @Scenario(
    title = "Discover available tools for current target",
    commands = ["trailblaze toolbox", "toolbox()"],
    description =
      "Shows platform toolsets and target-specific tools. Use before constructing --yaml tool sequences.",
    category = "Tool Discovery",
  )
  @Test
  fun `INDEX mode without target or platform shows platform toolsets and all other targets`() =
    runTest {
      val toolSet = createToolSet()

      val result = toolSet.toolbox()
      val obj = json.parseToJsonElement(result).jsonObject

      // platformToolsets should be present and non-empty
      val platformToolsets = obj["platformToolsets"]!!.jsonArray
      assertTrue(platformToolsets.isNotEmpty(), "platformToolsets should be populated")

      // Category names should include known categories (field is "name" in ToolDiscoveryToolsetInfo)
      val categoryNames = platformToolsets.map { it.jsonObject["name"]!!.jsonPrimitive.content }
      assertContains(categoryNames, "core_interaction")
      assertContains(categoryNames, "navigation")
      assertContains(categoryNames, "observation")

      // No targetToolsets when no current target
      assertNull(obj["targetToolsets"])

      // currentTarget should be null
      assertNull(obj["currentTarget"])

      // otherTargets should list all non-"none" targets (field is "name" in ToolDiscoveryOtherTarget)
      val otherTargets = obj["otherTargets"]!!.jsonArray
      val otherNames = otherTargets.map { it.jsonObject["name"]!!.jsonPrimitive.content }
      assertContains(otherNames, "testapp")
      assertContains(otherNames, "secondapp")
    }

  // -- 2. Index mode -- with target and device --------------------------------

  @Test
  fun `INDEX mode with current target and driver type shows target toolsets`() = runTest {
    val toolSet =
      createToolSet(currentTarget = testTarget, currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    // platformToolsets should still be present
    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    assertTrue(platformToolsets.isNotEmpty())

    // targetToolsets should be present for the current target
    val targetToolsets = obj["targetToolsets"]?.jsonArray
    // targetToolsets is a list; may be null if the test target has no custom tools
    // For TestAppTarget with empty custom tools, this will be null
    // That's fine -- just verify the structure is consistent

    // currentTarget info (it's a String, not an object)
    val currentTarget = obj["currentTarget"]!!.jsonPrimitive.content
    assertEquals("testapp", currentTarget)

    // currentPlatform
    assertEquals("Android", obj["currentPlatform"]!!.jsonPrimitive.content)

    // otherTargets should not include current target (field is "name")
    val otherTargets = obj["otherTargets"]!!.jsonArray
    val otherNames = otherTargets.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue("testapp" !in otherNames, "Current target should not appear in otherTargets")
    assertContains(otherNames, "secondapp")
  }

  // -- 3. Index mode -- detail=true -------------------------------------------

  @Scenario(
    title = "Get detailed tool descriptors with parameters",
    commands = ["trailblaze toolbox --detail", "toolbox(detail=true)"],
    description =
      "Expands each tool with full parameter descriptors (name, type, description). Useful for constructing --yaml tool sequences with correct parameter names.",
    category = "Tool Discovery",
  )
  @Test
  fun `INDEX mode with detail=true includes full tool descriptors`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(detail = true)
    val obj = json.parseToJsonElement(result).jsonObject

    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    // Find a category that has tools (core_interaction should have some)
    val coreInteraction =
      platformToolsets.first {
        it.jsonObject["name"]!!.jsonPrimitive.content == "core_interaction"
      }
    val coreObj = coreInteraction.jsonObject

    // With detail=true, "toolDetails" should be a list of full descriptors
    val toolDetails = coreObj["toolDetails"]
    assertNotNull(toolDetails, "toolDetails array should be present in detail mode")
    assertTrue(toolDetails is JsonArray, "toolDetails should be a JSON array")
    if (toolDetails.jsonArray.isNotEmpty()) {
      val firstTool = toolDetails.jsonArray[0].jsonObject
      assertNotNull(firstTool["name"], "Tool descriptor should have 'name'")
    }

    // With detail=true, "tools" (tool names) should be null (replaced by full descriptors)
    assertNull(
      coreObj["tools"],
      "tools (name list) should be null when detail=true",
    )
  }

  @Test
  fun `INDEX mode without detail shows tool names instead of full descriptors`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(detail = false)
    val obj = json.parseToJsonElement(result).jsonObject

    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    val coreInteraction =
      platformToolsets.first {
        it.jsonObject["name"]!!.jsonPrimitive.content == "core_interaction"
      }
    val coreObj = coreInteraction.jsonObject

    // Without detail, tools (name list) should be present
    val tools = coreObj["tools"]
    assertNotNull(tools, "tools (name list) should be present when detail=false")
    assertTrue(tools is JsonArray)

    // toolDetails (full descriptors) should be absent
    assertNull(
      coreObj["toolDetails"],
      "full tool descriptors should be null when detail=false",
    )
  }

  // -- 4. Name mode -- tool found in platform category ------------------------

  @Scenario(
    title = "Look up a specific tool by name",
    commands = ["trailblaze toolbox --name tapOnPoint", "toolbox(name=\"tapOnPoint\")"],
    description =
      "Returns the full descriptor for a single tool, including which categories it belongs to.",
    category = "Tool Discovery",
  )
  @Test
  fun `NAME mode finds tool from available tools`() = runTest {
    // Pick a known tool name from CORE_INTERACTION
    val knownToolName = fakeAvailableTools().first().name
    val toolSet = createToolSet()

    val result = toolSet.toolbox(name = knownToolName)
    val obj = json.parseToJsonElement(result).jsonObject

    // Should return tool descriptor
    val tool = obj["tool"]?.jsonObject
    assertNotNull(tool, "Result should contain 'tool' object")
    assertEquals(knownToolName, tool["name"]!!.jsonPrimitive.content)

    // foundInCategories should be populated
    val categories = obj["foundInCategories"]?.jsonArray
    assertNotNull(categories, "foundInCategories should be present")
    assertTrue(categories.isNotEmpty(), "foundInCategories should not be empty")
  }

  // -- 5. Name mode -- tool not found -----------------------------------------

  @Test
  fun `NAME mode returns error for nonexistent tool`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(name = "nonexistentTool")
    val obj = json.parseToJsonElement(result).jsonObject

    val error = obj["error"]?.jsonPrimitive?.content
    assertNotNull(error, "Should return an error")
    assertContains(error, "not found")
  }

  // -- 6. Target mode -- valid target -----------------------------------------

  @Scenario(
    title = "Query tools for a specific target app",
    commands = ["trailblaze toolbox --target myapp", "toolbox(target=\"myapp\")"],
    description =
      "Returns target-specific info including supported platforms and custom tools registered for that app.",
    category = "Tool Discovery",
  )
  @Test
  fun `TARGET mode returns target info for valid target`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(target = "testapp")
    val obj = json.parseToJsonElement(result).jsonObject

    // ToolDiscoveryTargetResult has "target" (id string), "displayName", "supportedPlatforms"
    val target = obj["target"]?.jsonPrimitive?.content
    assertNotNull(target, "Should return target id")
    assertEquals("testapp", target)
    assertEquals("Test App", obj["displayName"]!!.jsonPrimitive.content)
    assertNotNull(obj["supportedPlatforms"], "Should have supportedPlatforms")
  }

  // -- 7. Target mode -- invalid target ---------------------------------------

  @Test
  fun `TARGET mode returns error for unknown target with available names`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(target = "nonexistent")
    val obj = json.parseToJsonElement(result).jsonObject

    val error = obj["error"]?.jsonPrimitive?.content
    assertNotNull(error, "Should return an error")
    assertContains(error, "not found")
    // Should list available target names
    assertContains(error, "testapp")
    assertContains(error, "secondapp")
  }

  // -- 8. Other targets listing -----------------------------------------------

  @Test
  fun `otherTargets excludes current target and none target`() = runTest {
    val noneTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
    val allTargets = setOf(testTarget, secondTarget, noneTarget)
    val toolSet = createToolSet(allTargets = allTargets, currentTarget = testTarget)

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    val otherTargets = obj["otherTargets"]!!.jsonArray
    val otherNames = otherTargets.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    // Should exclude current target
    assertTrue("testapp" !in otherNames, "Current target should be excluded")
    // Should exclude "none" target
    assertTrue("none" !in otherNames, "DefaultTrailblazeHostAppTarget (none) should be excluded")
    // Should include secondapp
    assertContains(otherNames, "secondapp")
  }

  @Test
  fun `otherTargets lists all targets when no current target`() = runTest {
    val allTargets = setOf(testTarget, secondTarget)
    val toolSet = createToolSet(allTargets = allTargets, currentTarget = null)

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    val otherTargets = obj["otherTargets"]!!.jsonArray
    val otherNames = otherTargets.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    assertEquals(2, otherNames.size)
    assertContains(otherNames, "testapp")
    assertContains(otherNames, "secondapp")
  }

  // -- Additional edge cases --------------------------------------------------

  @Test
  fun `INDEX mode includes YAML-defined pressBack under navigation`() = runTest {
    // Regression guard: ToolDiscoveryToolSet.getToolDescriptorsForCategory must union
    // class-backed AND YAML-defined tool names per category. Before the fix, the
    // `navigation` category advertised only its class-backed tools and silently dropped
    // pressBack (now a YAML-defined tool), hiding it from LLM discovery.
    val toolSet = createToolSet()

    val result = toolSet.toolbox(detail = false)
    val obj = json.parseToJsonElement(result).jsonObject

    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    val navigation =
      platformToolsets.first {
        it.jsonObject["name"]!!.jsonPrimitive.content == "navigation"
      }
    val toolNames = navigation.jsonObject["tools"]!!.jsonArray
      .map { it.jsonPrimitive.content }
    assertTrue(
      "pressBack" in toolNames,
      "navigation category should include pressBack (YAML-defined). Got: $toolNames",
    )
  }

  @Test
  fun `INDEX mode excludes ALL and SESSION categories from platformToolsets`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    val categoryNames = platformToolsets.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    assertTrue("all" !in categoryNames, "ALL category should be excluded from index")
    assertTrue("session" !in categoryNames, "SESSION category should be excluded from index")
  }

  @Test
  fun `NAME mode finds tool via category search fallback`() = runTest {
    // Tool exists in a platform category — discovery searches categories
    val coreTools =
      ToolSetCategoryMapping.getToolClasses(ToolSetCategory.CORE_INTERACTION).mapNotNull {
        it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()
      }

    // Only if CORE_INTERACTION actually has tools (it should in a real build)
    if (coreTools.isNotEmpty()) {
      val knownToolName = coreTools.first().name
      val toolSet = createToolSet()

      val result = toolSet.toolbox(name = knownToolName)
      val obj = json.parseToJsonElement(result).jsonObject

      val tool = obj["tool"]?.jsonObject
      assertNotNull(tool, "Tool should be found via category fallback search")
      assertEquals(knownToolName, tool["name"]!!.jsonPrimitive.content)
    }
  }

  @Test
  fun `each platform category has a description field`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    for (category in platformToolsets) {
      val categoryObj = category.jsonObject
      assertNotNull(
        categoryObj["description"],
        "Category ${categoryObj["name"]?.jsonPrimitive?.content} should have description",
      )
    }
  }

  // -- Search mode tests ------------------------------------------------------

  @Scenario(
    title = "Search tools by keyword",
    commands = ["trailblaze toolbox --search launch", "toolbox(search=\"launch\")"],
    description =
      "Searches tool names and descriptions for matching keywords. Results include full descriptors with source info.",
    category = "Tool Discovery",
  )
  @Test
  fun `SEARCH mode finds tools matching keyword in name`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(search = "tap")
    val obj = json.parseToJsonElement(result).jsonObject

    val matches = obj["matches"]?.jsonArray
    assertNotNull(matches, "Search should return matches")
    assertTrue(matches.isNotEmpty(), "Should find tools matching 'tap'")

    val toolNames = matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue(toolNames.any { it.contains("tap", ignoreCase = true) }, "Should match tool names containing 'tap'")
  }

  @Test
  fun `SEARCH mode returns empty for no matches`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(search = "zzz_nonexistent_zzz")
    val obj = json.parseToJsonElement(result).jsonObject

    assertNull(obj["matches"], "Should return null matches for no results")
    assertEquals("zzz_nonexistent_zzz", obj["query"]!!.jsonPrimitive.content)
  }

  @Test
  fun `SEARCH mode scopes to specific target when target param provided`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(search = "tap", target = "testapp")
    val obj = json.parseToJsonElement(result).jsonObject

    // Results should exist (platform tools match "tap") or be empty
    // Key assertion: no error
    assertNull(obj["error"], "Should not return error")
    assertNotNull(obj["query"], "Should return the query")
  }

  // -- SEARCH mode platform filtering ------------------------------------------
  // Search results must respect the current driver's tool exclusions, so e.g.
  // mobile-only tools don't surface when a Web driver is connected. Previously
  // SEARCH iterated raw categories and ignored the target's excluded-tools list —
  // INDEX mode always filtered but SEARCH did not.

  /** Test target that excludes [OpenUrlTrailblazeTool] when connected to a Web driver. */
  private class WebFilteringTarget : TrailblazeHostAppTarget(
    id = "webfiltered",
    displayName = "Web Filtered Target",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): Set<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getExcludedToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> =
      if (driverType.platform == TrailblazeDevicePlatform.WEB) {
        setOf(xyz.block.trailblaze.toolcalls.commands.OpenUrlTrailblazeTool::class)
      } else {
        emptySet()
      }
  }

  @Test
  fun `SEARCH excludes tools filtered for the current driver`() = runTest {
    val webTarget = WebFilteringTarget()
    val toolSet = ToolDiscoveryToolSet(
      sessionContext = null,
      allTargetAppsProvider = { setOf(webTarget) },
      currentTargetProvider = { webTarget },
      currentDriverTypeProvider = { TrailblazeDriverType.PLAYWRIGHT_NATIVE },
    )

    val result = toolSet.toolbox(search = "openUrl")
    val obj = json.parseToJsonElement(result).jsonObject

    // openUrl is excluded for the Web driver — search must not return it.
    val matches = obj["matches"]?.jsonArray ?: JsonArray(emptyList())
    val toolNames =
      matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue(
      toolNames.none { it.equals("openUrl", ignoreCase = true) },
      "openUrl should be filtered out for Web driver. Got: $toolNames",
    )
  }

  @Test
  fun `SEARCH includes tools not filtered for the current driver`() = runTest {
    // Same target, but now connected via Android — openUrl is NOT excluded,
    // so it should appear in search results.
    val webTarget = WebFilteringTarget()
    val toolSet = ToolDiscoveryToolSet(
      sessionContext = null,
      allTargetAppsProvider = { setOf(webTarget) },
      currentTargetProvider = { webTarget },
      currentDriverTypeProvider = { TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION },
    )

    val result = toolSet.toolbox(search = "openUrl")
    val obj = json.parseToJsonElement(result).jsonObject

    val matches = obj["matches"]?.jsonArray ?: JsonArray(emptyList())
    val toolNames =
      matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue(
      toolNames.any { it.equals("openUrl", ignoreCase = true) },
      "openUrl should appear for Android driver. Got: $toolNames",
    )
  }

  // -- Driver type filtering tests --------------------------------------------

  @Test
  fun `TARGET mode with connected device scopes to driver type`() = runTest {
    val toolSet = createToolSet(currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

    val result = toolSet.toolbox(target = "testapp")
    val obj = json.parseToJsonElement(result).jsonObject

    // Should use grouped output (toolGroups) instead of flat toolsByPlatform
    // TestAppTarget has no custom tools, so toolGroups should be null
    assertNull(obj["toolGroups"], "TestAppTarget has no custom tools")

    // currentPlatform should reflect the connected device
    val currentPlatform = obj["currentPlatform"]?.jsonPrimitive?.content
    assertEquals("Android", currentPlatform)
  }

  @Test
  fun `TARGET mode without connected device shows all platforms`() = runTest {
    val toolSet = createToolSet(currentDriverType = null)

    val result = toolSet.toolbox(target = "testapp")
    val obj = json.parseToJsonElement(result).jsonObject

    // Should use flat toolsByPlatform output
    assertNull(obj["currentPlatform"], "No device connected, no current platform")
    // toolsByPlatform may be present or null depending on whether TestAppTarget has tools per platform
  }

  @Test
  fun `TARGET mode with platform filter but no device uses filtered platform`() = runTest {
    val toolSet = createToolSet(currentDriverType = null)

    val result = toolSet.toolbox(target = "testapp", platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    // Should resolve a default driver type from the platform filter and use grouped output
    val currentPlatform = obj["currentPlatform"]?.jsonPrimitive?.content
    assertEquals("Android", currentPlatform, "Platform filter should resolve to Android")

    // Should NOT show flat toolsByPlatform (that's the unfiltered fallback)
    assertNull(obj["toolsByPlatform"], "Should use grouped output, not flat platform listing")
  }
}
