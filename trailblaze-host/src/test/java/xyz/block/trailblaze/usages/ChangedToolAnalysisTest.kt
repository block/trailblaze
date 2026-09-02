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
 * tools by the injected fingerprint, the two sides are only ever compared at the SAME strength, and
 * anything that can't be compared counts as MODIFIED (fail open — a tool that can't be compared
 * must never silently read as unchanged) with a warning naming it and the failing side(s).
 */
class ChangedToolAnalysisTest {

  /** A side that bundled cleanly: its import closure resolved, so the strong comparison applies. */
  private fun bundled(key: String) = ToolFingerprint(closure = key, bytes = "bytes-of-$key")

  /** A side whose import closure could NOT be resolved — only the tool's own bytes are known. */
  private fun bytesOnly(key: String) = ToolFingerprint(closure = null, bytes = key)

  /** A side that couldn't be fingerprinted at all (unreadable script or descriptor). */
  private val unreadable = ToolFingerprint(closure = null, bytes = null)

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
      if (source.script.name == "edited.ts") bundled("hash-of-${source.script.path}") else bundled("same")
    }

    assertEquals(listOf("demo_new"), result.added)
    assertEquals(listOf("demo_gone"), result.removed)
    assertEquals(listOf("demo_edited"), result.modified, "demo_stays hashes identically and must not appear")
    assertTrue(result.diagnostics.isEmpty(), "no diagnostics expected: ${result.diagnostics}")
  }

  @Test
  fun `a tool that moved files but hashes identically is unchanged`() {
    val base = snapshot("demo_moved" to "/old/place.ts")
    val current = snapshot("demo_moved" to "/new/place.ts")

    val result = ChangedToolAnalysis.compute(base, current) { _, _ -> bundled("identical") }

    assertTrue(result.modified.isEmpty() && result.added.isEmpty() && result.removed.isEmpty())
  }

  @Test
  fun `an unhashable side counts as modified and is named in a warning`() {
    val base = snapshot("demo_ok" to "/a/ok.ts", "demo_broken" to "/a/broken.ts")
    val current = snapshot("demo_ok" to "/b/ok.ts", "demo_broken" to "/b/broken.ts")

    val result = ChangedToolAnalysis.compute(base, current) { source, _ ->
      if (source.script.name == "broken.ts" && source.script.path.startsWith("/a/")) unreadable else bundled("same")
    }

    assertEquals(listOf("demo_broken"), result.modified, "fail open: uncomparable must not read as unchanged")
    val diagnostic = result.diagnostics.single()
    assertEquals(UsagesDiagnostic.TOOL_COMPARISON_DEGRADED, diagnostic.kind)
    assertEquals("demo_broken", diagnostic.subject, "the subject is the bare tool name consumers key on")
    val warning = diagnostic.message
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

    val result = ChangedToolAnalysis.compute(base, current) { _, _ -> unreadable }

    assertEquals(listOf("demo_broken"), result.modified)
    assertTrue(
      result.diagnostics.single().message.contains("either side"),
      "blaming only 'base' when both sides failed misleads debugging: ${result.diagnostics}",
    )
  }

  @Test
  fun `a one-sided bundling failure reads as incomparable, not as a detected edit`() {
    val base = snapshot("demo_tool" to "/a/tool.ts")
    val current = snapshot("demo_tool" to "/b/tool.ts")

    // The ref side of a real comparison: a plain git checkout that can't resolve the workspace's
    // gitignored SDK, so nothing there bundles while everything in the working tree does. The two
    // fingerprints then measure different things, and comparing them directly flags EVERY tool on
    // EVERY run — a consumer reading `modified` as "these tools were edited" is misled into
    // replaying the whole suite.
    val result = ChangedToolAnalysis.compute(base, current) { source, _ ->
      if (source.script.path.startsWith("/a/")) bytesOnly("same-bytes") else bundled("closure-key")
    }

    assertEquals(listOf("demo_tool"), result.modified, "fail open: uncomparable must not read as unchanged")
    val warning = result.diagnostics.single().message
    assertTrue(
      warning.contains("the base side") && warning.contains("not comparable") &&
        warning.contains("not a detected edit"),
      "the report must distinguish 'could not compare' from 'was edited', or a consumer builds " +
        "its replay set out of comparison failures: $warning",
    )
  }

  @Test
  fun `the unreadable side is blamed, not the merely closure-less one`() {
    val base = snapshot("demo_tool" to "/a/tool.ts")
    val current = snapshot("demo_tool" to "/b/tool.ts")

    // Base has bytes but no closure; current has nothing at all. Current is what stopped the
    // comparison, so naming base sends whoever triages this warning to the wrong side.
    val result = ChangedToolAnalysis.compute(base, current) { source, _ ->
      if (source.script.path.startsWith("/a/")) bytesOnly("known-bytes") else unreadable
    }

    assertEquals(listOf("demo_tool"), result.modified)
    assertTrue(
      result.diagnostics.single().message.contains("the current side"),
      "the side that could not be read at all is the one that stopped the comparison: ${result.diagnostics}",
    )
  }

  @Test
  fun `when neither side bundles, identical bytes still read as unchanged`() {
    val base = snapshot("demo_subprocess" to "/a/sub.ts")
    val current = snapshot("demo_subprocess" to "/b/sub.ts")

    // Symmetric degradation — e.g. a `runtime: subprocess` tool importing Node built-ins, which
    // fails to bundle on both sides. Both sides fall back the same way, so the weaker comparison
    // is still a comparison.
    val result = ChangedToolAnalysis.compute(base, current) { _, _ -> bytesOnly("identical-bytes") }

    assertTrue(result.modified.isEmpty(), "a tool that degrades on BOTH sides is still comparable")
    assertTrue(
      result.diagnostics.single().message.contains("an edit to a file it imports will not flag it"),
      "the blind spot has to be stated — this comparison cannot see shared-helper edits: ${result.diagnostics}",
    )
  }

  @Test
  fun `when neither side bundles, differing bytes read as modified`() {
    val base = snapshot("demo_subprocess" to "/a/sub.ts")
    val current = snapshot("demo_subprocess" to "/b/sub.ts")

    val result = ChangedToolAnalysis.compute(base, current) { source, _ ->
      bytesOnly("bytes-of-${source.script.path}")
    }

    assertEquals(listOf("demo_subprocess"), result.modified)
  }

  @Test
  fun `snapshot warnings carry through prefixed by side, classified as an inventory gap`() {
    val result = ChangedToolAnalysis.compute(
      snapshot(warnings = listOf("bad descriptor")),
      snapshot(warnings = listOf("ambiguous ts")),
    ) { _, _ -> bundled("unused") }

    assertEquals(listOf("base: bad descriptor", "current: ambiguous ts"), result.diagnostics.map { it.message })
    // The SIDE is the subject, because an inventory gap on one side is what makes a tool read as
    // added or removed — and which side it happened on is what tells those two answers apart.
    assertEquals(listOf("base", "current"), result.diagnostics.map { it.subject })
    assertEquals(
      listOf(UsagesDiagnostic.TOOL_INVENTORY_INCOMPLETE, UsagesDiagnostic.TOOL_INVENTORY_INCOMPLETE),
      result.diagnostics.map { it.kind },
    )
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

    assertEquals(setOf(ToolKey("local", "demo_tracked")), filtered.snapshot.toolSources.keys)
    val diagnostic = filtered.diagnostics.single()
    assertTrue(
      diagnostic.message.contains("'staged'") && diagnostic.message.contains("demo_stagedA") &&
        diagnostic.message.contains("demo_stagedB"),
      "the report must say what it could not compare, not silently narrow: ${diagnostic.message}",
    )
    // A DELIBERATE exclusion, not a defective scan: the action is "validate these in their owning
    // repo", where an inventory gap's action is "fix the descriptor". Same list, different kinds.
    assertEquals(UsagesDiagnostic.TOOL_COMPARISON_EXCLUDED, diagnostic.kind)
    assertEquals("staged", diagnostic.subject, "the subject is the trailmap whose tools were excluded")
    assertTrue(
      filtered.snapshot.warnings.isEmpty(),
      "the exclusion must NOT ride in the snapshot's own warnings — compute() classifies those as " +
        "an inventory gap on the current side, which is a different failure with a different fix",
    )
  }

  @Test
  fun `no ignored scripts leaves the snapshot untouched`() {
    val snapshot = snapshot("demo_tool" to "/ws/trailmaps/m/tools/t.ts")
    val filtered = ChangedToolAnalysis.excludeRefInvisible(snapshot, emptySet())
    assertEquals(snapshot, filtered.snapshot)
    assertTrue(filtered.diagnostics.isEmpty())
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
      if (source.script.path.contains("map-b")) bundled("hash-of-${source.script.path}") else bundled("stable")
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

    val result = ChangedToolAnalysis.compute(base, current) { _, _ -> bundled("byte-identical") }

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
