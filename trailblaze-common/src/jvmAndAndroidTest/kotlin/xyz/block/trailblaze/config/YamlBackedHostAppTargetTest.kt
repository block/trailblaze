package xyz.block.trailblaze.config

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.allToolNames
import xyz.block.trailblaze.toolcalls.getAgentToolboxForDriver
import xyz.block.trailblaze.toolcalls.getExcludedToolSurfaceForDriver
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlBackedHostAppTargetTest {

  private val resolver = ToolNameResolver.fromBuiltInAndCustomTools()

  @Test
  fun `minimal app target with no tools or app ids`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: default
      display_name: Default
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertEquals("default", target.id)
    assertEquals("Default", target.displayName)
    assertNull(target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID))
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).isEmpty())
    assertFalse(target.hasCustomIosDriver)
  }

  @Test
  fun `system_prompt round-trips from YAML to getSystemPromptTemplate`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      system_prompt: |
        You are testing Test App.
        Be direct.
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertEquals("You are testing Test App.\nBe direct.", target.getSystemPromptTemplate())
  }

  @Test
  fun `system_prompt absent returns null`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertNull(target.getSystemPromptTemplate())
  }

  @Test
  fun `app ids resolve by platform`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          app_ids:
            - com.example.app.debug
        ios:
          app_ids:
            - com.example.app
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertEquals(listOf("com.example.app.debug"), target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID))
    assertEquals(listOf("com.example.app"), target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.IOS))
    assertNull(target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.WEB))
  }

  @Test
  fun `toolset scoped by platform section`() {
    val swipeToolSet = ResolvedToolSet(
      config = ToolSetYamlConfig(id = "test_set", tools = listOf("swipe")),
      resolvedToolClasses = setOf(SwipeTrailblazeTool::class),
      compatibleDriverTypes = emptySet(),
    )

    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          tool_sets: [test_set]
      """.trimIndent(),
      toolNameResolver = resolver,
      availableToolSets = mapOf("test_set" to swipeToolSet),
    )

    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).contains(SwipeTrailblazeTool::class))
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.IOS_HOST).isEmpty())
  }

  @Test
  fun `excluded tools resolve per platform`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        ios:
          excluded_tools: [swipe]
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    assertTrue(target.getExcludedToolsForDriver(TrailblazeDriverType.IOS_HOST).contains(SwipeTrailblazeTool::class))
    assertTrue(target.getExcludedToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).isEmpty())
  }

  @Test
  fun `excluded YAML-defined tools route to the YAML exclusion bucket`() {
    // Mirrors the inclusion side: a target YAML can list a YAML-defined tool name
    // (e.g. `pressBack`) under `excluded_tools` and the resolver classifies it into
    // the YAML-name bucket instead of throwing it away as an unknown class.
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          excluded_tools: [pressBack, swipe]
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    val androidDriver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    assertTrue(
      target.getExcludedToolsForDriver(androidDriver).contains(SwipeTrailblazeTool::class),
      "Class-backed swipe should still land in the class exclusion bucket",
    )
    assertTrue(
      target.getExcludedYamlToolNamesForDriver(androidDriver)
        .any { it.toolName == "pressBack" },
      "YAML-defined pressBack should land in the YAML exclusion bucket. Got: ${target.getExcludedYamlToolNamesForDriver(androidDriver)}",
    )
    assertTrue(target.getExcludedYamlToolNamesForDriver(TrailblazeDriverType.IOS_HOST).isEmpty())
  }

  @Test
  fun `excluded scripted tools route to the scripted exclusion bucket`() {
    // The scripted-partition parallel of the class / YAML exclusion tests above: a target YAML can
    // list a toolset-delivered scripted (`.ts`) tool name (e.g. `openUrl`, delivered by
    // `core_interaction`) under `excluded_tools`, and the resolver classifies it into the scripted
    // exclusion bucket instead of dropping it as "unknown tool". Before this, such an entry hit the
    // `else` branch in `resolvedExcludedToolsByDriver` and was silently lost, so the exclusion never
    // reached the LLM-surface compositors and `openUrl` stayed advertised.
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          excluded_tools: [openUrl]
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    val androidDriver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    assertTrue(
      target.getExcludedScriptedToolNamesForDriver(androidDriver).any { it.toolName == "openUrl" },
      "Scripted openUrl should land in the scripted exclusion bucket. " +
        "Got: ${target.getExcludedScriptedToolNamesForDriver(androidDriver)}",
    )
    // It must NOT leak into the class-backed or YAML-defined exclusion buckets.
    assertTrue(target.getExcludedToolsForDriver(androidDriver).isEmpty())
    assertTrue(target.getExcludedYamlToolNamesForDriver(androidDriver).isEmpty())
    // Scoped per platform like the other exclusion kinds (declared only under `android`).
    assertTrue(target.getExcludedScriptedToolNamesForDriver(TrailblazeDriverType.IOS_HOST).isEmpty())
  }

  @Test
  fun `getExcludedToolSurfaceForDriver unions class, YAML, and scripted exclusions`() {
    // The central exclusion entry point — the exclusion-side mirror of
    // TrailblazeToolSurface.allToolNames. A target that opts out of one tool of each backing should
    // see each land in the right partition of the returned surface, with allToolNames unioning them.
    // Every compositor (resolver, daemon, discovery, on-device) reads THIS one accessor, so this
    // partitioning is the single contract they all depend on — pinning it here guards against a
    // future backing being added to one partition but dropped from the union.
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          excluded_tools: [tap, eraseText, openUrl]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    val android = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val surface = target.getExcludedToolSurfaceForDriver(android)

    // tap is class-backed, eraseText is YAML-defined, openUrl is scripted — each in its own bucket.
    assertTrue(TapTrailblazeTool::class in surface.toolClasses, "tap -> class bucket")
    assertTrue(surface.yamlToolNames.any { it.toolName == "eraseText" }, "eraseText -> YAML bucket")
    assertTrue(surface.scriptedToolNames.any { it.toolName == "openUrl" }, "openUrl -> scripted bucket")
    // allToolNames is the union across all three partitions.
    val allNames = surface.allToolNames.map { it.toolName }.toSet()
    assertTrue(
      setOf("tap", "eraseText", "openUrl").all { it in allNames },
      "allToolNames must union every backing. Got: $allNames",
    )
  }

  @Test
  fun `scripted tools listed in platforms tools route to the scripted inclusion bucket`() {
    // The inclusion-side mirror of `excluded scripted tools route to the scripted exclusion bucket`
    // above. A target YAML can list a toolset-delivered scripted (`.ts`) tool name (e.g. `openUrl`,
    // delivered by `core_interaction`) directly under `platforms.<p>.tools:`, and the resolver
    // classifies it into the scripted INCLUSION bucket instead of dropping it as "unknown tool".
    // Before this, such an entry hit the `else` branch in `resolvedCustomToolsByDriver` and was
    // silently lost, so the scripted tool never surfaced to the LLM — the inclusion-side parallel
    // of the exclusion bug closed in #3862.
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          tools: [openUrl]
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    val androidDriver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    assertTrue(
      target.getCustomScriptedToolNamesForDriver(androidDriver).any { it.toolName == "openUrl" },
      "Scripted openUrl should land in the scripted inclusion bucket. " +
        "Got: ${target.getCustomScriptedToolNamesForDriver(androidDriver)}",
    )
    // It must NOT leak into the class-backed or YAML-defined inclusion buckets.
    assertTrue(target.getCustomToolsForDriver(androidDriver).isEmpty())
    assertTrue(target.getCustomYamlToolNamesForDriver(androidDriver).isEmpty())
    // Scoped per platform like the other inclusion kinds (declared only under `android`).
    assertTrue(target.getCustomScriptedToolNamesForDriver(TrailblazeDriverType.IOS_HOST).isEmpty())
  }

  @Test
  fun `getAgentToolboxForDriver surfaces a custom scripted tool listed in platforms tools`() {
    // The point of the whole effort: a scripted tool listed in `platforms.<p>.tools:` must reach
    // the LLM-visible surface via `getAgentToolboxForDriver`, exactly like a class-backed or
    // YAML-defined custom tool. Use playwright-native, where `openUrl`'s toolsets (`core_interaction`
    // / `navigation`) are mobile-only and deliver nothing — so `openUrl` appears in the toolbox ONLY
    // because the custom scripted inclusion bucket is unioned in. That pins the scripted partition
    // of the union the same way the existing parity tests pin the class / YAML partitions; without
    // the union (the inclusion-side mirror of #3862) this assertion fails.
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        web:
          drivers: [playwright-native]
          tools: [openUrl]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    val web = TrailblazeDriverType.PLAYWRIGHT_NATIVE
    assertTrue(
      target.getCustomScriptedToolNamesForDriver(web).any { it.toolName == "openUrl" },
      "custom scripted bucket should contain openUrl on playwright-native. " +
        "Got: ${target.getCustomScriptedToolNamesForDriver(web)}",
    )
    val toolbox = target.getAgentToolboxForDriver(driverType = web)
    assertTrue(
      toolbox.scriptedToolNames.any { it.toolName == "openUrl" },
      "getAgentToolboxForDriver must union the custom scripted inclusion bucket. " +
        "scriptedToolNames=${toolbox.scriptedToolNames}",
    )
  }

  @Test
  fun `getCustomToolGroupsForDriver includes scripted names from platforms tools`() {
    // The discovery-grouping leg of three-way parity: a scripted name in `platforms.<p>.tools:`
    // must land in the default ToolGroup's scriptedToolNames bucket so grouped discovery
    // (DeviceManagerToolSet / ToolDiscoveryToolSet via toMergedDescriptors) surfaces it alongside
    // class- and YAML-backed tools. Before the scripted bucket, a scripted-only custom target
    // returned emptyList() from getCustomToolGroupsForDriver and vanished from discovery.
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          tools: [openUrl]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    val android = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val groups = target.getCustomToolGroupsForDriver(android)
    assertTrue(groups.isNotEmpty(), "a scripted-only target must still produce a tool group")
    assertTrue(
      groups.any { group -> group.scriptedToolNames.any { it.toolName == "openUrl" } },
      "scripted openUrl must land in a ToolGroup.scriptedToolNames bucket. Got: $groups",
    )
  }

  @Test
  fun `min build version resolves by platform`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        ios:
          min_build_version: "6515"
        android:
          min_build_version: "67500000"
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    assertEquals("6515", target.getMinBuildVersion(TrailblazeDevicePlatform.IOS))
    assertEquals("67500000", target.getMinBuildVersion(TrailblazeDevicePlatform.ANDROID))
    assertNull(target.getMinBuildVersion(TrailblazeDevicePlatform.WEB))
  }

  @Test
  fun `has_custom_ios_driver flag`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      has_custom_ios_driver: true
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertTrue(target.hasCustomIosDriver)
  }

  @Test
  fun `individual tools list resolves for platform`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          tools: [tap]
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).contains(TapTrailblazeTool::class))
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.IOS_HOST).isEmpty())
  }

  @Test
  fun `drivers narrowing within platform section`() {
    val toolSet = ResolvedToolSet(
      config = ToolSetYamlConfig(id = "hw", tools = listOf("tap")),
      resolvedToolClasses = setOf(TapTrailblazeTool::class),
      compatibleDriverTypes = emptySet(),
    )

    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        web:
          drivers: [playwright-native]
          tool_sets: [hw]
      """.trimIndent(),
      toolNameResolver = resolver,
      availableToolSets = mapOf("hw" to toolSet),
    )

    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.PLAYWRIGHT_NATIVE).contains(TapTrailblazeTool::class))
    // playwright-electron not included because drivers narrowed to playwright-native only
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.PLAYWRIGHT_ELECTRON).isEmpty())
  }

  // --- allowsAppNotInstalled derivation ---
  //
  // Pins the per-target inference that keeps `default.yaml` (no app_ids declared anywhere)
  // selectable as a generic stand-in while real product targets stay strict. Without this
  // coverage, a future refactor of the `.all { isNullOrEmpty }` predicate could silently
  // flip a product target to lenient-mode without anything failing. PR #3118 review feedback
  // from Copilot called this gap out explicitly.

  @Test
  fun `allowsAppNotInstalled is true when no platforms section is declared`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: default
      display_name: Default
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertTrue(target.allowsAppNotInstalled)
  }

  @Test
  fun `allowsAppNotInstalled is true when every declared platform has no app_ids`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: default
      display_name: Default
      platforms:
        android:
          tool_sets: [core_interaction]
        ios:
          tool_sets: [core_interaction]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertTrue(target.allowsAppNotInstalled)
  }

  @Test
  fun `allowsAppNotInstalled is false when at least one platform declares an app id`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: half-strict
      display_name: Half Strict
      platforms:
        android:
          app_ids:
            - com.example.app
        ios:
          tool_sets: [core_interaction]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertFalse(target.allowsAppNotInstalled)
  }

  // --- getPossibleAppIdsForPlatform: null vs emptyList contract ---
  //
  // The base-class kdoc draws a load-bearing distinction: null = platform not supported,
  // emptyList = supported but no specific id declared. The dialog's per-row enable gate
  // (`acceptsDeviceForPlatform`) relies on this distinction to stop a web-only target from
  // enabling Android/iOS rows. PR #3118 review feedback from Codex flagged the original
  // implementation conflating both cases as null.

  @Test
  fun `getPossibleAppIdsForPlatform returns emptyList for declared platform with no app_ids`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: default
      display_name: Default
      platforms:
        ios:
          tool_sets: [core_interaction]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertEquals(emptyList(), target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.IOS))
  }

  @Test
  fun `getPossibleAppIdsForPlatform returns null for platform not declared at all`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: web-only
      display_name: Web Only
      platforms:
        web:
          drivers: [playwright-native]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertNull(target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID))
    assertNull(target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.IOS))
  }

  // --- acceptsDeviceForPlatform (cross-check) ---
  //
  // Sanity-checks that allowsAppNotInstalled does NOT bypass the platform-support gate.
  // This is the codex P1 finding on PR #3118: a web-only target should not enable
  // Android/iOS device rows just because it has no app_ids declared.

  @Test
  fun `acceptsDeviceForPlatform refuses unsupported platforms even when allowsAppNotInstalled`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: web-only
      display_name: Web Only
      platforms:
        web:
          drivers: [playwright-native]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    // The web-only target has no app_ids declared so allowsAppNotInstalled derives to true,
    // but Android isn't a supported platform — the row must stay disabled.
    assertTrue(target.allowsAppNotInstalled)
    assertFalse(target.acceptsDeviceForPlatform(TrailblazeDevicePlatform.ANDROID, installedAppId = null))
    assertFalse(target.acceptsDeviceForPlatform(TrailblazeDevicePlatform.IOS, installedAppId = null))
  }

  @Test
  fun `acceptsDeviceForPlatform allows declared platform with no installed app for stand-in target`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: default
      display_name: Default
      platforms:
        android:
          tool_sets: [core_interaction]
        ios:
          tool_sets: [core_interaction]
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertTrue(target.acceptsDeviceForPlatform(TrailblazeDevicePlatform.ANDROID, installedAppId = null))
    assertTrue(target.acceptsDeviceForPlatform(TrailblazeDevicePlatform.IOS, installedAppId = null))
  }

  @Test
  fun `acceptsDeviceForPlatform requires an installed app for strict product target`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: strict
      display_name: Strict
      platforms:
        android:
          app_ids:
            - com.example.app
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertFalse(target.allowsAppNotInstalled)
    assertFalse(target.acceptsDeviceForPlatform(TrailblazeDevicePlatform.ANDROID, installedAppId = null))
    assertTrue(
      target.acceptsDeviceForPlatform(TrailblazeDevicePlatform.ANDROID, installedAppId = "com.example.app"),
    )
  }

  @Test
  fun `root level inline script tools are exposed separately from platform tools`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      tools:
        - script: ./tools/greet_user.js
          name: greetUser
          description: Greets the current user.
          _meta:
            trailblaze/supportedPlatforms: [WEB]
      platforms:
        android:
          tools: [tap]
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    assertEquals(
      listOf(
        InlineScriptToolConfig(
          script = "./tools/greet_user.js",
          name = "greetUser",
          description = "Greets the current user.",
          meta = buildJsonObject {
            put(
              "trailblaze/supportedPlatforms",
              Json.parseToJsonElement("""["WEB"]"""),
            )
          },
        ),
      ),
      target.getInlineScriptTools(),
    )
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).contains(TapTrailblazeTool::class))
  }
}
