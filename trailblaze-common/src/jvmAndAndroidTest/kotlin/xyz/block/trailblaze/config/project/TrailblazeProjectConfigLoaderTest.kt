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
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.PlatformConfig
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
    assertTrue(raw.packs.isEmpty())
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
  fun `inline target entry decodes into AppTargetYamlConfig`() {
    val file = tempFolder.writeConfig(
      """
      targets:
        - id: sampleapp
          display_name: Trailblaze Sample App
          platforms:
            android:
              app_ids:
                - xyz.block.trailblaze.examples.sampleapp
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<TargetEntry.Inline>(resolved.targets.single())
    assertEquals("sampleapp", inline.config.id)
    assertEquals("Trailblaze Sample App", inline.config.displayName)
    assertEquals(
      listOf("xyz.block.trailblaze.examples.sampleapp"),
      inline.config.platforms?.get("android")?.appIds,
    )
  }

  @Test
  fun `ref target entry resolves relative to trailblaze_yaml dir`() {
    val targetsDir = File(tempFolder.root, "targets").apply { mkdirs() }
    File(targetsDir, "my-app.yaml").writeText(
      """
      id: my-app
      display_name: My App
      platforms:
        android:
          app_ids:
            - com.example.my-app
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: targets/my-app.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<TargetEntry.Inline>(resolved.targets.single())
    assertEquals("my-app", inline.config.id)
    assertEquals("My App", inline.config.displayName)
  }

  @Test
  fun `leading slash treats ref as anchor-relative`() {
    val targetsDir = File(tempFolder.root, "targets").apply { mkdirs() }
    File(targetsDir, "anchor-relative.yaml").writeText(
      """
      id: anchored
      display_name: Anchored
      platforms:
        android:
          app_ids:
            - com.example.anchored
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: /targets/anchor-relative.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<TargetEntry.Inline>(resolved.targets.single())
    assertEquals("anchored", inline.config.id)
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
      packs:
        - packs/sampleapp/pack.yaml
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    assertEquals(listOf("packs/sampleapp/pack.yaml"), resolved.packs)
    val target = assertIs<TargetEntry.Inline>(resolved.targets.single()).config
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
      toolsets:
        - toolsets/pack-toolset.yaml
      tools:
        - tools/pack-tool.yaml
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
    File(packDir, "tools/pack-tool.yaml").writeText(
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
      packs:
        - packs/sampleapp/pack.yaml
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
      packs:
        - packs/framework/pack.yaml
        - packs/myapp/pack.yaml
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    // Library pack (`framework`) has no `target:` and isn't surfaced as a runnable target.
    val target = assertIs<TargetEntry.Inline>(resolved.targets.single()).config
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

    // Both workspace packs report as successfully landed (framework has no target,
    // myapp's target made it through dep resolution).
    assertEquals(
      listOf("packs/framework/pack.yaml", "packs/myapp/pack.yaml"),
      resolved.packs,
    )
  }


  @Test
  fun `pack with missing dependency is skipped while sibling packs continue to load`() {
    // Integration counterpart to PackDependencyResolverTest's missing-dep unit tests:
    // verifies the loader catches the resolver's TrailblazeProjectConfigException and
    // applies the documented atomic-per-pack failure-isolation contract — sibling
    // packs continue to load, the broken pack's target is excluded from the result,
    // and (per the `successfulWorkspaceRefs` semantics) its workspace ref is NOT
    // recorded as successful. Without this test, a future refactor could swallow the
    // exception too aggressively or too narrowly without any regression signal.
    val validPackDir = File(tempFolder.root, "packs/valid-pack").apply { mkdirs() }
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
    val brokenPackDir = File(tempFolder.root, "packs/broken-dep").apply { mkdirs() }
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
      packs:
        - packs/valid-pack/pack.yaml
        - packs/broken-dep/pack.yaml
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    // Only the valid pack lands as a target; the broken-dep pack is skipped.
    assertEquals(
      listOf("validpack"),
      resolved.targets.map { assertIs<TargetEntry.Inline>(it).config.id },
    )
    // Per `successfulWorkspaceRefs` semantics, only refs whose target made it into
    // the resolved config are recorded; the broken pack contributed no target so its
    // ref is excluded.
    assertEquals(listOf("packs/valid-pack/pack.yaml"), resolved.packs)
  }

  @Test
  fun `broken pack does not abort sibling pack resolution`() {
    val validPackDir = File(tempFolder.root, "packs/valid-pack").apply { mkdirs() }
    File(validPackDir, "pack.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Pack
      """.trimIndent(),
    )
    val brokenPackDir = File(tempFolder.root, "packs/broken-pack").apply { mkdirs() }
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
      packs:
        - packs/valid-pack/pack.yaml
        - packs/broken-pack/pack.yaml
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    assertEquals(listOf("packs/valid-pack/pack.yaml"), resolved.packs)
    assertEquals("validpack", assertIs<TargetEntry.Inline>(resolved.targets.single()).config.id)
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
      targets:
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
      targets:
        - ref: targets/does-not-exist.yaml
      """.trimIndent(),
    )
    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    try {
      TrailblazeProjectConfigLoader.resolveRefs(loaded)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      val msg = e.message ?: ""
      assertContains(msg, "does-not-exist.yaml")
      assertContains(msg, "Referenced target file not found")
    }
  }

  @Test
  fun `ref entry mixing inline fields fails with helpful message`() {
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: targets/foo.yaml
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
    // inline delegation path). Without this, the encode branches are dead code.
    val original = TrailblazeProjectConfig(
      defaults = ProjectDefaults(target = "sampleapp", llm = "openai/gpt-4.1"),
      targets = listOf(
        TargetEntry.Inline(
          AppTargetYamlConfig(
            id = "sampleapp",
            displayName = "Sample App",
            platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.example.sample"))),
          ),
        ),
        TargetEntry.Ref("targets/other.yaml"),
      ),
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
    // Spot-check: ref entries serialize to a single-key `ref:` map, not the inline shape.
    assertContains(encoded, "ref: \"targets/other.yaml\"")
    assertContains(encoded, "ref: \"toolsets/shared.yaml\"")
    assertContains(encoded, "ref: \"providers/other.yaml\"")
  }

  @Test
  fun `blank ref value is rejected with a helpful message`() {
    val file = tempFolder.writeConfig(
      """
      targets:
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
    // A real directory named `targets` exists at the anchor; the ref points at it.
    // The loader should wrap the resulting IOException as TrailblazeProjectConfigException
    // rather than leaking a raw IOException.
    File(tempFolder.root, "targets").apply { mkdirs() }
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: targets
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.loadResolved(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      val msg = e.message ?: ""
      // Either the read fails ("Failed to read") or kaml parses the empty dir-as-text
      // path differently; we just want the wrapping type.
      assertContains(msg, "target ref 'targets'")
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

  @Test
  fun `schema violation on target missing required id field`() {
    val file = tempFolder.writeConfig(
      """
      targets:
        - display_name: Missing Id
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
  fun `pack-bundled waypoints surface in resolved runtime config`() {
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
      waypoints:
        - waypoints/ready.waypoint.yaml
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      packs:
        - packs/sampleapp/pack.yaml
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
  fun `pack with broken waypoint ref drops the whole pack from resolved waypoints`() {
    val packDir = File(tempFolder.root, "packs/broken").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: broken
      target:
        display_name: Broken Pack
      waypoints:
        - waypoints/missing.waypoint.yaml
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      packs:
        - packs/broken/pack.yaml
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathPacks = false,
    )

    assertNotNull(resolved)
    // Atomic-per-pack: a broken waypoint ref drops the WHOLE pack — including the
    // target — so neither the waypoint nor the target appears in the result.
    assertTrue(resolved.waypoints.isEmpty())
    assertTrue(resolved.projectConfig.targets.isEmpty())
    assertTrue(resolved.projectConfig.packs.isEmpty())
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
      tools:
        - tools/classpath_only.yaml
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
    File(File(classpathPackDir, "tools").apply { mkdirs() }, "classpath_only.yaml").writeText(
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
    File(File(workspacePackDir, "tools").apply { mkdirs() }, "workspace_only.yaml").writeText(
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
      tools:
        - tools/workspace_only.yaml
      waypoints:
        - waypoints/workspace.waypoint.yaml
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig(
      """
      packs:
        - packs/sampleapp/pack.yaml
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
    val target = assertIs<TargetEntry.Inline>(resolved.projectConfig.targets.single()).config
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
    val ids = resolved.projectConfig.targets.map { assertIs<TargetEntry.Inline>(it).config.id }
    assertContains(ids, "framework")
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
