package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.Status
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.test.Test

/**
 * Unit tests for [resolveKoogObjectiveResult] — the pure mapping from the Koog agent's terminal
 * `objectiveStatus` outcome to a [TrailblazeToolResult]. Exercises every branch without standing up
 * the Koog graph.
 */
class KoogStrategyGraphHostRunnerTest {

  @Test
  fun `COMPLETED maps to Success carrying the explanation`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.COMPLETED,
      explanation = "All goals met",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("All goals met")
  }

  @Test
  fun `IN_PROGRESS maps to Success`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.IN_PROGRESS,
      explanation = "still going",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `FAILED maps to an error mentioning the explanation`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.FAILED,
      explanation = "could not find the button",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .contains("could not find the button")
  }

  @Test
  fun `null outcome (finished without objectiveStatus) maps to an error, not a hollow pass`() {
    val result = resolveKoogObjectiveResult(
      outcome = null,
      explanation = null,
      finalMessage = "agent stopped here",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    // Surfaces the agent's final message so the failure is debuggable.
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .contains("agent stopped here")
  }

  @Test
  fun `null explanation falls back to the final message`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.COMPLETED,
      explanation = null,
      finalMessage = "fell back to this",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("fell back to this")
  }

  // ---------------------------------------------------------------------------------------------
  // refreshKoogToolSurface — runtime toolset switching under the Koog strategy-graph agent.
  //
  // These exercise the device-free core of the mid-run switch: when a setActiveToolSets-style
  // ConfigTrailblazeTool changes the active toolsets, the live Koog ToolRegistry must gain the
  // newly-active tool (so it can dispatch) AND the returned advertised descriptors must reflect
  // the new surface (so the LLM sees it). Neither the dispatcher nor the context provider is
  // invoked here — the refresh only rebuilds the registry view and reads descriptors.
  // ---------------------------------------------------------------------------------------------

  /** Dispatcher/context-provider stand-ins; refreshKoogToolSurface never invokes them. */
  private val noopDispatcher: suspend (TrailblazeTool) -> String = { "unused" }
  private val unusedContextProvider: () -> TrailblazeToolExecutionContext =
    { error("context provider should not be invoked by refreshKoogToolSurface") }

  /**
   * Two-entry catalog: an always-on `core` carrying the completion tool (modeling the real `meta`
   * always-enabled toolset) and one opt-in `typing` toolset that gates the class-backed
   * [InputTextTrailblazeTool]. Mirrors the real meta/core + opt-in split without coupling the test
   * to the full default catalog's driver logic.
   */
  private fun typingCatalog() = listOf(
    ToolSetCatalogEntry(
      id = "core",
      description = "always-on core",
      toolClasses = setOf(ObjectiveStatusTrailblazeTool::class),
      alwaysEnabled = true,
    ),
    ToolSetCatalogEntry(
      id = "typing",
      description = "text entry tools",
      toolClasses = setOf(InputTextTrailblazeTool::class),
      alwaysEnabled = false,
    ),
  )

  @Test
  fun `refreshKoogToolSurface activates a gated tool — registers it for dispatch and advertises it`() {
    val gatedName = InputTextTrailblazeTool::class.toolName().toolName
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = typingCatalog())
    val liveRegistry = repo.asToolRegistry(
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )

    // Before activation: the gated tool is neither registered nor advertised.
    assertThat(liveRegistry.getToolOrNull(gatedName)).isNull()

    // The LLM enables the toolset (this is what the intercepted ConfigTrailblazeTool does).
    repo.setActiveToolSets(listOf("typing"))

    val advertised = refreshKoogToolSurface(
      toolRepo = repo,
      liveRegistry = liveRegistry,
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )

    // Execution: the live registry — the same instance the running agent's environment resolves
    // against — can now find the newly-active tool by name.
    assertThat(liveRegistry.getToolOrNull(gatedName)).isNotNull()
    // Advertisement: the returned descriptors include the newly-active tool...
    assertThat(advertised.map { it.name }).contains(gatedName)
    // ...and never drop the always-on completion tool.
    assertThat(advertised.map { it.name }).contains(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
  }

  @Test
  fun `refreshKoogToolSurface after a reset narrows advertisement but keeps the tool registered`() {
    val gatedName = InputTextTrailblazeTool::class.toolName().toolName
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = typingCatalog())
    val liveRegistry = repo.asToolRegistry(
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )

    // Activate, then reset back to core only (setActiveToolSets([])).
    repo.setActiveToolSets(listOf("typing"))
    refreshKoogToolSurface(repo, liveRegistry, noopDispatcher, unusedContextProvider)
    repo.setActiveToolSets(emptyList())
    val narrowed = refreshKoogToolSurface(repo, liveRegistry, noopDispatcher, unusedContextProvider)

    // Advertisement narrows — the reset hid the tool from the LLM again.
    assertThat(narrowed.map { it.name }).doesNotContain(gatedName)
    // But the registry retains it: ToolRegistry has no remove, and an unadvertised-but-registered
    // tool is harmless (the LLM never calls a tool it can't see).
    assertThat(liveRegistry.getToolOrNull(gatedName)).isNotNull()
  }

  @Test
  fun `refreshKoogToolSurface is idempotent — re-running with no change re-yields the same surface`() {
    val gatedName = InputTextTrailblazeTool::class.toolName().toolName
    val repo = TrailblazeToolRepo.withDynamicToolSets(catalog = typingCatalog())
    val liveRegistry = repo.asToolRegistry(
      toolDispatcher = noopDispatcher,
      trailblazeToolContextProvider = unusedContextProvider,
    )
    repo.setActiveToolSets(listOf("typing"))

    val first = refreshKoogToolSurface(repo, liveRegistry, noopDispatcher, unusedContextProvider)
    val firstRegistrySize = liveRegistry.tools.size
    val second = refreshKoogToolSurface(repo, liveRegistry, noopDispatcher, unusedContextProvider)

    // Same advertised name set both times.
    assertThat(second.map { it.name }.toSet()).isEqualTo(first.map { it.name }.toSet())
    assertThat(second.map { it.name }).contains(gatedName)
    // The registry top-up only adds names it doesn't already have, so a no-op refresh can't grow
    // the live registry with duplicates.
    assertThat(liveRegistry.tools.size).isEqualTo(firstRegistrySize)
  }
}
