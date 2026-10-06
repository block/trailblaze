package xyz.block.trailblaze.cli

import xyz.block.trailblaze.api.SelectorDialect
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * The one thing a trail migration is: rewrite every selector written in [source] into [target].
 *
 * Both dialects must live on the same platform. Nothing bridges platforms — a migration replays a
 * recorded selector against a capture of the SAME screen taken by a second driver on the same
 * device, and a cross-platform pair has no such capture — so an attempt to cross is a misuse, not
 * an unsupported case.
 */
data class MigrationPair(
  val source: SelectorDialect,
  val target: SelectorDialect,
) {
  init {
    require(source != target) {
      "Migration source and target are both `${source.yamlKey}`. A migration rewrites one dialect " +
        "into a different one; there is nothing to do when they match."
    }
    require(source.platform == target.platform) {
      "Migration pair `${source.yamlKey}` → `${target.yamlKey}` crosses platforms " +
        "(${source.platform.displayName} → ${target.platform.displayName}). A migration resolves the " +
        "recorded selector against one capture and rewrites it against a second capture of the same " +
        "screen, which only exists within one platform."
    }
  }

  override fun toString(): String = "${source.yamlKey} → ${target.yamlKey}"

  companion object {
    /**
     * The pair a dual-tree capture implies: the primary tree is the dialect the trail's selectors
     * were recorded in, and the migration side-channel tree is the dialect being migrated TO.
     *
     * Returns null when either tree is missing or the two don't form a valid pair (same dialect,
     * or different platforms) — callers that want the reason use [infer].
     */
    fun inferOrNull(primaryTree: TrailblazeNode?, secondaryTree: TrailblazeNode?): MigrationPair? {
      val source = primaryTree?.let { SelectorDialect.ofTree(it) } ?: return null
      val target = secondaryTree?.let { SelectorDialect.ofTree(it) } ?: return null
      if (source == target || source.platform != target.platform) return null
      return MigrationPair(source, target)
    }

    /**
     * [inferOrNull] with a reason: throws [IllegalArgumentException] naming what the capture
     * actually contained, so the CLI can report it instead of a bare "couldn't infer".
     */
    fun infer(primaryTree: TrailblazeNode?, secondaryTree: TrailblazeNode?): MigrationPair {
      requireNotNull(primaryTree) { "the capture carries no primary node tree" }
      requireNotNull(secondaryTree) { "the capture carries no migration side-channel tree" }
      return MigrationPair(SelectorDialect.ofTree(primaryTree), SelectorDialect.ofTree(secondaryTree))
    }
  }
}
