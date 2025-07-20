package xyz.block.trailblaze.tools

import ai.koog.agents.core.tools.DirectToolCallsEnabler
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolArgs
import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool

@OptIn(InternalAgentToolsApi::class)
class KoogToolRegistryTest {

  object MyEnabler : DirectToolCallsEnabler

  @Test
  fun test() = runBlocking {
    val trailblazeAgent = FakeTrailblazeAgent()
    val toolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        "Input Text Only",
        setOf(InputTextTrailblazeTool::class),
      ),
    )
    val toolRegistry = toolRepo.asToolRegistry({
      TrailblazeToolExecutionContext(
        trailblazeAgent = trailblazeAgent,
        llmResponseId = null,
        screenState = null,
      )
    })
    val inputTextTool = toolRegistry.getTool("inputText") as Tool<ToolArgs, ToolResult>
    println("Koog Tool: $inputTextTool")
    println("descriptor: ${inputTextTool.descriptor}")
    val trailblazeToolArgs = InputTextTrailblazeTool("hello world")
    val result = inputTextTool.execute(
      args = trailblazeToolArgs,
      enabler = MyEnabler,
    )
    println("Result: $result")
    println("InputTextTool args: $trailblazeToolArgs")
    println("Tools: " + toolRegistry.tools.map { it.name })
  }
}
