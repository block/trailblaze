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

  @Test fun `setActiveToolSets routes through driver-aware resolver when driverType set`() {
    // Pre-fix, both branches of setActiveToolSets called the non-driver-aware
    // `TrailblazeToolSetCatalog.resolve`, so a Playwright-bound session that asked the
    // LLM to enable `core_interaction` (always_enabled, drivers: [android-*, ios-host])
    // would end up with `tap`/`swipe`/`launchApp` registered. Post-fix, the
    // `driverType` field routes through `resolveForSession` → `resolveForDriver`, which
    // filters out catalog entries whose `compatibleDriverTypes` don't include the
    // session driver.
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val coreInteractionEntry = catalog.firstOrNull { it.id == "core_interaction" }
      ?: error("core_interaction toolset missing from catalog — test fixture is stale")
    val coreInteractionToolClasses = coreInteractionEntry.toolClasses
    val coreInteractionToolClassNames = coreInteractionToolClasses
      .map { it.toolName().toolName }
      .toSet()

    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = catalog,
      driverType = xyz.block.trailblaze.devices.TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    )
    val ack = repo.setActiveToolSets(listOf("core_interaction"))
    assertThat(ack).contains("[driver=PLAYWRIGHT_NATIVE]")

    val registeredClasses = repo.getRegisteredTrailblazeTools()
    val registeredClassNames = registeredClasses.map { it.toolName().toolName }.toSet()
    // No tool class from core_interaction should have made it into the repo because the
    // toolset declares drivers that exclude playwright-native.
    val leaks = registeredClasses.intersect(coreInteractionToolClasses)
    assertThat(leaks.isEmpty()).isEqualTo(true)
    val nameLeaks = registeredClassNames.intersect(coreInteractionToolClassNames)
    assertThat(nameLeaks.isEmpty()).isEqualTo(true)
  }

  @Test fun `setActiveToolSets preserves dynamic tools across switches`() {
    // Derive a valid toolset id from the default catalog rather than hardcoding — if the
    // catalog evolves (rename / removal) this test keeps exercising the preservation
    // invariant instead of breaking for unrelated reasons.
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val switchableId = catalog.first { !it.alwaysEnabled }.id

    val repo = TrailblazeToolRepo.withDynamicToolSets(
      customToolClasses = setOf(TapTrailblazeTool::class),
      catalog = catalog,
    )
    repo.addDynamicTools(listOf(stubRegistration("subprocess_foo")))
    val beforeSwap = repo.getRegisteredDynamicTools().keys.toList()

    val ack = repo.setActiveToolSets(listOf(switchableId))
    assertThat(ack).contains("Active tool sets updated")
    assertThat(ack).contains("Total tools available")

    assertThat(repo.getRegisteredDynamicTools().keys.toList()).isEqualTo(beforeSwap)
  }

  @Test fun `scripted dynamic tools are advertised only when their toolset is active`() {
    // P1: the bundling layer registers a dynamic tool for EVERY catalog scripted tool at session
    // start (so late activation can dispatch), but a scripted tool is only ADVERTISED when its
    // toolset is active. Here a single opt-in toolset delivers `canary_scripted` by name.
    val scriptedName = ToolName("canary_scripted")
    val catalog = listOf(
      ToolSetCatalogEntry(
        id = "canary",
        description = "canary",
        toolClasses = emptySet(),
        scriptedToolNames = setOf(scriptedName),
        alwaysEnabled = false,
      ),
    )
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = catalog)
    repo.addDynamicTools(listOf(stubRegistration(scriptedName.toolName)))

    // Inactive toolset: registered (dispatchable) but NOT advertised to the LLM.
    assertThat(repo.getRegisteredDynamicTools().keys).contains(scriptedName)
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).doesNotContain(scriptedName.toolName)
    // Still dispatchable directly — e.g. a recorded-trail replay that bypasses advertisement.
    assertThat(repo.toolCallToTrailblazeTool(scriptedName.toolName, "{}"))
      .isInstanceOf(StubDynamicTool::class)

    // Activate the toolset → now advertised.
    repo.setActiveToolSets(listOf("canary"))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains(scriptedName.toolName)

    // Deactivate → hidden again, but still registered + dispatchable.
    repo.setActiveToolSets(emptyList())
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).doesNotContain(scriptedName.toolName)
    assertThat(repo.getRegisteredDynamicTools().keys).contains(scriptedName)
  }

  @Test fun `non-scripted dynamic tools are advertised regardless of active toolsets`() {
    // A subprocess-MCP / target-declared dynamic tool isn't a catalog scripted name, so the gate
    // never hides it — it's advertised even with no opt-in toolsets active.
    val repo = TrailblazeToolRepo.withDynamicToolSets(
      catalog = TrailblazeToolSetCatalog.defaultEntries(),
    )
    repo.addDynamicTools(listOf(stubRegistration("subprocess_mcp_tool")))
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains("subprocess_mcp_tool")
    repo.setActiveToolSets(emptyList())
    assertThat(repo.getCurrentToolDescriptors().map { it.name }).contains("subprocess_mcp_tool")
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
  ): DynamicTrailblazeToolRegistration =
    object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName(toolName)
      override val trailblazeDescriptor: TrailblazeToolDescriptor =
        TrailblazeToolDescriptor(name = toolName, source = source)

      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> = error("buildKoogTool not used in these tests")

      override fun decodeToolCall(argumentsJson: String): TrailblazeTool =
        StubDynamicTool(argumentsJson)
    }

}
