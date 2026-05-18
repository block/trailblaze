package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of [TrailCommand.planTrailExecution] and [TrailCommand.expandTrailFiles].
 * These are the single source of truth for how `trailblaze trail` resolves its arguments into
 * an executable workload — directory expansion, `--tags` filtering, and `skip:` classification.
 * The downstream per-file loop in both `call()` and `delegateToDaemon()` is a single iteration
 * over the plan's output, so anything the planner mis-classifies surfaces as a CI regression in
 * the actual runner.
 */
class TrailCommandPlanTrailExecutionTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun writeTrail(
    name: String,
    dir: File = tempFolder.root,
    title: String = name,
    tags: List<String>? = null,
    skip: String? = null,
  ): File {
    dir.mkdirs()
    val file = File(dir, name)
    val configLines = buildString {
      appendLine("- config:")
      appendLine("    title: $title")
      appendLine("    platform: android")
      if (tags != null) appendLine("    tags: [${tags.joinToString(", ")}]")
      if (skip != null) appendLine("    skip: \"$skip\"")
      appendLine("- tools:")
      appendLine("  - pressBack: {}")
    }
    file.writeText(configLines)
    return file
  }

  @Test
  fun `expandTrailFiles leaves plain files alone and recurses into directories`() {
    val topLevel = writeTrail("top.trail.yaml")
    val nestedDir = File(tempFolder.root, "nested").apply { mkdirs() }
    val deepDir = File(nestedDir, "deep").apply { mkdirs() }
    writeTrail("nested.trail.yaml", dir = nestedDir)
    writeTrail("blaze.yaml", dir = deepDir)
    writeTrail("not-a-trail.yaml", dir = nestedDir)  // unrelated yaml, must be ignored

    val expanded = TrailCommand.expandTrailFiles(listOf(topLevel, nestedDir))

    val names = expanded.map { it.name }.sorted()
    assertEquals(listOf("blaze.yaml", "nested.trail.yaml", "top.trail.yaml"), names)
  }

  @Test
  fun `expandTrailFiles skips the workspace config trailblaze yaml under any config subdir`() {
    // `TrailRecordings.isTrailFile` returns true for `trailblaze.yaml` (it's an NL-definition
    // alias for `blaze.yaml`). But by workspace convention the workspace *config* lives at
    // `trails/config/trailblaze.yaml` — picking that up as a runnable trail would mis-execute
    // the config file. The expander must exclude it specifically. Other `trailblaze.yaml`
    // files outside a `config/` directory still pass through (they're real NL definitions).
    val workspaceConfigDir = File(tempFolder.root, "config").apply { mkdirs() }
    File(workspaceConfigDir, "trailblaze.yaml").writeText("defaults:\n  target: sample\n")
    val realTrail = writeTrail("real.trail.yaml")
    val nlTrailOutsideConfig = writeTrail("trailblaze.yaml")  // a real NL trail, NOT under config/

    val expanded = TrailCommand.expandTrailFiles(listOf(tempFolder.root))

    val names = expanded.map { it.canonicalPath }.sorted()
    assertEquals(
      listOf(nlTrailOutsideConfig.canonicalPath, realTrail.canonicalPath).sorted(),
      names,
      "workspace config should be excluded; NL trail at the root should be kept",
    )
  }

  @Test
  fun `expandTrailFiles deduplicates overlapping inputs`() {
    val foo = writeTrail("foo.trail.yaml")

    // Pass the file twice and a containing directory — should yield foo.trail.yaml exactly once.
    val expanded = TrailCommand.expandTrailFiles(listOf(foo, foo, tempFolder.root))
    assertEquals(1, expanded.size)
    assertEquals("foo.trail.yaml", expanded.first().name)
  }

  @Test
  fun `no filters - every expanded trail becomes a Run item, no skips, no filtered`() {
    val a = writeTrail("a.trail.yaml")
    val b = writeTrail("b.trail.yaml")

    val plan = TrailCommand.planTrailExecution(
      files = listOf(a, b),
      includeTags = emptyList(),
    )

    assertEquals(2, plan.items.size)
    plan.items.forEach { assertIs<TrailExecutionItem.Run>(it) }
    assertEquals(0, plan.filteredOutByTag)
  }

  @Test
  fun `--tags includes only trails whose tags overlap - untagged trails are filtered out`() {
    val smokeTrail = writeTrail("smoke.trail.yaml", tags = listOf("smoke", "login"))
    val regressionTrail = writeTrail("regression.trail.yaml", tags = listOf("regression"))
    val untagged = writeTrail("untagged.trail.yaml")

    val plan = TrailCommand.planTrailExecution(
      files = listOf(smokeTrail, regressionTrail, untagged),
      includeTags = listOf("smoke"),
    )

    assertEquals(1, plan.items.size)
    assertEquals("smoke.trail.yaml", plan.items.first().file.name)
    assertEquals(2, plan.filteredOutByTag)
  }

  @Test
  fun `comma-separated tags behave the same as repeated tags (OR semantics)`() {
    // The CLI flag parsing turns `--tags smoke,login` into List["smoke", "login"]; the planner
    // applies OR — a trail tagged with EITHER tag passes the filter.
    val smoke = writeTrail("s.trail.yaml", tags = listOf("smoke"))
    val login = writeTrail("l.trail.yaml", tags = listOf("login"))
    val other = writeTrail("o.trail.yaml", tags = listOf("other"))

    val plan = TrailCommand.planTrailExecution(
      files = listOf(smoke, login, other),
      includeTags = listOf("smoke", "login"),
    )

    val runNames = plan.items.map { it.file.name }.sorted()
    assertEquals(listOf("l.trail.yaml", "s.trail.yaml"), runNames)
    assertEquals(1, plan.filteredOutByTag)
  }

  @Test
  fun `skip with reason becomes a Skip item - blank skip stays as Run`() {
    val skipped = writeTrail("skipped.trail.yaml", skip = "see #2194")
    val blankSkip = writeTrail("blank.trail.yaml", skip = "")
    val normal = writeTrail("normal.trail.yaml")

    val plan = TrailCommand.planTrailExecution(
      files = listOf(skipped, blankSkip, normal),
      includeTags = emptyList(),
    )

    assertEquals(3, plan.items.size)
    val byName = plan.items.associateBy { it.file.name }
    val skipItem = byName["skipped.trail.yaml"]
    assertIs<TrailExecutionItem.Skip>(skipItem)
    assertEquals("see #2194", skipItem.reason)
    assertIs<TrailExecutionItem.Run>(byName["blank.trail.yaml"])
    assertIs<TrailExecutionItem.Run>(byName["normal.trail.yaml"])
  }

  @Test
  fun `directory argument expands and filter-then-classify works end-to-end`() {
    val dir = File(tempFolder.root, "trails").apply { mkdirs() }
    writeTrail("a.trail.yaml", dir = dir, tags = listOf("smoke"))
    writeTrail("b.trail.yaml", dir = dir, tags = listOf("smoke"), skip = "blocked on infra")
    writeTrail("c.trail.yaml", dir = dir, tags = listOf("regression"))

    val plan = TrailCommand.planTrailExecution(
      files = listOf(dir),
      includeTags = listOf("smoke"),
    )

    // a → Run (smoke, no skip)
    // b → Skip (smoke, has skip:)
    // c → filtered (no smoke)
    assertEquals(2, plan.items.size)
    val byName = plan.items.associateBy { it.file.name }
    assertIs<TrailExecutionItem.Run>(byName["a.trail.yaml"])
    val bItem = byName["b.trail.yaml"]
    assertIs<TrailExecutionItem.Skip>(bItem)
    assertEquals("blocked on infra", bItem.reason)
    assertEquals(1, plan.filteredOutByTag)
  }

  @Test
  fun `unparseable trail is treated as untagged unskipped Run - runner surfaces the actual error`() {
    val malformed = File(tempFolder.root, "broken.trail.yaml")
    malformed.writeText("not: a: valid: trail: at: all\n  nesting: chaos\n")

    val plan = TrailCommand.planTrailExecution(
      files = listOf(malformed),
      includeTags = emptyList(),
    )

    assertEquals(1, plan.items.size)
    assertIs<TrailExecutionItem.Run>(plan.items.first())
  }

  @Test
  fun `empty input or fully-filtered result produces an empty plan, not an error`() {
    val emptyDir = File(tempFolder.root, "empty").apply { mkdirs() }

    val plan = TrailCommand.planTrailExecution(
      files = listOf(emptyDir),
      includeTags = emptyList(),
    )

    assertTrue(plan.items.isEmpty())
    assertEquals(0, plan.filteredOutByTag)
  }
}
