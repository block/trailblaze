package xyz.block.trailblaze.trailrunner

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeClassifierLineage

class TrailIndexBuilderTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun v1Trail(
    id: String = "demo/login",
    title: String = "Demo login",
    target: String = "myapp",
    platform: String = "ios",
    tags: List<String> = listOf("smoke", "login"),
    steps: List<String> = listOf("Launch the app", "Assert money tab"),
    verify: Boolean = false,
  ): String = buildString {
    appendLine("- config:")
    appendLine("    id: $id")
    appendLine("    title: \"$title\"")
    appendLine("    target: $target")
    appendLine("    platform: $platform")
    if (tags.isNotEmpty()) {
      appendLine("    tags: [${tags.joinToString(", ")}]")
    }
    appendLine("- prompts:")
    for (step in steps) {
      if (verify) appendLine("  - verify: \"$step\"")
      else appendLine("  - step: \"$step\"")
    }
  }

  @Test
  fun `scan returns entry for a single trail file`() {
    val dir = tmp.newFolder("trails")
    File(dir, "login.trail.yaml").writeText(v1Trail())

    val results = TrailIndexBuilder.scan(dir)

    assertEquals(1, results.size)
    val entry = results.single()
    assertEquals("Demo login", entry.title)
    assertEquals("myapp", entry.target)
    assertEquals("ios", entry.platform)
    assertEquals(listOf("smoke", "login"), entry.tags)
    assertEquals(0, entry.rootIdx)
  }

  @Test
  fun `scan classifies on-disk format and carries the declared config id`() {
    // `format` drives the unified badge + the migrate affordance; `configId` is the trail's
    // DECLARED config.id (shared by per-platform variants), distinct from the path-derived entry.id.
    val dir = tmp.newFolder("trails-format-test")
    File(dir, "legacy.trail.yaml").writeText(v1Trail(id = "demo/login"))
    File(dir, "single.trail.yaml").writeText(
      """
      config:
        id: demo/unified
        title: Unified demo
      trail:
        - step: Open the app
      """.trimIndent(),
    )

    val byName = TrailIndexBuilder.scan(dir).associateBy { it.path.substringAfterLast('/') }

    val v1 = byName.getValue("legacy.trail.yaml")
    assertEquals("v1", v1.format)
    assertEquals("demo/login", v1.configId)
    val unified = byName.getValue("single.trail.yaml")
    assertEquals("unified", unified.format)
    assertEquals("demo/unified", unified.configId)
  }

  @Test
  fun `scan indexes a bare unified trail_yaml with directory-derived id and config title`() {
    // A migrated unified trail lives as a BARE `trail.yaml` (no `<device>` prefix), so it does
    // NOT end in `.trail.yaml`. It must still be indexed, taking its identity from the enclosing
    // directory the way `blaze.yaml` does.
    val dir = tmp.newFolder("trails")
    val caseDir = File(dir, "regression/suite_71172/section_946176/case_5374124").also { it.mkdirs() }
    File(caseDir, "trail.yaml").writeText(
      """
      config:
        id: regression/case_5374124
        title: Cold boot flow
        target: myapp
      trail:
        - step: Open the app
      """.trimIndent(),
    )

    val entry = TrailIndexBuilder.scan(dir).single()

    // The id strips only `.yaml` (like `blaze.yaml`), NOT the whole directory, so the browser's
    // `resolveTrailFile` reconstructs `.../case_5374124/trail.yaml` via its `<id>.yaml` probe.
    assertEquals("0/regression/suite_71172/section_946176/case_5374124/trail", entry.id)
    assertEquals("regression/suite_71172/section_946176/case_5374124/trail.yaml", entry.path)
    assertEquals("Cold boot flow", entry.title)
    assertEquals("myapp", entry.target)
    assertEquals("unified", entry.format)
    assertEquals("regression/case_5374124", entry.configId)
    assertEquals("trail", entry.kind)
  }

  @Test
  fun `a bare unified trail_yaml id round-trips through resolveTrailFile`() {
    // Guards the P1 contract: the emitted id must resolve back to the on-disk file, because the
    // browser's detail / save / open / reveal / tool-usage routes all resolve `entry.id` through
    // `resolveTrailFile`. A directory-only id would 404 (the resolver never probes `.../trail.yaml`).
    val dir = tmp.newFolder("trails")
    val caseDir = File(dir, "regression/case_5374124").also { it.mkdirs() }
    val bare = File(caseDir, "trail.yaml").apply {
      writeText(
        """
        config:
          id: regression/case_5374124
        trail:
          - step: Open the app
        """.trimIndent(),
      )
    }

    val entry = TrailIndexBuilder.scan(dir).single()
    val resolved = resolveTrailFile(entry.id.split("/"), primary = dir, extras = emptyList())

    assertEquals(bare.canonicalFile, resolved?.second?.canonicalFile)
  }

  @Test
  fun `scan indexes a root-level bare unified trail_yaml, deriving title from the scanned root`() {
    // A bare `trail.yaml` directly in the scanned root has an empty `folder`, so the title falls
    // back to the on-disk parent directory name and the id is just `0/trail`. Exercises the
    // `folder.ifEmpty { parentFile?.name }` branch, and confirms the id still round-trips.
    val dir = tmp.newFolder("cold-boot")
    val bare = File(dir, "trail.yaml").apply {
      writeText(
        """
        config:
          id: cold-boot
        trail:
          - step: Open the app
        """.trimIndent(),
      )
    }

    val entry = TrailIndexBuilder.scan(dir).single()

    assertEquals("0/trail", entry.id)
    assertEquals("trail.yaml", entry.path)
    assertEquals("cold boot", entry.title)
    assertEquals("unified", entry.format)
    val resolved = resolveTrailFile(entry.id.split("/"), primary = dir, extras = emptyList())
    assertEquals(bare.canonicalFile, resolved?.second?.canonicalFile)
  }

  @Test
  fun `scan emits separate entries for a bare trail_yaml and a sibling blaze_yaml`() {
    // Mid-migration a directory can hold both the NL definition (`blaze.yaml`) and the migrated
    // unified `trail.yaml`. The index surfaces each as its own row with a distinct id and kind — it
    // does not (yet) suppress the stale blaze entry in favor of the unified one.
    val root = tmp.newFolder("trails")
    val caseDir = File(root, "flows/checkout").also { it.mkdirs() }
    File(caseDir, "blaze.yaml").writeText(
      """
      config:
        id: flows/checkout
      trail:
        - step: Open the app
      """.trimIndent(),
    )
    File(caseDir, "trail.yaml").writeText(
      """
      config:
        id: flows/checkout
        title: Checkout
      trail:
        - step: Open the app
      """.trimIndent(),
    )

    val byKind = TrailIndexBuilder.scan(root).associateBy { it.kind }

    assertEquals(2, byKind.size)
    assertEquals("0/flows/checkout/blaze", byKind.getValue("blaze").id)
    assertEquals("0/flows/checkout/trail", byKind.getValue("trail").id)
  }

  @Test
  fun `scan derives a bare unified trail_yaml title from its directory when config omits title`() {
    val dir = tmp.newFolder("trails")
    val caseDir = File(dir, "flows/my-cold-boot").also { it.mkdirs() }
    File(caseDir, "trail.yaml").writeText(
      """
      config:
        id: flows/my-cold-boot
      trail:
        - step: Open the app
      """.trimIndent(),
    )

    val entry = TrailIndexBuilder.scan(dir).single()

    assertEquals("my cold boot", entry.title)
    assertEquals("0/flows/my-cold-boot/trail", entry.id)
    assertEquals("unified", entry.format)
  }

  @Test
  fun `scan derives id as 0-slash-relative-path-without-trail-yaml-suffix`() {
    val dir = tmp.newFolder("trails-id-test")
    val sub = File(dir, "myapp/cold-boot").also { it.mkdirs() }
    File(sub, "my-trail.trail.yaml").writeText(v1Trail(title = "Cold boot"))

    val results = TrailIndexBuilder.scan(dir)

    assertEquals(1, results.size)
    assertEquals("0/myapp/cold-boot/my-trail", results.single().id)
  }

  @Test
  fun `scan returns folder label derived from directory name`() {
    val dir = tmp.newFolder("trails")
    val sub = File(dir, "smoke").apply { mkdirs() }
    File(sub, "example.trail.yaml").writeText(v1Trail())

    val entry = TrailIndexBuilder.scan(dir).single()

    assertTrue(entry.folder.contains("smoke"), "expected 'smoke' in folder='${entry.folder}'")
  }

  @Test
  fun `scan returns filename-derived title when config has no title`() {
    val dir = tmp.newFolder("trails")
    File(dir, "my-cold-boot-test.trail.yaml").writeText(
      """
      - config:
          id: example
      - prompts:
        - step: Launch
      """.trimIndent(),
    )

    val entry = TrailIndexBuilder.scan(dir).single()

    assertEquals("my cold boot test", entry.title)
  }

  @Test
  fun `scan skips malformed yaml and emits a filename-derived entry`() {
    val dir = tmp.newFolder("trails")
    File(dir, "broken.trail.yaml").writeText(": this: is: not: valid: yaml:")

    val results = TrailIndexBuilder.scan(dir)
    assertEquals(1, results.size, "malformed trail should still produce an entry")
  }

  @Test
  fun `scan recurses into subdirectories`() {
    val dir = tmp.newFolder("trails")
    File(dir, "a").mkdirs()
    File(dir, "a/b").mkdirs()
    File(dir, "a/b/deep.trail.yaml").writeText(v1Trail(title = "Deep trail"))

    val results = TrailIndexBuilder.scan(dir)
    assertEquals(1, results.size)
    assertEquals("Deep trail", results.single().title)
  }

  @Test
  fun `scan returns empty list when directory contains no trail files`() {
    val dir = tmp.newFolder("empty")
    assertTrue(TrailIndexBuilder.scan(dir).isEmpty())
  }

  @Test
  fun `scan ignores non-trail files`() {
    val dir = tmp.newFolder("trails")
    File(dir, "not-a-trail.yaml").writeText("id: irrelevant")
    File(dir, "also-not.txt").writeText("nope")

    assertTrue(TrailIndexBuilder.scan(dir).isEmpty())
  }

  @Test
  fun `scanAll prefixes primary entries with rootIdx 0`() {
    val primary = tmp.newFolder("primary")
    File(primary, "trail-a.trail.yaml").writeText(v1Trail(title = "Trail A"))

    val results = TrailIndexBuilder.scanAll(primary = primary, extras = emptyList())

    assertEquals(1, results.size)
    assertEquals(0, results.single().rootIdx)
    assertTrue(results.single().id.startsWith("0/"), "id should start with '0/'")
  }

  @Test
  fun `scanAll prefixes extra root entries with rootIdx 1 onward`() {
    val primary = tmp.newFolder("primary")
    File(primary, "primary-trail.trail.yaml").writeText(v1Trail(title = "Primary trail"))

    val extra = tmp.newFolder("extra")
    File(extra, "extra-trail.trail.yaml").writeText(v1Trail(title = "Extra trail"))

    val results = TrailIndexBuilder.scanAll(primary = primary, extras = listOf(extra))

    val primaryEntry = results.first { it.title == "Primary trail" }
    val extraEntry = results.first { it.title == "Extra trail" }

    assertEquals(0, primaryEntry.rootIdx)
    assertEquals(1, extraEntry.rootIdx)
    assertTrue(extraEntry.id.startsWith("1/"), "extra entry id should start with '1/'")
  }

  @Test
  fun `scanAll combines trails from primary and extras`() {
    val primary = tmp.newFolder("primary")
    File(primary, "a.trail.yaml").writeText(v1Trail(title = "A"))
    File(primary, "b.trail.yaml").writeText(v1Trail(title = "B"))

    val extra = tmp.newFolder("extra")
    File(extra, "c.trail.yaml").writeText(v1Trail(title = "C"))

    val results = TrailIndexBuilder.scanAll(primary = primary, extras = listOf(extra))
    assertEquals(3, results.size)
    val titles = results.map { it.title }.toSet()
    assertEquals(setOf("A", "B", "C"), titles)
  }

  @Test
  fun `scanAll skips missing extra root without throwing`() {
    val primary = tmp.newFolder("primary")
    File(primary, "a.trail.yaml").writeText(v1Trail(title = "A"))
    val missing = File(tmp.root, "does-not-exist")

    val results = TrailIndexBuilder.scanAll(primary = primary, extras = listOf(missing))
    assertEquals(1, results.size)
    assertEquals("A", results.single().title)
  }

  @Test
  fun `scanAll two extras get rootIdx 1 and 2`() {
    val primary = tmp.newFolder("primary")
    val extra1 = tmp.newFolder("extra1")
    File(extra1, "e1.trail.yaml").writeText(v1Trail(title = "E1"))
    val extra2 = tmp.newFolder("extra2")
    File(extra2, "e2.trail.yaml").writeText(v1Trail(title = "E2"))

    val results = TrailIndexBuilder.scanAll(primary = primary, extras = listOf(extra1, extra2))

    val e1 = results.first { it.title == "E1" }
    val e2 = results.first { it.title == "E2" }
    assertEquals(1, e1.rootIdx)
    assertEquals(2, e2.rootIdx)
  }

  @Test
  fun `platform backfills from the filename's classifier lineage when config omits it`() {
    assertEquals("android", TrailIndexBuilder.platformFromFileName("android-phone.trail.yaml"))
    assertEquals("ios", TrailIndexBuilder.platformFromFileName("ios-iphone.trail.yaml"))
    assertEquals("web", TrailIndexBuilder.platformFromFileName("web.trail.yaml"))
    assertNull(TrailIndexBuilder.platformFromFileName("my-trail.trail.yaml"))
    assertNull(TrailIndexBuilder.platformFromFileName("blaze.yaml"))
  }

  @Test
  fun `platform backfill resolves classifiers through registered lineage overrides`() {
    // A downstream build registers hardware-classifier families at startup; the index builder
    // must pick the family's platform up from the lineage rather than hardcoding names.
    TrailblazeClassifierLineage.registerParentOverride(child = "kiosk", parent = "android")
    assertEquals("android", TrailIndexBuilder.platformFromFileName("kiosk-v2.trail.yaml"))
  }
}
