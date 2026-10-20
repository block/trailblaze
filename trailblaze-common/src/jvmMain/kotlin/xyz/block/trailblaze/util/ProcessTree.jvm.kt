package xyz.block.trailblaze.util

internal actual fun Process.destroyTreeForcibly() {
  // Listed before the kill: once this process is gone its children are orphans it no longer lists.
  val descendants = descendants().toList()
  destroyForcibly()
  descendants.forEach { it.destroyForcibly() }
}
