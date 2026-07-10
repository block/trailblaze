package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertSame
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Regression coverage for [selectResolutionTree]: a dual-tree migration capture carries both a
 * `driverMigrationTreeNode` (always accessibility-shape) and the primary `trailblazeNodeTree`
 * (refs assigned, but possibly Maestro-shape). `--at` / `--maestro-selector` want the migration
 * tree; `--ref` must stay on the primary tree because refs are never applied to the migration
 * tree (see `MigrationTreeCapture` / `CompactScreenElements.applyRefsToTree`). Pinned here as a
 * pure function: without this, `--ref` silently reported "not found" against dual-tree migration
 * logs once the migration tree became the default.
 */
class WaypointSuggestSelectorCommandTest {

  private val migrationTree = TrailblazeNode(nodeId = 1, driverDetail = DriverNodeDetail.AndroidAccessibility())
  private val primaryTree = TrailblazeNode(nodeId = 2, ref = "a1", driverDetail = DriverNodeDetail.AndroidAccessibility())

  @Test
  fun `ref lookups always use the primary tree, even when a migration tree is present`() {
    assertSame(primaryTree, selectResolutionTree(migrationTree, primaryTree, refRequested = true))
  }

  @Test
  fun `at and maestro-selector lookups prefer the migration tree when present`() {
    assertSame(migrationTree, selectResolutionTree(migrationTree, primaryTree, refRequested = false))
  }

  @Test
  fun `at and maestro-selector lookups fall back to the primary tree when there is no migration tree`() {
    assertSame(primaryTree, selectResolutionTree(null, primaryTree, refRequested = false))
  }

  @Test
  fun `ref lookups with no primary tree return null even if a migration tree exists`() {
    assertSame(null, selectResolutionTree(migrationTree, null, refRequested = true))
  }
}
