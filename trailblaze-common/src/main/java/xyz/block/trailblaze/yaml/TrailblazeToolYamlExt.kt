package xyz.block.trailblaze.yaml

import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor

fun fromTrailblazeTool(trailblazeTool: TrailblazeTool): TrailblazeToolYamlWrapper = TrailblazeToolYamlWrapper(
  name = if (trailblazeTool is OtherTrailblazeTool) {
    trailblazeTool.toolName
  } else {
    trailblazeTool::class.toKoogToolDescriptor().name
  },
  trailblazeTool = trailblazeTool,
)
