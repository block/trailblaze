package xyz.block.trailblaze.trailrunner

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailDetailBuilderTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val twoStepYaml = """
    - config:
        id: demo/login
        title: Demo login
        target: myapp
        platform: ios
        tags: [smoke, login]
    - prompts:
      - step: Launch the app signed in
        recording:
          tools:
          - myapp_ios_signInViaUI: { email: x@y.z, password: secret }
      - verify: Money tab is visible
        recording:
          tools:
          - assertVisibleBySelector: { reason: "", nodeSelector: { iosMaestro: { resourceIdRegex: balance_tab_button } } }
  """.trimIndent()

  @Test
  fun `build returns correct title from config`() {
    val root = tmp.newFolder("trails")
    val file = File(root, "login.trail.yaml").apply { writeText(twoStepYaml) }

    val detail = TrailDetailBuilder.build(root, file)

    assertEquals("Demo login", detail.title)
  }

  @Test
  fun `build returns correct id as relative path without trail-yaml suffix`() {
    val root = tmp.newFolder("trails")
    val file = File(root, "login.trail.yaml").apply { writeText(twoStepYaml) }

    val detail = TrailDetailBuilder.build(root, file)

    assertEquals("login", detail.id)
  }

  @Test
  fun `build preserves raw yaml in response`() {
    val root = tmp.newFolder("trails")
    val file = File(root, "login.trail.yaml").apply { writeText(twoStepYaml) }

    val detail = TrailDetailBuilder.build(root, file)

    assertTrue(detail.yaml.contains("myapp_ios_signInViaUI"), "raw yaml should contain tool name")
  }

  @Test
  fun `build parses step and verify kinds from v1 list shape`() {
    val root = tmp.newFolder("trails")
    val file = File(root, "login.trail.yaml").apply { writeText(twoStepYaml) }

    val steps = TrailDetailBuilder.build(root, file).steps

    assertEquals(2, steps.size)
    assertEquals("step", steps[0].kind)
    assertEquals("verify", steps[1].kind)
  }

  @Test
  fun `build parses step text correctly`() {
    val root = tmp.newFolder("trails")
    val file = File(root, "login.trail.yaml").apply { writeText(twoStepYaml) }

    val steps = TrailDetailBuilder.build(root, file).steps

    assertEquals("Launch the app signed in", steps[0].text)
    assertEquals("Money tab is visible", steps[1].text)
  }

  @Test
  fun `build extracts tool names from recording`() {
    val root = tmp.newFolder("trails")
    val file = File(root, "login.trail.yaml").apply { writeText(twoStepYaml) }

    val steps = TrailDetailBuilder.build(root, file).steps

    assertEquals(listOf("myapp_ios_signInViaUI"), steps[0].tools)
    assertEquals(listOf("assertVisibleBySelector"), steps[1].tools)
  }

  @Test
  fun `build returns filename-derived title when config has no title`() {
    val yaml = """
      - config:
          id: example
      - prompts:
        - step: Do something
    """.trimIndent()
    val root = tmp.newFolder("trails")
    val file = File(root, "cold-boot.trail.yaml").apply { writeText(yaml) }

    val detail = TrailDetailBuilder.build(root, file)

    assertEquals("cold boot", detail.title)
  }

  @Test
  fun `build handles multiple steps in a single prompts block`() {
    val yaml = """
      - config:
          id: multi
          title: Multi-step trail
      - prompts:
        - step: Step one
        - step: Step two
        - verify: Assert final state
    """.trimIndent()
    val root = tmp.newFolder("trails")
    val file = File(root, "multi.trail.yaml").apply { writeText(yaml) }

    val steps = TrailDetailBuilder.build(root, file).steps

    assertEquals(3, steps.size)
    assertEquals("step", steps[0].kind)
    assertEquals("step", steps[1].kind)
    assertEquals("verify", steps[2].kind)
    assertEquals("Step one", steps[0].text)
  }

  @Test
  fun `build with step without recording has empty tools list`() {
    val yaml = """
      - config:
          id: no-recording
          title: No recording trail
      - prompts:
        - step: Just a prompt
    """.trimIndent()
    val root = tmp.newFolder("trails")
    val file = File(root, "no-recording.trail.yaml").apply { writeText(yaml) }

    val steps = TrailDetailBuilder.build(root, file).steps

    assertEquals(1, steps.size)
    assertTrue(steps[0].tools.isEmpty())
  }

  @Test
  fun `build on malformed yaml returns empty steps without throwing`() {
    val root = tmp.newFolder("trails")
    val file = File(root, "broken.trail.yaml").apply {
      writeText(": this: is: not: valid: yaml: {{{{")
    }

    val detail = TrailDetailBuilder.build(root, file)

    assertTrue(detail.steps.isEmpty(), "malformed yaml should produce no steps")
  }

  @Test
  fun `build on malformed yaml still returns raw yaml string`() {
    val badYaml = ": this: is: not: valid: yaml: {{{{"
    val root = tmp.newFolder("trails")
    val file = File(root, "broken.trail.yaml").apply { writeText(badYaml) }

    val detail = TrailDetailBuilder.build(root, file)

    assertEquals(badYaml, detail.yaml, "raw yaml should be returned even when malformed")
  }

  @Test
  fun `build on empty yaml returns empty steps without throwing`() {
    val root = tmp.newFolder("trails")
    val file = File(root, "empty.trail.yaml").apply { writeText("") }

    val detail = TrailDetailBuilder.build(root, file)

    assertTrue(detail.steps.isEmpty())
  }

  @Test
  fun `build returns correct path for nested file`() {
    val root = tmp.newFolder("trails")
    File(root, "myapp/login").mkdirs()
    val file = File(root, "myapp/login/smoke.trail.yaml").apply { writeText(twoStepYaml) }

    val detail = TrailDetailBuilder.build(root, file)

    assertEquals("myapp/login/smoke.trail.yaml", detail.path)
    assertEquals("myapp/login/smoke", detail.id)
  }
}
