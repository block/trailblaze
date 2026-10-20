package xyz.block.trailblaze.util

// Android has no API that lists a process's children.
internal actual fun Process.destroyTreeForcibly() {
  destroyForcibly()
}
