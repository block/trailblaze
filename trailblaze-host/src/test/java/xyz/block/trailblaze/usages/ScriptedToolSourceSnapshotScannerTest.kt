package xyz.block.trailblaze.usages

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral contract of the dir-parameterized scripted-tool discovery: it registers exactly what
 * the runtime loader would register (YAML-declared names, meta-only descriptors via typed
 * bindings, bare `.ts` with exactly one binding) and turns every author anomaly into a warning
 * instead of aborting — it must survive historical git refs that predate a fix.
 */
class ScriptedToolSourceSnapshotScannerTest {

  private val trailmapsDir: File = createTempDirectory("trailblaze-snapshot-test").toFile()

  @AfterTest fun cleanup() {
    trailmapsDir.deleteRecursively()
  }

  /** Declared tool names, dropping the trailmap scoping most of these cases don't exercise. */
  private val ToolSourceSnapshot.toolNames: Set<String>
    get() = toolSources.keys.mapTo(mutableSetOf()) { it.name }

  private fun write(relativePath: String, content: String): File {
    val file = File(trailmapsDir, relativePath)
    file.parentFile.mkdirs()
    file.writeText(content.trimIndent())
    return file
  }

  @Test
  fun `registers yaml-declared names with scripts resolved against the descriptor's directory`() {
    val single = write("demo-map/tools/addItem.ts", "export function demo_addItem() {}")
    write(
      "demo-map/tools/addItem.yaml",
      """
      name: demo_addItem
      script: ./addItem.ts
      """,
    )
    val multi = write("demo-map/tools/signIn.ts", "export function demo_signIn() {}\nexport function demo_signOut() {}")
    write(
      "demo-map/tools/signIn.yaml",
      """
      script: ./signIn.ts
      tools:
        - name: demo_signIn
          description: sign in
        - name: demo_signOut
          description: sign out
      """,
    )

    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)

    assertTrue(snapshot.warnings.isEmpty(), "no warnings expected: ${snapshot.warnings}")
    assertEquals(
      mapOf(
        "demo_addItem" to single.absoluteFile,
        "demo_signIn" to multi.absoluteFile,
        "demo_signOut" to multi.absoluteFile,
      ),
      snapshot.toolSources.entries.associate { it.key.name to it.value.script.absoluteFile },
    )
    assertEquals(
      setOf("addItem.yaml", "signIn.yaml"),
      snapshot.toolSources.values.mapNotNull { it.descriptor?.name }.toSet(),
      "the declaring descriptor is part of each tool's source material — descriptor-only " +
        "edits (runtime:, inputSchema:) change dispatch and must be fingerprintable",
    )
  }

  @Test
  fun `a meta-only descriptor harvests names from the script's typed bindings`() {
    write(
      "demo-map/tools/checkout.ts",
      """
      export const demo_checkout = trailblaze.tool<CheckoutArgs, void>({
        execute: async () => {},
      })
      """,
    )
    write("demo-map/tools/checkout.yaml", "script: ./checkout.ts")

    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)

    assertEquals(setOf("demo_checkout"), snapshot.toolNames)
  }

  @Test
  fun `a bare ts file registers only with exactly one typed binding`() {
    write(
      "demo-map/tools/single.ts",
      "export const demo_single = trailblaze.tool({ execute: async () => {} })",
    )
    // 0 bindings: a helper module the loader would never register — correctly invisible.
    write("demo-map/tools/helpers.ts", "export function formatPrice(cents: number) { return cents / 100 }")
    // 2 bindings without a descriptor: the loader refuses these, so the scan must too — but as
    // a warning, because on a historical ref this may be exactly the authoring mistake under study.
    write(
      "demo-map/tools/pair.ts",
      """
      export const demo_first = trailblaze.tool({ execute: async () => {} })
      export const demo_second = trailblaze.tool({ execute: async () => {} })
      """,
    )

    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)

    assertEquals(setOf("demo_single"), snapshot.toolNames)
    assertEquals(
      null,
      snapshot.toolSources.getValue(ToolKey("demo-map", "demo_single")).descriptor,
      "bare typed-binding tools have no descriptor",
    )
    assertTrue(
      snapshot.warnings.any { it.contains("pair.ts") },
      "the ambiguous file must be named so an author can add its descriptor: ${snapshot.warnings}",
    )
  }

  @Test
  fun `a descriptor-covered script is not double-registered by the bare-ts pass`() {
    write(
      "demo-map/tools/covered.ts",
      "export const demo_renamed = trailblaze.tool({ execute: async () => {} })",
    )
    write(
      "demo-map/tools/covered.yaml",
      """
      name: demo_declaredName
      script: ./covered.ts
      """,
    )

    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)

    assertEquals(
      setOf("demo_declaredName"),
      snapshot.toolNames,
      "the YAML declaration owns the name; the binding name must not also register",
    )
  }

  @Test
  fun `malformed yaml and a missing script become warnings, never aborts`() {
    write("demo-map/tools/broken.yaml", "script: [this is not\na valid descriptor")
    write(
      "demo-map/tools/dangling.yaml",
      """
      name: demo_dangling
      script: ./no-such-file.ts
      """,
    )
    write(
      "demo-map/tools/fine.ts",
      "export const demo_fine = trailblaze.tool({ execute: async () => {} })",
    )

    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)

    assertEquals(setOf("demo_fine"), snapshot.toolNames, "the healthy tool must still register")
    assertTrue(snapshot.warnings.any { it.contains("broken.yaml") }, "warnings: ${snapshot.warnings}")
    assertTrue(snapshot.warnings.any { it.contains("dangling.yaml") }, "warnings: ${snapshot.warnings}")
  }

  @Test
  fun `operational tool-yaml descriptors and generated trees are ignored`() {
    write(
      "demo-map/tools/operational.tool.yaml",
      """
      name: demo_operational
      script: ./nope.ts
      """,
    )
    // The full operational-suffix family: none of these are scripted-tool descriptors, so none
    // may reach the decode path (which would warn: they have no `script:`) or register a tool.
    write("demo-map/tools/edge.shortcut.yaml", "shortcut: { from: demo-a, to: demo-b }")
    write("demo-map/tools/boot.trailhead.yaml", "trailhead: { description: demo }")
    write("demo-map/tools/home.waypoint.yaml", "waypoint: { id: demo-home }")
    write(
      "demo-map/tools/.trailblaze/generated.ts",
      "export const demo_generated = trailblaze.tool({ execute: async () => {} })",
    )

    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)

    assertTrue(snapshot.toolSources.isEmpty(), "found: ${snapshot.toolSources.keys}")
    assertTrue(snapshot.warnings.isEmpty(), "warnings: ${snapshot.warnings}")
  }

  @Test
  fun `the same tool name in two trailmaps registers both, scoped per trailmap`() {
    // The loader enforces name uniqueness only WITHIN a trailmap, so this is legal authoring.
    write("map-a/tools/shared.ts", "export const demo_shared = trailblaze.tool({ execute: async () => {} })")
    write("map-b/tools/shared.ts", "export const demo_shared = trailblaze.tool({ execute: async () => {} })")

    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)

    assertEquals(
      setOf(ToolKey("map-a", "demo_shared"), ToolKey("map-b", "demo_shared")),
      snapshot.toolSources.keys,
      "a global name key would drop one declaration, so an edit to it would flag no trails",
    )
    assertTrue(
      snapshot.warnings.isEmpty(),
      "cross-trailmap name reuse is legal, not a duplicate: ${snapshot.warnings}",
    )
  }

  @Test
  fun `a duplicate name within one trailmap is still a warning`() {
    write("demo-map/tools/first.ts", "export function demo_dupe() {}")
    write("demo-map/tools/first.yaml", "name: demo_dupe\nscript: ./first.ts")
    write("demo-map/tools/second.ts", "export function demo_dupe() {}")
    write("demo-map/tools/second.yaml", "name: demo_dupe\nscript: ./second.ts")

    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)

    assertEquals(setOf(ToolKey("demo-map", "demo_dupe")), snapshot.toolSources.keys)
    assertTrue(
      snapshot.warnings.any { it.contains("demo_dupe") && it.contains("demo-map") },
      "the loader hard-fails on this; the scan warns and names the trailmap: ${snapshot.warnings}",
    )
  }

  @Test
  fun `a missing trailmaps directory is an empty snapshot, not an error`() {
    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(File(trailmapsDir, "does-not-exist"))
    assertTrue(snapshot.toolSources.isEmpty() && snapshot.warnings.isEmpty())
  }
}
