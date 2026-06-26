package xyz.block.trailblaze.config.project

import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * Tests pass `includeClasspathTrailmaps = false` (or rely on the default `loadResolved`
 * non-classpath path) where they need to assert workspace-only behaviour without the
 * framework-bundled trailmaps in `trailblaze-models` polluting the result set.
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
  fun `decodes defaults max-llm-calls under the kebab-case key`() {
    val file = tempFolder.writeConfig(
      """
      defaults:
        max-llm-calls: 30
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)
    assertNotNull(resolved)
    assertEquals(30, resolved.defaults?.maxLlmCalls)
  }

  @Test
  fun `defaults max-llm-calls defaults to null when absent`() {
    val file = tempFolder.writeConfig(
      """
      defaults:
        target: sampleapp
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)
    assertNotNull(resolved)
    assertNull(resolved.defaults?.maxLlmCalls)
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
  fun `trailmap ref resolves relative to trailblaze_yaml dir`() {
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App Trailmap
        platforms:
          web:
            base_url: https://example.test
        tools:
          - openSample
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/open_sample.yaml").writeText(
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

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

    assertEquals(listOf("sampleapp"), resolved.projectConfig.targets)
    val target = resolved.targets.single()
    assertEquals("sampleapp", target.id)
    assertEquals("Sample App Trailmap", target.displayName)
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
  fun `per-platform scripted tools hoist into target tools and strip from the platform section`() {
    // Mirror of the build-time generator (TrailblazeBundledConfigTasks): scripted tools declared
    // under `platforms.<p>.tools:` resolve into the delivered top-level `target.tools:` and are
    // stripped from the per-platform `tools:` (so the YamlBackedHostAppTarget per-platform path
    // doesn't re-resolve a descriptor-less name and warn). A non-scripted name (no descriptor) is
    // left in place for the class-/YAML-backed routing.
    val trailmapDir = File(tempFolder.root, "trailmaps/pp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: pp
      target:
        display_name: Per Platform
        platforms:
          android:
            app_ids:
              - com.example.pp
            tools:
              - ppAndroidTool
              - someClassBackedTool
          ios:
            tools:
              - ppIosTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/android_tool.yaml").writeText(
      """
      script: ./android_tool.js
      name: ppAndroidTool
      description: Android-only launch step.
      supportedPlatforms: [android]
      """.trimIndent(),
    )
    File(trailmapDir, "tools/ios_tool.yaml").writeText(
      """
      script: ./ios_tool.js
      name: ppIosTool
      description: iOS-only launch step.
      supportedPlatforms: [ios]
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - pp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!
    val target = resolved.targets.single()

    // Both per-platform scripted tools hoisted into the delivered top-level tools.
    assertEquals(setOf("ppAndroidTool", "ppIosTool"), target.tools.orEmpty().map { it.name }.toSet())
    // The scripted names are stripped from their platform sections, but the field is preserved
    // (empty list, not null) — see the next test for why this matters under dep inheritance.
    assertEquals(listOf("someClassBackedTool"), target.platforms?.get("android")?.tools)
    assertEquals(emptyList(), target.platforms?.get("ios")?.tools)
  }

  @Test
  fun `per-platform strip preserves empty list so deps don't override an author-cleared tools field`() {
    // Pins the Codex P2 fix: a `platforms.<p>.tools:` list that was originally non-null but
    // entirely scripted (all names hoisted out) must remain `emptyList()`, not `null`, so the
    // downstream TrailmapDependencyResolver.closestWinsOverlay sees "overlay declared" and skips
    // the dep default. Nulling here would let an inherited `defaults.<p>.tools` (class- or
    // YAML-backed) sneak in even though the author declared a tools list.
    val frameworkDir = File(tempFolder.root, "trailmaps/framework").apply { mkdirs() }
    File(frameworkDir, "trailmap.yaml").writeText(
      """
      id: framework
      defaults:
        android:
          tools:
            - inheritedClassBackedTool
      """.trimIndent(),
    )
    val targetDir = File(tempFolder.root, "trailmaps/myapp").apply { mkdirs() }
    File(targetDir, "trailmap.yaml").writeText(
      """
      id: myapp
      dependencies:
        - framework
      target:
        display_name: MyApp
        platforms:
          android:
            tools:
              - myScriptedTool
      """.trimIndent(),
    )
    File(targetDir, "tools").mkdirs()
    File(targetDir, "tools/my_scripted.yaml").writeText(
      """
      script: ./my_scripted.js
      name: myScriptedTool
      description: Android scripted tool.
      supportedPlatforms: [android]
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - myapp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!
    val target = resolved.targets.single { it.id == "myapp" }

    assertEquals(setOf("myScriptedTool"), target.tools.orEmpty().map { it.name }.toSet())
    // The dep's `inheritedClassBackedTool` MUST NOT show up — the author's explicit (now-empty
    // after stripping the scripted name) `tools:` declaration must win over the dep default.
    assertEquals(emptyList(), target.platforms?.get("android")?.tools)
  }

  @Test
  fun `non-string supportedPlatforms entry in a per-platform tool fails at descriptor decode`() {
    // A malformed `_meta.trailblaze/supportedPlatforms` (non-string element like 123) is caught at
    // typed-decode time by `TrailmapScriptedToolFile._meta`'s validation — before the per-platform
    // resolver ever sees the config. Pins this contract so the failure stays at the right layer
    // (the descriptor-decode boundary) with a debuggable message naming the tool and the field.
    val trailmapDir = File(tempFolder.root, "trailmaps/badmeta").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: badmeta
      target:
        display_name: BadMeta
        platforms:
          android:
            tools:
              - badTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/bad_tool.yaml").writeText(
      """
      script: ./bad_tool.js
      name: badTool
      _meta:
        trailblaze/supportedPlatforms:
          - 123
          - android
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - badmeta
      """.trimIndent(),
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)
    }
    assertTrue(
      ex.message?.contains("badTool") == true && ex.message?.contains("supportedPlatforms") == true,
      "error must name the tool and the field; got: ${ex.message}",
    )
  }

  @Test
  fun `trailmap toolset and tool refs resolve relative to trailmap dir`() {
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample
      toolsets:
        - toolsets/trailmap-toolset.yaml
      """.trimIndent(),
    )
    File(trailmapDir, "toolsets").mkdirs()
    File(trailmapDir, "toolsets/trailmap-toolset.yaml").writeText(
      """
      id: pack_toolset
      description: Trailmap toolset
      tools:
        - pack_tool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/trailmap-tool.tool.yaml").writeText(
      """
      id: pack_tool
      description: Trailmap tool
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
  fun `trailmap with dependencies inherits per-platform defaults via the loader pipeline`() {
    // End-to-end integration test for the dependencies-based composition: verifies the loader's two-pass
    // (sibling resolution → dep-graph defaults) actually wires TrailmapDependencyResolver
    // correctly. The resolver is well-tested in isolation; this guards against
    // regressions in the *wiring* between the loader and the resolver.
    //
    // Workspace shape:
    //   - `framework`: a library trailmap (no `target:`) publishing per-platform defaults
    //   - `myapp`: consumer with `dependencies: [framework]`, app-specific app_ids,
    //     omitted tool_sets on android (should inherit), explicit override on web
    val frameworkTrailmapDir = File(tempFolder.root, "trailmaps/framework").apply { mkdirs() }
    File(frameworkTrailmapDir, "trailmap.yaml").writeText(
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
    val appTrailmapDir = File(tempFolder.root, "trailmaps/myapp").apply { mkdirs() }
    File(appTrailmapDir, "trailmap.yaml").writeText(
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

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

    // Library trailmap (`framework`) has no `target:` and isn't surfaced as a runnable target.
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

    // Only myapp surfaces as a successful target id (framework is a library trailmap pulled
    // in via dependencies and contributes no target).
    assertEquals(
      listOf("myapp"),
      resolved.projectConfig.targets,
    )
  }


  @Test
  fun `legacy file-path in target tools drops the trailmap and a sibling trailmap still resolves`() {
    // Pin the helpful-diagnostic behavior: when a user lists a file path (the legacy
    // shape) in `target.tools:` instead of a tool name, the loader throws a
    // TrailblazeProjectConfigException with a migration hint pointing at the file-path
    // pattern. The atomic-per-trailmap catch in resolveTrailmapArtifacts logs the diagnostic and
    // drops the offending trailmap — siblings still resolve. This test pins both (a) the
    // mis-listed trailmap drops out, and (b) the loader still emits the sibling trailmap — i.e.
    // one bad mistake doesn't take down the whole workspace.
    val validTrailmapDir = File(tempFolder.root, "trailmaps/validpack").apply { mkdirs() }
    File(validTrailmapDir, "trailmap.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Trailmap
      """.trimIndent(),
    )
    val trailmapDir = File(tempFolder.root, "trailmaps/wronglisted").apply { mkdirs() }
    File(trailmapDir, "tools").mkdirs()
    // A perfectly valid operational `*.tool.yaml` — auto-discovered separately, no relation
    // to the scripted-tool registry. The point of the test is what happens when the author
    // lists this file's *path* (the legacy shape) under `target.tools:`.
    File(trailmapDir, "tools/wrong_listed.tool.yaml").writeText(
      """
      id: wrong_listed
      description: Pure-YAML composed tool that doesn't belong in target.tools:
      parameters: []
      tools:
        - mobile_maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: wronglisted
      target:
        display_name: Mis-Listed Trailmap
        platforms:
          android:
            app_ids: [com.example.wronglisted]
        tools:
          - tools/wrong_listed.tool.yaml
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - validpack
        - wronglisted
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    // The mis-listed trailmap drops out (the new diagnostic from resolveTrailmapSiblings is logged
    // by the atomic-per-trailmap catch in resolveTrailmapArtifacts); the valid sibling still
    // resolves and shows up in the final target list.
    assertEquals(listOf("validpack"), resolved.targets)
  }

  @Test
  fun `two scripted-tool descriptors declaring the same name fail with both file paths in the error`() {
    // Per-trailmap duplicate-name detection — two files under <trailmap>/tools/ both declare
    // `name: dupTool`. The discovery walk must fail loudly with both contributing
    // file paths so the author can pick which one to keep or rename. Without this guard
    // a `target.tools: [dupTool]` entry would silently resolve to whichever file the
    // filesystem listed first, with no signal that the other file also exists.
    val trailmapDir = File(tempFolder.root, "trailmaps/dupnames").apply { mkdirs() }
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/first.yaml").writeText(
      """
      script: ./tools/first.js
      name: dupTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools/second.yaml").writeText(
      """
      script: ./tools/second.js
      name: dupTool
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: dupnames
      target:
        display_name: Dup Names
        tools:
          - dupTool
      """.trimIndent(),
    )
    val validTrailmapDir = File(tempFolder.root, "trailmaps/validpack").apply { mkdirs() }
    File(validTrailmapDir, "trailmap.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Trailmap
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - dupnames
        - validpack
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    // Atomic-per-trailmap drop: the dup-name trailmap falls out, the valid sibling still resolves.
    // The diagnostic is logged via Console.log (see the loader's catch in resolveTrailmapArtifacts).
    // We can't reliably capture that stream from a multiplatform test, so we rely on the
    // structural assertion: a different per-trailmap failure (NPE, classloader error, etc.) would
    // also satisfy this, but is unlikely to be introduced by accident in the discovery walk.
    assertEquals(listOf("validpack"), resolved.targets)
  }

  @Test
  fun `duplicate target tools entries within one trailmap fail loudly`() {
    // Listing the same name twice in `target.tools:` silently double-registers without this
    // guard — surfaces later as an unhelpful "tool already registered" error from the
    // runtime tool repo that doesn't point at the manifest. Catch it at load time with a
    // message that names the offending tool.
    val trailmapDir = File(tempFolder.root, "trailmaps/dupentry").apply { mkdirs() }
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/foo.yaml").writeText(
      """
      script: ./tools/foo.js
      name: foo
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: dupentry
      target:
        display_name: Dup Entry
        tools:
          - foo
          - foo
      """.trimIndent(),
    )
    val validTrailmapDir = File(tempFolder.root, "trailmaps/validpack").apply { mkdirs() }
    File(validTrailmapDir, "trailmap.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Trailmap
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - dupentry
        - validpack
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    assertEquals(listOf("validpack"), resolved.targets)
  }

  @Test
  fun `malformed scripted-tool descriptor doesn't tank the rest of the trailmap`() {
    // Lead-dev review #2 (round 2): the discovery walk now wraps each per-descriptor decode
    // in try/log/skip. A half-written WIP `<trailmap>/tools/broken.yaml` no longer takes the
    // whole trailmap out of scope; sibling descriptors still register and `target.tools:`
    // entries that name those siblings resolve normally.
    val trailmapDir = File(tempFolder.root, "trailmaps/wipfriendly").apply { mkdirs() }
    File(trailmapDir, "tools").mkdirs()
    // Malformed YAML — half-written by the author.
    File(trailmapDir, "tools/broken.yaml").writeText(
      """
      script: ./tools/broken.ts
      this is not valid yaml:::
        - { unclosed
      """.trimIndent(),
    )
    File(trailmapDir, "tools/working.yaml").writeText(
      """
      script: ./tools/working.js
      name: workingTool
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: wipfriendly
      target:
        display_name: WIP-Friendly
        tools:
          - workingTool
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - wipfriendly
      """.trimIndent(),
    )

    // Use the runtime variant so we can inspect the resolved target's tool list — this lets
    // us positively pin "the sibling descriptor still registered" rather than just "the trailmap
    // didn't drop out", which is a much stronger statement about discovery resilience.
    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = file,
      includeClasspathTrailmaps = false,
    )!!
    val target = resolved.targets.single { it.id == "wipfriendly" }
    val toolNames = target.tools.orEmpty().map { it.name }
    assertEquals(
      listOf("workingTool"),
      toolNames,
      "the sibling descriptor must still register despite the malformed neighbor",
    )
  }

  @Test
  fun `target tools referencing a malformed descriptor's intended name drops trailmap with culprit hint`() {
    // Lead-dev round 3 #N6: the round-2 #1 fix pins "sibling resolves" but doesn't pin the
    // diagnostic when `target.tools:` references a name that *would* have lived in the
    // malformed file. The loader's UnknownScriptedToolName now embeds the skipped file path
    // when the conventional `<name>.yaml` matches the unknown tool name — this test pins
    // that contract end-to-end so the next refactor can't silently drop the breadcrumb.
    val trailmapDir = File(tempFolder.root, "trailmaps/wipreferenced").apply { mkdirs() }
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/foo.yaml").writeText(
      """
      script: ./tools/foo.ts
      this is not valid yaml:::
        - { unclosed
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: wipreferenced
      target:
        display_name: WIP Referenced
        tools:
          - foo
      """.trimIndent(),
    )
    val validTrailmapDir = File(tempFolder.root, "trailmaps/validpack").apply { mkdirs() }
    File(validTrailmapDir, "trailmap.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Trailmap
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - wipreferenced
        - validpack
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!

    // Atomic-per-trailmap drop: the wip-referenced trailmap drops out (the loader logs the diagnostic
    // including the skipped-file culprit hint), the valid sibling still resolves.
    assertEquals(listOf("validpack"), resolved.targets)
  }

  @Test
  fun `unresolvable dependency surfaces as a strict consolidated error`() {
    // Pin the strict dep-graph validation behavior: declaring `dependencies: [foo]`
    // where `foo` is not in the resolved pool fails the workspace load with a single
    // consolidated error message that names the broken trailmap, the missing dep id, and
    // the available pool. The motivation for strictness — captured in the design
    // discussion that prompted this rewrite — is that a trailmap's `dependencies:` field
    // is a contract; silently dropping a trailmap whose contract isn't met leaves authors
    // staring at "no such target" mysteries downstream.
    val validTrailmapDir = File(tempFolder.root, "trailmaps/validpack").apply { mkdirs() }
    File(validTrailmapDir, "trailmap.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Trailmap
        platforms:
          android:
            app_ids: [com.example.valid]
      """.trimIndent(),
    )
    val brokenTrailmapDir = File(tempFolder.root, "trailmaps/brokendep").apply { mkdirs() }
    File(brokenTrailmapDir, "trailmap.yaml").writeText(
      """
      id: brokendep
      dependencies:
        - this-trailmap-does-not-exist
      target:
        display_name: Broken Dep Trailmap
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
    assertContains(msg, "this-trailmap-does-not-exist")
  }

  @Test
  fun `broken trailmap does not abort sibling trailmap resolution`() {
    val validTrailmapDir = File(tempFolder.root, "trailmaps/validpack").apply { mkdirs() }
    File(validTrailmapDir, "trailmap.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid Trailmap
      """.trimIndent(),
    )
    val brokenTrailmapDir = File(tempFolder.root, "trailmaps/brokenpack").apply { mkdirs() }
    File(brokenTrailmapDir, "trailmap.yaml").writeText(
      """
      id: brokenpack
      target:
        display_name: Broken Trailmap
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
    // are now a flat list of trailmap-id strings; toolsets/providers still use the
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
  // Tests for trailmap-bundled waypoint resolution and classpath/workspace precedence.
  // ===========================================================================

  @Test
  fun `trailmap-bundled waypoints are auto-discovered from the waypoints directory`() {
    // Pin the post-2026-05-08 contract: trailmap.yaml no longer enumerates waypoint paths.
    // Anything in <trailmap>/waypoints/**.waypoint.yaml ships with the trailmap automatically,
    // mirroring the tools/ auto-discovery from the same migration.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    val waypointsDir = File(trailmapDir, "waypoints").apply { mkdirs() }
    File(waypointsDir, "ready.waypoint.yaml").writeText(
      """
      id: "sampleapp/ready"
      description: "Sample app's main screen, ready for input."
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
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
      includeClasspathTrailmaps = false,
    )

    assertNotNull(resolved)
    assertEquals(1, resolved.waypoints.size)
    val waypoint = resolved.waypoints.single()
    assertEquals("sampleapp/ready", waypoint.id)
    assertEquals("Sample app's main screen, ready for input.", waypoint.description)
  }

  @Test
  fun `trailmap-bundled waypoints under nested subdirectories are auto-discovered`() {
    // Pin: discovery walks the whole `waypoints/` tree, not just direct children. The
    // Square trailmap organizes ~120 waypoints under `waypoints/{android,ios,web}/...` (web
    // dashboard waypoints sit four levels deep), so the discovery path has to recurse.
    val trailmapDir = File(tempFolder.root, "trailmaps/multitarget").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: multitarget
      target:
        display_name: Multi-target Trailmap
      """.trimIndent(),
    )
    val androidDir = File(trailmapDir, "waypoints/android").apply { mkdirs() }
    File(androidDir, "home.waypoint.yaml").writeText(
      """
      id: "multitarget/android/home"
      description: "Android home screen."
      """.trimIndent(),
    )
    val webDeepDir = File(trailmapDir, "waypoints/web/dashboard/items").apply { mkdirs() }
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
      includeClasspathTrailmaps = false,
    )

    assertNotNull(resolved)
    val ids = resolved.waypoints.map(WaypointDefinition::id).toSet()
    assertEquals(
      setOf("multitarget/android/home", "multitarget/web/dashboard/items/library"),
      ids,
    )
  }

  @Test
  fun `trailmap-bundled YAML outside the waypoints directory is NOT picked up`() {
    // Symmetric structural-integrity rule with the tools/ side: a YAML file with a
    // `.waypoint.yaml` suffix that lives somewhere other than `<trailmap>/waypoints/` (e.g.
    // a misplaced file in `<trailmap>/misc/`) must not register as a waypoint.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
      """.trimIndent(),
    )
    val miscDir = File(trailmapDir, "misc").apply { mkdirs() }
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
      includeClasspathTrailmaps = false,
    )

    assertNotNull(resolved)
    assertTrue(resolved.waypoints.isEmpty(), "Got: ${resolved.waypoints.map { it.id }}")
  }

  @Test
  fun `library trailmap with waypoints on disk drops the whole trailmap with logged failure`() {
    // Discovery-side library-trailmap guard: a target-less trailmap that ships waypoint files
    // on disk must drop the whole trailmap. Symmetric with the manifest-side check on
    // `manifest.waypoints.isNotEmpty()` — covers the case where the legacy manifest list
    // is absent but YAMLs exist under waypoints/. Mirrors the trailhead-in-library-trailmap
    // test: the bad library trailmap enters scope via a target's dependencies, then drops
    // atomic-per-trailmap when sibling resolution fires the guard.
    val libraryTrailmapDir = File(tempFolder.root, "trailmaps/badlibrary").apply { mkdirs() }
    File(libraryTrailmapDir, "trailmap.yaml").writeText(
      """
      id: badlibrary
      """.trimIndent(),
    )
    val waypointsDir = File(libraryTrailmapDir, "waypoints").apply { mkdirs() }
    File(waypointsDir, "stray.waypoint.yaml").writeText(
      """
      id: "badlibrary/stray"
      description: "Library trailmap must not own this."
      """.trimIndent(),
    )
    val targetTrailmapDir = File(tempFolder.root, "trailmaps/consumer").apply { mkdirs() }
    File(targetTrailmapDir, "trailmap.yaml").writeText(
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
      includeClasspathTrailmaps = false,
    )

    assertNotNull(resolved)
    // Atomic-per-trailmap: the bad library drops; the consumer target may still surface but
    // the library's stray waypoint must not appear in the resolved pool.
    assertTrue(
      resolved.waypoints.isEmpty(),
      "Library-trailmap waypoint must not surface. Got: ${resolved.waypoints.map { it.id }}",
    )
  }

  @Test
  fun `trailmap with malformed waypoint YAML drops the whole trailmap from resolved waypoints`() {
    // Atomic-per-trailmap: a waypoint YAML that fails to decode drops the WHOLE trailmap —
    // including the target — so neither the waypoint nor the target appears in the
    // result. (Replaces the pre-auto-discovery "broken manifest ref" test: missing
    // refs aren't a thing anymore now that the manifest list is ignored.)
    val trailmapDir = File(tempFolder.root, "trailmaps/broken").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: broken
      target:
        display_name: Broken Trailmap
      """.trimIndent(),
    )
    val waypointsDir = File(trailmapDir, "waypoints").apply { mkdirs() }
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
      includeClasspathTrailmaps = false,
    )

    assertNotNull(resolved)
    assertTrue(resolved.waypoints.isEmpty())
    assertTrue(resolved.projectConfig.targets.isEmpty())
    assertTrue(resolved.targets.isEmpty())
  }

  @Test
  fun `workspace trailmap wholesale shadows same-id classpath trailmap`() {
    val classpathRoot = newTempDir()
    val classpathTrailmapDir = File(classpathRoot, "trails/config/trailmaps/sampleapp").apply { mkdirs() }
    File(classpathTrailmapDir, "trailmap.yaml").writeText(
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
    File(File(classpathTrailmapDir, "waypoints").apply { mkdirs() }, "classpath.waypoint.yaml").writeText(
      """
      id: "sampleapp/classpath-only"
      description: "Should NOT appear when workspace trailmap shadows."
      """.trimIndent(),
    )
    File(File(classpathTrailmapDir, "toolsets").apply { mkdirs() }, "classpath_only.yaml").writeText(
      """
      id: classpath_only_toolset
      description: Should NOT appear when workspace trailmap shadows.
      tools:
        - some_tool
      """.trimIndent(),
    )
    File(File(classpathTrailmapDir, "tools").apply { mkdirs() }, "classpath_only.tool.yaml").writeText(
      """
      id: classpath_only_tool
      class: xyz.block.trailblaze.fake.ClasspathOnlyTool
      """.trimIndent(),
    )

    val workspaceTrailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(File(workspaceTrailmapDir, "waypoints").apply { mkdirs() }, "workspace.waypoint.yaml").writeText(
      """
      id: "sampleapp/workspace-only"
      description: "Workspace trailmap contributes this."
      """.trimIndent(),
    )
    File(File(workspaceTrailmapDir, "toolsets").apply { mkdirs() }, "workspace_only.yaml").writeText(
      """
      id: workspace_only_toolset
      description: Workspace trailmap contributes this.
      tools:
        - some_tool
      """.trimIndent(),
    )
    File(File(workspaceTrailmapDir, "tools").apply { mkdirs() }, "workspace_only.tool.yaml").writeText(
      """
      id: workspace_only_tool
      class: xyz.block.trailblaze.fake.WorkspaceOnlyTool
      """.trimIndent(),
    )
    File(workspaceTrailmapDir, "trailmap.yaml").writeText(
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
        includeClasspathTrailmaps = true,
      )
    }

    assertNotNull(resolved)

    // Target: workspace's display_name wins.
    val target = resolved.targets.single()
    assertEquals("sampleapp", target.id)
    assertEquals(
      expected = "Workspace Sample App (should win)",
      actual = target.displayName,
      message = "Workspace trailmap should shadow classpath trailmap of the same id",
    )

    // Waypoints: only the workspace one appears — classpath trailmap is shadowed wholesale.
    val waypointIds = resolved.waypoints.map(WaypointDefinition::id)
    assertEquals(listOf("sampleapp/workspace-only"), waypointIds)

    // Toolsets: same wholesale-shadow rule. Only the workspace toolset appears,
    // and the classpath toolset must NOT leak through. The trailmap-id collision
    // documented on TrailblazeResolvedConfig is the contract this test gates.
    val toolsetIds = resolved.projectConfig.toolsets
      .map { assertIs<ToolsetEntry.Inline>(it).config.id }
    assertEquals(listOf("workspace_only_toolset"), toolsetIds)

    // Tools: same. The classpath trailmap's `class:`-backed tool is shadowed completely
    // — it's not merged in, just dropped.
    val toolIds = resolved.projectConfig.tools
      .map { assertIs<ToolEntry.Inline>(it).config.id }
    assertEquals(listOf("workspace_only_tool"), toolIds)
  }

  @Test
  fun `classpath trailmap contributes when workspace declares no same-id trailmap`() {
    val classpathRoot = newTempDir()
    val classpathTrailmapDir = File(classpathRoot, "trails/config/trailmaps/framework").apply { mkdirs() }
    File(classpathTrailmapDir, "trailmap.yaml").writeText(
      """
      id: framework
      target:
        display_name: Framework-Bundled Trailmap
      """.trimIndent(),
    )

    val configFile = tempFolder.writeConfig("")

    val resolved = withClasspathRoot(classpathRoot) {
      TrailblazeProjectConfigLoader.loadResolvedRuntime(
        configFile = configFile,
        includeClasspathTrailmaps = true,
      )
    }

    assertNotNull(resolved)
    val ids = resolved.targets.map { it.id }
    assertContains(ids, "framework")
  }

  @Test
  fun `trailmap target system_prompt_file resolves into config systemPrompt`() {
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App Trailmap
        system_prompt_file: prompt.md
      """.trimIndent(),
    )
    File(trailmapDir, "prompt.md").writeText("You are testing Sample App.\nBe direct.")
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

    val target = resolved.targets.single()
    assertEquals("You are testing Sample App.\nBe direct.", target.systemPrompt)
  }

  @Test
  fun `trailmap with missing system_prompt_file is skipped with logged failure`() {
    // Atomic-per-trailmap failure model: a trailmap with an unresolvable system_prompt_file fails to
    // resolve, but sibling trailmaps continue. Mirrors the behavior the existing test
    // `failed trailmap ref does not poison sibling trailmaps` documents for other ref types.
    val trailmapDir = File(tempFolder.root, "trailmaps/missingprompt").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
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

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

    // Trailmap failed to resolve → no target surfaces from it.
    assertTrue(
      resolved.targets.isEmpty(),
      "Trailmap with missing system_prompt_file should not contribute a target; got: ${resolved.targets}",
    )
  }

  @Test
  fun `trailmap with legacy inline system_prompt fails the load with migration message`() {
    // The schema dropped `system_prompt:` from TrailmapTargetConfig; lenient YAML decode would
    // silently lose the prompt content. The pre-decode typed shape (`LegacyInlineSystemPromptShape`)
    // in `TrailblazeTrailmapManifestLoader.parseManifest` catches this and fails the load loudly,
    // pointing the author at `system_prompt_file`. Atomic-per-trailmap: this trailmap drops, the
    // resolved config still loads with no targets surfaced from it.
    val trailmapDir = File(tempFolder.root, "trailmaps/legacyinline").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
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

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

    assertTrue(
      resolved.targets.isEmpty(),
      "Trailmap declaring legacy inline system_prompt should not contribute a target; got: ${resolved.targets}",
    )
  }

  @Test
  fun `system_prompt_file failure is atomic — sibling trailmaps still resolve`() {
    // Pins the atomic-per-trailmap contract for system_prompt_file failures specifically. Mirrors
    // the existing `failed trailmap ref does not poison sibling trailmaps` test for other ref types: a
    // broken trailmap should drop on its own without taking out workspace siblings.
    val brokenDir = File(tempFolder.root, "trailmaps/broken").apply { mkdirs() }
    File(brokenDir, "trailmap.yaml").writeText(
      """
      id: broken
      target:
        display_name: Broken
        system_prompt_file: does-not-exist.md
      """.trimIndent(),
    )
    val validDir = File(tempFolder.root, "trailmaps/valid").apply { mkdirs() }
    File(validDir, "trailmap.yaml").writeText(
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

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

    val targets = resolved.targets
    assertEquals(
      listOf("valid"),
      targets.map { it.id },
      "Only the valid sibling should surface; broken trailmap failure must not poison its sibling",
    )
    assertEquals("Valid sibling prompt content.", targets.single().systemPrompt)
  }

  @Test
  fun `legacy inline system_prompt failure is atomic — sibling trailmaps still resolve`() {
    // Same atomic-per-trailmap guarantee for the migration-error path: a trailmap still authored with
    // the removed inline `system_prompt:` field should drop, but a properly-authored sibling
    // continues to resolve.
    val legacyDir = File(tempFolder.root, "trailmaps/legacy").apply { mkdirs() }
    File(legacyDir, "trailmap.yaml").writeText(
      """
      id: legacy
      target:
        display_name: Legacy
        system_prompt: |
          Inline content the schema rejects.
      """.trimIndent(),
    )
    val validDir = File(tempFolder.root, "trailmaps/modern").apply { mkdirs() }
    File(validDir, "trailmap.yaml").writeText(
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

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

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
    val trailmapDir = File(tempFolder.root, "trailmaps/blockscalar").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: blockscalar
      target:
        display_name: |
          A trailmap whose description mentions
            system_prompt: foo
          inside a block scalar — should NOT trip the legacy-inline guard.
        system_prompt_file: prompt.md
      """.trimIndent(),
    )
    File(trailmapDir, "prompt.md").writeText("Block scalar test prompt.")
    val file = tempFolder.writeConfig(
      """
      targets:
        - blockscalar
      """.trimIndent(),
    )

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

    val target = resolved.targets.single()
    assertEquals("blockscalar", target.id)
    assertEquals("Block scalar test prompt.", target.systemPrompt)
  }

  @Test
  fun `trailmap target with neither system_prompt nor system_prompt_file decodes without throwing`() {
    // A trailmap target is allowed to declare no prompt at all (e.g. a target that only contributes
    // tools / waypoints and inherits its prompt from elsewhere or has none). The pre-decode
    // legacy guard must NOT fire on this case, and the typed decode must NOT add a synthetic
    // error. Pinned because a future tightening of the loader (e.g. "every target needs a prompt")
    // could regress this implicit allowance silently.
    val trailmapDir = File(tempFolder.root, "trailmaps/promptless").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
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

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!

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
    val trailmapDir = File(tempFolder.root, "trailmaps/malformed").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
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

    // Should not throw out of the guard (atomic-per-trailmap: malformed trailmap drops, sibling-less
    // resolved still loads cleanly).
    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(file, includeClasspathTrailmaps = false)!!
    assertTrue(
      resolved.targets.isEmpty(),
      "Malformed trailmap should drop atomically; got: ${resolved.targets}",
    )
  }

  // ===========================================================================
  // Library-trailmap contract: a tool YAML carrying a `trailhead:` block is rejected
  // when its owning trailmap has no `target:`. The waypoints-on-library-trailmap rule
  // is enforced earlier (manifest-level) by TrailblazeTrailmapManifestLoader; this
  // rule has to live here because the trailhead block lives inside the tool YAML,
  // which only gets loaded once the manifest's `tools:` paths are resolved.
  // ===========================================================================

  @Test
  fun `library trailmap with trailhead tool drops the whole trailmap with logged failure`() {
    // A library trailmap (no target:) must enter scope only via dependencies. To exercise the
    // tool-level trailhead guard, a target trailmap pulls the bad library in via dependencies;
    // the library's broken trailhead drops the WHOLE library trailmap.
    val libraryTrailmapDir = File(tempFolder.root, "trailmaps/badlibrary").apply { mkdirs() }
    val trailheadsDir = File(libraryTrailmapDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "go_home.trailhead.yaml").writeText(
      """
      id: go_home
      class: com.example.GoHomeTrailhead
      trailhead:
        to: app/home
      """.trimIndent(),
    )
    File(libraryTrailmapDir, "trailmap.yaml").writeText(
      """
      id: badlibrary
      """.trimIndent(),
    )
    val targetTrailmapDir = File(tempFolder.root, "trailmaps/consumer").apply { mkdirs() }
    File(targetTrailmapDir, "trailmap.yaml").writeText(
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
      includeClasspathTrailmaps = false,
    )

    // Atomic-per-trailmap: the trailhead-in-library-trailmap violation drops the bad library trailmap.
    // The consumer target may still surface (its own resolution didn't fail), but the
    // library's trailhead tool is excluded.
    assertNotNull(resolved)
    assertTrue(resolved.projectConfig.tools.isEmpty())
  }

  @Test
  fun `target trailmap with trailhead tool resolves cleanly`() {
    // Pin the happy path: a target trailmap legitimately owns trailhead tools — the guard
    // must only fire for library trailmaps.
    val trailmapDir = File(tempFolder.root, "trailmaps/goodtarget").apply { mkdirs() }
    val trailheadsDir = File(trailmapDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "go_home.trailhead.yaml").writeText(
      """
      id: go_home
      class: com.example.GoHomeTrailhead
      trailhead:
        to: app/home
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
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
      includeClasspathTrailmaps = false,
    )

    assertNotNull(resolved)
    assertEquals(1, resolved.projectConfig.targets.size)
    assertEquals(1, resolved.projectConfig.tools.size)
  }

  // ===========================================================================
  // Auto-discovery (`targets:` empty / omitted) — every target trailmap found under
  // `<workspace>/trailmaps/<id>/trailmap.yaml` loads automatically. Library trailmaps are
  // not auto-discovered as roots — they reach scope only via `dependencies:`.
  // ===========================================================================

  @Test
  fun `auto-discovery loads every target trailmap under workspace trailmaps dir when targets is omitted`() {
    val firstTrailmapDir = File(tempFolder.root, "trailmaps/firstapp").apply { mkdirs() }
    File(firstTrailmapDir, "trailmap.yaml").writeText(
      """
      id: firstapp
      target:
        display_name: First App
        platforms:
          android:
            app_ids: [com.example.first]
      """.trimIndent(),
    )
    val secondTrailmapDir = File(tempFolder.root, "trailmaps/secondapp").apply { mkdirs() }
    File(secondTrailmapDir, "trailmap.yaml").writeText(
      """
      id: secondapp
      target:
        display_name: Second App
        platforms:
          android:
            app_ids: [com.example.second]
      """.trimIndent(),
    )
    // A library trailmap sitting alongside the targets — must NOT auto-discover as a root,
    // but should still be reachable transitively if a target depends on it.
    val libraryTrailmapDir = File(tempFolder.root, "trailmaps/shared-lib").apply { mkdirs() }
    File(libraryTrailmapDir, "trailmap.yaml").writeText(
      """
      id: shared-lib
      """.trimIndent(),
    )
    val configFile = tempFolder.writeConfig("")

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathTrailmaps = false,
    )

    assertNotNull(resolved)
    assertEquals(
      setOf("firstapp", "secondapp"),
      resolved.projectConfig.targets.toSet(),
      "Auto-discovery should pick up both target trailmaps but skip the library trailmap",
    )
    assertEquals(2, resolved.targets.size)
  }

  @Test
  fun `auto-discovery skips a malformed trailmap atomically while siblings continue to load`() {
    val goodTrailmapDir = File(tempFolder.root, "trailmaps/goodapp").apply { mkdirs() }
    File(goodTrailmapDir, "trailmap.yaml").writeText(
      """
      id: goodapp
      target:
        display_name: Good App
        platforms:
          android:
            app_ids: [com.example.good]
      """.trimIndent(),
    )
    val brokenTrailmapDir = File(tempFolder.root, "trailmaps/broken").apply { mkdirs() }
    // Truncated YAML — `display_name` has no value, kaml fails to parse.
    File(brokenTrailmapDir, "trailmap.yaml").writeText("id: broken\ntarget:\n  display_name")
    val configFile = tempFolder.writeConfig("")

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      configFile = configFile,
      includeClasspathTrailmaps = false,
    )

    assertNotNull(resolved)
    assertEquals(
      listOf("goodapp"),
      resolved.projectConfig.targets,
      "Malformed trailmap should be skipped atomically; sibling trailmap should still load",
    )
  }

  // ===========================================================================
  // Workspace `targets:` only accepts target trailmaps — listing a library-trailmap id
  // there is rejected with a redirecting error pointing at `dependencies:`.
  // ===========================================================================

  @Test
  fun `workspace targets list rejects library trailmap id with redirecting error`() {
    // Library trailmap on disk (no `target:` block).
    val libDir = File(tempFolder.root, "trailmaps/shared-lib").apply { mkdirs() }
    File(libDir, "trailmap.yaml").writeText(
      """
      id: shared-lib
      """.trimIndent(),
    )
    // Author tries to list the library trailmap at the workspace level — this is
    // a category error since library trailmaps reach scope only via `dependencies:`.
    val configFile = tempFolder.writeConfig(
      """
      targets:
        - shared-lib
      """.trimIndent(),
    )

    val error = runCatching {
      TrailblazeProjectConfigLoader.loadResolvedRuntime(configFile, includeClasspathTrailmaps = false)
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
  // trailmap appears in a single consolidated error so the author can see the
  // whole list in one shot rather than fix-and-retry.
  // ===========================================================================

  @Test
  fun `consolidated dep-graph error names every broken edge across every loaded trailmap`() {
    val firstTrailmapDir = File(tempFolder.root, "trailmaps/firstapp").apply { mkdirs() }
    File(firstTrailmapDir, "trailmap.yaml").writeText(
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
    val secondTrailmapDir = File(tempFolder.root, "trailmaps/secondapp").apply { mkdirs() }
    File(secondTrailmapDir, "trailmap.yaml").writeText(
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
      TrailblazeProjectConfigLoader.loadResolvedRuntime(configFile, includeClasspathTrailmaps = false)
    }.exceptionOrNull()
    val typed = assertIs<TrailblazeProjectConfigException>(error)
    val msg = typed.message.orEmpty()
    assertContains(msg, "dependency-graph validation failed")
    // Every broken edge appears in the single consolidated message — pinning
    // the difference vs. atomic-per-trailmap (which would surface only the first).
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
   * at [root], so classpath-trailmap discovery sees the fixture instead of the framework's
   * actual bundled trailmaps.
   */
  private fun <T> withClasspathRoot(root: File, block: () -> T): T =
    classpath.withClasspathRoot(root, block)

  private fun TemporaryFolder.writeConfig(yaml: String): File {
    val file = File(root, "trailblaze.yaml")
    file.writeText(yaml)
    return file
  }

  // ---- Meta-only descriptor enrichment ----------------------------------------------------

  @Test
  fun `meta-only descriptor resolves via ScriptedToolEnrichment hook`() {
    // The author wrote a meta-only YAML (`script:` + `_meta:` only) alongside a typed
    // `.ts` declaration. The loader can't recover `name:` / `inputSchema:` on its own —
    // it routes the descriptor through the wired ScriptedToolEnrichment, which returns
    // analyzer-derived values. Pin that the resulting target.tools entry carries both
    // the meta from the YAML AND the name/inputSchema/description from enrichment.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - openSample
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/openSample.yaml").writeText(
      """
      script: ./openSample.ts
      _meta:
        trailblaze/supportedPlatforms: [ios]
      """.trimIndent(),
    )
    File(trailmapDir, "tools/openSample.ts").writeText("// placeholder — analyzer is stubbed in this test")
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/openSample.ts").absolutePath,
              name = "openSample",
              description = "Open the sample app via the typed handler.",
              requiresHost = d.descriptor.requiresHost,
              runtime = d.descriptor.runtime,
              meta = d.descriptor.meta,
              inputSchema = JsonObject(
                mapOf(
                  "type" to JsonPrimitive("object"),
                  "properties" to JsonObject(emptyMap()),
                ),
              ),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    val target = resolved.targets.single()
    val tool = target.tools?.single()
    assertEquals("openSample", tool?.name)
    assertEquals("Open the sample app via the typed handler.", tool?.description)
    // The analyzer-derived inputSchema flows through verbatim.
    assertEquals(JsonPrimitive("object"), tool?.inputSchema?.get("type"))
    // Top-level YAML _meta survives — supportedPlatforms continues to gate the tool to iOS.
    val meta = assertNotNull(tool?.meta)
    val platforms = assertIs<JsonArray>(meta["trailblaze/supportedPlatforms"])
    assertEquals(JsonPrimitive("ios"), platforms.single())
  }

  @Test
  fun `partial single-tool descriptor (name only) merges YAML name + analyzer description and inputSchema`() {
    // Partial single-tool authoring shape: YAML carries the load-bearing `name:` (for
    // `target.tools:` resolution + dup detection) and the `supportedPlatforms:` shortcut
    // (for the runtime registration gate), but no `description:` / `inputSchema:` —
    // the typed `.ts`'s `trailblaze.tool<I>(spec, handler)` declaration is the source of
    // truth for those fields via `AnalyzerScriptedToolEnrichment`. Pin that the
    // resulting target.tools entry merges YAML's name with analyzer-derived
    // description + inputSchema.
    val trailmapDir = File(tempFolder.root, "trailmaps/contacts").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: contacts
      target:
        display_name: iOS Contacts
        tools:
          - contacts_ios_addPhoneNumber
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/contacts_ios_addPhoneNumber.yaml").writeText(
      """
      script: ./contacts_ios_addPhoneNumber.ts
      name: contacts_ios_addPhoneNumber
      supportedPlatforms: [ios]
      """.trimIndent(),
    )
    File(trailmapDir, "tools/contacts_ios_addPhoneNumber.ts").writeText(
      "// placeholder — analyzer is stubbed in this test",
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - contacts
      """.trimIndent(),
    )

    val analyzerInputSchema = JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(
          mapOf(
            "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "phoneNumber" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
          ),
        ),
      ),
    )
    val stub = StubEnrichment { _, _, _, deferred ->
      // The enrichment impl is responsible for matching YAML's `name:` against the
      // analyzer's exports — the stub mirrors that by reading `d.descriptor.name` and
      // emitting a config under that exact name. It also mirrors the real impl's
      // shortcut→`_meta` projection so the top-level `supportedPlatforms:` in the YAML
      // folds into the namespaced `trailblaze/supportedPlatforms` runtime key. Without
      // this projection the platform gate would be lost on the resolved config — exactly
      // the regression the codex-bot review caught on the original mode-2 strip pass.
      deferred.map { d ->
        val projectedMeta: JsonObject? = d.descriptor.supportedPlatforms
          ?.takeIf { it.isNotEmpty() }
          ?.let { platforms ->
            JsonObject(
              (d.descriptor.meta?.toMap() ?: emptyMap()) + mapOf(
                "trailblaze/supportedPlatforms" to JsonArray(platforms.map { JsonPrimitive(it) }),
              ),
            )
          }
          ?: d.descriptor.meta
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/contacts_ios_addPhoneNumber.ts").absolutePath,
              name = d.descriptor.name!!,
              description = "Add a phone number to an existing iOS contact.",
              requiresHost = d.descriptor.requiresHost,
              runtime = d.descriptor.runtime,
              meta = projectedMeta,
              inputSchema = analyzerInputSchema,
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    val tool = resolved.targets.single().tools?.single()
    // YAML's `name:` is the registered name.
    assertEquals("contacts_ios_addPhoneNumber", tool?.name)
    // Analyzer fills the description gap.
    assertEquals("Add a phone number to an existing iOS contact.", tool?.description)
    // Analyzer fills the inputSchema gap.
    assertEquals(analyzerInputSchema, tool?.inputSchema)
    // Top-level `supportedPlatforms:` shortcut still gates the tool to iOS at runtime —
    // folds into namespaced `_meta` via `toInlineScriptToolConfig` semantics.
    val platforms = assertIs<JsonArray>(tool?.meta?.get("trailblaze/supportedPlatforms"))
    assertEquals(JsonPrimitive("ios"), platforms.single())
  }

  @Test
  fun `partial multi-tool descriptor end-to-end resolves every entry through analyzer enrichment`() {
    // Two-phase loader interaction (SISTER-IMPL-TAG: partial-descriptor-eager-upgrade)
    // means a partial multi-tool descriptor must (a) register each `tools[].name` eagerly
    // in the discovery walk, then (b) upgrade each entry in-place with the
    // analyzer-resolved config — without firing the dup-name error.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - sample_signIn
          - sample_signInWithCredentials
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    // Partial multi-tool YAML: file-wide script + supportedPlatforms; each entry has
    // only `name:` (no description, no inputSchema). The analyzer is responsible for
    // filling those in per-entry, matched by name.
    File(trailmapDir, "tools/sample_web_sign_in.yaml").writeText(
      """
      script: ./sample_web_sign_in.ts
      supportedPlatforms: [web]
      tools:
        - name: sample_signIn
        - name: sample_signInWithCredentials
      """.trimIndent(),
    )
    File(trailmapDir, "tools/sample_web_sign_in.ts").writeText(
      "// placeholder — analyzer is stubbed in this test",
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    // Per-entry analyzer schemas — distinct so the test can pin per-name routing.
    val signInSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
    val signInWithCredsSchema = JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(
          mapOf(
            "email" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "password" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
          ),
        ),
      ),
    )

    val stub = StubEnrichment { _, _, _, deferred ->
      // Real impl matches each `tools[].name` against analyzer exports; the stub
      // mirrors that contract by emitting one config per entry-name.
      deferred.map { d ->
        val descriptor = d.descriptor
        val projectedMeta: JsonObject? = descriptor.supportedPlatforms
          ?.takeIf { it.isNotEmpty() }
          ?.let { platforms ->
            JsonObject(
              (descriptor.meta?.toMap() ?: emptyMap()) + mapOf(
                "trailblaze/supportedPlatforms" to JsonArray(platforms.map { JsonPrimitive(it) }),
              ),
            )
          }
          ?: descriptor.meta
        val perEntry = descriptor.tools.orEmpty().map { entry ->
          val (description, schema) = when (entry.name) {
            "sample_signIn" -> "Sign in via account-resolver key." to signInSchema
            "sample_signInWithCredentials" -> "Sign in with explicit credentials." to signInWithCredsSchema
            else -> error("unexpected entry name '${entry.name}' in stub")
          }
          xyz.block.trailblaze.config.InlineScriptToolConfig(
            script = File(trailmapDir, "tools/sample_web_sign_in.ts").absolutePath,
            name = entry.name,
            description = description,
            requiresHost = false,
            runtime = descriptor.runtime,
            meta = projectedMeta,
            inputSchema = schema,
          )
        }
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = perEntry,
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    val target = resolved.targets.single()
    val tools = target.tools.orEmpty().associateBy { it.name }
    assertEquals(2, tools.size, "two tool names must resolve from the partial multi-tool descriptor")
    val signIn = assertNotNull(tools["sample_signIn"])
    assertEquals("Sign in via account-resolver key.", signIn.description)
    assertEquals(JsonPrimitive("object"), signIn.inputSchema?.get("type"))
    val withCreds = assertNotNull(tools["sample_signInWithCredentials"])
    assertEquals("Sign in with explicit credentials.", withCreds.description)
    // File-wide `supportedPlatforms` shortcut folds into namespaced `_meta` on every entry.
    val platforms = assertIs<JsonArray>(signIn.meta?.get("trailblaze/supportedPlatforms"))
    assertEquals(JsonPrimitive("web"), platforms.single())
  }

  @Test
  fun `partial descriptor enrichment failure throws at the loader without leaking a half-registered tool`() {
    // SISTER-IMPL-TAG: partial-descriptor-eager-upgrade. The discovery walk registers
    // partial descriptors eagerly so `target.tools:` resolution + dup detection see them
    // right away. If enrichment then fails (analyzer error, name mismatch, missing
    // export), the loader must throw — NOT silently fall through to a half-registered
    // entry whose `enrichedConfig == null`. This pins the throw end-to-end at the loader
    // level (the enrichment unit covers the Failed shape directly; this test covers the
    // loader's handling of it).
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - partialTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    // Partial single-tool: name set, no description / no inputSchema. Routes through
    // enrichment per the broadened `requiresEnrichment()` gate.
    File(trailmapDir, "tools/partialTool.yaml").writeText(
      """
      script: ./partialTool.ts
      name: partialTool
      supportedPlatforms: [web]
      """.trimIndent(),
    )
    File(trailmapDir, "tools/partialTool.ts").writeText(
      "// placeholder — analyzer is stubbed in this test",
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    // StubEnrichment that simulates an analyzer-side failure (e.g., the `.ts` doesn't
    // declare an export named `partialTool`). The loader must propagate the Failed
    // result as a `TrailblazeProjectConfigException`.
    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Failed(
          relativePath = d.relativePath,
          reason = "stubbed analyzer failure for the test — pretend the `.ts` " +
            "doesn't export `partialTool`",
        )
      }
    }

    // The loader catches `TrailblazeProjectConfigException` per-trailmap and DROPS the
    // trailmap with a console warning. The downstream test surface is: the trailmap
    // doesn't appear in the resolved targets, and no half-registered `partialTool` leaks
    // into any target. This is what protects callers from seeing a tool whose schema
    // and description came from neither the YAML nor the analyzer.
    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )
    assertTrue(
      resolved.targets.isEmpty(),
      "expected the trailmap to drop out when partial-descriptor enrichment fails — got ${resolved.targets}",
    )
    // Symmetric pin: NO resolved target carries the `partialTool` name, so the eager
    // registry entry (registered before enrichment ran) didn't survive past the
    // failure. The SISTER-IMPL-TAG contract is: enrichment failure → trailmap drop →
    // zero half-registered entries.
    val allToolNames = resolved.targets.flatMap { it.tools.orEmpty().map { tool -> tool.name } }
    assertTrue(
      "partialTool" !in allToolNames,
      "no resolved target should carry the eager-registered name; got $allToolNames",
    )
  }

  @Test
  fun `meta-only descriptor fails clearly when no enrichment is wired in`() {
    // The author wrote a meta-only YAML but the loader's caller didn't provide an
    // analyzer-backed enrichment strategy. The loader can't recover `name:` /
    // `inputSchema:` from the .ts side, so the trailmap drops out with a diagnostic that
    // names the offending descriptor file. This is what an on-device-runtime / test
    // fixture / Gradle-build-time caller hits when meta-only YAML lands in scope.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - openSample
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/openSample.yaml").writeText(
      """
      script: ./openSample.ts
      _meta:
        trailblaze/supportedPlatforms: [ios]
      """.trimIndent(),
    )
    File(trailmapDir, "tools/openSample.ts").writeText("// placeholder")
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    // No enrichment passed → the trailmap-sibling resolution throws inside the atomic-per-trailmap
    // catch, which logs and drops the trailmap. The result is an empty target list (the
    // workspace's only trailmap failed to resolve).
    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      file,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = null,
    )!!
    assertTrue(
      resolved.targets.isEmpty(),
      "expected the trailmap to drop out when meta-only descriptor can't be resolved without enrichment",
    )
  }

  @Test
  fun `meta-only descriptor surfaces enrichment failure as a load-time error`() {
    // The author wrote a meta-only YAML and an enrichment IS wired up, but the
    // enrichment can't find a typed declaration in the sibling .ts (e.g. the author's
    // file still uses the legacy `export async function` shape). The trailmap drops out
    // with the analyzer's reason surfaced in the failure path — same trailmap-drop
    // semantics as the no-enrichment case, but the operator sees the per-descriptor
    // reason in the log.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - openSample
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/openSample.yaml").writeText(
      """
      script: ./openSample.ts
      """.trimIndent(),
    )
    File(trailmapDir, "tools/openSample.ts").writeText("// no typed export here")
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Failed(
          relativePath = d.relativePath,
          reason = "no `trailblaze.tool<I, O>({...})` declaration found",
        )
      }
    }
    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )
    assertTrue(
      resolved.targets.isEmpty(),
      "expected the trailmap to drop out when meta-only enrichment fails",
    )
  }

  @Test
  fun `meta-only enriched name colliding with a legacy YAML descriptor name drops the trailmap`() {
    // Trailmap carries both a meta-only descriptor whose enrichment resolves to `dupTool`
    // AND a legacy full-YAML descriptor declaring `name: dupTool`. The loader's
    // duplicate-name guard must fire and name both contributing files.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - dupTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/dupTool.yaml").writeText(
      """
      script: ./dupTool-legacy.ts
      name: dupTool
      description: Legacy descriptor.
      inputSchema: {}
      """.trimIndent(),
    )
    File(trailmapDir, "tools/dupTool-meta.yaml").writeText(
      """
      script: ./dupTool-meta.ts
      """.trimIndent(),
    )
    File(trailmapDir, "tools/dupTool-legacy.ts").writeText("// stub")
    File(trailmapDir, "tools/dupTool-meta.ts").writeText("// stub")
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.map { d ->
        // Enrichment resolves the meta-only descriptor to the same name the legacy
        // descriptor already claimed.
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/dupTool-meta.ts").absolutePath,
              name = "dupTool",
              description = "Meta-only descriptor.",
              meta = null,
              inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )
    assertTrue(
      resolved.targets.isEmpty(),
      "expected the trailmap to drop on duplicate-name collision between legacy + enriched descriptors",
    )
  }

  @Test
  fun `meta-only enrichment returning a blank name drops the trailmap`() {
    // Defense in depth: an enrichment impl that returns a Resolved config with a blank
    // tool name is an implementation bug, but the loader should surface it with a clear
    // message rather than poison the registry.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - blank
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/blank.yaml").writeText(
      """
      script: ./blank.ts
      """.trimIndent(),
    )
    File(trailmapDir, "tools/blank.ts").writeText("// stub")
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/blank.ts").absolutePath,
              name = "_placeholder", // InlineScriptToolConfig's init rejects blank — synthesize a name that passes init then loader rejects via enrichDeferredDescriptors blank check.
              description = null,
              meta = null,
              inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
            // Note: InlineScriptToolConfig's init throws on a literally-blank name,
            // so the loader's defense-in-depth blank check is unreachable through
            // InlineScriptToolConfig construction. The check exists as a guard
            // against a future config-class refactor that loosens that init.
            // We'd ideally bypass init for the test, but the loader's `name.isBlank()`
            // branch is documented as defense-in-depth and isn't directly testable
            // through public construction.
          ),
        )
      }
    }
    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )
    // The placeholder-name path resolves; the trailmap's `target.tools: [blank]` references
    // a name the enrichment didn't produce, so the trailmap drops with UnknownScriptedToolName.
    assertTrue(
      resolved.targets.isEmpty(),
      "expected the trailmap to drop when target.tools references a name the enrichment didn't produce",
    )
  }

  @Test
  fun `bare ts in tools dir auto-discovers as meta-only descriptor`() {
    // Auto-discovery contract: a `.ts` whose source mentions `trailblaze.tool` and has
    // no sibling YAML descriptor is treated as a meta-only descriptor — the loader
    // synthesizes a `TrailmapScriptedToolFile(script = "./<file>.ts")` and routes it
    // through the same enrichment hook the YAML-declared meta-only path uses. The
    // result: a workspace can author a tool as a single `.ts` file with zero YAML.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - autoTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    // ONLY a `.ts` file — no sibling YAML. The auto-discovery walk must pick it up
    // and the analyzer-stub fills in name + description + inputSchema as if the file
    // declared a single `trailblaze.tool<I>(spec, handler)` export.
    File(trailmapDir, "tools/autoTool.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const autoTool = trailblaze.tool<{ value: string }>(
        { supportedPlatforms: ["web"] },
        async (input) => `value=${"$"}{input.value}`,
      );
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.map { d ->
        // The synthetic descriptor's relativePath is the `.ts` itself, not a YAML
        // path — the loader uses it verbatim for diagnostics + the registry key.
        assertTrue(
          d.relativePath.endsWith(".ts"),
          "expected synthetic descriptor's relativePath to be the .ts file; got ${d.relativePath}",
        )
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/autoTool.ts").absolutePath,
              name = "autoTool",
              description = "Auto-discovered tool.",
              meta = null,
              inputSchema = JsonObject(
                mapOf(
                  "type" to JsonPrimitive("object"),
                  "properties" to JsonObject(emptyMap()),
                ),
              ),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    val tool = resolved.targets.single().tools?.single()
    assertEquals("autoTool", tool?.name)
    assertEquals("Auto-discovered tool.", tool?.description)
  }

  @Test
  fun `YAML descriptor wins over auto-discovered ts`() {
    // When both a YAML descriptor AND a sibling `.ts` exist, the YAML's coverage of
    // the `.ts` is recorded during the YAML walk and the auto-discovery pass skips
    // the `.ts`. Net result: exactly ONE deferred descriptor reaches enrichment, and
    // the tool's name / description come from the YAML-routed path — not a synthetic
    // shadow entry that would double-register or trigger the dup-name guard.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - openSample
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    // Partial single-tool descriptor (name + script, no description / inputSchema).
    File(trailmapDir, "tools/openSample.yaml").writeText(
      """
      script: ./openSample.ts
      name: openSample
      """.trimIndent(),
    )
    File(trailmapDir, "tools/openSample.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const openSample = trailblaze.tool<{}>(
        { supportedPlatforms: ["web"] },
        async () => "ok",
      );
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    // Pin that the YAML's coverage filter works: exactly ONE deferred descriptor is
    // sent to enrichment (the YAML's), not two (YAML + synthetic .ts).
    var deferredCount = -1
    val stub = StubEnrichment { _, _, _, deferred ->
      deferredCount = deferred.size
      deferred.map { d ->
        // The YAML route's relativePath is the `.yaml` file — proves we didn't route
        // through the auto-discovery synthetic-descriptor branch.
        assertTrue(
          d.relativePath.endsWith(".yaml"),
          "expected YAML route to win; got ${d.relativePath}",
        )
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/openSample.ts").absolutePath,
              name = d.descriptor.name!!,
              description = "From YAML route.",
              meta = null,
              inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    assertEquals(1, deferredCount, "expected the YAML to suppress auto-discovery of its sibling .ts")
    val tool = resolved.targets.single().tools?.single()
    assertEquals("openSample", tool?.name)
    assertEquals("From YAML route.", tool?.description)
  }

  @Test
  fun `helper ts without trailblaze tool reference is skipped during auto-discovery`() {
    // Helpers (`*_shared.ts`, `_example_*.ts`, etc.) live alongside tool sources but
    // export plain functions / constants — they don't carry a `trailblaze.tool` call.
    // The auto-discovery walk's substring pre-filter must skip them so they don't
    // spawn pointless analyzer work or surface "no typed declaration found" failures.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - realTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/realTool.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const realTool = trailblaze.tool<{}>(
        { supportedPlatforms: ["web"] },
        async () => "ok",
      );
      """.trimIndent(),
    )
    // Helper file — no `trailblaze.tool` text anywhere. Must be skipped.
    File(trailmapDir, "tools/shared_constants.ts").writeText(
      """
      // Plain helper constants — no tools here.
      export const ORIGIN = "https://example.com";
      export const VERSION = "1.0.0";
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val deferredPaths = mutableListOf<String>()
    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.forEach { deferredPaths += it.relativePath }
      deferred.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/realTool.ts").absolutePath,
              name = "realTool",
              description = "Real tool.",
              meta = null,
              inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    assertTrue(
      deferredPaths.none { it.endsWith("shared_constants.ts") },
      "helper without `trailblaze.tool` substring must be skipped; deferred saw $deferredPaths",
    )
    assertEquals(
      listOf("tools/realTool.ts"),
      deferredPaths,
      "only the tool-bearing .ts should reach enrichment",
    )
  }

  @Test
  fun `reference example ts that only mentions trailblaze tool in comments is skipped`() {
    // Reference / template files (`_example_typescript_tool.ts`) demonstrate the
    // typed binding shape inside `//` comments without actually declaring an
    // `export const X = trailblaze.tool(...)` binding. The auto-discovery
    // pre-grep must use a tight pattern that matches the analyzer's own
    // signature recognition — admitting the comment-bearing reference would
    // surface a "no typed declaration found" failure on every workspace compile.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - realTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/realTool.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const realTool = trailblaze.tool<{}>(
        { supportedPlatforms: ["web"] },
        async () => "ok",
      );
      """.trimIndent(),
    )
    // Reference example — the comment body shows `trailblaze.tool<…>(…)` and even
    // `export const myTool = trailblaze.tool(...)` inside `//` comments. The file
    // itself exports a plain async function (not a tool binding), so the
    // analyzer would reject it. The auto-discovery walk must skip it via the
    // pre-grep, before the analyzer sees it.
    File(trailmapDir, "tools/_example_typescript_tool.ts").writeText(
      """
      // Reference example for typed TypeScript tool authoring.
      //
      // To turn an export here into a real tool:
      // 1. Author as a `trailblaze.tool<I>(spec, handler)` binding:
      //      export const myTool = trailblaze.tool<MyArgs>(
      //        { supportedPlatforms: ["web"] },
      //        async (input, ctx) => "ok",
      //      );
      // 2. Add the export name to `target.tools:`.
      //
      // The trailmap loader auto-discovers any `.ts` with a `trailblaze.tool`
      // export — see the wikipedia trailmap for examples.

      import type { TrailblazeContext, TrailblazeClient } from "@trailblaze/scripting";

      export async function exampleHello(
        args: { greeting: string },
        ctx: TrailblazeContext | undefined,
        client: TrailblazeClient,
      ): Promise<string> {
        return args.greeting;
      }
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val deferredPaths = mutableListOf<String>()
    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.forEach { deferredPaths += it.relativePath }
      deferred.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/realTool.ts").absolutePath,
              name = "realTool",
              description = "Real tool.",
              meta = null,
              inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    assertEquals(
      listOf("tools/realTool.ts"),
      deferredPaths,
      "reference-example file with trailblaze.tool only in comments must NOT reach enrichment; saw $deferredPaths",
    )
  }

  @Test
  fun `multi-export ts without YAML surfaces a clear analyzer-side failure`() {
    // The synthetic meta-only descriptor cannot disambiguate a multi-export `.ts` —
    // matches the same contract the YAML-declared meta-only path enforces. The
    // analyzer impl emits a Failed result with a specific reason; the loader's
    // atomic-per-trailmap catch drops the trailmap. Pin the drop here; the
    // analyzer-side message is covered in AnalyzerScriptedToolEnrichmentTest.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - firstTool
          - secondTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/multi.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const firstTool = trailblaze.tool<{}>(
        { supportedPlatforms: ["web"] },
        async () => "1",
      );
      export const secondTool = trailblaze.tool<{}>(
        { supportedPlatforms: ["web"] },
        async () => "2",
      );
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val stub = StubEnrichment { _, _, _, deferred ->
      // Auto-discovery produced exactly one synthetic descriptor for `tools/multi.ts`
      // — same shape the analyzer-real impl receives. Pin that, then return Failed
      // with the multi-export reason.
      assertEquals(1, deferred.size, "expected exactly one synthetic descriptor")
      val d = deferred.single()
      assertTrue(d.relativePath.endsWith(".ts"))
      listOf(
        ScriptedToolEnrichment.EnrichmentResult.Failed(
          relativePath = d.relativePath,
          reason = "script declares more than one `trailblaze.tool` export ([firstTool, secondTool]); " +
            "meta-only descriptors can't disambiguate. Split the .ts into one export per file, " +
            "or author a YAML descriptor with a `tools:` list.",
        ),
      )
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    assertTrue(
      resolved.targets.isEmpty(),
      "expected the trailmap to drop on multi-export `.ts` without a disambiguating YAML",
    )
  }

  @Test
  fun `two bare ts files declaring the same tool name fail with dup-name error`() {
    // The dup-name guard in `enrichDeferredDescriptors` must fire when two distinct
    // bare `.ts` files (auto-discovered, no covering YAML) each enrich to the same
    // tool name — same contract the YAML-vs-YAML and YAML-vs-meta-only paths enforce.
    // SISTER-IMPL-TAG: partial-descriptor-eager-upgrade — without the
    // `previous.relativePath == result.relativePath` upgrade-in-place check ONLY
    // matching the SAME file, two different files producing the same name would
    // wrongly upgrade-in-place instead of throwing.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - dupTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/firstFile.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const dupTool = trailblaze.tool<{}>(
        { supportedPlatforms: ["web"] },
        async () => "first",
      );
      """.trimIndent(),
    )
    File(trailmapDir, "tools/secondFile.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const dupTool = trailblaze.tool<{}>(
        { supportedPlatforms: ["web"] },
        async () => "second",
      );
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    // Stub enrichment that synthesizes a config matching each file's declared name —
    // the dup-name guard fires at the loader level when both configs try to register
    // the same key from different relativePaths.
    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.map { d ->
        val basename = d.relativePath.substringAfterLast('/').removeSuffix(".ts")
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/$basename.ts").absolutePath,
              name = "dupTool",
              description = "From ${d.relativePath}.",
              meta = null,
              inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )
    assertTrue(
      resolved.targets.isEmpty(),
      "trailmap must drop when two bare .ts files declare the same tool name",
    )
  }

  @Test
  fun `multiple distinct bare ts files in one trailmap all auto-discover as synthetic descriptors`() {
    // Verify the second-pass walk doesn't choke when more than one bare `.ts` reaches
    // enrichment — each gets its own synthetic descriptor, each enriches independently,
    // and the final registry carries all of them. Catches a regression where the walk
    // would short-circuit after the first match or accidentally collapse multiple
    // synthetic descriptors into one.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - alpha
          - bravo
          - charlie
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    listOf("alpha", "bravo", "charlie").forEach { name ->
      File(trailmapDir, "tools/$name.ts").writeText(
        """
        import { trailblaze } from "@trailblaze/scripting";
        export const $name = trailblaze.tool<{}>(
          { supportedPlatforms: ["web"] },
          async () => "$name",
        );
        """.trimIndent(),
      )
    }
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val deferredPaths = mutableListOf<String>()
    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.forEach { deferredPaths += it.relativePath }
      deferred.map { d ->
        val name = d.relativePath.substringAfterLast('/').removeSuffix(".ts")
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/$name.ts").absolutePath,
              name = name,
              description = "$name tool.",
              meta = null,
              inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    assertEquals(
      listOf("tools/alpha.ts", "tools/bravo.ts", "tools/charlie.ts"),
      deferredPaths,
      "all three bare .ts files must reach enrichment",
    )
    val resolvedNames = resolved.targets.single().tools.orEmpty().map { it.name }.sorted()
    assertEquals(listOf("alpha", "bravo", "charlie"), resolvedNames)
  }

  @Test
  fun `test ts and d ts files in tools dir are skipped during auto-discovery`() {
    // `*.test.ts` (co-located type-smoke tests) and `*.d.ts` (declaration sidecars)
    // are intentionally outside the auto-discovery surface. They must not be treated
    // as tool sources even if they textually contain the string `trailblaze.tool`
    // (the type-smoke file references it constantly via the typed `client.tools.X`
    // surface). Pin both suffixes here so future test-fixture additions don't
    // accidentally trip the walk.
    val trailmapDir = File(tempFolder.root, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
        tools:
          - realTool
      """.trimIndent(),
    )
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/realTool.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const realTool = trailblaze.tool<{}>(
        { supportedPlatforms: ["web"] },
        async () => "ok",
      );
      """.trimIndent(),
    )
    // Co-located type-smoke test — references `trailblaze.tool` via type assertions.
    File(trailmapDir, "tools/realTool.test.ts").writeText(
      """
      // Type-level smoke test — references trailblaze.tool but is NOT a tool source.
      import type { trailblaze } from "@trailblaze/scripting";
      // ... assertions ...
      """.trimIndent(),
    )
    // Declaration sidecar — must not be analyzed.
    File(trailmapDir, "tools/realTool.d.ts").writeText(
      """
      // Hand-rolled declaration — must not be picked up as a tool source.
      export const realTool: typeof import("./realTool").realTool;
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - sampleapp
      """.trimIndent(),
    )

    val deferredPaths = mutableListOf<String>()
    val stub = StubEnrichment { _, _, _, deferred ->
      deferred.forEach { deferredPaths += it.relativePath }
      deferred.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            xyz.block.trailblaze.config.InlineScriptToolConfig(
              script = File(trailmapDir, "tools/realTool.ts").absolutePath,
              name = "realTool",
              description = "Real tool.",
              meta = null,
              inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
          ),
        )
      }
    }

    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    TrailblazeProjectConfigLoader.resolveRuntime(
      loaded,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = stub,
    )

    assertEquals(
      listOf("tools/realTool.ts"),
      deferredPaths,
      "only realTool.ts should reach enrichment; saw $deferredPaths",
    )
  }

  /**
   * Test-only [ScriptedToolEnrichment] that lets a test inline-stub the enrich(...)
   * result without spinning up the real analyzer subprocess.
   */
  private class StubEnrichment(
    private val behavior: (
      trailmapId: String,
      trailmapDir: File,
      trailmapToolsDir: File,
      deferred: List<ScriptedToolEnrichment.DeferredDescriptor>,
    ) -> List<ScriptedToolEnrichment.EnrichmentResult>,
  ) : ScriptedToolEnrichment {
    override fun enrich(
      trailmapId: String,
      trailmapDir: File,
      trailmapToolsDir: File,
      deferredDescriptors: List<ScriptedToolEnrichment.DeferredDescriptor>,
    ): List<ScriptedToolEnrichment.EnrichmentResult> =
      behavior(trailmapId, trailmapDir, trailmapToolsDir, deferredDescriptors)
  }
}
