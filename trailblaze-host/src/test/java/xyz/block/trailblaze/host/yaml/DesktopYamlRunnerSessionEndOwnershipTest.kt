package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.logs.model.SessionId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ending an in-process run ends every session the device holds, so it runs only while the ending
 * run still owns the device. A run that already took the device over keeps what it loaded.
 */
class DesktopYamlRunnerSessionEndOwnershipTest {

  private val ending = SessionId("ending-run")

  @Test
  fun `the ending run owns a device it still holds, or one nobody holds`() {
    assertTrue(DesktopYamlRunner.stillOwnsDevice(current = ending, ended = ending))
    assertTrue(DesktopYamlRunner.stillOwnsDevice(current = null, ended = ending))
  }

  @Test
  fun `the ending run does not own a device another run took over`() {
    assertFalse(DesktopYamlRunner.stillOwnsDevice(current = SessionId("replacement-run"), ended = ending))
  }
}
