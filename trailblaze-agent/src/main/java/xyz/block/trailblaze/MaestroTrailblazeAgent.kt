package xyz.block.trailblaze

import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.findBestTapTrailblazeToolForNode

abstract class MaestroTrailblazeAgent : TrailblazeAgent {
  /**
   * This will allow you to handle custom [TrailblazeTool]s that are not directly mapped to Maestro commands.
   */
  open val customTrailblazeToolHandler: (TrailblazeToolExecutionContext) -> TrailblazeToolResult? = { null }

  abstract fun runMaestroYaml(
    yaml: String,
    llmResponseId: String? = null,
  ): TrailblazeToolResult

  protected abstract fun executeMaestroCommands(
    commands: List<Command>,
    llmResponseId: String?,
  ): TrailblazeToolResult

  fun runMaestroCommands(
    maestroCommands: List<Command>,
    llmResponseId: String? = null,
  ): TrailblazeToolResult {
    maestroCommands.forEach { command ->
      val result = executeMaestroCommands(
        commands = listOf(command),
        llmResponseId = llmResponseId,
      )
      if (result != TrailblazeToolResult.Success) {
        return result
      }
    }
    return TrailblazeToolResult.Success
  }

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    llmResponseId: String?,
    screenState: ScreenState?,
  ): Pair<List<TrailblazeTool>, TrailblazeToolResult> {
    val updatedTools = mutableListOf<TrailblazeTool>()
    tools.forEach { trailblazeTool ->
      updatedTools.add(trailblazeTool)
      val result = when (trailblazeTool) {
        is MapsToMaestroCommands -> {
          runMaestroCommands(
            trailblazeTool.toMaestroCommands(),
            llmResponseId,
          )
        }

        is ExecutableTrailblazeTool -> {
          trailblazeTool.execute(
            TrailblazeToolExecutionContext(
              trailblazeTool = trailblazeTool,
              screenState = screenState,
              llmResponseId = llmResponseId,
              trailblazeAgentProvider = { this },
            ),
          )
        }

        is TapOnElementByNodeIdTrailblazeTool -> {
          var response: TrailblazeToolResult = TrailblazeToolResult.Error.UnknownTrailblazeTool(trailblazeTool)
          if (screenState?.viewHierarchy != null) {
            println("Full View Hierarchy:\n" + prettyPrintViewHierarchy(screenState.viewHierarchy))
            println("TapOnElementByNodeId: Looking for nodeId=${trailblazeTool.nodeId}")
            val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchy) {
              it.nodeId == trailblazeTool.nodeId
            }
            if (matchingNode != null) {
              println("TapOnElementByNodeId: Found node: text='${matchingNode.text}', accessibilityText='${matchingNode.accessibilityText}', bounds=${matchingNode.bounds}")
              val tool = findBestTapTrailblazeToolForNode(
                screenState.viewHierarchyOriginal,
                matchingNode,
                trailblazeTool.longPress,
              )
              println("Selected TrailblazeTool: $tool")
              updatedTools.add(tool)
              val commands = (tool as MapsToMaestroCommands).toMaestroCommands()
              response = runMaestroCommands(
                maestroCommands = commands,
                llmResponseId = llmResponseId,
              )
            } else {
              println("TapOnElementByNodeId: No node found with nodeId=${trailblazeTool.nodeId}")
            }
          }
          response
        }

        else -> {
          customTrailblazeToolHandler(
            TrailblazeToolExecutionContext(
              trailblazeTool = trailblazeTool,
              screenState = screenState,
              llmResponseId = llmResponseId,
              trailblazeAgentProvider = { this },
            ),
          ) ?: TrailblazeToolResult.Error.UnknownTrailblazeTool(
            trailblazeTool,
          )
        }
      }
      if (result != TrailblazeToolResult.Success) return updatedTools to result
    }
    return updatedTools to TrailblazeToolResult.Success
  }

  private fun prettyPrintViewHierarchy(
    node: ViewHierarchyTreeNode,
    indent: String = "",
  ): String {
    val builder = StringBuilder()
    builder.append(
      "$indent- nodeId=${node.nodeId}, text='${node.text}', accessibilityText='${node.accessibilityText}', bounds=${node.bounds}\n",
    )
    node.children.forEach { child ->
      builder.append(prettyPrintViewHierarchy(child, "$indent  "))
    }
    return builder.toString()
  }
}
