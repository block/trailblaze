package xyz.block.trailblaze.toolcalls

interface Trail {
  /** The prompt that represents the work done by this [Trail]. */
  fun prompt(): String
}
