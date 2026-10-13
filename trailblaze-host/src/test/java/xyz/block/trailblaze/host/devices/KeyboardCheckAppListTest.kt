package xyz.block.trailblaze.host.devices

import org.junit.Test
import kotlin.test.assertEquals

class KeyboardCheckAppListTest {

  private var now = 0L
  private var listings = 0
  private var installed = setOf("com.apple.Preferences", "com.example.app", "com.apple.mobilesafari")

  private fun appList(preferred: List<String> = listOf("com.example.app")) = appList { preferred }

  private fun appList(preferred: () -> List<String>) =
    KeyboardCheckAppList(preferred, ttlMs = 1_000, nowMs = { now }) {
      listings++
      installed
    }

  @Test
  fun `the target's installed app is asked about first`() {
    val apps = appList()()
    assertEquals("com.example.app", apps.first())
    assertEquals(installed, apps)
  }

  @Test
  fun `a target app installed after the listing is still asked about, after the listed ones`() {
    val list = appList(preferred = listOf("com.example.new", "com.example.app"))
    list()
    installed = installed + "com.example.new"

    val apps = list().toList()

    assertEquals(1, listings)
    assertEquals(listOf("com.example.app", "com.example.new"), apps.take(2))
    assertEquals(installed, apps.toSet())
  }

  @Test
  fun `a switch to another target is asked about first on the next check, without listing again`() {
    var target = listOf("com.example.app")
    val list = appList { target }
    list()

    target = listOf("com.apple.mobilesafari")
    assertEquals("com.apple.mobilesafari", list().first())
    assertEquals(1, listings)
  }

  @Test
  fun `an empty listing is listed again on the next check`() {
    installed = emptySet()
    val list = appList()
    assertEquals(setOf("com.example.app"), list())

    installed = setOf("com.example.app", "com.apple.Preferences")
    assertEquals(installed, list())
    assertEquals(2, listings)
  }

  @Test
  fun `apps are listed once per ttl`() {
    val list = appList()
    list()
    now = 999
    list()
    assertEquals(1, listings)

    installed = installed + "com.example.new"
    now = 1_000
    assertEquals(true, "com.example.new" in list())
    assertEquals(2, listings)
  }
}
