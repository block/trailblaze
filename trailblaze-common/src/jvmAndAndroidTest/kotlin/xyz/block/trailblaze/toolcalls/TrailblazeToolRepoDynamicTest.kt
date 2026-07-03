package xyz.block.trailblaze.toolcalls

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import assertk.assertions.prop
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import kotlin.test.Test

class TrailblazeToolRepoDynamicTest {

  private fun newRepo() = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "empty",
      toolClasses = emptySet(),
      yamlToolNames = emptySet(),
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
        yamlToolNames = emptySet(),
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

  @Test fun `withDynamicToolSets subtracts excluded YAML and scripted names from the always-enabled surface`() {
    // The on-device / host-runner exclusion fix relies on excludedYamlToolNames /
    // excludedScriptedToolNames being subtracted from the ALWAYS-ENABLED catalog surface — not just
    // from caller-supplied custom sets. That's exactly where a tool like `openUrl` is re-added, so
    // pre-subtracting it upstream isn't enough; the param must reach the initial composition. Pin
    // both partitions here against an always-enabled fixture toolset.
    val yamlName = ToolName("always_yaml")
    val scriptedName = ToolName("always_scripted")
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "always_on",
        description = "always-enabled fixture",
        toolClasses = emptySet(),
        yamlToolNames = setOf(yamlName),
        scriptedToolNames = setOf(scriptedName),
        alwaysEnabled = true,
      ),
    )

    // Baseline: both surface from the always-enabled toolset, so the exclusion assertions below
    // aren't vacuous.
    val baseline = TrailblazeToolRepo.withDynamicToolSets(catalog = catalog)
    assertThat(baseline.getRegisteredYamlToolNames()).contains(yamlName)
    assertThat(baseline.getRegisteredScriptedToolNames()).contains(scriptedName)

    // Excluded: each partition is subtracted from the initial surface.
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = catalog,
      excludedYamlToolNames = setOf(yamlName),
      excludedScriptedToolNames = setOf(scriptedName),
    )
    assertThat(repo.getRegisteredYamlToolNames()).doesNotContain(yamlName)
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
