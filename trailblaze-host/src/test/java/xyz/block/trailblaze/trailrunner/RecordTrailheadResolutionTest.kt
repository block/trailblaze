package xyz.block.trailblaze.trailrunner

import kotlin.test.assertEquals
import org.junit.Test

/**
 * Covers `prependTrailheadTool`, which `/api/folder/record` uses to inject the per-platform trailhead
 * as step 0 — now a first-class `- trailhead: <id>` root element ahead of the prompts. The trailhead
 * id comes from the recording's platform column / the Configure-recording dialog — it is
 * platform-specific and never read from the cross-platform blaze.yaml.
 */
class RecordTrailheadResolutionTest {

  @Test
  fun `inserts a trailhead element before the first prompts block`() {
    val yaml = "- config:\n    target: myapp\n- prompts:\n  - step: Tap Pay\n"
    val out = prependTrailheadTool(yaml, "myapp_android_signedInFresh")
    val expected = "- config:\n    target: myapp\n- trailhead: myapp_android_signedInFresh\n- prompts:\n  - step: Tap Pay\n"
    assertEquals(expected, out)
  }

  @Test
  fun `appends after the config when there is no prompts block`() {
    // The trailhead must come AFTER config, so with no step block we append rather than prepend.
    val yaml = "- config:\n    target: myapp\n"
    val out = prependTrailheadTool(yaml, "myapp_android_signedInFresh")
    assertEquals(yaml + "- trailhead: myapp_android_signedInFresh\n", out)
  }

  @Test
  fun `inserts before the FIRST step block only`() {
    val yaml = "- config: {}\n- prompts:\n  - step: a\n- prompts:\n  - step: b\n"
    val out = prependTrailheadTool(yaml, "th")
    assertEquals(1, Regex("- trailhead:").findAll(out).count())
    assertEquals(true, out.indexOf("- trailhead:") < out.indexOf("- prompts:"))
  }

  @Test
  fun `returns yaml unchanged for a malformed tool id`() {
    val yaml = "- config: {}\n- prompts:\n  - step: a\n"
    // A control char / space / quote would break out of the emitted YAML scalar — must no-op.
    assertEquals(yaml, prependTrailheadTool(yaml, "bad id"))
    assertEquals(yaml, prependTrailheadTool(yaml, "evil: {}\n- inject"))
    assertEquals(yaml, prependTrailheadTool(yaml, "with\nnewline"))
  }

  @Test
  fun `accepts ids with slashes, underscores, hyphens, digits`() {
    val yaml = "- prompts:\n  - step: a\n"
    val out = prependTrailheadTool(yaml, "defaults/standard-merchant_2")
    assertEquals("- trailhead: defaults/standard-merchant_2\n" + yaml, out)
  }

  @Test
  fun `replaces an existing shorthand trailhead instead of adding a second`() {
    // Re-recording a trail that already adopted a `- trailhead:` must not emit two — TrailblazeYaml
    // rejects that ("Only one trailhead item is allowed"). The old one is stripped, the new inserted.
    val yaml = "- config:\n    target: myapp\n- trailhead: old_trailhead\n- prompts:\n  - step: Tap Pay\n"
    val out = prependTrailheadTool(yaml, "new_trailhead")
    assertEquals(1, Regex("(?m)^- trailhead:").findAll(out).count())
    assertEquals(true, out.contains("- trailhead: new_trailhead"))
    assertEquals(false, out.contains("old_trailhead"))
  }

  @Test
  fun `replaces an existing object-form trailhead instead of adding a second`() {
    val yaml = "- config:\n    target: myapp\n" +
      "- trailhead:\n    step: Sign in\n    tools:\n      - old_tool: {}\n" +
      "- prompts:\n  - step: Tap Pay\n"
    val out = prependTrailheadTool(yaml, "new_trailhead")
    assertEquals(1, Regex("(?m)^- trailhead:").findAll(out).count())
    assertEquals(true, out.contains("- trailhead: new_trailhead"))
    assertEquals(false, out.contains("old_tool"))
    assertEquals(false, out.contains("Sign in"))
  }
}
