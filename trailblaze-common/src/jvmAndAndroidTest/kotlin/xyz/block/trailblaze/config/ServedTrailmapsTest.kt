package xyz.block.trailblaze.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.config.ServedTrailmaps.Shadowed
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

class ServedTrailmapsTest {

  private val root = Files.createTempDirectory("served-trailmaps").toFile().canonicalFile
  private val resolver = ToolNameResolver.fromBuiltInAndCustomTools()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  /** An empty workspace under [root]/[name]. */
  private fun workspace(name: String): File = File(root, name).also { dir ->
    File(dir, "trailblaze-config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText("")
    }
  }

  /** A workspace under [root]/[name] holding a target trailmap for each of [targetIds]. */
  private fun workspace(name: String, vararg targetIds: String): File =
    workspace(name).also { dir -> targetIds.forEach { trailmap(dir, it, target = true) } }

  /**
   * Writes trailmap [id] into [workspace] at directory [dirName]. [target] true declares a target of
   * the same id; a string declares that target id instead.
   */
  private fun trailmap(
    workspace: File,
    id: String,
    target: Any = false,
    dependencies: List<String> = emptyList(),
    dirName: String = id,
  ): File = trailmapDir(workspace, dirName).apply {
    mkdirs()
    resolve("trailmap.yaml").writeText(
      buildString {
        appendLine("id: $id")
        when (target) {
          true -> appendLine("target:\n  display_name: $id")
          is String -> appendLine("target:\n  id: $target\n  display_name: $target")
        }
        if (dependencies.isNotEmpty()) appendLine("dependencies: [${dependencies.joinToString()}]")
      },
    )
  }

  private fun trailmapDir(workspace: File, dirName: String) = File(workspace, "trailblaze-config/trailmaps/$dirName")

  private fun path(workspace: File, dirName: String) = trailmapDir(workspace, dirName).canonicalPath

  private fun target(id: String, trailmapDirs: Map<String, File?> = emptyMap()) = YamlBackedHostAppTarget(
    config = AppTargetYamlConfig(id = id, displayName = id),
    toolNameResolver = resolver,
    trailmapDirs = trailmapDirs,
  )

  @Test
  fun `a target the daemon serves from a bundled copy is shadowed`() {
    val caller = workspace("caller", "app")

    val shadowed = ServedTrailmaps.shadowedIn(caller.toPath(), "app", mapOf("app" to mapOf("app" to null)))

    assertEquals(listOf(Shadowed("app", path(caller, "app"), servedFrom = null)), shadowed)
  }

  @Test
  fun `a target the daemon serves from another checkout is shadowed`() {
    val caller = workspace("caller", "app")
    val other = path(workspace("other", "app"), "app")

    val shadowed = ServedTrailmaps.shadowedIn(caller.toPath(), "app", mapOf("app" to mapOf("app" to other)))

    assertEquals(listOf(Shadowed("app", path(caller, "app"), servedFrom = other)), shadowed)
  }

  /** A prebuilt target, or one defined in code, records no trailmap: it is never the caller's copy. */
  @Test
  fun `a target that came from no trailmap is shadowed`() {
    val caller = workspace("caller", "app")

    val shadowed = ServedTrailmaps.shadowedIn(caller.toPath(), "app", mapOf("app" to emptyMap()))

    assertEquals(listOf(Shadowed("app", path(caller, "app"), servedFrom = null)), shadowed)
  }

  @Test
  fun `a target the daemon serves from that workspace is not shadowed`() {
    val caller = workspace("caller", "app")
    val nested = File(caller, "trails/some/dir").apply { mkdirs() }
    val served = ServedTrailmaps.of(setOf(target("app", mapOf("app" to trailmapDir(caller, "app")))))

    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(caller.toPath(), "app", served))
    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(nested.toPath(), "app", served))
  }

  /** A clone reached through a symlink is the same directory, not another copy. */
  @Test
  fun `the same trailmap reached through a symlink is not shadowed`() {
    val caller = workspace("caller", "app")
    val link = Files.createSymbolicLink(File(root, "link").toPath(), caller.toPath())
    val served = ServedTrailmaps.of(setOf(target("app", mapOf("app" to trailmapDir(link.toFile(), "app")))))

    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(caller.toPath(), "app", served))
  }

  /** The loader keys trailmaps by manifest id, so a folder named otherwise is still the same trailmap. */
  @Test
  fun `a trailmap is matched by its manifest id, not its folder name`() {
    val caller = workspace("caller")
    trailmap(caller, "app", target = true, dirName = "app-folder")

    val shadowed = ServedTrailmaps.shadowedIn(caller.toPath(), "app", mapOf("app" to mapOf("app" to null)))

    assertEquals(listOf(Shadowed("app", path(caller, "app-folder"), servedFrom = null)), shadowed)
  }

  @Test
  fun `a trailmap declaring another target id is matched by that target id`() {
    val caller = workspace("caller")
    trailmap(caller, "appTrailmap", target = "app")
    val served = mapOf("app" to mapOf("appTrailmap" to null))

    val shadowed = ServedTrailmaps.shadowedIn(caller.toPath(), "app", served)

    assertEquals(listOf(Shadowed("appTrailmap", path(caller, "appTrailmap"), servedFrom = null)), shadowed)
    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(caller.toPath(), "appTrailmap", served))
  }

  /**
   * The caller's `TRAILBLAZE_CONFIG_DIR` names its workspace wherever it runs from. This process's own
   * env is never read: in the daemon it would name the daemon's workspace as the caller's.
   */
  @Test
  fun `the caller's config dir names its workspace`() {
    val named = workspace("named", "app")
    val cwd = workspace("cwd", "app")
    val configDir = File(named, "trailblaze-config").path
    val served = mapOf("app" to mapOf("app" to path(cwd, "app")))

    assertEquals(
      listOf(Shadowed("app", path(named, "app"), servedFrom = path(cwd, "app"))),
      ServedTrailmaps.shadowedIn(cwd.toPath(), "app", served, callerConfigDir = configDir),
    )
    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(cwd.toPath(), "app", served))
  }

  /** The CLI lowercases a `--target` it is given, and target lookup ignores case. */
  @Test
  fun `a mixed-case target is matched ignoring case`() {
    val caller = workspace("caller", "appLite")

    val shadowed = ServedTrailmaps.shadowedIn(caller.toPath(), "applite", mapOf("appLite" to mapOf("appLite" to null)))

    assertEquals(listOf(Shadowed("appLite", path(caller, "appLite"), servedFrom = null)), shadowed)
    assertTrue(ServedTrailmaps.declaresTarget(caller.toPath(), "applite"))
  }

  /** A library trailmap has no target of its own: it runs only as a dependency of one. */
  @Test
  fun `a library trailmap the target depends on is shadowed when served from another copy`() {
    val caller = workspace("caller")
    trailmap(caller, "app", target = true, dependencies = listOf("lib", "framework"))
    trailmap(caller, "lib")
    val served = mapOf(
      "app" to mapOf("app" to path(caller, "app"), "lib" to null, "framework" to null),
    )

    val shadowed = ServedTrailmaps.shadowedIn(caller.toPath(), "app", served)

    // `framework` is not in this workspace, so it is meant to come from elsewhere.
    assertEquals(listOf(Shadowed("lib", path(caller, "lib"), servedFrom = null)), shadowed)
  }

  /** One broken trailmap must not stop commands that never touch it. */
  @Test
  fun `only the trailmaps the target uses are checked`() {
    val caller = workspace("caller", "app", "unrelated")
    val served = mapOf(
      "app" to mapOf("app" to path(caller, "app")),
      "unrelated" to mapOf("unrelated" to null),
    )

    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(caller.toPath(), "app", served))
    assertEquals(listOf("unrelated"), ServedTrailmaps.shadowedIn(caller.toPath(), "unrelated", served).map { it.id })
  }

  @Test
  fun `a manifest that does not parse is still compared by its folder name`() {
    val caller = workspace("caller")
    trailmapDir(caller, "app").apply { mkdirs() }.resolve("trailmap.yaml").writeText("id: [not, a, string\n")

    val shadowed = ServedTrailmaps.shadowedIn(caller.toPath(), "app", mapOf("app" to mapOf("app" to null)))

    assertEquals(listOf(Shadowed("app", path(caller, "app"), servedFrom = null)), shadowed)
  }

  /** Nothing stands in for a target the daemon does not serve, so a command needing it fails loudly anyway. */
  @Test
  fun `a target the daemon does not serve is not reported`() {
    val caller = workspace("caller", "app")

    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(caller.toPath(), "app", mapOf("other" to emptyMap())))
  }

  @Test
  fun `nothing is reported without a target, outside a workspace, or for a daemon that does not report its sources`() {
    val outside = File(root, "outside").apply { mkdirs() }
    val caller = workspace("caller", "app")
    val served = mapOf("app" to mapOf("app" to null))

    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(caller.toPath(), targetId = null, served))
    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(outside.toPath(), "app", served))
    assertEquals(emptyList(), ServedTrailmaps.shadowedIn(caller.toPath(), "app", served = null))
  }

  @Test
  fun `served sources list every target with the canonical directory of each trailmap it uses`() {
    val caller = workspace("caller", "app")
    val other = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget

    assertEquals(
      mapOf(
        "app" to mapOf("app" to path(caller, "app"), "lib" to null),
        "prebuilt" to emptyMap(),
        other.id to emptyMap(),
      ),
      ServedTrailmaps.of(
        setOf(target("app", mapOf("app" to trailmapDir(caller, "app"), "lib" to null)), target("prebuilt"), other),
      ),
    )
  }

  @Test
  fun `a workspace declares the targets of its own trailmaps only`() {
    val caller = workspace("caller")
    trailmap(caller, "appTrailmap", target = "app")
    trailmap(caller, "lib")
    val outside = File(root, "outside").apply { mkdirs() }

    assertTrue(ServedTrailmaps.declaresTarget(caller.toPath(), "app"))
    assertFalse(ServedTrailmaps.declaresTarget(caller.toPath(), "lib"))
    assertFalse(ServedTrailmaps.declaresTarget(caller.toPath(), "other"))
    assertTrue(ServedTrailmaps.hasWorkspaceTrailmaps(caller.toPath()))
    assertFalse(ServedTrailmaps.hasWorkspaceTrailmaps(workspace("empty").toPath()))
    assertFalse(ServedTrailmaps.hasWorkspaceTrailmaps(outside.toPath()))
  }

  @Test
  fun `the refusal points at trailblaze check only when a copy filled in for a trailmap`() {
    val fromCheckout = ServedTrailmaps.refusal(listOf(Shadowed("app", "/mine/app", servedFrom = "/theirs/app")))
    val fromBundle = ServedTrailmaps.refusal(listOf(Shadowed("app", "/mine/app", servedFrom = null)))

    assertTrue("/theirs/app" in fromCheckout)
    assertFalse("trailblaze check" in fromCheckout)
    assertTrue("trailblaze check" in fromBundle)
  }
}
