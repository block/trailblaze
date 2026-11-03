package xyz.block.trailblaze.device

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Test

class AndroidAppLaunchHookTest {

  private val calls = mutableListOf<String>()

  private fun install(confirm: Boolean, failAttach: Boolean = false) {
    AndroidAppLaunchHook.installed = object : AndroidAppLaunchHook.Hook {
      override fun beforeLaunch(appId: String): Boolean {
        calls += "attach $appId"
        if (failAttach) error("turbo required but the detector could not attach")
        return confirm
      }

      override fun afterLaunch(appId: String) {
        calls += "confirm $appId"
      }
    }
  }

  @After
  fun uninstall() {
    AndroidAppLaunchHook.installed = null
  }

  @Test
  fun `the launch runs between the attach and the confirmation`() {
    install(confirm = true)
    val result = AndroidAppLaunchHook.aroundLaunch("com.example") { calls += "launch"; "Status: ok" }
    assertEquals("Status: ok", result)
    assertEquals(listOf("attach com.example", "launch", "confirm com.example"), calls)
  }

  @Test
  fun `no confirmation follows when the attach had nothing to confirm`() {
    install(confirm = false)
    AndroidAppLaunchHook.aroundLaunch("com.example") { calls += "launch" }
    assertEquals(listOf("attach com.example", "launch"), calls)
  }

  @Test
  fun `a failed required attach stops the launch`() {
    install(confirm = true, failAttach = true)
    assertFailsWith<IllegalStateException> { AndroidAppLaunchHook.aroundLaunch("com.example") { calls += "launch" } }
    assertEquals(listOf("attach com.example"), calls)
  }

  @Test
  fun `a started attachment is still confirmed when the start throws`() {
    // The start may have run on the device before its shell call failed, so its detector may serve.
    install(confirm = true)
    assertFailsWith<IllegalStateException> {
      AndroidAppLaunchHook.aroundLaunch("com.example") { calls += "launch"; error("shell dropped") }
    }
    assertEquals(listOf("attach com.example", "launch", "confirm com.example"), calls)
  }

  @Test
  fun `with no hook installed the launch is just the launch`() {
    AndroidAppLaunchHook.aroundLaunch("com.example") { calls += "launch" }
    assertEquals(listOf("launch"), calls)
  }
}
