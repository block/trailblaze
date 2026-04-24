package xyz.block.trailblaze.yaml

import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.InstanceNamedTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName

fun fromTrailblazeTool(trailblazeTool: TrailblazeTool): TrailblazeToolYamlWrapper = TrailblazeToolYamlWrapper(
  name = when (trailblazeTool) {
    is OtherTrailblazeTool -> trailblazeTool.toolName
    is InstanceNamedTrailblazeTool -> trailblazeTool.instanceToolName
    else -> trailblazeTool::class.toolName().toolName
  },
  trailblazeTool = trailblazeTool,
)
