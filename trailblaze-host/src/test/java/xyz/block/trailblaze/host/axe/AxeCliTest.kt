package xyz.block.trailblaze.host.axe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AxeCli]'s pure version-parsing/comparison helpers backing the minimum-axe-
 * version gate in `computeAvailability`. The subprocess-probing path itself needs a real (or
 * stubbed) `axe` binary and isn't covered here.
 */
class AxeCliTest {

  // --- parseAxeVersion ---

  @Test
  fun `parses a bare dotted version`() {
    assertEquals("1.8.0", AxeCli.parseAxeVersion("1.8.0"))
  }

  @Test
  fun `parses a v-prefixed version`() {
    assertEquals("1.8.0", AxeCli.parseAxeVersion("v1.8.0"))
  }

  @Test
  fun `parses a version embedded in surrounding text`() {
    assertEquals("1.8.0", AxeCli.parseAxeVersion("axe version v1.8.0\n"))
  }

  @Test
  fun `parses a 2-segment version`() {
    assertEquals("1.8", AxeCli.parseAxeVersion("axe 1.8"))
  }

  @Test
  fun `malformed output produces no version`() {
    assertNull(AxeCli.parseAxeVersion("command not found"))
  }

  @Test
  fun `blank output produces no version`() {
    assertNull(AxeCli.parseAxeVersion(""))
  }

  // --- compareVersions ---

  @Test
  fun `equal 3-segment versions compare equal`() {
    assertEquals(0, AxeCli.compareVersions("1.8.0", "1.8.0"))
  }

  @Test
  fun `an older patch version compares less than a newer one`() {
    assertTrue(AxeCli.compareVersions("1.5.2", "1.8.0") < 0)
  }

  @Test
  fun `a newer major version compares greater`() {
    assertTrue(AxeCli.compareVersions("2.0.0", "1.8.0") > 0)
  }

  @Test
  fun `a 2-segment version is padded with zero to compare against a 3-segment version`() {
    assertEquals(0, AxeCli.compareVersions("1.8", "1.8.0"))
  }

  @Test
  fun `a 2-segment version below the 3-segment minimum compares less`() {
    assertTrue(AxeCli.compareVersions("1.5", "1.8.0") < 0)
  }

  // --- computeAvailability's gating decision, expressed in terms of the pure helpers ---

  @Test
  fun `axe 1_5_2 is below MIN_VERSION`() {
    assertTrue(AxeCli.compareVersions("1.5.2", AxeCli.MIN_VERSION) < 0)
  }

  @Test
  fun `axe 1_8_0 meets MIN_VERSION`() {
    assertTrue(AxeCli.compareVersions("1.8.0", AxeCli.MIN_VERSION) >= 0)
  }
}
