package xyz.block.trailblaze.toolcalls

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.contains
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

  @Test fun `toolCallToTrailblazeTool resolves a class-backed tool with isForLlm = false`() {
    // Regression for the lookup change in #2634: previously the repo matched class-backed
    // tools via `toKoogToolDescriptor()?.name`, which returns null for `isForLlm = false`
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

  // --- helpers ---

  @Serializable
  private data class StubDynamicTool(val args: String) : TrailblazeTool

  /**
   * Stand-in for a non-LLM-visible class-backed tool (e.g. `TapOnByElementSelector`). Has
   * `isForLlm = false` so its koog descriptor is null — the lookup fix in
   * `toolCallToTrailblazeTool` must reach it via the `@TrailblazeToolClass(name)` annotation
   * directly, not via `toKoogToolDescriptor()`.
   */
  @Serializable
  @TrailblazeToolClass(name = "test_non_llm", isForLlm = false)
  private data class NonLlmTool(val reason: String) : TrailblazeTool

  private fun stubRegistration(toolName: String): DynamicTrailblazeToolRegistration =
    object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName(toolName)
      override val trailblazeDescriptor: TrailblazeToolDescriptor =
        TrailblazeToolDescriptor(name = toolName)

      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> = error("buildKoogTool not used in these tests")

      override fun decodeToolCall(argumentsJson: String): TrailblazeTool =
        StubDynamicTool(argumentsJson)
    }

}
