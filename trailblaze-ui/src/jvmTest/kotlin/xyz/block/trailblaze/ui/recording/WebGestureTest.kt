package xyz.block.trailblaze.ui.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [WebGesture.toYaml] output for every variant + the [toTrailYaml] envelope, so the
 * daemon's `runYaml` wire format can't silently drift. The original `Custom` indent-
 * normalization bug (commit f4ec2216c) shipped without a test — the daemon returns success
 * on malformed YAML and runs zero tools, so the failure mode is invisible to the client.
 * These tests catch the entire class of "YAML emitter regressed" issues at commonMain build
 * time instead of at the next live device run.
 *
 * Lives in jvmTest (not commonTest) because the trailblaze-ui module only currently has a
 * JVM test source set wired in Gradle. The pure tests below would run identically in
 * commonTest if/when that's added.
 */
class WebGestureTest {

  @Test fun `tap emits canonical tapOnPoint block`() {
    val tap = WebGesture.Tap(x = 100, y = 200, id = 1L)
    assertEquals(
      """
      - tapOnPoint:
          x: 100
          y: 200
      """.trimIndent(),
      tap.toYaml(),
    )
  }

  @Test fun `tap with longPress sets the longPress field`() {
    val tap = WebGesture.Tap(x = 50, y = 75, longPress = true, id = 2L)
    assertEquals(
      """
      - tapOnPoint:
          x: 50
          y: 75
          longPress: true
      """.trimIndent(),
      tap.toYaml(),
    )
  }

  @Test fun `swipe rounds sub-percent offsets instead of truncating`() {
    // 3-pixel offset on a 720-wide device is 0.4% — truncating would yield 0%, rounding
    // yields 0% but a 4-pixel offset (0.55%) should round to 1%.
    val swipe = WebGesture.Swipe(
      startX = 4, startY = 4, endX = 700, endY = 700,
      durationMs = 400L, deviceWidth = 720, deviceHeight = 720, id = 3L,
    )
    val yaml = swipe.toYaml()
    // start ~0.55% → rounds to 1%, end ~97% → 97%
    assertTrue(yaml.contains("startRelative: \"1%, 1%\""), "startRelative wrong: $yaml")
    assertTrue(yaml.contains("endRelative: \"97%, 97%\""), "endRelative wrong: $yaml")
    assertTrue(yaml.contains("durationMs: 400"), "durationMs wrong: $yaml")
  }

  @Test fun `inputText escapes backslash quote and control chars`() {
    val typed = WebGesture.InputText(text = "a\\b\"c\nd\te\rf", id = 4L)
    assertEquals(
      """- inputText:
    text: "a\\b\"c\nd\te\rf"""",
      typed.toYaml(),
    )
  }

  @Test fun `pressKey uppercases the key code`() {
    val key = WebGesture.PressKey(key = "enter", id = 5L)
    assertEquals(
      """- pressKey:
    keyCode: ENTER""",
      key.toYaml(),
    )
  }

  @Test fun `custom normalizes bare-map dialog output to list-item`() {
    // What `buildSingleToolYaml` emits today — 2-space indent, no leading dash.
    val dialogYaml = "inputText:\n  text: hi"
    val custom = WebGesture.Custom(toolName = "inputText", yaml = dialogYaml, id = 6L)
    assertEquals(
      """- inputText:
    text: hi""",
      custom.toYaml(),
    )
  }

  @Test fun `custom on single-line input does not double-dash`() {
    // Regression guard for the bug: `lines.drop(1)` returns empty on 1-line input but the
    // `- ` prefix was still added; the outer envelope then produced `- - <line>`.
    val custom = WebGesture.Custom(toolName = "noop", yaml = "noop: {}", id = 7L)
    assertEquals("- noop: {}", custom.toYaml())
  }

  @Test fun `custom passes pre-listified YAML through unchanged`() {
    // Idempotency: future callers may pre-format. Don't add a second `- `.
    val preFormatted = "- tapOnPoint:\n    x: 10\n    y: 20"
    val custom = WebGesture.Custom(toolName = "tapOnPoint", yaml = preFormatted, id = 8L)
    assertEquals(preFormatted, custom.toYaml())
  }

  @Test fun `custom with empty yaml returns empty string`() {
    val custom = WebGesture.Custom(toolName = "empty", yaml = "", id = 9L)
    assertEquals("", custom.toYaml())
  }

  @Test fun `toTrailYaml wraps gestures in canonical envelope`() {
    val gestures = listOf(
      WebGesture.Tap(x = 1, y = 2, id = 10L),
      WebGesture.InputText(text = "hi", id = 11L),
    )
    // `appendLine` after each gesture-line means the output ends with a single trailing
    // newline — no extra blank line between gestures, no double trailing newline.
    assertEquals(
      "- tools:\n" +
        "    - tapOnPoint:\n" +
        "        x: 1\n" +
        "        y: 2\n" +
        "    - inputText:\n" +
        "        text: \"hi\"\n",
      gestures.toTrailYaml(),
    )
  }

  @Test fun `toSingleStepTrailYaml is equivalent to one-element toTrailYaml`() {
    val tap = WebGesture.Tap(x = 1, y = 2, id = 12L)
    assertEquals(listOf(tap).toTrailYaml(), tap.toSingleStepTrailYaml())
  }
}
