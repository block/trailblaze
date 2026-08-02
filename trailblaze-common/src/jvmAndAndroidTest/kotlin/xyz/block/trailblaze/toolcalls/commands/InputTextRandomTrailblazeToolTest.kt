package xyz.block.trailblaze.toolcalls.commands

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure value generator of [InputTextRandomTrailblazeTool]. The tool's on-device
 * behavior (typing + remembering) is exercised by trail replay; here we pin the deterministic
 * generation contract with a seeded [Random], no device and no memory.
 */
class InputTextRandomTrailblazeToolTest {

  @Test
  fun `randomValue is prefix plus exactly digitCount decimal digits`() {
    val v = InputTextRandomTrailblazeTool.randomValue("TBZ-", 6, "", false, Random(1))
    assertTrue(v.matches(Regex("^TBZ-[0-9]{6}$")), "unexpected shape: $v")
  }

  @Test
  fun `randomValue honors a custom prefix and digit count`() {
    val v = InputTextRandomTrailblazeTool.randomValue("cust_", 3, "", false, Random(1))
    assertTrue(v.matches(Regex("^cust_[0-9]{3}$")), "unexpected shape: $v")
  }

  @Test
  fun `randomValue emits hexadecimal digits when hex is true`() {
    val v = InputTextRandomTrailblazeTool.randomValue("", 8, "", true, Random(1))
    assertTrue(v.matches(Regex("^[0-9a-f]{8}$")), "unexpected shape: $v")
  }

  @Test
  fun `randomValue appends the suffix (email domain use)`() {
    val v = InputTextRandomTrailblazeTool.randomValue("", 6, "@example.com", true, Random(1))
    assertTrue(v.matches(Regex("^[0-9a-f]{6}@example\\.com$")), "unexpected shape: $v")
  }

  @Test
  fun `randomValue is deterministic for a given seed`() {
    assertEquals(
      InputTextRandomTrailblazeTool.randomValue("TBZ-", 6, "", false, Random(42)),
      InputTextRandomTrailblazeTool.randomValue("TBZ-", 6, "", false, Random(42)),
    )
  }

  @Test
  fun `randomValue varies across seeds (unique-per-run intent)`() {
    val values = (1..100).map { InputTextRandomTrailblazeTool.randomValue("TBZ-", 6, "", false, Random(it)) }
    assertTrue(values.toSet().size > 1, "expected varied values, got ${values.toSet()}")
    assertTrue(values.all { it.matches(Regex("^TBZ-[0-9]{6}$")) })
  }
}
