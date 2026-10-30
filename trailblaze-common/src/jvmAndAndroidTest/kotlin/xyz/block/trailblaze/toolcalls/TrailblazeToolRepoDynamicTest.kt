package xyz.block.trailblaze.toolcalls

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import assertk.assertions.prop
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import kotlin.test.Test

class TrailblazeToolRepoDynamicTest {

  private fun newRepo() = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "empty",
      toolClasses = emptySet(),
    ),
  )

  @Test fun `addDynamicTools registers a new source`() {
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("myapp_login")))
    assertThat(repo.getRegisteredDynamicTools().keys.toList())
      .containsExactly(ToolName("myapp_login"))
  }

  @Test fun `getCurrentToolDescriptors includes dynamic tools alongside KClass tools`() {
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "has-tap",
        toolClasses = setOf(TapTrailblazeTool::class),
      ),
    )
    repo.addDynamicTools(listOf(stubRegistration("external_fetch")))
    val names = repo.getCurrentToolDescriptors().map { it.name }
    assertThat(names).contains("external_fetch")
  }

  @Test fun `getCurrentTrailblazeToolDescriptors preserves source metadata`() {
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "has-tap",
        toolClasses = setOf(TapTrailblazeTool::class),
      ),
    )
    repo.addDynamicTools(
      listOf(
        stubRegistration(
          "external_fetch",
          source = trailblazeToolSourceForScript("/tmp/external_fetch.ts"),
        )
      )
    )

    val descriptors = repo.getCurrentTrailblazeToolDescriptors().associateBy { it.name }
    assertThat(descriptors[TapTrailblazeTool::class.toolName().toolName]?.source?.type)
      .isEqualTo(TrailblazeToolSourceType.KOTLIN)
    assertThat(descriptors["external_fetch"]?.source?.type)
      .isEqualTo(TrailblazeToolSourceType.TYPESCRIPT)
    assertThat(descriptors["external_fetch"]?.source?.scriptPath)
      .isEqualTo("/tmp/external_fetch.ts")
  }

  @Test fun `duplicate dynamic registration errors`() {
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("dup")))
    assertFailure {
      repo.addDynamicTools(listOf(stubRegistration("dup")))
    }.messageContains("already registered")
  }

  @Test fun `collision with existing Kotlin tool errors with both names`() {
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "has-tap",
        toolClasses = setOf(TapTrailblazeTool::class),
      ),
    )
    val tapName = TapTrailblazeTool::class.toolName().toolName
    assertFailure {
      repo.addDynamicTools(listOf(stubRegistration(tapName)))
    }.messageContains("Kotlin-registered tool")
  }

  @Test fun `toolCallToTrailblazeTool dispatches dynamic tools before KClass lookup`() {
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("search")))
    val decoded = repo.toolCallToTrailblazeTool("search", """{"q":"kotlin"}""")
    assertThat(decoded)
      .isInstanceOf(StubDynamicTool::class)
      .prop(StubDynamicTool::args)
      .isEqualTo("""{"q":"kotlin"}""")
  }

  @Test fun `removeDynamicTool drops the registration`() {
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("temp")))
    repo.removeDynamicTool(ToolName("temp"))
    assertThat(repo.getRegisteredDynamicTools()).isEqualTo(emptyMap())
  }

  @Test fun `removeAllTrailblazeTools clears dynamic tools too`() {
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("a"), stubRegistration("b")))
    repo.removeAllTrailblazeTools()
    assertThat(repo.getRegisteredDynamicTools()).isEqualTo(emptyMap())
  }

  @Test fun `batch collision rolls back the whole addDynamicTools call`() {
    val repo = newRepo()
    // Include a duplicate deep in the list — validation should see it before any insert
    // happens so the earlier registrations don't leak into the repo.
    assertFailure {
      repo.addDynamicTools(
        listOf(
          stubRegistration("a"),
          stubRegistration("b"),
          stubRegistration("a"),
        ),
      )
    }.messageContains("appears twice")
    assertThat(repo.getRegisteredDynamicTools()).isEqualTo(emptyMap())
  }

  @Test fun `batch collision against existing dynamic tool leaves repo unchanged`() {
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("a")))
    // Include a legitimate new name alongside a colliding one — the new name must not land.
    assertFailure {
      repo.addDynamicTools(listOf(stubRegistration("b"), stubRegistration("a")))
    }.messageContains("already registered")
    assertThat(repo.getRegisteredDynamicTools().keys.toList()).containsExactly(ToolName("a"))
  }

  @Test fun `toolCallToTrailblazeTool resolves a class-backed tool with surfaceToLlm = false`() {
    // Regression for the lookup change in #2634: previously the repo matched class-backed
    // tools via `toKoogToolDescriptor()?.name`, which returns null for `surfaceToLlm = false`
    // tools (they're not surfaced to the LLM). Now it matches via `toolName().toolName`
    // straight from the `@TrailblazeToolClass` annotation, so non-LLM-visible tools stay
    // reachable through the repo for recording-replay paths that never go through the LLM.
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "non-llm",
        toolClasses = setOf(NonLlmTool::class),
      ),
    )
    val decoded = repo.toolCallToTrailblazeTool(
      toolName = NonLlmTool::class.toolName().toolName,
      toolContent = """{"reason":"replay"}""",
    )
    assertThat(decoded).isInstanceOf(NonLlmTool::class)
    assertThat((decoded as NonLlmTool).reason).isEqualTo("replay")
  }

  @Test fun `an excluded scripted tool is registered and dispatchable but never advertised`() {
    // The scripted-tool advertisement gate now enforces `excluded_tools:`. The bundling layer
    // registers a dynamic tool for EVERY catalog scripted tool (so recorded replays can dispatch),
    // but an excluded scripted name is subtracted from the repo's registered set, so
    // `advertisedDynamic` hides it from the LLM while it stays dispatchable by name.
    val scriptedName = ToolName("canary_scripted")
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "canary",
        description = "canary",
        toolClasses = emptySet(),
        scriptedToolNames = setOf(scriptedName),
        alwaysEnabled = true,
      ),
    )
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = catalog,
      excludedScriptedToolNames = setOf(scriptedName),
    )
    repo.addDynamicTools(listOf(stubRegistration(scriptedName.toolName)))

    // Excluded: registered (dispatchable) but NOT advertised to the LLM.
    assertThat(repo.getRegisteredDynamicTools().keys).contains(scriptedName)
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).doesNotContain(scriptedName.toolName)
    // Still dispatchable directly — e.g. a recorded-trail replay that bypasses advertisement.
    assertThat(repo.toolCallToTrailblazeTool(scriptedName.toolName, "{}"))
      .isInstanceOf(StubDynamicTool::class)
  }

  @Test fun `a non-excluded catalog scripted tool is advertised up front`() {
    // The counterpart to the exclusion test: with no exclusion, a catalog scripted tool the
    // bundler registered is advertised immediately — there is no opt-in step.
    val scriptedName = ToolName("canary_scripted")
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "canary",
        description = "canary",
        toolClasses = emptySet(),
        scriptedToolNames = setOf(scriptedName),
        alwaysEnabled = true,
      ),
    )
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = catalog)
    repo.addDynamicTools(listOf(stubRegistration(scriptedName.toolName)))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains(scriptedName.toolName)
  }

  @Test fun `non-scripted dynamic tools are always advertised`() {
    // A subprocess-MCP / target-declared dynamic tool isn't a catalog scripted name, so the
    // exclusion gate never hides it — it's always advertised.
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = TrailblazeToolSetCatalog.defaultEntries(),
    )
    repo.addDynamicTools(listOf(stubRegistration("subprocess_mcp_tool")))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains("subprocess_mcp_tool")
  }

  @Test fun `an excluded target-declared scripted tool is dispatchable but never advertised`() {
    // Not a catalog tool: a trailmap's own `.ts` tool, on its target unlisted and opted out of
    // with `excluded_tools:`.
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = TrailblazeToolSetCatalog.defaultEntries(),
      excludedScriptedToolNames = setOf(ToolName("target_tool")),
    )
    repo.addDynamicTools(listOf(stubRegistration("target_tool"), stubRegistration("other_target_tool")))

    val advertised = repo.getCurrentToolDescriptors().map { it.name }
    assertThat(advertised).doesNotContain("target_tool")
    assertThat(advertised).contains("other_target_tool")
    assertThat(repo.toolCallToTrailblazeTool("target_tool", "{}")).isInstanceOf(StubDynamicTool::class)
  }

  @Test fun `hideScriptedToolsFromLlm hides a tool on a repo built without exclusions`() {
    // A launcher hides its target's opted-out tools whatever repo it registers them into.
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("target_tool"), stubRegistration("other_target_tool")))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains("target_tool")

    repo.hideScriptedToolsFromLlm(setOf(ToolName("target_tool")))

    val advertised = repo.getCurrentToolDescriptors().map { it.name }
    assertThat(advertised).doesNotContain("target_tool")
    assertThat(advertised).contains("other_target_tool")
    assertThat(repo.toolCallToTrailblazeTool("target_tool", "{}")).isInstanceOf(StubDynamicTool::class)

    repo.showScriptedToolsToLlm(setOf(ToolName("target_tool")))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains("target_tool")
  }

  @Test fun `showScriptedToolsToLlm advertises a shared tool whose registered copy is hidden`() {
    // Another target registered its `surfaceToLlm: false` copy first; a target that offers the tool shows it.
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("shared_tool", surfaceToLlm = false)))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).doesNotContain("shared_tool")

    repo.showScriptedToolsToLlm(setOf(ToolName("shared_tool")))

    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains("shared_tool")
  }

  @Test fun `a removed tool's show and hide overrides don't carry over to its next registration`() {
    val repo = newRepo()
    repo.addDynamicTools(listOf(stubRegistration("shown_tool"), stubRegistration("hidden_tool")))
    repo.showScriptedToolsToLlm(setOf(ToolName("shown_tool")))
    repo.hideScriptedToolsFromLlm(setOf(ToolName("hidden_tool")))

    repo.removeDynamicTool(ToolName("shown_tool"))
    repo.removeDynamicTool(ToolName("hidden_tool"))
    repo.addDynamicTools(
      listOf(stubRegistration("shown_tool", surfaceToLlm = false), stubRegistration("hidden_tool")),
    )

    val advertised = repo.getCurrentToolDescriptors().map { it.name }
    assertThat(advertised).doesNotContain("shown_tool")
    assertThat(advertised).contains("hidden_tool")
  }

  @Test fun `showScriptedToolsToLlm never overrides the exclusions the repo was built with`() {
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = TrailblazeToolSetCatalog.defaultEntries(),
      excludedScriptedToolNames = setOf(ToolName("target_tool")),
    )
    repo.addDynamicTools(listOf(stubRegistration("target_tool")))

    repo.showScriptedToolsToLlm(setOf(ToolName("target_tool")))

    assertThat(repo.getCurrentToolDescriptors().map { it.name }).doesNotContain("target_tool")
  }

  @Test fun `withDynamicToolSets subtracts excluded scripted names from the always-enabled surface`() {
    // The on-device / host-runner exclusion fix relies on excludedScriptedToolNames being
    // subtracted from the ALWAYS-ENABLED catalog surface — not just from caller-supplied custom
    // sets. That's exactly where a tool like `openUrl` is re-added, so pre-subtracting it upstream
    // isn't enough; the param must reach the initial composition. Pinned here against an
    // always-enabled fixture toolset.
    val scriptedName = ToolName("always_scripted")
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "always_on",
        description = "always-enabled fixture",
        toolClasses = emptySet(),
        scriptedToolNames = setOf(scriptedName),
        alwaysEnabled = true,
      ),
    )

    // Baseline: it surfaces from the always-enabled toolset, so the exclusion assertion below
    // isn't vacuous.
    val baseline = TrailblazeToolRepo.withDynamicToolSets(catalog = catalog)
    assertThat(baseline.getRegisteredScriptedToolNames()).contains(scriptedName)

    // Excluded: subtracted from the initial surface.
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = catalog,
      excludedScriptedToolNames = setOf(scriptedName),
    )
    assertThat(repo.getRegisteredScriptedToolNames()).doesNotContain(scriptedName)
  }

  @Test fun `a surfaceToLlm = false dynamic registration is dispatchable but never advertised`() {
    // The scripted-tool internal-step gate: a registration declaring `surfaceToLlm = false`
    // (e.g. a launch-step composed by a parent tool) stays registered + dispatchable by name +
    // resolvable for recorded replays, but `advertisedDynamic` drops it from the LLM menu —
    // independent of toolset activity (it isn't a catalog scripted name here).
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = TrailblazeToolSetCatalog.defaultEntries(),
    )
    repo.addDynamicTools(listOf(stubRegistration("internal_step", surfaceToLlm = false)))

    // Registered + dispatchable, but hidden from the advertised descriptor set.
    assertThat(repo.getRegisteredDynamicTools().keys).contains(ToolName("internal_step"))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).doesNotContain("internal_step")
    assertThat(repo.toolCallToTrailblazeTool("internal_step", "{}"))
      .isInstanceOf(StubDynamicTool::class)

    // A sibling surfaceToLlm = true registration IS advertised — proving the gate is per-tool.
    repo.addDynamicTools(listOf(stubRegistration("visible_tool")))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains("visible_tool")
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).doesNotContain("internal_step")
  }

  @Test fun `the executor-backed tool registry also excludes surfaceToLlm = false dynamic tools`() {
    // Regression: the executor overload (the KOOG_STRATEGY_GRAPH in-process path) previously
    // registered `snapshot.dynamic.values` directly, so a `surfaceToLlm = false` scripted internal
    // step leaked into the LLM's menu there even though the other overload's gate hid it. It now
    // routes through `advertisedDynamic()` too.
    //
    // The stub's `buildKoogTool` throws; `advertisedDynamic()` is empty for a `surfaceToLlm = false`
    // registration, so the executor registry builds WITHOUT calling it. Before the fix, the
    // `snapshot.dynamic.values` path would have called `buildKoogTool` and thrown — so a clean build
    // here that omits the tool's name proves it was filtered before koog-tool construction.
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "has-tap",
        toolClasses = setOf(TapTrailblazeTool::class),
      ),
    )
    repo.addDynamicTools(listOf(stubRegistration("internal_step", surfaceToLlm = false)))

    val registry = repo.asToolRegistry(
      toolDispatcher = { "" },
      // Only invoked for advertised dynamic tools — none here, so it must never fire.
      trailblazeToolContextProvider = { error("context provider must not be needed — no advertised dynamic tools") },
    )
    val names = registry.tools.map { it.descriptor.name }
    assertThat(names).doesNotContain("internal_step")
    // The class-backed tool is still present — the registry built fine, it just dropped the hidden one.
    assertThat(names).contains(TapTrailblazeTool::class.toolName().toolName)
  }

  // --- toolSetIds scope ---

  /**
   * Catalog with one always-enabled entry, one entry a caller would declare, and one it wouldn't —
   * the three cases `toolSetIds` has to separate.
   */
  private fun scopeCatalog() = listOf(
    ToolSetCatalogEntry(
      id = "framework",
      description = "always-enabled framework surface",
      toolClasses = setOf(TapTrailblazeTool::class),
      alwaysEnabled = true,
    ),
    ToolSetCatalogEntry(
      id = "declared",
      description = "a toolset the target's trailmap declares",
      toolClasses = emptySet(),
      scriptedToolNames = setOf(ToolName("declared_scripted")),
    ),
    ToolSetCatalogEntry(
      id = "someone_elses",
      description = "another app's toolset",
      toolClasses = emptySet(),
      scriptedToolNames = setOf(ToolName("other_scripted")),
    ),
  )

  @Test fun `toolSetIds limits the surface to the declared toolsets plus always-enabled ones`() {
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = scopeCatalog(),
      toolSetIds = listOf("declared"),
    )
    assertThat(repo.getRegisteredScriptedToolNames()).contains(ToolName("declared_scripted"))
    // Always-enabled entries ride along without being declared — that's their contract.
    assertThat(repo.getRegisteredTrailblazeTools()).contains(TapTrailblazeTool::class)
    // Another app's toolset does not.
    assertThat(repo.getRegisteredScriptedToolNames()).doesNotContain(ToolName("other_scripted"))
  }

  @Test fun `toolSetIds also scopes which scripted tools the bundling layer is asked to load`() {
    // allCatalogScriptedToolNames is what the on-device / daemon launchers bundle and register.
    // Leaving it catalog-wide while the advertised surface narrowed would keep paying to load
    // undeclared toolsets' bundles, and `advertisedDynamic` would then advertise every tool they
    // register.
    val scoped = TrailblazeToolRepo.withDynamicToolSets(
      catalog = scopeCatalog(),
      toolSetIds = listOf("declared"),
    )
    assertThat(scoped.allCatalogScriptedToolNames.toList()).containsExactly(ToolName("declared_scripted"))
  }

  @Test fun `catalog scripted names resolve for a companion driver the repo's own driver excludes`() {
    // A browser-launched session's repo is bound to PLAYWRIGHT_NATIVE and scoped to its target's
    // web toolsets, but its iOS companion's recorded `openUrl` needs the mobile-only toolset too.
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "navigation",
        description = "mobile-only navigation",
        toolClasses = emptySet(),
        scriptedToolNames = setOf(ToolName("openUrl")),
        compatibleDriverTypes = setOf(TrailblazeDriverType.IOS_HOST),
      ),
      ToolSetCatalogEntry(
        id = "web_core",
        description = "web-only",
        toolClasses = emptySet(),
        scriptedToolNames = setOf(ToolName("web_scripted")),
        compatibleDriverTypes = setOf(TrailblazeDriverType.PLAYWRIGHT_NATIVE),
      ),
    )
    val webRepo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = catalog,
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      toolSetIds = listOf("web_core"),
    )

    assertThat(webRepo.allCatalogScriptedToolNames.toList()).containsExactly(ToolName("web_scripted"))
    assertThat(webRepo.widenCatalogToDriver(TrailblazeDriverType.IOS_HOST).toList())
      .containsExactly(ToolName("openUrl"))
  }

  @Test fun `a companion driver's catalog scripted tool is dispatchable but not advertised`() {
    // Widening the catalog for a companion must not route its names around the advertisement
    // gate: one the target excluded, or the repo never advertised, stays hidden from the LLM.
    val openUrl = ToolName("openUrl")
    val webRepo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = listOf(
        ToolSetCatalogEntry(
          id = "navigation",
          description = "mobile-only navigation",
          toolClasses = emptySet(),
          scriptedToolNames = setOf(openUrl),
          compatibleDriverTypes = setOf(TrailblazeDriverType.IOS_HOST),
        ),
      ),
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      toolSetIds = listOf("navigation"),
      excludedScriptedToolNames = setOf(openUrl),
    )
    webRepo.widenCatalogToDriver(TrailblazeDriverType.IOS_HOST)
    webRepo.addDynamicTools(listOf(stubRegistration(openUrl.toolName)))

    assertThat(webRepo.getCurrentToolDescriptors().map { it.name }).doesNotContain(openUrl.toolName)
    assertThat(webRepo.toolCallToTrailblazeTool(openUrl.toolName, "{}")).isInstanceOf(StubDynamicTool::class)
  }

  @Test fun `omitting toolSetIds keeps the whole-catalog surface`() {
    // Callers with no target in hand (test fixtures, driver-agnostic construction) still get
    // everything — the scope is opt-in, so this change can't silently narrow them.
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = scopeCatalog())
    assertThat(repo.getRegisteredScriptedToolNames()).contains(ToolName("other_scripted"))
    assertThat(repo.allCatalogScriptedToolNames).contains(ToolName("other_scripted"))
  }

  // --- helpers ---

  @Serializable
  private data class StubDynamicTool(val args: String) : TrailblazeTool

  /**
   * Stand-in for a non-LLM-visible class-backed tool (e.g. `TapOnByElementSelector`). Has
   * `surfaceToLlm = false` so its koog descriptor is null — the lookup fix in
   * `toolCallToTrailblazeTool` must reach it via the `@TrailblazeToolClass(name)` annotation
   * directly, not via `toKoogToolDescriptor()`.
   */
  @Serializable
  @TrailblazeToolClass(name = "test_non_llm", surfaceToLlm = false)
  private data class NonLlmTool(val reason: String) : TrailblazeTool

  private fun stubRegistration(
    toolName: String,
    source: TrailblazeToolSourceDescriptor? = null,
    surfaceToLlm: Boolean = true,
  ): DynamicTrailblazeToolRegistration =
    object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName(toolName)
      override val trailblazeDescriptor: TrailblazeToolDescriptor =
        TrailblazeToolDescriptor(name = toolName, source = source)
      override val surfaceToLlm: Boolean = surfaceToLlm

      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> = error("buildKoogTool not used in these tests")

      override fun decodeToolCall(argumentsJson: String): TrailblazeTool =
        StubDynamicTool(argumentsJson)
    }

}
