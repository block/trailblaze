package xyz.block.trailblaze.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckedAdbShellOutputTest {
  @Test
  fun `an empty successful listing is valid output`() {
    assertEquals("", AndroidHostAdbUtils.checkedShellOutput(0, "", ""))
    assertEquals("receipt.png\n", AndroidHostAdbUtils.checkedShellOutput(0, "receipt.png\n", ""))
  }

  @Test
  fun `a failed listing cannot be mistaken for an empty directory`() {
    for (diagnostic in listOf("Permission denied", "No such file or directory")) {
      val failure = assertFailsWith<IllegalStateException> {
        AndroidHostAdbUtils.checkedShellOutput(1, "", diagnostic)
      }
      assertTrue(failure.message.orEmpty().contains(diagnostic))
    }
  }

  @Test
  fun `a failed command with stdout also fails`() {
    assertFailsWith<IllegalStateException> {
      AndroidHostAdbUtils.checkedShellOutput(1, "partial data", "")
    }
  }
}
