package xyz.block.trailblaze.device

import kotlin.test.assertEquals
import org.junit.After
import org.junit.Test

class AndroidAppDataClearHookTest {

  private val calls = mutableListOf<String>()
  private val registered = mutableListOf<AndroidAppDataClearHook.Listener>()

  private fun register(name: String, fail: Boolean = false) {
    val listener = AndroidAppDataClearHook.Listener { appId ->
      calls += "$name $appId"
      if (fail) error("listener broke")
    }
    registered += listener
    AndroidAppDataClearHook.add(listener)
  }

  @After
  fun unregister() {
    registered.forEach(AndroidAppDataClearHook::remove)
  }

  @Test
  fun `every listener hears the cleared app`() {
    register("first")
    register("second")
    AndroidAppDataClearHook.notifyCleared("com.example")
    assertEquals(listOf("first com.example", "second com.example"), calls)
  }

  @Test
  fun `a listener that throws does not stop the others or fail the clear`() {
    register("broken", fail = true)
    register("after")
    AndroidAppDataClearHook.notifyCleared("com.example")
    assertEquals(listOf("broken com.example", "after com.example"), calls)
  }

  @Test
  fun `a removed listener hears nothing`() {
    register("gone")
    AndroidAppDataClearHook.remove(registered.removeAt(0))
    AndroidAppDataClearHook.notifyCleared("com.example")
    assertEquals(emptyList(), calls)
  }
}
