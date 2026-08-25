package xyz.block.trailblaze.usages

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral contract of the changed-tool classification: presence is compared by NAME, common
 * tools by the injected hash, and a hash failure counts the tool as MODIFIED (fail open — a tool
 * that can't be compared must never silently read as unchanged) with a warning naming it and the
 * failing side(s).
 */
class ChangedToolAnalysisTest {

  /** Tools in a single trailmap, as (tool name -> script path). */
  private fun snapshot(
    vararg tools: Pair<String, String>,
    trailmap: String = "demo-map",
    warnings: List<String> = emptyList(),
  ) = ToolSourceSnapshot(
    tools.associate { (name, path) -> ToolKey(trailmap, name) to ToolSource(script = File(path)) },
    warnings,
  )

  /** Tools across trailmaps, as (trailmap, tool name, script path). */
  private fun multiTrailmapSnapshot(vararg tools: Triple<String, String, String>) =
    ToolSourceSnapshot(
      tools.associate { (trailmap, name, path) -> ToolKey(trailmap, name) to ToolSource(script = File(path)) },
    )

  @Test
  fun `classifies added, removed, and hash-differing tools`() {
    val base = snapshot("demo_stays" to "/a/stays.ts", "demo_edited" to "/a/edited.ts", "demo_gone" to "/a/gone.ts")
    val current = snapshot("demo_stays" to "/b/stays.ts", "demo_edited" to "/b/edited.ts", "demo_new" to "/b/new.ts")

    val result = ChangedToolAnalysis.compute(base, current) { source, _ ->
      if (source.script.name == "edited.ts") "hash-of-${source.script.path}" else "same"
    }

    assertEquals(listOf("demo_new"), result.added)
    assertEquals(listOf("demo_gone"), result.removed)
    assertEquals(listOf("demo_edited"), result.modified, "demo_stays hashes identically and must not appear")
    assertTrue(result.warnings.isEmpty(), "no warnings expected: ${result.warnings}")
  }

  @Test
  fun `a tool that moved files but hashes identically is unchanged`() {
    val base = snapshot("demo_moved" to "/old/place.ts")
    val current = snapshot("demo_moved" to "/new/place.ts")

    val result = ChangedToolAnalysis.compute(base, current) { _, _ -> "identical" }

    assertTrue(result.modified.isEmpty() && result.added.isEmpty() && result.removed.isEmpty())
  }

  @Test
  fun `an unhashable side counts as modified and is named in a warning`() {
    val base = snapshot("demo_ok" to "/a/ok.ts", "demo_broken" to "/a/broken.ts")
    val current = snapshot("demo_ok" to "/b/ok.ts", "demo_broken" to "/b/broken.ts")

    val result = ChangedToolAnalysis.compute(base, current) { source, _ ->
      if (source.script.name == "broken.ts" && source.script.path.startsWith("/a/")) null else "same"
    }

    assertEquals(listOf("demo_broken"), result.modified, "fail open: uncomparable must not read as unchanged")
    val warning = result.warnings.single()
    assertTrue(
      warning.contains("demo_broken") && warning.contains("the base side") && warning.contains("fail open"),
      "the warning must say WHICH side failed so a consumer can tell a real modification " +
        "from a comparison failure: $warning",
    )
  }

  @Test
  fun `both sides unhashable names both, not just base`() {
    val base = snapshot("demo_broken" to "/a/broken.ts")
    val current = snapshot("demo_broken" to "/b/broken.ts")

    val result = ChangedToolAnalysis.compute(base, current) { _, _ -> null }

    assertEquals(listOf("demo_broken"), result.modified)
    assertTrue(
      result.warnings.single().contains("either side"),
      "blaming only 'base' when both sides failed misleads debugging: ${result.warnings}",
    )
  }

  @Test
  fun `snapshot warnings carry through prefixed by side`() {
    val result = ChangedToolAnalysis.compute(
      snapshot(warnings = listOf("bad descriptor")),
      snapshot(warnings = listOf("ambiguous ts")),
    ) { _, _ -> "unused" }

    assertEquals(listOf("base: bad descriptor", "current: ambiguous ts"), result.warnings)
  }

  @Test
  fun `gitignored scripts are excluded with a per-trailmap warning naming the tools`() {
    val snapshot = multiTrailmapSnapshot(
      Triple("local", "demo_tracked", "/ws/trails/config/trailmaps/local/tools/tracked.ts"),
      Triple("staged", "demo_stagedA", "/ws/trails/config/trailmaps/staged/tools/a.ts"),
      Triple("staged", "demo_stagedB", "/ws/trails/config/trailmaps/staged/tools/b.ts"),
    )
    val ignored = setOf(
      File("/ws/trails/config/trailmaps/staged/tools/a.ts"),
      File("/ws/trails/config/trailmaps/staged/tools/b.ts"),
    )

    val filtered = ChangedToolAnalysis.excludeRefInvisible(snapshot, ignored)

    assertEquals(setOf(ToolKey("local", "demo_tracked")), filtered.toolSources.keys)
    val warning = filtered.warnings.single()
    assertTrue(
      warning.contains("'staged'") && warning.contains("demo_stagedA") && warning.contains("demo_stagedB"),
      "the report must say what it could not compare, not silently narrow: $warning",
    )
  }

  @Test
  fun `no ignored scripts leaves the snapshot untouched`() {
    val snapshot = snapshot("demo_tool" to "/ws/trailmaps/m/tools/t.ts")
    assertEquals(snapshot, ChangedToolAnalysis.excludeRefInvisible(snapshot, emptySet()))
  }

  @Test
  fun `two trailmaps may declare the same tool name and an edit to either one flags it`() {
    val base = multiTrailmapSnapshot(
      Triple("map-a", "demo_shared", "/base/map-a/tools/shared.ts"),
      Triple("map-b", "demo_shared", "/base/map-b/tools/shared.ts"),
    )
    val current = multiTrailmapSnapshot(
      Triple("map-a", "demo_shared", "/current/map-a/tools/shared.ts"),
      Triple("map-b", "demo_shared", "/current/map-b/tools/shared.ts"),
    )

    // map-a's copy is byte-identical across sides; only map-b's changed. A name-keyed inventory
    // would have dropped one of the two declarations, so an edit to the dropped one reported
    // nothing — and map-a sorting first means the comparison has to look past it to notice.
    val result = ChangedToolAnalysis.compute(base, current) { source, _ ->
      if (source.script.path.contains("map-b")) "hash-of-${source.script.path}" else "stable"
    }

    assertEquals(
      listOf("demo_shared"),
      result.modified,
      "the name must flag when ANY declaring trailmap's copy changed",
    )
    assertTrue(result.added.isEmpty() && result.removed.isEmpty(), "presence didn't change")
  }

  @Test
  fun `a tool that moved trailmaps is modified, not added and removed`() {
    val base = multiTrailmapSnapshot(Triple("map-a", "demo_moved", "/a/tools/moved.ts"))
    val current = multiTrailmapSnapshot(Triple("map-b", "demo_moved", "/b/tools/moved.ts"))

    val result = ChangedToolAnalysis.compute(base, current) { _, _ -> "byte-identical" }

    assertEquals(
      listOf("demo_moved"),
      result.modified,
      "which trailmap declares a name changes how a recorded usage dispatches, even byte-identical",
    )
    assertTrue(
      result.added.isEmpty() && result.removed.isEmpty(),
      "trails invoking it by name still resolve, so this is not a break: ${result.removed}",
    )
  }

  @Test
  fun `a descriptor-only edit changes the fingerprint even when the script key is identical`() {
    val dir = createTempDirectory("fingerprint-test").toFile()
    try {
      val descriptor = File(dir, "tool.yaml").apply { writeText("runtime: subprocess") }
      val before = ChangedToolAnalysis.composeFingerprint("same-script-key", descriptor)
      descriptor.writeText("runtime: inProcess")
      val after = ChangedToolAnalysis.composeFingerprint("same-script-key", descriptor)

      assertNotEquals(
        before,
        after,
        "descriptor fields like runtime/inputSchema change how usages dispatch without touching script bytes",
      )
      assertEquals(
        "same-script-key",
        ChangedToolAnalysis.composeFingerprint("same-script-key", null),
        "a descriptor-less tool's fingerprint is the script key alone",
      )
      assertNull(
        ChangedToolAnalysis.composeFingerprint("same-script-key", File(dir, "missing.yaml")),
        "an unreadable descriptor must fail open (null), not silently fingerprint as descriptor-less",
      )
    } finally {
      dir.deleteRecursively()
    }
  }
}
