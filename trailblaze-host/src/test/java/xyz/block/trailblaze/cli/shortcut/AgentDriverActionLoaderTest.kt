package xyz.block.trailblaze.cli.shortcut

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.AgentDriverAction

/**
 * Pinpoint tests for the loader that pulls `AgentDriverAction` out of an
 * `_AgentDriverLog.json` step file. This is the load-bearing input to the proposer's
 * consensus-action analysis — a loader bug (silent JSON deserialization failure,
 * wrong discriminated-union branch, etc.) means proposed shortcuts carry the wrong
 * actions and replay fails silently downstream.
 *
 * The polymorphic deser uses the kotlinx-serialization discriminator
 * `class = "xyz.block.trailblaze.api.AgentDriverAction.<Type>"` configured on
 * `TrailblazeJson.defaultWithoutToolsInstance`.
 */
class AgentDriverActionLoaderTest {

  private val tempDir: File = createTempDirectory(prefix = "agent-driver-action-loader-").toFile()

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  @Test
  fun `loads TapPoint action from a well-formed AgentDriverLog`() {
    val file = write("001_AgentDriverLog.json", actionJson("TapPoint", """"x": 540, "y": 1200"""))
    val action = AgentDriverActionLoader.load(file)
    assertNotNull(action, "expected a TapPoint action, got null")
    assertTrue(action is AgentDriverAction.TapPoint, "expected TapPoint, got ${action::class.simpleName}")
    assertEquals(540, (action as AgentDriverAction.TapPoint).x)
    assertEquals(1200, action.y)
  }

  @Test
  fun `loads Scroll action`() {
    val file = write("002_AgentDriverLog.json", actionJson("Scroll", """"forward": false"""))
    val action = AgentDriverActionLoader.load(file)
    assertNotNull(action)
    assertTrue(action is AgentDriverAction.Scroll)
    assertEquals(false, (action as AgentDriverAction.Scroll).forward)
  }

  @Test
  fun `loads BackPress action (object case)`() {
    val file = write("003_AgentDriverLog.json", actionJson("BackPress", null))
    val action = AgentDriverActionLoader.load(file)
    assertNotNull(action)
    assertTrue(action is AgentDriverAction.BackPress)
  }

  @Test
  fun `loads EnterText action`() {
    val file = write("004_AgentDriverLog.json", actionJson("EnterText", """"text": "hello world""""))
    val action = AgentDriverActionLoader.load(file)
    assertNotNull(action)
    assertTrue(action is AgentDriverAction.EnterText)
    assertEquals("hello world", (action as AgentDriverAction.EnterText).text)
  }

  @Test
  fun `returns null for non-AgentDriverLog file by extension`() {
    val file = write("005_TrailblazeSnapshotLog.json", actionJson("TapPoint", """"x": 1, "y": 2"""))
    assertNull(AgentDriverActionLoader.load(file), "non-AgentDriverLog suffix should short-circuit")
  }

  @Test
  fun `returns null for non-existent file`() {
    assertNull(AgentDriverActionLoader.load(File(tempDir, "doesnotexist_AgentDriverLog.json")))
  }

  @Test
  fun `returns null on malformed JSON (lenient policy)`() {
    val file = write("006_AgentDriverLog.json", "{ this is not json")
    assertNull(AgentDriverActionLoader.load(file), "malformed json must not throw — return null")
  }

  @Test
  fun `returns null when the action field is missing`() {
    val file = write("007_AgentDriverLog.json", """{"unrelated": "field"}""")
    assertNull(AgentDriverActionLoader.load(file))
  }

  @Test
  fun `returns null on a directory (not a file)`() {
    assertNull(AgentDriverActionLoader.load(tempDir))
  }

  /**
   * Mimics the wire shape `TrailblazeLog.AgentDriverLog` produces: a top-level `action`
   * object carrying the polymorphic `class` discriminator + the variant's fields.
   * Heavy fields (`viewHierarchy`, `trailblazeNodeTree`) are omitted from these
   * fixtures — the loader's projection only requests `action`, so omitting them is
   * the cheap path and exercises `ignoreUnknownKeys` behavior on the rest.
   */
  private fun actionJson(variantSimpleName: String, fields: String?): String {
    val cls = "xyz.block.trailblaze.api.AgentDriverAction.$variantSimpleName"
    val inner = if (fields == null) {
      """{"class": "$cls"}"""
    } else {
      """{"class": "$cls", $fields}"""
    }
    return """{"action": $inner}"""
  }

  private fun write(name: String, content: String): File {
    val file = File(tempDir, name)
    file.writeText(content)
    return file
  }
}
