package xyz.block.trailblaze.tools

import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.ClearTextTrailblazeTool
import xyz.block.trailblaze.util.Console

@OptIn(InternalAgentToolsApi::class)
class KoogToolRegistryTest {

  @Test
  fun test() {
    val trailblazeAgent = FakeTrailblazeAgent()
    val toolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        "Clear Text Only",
        setOf(ClearTextTrailblazeTool::class),
      ),
    )
    val toolRegistry = toolRepo.asToolRegistry({
      TrailblazeToolExecutionContext(
        traceId = null,
        screenState = null,
        trailblazeDeviceInfo = trailblazeAgent.trailblazeDeviceInfoProvider(),
        sessionProvider = trailblazeAgent.sessionProvider,
        trailblazeLogger = trailblazeAgent.trailblazeLogger,
        memory = trailblazeAgent.memory,
        maestroTrailblazeAgent = trailblazeAgent,
      )
    })
    val clearTextTool = toolRegistry.getTool("clearText")
    Console.log("Koog Tool: $clearTextTool")
    Console.log("descriptor: ${clearTextTool.descriptor}")
    val trailblazeToolArgs = ClearTextTrailblazeTool
    val result = runBlocking {
      clearTextTool.executeUnsafe(args = trailblazeToolArgs)
    }
    Console.log("Result: $result")
    Console.log("ClearTextTool args: $trailblazeToolArgs")
    Console.log("Tools: " + toolRegistry.tools.map { it.name })
  }
}
