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
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.TrailmapTargetConfig
import xyz.block.trailblaze.config.project.ResolvedTrailmap
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import kotlinx.serialization.json.buildJsonArray
import xyz.block.trailblaze.scripting.ScriptedToolDefinition
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.util.BunBinaryResolver
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionException
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Tests for [PerTrailmapClientDtsEmitter] — the per-trailmap codegen layer that writes
 * `<trailmapDir>/tools/trailblaze-client.d.ts` per filesystem-backed trailmap.
 *
 * Coverage:
 *  - One file emitted per filesystem-backed trailmap at the documented path.
 *  - Trailmap's own scripted tools surface in its typed binding.
 *  - Transitively-exported scripted tools from a dep flow into the consumer's binding;
 *    NON-exported tools from the dep stay internal.
 *  - Classpath-backed trailmaps are skipped (no JAR writes).
 *  - Empty trailmap pool is a no-op.
 */
class PerTrailmapClientDtsEmitterTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `emits one client_d_ts per filesystem-backed trailmap with own scripted tools`() {
    val alphaTrailmapDir = newTrailmapDir("alpha")
    val betaTrailmapDir = newTrailmapDir("beta")

    val alphaTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "alpha",
        target = TrailmapTargetConfig(displayName = "Alpha"),
      ),
      source = TrailmapSource.Filesystem(alphaTrailmapDir),
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
    val betaTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "beta",
        target = TrailmapTargetConfig(displayName = "Beta"),
      ),
      source = TrailmapSource.Filesystem(betaTrailmapDir),
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

    val emitted = PerTrailmapClientDtsEmitter.emit(listOf(alphaTrailmap, betaTrailmap))

    assertEquals(2, emitted.size, "expected one binding per trailmap, got: $emitted")
    val alphaPath = File(alphaTrailmapDir, "tools/trailblaze-client.d.ts")
    val betaPath = File(betaTrailmapDir, "tools/trailblaze-client.d.ts")
    assertTrue(alphaPath.isFile, "expected $alphaPath")
    assertTrue(betaPath.isFile, "expected $betaPath")

    val alphaRendered = Files.readString(alphaPath.toPath())
    val betaRendered = Files.readString(betaPath.toPath())

    // Per-trailmap slicing: alpha's binding contains only alpha's scripted tool, not beta's.
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
    val libTrailmapDir = newTrailmapDir("entity-factory")
    val appTrailmapDir = newTrailmapDir("storefront")

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
    val libTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "entity_factory",
        target = TrailmapTargetConfig(displayName = "Entity Factory"),
        // `exports:` lists ONLY `createEntity` — internal helper is NOT exposed.
        exports = listOf("createEntity"),
      ),
      source = TrailmapSource.Filesystem(libTrailmapDir),
      target = AppTargetYamlConfig(
        id = "entity_factory",
        displayName = "Entity Factory",
        tools = listOf(createEntity, internalHelper),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val appTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "storefront",
        target = TrailmapTargetConfig(displayName = "Storefront"),
        dependencies = listOf("entity_factory"),
      ),
      source = TrailmapSource.Filesystem(appTrailmapDir),
      target = AppTargetYamlConfig(
        id = "storefront",
        displayName = "Storefront",
        tools = emptyList(),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    PerTrailmapClientDtsEmitter.emit(listOf(libTrailmap, appTrailmap))

    val appRendered = Files.readString(File(appTrailmapDir, "tools/trailblaze-client.d.ts").toPath())
    val libRendered = Files.readString(File(libTrailmapDir, "tools/trailblaze-client.d.ts").toPath())

    // App trailmap inherits the exported tool through the dep's `exports:`.
    assertTrue("app binding should include exported createEntity: $appRendered") {
      appRendered.contains("createEntity:")
    }
    // App trailmap does NOT see the lib's internal helper (not in `exports:`).
    assertFalse("app binding should NOT include unexported helper: $appRendered") {
      appRendered.contains("_internal_setup:")
    }
    // Lib's own binding sees both — they're its own trailmap-local scripted tools.
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
    val libTrailmapDir = newTrailmapDir("entity_factory")
    val appTrailmapDir = newTrailmapDir("storefront")

    val createEntity = InlineScriptToolConfig(
      script = "./tools/createEntity.ts",
      name = "createEntity",
      description = "Create a fresh entity.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )
    val libTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "entity_factory",
        target = TrailmapTargetConfig(displayName = "Entity Factory"),
        // Typo: `createEntty` doesn't match the scripted tool's `createEntity` name.
        exports = listOf("createEntty"),
      ),
      source = TrailmapSource.Filesystem(libTrailmapDir),
      target = AppTargetYamlConfig(
        id = "entity_factory",
        displayName = "Entity Factory",
        tools = listOf(createEntity),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "storefront",
        target = TrailmapTargetConfig(displayName = "Storefront"),
        dependencies = listOf("entity_factory"),
      ),
      source = TrailmapSource.Filesystem(appTrailmapDir),
      target = AppTargetYamlConfig(id = "storefront", displayName = "Storefront"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val ex = assertFailsWith<IllegalStateException> {
      PerTrailmapClientDtsEmitter.emit(listOf(libTrailmap, appTrailmap))
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
    // `TrailblazeTrailmapBundler.collectScriptedToolEntriesForClosure` cross-trailmap-collision
    // posture so an author can't ship a declaration-merging mess that compiles cleanly
    // but resolves to whichever shape TypeScript picks at random.
    val depATrailmapDir = newTrailmapDir("dep_a")
    val depBTrailmapDir = newTrailmapDir("dep_b")
    val appTrailmapDir = newTrailmapDir("consumer")

    fun mkLogin(trailmapId: String): InlineScriptToolConfig = InlineScriptToolConfig(
      script = "./tools/login.ts",
      name = "login",
      description = "Login flow from $trailmapId.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )

    val depA = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "dep_a",
        target = TrailmapTargetConfig(displayName = "Dep A"),
        exports = listOf("login"),
      ),
      source = TrailmapSource.Filesystem(depATrailmapDir),
      target = AppTargetYamlConfig(
        id = "dep_a",
        displayName = "Dep A",
        tools = listOf(mkLogin("dep_a")),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val depB = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "dep_b",
        target = TrailmapTargetConfig(displayName = "Dep B"),
        exports = listOf("login"),
      ),
      source = TrailmapSource.Filesystem(depBTrailmapDir),
      target = AppTargetYamlConfig(
        id = "dep_b",
        displayName = "Dep B",
        tools = listOf(mkLogin("dep_b")),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "consumer",
        target = TrailmapTargetConfig(displayName = "Consumer"),
        dependencies = listOf("dep_a", "dep_b"),
      ),
      source = TrailmapSource.Filesystem(appTrailmapDir),
      target = AppTargetYamlConfig(id = "consumer", displayName = "Consumer"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val ex = assertFailsWith<IllegalStateException> {
      PerTrailmapClientDtsEmitter.emit(listOf(depA, depB, appTrailmap))
    }
    val msg = ex.message ?: ""
    assertTrue("expected message to name the colliding tool: $msg") { msg.contains("'login'") }
    assertTrue("expected message to name dep_a: $msg") { msg.contains("dep_a") }
    assertTrue("expected message to name dep_b: $msg") { msg.contains("dep_b") }
    assertTrue("expected message to name the consumer: $msg") { msg.contains("consumer") }
  }

  @Test
  fun `trailmap-local override of a dep-exported scripted tool is allowed without collision`() {
    // A consumer trailmap legitimately defines its own scripted tool with the same name as a
    // dep's exported tool — the trailmap-local version wins, no error. This is the consumer-
    // override pattern documented on `collectTrailmapTypedScriptedTools`.
    val libTrailmapDir = newTrailmapDir("entity_factory_override")
    val appTrailmapDir = newTrailmapDir("storefront_override")

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
      description = "Trailmap-local override of createEntity.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { /* no params */ })
      },
    )

    val libTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "entity_factory_override",
        target = TrailmapTargetConfig(displayName = "Entity Factory"),
        exports = listOf("createEntity"),
      ),
      source = TrailmapSource.Filesystem(libTrailmapDir),
      target = AppTargetYamlConfig(
        id = "entity_factory_override",
        displayName = "Entity Factory",
        tools = listOf(depCreateEntity),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "storefront_override",
        target = TrailmapTargetConfig(displayName = "Storefront"),
        dependencies = listOf("entity_factory_override"),
      ),
      source = TrailmapSource.Filesystem(appTrailmapDir),
      target = AppTargetYamlConfig(
        id = "storefront_override",
        displayName = "Storefront",
        tools = listOf(appCreateEntity),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    // Should NOT throw — trailmap-local overrides are intentional.
    PerTrailmapClientDtsEmitter.emit(listOf(libTrailmap, appTrailmap))

    val appRendered = Files.readString(File(appTrailmapDir, "tools/trailblaze-client.d.ts").toPath())
    assertTrue("expected trailmap-local override to surface: $appRendered") {
      appRendered.contains("Trailmap-local override of createEntity")
    }
    assertFalse("expected dep's library default to be overridden, not duplicated: $appRendered") {
      appRendered.contains("Library default createEntity")
    }
  }

  @Test
  fun `classpath-backed trailmaps are skipped`() {
    // A classpath-backed trailmap lives inside a JAR — we can't write into it.
    val classpathTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "clock",
        target = TrailmapTargetConfig(displayName = "Clock"),
      ),
      source = TrailmapSource.Classpath(resourceDir = "trails/config/trailmaps/clock"),
      target = AppTargetYamlConfig(id = "clock", displayName = "Clock"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val emitted = PerTrailmapClientDtsEmitter.emit(listOf(classpathTrailmap))

    assertTrue(emitted.isEmpty(), "expected no emissions for classpath-only pool: $emitted")
  }

  @Test
  fun `empty resolvedTrailmaps is a no-op`() {
    val emitted = PerTrailmapClientDtsEmitter.emit(emptyList())
    assertTrue(emitted.isEmpty())
  }

  @Test
  fun `isRecordable-derived surface injects selector + YAML tools into every binding`() {
    // The durable fix: the surface is derived from `isRecordable`, so recordable tools that live in
    // NO tool_sets (the selector-migration pipeline's `assertVisibleBySelector` /
    // `assertNotVisibleBySelector`, which the LLM never picks) and YAML-defined recordable tools
    // (`eraseText` / `pressBack`) land in EVERY binding — even a trailmap with no tool_sets and no
    // scripted tools of its own. Selector-typed args the descriptor path strips are re-injected, and
    // HAND_CURATED_RECORDABLE tools are left to built-in-tools.ts so the two surfaces don't collide.
    val trailmapDir = newTrailmapDir("recordable_surface_pack")
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "recordable_surface_pack",
        target = TrailmapTargetConfig(displayName = "Recordable Surface"),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(id = "recordable_surface_pack", displayName = "Recordable Surface"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    PerTrailmapClientDtsEmitter.emit(listOf(trailmap))
    val rendered = Files.readString(File(trailmapDir, "tools/trailblaze-client.d.ts").toPath())

    // Selector-migration tools that are in no tool_sets still appear — including
    // `assertNotVisibleBySelector`, the one a prior hand-curation of built-in-tools.ts forgot.
    assertTrue("expected assertVisibleBySelector in the recordable surface: $rendered") {
      rendered.contains("assertVisibleBySelector:")
    }
    assertTrue("expected assertNotVisibleBySelector (the drift-missed tool): $rendered") {
      rendered.contains("assertNotVisibleBySelector:")
    }
    // Selector arg re-injected, with the grammar type imported from the SDK bundle.
    assertTrue("expected the TrailblazeNodeSelector import: $rendered") {
      rendered.contains("import type { TrailblazeNodeSelector } from \"@trailblaze/scripting\";")
    }
    assertTrue("expected a re-injected nodeSelector arg on assertVisibleBySelector: $rendered") {
      toolEntryBlock(rendered, "assertVisibleBySelector").contains("nodeSelector?: TrailblazeNodeSelector;")
    }
    // `tapOn` carries a REQUIRED `selector: TrailblazeNodeSelector` — re-injected non-optional. (It
    // needs a class `@LLMDescription` to be lowerable at all; without one the descriptor build throws
    // and the tool is silently dropped from the surface, which is how it regressed to "does not
    // exist" before that annotation was added.)
    assertTrue("expected tapOn in the recordable surface: $rendered") { rendered.contains("tapOn:") }
    assertTrue("expected a re-injected required selector arg on tapOn: $rendered") {
      toolEntryBlock(rendered, "tapOn").contains("selector: TrailblazeNodeSelector;")
    }
    // YAML-defined recordable tools flow in from their config params.
    assertTrue("expected YAML-defined eraseText: $rendered") { rendered.contains("eraseText:") }
    assertTrue("expected YAML-defined pressBack: $rendered") { rendered.contains("pressBack:") }
    // HAND_CURATED_RECORDABLE tools are skipped here (they stay in built-in-tools.ts).
    assertFalse("mobile_maestro must be left to built-in-tools.ts: $rendered") {
      rendered.contains("mobile_maestro:")
    }
    assertFalse("mobile_listInstalledApps must be left to built-in-tools.ts: $rendered") {
      rendered.contains("mobile_listInstalledApps:")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Classpath validation surfaces (trail-recording type-validation coverage for
  // JAR-bundled targets like `square` / `dashboardapp`).
  //
  // `emit` deliberately skips classpath-backed trailmaps (can't write into a JAR).
  // `emitClasspathValidationSurfaces` is the companion that writes a validation-only
  // surface into a caller-supplied scratch dir so `TrailTscValidator` can type-check
  // those targets' recorded trails. These tests pin the contract at the emitter
  // boundary; the end-to-end wiring is covered by the CompileCommand/CheckCommand path.
  // ─────────────────────────────────────────────────────────────────────────────

  @Serializable
  @TrailblazeToolClass(name = "classpath_surface_tool")
  @LLMDescription("Class-backed tool that must land in a classpath trailmap's validation surface.")
  private class ClasspathSurfaceTool(
    @Suppress("unused") val text: String,
  ) : TrailblazeTool

  @Test
  fun `emitClasspathValidationSurfaces writes a surface from a baked target config`() {
    // A classpath-backed target (lives in a JAR) has no writable tools/ dir of its own, so it
    // gets its validation surface in a caller-supplied scratch dir keyed by id, built from the
    // build-time-baked AppTargetYamlConfig. The surface must carry BOTH the class-backed tool
    // (resolved via the catalog from platforms.tool_sets) AND the baked scripted tool.
    val outputBase = createTempDirectory("classpath-surface-out").toFile().also { tempDirs += it }
    val platforms = mapOf(
      "android" to PlatformConfig(
        appIds = listOf("com.example.bundled"),
        toolSets = listOf("classpath_surface_set"),
      ),
    )
    val bakedConfig = AppTargetYamlConfig(
      id = "bundled_app",
      displayName = "Bundled App",
      platforms = platforms,
      tools = listOf(
        InlineScriptToolConfig(
          script = "./tools/bundled_login.ts",
          name = "bundled_login",
          description = "Sign into the bundled app.",
          inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { /* no params */ })
          },
        ),
      ),
    )
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "classpath_surface_set",
        description = "Synthetic toolset for the classpath validation-surface test.",
        toolClasses = setOf(ClasspathSurfaceTool::class),
      ),
    )

    val emitted = PerTrailmapClientDtsEmitter.emitClasspathValidationSurfaces(
      targetConfigs = listOf(bakedConfig),
      excludeIds = emptySet(),
      outputBaseDir = outputBase.toPath(),
      catalog = catalog,
    )

    assertEquals(1, emitted.size, "expected one surface for the classpath target, got: $emitted")
    val surfacePath = File(outputBase, "bundled_app/tools/trailblaze-client.d.ts")
    assertTrue(surfacePath.isFile, "expected surface at $surfacePath")
    val rendered = Files.readString(surfacePath.toPath())
    assertTrue("surface should carry the baked scripted tool: $rendered") {
      rendered.contains("bundled_login:")
    }
    assertTrue("surface should carry the class-backed tool from the toolset: $rendered") {
      rendered.contains("classpath_surface_tool:")
    }
  }

  @Test
  fun `emitClasspathValidationSurfaces excludes workspace filesystem trailmap ids`() {
    // A bundled target whose id matches a workspace filesystem trailmap already got a real
    // (analyzer-upgraded) surface from `emit`; re-emitting a bundled copy here would shadow it,
    // so ids in excludeIds must be skipped and never leak a file into the scratch dir.
    val outputBase = createTempDirectory("classpath-surface-skip-out").toFile().also { tempDirs += it }
    val bakedConfig = AppTargetYamlConfig(id = "sample_app", displayName = "Sample App")

    val emitted = PerTrailmapClientDtsEmitter.emitClasspathValidationSurfaces(
      targetConfigs = listOf(bakedConfig),
      excludeIds = setOf("sample_app"),
      outputBaseDir = outputBase.toPath(),
    )

    assertTrue(emitted.isEmpty(), "expected no surfaces when the only target id is excluded: $emitted")
    assertFalse("scratch dir must stay empty for an excluded id") {
      File(outputBase, "sample_app").exists()
    }
  }

  @Test
  fun `trailmap-local platforms_tool_sets surface Kotlin tools in typed binding`() {
    // Validates the resolver wire-through end-to-end together with the isRecordable-derived surface:
    // every binding — even one that requests NO tool_sets — now carries the recordable framework
    // tools (source #2 of `resolveKotlinToolDescriptorsForTrailmap`), and a trailmap that DOES
    // request `core_interaction` is a superset of that recordable-only control. The tool_sets
    // wire-through itself (source #1, incl. non-recordable tools) is pinned separately by the
    // synthetic-catalog tests above.
    val trailmapWithToolset = newTrailmapDir("with_toolset")
    val trailmapWithoutToolset = newTrailmapDir("without_toolset")

    fun buildTrailmap(dir: File, toolSetIds: List<String>): ResolvedTrailmap {
      val platforms = mapOf(
        "android" to PlatformConfig(
          appIds = listOf("com.example.${dir.name}"),
          toolSets = toolSetIds.ifEmpty { null },
        ),
      )
      return ResolvedTrailmap(
        manifest = TrailblazeTrailmapManifest(
          id = dir.name,
          target = TrailmapTargetConfig(displayName = dir.name, platforms = platforms),
        ),
        source = TrailmapSource.Filesystem(dir),
        target = AppTargetYamlConfig(id = dir.name, displayName = dir.name, platforms = platforms),
        toolsets = emptyList(),
        tools = emptyList(),
        waypoints = emptyList(),
      )
    }

    PerTrailmapClientDtsEmitter.emit(
      listOf(
        buildTrailmap(trailmapWithToolset, listOf("core_interaction")),
        buildTrailmap(trailmapWithoutToolset, emptyList()),
      ),
    )

    val withToolset = Files.readString(File(trailmapWithToolset, "tools/trailblaze-client.d.ts").toPath())
    val withoutToolset = Files.readString(File(trailmapWithoutToolset, "tools/trailblaze-client.d.ts").toPath())

    // The recordable-tool surface is now derived from `isRecordable` and injected into EVERY
    // binding, so the no-toolset control is no longer empty — it carries the recordable framework
    // tools (e.g. `inputText`). Requesting `core_interaction` can only ADD to that (its
    // non-recordable tools aren't in the recordable union), so the toolset binding is a superset.
    assertTrue("no-toolset control should still carry the recordable framework surface: $withoutToolset") {
      withoutToolset.contains("inputText:")
    }
    assertTrue(
      "core_interaction binding should be at least as large as the recordable-only control " +
        "(with=${withToolset.length}, without=${withoutToolset.length})",
    ) {
      withToolset.length >= withoutToolset.length
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Framework-metadata JSDoc tag emission (PR #3506)
  //
  // The emitter projects each Kotlin tool's `@TrailblazeToolClass` annotation values into
  // a `ToolFrameworkMetadata` map and threads it into `WorkspaceClientDtsGenerator`, which
  // renders `@trailblaze*` JSDoc tags above each tool's entry. The renderer-level tag
  // emission is pinned by `ToolMapEntryRendererTest`; the generator-level threading is
  // pinned by `WorkspaceClientDtsGeneratorTest`. THIS test pins the integration: a Kotlin
  // tool whose annotation declares a non-default field actually produces the matching tag
  // in the trailmap's rendered `.d.ts`. Without it, a regression that populated descriptors
  // but cleared the metadata map (or vice versa) would pass every per-layer unit test.
  // ─────────────────────────────────────────────────────────────────────────────

  @Serializable
  @TrailblazeToolClass(name = "test_host_only", requiresHost = true)
  @LLMDescription("Host-only test tool — should render with @trailblazeHostOnly.")
  private class TestHostOnlyTool(
    @Suppress("unused") val command: String,
  ) : TrailblazeTool

  // ─────────────────────────────────────────────────────────────────────────────
  // No scripted-surface visibility gate (surfaceToScriptedTools removed)
  //
  // The scripted-tool typed surface is ungated: a tool hidden from the LLM
  // (`surfaceToLlm = false`) MUST still be emitted into the trailmap's rendered
  // `trailblaze-client.d.ts`. `SurfaceFlagFilteringTest` pins this at the converter
  // (`toScriptedToolDescriptor`); THIS integration test pins it at the emitter call site, so a
  // future re-introduction of a scripted-surface gate (here or in the converter) regresses
  // obviously rather than silently dropping LLM-hidden tools from the typed surface.
  // ─────────────────────────────────────────────────────────────────────────────

  @Serializable
  @TrailblazeToolClass(name = "test_llm_hidden_surfacing", surfaceToLlm = false)
  @LLMDescription("Hidden from the LLM, but must still appear in the scripted-tool typed surface.")
  private class LlmHiddenSurfacingTool(
    @Suppress("unused") val text: String,
  ) : TrailblazeTool

  @Test
  fun `LLM-hidden Kotlin tool still surfaces in rendered client_d_ts (no scripted-surface gate)`() {
    val trailmapDir = newTrailmapDir("llm_hidden_pack")
    val platforms = mapOf(
      "android" to PlatformConfig(
        appIds = listOf("com.example.llm_hidden"),
        toolSets = listOf("llm_hidden_test_set"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "llm_hidden_pack",
        target = TrailmapTargetConfig(displayName = "LLM Hidden", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(id = "llm_hidden_pack", displayName = "LLM Hidden", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "llm_hidden_test_set",
        description = "Synthetic toolset holding an LLM-hidden tool.",
        toolClasses = setOf(LlmHiddenSurfacingTool::class),
      ),
    )

    PerTrailmapClientDtsEmitter.emit(listOf(trailmap), catalog = catalog)

    val rendered = Files.readString(File(trailmapDir, "tools/trailblaze-client.d.ts").toPath())
    assertTrue("LLM-hidden tool must still be emitted to the scripted surface: $rendered") {
      rendered.contains("test_llm_hidden_surfacing:")
    }
  }

  @Serializable
  @TrailblazeToolClass(name = "test_no_flags")
  @LLMDescription("Test tool with all-default annotation flags; renders with no framework tag.")
  private class TestNoFlagsTool(
    @Suppress("unused") val text: String,
  ) : TrailblazeTool

  // ─────────────────────────────────────────────────────────────────────────────
  // Declared resultType renders the same named type built-in-tools.ts would (#4362 follow-up)
  //
  // A Kotlin tool that declares `@TrailblazeToolClass(resultType = ...)` must render `result:
  // <TheTypeName>` (a bare reference resolved via `import type { ... } from
  // "@trailblaze/scripting"`) instead of the today-default `result: string`. This is the
  // per-trailmap-generator half of closing the dormant declaration-merge risk documented on
  // built-in-tools.ts: both this generator and BuiltInToolResultTsBindings derive the same
  // `result:` type from the same `resultType` annotation, so they can't disagree.
  // ─────────────────────────────────────────────────────────────────────────────

  @Serializable
  private data class TestTypedToolResult(val value: String)

  @Serializable
  @TrailblazeToolClass(name = "test_typed_result", resultType = TestTypedToolResult::class)
  @LLMDescription("Test tool whose structuredContent is a declared result type.")
  private class TestTypedResultTool(
    @Suppress("unused") val text: String,
  ) : TrailblazeTool

  @Test
  fun `Kotlin tool with declared resultType renders a named result type, not the string fallback`() {
    val trailmapDir = newTrailmapDir("typed_result_pack")
    val platforms = mapOf(
      "android" to PlatformConfig(
        appIds = listOf("com.example.typed_result"),
        toolSets = listOf("typed_result_test_set"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "typed_result_pack",
        target = TrailmapTargetConfig(displayName = "Typed Result", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(id = "typed_result_pack", displayName = "Typed Result", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "typed_result_test_set",
        description = "Synthetic toolset for declared-resultType integration testing.",
        toolClasses = setOf(TestTypedResultTool::class),
      ),
    )

    PerTrailmapClientDtsEmitter.emit(listOf(trailmap), catalog = catalog)

    val rendered = Files.readString(File(trailmapDir, "tools/trailblaze-client.d.ts").toPath())
    assertTrue("expected an import of the declared result type: $rendered") {
      rendered.contains("import type { TestTypedToolResult } from \"@trailblaze/scripting\";")
    }
    assertTrue("expected the named result type on the tool entry: $rendered") {
      rendered.contains("result: TestTypedToolResult;")
    }
    assertFalse("must not fall back to the default `result: string;` for this tool: $rendered") {
      rendered.substringAfter("test_typed_result:").substringBefore("};").contains("result: string;")
    }
  }

  @Test
  fun `Kotlin tool annotation projects to @trailblaze JSDoc tag in rendered client_d_ts`() {
    val trailmapDir = newTrailmapDir("framework_tag_pack")
    val platforms = mapOf(
      "android" to PlatformConfig(
        appIds = listOf("com.example.framework_tag"),
        toolSets = listOf("framework_tag_test_set"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "framework_tag_pack",
        target = TrailmapTargetConfig(displayName = "Framework Tag", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(id = "framework_tag_pack", displayName = "Framework Tag", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    // Synthetic catalog pairs a non-default-annotation tool with an all-defaults tool in
    // ONE toolset so the rendered output covers both the "tag emitted" and "no tag emitted"
    // branches in one go — guards against a regression that would silently apply the same
    // metadata to every tool regardless of its annotation values.
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "framework_tag_test_set",
        description = "Synthetic toolset for @trailblaze tag integration testing.",
        toolClasses = setOf(TestHostOnlyTool::class, TestNoFlagsTool::class),
      ),
    )

    PerTrailmapClientDtsEmitter.emit(listOf(trailmap), catalog = catalog)

    val rendered = Files.readString(File(trailmapDir, "tools/trailblaze-client.d.ts").toPath())
    // Positive case: the annotation value flows all the way through to the rendered tag.
    assertTrue("expected @trailblazeHostOnly above test_host_only: $rendered") {
      rendered.contains("     * @trailblazeHostOnly\n     */\n    test_host_only:")
    }
    // Negative case: an all-defaults annotation produces NO tag. Scope the assertion to
    // the immediate vicinity of the tool's JSDoc block so a `@trailblaze` tag emitted
    // above some OTHER tool elsewhere in the file doesn't spuriously fail this check.
    // Anchor on the rendered tag-line column (`     * @trailblaze`) so a prose mention of
    // `@trailblaze` inside the description doesn't false-positive — only an actual
    // emitted tag line matches.
    val noFlagsBlockStart = rendered.indexOf("    test_no_flags:")
    val noFlagsJsdocStart = rendered.lastIndexOf("/**", noFlagsBlockStart)
    val noFlagsJsdocBlock = rendered.substring(noFlagsJsdocStart, noFlagsBlockStart)
    assertFalse("test_no_flags JSDoc must not contain a @trailblaze tag line: $noFlagsJsdocBlock") {
      noFlagsJsdocBlock.lineSequence().any { it.startsWith("     * @trailblaze") }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Analyzer-aware codegen (typed-authoring follow-up to PR #3322 / #3323)
  //
  // PerTrailmapClientDtsEmitter accepts an optional [ScriptedToolDefinitionAnalyzer] and
  // applies its output as [WorkspaceClientDtsGenerator.TypedToolOverride]s — upgrading
  // matching tools' `args:` and `result:` halves from the YAML-derived flat shape to
  // the analyzer's full typed surface (nested objects, unions, TSDoc round-trip).
  //
  // These tests pin the wiring; the unit-level emitter behavior is covered by
  // `WorkspaceClientDtsGeneratorTest` and `JsonSchemaToTsRichTest`.
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Author-shape `.ts` fixture mirroring the wikipedia_typed_demo authoring pattern.
   * Uses the same `declare const trailblaze: { ... }` stub the analyzer's own test
   * suite uses so the fixture compiles without needing `@trailblaze/scripting`
   * resolvable from the temp trailmap tree.
   */
  private val typedDemoFixture: String = """
    |declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => unknown };
    |
    |/** Inputs for the typed demo. */
    |interface DemoInput {
    |  /** The message to format. */
    |  message: string;
    |  /** Optional prefix. */
    |  prefix?: string;
    |}
    |
    |/** Result shape for the typed demo. */
    |interface DemoOutput {
    |  /** The formatted message. */
    |  formatted: string;
    |  /** Code-unit length of the input message. */
    |  inputLength: number;
    |}
    |
    |/** Typed-authoring demo. */
    |export const typed_demo = trailblaze.tool<DemoInput, DemoOutput>({
    |  handler: async (input) => ({ formatted: input.message, inputLength: input.message.length }),
    |});
    |""".trimMargin()

  @Test
  fun `analyzer-derived TypedToolOverride upgrades args and result in the rendered client_d_ts`() {
    val bun = BunBinaryResolver.resolveBunBinary()
    val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir()
    val shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir)
    val tsjsg = sdkDir?.let { File(it, "node_modules/ts-json-schema-generator") }
    assumeTrue(
      "bun + extract-tool-defs.mjs + ts-json-schema-generator must be available to run " +
        "the analyzer end-to-end; install bun (`brew install bun`) and run `bun install` under " +
        "sdks/typescript to enable.",
      bun != null && shim != null && sdkDir != null && tsjsg?.isDirectory == true,
    )
    val analyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun!!,
      extractorShim = shim!!,
      sdkDir = sdkDir!!,
    )

    val trailmapDir = newTrailmapDir("typed_demo_pack")
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "typed_demo.ts").writeText(typedDemoFixture)

    // Synthesize the matching loader-shape `InlineScriptToolConfig`. In a real workspace
    // this would come from a YAML descriptor; here we shortcut it to focus the test on
    // the emitter's analyzer-merge behavior. The YAML-derived `description:` is set to a
    // sentinel value that the analyzer's TSDoc must override.
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "typed_demo_pack",
        target = TrailmapTargetConfig(displayName = "Typed Demo"),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(
        id = "typed_demo_pack",
        displayName = "Typed Demo",
        tools = listOf(
          InlineScriptToolConfig(
            script = File(toolsDir, "typed_demo.ts").absolutePath,
            name = "typed_demo",
            description = "YAML-DERIVED-DESCRIPTION-SHOULD-BE-OVERRIDDEN",
            inputSchema = buildJsonObject {
              put("type", JsonPrimitive("object"))
            },
          ),
        ),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    PerTrailmapClientDtsEmitter.emit(listOf(trailmap), analyzer = analyzer)

    val rendered = Files.readString(File(trailmapDir, "tools/trailblaze-client.d.ts").toPath())
    // TSDoc on the exported const replaces the YAML-derived description.
    assertTrue("expected analyzer TSDoc in rendered: $rendered") {
      rendered.contains("Typed-authoring demo.")
    }
    assertFalse("YAML description sentinel must be overridden: $rendered") {
      rendered.contains("YAML-DERIVED-DESCRIPTION-SHOULD-BE-OVERRIDDEN")
    }
    // Args carry the full typed shape plus per-field TSDoc.
    assertTrue("expected typed args message field: $rendered") {
      rendered.contains("message: string;")
    }
    assertTrue("expected optional typed args prefix field: $rendered") {
      rendered.contains("prefix?: string;")
    }
    assertTrue("expected field-level TSDoc on message: $rendered") {
      rendered.contains("/** The message to format. */")
    }
    // Result is the real `DemoOutput` shape — NOT the today-default `string`. Scope to typed_demo's
    // own entry (the surface now also carries recordable framework tools, each `result: string;`).
    assertFalse("typed_demo's result must be the typed shape, not `string`: $rendered") {
      toolEntryBlock(rendered, "typed_demo").contains("result: string;")
    }
    assertTrue("expected typed result shape: $rendered") {
      rendered.contains("formatted: string;") && rendered.contains("inputLength: number;")
    }
  }

  @Test
  fun `null analyzer degrades cleanly to today's YAML-derived shape`() {
    // The forward-compat guarantee: a daemon that can't resolve `node` (no install, fresh
    // CI, etc.) still emits per-trailmap `trailblaze-client.d.ts` — just without typed `result` upgrades.
    // Passing `analyzer = null` exercises the degradation path explicitly, regardless of
    // whether the test host has Node installed.
    val trailmapDir = newTrailmapDir("yaml_only_pack")
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "yaml_only_pack",
        target = TrailmapTargetConfig(displayName = "Yaml Only"),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(
        id = "yaml_only_pack",
        displayName = "Yaml Only",
        tools = listOf(
          InlineScriptToolConfig(
            script = "./tools/foo.ts",
            name = "foo",
            description = "Legacy YAML-only tool.",
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

    PerTrailmapClientDtsEmitter.emit(listOf(trailmap), analyzer = null)

    val rendered = Files.readString(File(trailmapDir, "tools/trailblaze-client.d.ts").toPath())
    // The today-default `result: string;` shape MUST appear when the analyzer is null —
    // verifies the degradation path doesn't silently leak a partial upgrade.
    assertTrue("expected legacy `result: string;` shape: $rendered") {
      rendered.contains("result: string;")
    }
    assertTrue("expected foo entry: $rendered") { rendered.contains("foo: {") }
  }

  @Test
  fun `typed-tool override flows through deps exports list (cross-trailmap)`() {
    // Bot-flagged (codex P1) regression: `collectTypedToolOverridesForTrailmap` only analyzed
    // the current trailmap, so a consumer trailmap that imported a typed tool via a dep's
    // `exports:` list saw `result: string` and the YAML-flat args shape — losing the
    // typed-surface upgrade across the trailmap boundary. Fix: pre-analyze every trailmap once
    // and walk the dep closure (same shape as `collectTrailmapTypedScriptedTools`) when
    // building each consumer's override map.
    val bun = BunBinaryResolver.resolveBunBinary()
    val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir()
    val shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir)
    val tsjsg = sdkDir?.let { File(it, "node_modules/ts-json-schema-generator") }
    assumeTrue(
      "bun + extract-tool-defs.mjs + ts-json-schema-generator must be available; " +
        "run `bun install` under sdks/typescript.",
      bun != null && shim != null && sdkDir != null && tsjsg?.isDirectory == true,
    )
    val analyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun!!,
      extractorShim = shim!!,
      sdkDir = sdkDir!!,
    )

    val libTrailmapDir = newTrailmapDir("lib_pack")
    val libToolsDir = File(libTrailmapDir, "tools").apply { mkdirs() }
    File(libToolsDir, "shared_demo.ts").writeText(typedDemoFixture)

    val appTrailmapDir = newTrailmapDir("app_pack")
    // App trailmap has no typed tools of its own — only imports `typed_demo` via the lib's
    // `exports:` list. Without the cross-trailmap fix, the app's emitted trailblaze-client.d.ts would
    // carry the typed_demo entry with `result: string` instead of the real DemoOutput.
    val libTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "lib_pack",
        target = TrailmapTargetConfig(displayName = "Lib"),
        exports = listOf("typed_demo"),
      ),
      source = TrailmapSource.Filesystem(libTrailmapDir),
      target = AppTargetYamlConfig(
        id = "lib_pack",
        displayName = "Lib",
        tools = listOf(
          InlineScriptToolConfig(
            script = File(libToolsDir, "shared_demo.ts").absolutePath,
            name = "typed_demo",
            description = "YAML-DERIVED-SHOULD-BE-OVERRIDDEN-BY-ANALYZER",
            inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
          ),
        ),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "app_pack",
        target = TrailmapTargetConfig(displayName = "App"),
        dependencies = listOf("lib_pack"),
      ),
      source = TrailmapSource.Filesystem(appTrailmapDir),
      target = AppTargetYamlConfig(
        id = "app_pack",
        displayName = "App",
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    PerTrailmapClientDtsEmitter.emit(listOf(libTrailmap, appTrailmap), analyzer = analyzer)

    val appRendered = Files.readString(File(appTrailmapDir, "tools/trailblaze-client.d.ts").toPath())
    // The app trailmap inherits `typed_demo` from the lib's `exports:`. Its emitted typed
    // surface must carry the analyzer-derived shapes, not the YAML-flat fallback.
    assertTrue("expected typed_demo in app's trailblaze-client.d.ts (cross-trailmap): $appRendered") {
      appRendered.contains("typed_demo: {")
    }
    assertTrue("expected analyzer TSDoc carries across trailmaps: $appRendered") {
      appRendered.contains("Typed-authoring demo.")
    }
    assertTrue("expected typed args message field cross-trailmap: $appRendered") {
      appRendered.contains("message: string;")
    }
    assertFalse("typed_demo's result must be typed cross-trailmap, not `string`: $appRendered") {
      toolEntryBlock(appRendered, "typed_demo").contains("result: string;")
    }
    assertTrue("expected typed result formatted field cross-trailmap: $appRendered") {
      appRendered.contains("formatted: string;")
    }
  }

  @Test
  fun `analyzer ScriptedToolDefinitionException with partialTools still upgrades healthy tools`() {
    // Lead-dev review #3: `collectTypedToolOverridesForTrailmap`'s catch on
    // `ScriptedToolDefinitionException` uses `e.partialTools` to feed healthy tools
    // through while logging per-tool errors. Today's tests cover the happy path and
    // the null-analyzer degradation; this fills the gap on the exception-with-partial
    // branch using a test-double analyzer.
    val trailmapDir = newTrailmapDir("partial_pack")
    val trailmap = stubTrailmap("partial_pack", trailmapDir, scriptedToolName = "healthy_tool")

    val analyzer = stubAnalyzer { _ ->
      throw ScriptedToolDefinitionException(
        message = "stubbed exception with partials",
        errors = listOf(
          xyz.block.trailblaze.scripting.ScriptedToolDefinitionError(
            file = "/fake/path/broken.ts",
            toolName = "broken_tool",
            message = "test-injected per-tool error",
          ),
        ),
        partialTools = listOf(
          ScriptedToolDefinition(
            name = "healthy_tool",
            sourcePath = "/fake/path/healthy.ts",
            line = 1,
            description = "Analyzer-described tool (partial-extraction survivor).",
            inputSchema = buildJsonObject {
              put("type", JsonPrimitive("object"))
              put(
                "properties",
                buildJsonObject {
                  put("q", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
              )
              put("required", buildJsonArray { add(JsonPrimitive("q")) })
            },
            outputSchema = buildJsonObject {
              put("type", JsonPrimitive("object"))
              put(
                "properties",
                buildJsonObject {
                  put("ok", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                },
              )
              put("required", buildJsonArray { add(JsonPrimitive("ok")) })
            },
          ),
        ),
      )
    }

    PerTrailmapClientDtsEmitter.emit(listOf(trailmap), analyzer = analyzer)
    val rendered = Files.readString(File(trailmapDir, "tools/trailblaze-client.d.ts").toPath())
    // Healthy tool from partialTools should still surface its typed shape.
    assertTrue("expected typed args from partialTools: $rendered") { rendered.contains("q: string;") }
    assertTrue("expected typed result from partialTools: $rendered") { rendered.contains("ok: boolean;") }
    assertFalse("healthy_tool's result must be typed, not `string`: $rendered") {
      toolEntryBlock(rendered, "healthy_tool").contains("result: string;")
    }
  }

  @Test
  fun `generic Throwable from analyzer degrades to YAML-flat shape without aborting emit`() {
    // Lead-dev review #3 (b): the belt-and-suspenders catch on `Throwable`
    // (subprocess-launch IOException, programmer errors, etc.) must keep emit() running
    // with YAML-flat shapes — losing typed upgrades for that trailmap but not failing the
    // whole codegen. Pinned with a stub analyzer that throws a non-typed exception.
    val trailmapDir = newTrailmapDir("throwable_pack")
    val trailmap = stubTrailmap("throwable_pack", trailmapDir, scriptedToolName = "foo")
    val analyzer = stubAnalyzer { _ ->
      throw RuntimeException("test-injected generic failure")
    }

    PerTrailmapClientDtsEmitter.emit(listOf(trailmap), analyzer = analyzer)
    val rendered = Files.readString(File(trailmapDir, "tools/trailblaze-client.d.ts").toPath())
    // emit() didn't abort — the file exists and carries the YAML-derived flat shape.
    assertTrue("expected foo entry: $rendered") { rendered.contains("foo: {") }
    assertTrue("expected fallback `result: string;`: $rendered") { rendered.contains("result: string;") }
  }

  /**
   * Build a minimal trailmap with one scripted tool keyed by [scriptedToolName] so test
   * call-sites focus on the analyzer's interaction rather than fiddly trailmap shape.
   */
  private fun stubTrailmap(id: String, trailmapDir: File, scriptedToolName: String): ResolvedTrailmap {
    File(trailmapDir, "tools").mkdirs()
    return ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(id = id, target = TrailmapTargetConfig(displayName = id)),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(
        id = id,
        displayName = id,
        tools = listOf(
          InlineScriptToolConfig(
            script = "./tools/$scriptedToolName.ts",
            name = scriptedToolName,
            description = "stub",
            inputSchema = buildJsonObject {
              put("type", JsonPrimitive("object"))
              put("properties", buildJsonObject { /* none */ })
            },
          ),
        ),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
  }

  /**
   * Construct a [ScriptedToolDefinitionAnalyzer] whose [analyze] is replaced by [body].
   * Goes through a real instance whose `bunBinary` / `extractorShim` / `sdkDir` point at
   * non-existent files — the override bypasses all of them so no subprocess launches.
   *
   * The analyzer class + `analyze` method are marked `open` to support this test seam.
   * The override is exercised only by tests; production code paths invoke `analyze`
   * normally and go through the JVM-default vtable dispatch with negligible overhead.
   */
  private fun stubAnalyzer(body: (java.io.File) -> List<ScriptedToolDefinition>): ScriptedToolDefinitionAnalyzer {
    return object : ScriptedToolDefinitionAnalyzer(
      bunBinary = File("/nonexistent/bun"),
      extractorShim = File("/nonexistent/shim.mjs"),
      sdkDir = File("/nonexistent/sdk"),
    ) {
      override suspend fun analyze(trailmapToolsDir: File): List<ScriptedToolDefinition> = body(trailmapToolsDir)
    }
  }

  private fun newTrailmapDir(id: String): File {
    val parent = createTempDirectory("per-trailmap-client-dts-test").toFile()
    tempDirs += parent
    return File(parent, id).apply { mkdirs() }
  }

  /**
   * Extract a single tool's `<name>: { … }` entry block from a rendered `trailblaze-client.d.ts`,
   * for assertions that must be SCOPED to one tool. Since the recordable surface is now injected into
   * every binding (each framework tool renders `result: string;`), a whole-file `contains("result:
   * string;")` check would spuriously match a framework tool — scope to the tool under test instead.
   * The entry closes at the first 4-space-indented `};` after the opener (nested `args:` / `result:`
   * objects close at deeper indentation).
   */
  private fun toolEntryBlock(rendered: String, toolName: String): String {
    val start = rendered.indexOf("    $toolName: {")
    assertTrue("expected an entry for `$toolName` in:\n$rendered") { start >= 0 }
    val end = rendered.indexOf("\n    };", start)
    assertTrue("expected a closing `};` for the `$toolName` entry") { end >= 0 }
    return rendered.substring(start, end)
  }
}
