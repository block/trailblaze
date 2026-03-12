package xyz.block.trailblaze.playwright

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Test
import xyz.block.trailblaze.yaml.ElectronAppConfig

/**
 * Unit tests for [ElectronAppManager] that exercise config-driven logic without
 * needing a real Electron binary or CDP endpoint.
 */
class ElectronAppManagerTest {

  @Test
  fun `cdpUrl returns localhost URL from configured port`() {
    val manager = ElectronAppManager(ElectronAppConfig(cdpPort = 12345))
    assertEquals("http://localhost:12345", manager.cdpUrl)
  }

  @Test
  fun `cdpUrl returns explicit URL when set`() {
    val manager = ElectronAppManager(ElectronAppConfig(cdpUrl = "http://remote:9222"))
    assertEquals("http://remote:9222", manager.cdpUrl)
  }

  @Test
  fun `start throws when no command and no cdpUrl`() {
    val manager = ElectronAppManager(ElectronAppConfig())
    val exception = assertFailsWith<IllegalStateException> { manager.start() }
    assertEquals(
      "ElectronAppConfig requires either 'command' or 'cdpUrl' to be set",
      exception.message,
    )
  }

  @Test
  fun `start with invalid command fails`() {
    val manager =
      ElectronAppManager(
        ElectronAppConfig(
          command = "/nonexistent/electron-binary-that-does-not-exist",
          cdpPort = 19999,
          cdpTimeoutSeconds = 2,
        )
      )
    assertFailsWith<Exception> { manager.start() }
    manager.close()
  }

  @Test
  fun `close is idempotent when no process was launched`() {
    val manager = ElectronAppManager(ElectronAppConfig())
    // Should not throw
    manager.close()
    manager.close()
  }

  @Test
  fun `attach mode does not manage a process`() {
    val manager = ElectronAppManager(ElectronAppConfig(cdpUrl = "http://localhost:9222"))
    assertFalse(manager.isManagingProcess)
  }
}
