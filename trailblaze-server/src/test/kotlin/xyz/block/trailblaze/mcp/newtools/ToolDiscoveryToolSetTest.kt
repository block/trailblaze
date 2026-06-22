package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.docs.Scenario
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolSourceType
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import kotlin.reflect.KClass
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private val androidAppIds: List<String> = emptyList(),
  ) : TrailblazeHostAppTarget(id, displayName) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.ANDROID) androidAppIds else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val testTarget =
    TestAppTarget(id = "testapp", displayName = "Test App", androidAppIds = listOf("com.test.app"))

  private val secondTarget =
    TestAppTarget(
      id = "secondapp",
      displayName = "Second App",
      androidAppIds = listOf("com.second.app"),
    )

  private val inlineToolTarget = object : TrailblazeHostAppTarget(
    id = "inlineapp",
    displayName = "Inline App",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.WEB) listOf("inline-web") else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getInlineScriptTools(): List<InlineScriptToolConfig> = listOf(
      InlineScriptToolConfig(
        script = "./tools/web_inline.ts",
        name = "web_inline_script_tool",
        description = "Drive a sample web page through an inline script tool",
        meta = buildJsonObject {
          put("trailblaze/supportedPlatforms", buildJsonArray { add(JsonPrimitive("WEB")) })
          put("trailblaze/toolset", "web_core")
        },
        inputSchema = buildJsonObject {
          put("type", "object")
          put("required", buildJsonArray { add(JsonPrimitive("relativePath")) })
          put("properties", buildJsonObject {
            put("relativePath", buildJsonObject { put("type", "string") })
            put("count", buildJsonObject { put("type", "integer") })
          })
        },
      )
    )
  }

  private val iosInlineToolTarget = object : TrailblazeHostAppTarget(
    id = "iosinlineapp",
    displayName = "iOS Inline App",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.IOS) listOf("com.apple.MobileAddressBook") else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getInlineScriptTools(): List<InlineScriptToolConfig> = listOf(
      InlineScriptToolConfig(
        script = "./tools/contacts_ios_createContact.js",
        name = "contacts_ios_createContact",
        description = "Create a contact in the iOS Contacts app through an inline script tool",
        meta = buildJsonObject {
          put("trailblaze/supportedPlatforms", buildJsonArray { add(JsonPrimitive("IOS")) })
          put("trailblaze/toolset", "ios_contacts")
        },
        inputSchema = buildJsonObject {
          put("type", "object")
          put("properties", buildJsonObject {
            put("firstName", buildJsonObject { put("type", "string") })
            put("lastName", buildJsonObject { put("type", "string") })
            put("phoneNumber", buildJsonObject { put("type", "string") })
          })
        },
      )
    )
  }

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

      // otherTargets should list all non-"default" targets (field is "name" in ToolDiscoveryOtherTarget)
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

  @Test
  fun `INDEX mode hides a scripted tool the target excludes via excluded_tools`() = runTest {
    // Discovery must honor scripted `excluded_tools:` the same way the LLM surface does. openUrl is
    // a scripted (.ts) tool delivered by core_interaction/navigation; a target that opts out of it
    // must not see it advertised by `toolbox`. Exercises the scripted partition of
    // getExcludedToolSurfaceForDriver routed through getExcludedToolNames.
    val android = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION

    fun toolNamesInPlatformToolsets(resultJson: String): Set<String> =
      json.parseToJsonElement(resultJson).jsonObject["platformToolsets"]!!.jsonArray
        .flatMap { it.jsonObject["tools"]?.jsonArray.orEmpty() }
        .map { it.jsonPrimitive.content }
        .toSet()

    // Baseline: a target with no exclusions surfaces openUrl, so the negative assertion isn't vacuous.
    val baseline = createToolSet(currentTarget = testTarget, currentDriverType = android)
    assertContains(toolNamesInPlatformToolsets(baseline.toolbox()), "openUrl")

    // A target that excludes openUrl (scripted) must not see it advertised in discovery output.
    val excludingTarget = object : TrailblazeHostAppTarget(
      id = "excludesopenurl",
      displayName = "Excludes openUrl",
    ) {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
        if (platform == TrailblazeDevicePlatform.ANDROID) listOf("com.excludes.app") else null

      override fun internalGetCustomToolsForDriver(
        driverType: TrailblazeDriverType,
      ): Set<KClass<out TrailblazeTool>> = emptySet()

      override fun getExcludedScriptedToolNamesForDriver(
        driverType: TrailblazeDriverType,
      ): Set<ToolName> = setOf(ToolName("openUrl"))
    }
    val toolSet = createToolSet(
      allTargets = setOf(excludingTarget),
      currentTarget = excludingTarget,
      currentDriverType = android,
    )
    assertFalse(
      "openUrl" in toolNamesInPlatformToolsets(toolSet.toolbox()),
      "openUrl was excluded via excluded_tools — discovery must hide it from platformToolsets",
    )
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
  fun `INDEX mode without detail still includes toolDetails so the CLI can render description peeks`() = runTest {
    // The compact toolbox listing renders `- name: <first-line description>` per tool,
    // which means it needs descriptions even when `detail=false`. Server-side, that
    // means `toolDetails` is now ALWAYS populated; the legacy `tools` (name-only) field
    // remains present for backward compatibility with any other consumers.
    val toolSet = createToolSet()

    val result = toolSet.toolbox(detail = false)
    val obj = json.parseToJsonElement(result).jsonObject

    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    val coreInteraction =
      platformToolsets.first {
        it.jsonObject["name"]!!.jsonPrimitive.content == "core_interaction"
      }
    val coreObj = coreInteraction.jsonObject

    // Without detail, both `tools` (name list, legacy) and `toolDetails` (rich list, new
    // requirement for compact-mode peek) should be present.
    val tools = coreObj["tools"]
    assertNotNull(tools, "tools (name list) should be present when detail=false")
    assertTrue(tools is JsonArray)

    val toolDetails = coreObj["toolDetails"]
    assertNotNull(toolDetails, "toolDetails should also be present so compact mode can peek descriptions")
    assertTrue(toolDetails is JsonArray)
    assertTrue(toolDetails.isNotEmpty(), "toolDetails should not be empty for core_interaction")
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
  fun `otherTargets excludes current target and default target`() = runTest {
    val defaultTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
    val allTargets = setOf(testTarget, secondTarget, defaultTarget)
    val toolSet = createToolSet(allTargets = allTargets, currentTarget = testTarget)

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    val otherTargets = obj["otherTargets"]!!.jsonArray
    val otherNames = otherTargets.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    // Should exclude current target
    assertTrue("testapp" !in otherNames, "Current target should be excluded")
    // Should exclude "default" target
    assertTrue(
      TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id !in otherNames,
      "DefaultTrailblazeHostAppTarget (default) should be excluded",
    )
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

  /**
   * Test target masquerading as the default target with both class-backed and YAML-defined
   * tools. Uses [TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id] so [buildPlatformToolsets]
   * recognizes it as the platform-tools source (was renamed from `"none"` → `"default"`).
   *
   * Mirrors the production YAML-backed target (`default.yaml`) where the toolsets reference some
   * tools by class binding (e.g. `hideKeyboard`) and others by name only (e.g. `eraseText`, `pressBack`
   * in `core_interaction.yaml` / `navigation.yaml`).
   */
  private class MixedNoneTarget(
    private val classTools: Set<KClass<out TrailblazeTool>>,
    private val yamlNames: Set<ToolName>,
  ) : TrailblazeHostAppTarget(
    id = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id,
    displayName = "Default",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = classTools

    override fun getCustomYamlToolNamesForDriver(
      driverType: TrailblazeDriverType,
    ): Set<ToolName> = yamlNames
  }

  @Test
  fun `INDEX mode lists YAML-defined tools from a YAML-backed default target`() = runTest {
    // Regression guard: when the default target is loaded from YAML (`default.yaml`,
    // formerly `none.yaml`), its single tool group exposes BOTH class-backed and YAML-defined
    // tools. Before the fix, buildPlatformToolsets only iterated `group.toolClasses` and
    // silently dropped name-only entries like `eraseText` and `pressBack` — even though a class
    // tool in the same group (`hideKeyboard` here) made `groups.isNotEmpty()` true and bypassed the
    // working DISCOVERABLE_CATEGORIES fallback.
    val defaultTarget = MixedNoneTarget(
      classTools = setOf(xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool::class),
      yamlNames = setOf(ToolName("pressBack"), ToolName("eraseText")),
    )
    val toolSet = createToolSet(allTargets = setOf(defaultTarget))

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    val defaultGroup = platformToolsets.firstOrNull {
      it.jsonObject["name"]!!.jsonPrimitive.content ==
        TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id
    }?.jsonObject
    assertNotNull(
      defaultGroup,
      "platformToolsets should contain the default group from the YAML-backed target",
    )

    val tools = defaultGroup["tools"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertContains(tools, "hideKeyboard", "Class-backed hideKeyboard should still appear in default listing. Got: $tools")
    assertContains(tools, "pressBack", "YAML-defined pressBack should appear in default listing. Got: $tools")
    assertContains(tools, "eraseText", "YAML-defined eraseText should appear in default listing. Got: $tools")
  }

  /** Test target masquerading as the default target that excludes a YAML-defined tool. */
  private class YamlExcludingNoneTarget : TrailblazeHostAppTarget(
    id = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id,
    displayName = "Default",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> =
      setOf(xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool::class)

    override fun getCustomYamlToolNamesForDriver(
      driverType: TrailblazeDriverType,
    ): Set<ToolName> = setOf(ToolName("pressBack"), ToolName("eraseText"))

    override fun getExcludedYamlToolNamesForDriver(
      driverType: TrailblazeDriverType,
    ): Set<ToolName> = setOf(ToolName("pressBack"))
  }

  @Test
  fun `INDEX mode honors YAML-name exclusions in addition to class exclusions`() = runTest {
    // Symmetric counterpart to the inclusion fix: a target's `excluded_tools` can
    // name a YAML-defined tool (e.g. `pressBack`) and discovery must drop it from
    // the listing the same way it would drop a class-backed exclusion.
    val target = YamlExcludingNoneTarget()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentTarget = target,
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    val platformToolsets = obj["platformToolsets"]!!.jsonArray
    val defaultGroup = platformToolsets.first {
      it.jsonObject["name"]!!.jsonPrimitive.content ==
        TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id
    }.jsonObject
    val tools = defaultGroup["tools"]!!.jsonArray.map { it.jsonPrimitive.content }

    assertTrue(
      "pressBack" !in tools,
      "Excluded YAML-defined pressBack must NOT appear in default listing. Got: $tools",
    )
    assertContains(tools, "eraseText", "Non-excluded YAML tool eraseText should still appear. Got: $tools")
    assertContains(tools, "hideKeyboard", "Non-excluded class tool hideKeyboard should still appear. Got: $tools")
  }

  @Test
  fun `toMergedDescriptors deduplicates names appearing in both class and YAML buckets`() = runTest {
    // Defensive: today's YAML-backed target enforces mutual exclusion via the resolver,
    // but a hand-built ToolGroup could legitimately list the same name in both buckets
    // (e.g. a transitional target). The renderer must not double-list the row.
    val collidingGroup = TrailblazeHostAppTarget.ToolGroup(
      id = "collide",
      description = "test",
      toolClasses = setOf(xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool::class),
      yamlToolNames = setOf(ToolName("hideKeyboard"), ToolName("pressBack")),
    )

    val descriptors = collidingGroup.toMergedDescriptors()
    val names = descriptors.map { it.name }

    assertEquals(
      names.size, names.toSet().size,
      "toMergedDescriptors must dedupe by name. Got duplicates in: $names",
    )
    assertContains(names, "hideKeyboard")
    assertContains(names, "pressBack")
  }

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

  /** Test target that excludes [HideKeyboardTrailblazeTool] when connected to a Web driver. */
  private class WebFilteringTarget : TrailblazeHostAppTarget(
    id = "webfiltered",
    displayName = "Web Filtered Target",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getExcludedToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> =
      if (driverType.platform == TrailblazeDevicePlatform.WEB) {
        setOf(xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool::class)
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

    val result = toolSet.toolbox(search = "hideKeyboard")
    val obj = json.parseToJsonElement(result).jsonObject

    // hideKeyboard is excluded for the Web driver — search must not return it.
    val matches = obj["matches"]?.jsonArray ?: JsonArray(emptyList())
    val toolNames =
      matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue(
      toolNames.none { it.equals("hideKeyboard", ignoreCase = true) },
      "hideKeyboard should be filtered out for Web driver. Got: $toolNames",
    )
  }

  @Test
  fun `SEARCH includes tools not filtered for the current driver`() = runTest {
    // Same target, but now connected via Android — hideKeyboard is NOT excluded,
    // so it should appear in search results.
    val webTarget = WebFilteringTarget()
    val toolSet = ToolDiscoveryToolSet(
      sessionContext = null,
      allTargetAppsProvider = { setOf(webTarget) },
      currentTargetProvider = { webTarget },
      currentDriverTypeProvider = { TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION },
    )

    val result = toolSet.toolbox(search = "hideKeyboard")
    val obj = json.parseToJsonElement(result).jsonObject

    val matches = obj["matches"]?.jsonArray ?: JsonArray(emptyList())
    val toolNames =
      matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue(
      toolNames.any { it.equals("hideKeyboard", ignoreCase = true) },
      "hideKeyboard should appear for Android driver. Got: $toolNames",
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

  /**
   * Target with platform-specific tool sets: different custom classes register against Android vs
   * iOS vs Web drivers. Used to verify that the CLI platform filter resolves to the
   * platform-appropriate driver even when the daemon already holds a different driver type. Uses
   * class-backed tools (not YAML names) to avoid coupling to the YAML name registry — what we
   * care about is the (platform, driver) → tool routing.
   */
  private class PerPlatformTarget : TrailblazeHostAppTarget(
    id = "perplatform",
    displayName = "Per Platform App",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> listOf("com.perplatform.android")
        TrailblazeDevicePlatform.IOS -> listOf("com.perplatform.ios")
        TrailblazeDevicePlatform.WEB -> listOf("perplatform-web")
        else -> null
      }

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = when (driverType.platform) {
      TrailblazeDevicePlatform.ANDROID ->
        setOf(xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool::class)
      TrailblazeDevicePlatform.IOS ->
        setOf(xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool::class)
      TrailblazeDevicePlatform.WEB ->
        setOf(xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool::class)
      else -> emptySet()
    }
  }

  // -- Regression: --device CLI flag overrides the daemon's currently-connected driver ---------
  //
  // Bug: when the daemon held any non-Android driver (e.g. a leftover playwright session),
  // `toolbox(target=X, platform=android)` silently returned web tools and labeled the header
  // `(Web Browser)` because `effectiveDriverType` defaulted to `currentDriverType` and only fell
  // back to the platform filter when the daemon was idle. The CLI flag is the user's explicit
  // intent — it must win over a stale daemon state.

  @Test
  fun `TARGET mode platform filter overrides daemon's connected web driver`() = runTest {
    val target = PerPlatformTarget()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )

    val result = toolSet.toolbox(target = "perplatform", platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    // Header must reflect the CLI flag, not the daemon's web driver.
    assertEquals("Android", obj["currentPlatform"]?.jsonPrimitive?.content)

    // Tool listing must contain the Android tool, not the web one. The class-backed default-target
    // tool list isn't asserted here — only the target's per-platform tool ids matter for the bug.
    val toolGroups = obj["toolGroups"]?.jsonArray ?: JsonArray(emptyList())
    val allTools = toolGroups.flatMap {
      it.jsonObject["tools"]?.jsonArray?.map { tn -> tn.jsonPrimitive.content } ?: emptyList()
    }
    assertContains(allTools, "launchApp", "Android tool must appear. Got: $allTools")
    assertTrue(
      "hideKeyboard" !in allTools,
      "This fixture's web-platform sentinel tool (hideKeyboard) must NOT appear under the " +
        "Android filter. Got: $allTools",
    )
  }

  @Test
  fun `TARGET mode platform filter overrides daemon's connected android driver`() = runTest {
    val target = PerPlatformTarget()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox(target = "perplatform", platform = "web")
    val obj = json.parseToJsonElement(result).jsonObject

    assertEquals("Web Browser", obj["currentPlatform"]?.jsonPrimitive?.content)

    val toolGroups = obj["toolGroups"]?.jsonArray ?: JsonArray(emptyList())
    val allTools = toolGroups.flatMap {
      it.jsonObject["tools"]?.jsonArray?.map { tn -> tn.jsonPrimitive.content } ?: emptyList()
    }
    assertContains(
      allTools,
      "hideKeyboard",
      "This fixture's web-platform sentinel tool (hideKeyboard) must appear under the web filter. " +
        "Got: $allTools",
    )
    assertTrue("launchApp" !in allTools, "Android-only tool must NOT appear. Got: $allTools")
  }

  @Test
  fun `TARGET mode platform filter overrides daemon's connected ios driver to android`() = runTest {
    val target = PerPlatformTarget()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentDriverType = TrailblazeDriverType.IOS_HOST,
    )

    val result = toolSet.toolbox(target = "perplatform", platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    assertEquals("Android", obj["currentPlatform"]?.jsonPrimitive?.content)

    val toolGroups = obj["toolGroups"]?.jsonArray ?: JsonArray(emptyList())
    val allTools = toolGroups.flatMap {
      it.jsonObject["tools"]?.jsonArray?.map { tn -> tn.jsonPrimitive.content } ?: emptyList()
    }
    assertContains(allTools, "launchApp")
    assertTrue("tap" !in allTools, "iOS-only tool must NOT appear. Got: $allTools")
  }

  @Test
  fun `TARGET mode preserves specific connected driver when platform agrees with CLI flag`() = runTest {
    // Counterpart to the override tests: when --device matches the daemon's platform, the
    // specific driver instance (instrumentation vs accessibility, axe vs host, ...) must be
    // preserved — the override path's "default for platform" fallback must not kick in.
    val target = PerPlatformTarget()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox(target = "perplatform", platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    assertEquals("Android", obj["currentPlatform"]?.jsonPrimitive?.content)
    // Direct proof of driver-instance preservation: the response's `currentPlatform` is part of
    // `ToolDiscoveryTargetResult` but the driver yaml key is what would diverge if the agree
    // branch silently flattened `ANDROID_ONDEVICE_INSTRUMENTATION` → `DEFAULT_ANDROID`. Assert
    // the specific instance survived. (The fixture's per-platform tool sets are the same across
    // Android driver types, so listing-content alone wouldn't catch a regression.)
    val toolGroups = obj["toolGroups"]?.jsonArray ?: JsonArray(emptyList())
    val allTools = toolGroups.flatMap {
      it.jsonObject["tools"]?.jsonArray?.map { tn -> tn.jsonPrimitive.content } ?: emptyList()
    }
    assertContains(allTools, "launchApp")
    // The TARGET-mode result type doesn't surface `currentDriverType`; the listing-content
    // assertion plus the platform header are the contractually-observable handles. INDEX mode
    // (which DOES surface `currentDriverType`) carries the driver-key assertion in its own
    // mirror test below.
  }

  @Test
  fun `INDEX mode preserves specific connected driver when platform agrees with CLI flag`() = runTest {
    // Mirror of the TARGET preservation test for INDEX. INDEX returns `currentDriverType` in
    // its result, so this is where we can assert the load-bearing claim end-to-end: the
    // daemon-connected `ANDROID_ONDEVICE_INSTRUMENTATION` driver survives a matching
    // `--device=android` rather than being flattened to `DEFAULT_ANDROID`.
    val defaultTarget = MixedNoneTarget(
      classTools = setOf(xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool::class),
      yamlNames = setOf(ToolName("pressBack")),
    )
    val toolSet = createToolSet(
      allTargets = setOf(defaultTarget),
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox(platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    assertEquals("Android", obj["currentPlatform"]?.jsonPrimitive?.content)
    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION.yamlKey,
      obj["currentDriverType"]?.jsonPrimitive?.content,
      "Specific connected driver must be preserved, not flattened to DEFAULT_ANDROID.",
    )
  }

  @Test
  fun `INDEX mode override with desktop platform resolves to compose driver`() = runTest {
    // The DESKTOP platform is `hidden = true` but the `--device=desktop` code path is
    // nonetheless live: `resolveDefaultDriverType(DESKTOP)` returns `COMPOSE`. If a user (or
    // an MCP client) reaches for it with an unrelated daemon driver active, the override
    // branch must still resolve correctly.
    val defaultTarget = MixedNoneTarget(
      classTools = setOf(xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool::class),
      yamlNames = setOf(ToolName("pressBack")),
    )
    val toolSet = createToolSet(
      allTargets = setOf(defaultTarget),
      currentDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )

    val result = toolSet.toolbox(platform = "desktop")
    val obj = json.parseToJsonElement(result).jsonObject

    assertEquals("Compose Desktop", obj["currentPlatform"]?.jsonPrimitive?.content)
    assertEquals(
      TrailblazeDriverType.COMPOSE.yamlKey,
      obj["currentDriverType"]?.jsonPrimitive?.content,
    )
  }

  @Test
  fun `toolbox rejects unknown --device platform with an actionable error message`() = runTest {
    // Typos like `--device=androd` previously degraded to "no platform filter" silently, which
    // re-triggered the very bug this PR fixes (daemon's stale driver wins). Distinguish "not
    // provided" from "provided but invalid" so the user gets a clear hint. The message goes in
    // the `error` field so the CLI's top-level error check renders it via `Console.error` for
    // every mode — including `--target=X --device=typo` (which would otherwise misroute through
    // `formatTargetResult` and emit a misleading "Target not found" line).
    val toolSet = createToolSet()

    val result = toolSet.toolbox(platform = "androd")
    val obj = json.parseToJsonElement(result).jsonObject

    val error = obj["error"]?.jsonPrimitive?.content
    assertNotNull(error, "Unknown platform must surface an `error` field. Got: $obj")
    assertContains(error, "Unknown platform 'androd'")
    assertContains(error, "android")
    assertContains(error, "ios")
    assertContains(error, "web")
    // Hidden DESKTOP must NOT leak into the user-facing message — that's why this site uses
    // `visibleEntries` and not `entries`.
    assertTrue(
      "desktop" !in error,
      "Hidden DESKTOP platform must not appear in the user-facing accepted list. Got: $error",
    )
  }

  @Test
  fun `toolbox rejects unknown --device even when --target is set (no target-mode misroute)`() = runTest {
    // Regression: when both `--target` and `--device=<typo>` are passed, the response must still
    // surface the platform validation error in the SAME mode-specific envelope the call would
    // have produced on success — so MCP clients doing structural typing get a `TargetResult`
    // back, not an `IndexResult`-as-generic-error. Asserts the response is shaped as a
    // [ToolDiscoveryTargetResult] by checking it can deserialize into that type.
    val toolSet = createToolSet()

    val result = toolSet.toolbox(target = "testapp", platform = "androd")
    val obj = json.parseToJsonElement(result).jsonObject

    val error = obj["error"]?.jsonPrimitive?.content
    assertNotNull(error, "Even with --target set, unknown platform must surface `error`. Got: $obj")
    assertContains(error, "Unknown platform 'androd'")
    // The response carries only the `error` field — `target`, `toolGroups`, `toolsByPlatform` and
    // friends remain null so the CLI formatter's top-level error check fires and routes to
    // `Console.error` rather than falling through to `formatTargetResult` / "Target not found".
    assertNull(obj["target"], "Validation failure must not produce target-mode fields.")
    // Round-trip through `ToolDiscoveryTargetResult` deserializer to lock in the response shape.
    val typed = Json { ignoreUnknownKeys = false }.decodeFromString(
      ToolDiscoveryTargetResult.serializer(), result,
    )
    assertEquals(error, typed.error)
  }

  @Test
  fun `toolbox rejects unknown --device with --search and returns SearchResult shape`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(search = "anything", platform = "androd")
    val obj = json.parseToJsonElement(result).jsonObject

    val error = obj["error"]?.jsonPrimitive?.content
    assertNotNull(error, "Unknown platform with --search must surface `error`. Got: $obj")
    assertContains(error, "Unknown platform 'androd'")
    assertNull(obj["matches"], "Validation failure must not produce search-mode fields.")
    val typed = Json { ignoreUnknownKeys = false }.decodeFromString(
      ToolDiscoverySearchResult.serializer(), result,
    )
    assertEquals(error, typed.error)
  }

  @Test
  fun `toolbox rejects unknown --device with --name and returns NameResult shape`() = runTest {
    val toolSet = createToolSet()

    val result = toolSet.toolbox(name = "tap", platform = "androd")
    val obj = json.parseToJsonElement(result).jsonObject

    val error = obj["error"]?.jsonPrimitive?.content
    assertNotNull(error, "Unknown platform with --name must surface `error`. Got: $obj")
    assertContains(error, "Unknown platform 'androd'")
    assertNull(obj["tool"], "Validation failure must not produce name-mode fields.")
    val typed = Json { ignoreUnknownKeys = false }.decodeFromString(
      ToolDiscoveryNameResult.serializer(), result,
    )
    assertEquals(error, typed.error)
  }

  @Test
  fun `NAME mode accepts a valid --device value but ignores it (documents intent)`() = runTest {
    // NAME lookup is "find this tool wherever it's defined." Per the dispatch in `toolbox()`,
    // `platform` is parsed and validated (typos still short-circuit — covered above) but the
    // valid value is then dropped before reaching `handleNameMode`. This test locks in that
    // intent so a future maintainer who "fixes" NAME to scope by platform notices the trade-off
    // and either updates the test or makes a deliberate semantic change. Without this anchor the
    // documented behavior is enforced by code comments alone.
    val toolSet = createToolSet()

    val withPlatform = toolSet.toolbox(name = "tap", platform = "android")
    val withoutPlatform = toolSet.toolbox(name = "tap")

    // Both responses are NAME-mode envelopes (same shape, same content for the same query).
    assertEquals(
      withoutPlatform, withPlatform,
      "NAME mode currently spans all platforms — passing --device must not change the result.",
    )
  }

  @Test
  fun `SEARCH mode preserves specific connected driver when platform agrees with CLI flag`() = runTest {
    // Mirror of the INDEX and TARGET preserve tests but for SEARCH. The fix routes SEARCH
    // through `resolveEffectiveDriverType` too; this asserts that the agree-branch returns the
    // exact connected driver (not flattened to the platform default) so a search emitted while
    // an instrumentation device is active doesn't silently re-run as accessibility.
    // SEARCH doesn't surface `currentDriverType` in its result, so we exercise this indirectly:
    // a target whose tools are scoped to ANDROID_ONDEVICE_INSTRUMENTATION specifically should
    // contribute results, proving the specific driver instance reached the filter logic.
    val driverSpecificTarget = object : TrailblazeHostAppTarget(
      id = "instronly",
      displayName = "Instrumentation Only",
    ) {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
        if (platform == TrailblazeDevicePlatform.ANDROID) listOf("com.instronly") else null

      override fun internalGetCustomToolsForDriver(
        driverType: TrailblazeDriverType,
      ): Set<KClass<out TrailblazeTool>> =
        if (driverType == TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION) {
          setOf(xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool::class)
        } else {
          emptySet()
        }
    }
    val toolSet = createToolSet(
      allTargets = setOf(driverSpecificTarget),
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox(search = "launchApp", platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    val matches = obj["matches"]?.jsonArray ?: JsonArray(emptyList())
    val toolNames =
      matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertContains(
      toolNames, "launchApp",
      "SEARCH must use the specific connected driver (ANDROID_ONDEVICE_INSTRUMENTATION), not " +
        "flatten to the platform default (ANDROID_ONDEVICE_ACCESSIBILITY) which would yield no tools. Got: $toolNames",
    )
  }

  @Test
  fun `SEARCH mode with no device honors --device platform filter`() = runTest {
    // The override-from-stale-daemon-driver case is covered above. This test exercises the OTHER
    // branch of `resolveEffectiveDriverType` that SEARCH now flows through: when the daemon is
    // idle (`currentDriverType == null`) and the user passes `--device=android`, the helper
    // resolves the platform's default driver and the search filters by it. Previously SEARCH
    // ignored `--device` entirely in this path too.
    val target = PerPlatformTarget()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentDriverType = null,
    )

    val result = toolSet.toolbox(search = "launchApp", platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    val matches = obj["matches"]?.jsonArray ?: JsonArray(emptyList())
    val toolNames =
      matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertContains(
      toolNames, "launchApp",
      "SEARCH with idle daemon must honor --device=android. Got: $toolNames",
    )
  }

  @Test
  fun `SEARCH mode platform filter overrides daemon's connected web driver`() = runTest {
    // SEARCH was the half of the toolbox surface not covered by the original fix. Same bug
    // class: with the daemon holding a web driver, `--search` would return web-filtered
    // results even when the user passed `--device=android`. Verify the override now applies.
    val target = PerPlatformTarget()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )

    val result = toolSet.toolbox(search = "launchApp", platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    val matches = obj["matches"]?.jsonArray ?: JsonArray(emptyList())
    val toolNames =
      matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertContains(
      toolNames, "launchApp",
      "SEARCH must honor --device=android even when daemon holds a web driver. Got: $toolNames",
    )
  }

  @Test
  fun `INDEX mode platform filter overrides daemon's connected web driver`() = runTest {
    // The same override applies to INDEX mode (`toolbox --device=android` with no --target, or
    // with --target=default which routes through INDEX). Before the fix, the header read
    // `(Android)` while the platform-toolset list contained web tools — a contradiction.
    val defaultTarget = MixedNoneTarget(
      classTools = setOf(xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool::class),
      yamlNames = setOf(ToolName("pressBack")),
    )
    val toolSet = createToolSet(
      allTargets = setOf(defaultTarget),
      currentDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )

    val result = toolSet.toolbox(platform = "android")
    val obj = json.parseToJsonElement(result).jsonObject

    assertEquals("Android", obj["currentPlatform"]?.jsonPrimitive?.content)
    val driverKey = obj["currentDriverType"]?.jsonPrimitive?.content
    assertEquals(
      TrailblazeDriverType.DEFAULT_ANDROID.yamlKey, driverKey,
      "INDEX mode must resolve to the Android default driver when --device=android overrides a web daemon. Got: $driverKey",
    )
  }

  @Test
  fun `TARGET mode includes inline scripted tools for matching driver`() = runTest {
    val toolSet = createToolSet(
      allTargets = setOf(inlineToolTarget),
      currentDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )

    val result = toolSet.toolbox(target = "inlineapp")
    val obj = json.parseToJsonElement(result).jsonObject

    val toolGroups = obj["toolGroups"]!!.jsonArray
    val scriptedGroup = toolGroups.first {
      it.jsonObject["name"]!!.jsonPrimitive.content == "inlineapp/web_core"
    }.jsonObject
    val toolNames = scriptedGroup["tools"]!!.jsonArray.map { it.jsonPrimitive.content }

    assertContains(toolNames, "web_inline_script_tool")
  }

  @Test
  fun `TARGET mode details classify inline TypeScript scripted tools`() = runTest {
    val toolSet = createToolSet(
      allTargets = setOf(inlineToolTarget),
      currentDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )

    val result = toolSet.toolbox(target = "inlineapp", detail = true)
    val typed = json.decodeFromString(ToolDiscoveryTargetResult.serializer(), result)
    val descriptor = typed.toolGroups
      ?.flatMap { it.toolDetails ?: emptyList() }
      ?.firstOrNull { it.name == "web_inline_script_tool" }
    assertNotNull(descriptor, "Inline scripted tool should be returned with detail=true")
    assertEquals(TrailblazeToolSourceType.TYPESCRIPT, descriptor.source?.type)
    assertEquals("./tools/web_inline.ts", descriptor.source?.scriptPath)
  }

  @Test
  fun `TARGET mode includes inline scripted tools for ios host driver`() = runTest {
    val toolSet = createToolSet(
      allTargets = setOf(iosInlineToolTarget),
      currentTarget = iosInlineToolTarget,
      currentDriverType = TrailblazeDriverType.IOS_HOST,
    )

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject
    val targetToolsets = obj["targetToolsets"]!!.jsonArray
    val inlineGroup = targetToolsets.first {
      it.jsonObject["name"]!!.jsonPrimitive.content == "iosinlineapp/ios_contacts"
    }.jsonObject
    val tools = inlineGroup["tools"]!!.jsonArray.map { it.jsonPrimitive.content }

    assertContains(tools, "contacts_ios_createContact")
  }

  @Test
  fun `NAME mode finds inline scripted tool`() = runTest {
    val toolSet = createToolSet(allTargets = setOf(inlineToolTarget))

    val result = toolSet.toolbox(name = "web_inline_script_tool")
    val obj = json.parseToJsonElement(result).jsonObject

    val tool = obj["tool"]?.jsonObject
    assertNotNull(tool, "Inline scripted tool should be discoverable by name")
    assertEquals("web_inline_script_tool", tool["name"]!!.jsonPrimitive.content)
    val foundInTargets = obj["foundInTargets"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertContains(foundInTargets, "inlineapp")
  }

  // -- YAML-aware union for target tools (search / name / target-flat paths) ---
  // `getCustomToolDescriptors` and `getCustomToolDescriptorsForPlatform` previously
  // iterated only `getCustomToolsForDriver` (class-only) and silently dropped YAML-defined
  // target tools from search, name lookup, and the target-mode no-device flat listing —
  // mirror of the bug the index-mode fix solved. Uses real registered YAML tool names
  // (`pressBack`, `eraseText`) since `buildDescriptorsForYamlDefined` skips unknown names.

  /** Test target that exposes real YAML-registered tool names (no class binding). */
  private class YamlToolTarget : TrailblazeHostAppTarget(
    id = "yamltarget",
    displayName = "YAML Target",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.ANDROID) listOf("com.yamltarget") else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getCustomYamlToolNamesForDriver(
      driverType: TrailblazeDriverType,
    ): Set<ToolName> = setOf(ToolName("pressBack"))
  }

  @Test
  fun `SEARCH finds YAML target tool via target tool descriptor path`() = runTest {
    val target = YamlToolTarget()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentTarget = target,
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox(search = "pressBack")
    val obj = json.parseToJsonElement(result).jsonObject

    val matches = obj["matches"]?.jsonArray ?: JsonArray(emptyList())
    val sources = matches.map { it.jsonObject["source"]!!.jsonPrimitive.content }
    // Search dedupes by name and prefers first match. We only need to know it WAS searchable
    // — match presence + non-empty result is enough. (The platform path may surface it first
    // since pressBack is also a navigation category tool; both paths must contribute.)
    assertTrue(
      matches.isNotEmpty(),
      "SEARCH must return pressBack via target tool descriptor path. Got: $matches",
    )
    val toolNames =
      matches.map { it.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    assertContains(toolNames, "pressBack", "Got sources: $sources")
  }

  @Test
  fun `NAME finds YAML target tool and reports it under the target`() = runTest {
    val target = YamlToolTarget()
    val toolSet = createToolSet(allTargets = setOf(target))

    val result = toolSet.toolbox(name = "pressBack")
    val obj = json.parseToJsonElement(result).jsonObject

    assertNull(obj["error"], "YAML target tool must be findable by exact name. Got: $obj")
    val tool = obj["tool"]?.jsonObject
    assertNotNull(tool, "Tool descriptor must be returned")
    assertEquals("pressBack", tool["name"]!!.jsonPrimitive.content)
    val foundInTargets = obj["foundInTargets"]?.jsonArray?.map { it.jsonPrimitive.content }
      .orEmpty()
    assertContains(
      foundInTargets, "yamltarget",
      "NAME must report the YAML tool under its owning target via the target descriptor path. " +
        "Got foundInTargets: $foundInTargets",
    )
  }

  @Test
  fun `TARGET mode no-device flat listing includes YAML target tools`() = runTest {
    val target = YamlToolTarget()
    val toolSet = createToolSet(allTargets = setOf(target), currentDriverType = null)

    val result = toolSet.toolbox(target = "yamltarget")
    val obj = json.parseToJsonElement(result).jsonObject

    val toolsByPlatform = obj["toolsByPlatform"]?.jsonArray
    assertNotNull(toolsByPlatform, "TARGET mode no-device must produce toolsByPlatform. Got: $obj")
    val androidEntry = toolsByPlatform.first {
      it.jsonObject["platform"]!!.jsonPrimitive.content == "Android"
    }.jsonObject
    val toolNames = androidEntry["tools"]!!.jsonArray
      .map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertContains(
      toolNames, "pressBack",
      "TARGET no-device flat listing must include YAML target tools. Got: $toolNames",
    )
  }

  // -- Target-mode listings honor target's own excluded_tools ------------------
  // INDEX/[none] listings always filtered exclusions; the per-target listings did not.
  // After the fix, `toolbox(target=X)` and `targetToolsets` in INDEX both apply X's
  // own exclusions so a YAML-declared `excluded_tools:` is consistently respected.

  /** Target with two real YAML tools, excluding one via the YAML-name exclusion bucket. */
  private class TargetWithYamlExclusion : TrailblazeHostAppTarget(
    id = "exclude_target",
    displayName = "Excluding Target",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.ANDROID) listOf("com.exclude") else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getCustomYamlToolNamesForDriver(
      driverType: TrailblazeDriverType,
    ): Set<ToolName> = setOf(ToolName("pressBack"), ToolName("eraseText"))

    override fun getExcludedYamlToolNamesForDriver(
      driverType: TrailblazeDriverType,
    ): Set<ToolName> = setOf(ToolName("pressBack"))
  }

  @Test
  fun `TARGET mode device-connected filters tools by target's own exclusions`() = runTest {
    val target = TargetWithYamlExclusion()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentTarget = target,
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox(target = "exclude_target")
    val obj = json.parseToJsonElement(result).jsonObject

    val toolGroups = obj["toolGroups"]?.jsonArray
    assertNotNull(toolGroups, "TARGET mode device-connected must produce toolGroups. Got: $obj")
    val tools = toolGroups.flatMap {
      it.jsonObject["tools"]!!.jsonArray.map { name -> name.jsonPrimitive.content }
    }
    assertContains(tools, "eraseText", "Non-excluded YAML tool must remain. Got: $tools")
    assertTrue(
      "pressBack" !in tools,
      "Excluded YAML tool must NOT appear in target listing. Got: $tools",
    )
  }

  @Test
  fun `TARGET mode no-device platform listing filters by target's exclusions`() = runTest {
    val target = TargetWithYamlExclusion()
    val toolSet = createToolSet(allTargets = setOf(target), currentDriverType = null)

    val result = toolSet.toolbox(target = "exclude_target")
    val obj = json.parseToJsonElement(result).jsonObject

    val toolsByPlatform = obj["toolsByPlatform"]?.jsonArray
    assertNotNull(toolsByPlatform, "TARGET mode no-device must produce toolsByPlatform. Got: $obj")
    val androidEntry = toolsByPlatform.first {
      it.jsonObject["platform"]!!.jsonPrimitive.content == "Android"
    }.jsonObject
    val toolNames = androidEntry["tools"]!!.jsonArray
      .map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertContains(toolNames, "eraseText", "Non-excluded YAML tool must remain. Got: $toolNames")
    assertTrue(
      "pressBack" !in toolNames,
      "Excluded YAML tool must NOT appear in no-device target listing. Got: $toolNames",
    )
  }

  @Test
  fun `INDEX mode buildTargetToolsets filters by per-target exclusions when device connected`() = runTest {
    // Index mode shows targetToolsets for the connected target. Each target's tools must be
    // filtered by THAT target's own exclusions, not just the platform-toolset exclusions.
    val target = TargetWithYamlExclusion()
    val toolSet = createToolSet(
      allTargets = setOf(target),
      currentTarget = target,
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    val targetToolsets = obj["targetToolsets"]?.jsonArray ?: JsonArray(emptyList())
    val tools = targetToolsets.flatMap {
      it.jsonObject["tools"]?.jsonArray.orEmpty().map { name -> name.jsonPrimitive.content }
    }
    assertContains(
      tools, "eraseText",
      "Non-excluded YAML tool must remain in targetToolsets. Got: $tools",
    )
    assertTrue(
      "pressBack" !in tools,
      "Excluded YAML tool must NOT appear in targetToolsets. Got: $tools",
    )
  }

  // -- Role grouping (trailheadTools / shortcutTools) -------------------------

  /**
   * Stub source returning a trailmap-scoped map of `<key> -> <yaml-content>` for the
   * recursive trailmap walk that backs every `.tool.yaml` / `.shortcut.yaml` /
   * `.trailhead.yaml` discovery. Input keys are the filename minus the `.yaml` suffix —
   * `tap.trailhead` becomes a `<stub-trailmap>/trailheads/tap.trailhead.yaml` entry served
   * to `discoverAndLoadRecursive(TRAILMAPS_DIR, ".trailhead.yaml")`. The YAML content's
   * `id:` is the authoritative tool identifier used by the intersection guard.
   *
   * The stub also serves a `trailmap.yaml` with a `target:` block so the loader's
   * library-trailmap-trailhead guard doesn't reject the trailheads at discovery time.
   */
  private fun stubRoleYamlSource(entries: Map<String, String>): ConfigResourceSource {
    val trailmapId = "stubRoleTrailmap"
    val trailmapManifest = """
      id: $trailmapId
      target:
        display_name: Stub Role Trailmap
    """.trimIndent()
    fun dirForKey(key: String): String? = when {
      key.endsWith(".tool") -> "tools"
      key.endsWith(".shortcut") -> "shortcuts"
      key.endsWith(".trailhead") -> "trailheads"
      else -> null
    }
    return object : ConfigResourceSource {
      override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> = emptyMap()

      override fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> {
        if (directoryPath != TrailblazeConfigPaths.TRAILMAPS_DIR) return emptyMap()
        return when (suffix) {
          "/trailmap.yaml" -> mapOf("$trailmapId/trailmap.yaml" to trailmapManifest)
          ".tool.yaml", ".shortcut.yaml", ".trailhead.yaml" -> {
            val targetKind = suffix.removePrefix(".").removeSuffix(".yaml") // tool / shortcut / trailhead
            entries.filterKeys { it.endsWith(".$targetKind") }
              .mapKeys { (key, _) -> "$trailmapId/${dirForKey(key)}/$key.yaml" }
          }
          else -> emptyMap()
        }
      }
    }
  }

  @Test
  fun `INDEX mode trailheadTools and shortcutTools intersect with in-scope toolset names`() = runTest {
    // The intersection guard is the load-bearing invariant called out in computeRoleNames' kdoc:
    // role lists must never surface a tool that won't resolve when the user drills in via
    // `toolbox --name <id>`. Without this test, a refactor that drops the `it.id in inScopeNames`
    // filter would silently break the CLI's role-grouped view (names listed that lead nowhere).
    //
    // Two stub YAML configs share the same shape; their `id:` fields differ:
    //   - `tap` is a real platform tool (in scope of every toolbox response) → must appear.
    //   - `ghostTool_NotInAnyToolset` doesn't exist anywhere → must be filtered out.
    val stubSource = stubRoleYamlSource(
      mapOf(
        "tap.trailhead" to
          """
          id: tap
          description: Tap a thing (role metadata applied for test only).
          trailhead:
            to: testapp/android/home
          tools:
            - tap: { selector: "ok" }
          """.trimIndent(),
        "ghostTool_NotInAnyToolset.trailhead" to
          """
          id: ghostTool_NotInAnyToolset
          description: A trailhead pointing at a tool no toolset surfaces.
          trailhead:
            to: testapp/android/home
          tools:
            - tap: { selector: "ok" }
          """.trimIndent(),
        "tap.shortcut" to
          """
          id: tapShortcut_NotInAnyToolset
          description: A shortcut whose id is not in any toolset.
          shortcut:
            from: testapp/android/a
            to: testapp/android/b
          tools:
            - tap: { selector: "ok" }
          """.trimIndent(),
      ),
    )

    val toolSet = ToolDiscoveryToolSet(
      sessionContext = null,
      allTargetAppsProvider = { setOf(testTarget) },
      currentTargetProvider = { null },
      currentDriverTypeProvider = { null },
      resourceSourceProvider = { stubSource },
    )

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    // Both fields default to emptyList() with encodeDefaults = false, so the JSON omits them when
    // they're empty. Use safe-access defaults so the assertions read naturally either way.
    val trailheadTools = obj["trailheadTools"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    val shortcutTools = obj["shortcutTools"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    assertContains(
      trailheadTools, "tap",
      "in-scope role tool must be surfaced — `tap` is a real platform tool and the stub gave it trailhead metadata",
    )
    assertFalse(
      "ghostTool_NotInAnyToolset" in trailheadTools,
      "out-of-scope trailhead must be filtered — intersection guard prevents the CLI from naming tools that won't resolve via `--name <id>`. Got: $trailheadTools",
    )
    assertFalse(
      "tapShortcut_NotInAnyToolset" in shortcutTools,
      "out-of-scope shortcut must be filtered for the same reason. Got: $shortcutTools",
    )
  }

  @Test
  fun `INDEX mode trailheadTools are returned in sorted order`() = runTest {
    // Sort order matters for stable rendering — the CLI emits role lists verbatim in the order
    // received, so a regression to discovery-order would produce flapping output across builds
    // (resource-scan order is FS-dependent). Pin it explicitly.
    val stubSource = stubRoleYamlSource(
      mapOf(
        // Note YAML ids deliberately out of alphabetical order; only tap and pressBack are in
        // platform toolsets so only those two should appear, and they must be sorted.
        "pressBack.trailhead" to
          """
          id: pressBack
          description: Press back (stub trailhead).
          trailhead:
            to: testapp/android/back
          tools:
            - tap: { selector: "back" }
          """.trimIndent(),
        "tap.trailhead" to
          """
          id: tap
          description: Tap (stub trailhead).
          trailhead:
            to: testapp/android/home
          tools:
            - tap: { selector: "ok" }
          """.trimIndent(),
      ),
    )

    val toolSet = ToolDiscoveryToolSet(
      sessionContext = null,
      allTargetAppsProvider = { setOf(testTarget) },
      currentTargetProvider = { null },
      currentDriverTypeProvider = { null },
      resourceSourceProvider = { stubSource },
    )

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject
    val trailheadTools = obj["trailheadTools"]!!.jsonArray.map { it.jsonPrimitive.content }

    assertEquals(
      trailheadTools.sorted(),
      trailheadTools,
      "trailheadTools must be sorted — pinning the `.sorted()` contract from computeRoleNames",
    )
  }

  @Test
  fun `TARGET mode populates trailheadTools so role filter works for non-default targets`() = runTest {
    // Bug fix regression test: before the fix, handleTargetMode returned a
    // ToolDiscoveryTargetResult without the role fields, so the CLI's `toolbox trailheads
    // --target <non-default>` flow would silently see an empty list and tell the user "no
    // trailheads available" even when there were some. This test pins that target-mode responses
    // now carry the role lists.
    //
    // The intersection guard in target mode scopes against the target's own toolGroups (not all
    // platform tools), so the trailhead YAML must reference an id that the target actually owns.
    // The shared `inlineToolTarget` fixture exposes `web_inline_script_tool` as a Web inline
    // script tool — picking that one keeps the test self-contained.
    val stubSource = stubRoleYamlSource(
      mapOf(
        "web_inline_script_tool.trailhead" to
          """
          id: web_inline_script_tool
          description: Inline-script tool with trailhead role for the test.
          trailhead:
            to: inlineapp/web/home
          tools:
            - tap: { selector: "ok" }
          """.trimIndent(),
      ),
    )

    val toolSet = ToolDiscoveryToolSet(
      sessionContext = null,
      allTargetAppsProvider = { setOf(inlineToolTarget) },
      currentTargetProvider = { inlineToolTarget },
      currentDriverTypeProvider = { TrailblazeDriverType.PLAYWRIGHT_NATIVE },
      resourceSourceProvider = { stubSource },
    )

    // Target-mode dispatch (target != null && target != default → handleTargetMode)
    val result = toolSet.toolbox(target = "inlineapp")
    val obj = json.parseToJsonElement(result).jsonObject

    // Response is target-mode shape (not index-mode) — confirms we're exercising the right path.
    assertNotNull(obj["target"], "target-mode response should carry a `target` field")
    val trailheadTools = obj["trailheadTools"]?.jsonArray?.map { it.jsonPrimitive.content }
      ?: emptyList()
    assertContains(
      trailheadTools, "web_inline_script_tool",
      "TARGET mode must now populate trailheadTools so `toolbox trailheads --target <non-default>` returns a non-empty list when role tools exist. Got: $trailheadTools",
    )
  }

  // -- 12. systemPrompt field -------------------------------------------------
  //
  // The `toolbox` response carries the resolved target's curated LLM-facing prose so the
  // CLI can surface it under a `## System prompt` section above the tool catalog. The
  // contract has three branches and one defense-in-depth case worth pinning:
  //
  //   - INDEX mode with a non-default current target → `systemPrompt` populated.
  //   - INDEX mode with `--target default` (suppressTargetTools=true) → `systemPrompt` null,
  //     so a platform-only listing is never paired with app-specific guidance.
  //   - TARGET mode with a non-default target → `systemPrompt` populated.
  //   - TARGET mode dispatched directly with `target="default"` (which today the CLI never
  //     emits because the dispatcher routes that through INDEX, but a future MCP caller
  //     could bypass) → `systemPrompt` null, per the same suppression rule.

  /** Helper: a target with a fixed system prompt template. */
  private class PromptedTestTarget(
    id: String,
    displayName: String,
    private val prompt: String?,
    private val androidAppIds: List<String> = listOf("com.test.app"),
  ) : TrailblazeHostAppTarget(id, displayName) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.ANDROID) androidAppIds else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getSystemPromptTemplate(): String? = prompt
  }

  @Test
  fun `INDEX mode populates systemPrompt from current target`() = runTest {
    val promptedTarget =
      PromptedTestTarget(id = "prompted", displayName = "Prompted", prompt = "Test prompt.")
    val toolSet = createToolSet(
      allTargets = setOf(promptedTarget),
      currentTarget = promptedTarget,
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox()
    val obj = json.parseToJsonElement(result).jsonObject

    assertEquals(
      "Test prompt.",
      obj["systemPrompt"]?.jsonPrimitive?.contentOrNull,
      "Index-mode response must inline the current target's system prompt for the CLI to surface.",
    )
  }

  @Test
  fun `INDEX mode with target=default suppresses systemPrompt even when current target has one`() = runTest {
    // Reproduces the Codex review concern on PR #3498: a CLI agent (or RecordingToolDiscovery)
    // that scopes its listing to platform-only tools via `--target default` must not see the
    // bound target's app-specific guidance. The bug: prompt scope wider than catalog scope.
    val promptedTarget =
      PromptedTestTarget(id = "prompted", displayName = "Prompted", prompt = "Would mislead.")
    val toolSet = createToolSet(
      allTargets = setOf(promptedTarget),
      currentTarget = promptedTarget,
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox(target = "default")
    val obj = json.parseToJsonElement(result).jsonObject

    assertNull(
      obj["systemPrompt"],
      "`target=default` requests a platform-only listing; surfacing the current target's " +
        "prompt would put app-specific guidance above a catalog from which app-specific tools " +
        "were intentionally excluded.",
    )
  }

  @Test
  fun `TARGET mode populates systemPrompt for non-default target`() = runTest {
    val promptedTarget =
      PromptedTestTarget(id = "prompted", displayName = "Prompted", prompt = "Target-mode prompt.")
    val toolSet = createToolSet(
      allTargets = setOf(promptedTarget),
      currentTarget = promptedTarget,
      currentDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    val result = toolSet.toolbox(target = "prompted")
    val obj = json.parseToJsonElement(result).jsonObject

    assertEquals(
      "Target-mode prompt.",
      obj["systemPrompt"]?.jsonPrimitive?.contentOrNull,
      "Target-mode response must also inline the prompt — both modes are target-wide.",
    )
  }

  @Test
  fun `systemPromptForTarget returns null for the default sentinel (defense-in-depth)`() {
    // The dispatcher's `isDefaultTarget` check in `toolbox()` routes `target="default"`
    // through index mode, so today this branch is unreachable from the public surface.
    // Pin it as a unit test against the helper directly — a future refactor of the
    // dispatcher (or a direct MCP caller bypassing it) must not produce a result whose
    // `systemPrompt` field carries app-specific guidance for the platform-only sentinel.
    val defaultLookalike = PromptedTestTarget(
      id = "default",
      displayName = "Default Lookalike",
      prompt = "WOULD MISLEAD — must be dropped by the helper.",
    )
    val toolSet = createToolSet()

    assertNull(
      toolSet.systemPromptForTarget(defaultLookalike),
      "The helper MUST drop the prompt when the target's id matches DefaultTrailblazeHostAppTarget.id — " +
        "this is the only thing keeping a direct `handleTargetMode(target=default)` callsite from " +
        "leaking a stale prompt.",
    )
  }

  @Test
  fun `systemPromptForTarget passes through for any non-default target`() {
    val promptedTarget =
      PromptedTestTarget(id = "nondefault", displayName = "Non-default", prompt = "Non-default prompt.")
    val toolSet = createToolSet()

    assertEquals(
      "Non-default prompt.",
      toolSet.systemPromptForTarget(promptedTarget),
      "The helper only suppresses the default sentinel — every other target's prompt flows through unchanged.",
    )
  }
}
