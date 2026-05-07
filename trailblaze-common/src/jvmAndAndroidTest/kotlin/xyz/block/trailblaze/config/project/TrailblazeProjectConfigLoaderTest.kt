package xyz.block.trailblaze.config.project

import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.testing.ClasspathFixture
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.llm.config.BuiltInProviderConfig
import xyz.block.trailblaze.llm.config.LlmModelConfigEntry

/**
 * Unit tests for [TrailblazeProjectConfigLoader]. These exercise the loader's behaviour
 * directly with synthetic workspace fixtures so failure modes are isolated; runtime
 * integration is covered by `AppTargetDiscoveryTest` and the waypoint CLI smoke tests.
 *
 * Tests pass `includeClasspathPacks = false` (or rely on the default `loadResolved`
 * non-classpath path) where they need to assert workspace-only behaviour without the
 * framework-bundled packs in `trailblaze-models` polluting the result set.
 */
class TrailblazeProjectConfigLoaderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `load returns null when no trailblaze_yaml exists`() {
    val missing = File(tempFolder.root, "trailblaze.yaml")
    assertNull(TrailblazeProjectConfigLoader.load(missing))
  }

  @Test
  fun `empty file decodes to an empty config`() {
    val file = tempFolder.writeConfig("")
    val loaded = TrailblazeProjectConfigLoader.load(file)
    assertNotNull(loaded)
    val raw = loaded.raw
    assertNull(raw.defaults)
    assertTrue(raw.targets.isEmpty())
    assertTrue(raw.toolsets.isEmpty())
    assertTrue(raw.tools.isEmpty())
    assertTrue(raw.providers.isEmpty())
    assertNull(raw.llm)
  }

  @Test
  fun `decodes defaults and llm sections`() {
    val file = tempFolder.writeConfig(
      """
      defaults:
        target: sampleapp
        llm: openai/gpt-4.1
      llm:
        providers:
          openai:
            models:
              - id: gpt-4.1
        defaults:
          model: openai/gpt-4.1
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)
    assertNotNull(resolved)
    assertEquals("sampleapp", resolved.defaults?.target)
    assertEquals("openai/gpt-4.1", resolved.defaults?.llm)
    val llm = assertNotNull(resolved.llm)
    assertEquals("openai/gpt-4.1", llm.defaults.model)
    assertEquals(1, llm.providers.size)
  }

  @Test
  fun `provider ref loads BuiltInProviderConfig`() {
    val providersDir = File(tempFolder.root, "providers").apply { mkdirs() }
    File(providersDir, "custom.yaml").writeText(
      """
      provider_id: custom
      name: Custom Provider
      default_model: x-1
      models:
        - id: x-1
          context_length: 8192
          max_output_tokens: 2048
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      providers:
        - ref: providers/custom.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<ProviderEntry.Inline>(resolved.providers.single())
    assertEquals("custom", inline.config.providerId)
    assertEquals("x-1", inline.config.defaultModel)
  }

  @Test
  fun `pack ref resolves relative to trailblaze_yaml dir`() {
    val packDir = File(tempFolder.root, "packs/sampleapp").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App Pack
        platforms:
          web:
            base_url: https://example.test
        tools:
          - tools/open_sample.yaml
      """.trimIndent(),
    )
    File(packDir, "tools").mkdirs()
    File(packDir, "tools/open_sample.yaml").writeText(
      """
      script: ./tools/open_sample.js
      name: openSample
      description: Open the sample app
      inputSchema:
        relativePath:
          type: string
          description: Sample-app fixture path.
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    assertEquals(listOf("sampleapp"), resolved.projectConfig.targets)
    val target = resolved.targets.single()
    assertEquals("sampleapp", target.id)
    assertEquals("Sample App Pack", target.displayName)
    assertEquals("https://example.test", target.platforms?.get("web")?.baseUrl)
    val tool = target.tools?.single()
    assertEquals("openSample", tool?.name)
    assertEquals("Open the sample app", tool?.description)
    // Confirms the loader wraps the flat author shape into a JSON-Schema-conformant
    // object with `type: object`, `properties`, and `required` derived from the
    // (default-true) `required` flag on each property.
    val schema = assertNotNull(tool?.inputSchema)
    assertEquals(JsonPrimitive("object"), schema["type"])
    val properties = assertIs<JsonObject>(assertNotNull(schema["properties"]))
    val relativePath = assertIs<JsonObject>(assertNotNull(properties["relativePath"]))
    assertEquals(JsonPrimitive("string"), relativePath["type"])
    assertEquals(JsonPrimitive("Sample-app fixture path."), relativePath["description"])
    assertEquals(JsonArray(listOf(JsonPrimitive("relativePath"))), schema["required"])
  }

  @Test
  fun `pack toolset and tool refs resolve relative to pack dir`() {
    val packDir = File(tempFolder.root, "packs/sampleapp").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample
      toolsets:
        - toolsets/pack-toolset.yaml
      """.trimIndent(),
    )
    File(packDir, "toolsets").mkdirs()
    File(packDir, "toolsets/pack-toolset.yaml").writeText(
      """
      id: pack_toolset
      description: Pack toolset
      tools:
        - pack_tool
      """.trimIndent(),
    )
    File(packDir, "tools").mkdirs()
    File(packDir, "tools/pack-tool.tool.yaml").writeText(
      """
      id: pack_tool
      description: Pack tool
      parameters: []
      tools:
        - tapOnElementWithText:
            text: "{{params.label}}"
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    assertEquals("pack_toolset", assertIs<ToolsetEntry.Inline>(resolved.toolsets.single()).config.id)
    assertEquals("pack_tool", assertIs<ToolEntry.Inline>(resolved.tools.single()).config.id)
  }

  @Test
  fun `pack with dependencies inherits per-platform defaults via the loader pipeline`() {
    // End-to-end integration test for the dependencies-based composition: verifies the loader's two-pass
    // (sibling resolution → dep-graph defaults) actually wires PackDependencyResolver
    // correctly. The resolver is well-tested in isolation; this guards against
    // regressions in the *wiring* between the loader and the resolver.
    //
    // Workspace shape:
    //   - `framework`: a library pack (no `target:`) publishing per-platform defaults
    //   - `myapp`: consumer with `dependencies: [framework]`, app-specific app_ids,
    //     omitted tool_sets on android (should inherit), explicit override on web
    val frameworkPackDir = File(tempFolder.root, "packs/framework").apply { mkdirs() }
    File(frameworkPackDir, "pack.yaml").writeText(
      """
      id: framework
      defaults:
        android:
          tool_sets:
            - core_interaction
            - memory
        web:
          drivers: [playwright-native, playwright-electron]
          tool_sets:
            - web_core
            - memory
      """.trimIndent(),
    )
    val appPackDir = File(tempFolder.root, "packs/myapp").apply { mkdirs() }
    File(appPackDir, "pack.yaml").writeText(
      """
      id: myapp
      dependencies:
        - framework
      target:
        display_name: My App
        platforms:
          android:
            app_ids:
              - com.example.myapp
          web:
            tool_sets:
              - only_my_tool
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - myapp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    // Library pack (`framework`) has no `target:` and isn't surfaced as a runnable target.
    val target = resolved.targets.single()
    assertEquals("myapp", target.id)

    // Android: consumer set app_ids; tool_sets inherited from framework defaults.
    val android = assertNotNull(target.platforms?.get("android"))
    assertEquals(listOf("com.example.myapp"), android.appIds)
    assertEquals(listOf("core_interaction", "memory"), android.toolSets)

    // Web: consumer's explicit tool_sets wins entirely (no list concat); drivers
    // inherited from framework defaults because consumer didn't set them.
    val web = assertNotNull(target.platforms?.get("web"))
    assertEquals(listOf("only_my_tool"), web.toolSets)
    assertEquals(listOf("playwright-native", "playwright-electron"), web.drivers)

    // Only myapp surfaces as a successful target id (framework is a library pack pulled
    // in via dependencies and contributes no target).
    assertEquals(
      listOf("myapp"),
      resolved.projectConfig.targets,
    )
  }


  @Test
  fun `unresolvable dependency surfaces as a strict consolidated error`() {
    // Pin the strict dep-graph validation behavior: declaring `dependencies: [foo]`
    // where `foo` is not in the resolved pool fails the workspace load with a single
    // consolidated error message that names the broken pack, the missing dep id, and
    // the available pool. The motivation for strictness — captured in the design
    // discussion that prompted this rewrite — is that a pack's `dependencies:` field
    // is a contract; silently dropping a pack whose contract isn't met leaves authors
    // staring at "no such target" mysteries downstream.
    val validPackDir = File(tempFolder.root, "packs/validpack").apply { mkdirs() }
    File(validPackDir, "pack.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Pack
        platforms:
          android:
            app_ids: [com.example.valid]
      """.trimIndent(),
    )
    val brokenPackDir = File(tempFolder.root, "packs/brokendep").apply { mkdirs() }
    File(brokenPackDir, "pack.yaml").writeText(
      """
      id: brokendep
      dependencies:
        - this-pack-does-not-exist
      target:
        display_name: Broken Dep Pack
        platforms:
          android:
            app_ids: [com.example.broken]
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - validpack
        - brokendep
      """.trimIndent(),
    )

    val error = runCatching { TrailblazeProjectConfigLoader.loadResolved(file) }
      .exceptionOrNull()
    val typed = assertIs<TrailblazeProjectConfigException>(error)
    val msg = typed.message.orEmpty()
    assertContains(msg, "dependency-graph validation failed")
    assertContains(msg, "brokendep")
    assertContains(msg, "this-pack-does-not-exist")
  }

  @Test
  fun `broken pack does not abort sibling pack resolution`() {
    val validPackDir = File(tempFolder.root, "packs/validpack").apply { mkdirs() }
    File(validPackDir, "pack.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Pack
      """.trimIndent(),
    )
    val brokenPackDir = File(tempFolder.root, "packs/brokenpack").apply { mkdirs() }
    File(brokenPackDir, "pack.yaml").writeText(
      """
      id: brokenpack
      target:
        display_name: Broken Pack
      toolsets:
        - toolsets/missing-toolset.yaml
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - validpack
        - brokenpack
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    assertEquals(listOf("validpack"), resolved.targets)
  }

  @Test
  fun `toolset ref loads ToolSetYamlConfig`() {
    val toolsetsDir = File(tempFolder.root, "toolsets").apply { mkdirs() }
    File(toolsetsDir, "custom.yaml").writeText(
      """
      id: my_custom_toolset
      description: Custom toolset
      tools:
        - some_custom_tool
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      toolsets:
        - ref: toolsets/custom.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<ToolsetEntry.Inline>(resolved.toolsets.single())
    assertEquals("my_custom_toolset", inline.config.id)
    assertEquals(listOf("some_custom_tool"), inline.config.tools)
  }

  @Test
  fun `tool ref loads ToolYamlConfig in class mode`() {
    val toolsDir = File(tempFolder.root, "tools").apply { mkdirs() }
    File(toolsDir, "my-tool.yaml").writeText(
      """
      id: my_custom_tool
      class: com.example.tools.MyCustomTool
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      tools:
        - ref: tools/my-tool.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<ToolEntry.Inline>(resolved.tools.single())
    assertEquals("my_custom_tool", inline.config.id)
    assertEquals("com.example.tools.MyCustomTool", inline.config.toolClass)
  }

  @Test
  fun `non-scalar ref value is wrapped as TrailblazeProjectConfigException`() {
    // Regression guard: previously this threw raw IllegalStateException from `error(...)`,
    // which escaped the loader's try/catch and produced an inconsistent failure mode.
    val file = tempFolder.writeConfig(
      """
      toolsets:
        - ref:
            path: nested.yaml
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "'ref:' must be a scalar string")
    }
  }

  @Test
  fun `load throws on invalid YAML`() {
    val file = tempFolder.writeConfig("this: is: not: valid")
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "Failed to parse")
    }
  }

  @Test
  fun `missing ref file throws with resolved path`() {
    val file = tempFolder.writeConfig(
      """
      toolsets:
        - ref: toolsets/does-not-exist.yaml
      """.trimIndent(),
    )
    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    try {
      TrailblazeProjectConfigLoader.resolveRefs(loaded)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      val msg = e.message ?: ""
      assertContains(msg, "does-not-exist.yaml")
      assertContains(msg, "Referenced toolset file not found")
    }
  }

  @Test
  fun `ref entry mixing inline fields fails with helpful message`() {
    val file = tempFolder.writeConfig(
      """
      toolsets:
        - ref: toolsets/foo.yaml
          id: inline-id
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "mixes 'ref:' with inline fields")
    }
  }

  @Test
  fun `encode then decode round-trips inline and ref entries across all sections`() {
    // Guards the four serializers' serialize() methods (the RefOnly encode path and the
    // inline delegation path). Without this, the encode branches are dead code. Targets
    // are now a flat list of pack-id strings; toolsets/providers still use the
    // Inline+Ref sealed shape and must round-trip cleanly.
    val original = TrailblazeProjectConfig(
      defaults = ProjectDefaults(target = "sampleapp", llm = "openai/gpt-4.1"),
      targets = listOf("sampleapp", "other"),
      toolsets = listOf(
        ToolsetEntry.Inline(ToolSetYamlConfig(id = "t1", tools = listOf("tapOnElement"))),
        ToolsetEntry.Ref("toolsets/shared.yaml"),
      ),
      providers = listOf(
        ProviderEntry.Inline(
          BuiltInProviderConfig(
            providerId = "custom",
            defaultModel = "x-1",
            models = listOf(LlmModelConfigEntry(id = "x-1")),
          ),
        ),
        ProviderEntry.Ref("providers/other.yaml"),
      ),
    )

    val yaml = TrailblazeConfigYaml.instance
    val encoded = yaml.encodeToString(TrailblazeProjectConfig.serializer(), original)
    val decoded = yaml.decodeFromString(TrailblazeProjectConfig.serializer(), encoded)

    assertEquals(original, decoded)
    assertEquals(listOf("sampleapp", "other"), decoded.targets)
    // Spot-check: ref entries serialize to a single-key `ref:` map, not the inline shape.
    assertContains(encoded, "ref: \"toolsets/shared.yaml\"")
    assertContains(encoded, "ref: \"providers/other.yaml\"")
  }

  @Test
  fun `blank ref value is rejected with a helpful message`() {
    val file = tempFolder.writeConfig(
      """
      toolsets:
        - ref: "   "
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "'ref:' must not be blank")
    }
  }

  @Test
  fun `ref that resolves to a directory surfaces as read failure`() {
    // A real directory named `toolsets` exists at the anchor; the ref points at it.
    // The loader should wrap the resulting IOException as TrailblazeProjectConfigException
    // rather than leaking a raw IOException.
    File(tempFolder.root, "toolsets").apply { mkdirs() }
    val file = tempFolder.writeConfig(
      """
      toolsets:
        - ref: toolsets
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.loadResolved(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      val msg = e.message ?: ""
      // Either the read fails ("Failed to read") or kaml parses the empty dir-as-text
      // path differently; we just want the wrapping type.
      assertContains(msg, "toolset ref 'toolsets'")
    }
  }

  @Test
  fun `field type mismatch surfaces as parse failure`() {
    // `targets:` declared as a scalar instead of a list.
    val file = tempFolder.writeConfig(
      """
      targets: "oops-not-a-list"
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "Failed to parse")
    }
  }

  // ===========================================================================
  // Tests for pack-bundled waypoint resolution and classpath/workspace precedence.
  // ===========================================================================

  @Test
  fun `pack-bundled waypoints are auto-discovered from the waypoints directory`() {
    // Pin the post-2026-05-08 contract: pack.yaml no longer enumerates waypoint paths.
    // Anything in <pack>/waypoints/**.waypoint.yaml ships with the pack automatically,
    // mirroring the tools/ auto-discovery from the same migration.
    val packDir = File(tempFolder.root, "packs/sampleapp").apply { mkdirs() }
    val waypointsDir = File(packDir, "waypoints").apply { mkdirs() }
    File(waypointsDir, "ready.waypoint.yaml").writeText(
      """
      id: "sampleapp/ready"
      description: "Sample app's main screen, ready for input."
      """.trimIndent(),
    )
    File(packDir, "pack.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    assertEquals(1, resolved.waypoints.size)
    val waypoint = resolved.waypoints.single()
    assertEquals("sampleapp/ready", waypoint.id)
    assertEquals("Sample app's main screen, ready for input.", waypoint.description)
  }

  @Test
  fun `pack-bundled waypoints under nested subdirectories are auto-discovered`() {
    // Pin: discovery walks the whole `waypoints/` tree, not just direct children. The
    // Square pack organizes ~120 waypoints under `waypoints/{android,ios,web}/...` (web
    // dashboard waypoints sit four levels deep), so the discovery path has to recurse.
    val packDir = File(tempFolder.root, "packs/multitarget").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: multitarget
      target:
        display_name: Multi-target Pack
      """.trimIndent(),
    )
    val androidDir = File(packDir, "waypoints/android").apply { mkdirs() }
    File(androidDir, "home.waypoint.yaml").writeText(
      """
      id: "multitarget/android/home"
      description: "Android home screen."
      """.trimIndent(),
    )
    val webDeepDir = File(packDir, "waypoints/web/dashboard/items").apply { mkdirs() }
    File(webDeepDir, "library.waypoint.yaml").writeText(
      """
      id: "multitarget/web/dashboard/items/library"
      description: "Web dashboard items library, deeply nested."
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - multitarget
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    val ids = resolved.waypoints.map(WaypointDefinition::id).toSet()
    assertEquals(
      setOf("multitarget/android/home", "multitarget/web/dashboard/items/library"),
      ids,
    )
  }

  @Test
  fun `pack-bundled YAML outside the waypoints directory is NOT picked up`() {
    // Symmetric structural-integrity rule with the tools/ side: a YAML file with a
    // `.waypoint.yaml` suffix that lives somewhere other than `<pack>/waypoints/` (e.g.
    // a misplaced file in `<pack>/misc/`) must not register as a waypoint.
    val packDir = File(tempFolder.root, "packs/sampleapp").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
      """.trimIndent(),
    )
    val miscDir = File(packDir, "misc").apply { mkdirs() }
    File(miscDir, "stray.waypoint.yaml").writeText(
      """
      id: "sampleapp/stray"
      description: "Should not register because it isn't under waypoints/."
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    assertTrue(resolved.waypoints.isEmpty(), "Got: ${resolved.waypoints.map { it.id }}")
  }

  @Test
  fun `library pack with waypoints on disk drops the whole pack with logged failure`() {
    // Discovery-side library-pack guard: a target-less pack that ships waypoint files
    // on disk must drop the whole pack. Symmetric with the manifest-side check on
    // `manifest.waypoints.isNotEmpty()` — covers the case where the legacy manifest list
    // is absent but YAMLs exist under waypoints/. Mirrors the trailhead-in-library-pack
    // test: the bad library pack enters scope via a target's dependencies, then drops
    // atomic-per-pack when sibling resolution fires the guard.
    val libraryPackDir = File(tempFolder.root, "packs/badlibrary").apply { mkdirs() }
    File(libraryPackDir, "pack.yaml").writeText(
      """
      id: badlibrary
      """.trimIndent(),
    )
    val waypointsDir = File(libraryPackDir, "waypoints").apply { mkdirs() }
    File(waypointsDir, "stray.waypoint.yaml").writeText(
      """
      id: "badlibrary/stray"
      description: "Library pack must not own this."
      """.trimIndent(),
    )
    val targetPackDir = File(tempFolder.root, "packs/consumer").apply { mkdirs() }
    File(targetPackDir, "pack.yaml").writeText(
      """
      id: consumer
      dependencies:
        - badlibrary
      target:
        display_name: Consumer
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - consumer
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    // Atomic-per-pack: the bad library drops; the consumer target may still surface but
    // the library's stray waypoint must not appear in the resolved pool.
    assertTrue(
      resolved.waypoints.isEmpty(),
      "Library-pack waypoint must not surface. Got: ${resolved.waypoints.map { it.id }}",
    )
  }

  @Test
  fun `pack with malformed waypoint YAML drops the whole pack from resolved waypoints`() {
    // Atomic-per-pack: a waypoint YAML that fails to decode drops the WHOLE pack —
    // including the target — so neither the waypoint nor the target appears in the
    // result. (Replaces the pre-auto-discovery "broken manifest ref" test: missing
    // refs aren't a thing anymore now that the manifest list is ignored.)
    val packDir = File(tempFolder.root, "packs/broken").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: broken
      target:
        display_name: Broken Pack
      """.trimIndent(),
    )
    val waypointsDir = File(packDir, "waypoints").apply { mkdirs() }
    File(waypointsDir, "malformed.waypoint.yaml").writeText(
      "this is not: { a valid waypoint yaml document",
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - broken
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    assertTrue(resolved.waypoints.isEmpty())
    assertTrue(resolved.projectConfig.targets.isEmpty())
    assertTrue(resolved.targets.isEmpty())
  }

  @Test
  fun `workspace pack wholesale shadows same-id classpath pack`() {
    val classpathRoot = newTempDir()
    val classpathPackDir = File(classpathRoot, "trailblaze-config/packs/sampleapp").apply { mkdirs() }
    File(classpathPackDir, "pack.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Classpath Sample App (should be shadowed)
      toolsets:
        - toolsets/classpath_only.yaml
      waypoints:
        - waypoints/classpath.waypoint.yaml
      """.trimIndent(),
    )
    File(File(classpathPackDir, "waypoints").apply { mkdirs() }, "classpath.waypoint.yaml").writeText(
      """
      id: "sampleapp/classpath-only"
      description: "Should NOT appear when workspace pack shadows."
      """.trimIndent(),
    )
    File(File(classpathPackDir, "toolsets").apply { mkdirs() }, "classpath_only.yaml").writeText(
      """
      id: classpath_only_toolset
      description: Should NOT appear when workspace pack shadows.
      tools:
        - some_tool
      """.trimIndent(),
    )
    File(File(classpathPackDir, "tools").apply { mkdirs() }, "classpath_only.tool.yaml").writeText(
      """
      id: classpath_only_tool
      class: xyz.block.trailblaze.fake.ClasspathOnlyTool
      """.trimIndent(),
    )

    val workspacePackDir = File(tempFolder.root, "packs/sampleapp").apply { mkdirs() }
    File(File(workspacePackDir, "waypoints").apply { mkdirs() }, "workspace.waypoint.yaml").writeText(
      """
      id: "sampleapp/workspace-only"
      description: "Workspace pack contributes this."
      """.trimIndent(),
    )
    File(File(workspacePackDir, "toolsets").apply { mkdirs() }, "workspace_only.yaml").writeText(
      """
      id: workspace_only_toolset
      description: Workspace pack contributes this.
      tools:
        - some_tool
      """.trimIndent(),
    )
    File(File(workspacePackDir, "tools").apply { mkdirs() }, "workspace_only.tool.yaml").writeText(
      """
      id: workspace_only_tool
      class: xyz.block.trailblaze.fake.WorkspaceOnlyTool
      """.trimIndent(),
    )
    File(workspacePackDir, "pack.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Workspace Sample App (should win)
      toolsets:
        - toolsets/workspace_only.yaml
      waypoints:
        - waypoints/workspace.waypoint.yaml
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val resolved = withClasspathRoot(classpathRoot) {
      TrailblazeProjectConfigLoader.loadResolvedRuntime(
        configFile = configFile,
        includeClasspathPacks = true,
      )
    }

    assertNotNull(resolved)

    // Target: workspace's display_name wins.
    val target = resolved.targets.single()
    assertEquals("sampleapp", target.id)
    assertEquals(
      expected = "Workspace Sample App (should win)",
      actual = target.displayName,
      message = "Workspace pack should shadow classpath pack of the same id",
    )

    // Waypoints: only the workspace one appears — classpath pack is shadowed wholesale.
    val waypointIds = resolved.waypoints.map(WaypointDefinition::id)
    assertEquals(listOf("sampleapp/workspace-only"), waypointIds)

    // Toolsets: same wholesale-shadow rule. Only the workspace toolset appears,
    // and the classpath toolset must NOT leak through. The pack-id collision
    // documented on TrailblazeResolvedConfig is the contract this test gates.
    val toolsetIds = resolved.projectConfig.toolsets
      .map { assertIs<ToolsetEntry.Inline>(it).config.id }
    assertEquals(listOf("workspace_only_toolset"), toolsetIds)

    // Tools: same. The classpath pack's `class:`-backed tool is shadowed completely
    // — it's not merged in, just dropped.
    val toolIds = resolved.projectConfig.tools
      .map { assertIs<ToolEntry.Inline>(it).config.id }
    assertEquals(listOf("workspace_only_tool"), toolIds)
  }

  @Test
  fun `classpath pack contributes when workspace declares no same-id pack`() {
    val classpathRoot = newTempDir()
    val classpathPackDir = File(classpathRoot, "trailblaze-config/packs/framework").apply { mkdirs() }
    File(classpathPackDir, "pack.yaml").writeText(
      """
      id: framework
      target:
        display_name: Framework-Bundled Pack
      """.trimIndent(),
    )

    val configFile = tempFolder.writeConfig("")

    val resolved = withClasspathRoot(classpathRoot) {
      TrailblazeProjectConfigLoader.loadResolvedRuntime(
        configFile = configFile,
        includeClasspathPacks = true,
      )
    }

    assertNotNull(resolved)
    val ids = resolved.targets.map { it.id }
    assertContains(ids, "framework")
  }

  @Test
  fun `pack target system_prompt_file resolves into config systemPrompt`() {
    val packDir = File(tempFolder.root, "packs/sampleapp").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App Pack
        system_prompt_file: prompt.md
      """.trimIndent(),
    )
    File(packDir, "prompt.md").writeText("You are testing Sample App.\nBe direct.")
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    val target = resolved.targets.single()
    assertEquals("You are testing Sample App.\nBe direct.", target.systemPrompt)
  }

  @Test
  fun `pack with missing system_prompt_file is skipped with logged failure`() {
    // Atomic-per-pack failure model: a pack with an unresolvable system_prompt_file fails to
    // resolve, but sibling packs continue. Mirrors the behavior the existing test
    // `failed pack ref does not poison sibling packs` documents for other ref types.
    val packDir = File(tempFolder.root, "packs/missingprompt").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: missingprompt
      target:
        display_name: Missing Prompt
        system_prompt_file: does-not-exist.md
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - missingprompt
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    // Pack failed to resolve → no target surfaces from it.
    assertTrue(
      resolved.targets.isEmpty(),
      "Pack with missing system_prompt_file should not contribute a target; got: ${resolved.targets}",
    )
  }

  @Test
  fun `pack with legacy inline system_prompt fails the load with migration message`() {
    // The schema dropped `system_prompt:` from PackTargetConfig; lenient YAML decode would
    // silently lose the prompt content. The pre-decode typed shape (`LegacyInlineSystemPromptShape`)
    // in `TrailblazePackManifestLoader.parseManifest` catches this and fails the load loudly,
    // pointing the author at `system_prompt_file`. Atomic-per-pack: this pack drops, the
    // resolved config still loads with no targets surfaced from it.
    val packDir = File(tempFolder.root, "packs/legacyinline").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: legacyinline
      target:
        display_name: Legacy Inline
        system_prompt: |
          Inline prompt content the schema no longer accepts.
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - legacyinline
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    assertTrue(
      resolved.targets.isEmpty(),
      "Pack declaring legacy inline system_prompt should not contribute a target; got: ${resolved.targets}",
    )
  }

  @Test
  fun `system_prompt_file failure is atomic — sibling packs still resolve`() {
    // Pins the atomic-per-pack contract for system_prompt_file failures specifically. Mirrors
    // the existing `failed pack ref does not poison sibling packs` test for other ref types: a
    // broken pack should drop on its own without taking out workspace siblings.
    val brokenDir = File(tempFolder.root, "packs/broken").apply { mkdirs() }
    File(brokenDir, "pack.yaml").writeText(
      """
      id: broken
      target:
        display_name: Broken
        system_prompt_file: does-not-exist.md
      """.trimIndent(),
    )
    val validDir = File(tempFolder.root, "packs/valid").apply { mkdirs() }
    File(validDir, "pack.yaml").writeText(
      """
      id: valid
      target:
        display_name: Valid Sibling
        system_prompt_file: prompt.md
      """.trimIndent(),
    )
    File(validDir, "prompt.md").writeText("Valid sibling prompt content.")
    val file = tempFolder.writeConfig(
      """
      targets:
        - broken
        - valid
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    val targets = resolved.targets
    assertEquals(
      listOf("valid"),
      targets.map { it.id },
      "Only the valid sibling should surface; broken pack failure must not poison its sibling",
    )
    assertEquals("Valid sibling prompt content.", targets.single().systemPrompt)
  }

  @Test
  fun `legacy inline system_prompt failure is atomic — sibling packs still resolve`() {
    // Same atomic-per-pack guarantee for the migration-error path: a pack still authored with
    // the removed inline `system_prompt:` field should drop, but a properly-authored sibling
    // continues to resolve.
    val legacyDir = File(tempFolder.root, "packs/legacy").apply { mkdirs() }
    File(legacyDir, "pack.yaml").writeText(
      """
      id: legacy
      target:
        display_name: Legacy
        system_prompt: |
          Inline content the schema rejects.
      """.trimIndent(),
    )
    val validDir = File(tempFolder.root, "packs/modern").apply { mkdirs() }
    File(validDir, "pack.yaml").writeText(
      """
      id: modern
      target:
        display_name: Modern
        system_prompt_file: prompt.md
      """.trimIndent(),
    )
    File(validDir, "prompt.md").writeText("Modern prompt content.")
    val file = tempFolder.writeConfig(
      """
      targets:
        - legacy
        - modern
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    val targets = resolved.targets
    assertEquals(listOf("modern"), targets.map { it.id })
    assertEquals("Modern prompt content.", targets.single().systemPrompt)
  }

  @Test
  fun `legacy-prompt typed guard tolerates system_prompt mention inside an indented block scalar`() {
    // The pre-decode guard parses the manifest into `LegacyInlineSystemPromptShape` and only fires
    // when kaml populates `target.systemPrompt` from a real YAML key. A `system_prompt:` literal
    // that appears as block-scalar content (e.g. inside a `display_name:` description) is just
    // text under another key and never resolves into the shape's `systemPrompt` field. Locks in
    // that false-positive-safety contract — replaced an earlier regex implementation that DID
    // match block-scalar content, so this test exists to prevent a future regression back to
    // textual matching.
    val packDir = File(tempFolder.root, "packs/blockscalar").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: blockscalar
      target:
        display_name: |
          A pack whose description mentions
            system_prompt: foo
          inside a block scalar — should NOT trip the legacy-inline guard.
        system_prompt_file: prompt.md
      """.trimIndent(),
    )
    File(packDir, "prompt.md").writeText("Block scalar test prompt.")
    val file = tempFolder.writeConfig(
      """
      targets:
        - blockscalar
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    val target = resolved.targets.single()
    assertEquals("blockscalar", target.id)
    assertEquals("Block scalar test prompt.", target.systemPrompt)
  }

  @Test
  fun `pack target with neither system_prompt nor system_prompt_file decodes without throwing`() {
    // A pack target is allowed to declare no prompt at all (e.g. a target that only contributes
    // tools / waypoints and inherits its prompt from elsewhere or has none). The pre-decode
    // legacy guard must NOT fire on this case, and the typed decode must NOT add a synthetic
    // error. Pinned because a future tightening of the loader (e.g. "every target needs a prompt")
    // could regress this implicit allowance silently.
    val packDir = File(tempFolder.root, "packs/promptless").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: promptless
      target:
        display_name: Promptless Target
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - promptless
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!

    val target = resolved.targets.single()
    assertEquals("promptless", target.id)
    assertEquals(null, target.systemPrompt)
  }

  @Test
  fun `legacy-prompt guard returns silently on malformed YAML — typed decode reports the real error`() {
    // The legacy-prompt guard's catch is intentionally broad (Exception): malformed YAML must
    // not propagate from the guard, because the real typed decode immediately after will surface
    // a precise error. If the guard threw instead, authors would see a stack trace from the
    // legacy-detector pointing at unrelated YAML structure errors. Pinned so a future narrowing
    // of the catch (e.g. back to SerializationException only) can't silently regress this.
    val packDir = File(tempFolder.root, "packs/malformed").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: malformed
      target:
        display_name: Has malformed YAML below
        system_prompt_file: prompt.md
      this is :: not valid :: yaml :: at :: all
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - malformed
      """.trimIndent(),
    )

    // Should not throw out of the guard (atomic-per-pack: malformed pack drops, sibling-less
    // resolved still loads cleanly).
    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathPacks = false)!!
    assertTrue(
      resolved.targets.isEmpty(),
      "Malformed pack should drop atomically; got: ${resolved.targets}",
    )
  }

  // ===========================================================================
  // Library-pack contract: a tool YAML carrying a `trailhead:` block is rejected
  // when its owning pack has no `target:`. The waypoints-on-library-pack rule
  // is enforced earlier (manifest-level) by TrailblazePackManifestLoader; this
  // rule has to live here because the trailhead block lives inside the tool YAML,
  // which only gets loaded once the manifest's `tools:` paths are resolved.
  // ===========================================================================

  @Test
  fun `library pack with trailhead tool drops the whole pack with logged failure`() {
    // A library pack (no target:) must enter scope only via dependencies. To exercise the
    // tool-level trailhead guard, a target pack pulls the bad library in via dependencies;
    // the library's broken trailhead drops the WHOLE library pack.
    val libraryPackDir = File(tempFolder.root, "packs/badlibrary").apply { mkdirs() }
    val trailheadsDir = File(libraryPackDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "go_home.trailhead.yaml").writeText(
      """
      id: go_home
      class: com.example.GoHomeTrailhead
      trailhead:
        to: app/home
      """.trimIndent(),
    )
    File(libraryPackDir, "pack.yaml").writeText(
      """
      id: badlibrary
      """.trimIndent(),
    )
    val targetPackDir = File(tempFolder.root, "packs/consumer").apply { mkdirs() }
    File(targetPackDir, "pack.yaml").writeText(
      """
      id: consumer
      dependencies:
        - badlibrary
      target:
        display_name: Consumer
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - consumer
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    // Atomic-per-pack: the trailhead-in-library-pack violation drops the bad library pack.
    // The consumer target may still surface (its own resolution didn't fail), but the
    // library's trailhead tool is excluded.
    assertNotNull(resolved)
    assertTrue(resolved.projectConfig.tools.isEmpty())
  }

  @Test
  fun `target pack with trailhead tool resolves cleanly`() {
    // Pin the happy path: a target pack legitimately owns trailhead tools — the guard
    // must only fire for library packs.
    val packDir = File(tempFolder.root, "packs/goodtarget").apply { mkdirs() }
    val trailheadsDir = File(packDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "go_home.trailhead.yaml").writeText(
      """
      id: go_home
      class: com.example.GoHomeTrailhead
      trailhead:
        to: app/home
      """.trimIndent(),
    )
    File(packDir, "pack.yaml").writeText(
      """
      id: goodtarget
      target:
        display_name: Good Target
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - goodtarget
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    assertEquals(1, resolved.projectConfig.targets.size)
    assertEquals(1, resolved.projectConfig.tools.size)
  }

  // ===========================================================================
  // Auto-discovery (`targets:` empty / omitted) — every target pack found under
  // `<workspace>/packs/<id>/pack.yaml` loads automatically. Library packs are
  // not auto-discovered as roots — they reach scope only via `dependencies:`.
  // ===========================================================================

  @Test
  fun `auto-discovery loads every target pack under workspace packs dir when targets is omitted`() {
    val firstPackDir = File(tempFolder.root, "packs/firstapp").apply { mkdirs() }
    File(firstPackDir, "pack.yaml").writeText(
      """
      id: firstapp
      target:
        display_name: First App
        platforms:
          android:
            app_ids: [com.example.first]
      """.trimIndent(),
    )
    val secondPackDir = File(tempFolder.root, "packs/secondapp").apply { mkdirs() }
    File(secondPackDir, "pack.yaml").writeText(
      """
      id: secondapp
      target:
        display_name: Second App
        platforms:
          android:
            app_ids: [com.example.second]
      """.trimIndent(),
    )
    // A library pack sitting alongside the targets — must NOT auto-discover as a root,
    // but should still be reachable transitively if a target depends on it.
    val libraryPackDir = File(tempFolder.root, "packs/shared-lib").apply { mkdirs() }
    File(libraryPackDir, "pack.yaml").writeText(
      """
      id: shared-lib
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig("")

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    assertEquals(
      setOf("firstapp", "secondapp"),
      resolved.projectConfig.targets.toSet(),
      "Auto-discovery should pick up both target packs but skip the library pack",
    )
    assertEquals(2, resolved.targets.size)
  }

  @Test
  fun `auto-discovery skips a malformed pack atomically while siblings continue to load`() {
    val goodPackDir = File(tempFolder.root, "packs/goodapp").apply { mkdirs() }
    File(goodPackDir, "pack.yaml").writeText(
      """
      id: goodapp
      target:
        display_name: Good App
        platforms:
          android:
            app_ids: [com.example.good]
      """.trimIndent(),
    )
    val brokenPackDir = File(tempFolder.root, "packs/broken").apply { mkdirs() }
    // Truncated YAML — `display_name` has no value, kaml fails to parse.
    File(brokenPackDir, "pack.yaml").writeText("id: broken\ntarget:\n  display_name")
    val configFile = tempFolder.writeConfig("")

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    assertEquals(
      listOf("goodapp"),
      resolved.projectConfig.targets,
      "Malformed pack should be skipped atomically; sibling pack should still load",
    )
  }

  // ===========================================================================
  // Workspace `targets:` only accepts target packs — listing a library-pack id
  // there is rejected with a redirecting error pointing at `dependencies:`.
  // ===========================================================================

  @Test
  fun `workspace targets list rejects library pack id with redirecting error`() {
    // Library pack on disk (no `target:` block).
    val libDir = File(tempFolder.root, "packs/shared-lib").apply { mkdirs() }
    File(libDir, "pack.yaml").writeText(
      """
      id: shared-lib
      """.trimIndent(),
    )
    // Author tries to list the library pack at the workspace level — this is
    // a category error since library packs reach scope only via `dependencies:`.
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - shared-lib
      """.trimIndent(),
    )

    val error = runCatching {
      TrailblazeProjectConfigLoader.loadResolvedRuntime(configFile, includeClasspathPacks = false)
    }.exceptionOrNull()
    val typed = assertIs<TrailblazeProjectConfigException>(error)
    val msg = typed.message.orEmpty()
    assertContains(msg, "Workspace `targets:` validation failed")
    assertContains(msg, "shared-lib")
    assertContains(msg, "library")
    // The error must point the author at the right surface — `dependencies:`.
    assertContains(msg, "dependencies:")
  }

  // ===========================================================================
  // Multi-edge dep-graph validation — every broken edge across every loaded
  // pack appears in a single consolidated error so the author can see the
  // whole list in one shot rather than fix-and-retry.
  // ===========================================================================

  @Test
  fun `consolidated dep-graph error names every broken edge across every loaded pack`() {
    val firstPackDir = File(tempFolder.root, "packs/firstapp").apply { mkdirs() }
    File(firstPackDir, "pack.yaml").writeText(
      """
      id: firstapp
      dependencies:
        - missing-one
      target:
        display_name: First App
        platforms:
          android:
            app_ids: [com.example.first]
      """.trimIndent(),
    )
    val secondPackDir = File(tempFolder.root, "packs/secondapp").apply { mkdirs() }
    File(secondPackDir, "pack.yaml").writeText(
      """
      id: secondapp
      dependencies:
        - missing-two
        - missing-three
      target:
        display_name: Second App
        platforms:
          android:
            app_ids: [com.example.second]
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - firstapp
        - secondapp
      """.trimIndent(),
    )

    val error = runCatching {
      TrailblazeProjectConfigLoader.loadResolvedRuntime(configFile, includeClasspathPacks = false)
    }.exceptionOrNull()
    val typed = assertIs<TrailblazeProjectConfigException>(error)
    val msg = typed.message.orEmpty()
    assertContains(msg, "dependency-graph validation failed")
    // Every broken edge appears in the single consolidated message — pinning
    // the difference vs. atomic-per-pack (which would surface only the first).
    assertContains(msg, "firstapp")
    assertContains(msg, "missing-one")
    assertContains(msg, "secondapp")
    assertContains(msg, "missing-two")
    assertContains(msg, "missing-three")
  }

  // ===========================================================================
  // Test infrastructure.
  // ===========================================================================

  private val classpath = ClasspathFixture()

  @org.junit.After fun cleanup() = classpath.cleanup()

  /** Creates a temp dir tracked for cleanup at @After. Used as a fake classpath root. */
  private fun newTempDir(): File = classpath.newTempDir(prefix = "project-config-loader-test")

  /**
   * Runs [block] with [Thread.currentThread]'s context classloader swapped for one rooted
   * at [root], so classpath-pack discovery sees the fixture instead of the framework's
   * actual bundled packs.
   */
  private fun <T> withClasspathRoot(root: File, block: () -> T): T =
    classpath.withClasspathRoot(root, block)

  private fun TemporaryFolder.writeConfig(yaml: String): File {
    val file = File(root, "trailblaze.yaml")
    file.writeText(yaml)
    return file
  }
}
