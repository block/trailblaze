package xyz.block.trailblaze.ui.editors.yaml

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the editor's soft [validateYaml] gate. The Run/Save buttons in the trail editor are enabled
 * only when this returns null, so a false-positive here silently blocks running or saving a trail.
 */
class YamlEditorValidationTest {

  @Test
  fun `blank content is not an error`() {
    assertNull(validateYaml(""))
    assertNull(validateYaml("   \n  "))
  }

  @Test
  fun `a v1 trail validates cleanly`() {
    val v1 = """
      - config:
          target: myapp
      - tools:
        - pressBack: {}
    """.trimIndent()
    assertNull(validateYaml(v1))
  }

  @Test
  fun `a unified trail carrying recordings validates cleanly`() {
    // Regression: validation must NOT run through decodeTrail(no classifiers), which throws a
    // runtime guard for a unified file with recordings ("would drop every recording"). That is not
    // a syntax error, and treating it as one disabled Run/Save for every unified trail. The
    // version-aware decodeTrailDocument accepts the unified mapping shape without the guard.
    val unified = """
      config:
        target: myapp
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Go back
          recording:
            android:
              - pressBack: {}
    """.trimIndent()
    assertNull(validateYaml(unified), "a unified trail with recordings must validate")
  }

  @Test
  fun `a genuinely malformed trail still reports an error`() {
    // Neither a v1 list nor a unified mapping — validation must still flag it.
    val garbage = "this: is: not: a: trail"
    assertTrue(validateYaml(garbage) != null, "malformed YAML should still surface an error")
  }

  @Test
  fun `a v1 trail using an app-specific tool not on this classpath validates cleanly`() {
    // Observable contract: the editor must not block Run/Save on a tool it doesn't recognize, since
    // the runner registers app-specific tools at run time. (An unknown tool decodes leniently into a
    // pass-through OtherTrailblazeTool, so the decode succeeds; validateYaml returns null either way.)
    val v1 = """
      - config:
          target: myapp
      - tools:
        - thisToolIsNotRegisteredAnywhere: {}
    """.trimIndent()
    assertNull(validateYaml(v1), "an unknown tool must be tolerated, not flagged as invalid")
  }

  @Test
  fun `a unified trail using an app-specific tool not on this classpath validates cleanly`() {
    // Same tolerance for the unified shape — an unrecognized tool must not disable Run/Save.
    val unified = """
      config:
        target: myapp
      trail:
        - step: Do the app-specific thing
          recording:
            android:
              - thisToolIsNotRegisteredAnywhere: {}
    """.trimIndent()
    assertNull(validateYaml(unified), "an unknown tool in a unified trail must be tolerated")
  }
}
