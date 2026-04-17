package xyz.block.trailblaze.cli

internal sealed class PathSegment {
  data class Key(val name: String) : PathSegment()
  data class IndexedKey(val name: String, val index: Int) : PathSegment()
}
