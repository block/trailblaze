package xyz.block.trailblaze.trailrunner

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * The live trail rail's backing endpoint: given a demonstration run, return the trail files it
 * produced. These pin the observable contract - reading the delivered trail, filling in from the
 * suggested folder before any trail_output, and refusing to read a file that escapes the trails
 * root - without a live Ktor server.
 */
class DemoTrailContentTest {
  private val seededIds = mutableListOf<String>()
  private val tempDirs = mutableListOf<File>()
  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  @AfterTest
  fun cleanup() {
    seededIds.forEach { ExternalAgentSupervisor.runs.remove(it) }
    seededIds.clear()
    tempDirs.forEach { runCatching { it.deleteRecursively() } }
    tempDirs.clear()
  }

  private fun tempRoot(): File = Files.createTempDirectory("demo-trail-content").toFile().also { tempDirs += it }

  private fun seedDemoRun(demo: DemoRunState): String {
    val id = "seed-" + System.nanoTime()
    val run = MutableExternalAgentRun(
      id = id,
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.CLAUDE, prompt = "x"),
      title = "seed",
      prompt = "x",
      cwd = tempRoot(),
    ).also { it.demo = demo }
    ExternalAgentSupervisor.runs[id] = run
    seededIds += id
    return id
  }

  @Test
  fun readsTheDeliveredTrailFilesUnderTheTrailsRoot() {
    val root = tempRoot()
    File(root, "myapp/widget").mkdirs()
    File(root, "myapp/widget/trail.yaml").writeText("name: widget\n")
    File(root, "myapp/widget/android.trail.yaml").writeText("platform: android\n")

    val demo = DemoRunState(deviceId = device, target = "myapp", platform = "android").apply {
      trailId = "0/myapp/widget"
      trailFiles = "trail.yaml,android.trail.yaml"
    }
    val id = seedDemoRun(demo)

    val result = ExternalAgentSupervisor.demoTrailContent(id, root)!!
    assertEquals("0/myapp/widget", result.trailId)
    assertEquals(listOf("trail.yaml", "android.trail.yaml"), result.files.map { it.name })
    assertEquals("name: widget\n", result.files.first { it.name == "trail.yaml" }.content)
  }

  @Test
  fun fillsInFromTheSuggestedFolderBeforeAnyTrailOutput() {
    val root = tempRoot()
    // suggestedTrailDir for target=myapp, objective="Widget in cart" is myapp/widget-in-cart/
    File(root, "myapp/widget-in-cart").mkdirs()
    File(root, "myapp/widget-in-cart/trail.yaml").writeText("draft\n")
    File(root, "myapp/widget-in-cart/notes.md").writeText("# notes\n")
    // A non-trail file must be ignored.
    File(root, "myapp/widget-in-cart/scratch.txt").writeText("ignore me\n")

    val demo = DemoRunState(deviceId = device, target = "myapp", platform = "android").apply {
      objective = "Widget in cart"
      // No trailId/trailFiles yet - the agent hasn't emitted a trail_output.
    }
    val id = seedDemoRun(demo)

    val result = ExternalAgentSupervisor.demoTrailContent(id, root)!!
    assertNull(result.trailId)
    assertEquals(listOf("notes.md", "trail.yaml"), result.files.map { it.name })
  }

  @Test
  fun refusesToReadAFileThatEscapesTheTrailsRoot() {
    val root = tempRoot()
    // A file that lives OUTSIDE the trails root, reachable only by climbing out with `..`.
    val secret = File(root.parentFile, "escape-secret-${System.nanoTime()}.yaml")
    secret.writeText("SECRET\n")
    tempDirs += secret // clean it up

    val demo = DemoRunState(deviceId = device, target = "myapp", platform = "android").apply {
      // "0/.." strips the root-index prefix to the relative path "..", climbing above the root.
      trailId = "0/.."
      trailFiles = secret.name
    }
    val id = seedDemoRun(demo)

    val result = ExternalAgentSupervisor.demoTrailContent(id, root)!!
    // The escaping file is rejected by the canonical-prefix containment check.
    assertTrue(result.files.isEmpty())
  }

  @Test
  fun returnsNullForAnUnknownOrNonDemonstrationRun() {
    assertNull(ExternalAgentSupervisor.demoTrailContent("does-not-exist", tempRoot()))

    val id = "seed-" + System.nanoTime()
    ExternalAgentSupervisor.runs[id] = MutableExternalAgentRun(
      id = id,
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.CLAUDE, prompt = "x"),
      title = "seed",
      prompt = "x",
      cwd = tempRoot(),
    ) // no demo state
    seededIds += id
    assertNull(ExternalAgentSupervisor.demoTrailContent(id, tempRoot()))
  }
}
