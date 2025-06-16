package xyz.block.trailblaze.logs.model

import xyz.block.trailblaze.toolcalls.TrailblazeTool

interface HasTrailblazeTool {
  /**
   * We should rename this to `trailblazeTool` but
   * are keeping it as `command` for backwards compatibility in logging.
   */
  val command: TrailblazeTool
}
