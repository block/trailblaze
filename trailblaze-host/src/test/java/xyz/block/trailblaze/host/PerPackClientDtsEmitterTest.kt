package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.project.PackSource
import xyz.block.trailblaze.config.project.PackTargetConfig
import xyz.block.trailblaze.config.project.ResolvedPack
import xyz.block.trailblaze.config.project.TrailblazePackManifest
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Tests for [PerPackClientDtsEmitter] — the per-pack codegen layer that writes
 * `<packDir>/tools/.trailblaze/client.d.ts` per filesystem-backed pack.
 *
 * Coverage:
 *  - One file emitted per filesystem-backed pack at the documented path.
 *  - Pack's own scripted tools surface in its typed binding.
 *  - Transitively-exported scripted tools from a dep flow into the consumer's binding;
 *    NON-exported tools from the dep stay internal.
 *  - Classpath-backed packs are skipped (no JAR writes).
 *  - Empty pack pool is a no-op.
 */
class PerPackClientDtsEmitterTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `emits one client_d_ts per filesystem-backed pack with own scripted tools`() {
    val alphaPackDir = newPackDir("alpha")
    val betaPackDir = newPackDir("beta")

    val alphaPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "alpha",
        target = PackTargetConfig(displayName = "Alpha"),
      ),
      source = PackSource.Filesystem(alphaPackDir),
      target = AppTargetYamlConfig(
        id = "alpha",
        displayName = "Alpha",
        tools = listOf(
          InlineScriptToolConfig(
            script = "./tools/alpha_login.ts",
            name = "alpha_login",
            description = "Sign into Alpha.",
            inputSchema = buildJsonObject {
              put("type", JsonPrimitive("object"))
              put("properties", buildJsonObject { /* no params */ })
            },
          ),
        ),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val betaPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "beta",
        target = PackTargetConfig(displayName = "Beta"),
      ),
      source = PackSource.Filesystem(betaPackDir),
      target = AppTargetYamlConfig(
        id = "beta",
        displayName = "Beta",
        tools = listOf(
          InlineScriptToolConfig(
            script = "./tools/beta_login.ts",
            name = "beta_login",
            description = "Sign into Beta.",
            inputSchema = buildJsonObject {
              put("type", JsonPrimitive("object"))
              put("properties", buildJsonObject { /* no params */ })
            },
          ),
        ),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val emitted = PerPackClientDtsEmitter.emit(listOf(alphaPack, betaPack))

    assertEquals(2, emitted.size, "expected one binding per pack, got: $emitted")
    val alphaPath = File(alphaPackDir, "tools/.trailblaze/client.d.ts")
    val betaPath = File(betaPackDir, "tools/.trailblaze/client.d.ts")
    assertTrue(alphaPath.isFile, "expected $alphaPath")
    assertTrue(betaPath.isFile, "expected $betaPath")

    val alphaRendered = Files.readString(alphaPath.toPath())
    val betaRendered = Files.readString(betaPath.toPath())

    // Per-pack slicing: alpha's binding contains only alpha's scripted tool, not beta's.
    assertTrue("alpha binding should contain alpha_login: $alphaRendered") {
      alphaRendered.contains("alpha_login:")
    }
    assertFalse("alpha binding should NOT contain beta_login: $alphaRendered") {
      alphaRendered.contains("beta_login:")
    }
    assertTrue("beta binding should contain beta_login: $betaRendered") {
      betaRendered.contains("beta_login:")
    }
    assertFalse("beta binding should NOT contain alpha_login: $betaRendered") {
      betaRendered.contains("alpha_login:")
    }
  }

  @Test
  fun `transitively-exported scripted tools from a dep flow into consumers binding`() {
    val libPackDir = newPackDir("entity-factory")
    val appPackDir = newPackDir("storefront")

    val createEntity = InlineScriptToolConfig(
      script = "./tools/createEntity.ts",
      name = "createEntity",
      description = "Create a fresh entity in the system.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )
    val internalHelper = InlineScriptToolConfig(
      script = "./tools/_internal_setup.ts",
      name = "_internal_setup",
      description = "Library-internal helper — not for consumers.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )
    val libPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "entity_factory",
        target = PackTargetConfig(displayName = "Entity Factory"),
        // `exports:` lists ONLY `createEntity` — internal helper is NOT exposed.
        exports = listOf("createEntity"),
      ),
      source = PackSource.Filesystem(libPackDir),
      target = AppTargetYamlConfig(
        id = "entity_factory",
        displayName = "Entity Factory",
        tools = listOf(createEntity, internalHelper),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val appPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "storefront",
        target = PackTargetConfig(displayName = "Storefront"),
        dependencies = listOf("entity_factory"),
      ),
      source = PackSource.Filesystem(appPackDir),
      target = AppTargetYamlConfig(
        id = "storefront",
        displayName = "Storefront",
        tools = emptyList(),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    PerPackClientDtsEmitter.emit(listOf(libPack, appPack))

    val appRendered = Files.readString(File(appPackDir, "tools/.trailblaze/client.d.ts").toPath())
    val libRendered = Files.readString(File(libPackDir, "tools/.trailblaze/client.d.ts").toPath())

    // App pack inherits the exported tool through the dep's `exports:`.
    assertTrue("app binding should include exported createEntity: $appRendered") {
      appRendered.contains("createEntity:")
    }
    // App pack does NOT see the lib's internal helper (not in `exports:`).
    assertFalse("app binding should NOT include unexported helper: $appRendered") {
      appRendered.contains("_internal_setup:")
    }
    // Lib's own binding sees both — they're its own pack-local scripted tools.
    assertTrue("lib binding should include createEntity: $libRendered") {
      libRendered.contains("createEntity:")
    }
    assertTrue("lib binding should include its own internal helper: $libRendered") {
      libRendered.contains("\"_internal_setup\":") || libRendered.contains("_internal_setup:")
    }
  }

  @Test
  fun `typoed exports name fails loudly when consumer resolves the dep`() {
    // A typo in a dep's `exports:` (e.g. `createMrchant` instead of `createMerchant`)
    // would otherwise silently drop the tool from consumers' typed surface — caught here
    // at typed-surface codegen because that's the first layer that joins the dep's
    // declared exports against its actual scripted-tool list.
    val libPackDir = newPackDir("entity_factory")
    val appPackDir = newPackDir("storefront")

    val createEntity = InlineScriptToolConfig(
      script = "./tools/createEntity.ts",
      name = "createEntity",
      description = "Create a fresh entity.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )
    val libPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "entity_factory",
        target = PackTargetConfig(displayName = "Entity Factory"),
        // Typo: `createEntty` doesn't match the scripted tool's `createEntity` name.
        exports = listOf("createEntty"),
      ),
      source = PackSource.Filesystem(libPackDir),
      target = AppTargetYamlConfig(
        id = "entity_factory",
        displayName = "Entity Factory",
        tools = listOf(createEntity),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "storefront",
        target = PackTargetConfig(displayName = "Storefront"),
        dependencies = listOf("entity_factory"),
      ),
      source = PackSource.Filesystem(appPackDir),
      target = AppTargetYamlConfig(id = "storefront", displayName = "Storefront"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val ex = assertFailsWith<IllegalStateException> {
      PerPackClientDtsEmitter.emit(listOf(libPack, appPack))
    }
    val msg = ex.message ?: ""
    assertTrue("expected message to name the unresolved export: $msg") {
      msg.contains("createEntty")
    }
    assertTrue("expected message to name the offending dep: $msg") {
      msg.contains("entity_factory")
    }
  }

  @Test
  fun `cross-dep collision on exported scripted tool name fails loudly`() {
    // Two different deps both export the same scripted tool name with potentially
    // different schemas → ambiguous typed surface. Hard error matches the
    // `TrailblazePackBundler.collectScriptedToolEntriesForClosure` cross-pack-collision
    // posture so an author can't ship a declaration-merging mess that compiles cleanly
    // but resolves to whichever shape TypeScript picks at random.
    val depAPackDir = newPackDir("dep_a")
    val depBPackDir = newPackDir("dep_b")
    val appPackDir = newPackDir("consumer")

    fun mkLogin(packId: String): InlineScriptToolConfig = InlineScriptToolConfig(
      script = "./tools/login.ts",
      name = "login",
      description = "Login flow from $packId.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )

    val depA = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "dep_a",
        target = PackTargetConfig(displayName = "Dep A"),
        exports = listOf("login"),
      ),
      source = PackSource.Filesystem(depAPackDir),
      target = AppTargetYamlConfig(
        id = "dep_a",
        displayName = "Dep A",
        tools = listOf(mkLogin("dep_a")),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val depB = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "dep_b",
        target = PackTargetConfig(displayName = "Dep B"),
        exports = listOf("login"),
      ),
      source = PackSource.Filesystem(depBPackDir),
      target = AppTargetYamlConfig(
        id = "dep_b",
        displayName = "Dep B",
        tools = listOf(mkLogin("dep_b")),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "consumer",
        target = PackTargetConfig(displayName = "Consumer"),
        dependencies = listOf("dep_a", "dep_b"),
      ),
      source = PackSource.Filesystem(appPackDir),
      target = AppTargetYamlConfig(id = "consumer", displayName = "Consumer"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val ex = assertFailsWith<IllegalStateException> {
      PerPackClientDtsEmitter.emit(listOf(depA, depB, appPack))
    }
    val msg = ex.message ?: ""
    assertTrue("expected message to name the colliding tool: $msg") { msg.contains("'login'") }
    assertTrue("expected message to name dep_a: $msg") { msg.contains("dep_a") }
    assertTrue("expected message to name dep_b: $msg") { msg.contains("dep_b") }
    assertTrue("expected message to name the consumer: $msg") { msg.contains("consumer") }
  }

  @Test
  fun `pack-local override of a dep-exported scripted tool is allowed without collision`() {
    // A consumer pack legitimately defines its own scripted tool with the same name as a
    // dep's exported tool — the pack-local version wins, no error. This is the consumer-
    // override pattern documented on `collectPackTypedScriptedTools`.
    val libPackDir = newPackDir("entity_factory_override")
    val appPackDir = newPackDir("storefront_override")

    val depCreateEntity = InlineScriptToolConfig(
      script = "./tools/createEntity.ts",
      name = "createEntity",
      description = "Library default createEntity.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )
    val appCreateEntity = InlineScriptToolConfig(
      script = "./tools/createEntity.ts",
      name = "createEntity",
      description = "Pack-local override of createEntity.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )

    val libPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "entity_factory_override",
        target = PackTargetConfig(displayName = "Entity Factory"),
        exports = listOf("createEntity"),
      ),
      source = PackSource.Filesystem(libPackDir),
      target = AppTargetYamlConfig(
        id = "entity_factory_override",
        displayName = "Entity Factory",
        tools = listOf(depCreateEntity),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "storefront_override",
        target = PackTargetConfig(displayName = "Storefront"),
        dependencies = listOf("entity_factory_override"),
      ),
      source = PackSource.Filesystem(appPackDir),
      target = AppTargetYamlConfig(
        id = "storefront_override",
        displayName = "Storefront",
        tools = listOf(appCreateEntity),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    // Should NOT throw — pack-local overrides are intentional.
    PerPackClientDtsEmitter.emit(listOf(libPack, appPack))

    val appRendered = Files.readString(File(appPackDir, "tools/.trailblaze/client.d.ts").toPath())
    assertTrue("expected pack-local override to surface: $appRendered") {
      appRendered.contains("Pack-local override of createEntity")
    }
    assertFalse("expected dep's library default to be overridden, not duplicated: $appRendered") {
      appRendered.contains("Library default createEntity")
    }
  }

  @Test
  fun `classpath-backed packs are skipped`() {
    // A classpath-backed pack lives inside a JAR — we can't write into it.
    val classpathPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "clock",
        target = PackTargetConfig(displayName = "Clock"),
      ),
      source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/clock"),
      target = AppTargetYamlConfig(id = "clock", displayName = "Clock"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val emitted = PerPackClientDtsEmitter.emit(listOf(classpathPack))

    assertTrue(emitted.isEmpty(), "expected no emissions for classpath-only pool: $emitted")
  }

  @Test
  fun `empty resolvedPacks is a no-op`() {
    val emitted = PerPackClientDtsEmitter.emit(emptyList())
    assertTrue(emitted.isEmpty())
  }

  @Test
  fun `pack-local platforms_tool_sets surface Kotlin tools in typed binding`() {
    // Validates the resolver wire-through end-to-end: a pack that declares
    // `platforms.android.tool_sets: [core_interaction]` should see at least one
    // class-backed Kotlin tool from the classpath catalog land in its typed binding.
    //
    // The assertion deliberately avoids naming a specific tool ("inputText", "tap")
    // because the framework's toolset membership evolves over time — pinning the test
    // to one tool name turns every catalog refactor into a spurious test-failure
    // mystery. Instead we compare against a same-shape control pack that requests NO
    // toolsets: anything in the surface delta is by definition something
    // `core_interaction` contributed.
    val packWithToolset = newPackDir("with_toolset")
    val packWithoutToolset = newPackDir("without_toolset")

    fun buildPack(dir: File, toolSetIds: List<String>): ResolvedPack {
      val platforms = mapOf(
        "android" to PlatformConfig(
          appIds = listOf("com.example.${dir.name}"),
          toolSets = toolSetIds.ifEmpty { null },
        ),
      )
      return ResolvedPack(
        manifest = TrailblazePackManifest(
          id = dir.name,
          target = PackTargetConfig(displayName = dir.name, platforms = platforms),
        ),
        source = PackSource.Filesystem(dir),
        target = AppTargetYamlConfig(id = dir.name, displayName = dir.name, platforms = platforms),
        toolsets = emptyList(),
        tools = emptyList(),
        waypoints = emptyList(),
      )
    }

    PerPackClientDtsEmitter.emit(
      listOf(
        buildPack(packWithToolset, listOf("core_interaction")),
        buildPack(packWithoutToolset, emptyList()),
      ),
    )

    val withToolset = Files.readString(File(packWithToolset, "tools/.trailblaze/client.d.ts").toPath())
    val withoutToolset = Files.readString(File(packWithoutToolset, "tools/.trailblaze/client.d.ts").toPath())

    // The control pack has no requested toolsets → empty `TrailblazeToolMap`. The
    // toolset-requesting pack must produce a strictly-larger output (the additional
    // bytes are the typed entries `core_interaction` contributed).
    assertTrue(
      "expected `core_interaction` contributions to make the typed binding longer than " +
        "the empty-toolset control (with=${withToolset.length}, " +
        "without=${withoutToolset.length})",
    ) {
      withToolset.length > withoutToolset.length
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // surfaceToScriptedTools=false exclusion (issue #3258 / PR #3272)
  //
  // The unit-level filter is pinned by SurfaceFlagFilteringTest. This test pins the
  // integration: a Kotlin tool annotated `surfaceToScriptedTools = false` that lives in a
  // toolset the pack requests must NOT appear in the rendered `client.d.ts`. Without this,
  // a future change that reverts the call site back to `toKoogToolDescriptor()` (which
  // gates on `surfaceToLlm` instead) would silently re-include hidden tools in the typed
  // surface.
  // ─────────────────────────────────────────────────────────────────────────────

  @Serializable
  @TrailblazeToolClass(name = "test_visible_to_scripted")
  @LLMDescription("Test tool that should appear in client.d.ts.")
  private class VisibleToScriptedTool(
    @Suppress("unused") val text: String,
  ) : TrailblazeTool

  @Serializable
  @TrailblazeToolClass(name = "test_hidden_from_scripted", surfaceToScriptedTools = false)
  @LLMDescription("Test tool with surfaceToScriptedTools=false; must NOT appear in client.d.ts.")
  private class HiddenFromScriptedTool(
    @Suppress("unused") val text: String,
  ) : TrailblazeTool

  @Test
  fun `surfaceToScriptedTools false excludes Kotlin tool from rendered client_d_ts`() {
    val packDir = newPackDir("surface_filter_pack")
    val platforms = mapOf(
      "android" to PlatformConfig(
        appIds = listOf("com.example.surface_filter"),
        toolSets = listOf("surface_filter_test_set"),
      ),
    )
    val pack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "surface_filter_pack",
        target = PackTargetConfig(displayName = "Surface Filter", platforms = platforms),
      ),
      source = PackSource.Filesystem(packDir),
      target = AppTargetYamlConfig(id = "surface_filter_pack", displayName = "Surface Filter", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    // Inject a synthetic catalog so the test doesn't depend on the classpath-discovered
    // toolset set, and so it can pair the two flag values in one toolset for an apples-
    // to-apples comparison.
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "surface_filter_test_set",
        description = "Synthetic toolset for surface-flag exclusion testing.",
        toolClasses = setOf(VisibleToScriptedTool::class, HiddenFromScriptedTool::class),
      ),
    )

    PerPackClientDtsEmitter.emit(listOf(pack), catalog = catalog)

    val rendered = Files.readString(File(packDir, "tools/.trailblaze/client.d.ts").toPath())
    assertTrue("expected visible tool in binding: $rendered") {
      rendered.contains("test_visible_to_scripted")
    }
    assertFalse("expected hidden tool to be excluded from binding: $rendered") {
      rendered.contains("test_hidden_from_scripted")
    }
  }

  private fun newPackDir(id: String): File {
    val parent = createTempDirectory("per-pack-client-dts-test").toFile()
    tempDirs += parent
    return File(parent, id).apply { mkdirs() }
  }
}
