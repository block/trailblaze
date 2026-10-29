package xyz.block.trailblaze.maestro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Clock
import maestro.DeviceInfo
import maestro.Driver
import maestro.TreeNode
import maestro.device.Platform
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver

class LoggingDriverPermissionsTest {
  @Test
  fun `all-only permissions reach the device and are logged`() {
    val fixture = Fixture()
    val permissions = mapOf("all" to "allow")

    fixture.driver.setPermissions(APP_ID, permissions)

    assertEquals(listOf(APP_ID to permissions), fixture.delivered)
    assertEquals(listOf(AgentDriverAction.GrantPermissions(APP_ID, permissions)), fixture.loggedActions())
  }

  @Test
  fun `explicit overrides are forwarded with the all permission`() {
    val fixture = Fixture()
    val permissions = mapOf("all" to "allow", "notifications" to "unset")

    fixture.driver.setPermissions(APP_ID, permissions)

    assertEquals(listOf(APP_ID to permissions), fixture.delivered)
    assertEquals(listOf(AgentDriverAction.GrantPermissions(APP_ID, permissions)), fixture.loggedActions())
  }

  @Test
  fun `empty permissions leave the device unchanged and produce no action log`() {
    val fixture = Fixture()

    fixture.driver.setPermissions(APP_ID, emptyMap())

    assertEquals(emptyList(), fixture.delivered)
    assertEquals(emptyList(), fixture.loggedActions())
  }

  private class Fixture {
    val delivered = mutableListOf<Pair<String, Map<String, String>>>()
    private val logs = mutableListOf<TrailblazeLog>()
    private val delegate = object : Driver by ViewHierarchyOnlyDriver(
      rootTreeNode = TreeNode(),
      deviceInfo = DeviceInfo(
        platform = Platform.IOS,
        widthPixels = 390,
        heightPixels = 844,
        widthGrid = 390,
        heightGrid = 844,
      ),
    ) {
      override fun setPermissions(appId: String, permissions: Map<String, String>) {
        delivered += appId to permissions
      }
    }
    val driver = LoggingDriver(
      delegate = delegate,
      screenStateProvider = { error("Permission changes do not capture the screen") },
      trailblazeLogger = TrailblazeLogger(logEmitter = { logs += it }, screenStateLogger = { "" }),
      sessionProvider = TrailblazeSessionProvider { TrailblazeSession(SessionId("fixture"), Clock.System.now()) },
    )

    fun loggedActions() = logs.filterIsInstance<TrailblazeLog.AgentDriverLog>().map { it.action }
  }

  private companion object {
    const val APP_ID = "com.example.app"
  }
}
