package xyz.block.trailblaze.host.axe

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.host.devices.AxeConnectedDevice
import xyz.block.trailblaze.host.ios.IosDriverAction
import xyz.block.trailblaze.logs.model.SessionId

/**
 * A one-shot MCP or CLI call builds a new [AxeDeviceManager] for every tool, so the notifications
 * answer `ios_setPermissions` arms has to outlive the manager that armed it, and belongs to one app.
 * These pin that a later call taps an alert with the answer armed for the app the alert names,
 * once, and that what a read returns or logs shows the screen the answer left.
 */
class AxeDeviceManagerNotificationAlertTest {

  private val pay = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "^Pay$"))

  @Test
  fun `the answer one call arms is tapped by a later call's manager`() {
    val deviceAnswer = NotificationAlertAnswers()
    manager(Simulator(), deviceAnswer)
      .execute(IosDriverAction.SetPermissions(APP_ID, mapOf("notifications" to "allow")))

    val sim = Simulator(alertApp = "Shop")
    manager(sim, deviceAnswer).execute(IosDriverAction.AssertVisible(pay, timeoutMs = 5_000))

    assertEquals(listOf(ALLOW_CENTER), sim.taps)
  }

  @Test
  fun `a manager without the device's answer leaves the alert alone`() {
    manager(Simulator(), NotificationAlertAnswers())
      .execute(IosDriverAction.SetPermissions(APP_ID, mapOf("notifications" to "allow")))

    val sim = Simulator(alertApp = "Shop")
    manager(sim, NotificationAlertAnswers()).getScreenState().trailblazeNodeTree

    assertEquals(emptyList(), sim.taps)
  }

  @Test
  fun `a plain screen read answers the alert and returns the app behind it`() {
    // openApp's readiness poll reads this way; without the answer it would settle on the alert.
    val sim = Simulator(alertApp = "Shop")
    val manager = manager(sim, NotificationAlertAnswers().apply { arm(APP_ID, "Shop", "allow") })

    val tree = assertNotNull(manager.getScreenState().trailblazeNodeTree)

    assertEquals(listOf(ALLOW_CENTER), sim.taps)
    assertNotNull(tree.findFirst { (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Pay" })
  }

  @Test
  fun `each app's alert gets that app's answer`() {
    val deviceAnswer = NotificationAlertAnswers()
    manager(Simulator(), deviceAnswer, displayNames = mapOf("com.example.alpha" to "Alpha"))
      .execute(IosDriverAction.SetPermissions("com.example.alpha", mapOf("notifications" to "deny")))
    manager(Simulator(), deviceAnswer)
      .execute(IosDriverAction.SetPermissions(APP_ID, mapOf("notifications" to "allow")))

    fun tapsFor(appName: String) =
      Simulator(alertApp = appName).also { manager(it, deviceAnswer).getScreenState().trailblazeNodeTree }.taps

    assertEquals(listOf(DONT_ALLOW_CENTER), tapsFor("Alpha"))
    assertEquals(listOf(ALLOW_CENTER), tapsFor("Shop"))
    assertEquals(emptyList(), tapsFor("Other"), "an app nobody armed keeps its alert")
  }

  @Test
  fun `an alert quoting an armed app's name in another case gets that app's answer`() {
    val deviceAnswer = NotificationAlertAnswers().apply {
      arm("com.example.alpha", "Alpha", "deny")
      arm(APP_ID, "Shop", "allow")
    }

    val sim = Simulator(alertApp = "SHOP")
    manager(sim, deviceAnswer).getScreenState().trailblazeNodeTree

    assertEquals(listOf(ALLOW_CENTER), sim.taps)
  }

  @Test
  fun `with one app armed, its answer holds when the alert quotes a name simctl didn't report`() {
    // A display name set only in a localized InfoPlist.strings: simctl reports CFBundleName.
    val sim = Simulator(alertApp = "Shop Localized")
    manager(sim, NotificationAlertAnswers().apply { arm(APP_ID, "Shop", "allow") }).getScreenState().trailblazeNodeTree

    assertEquals(listOf(ALLOW_CENTER), sim.taps)
  }

  @Test
  fun `an alert another manager already answered is not tapped again`() {
    val deviceAnswer = NotificationAlertAnswers().apply { arm(APP_ID, "Shop", "allow") }
    val sim = Simulator(alertApp = "Shop")
    // Both read the alert before either answered it; the first answers, then the second goes on.
    val late = manager(sim, deviceAnswer, firstRead = alertScreen("Shop"))
    manager(sim, deviceAnswer).getScreenState().trailblazeNodeTree

    val tree = assertNotNull(late.getScreenState().trailblazeNodeTree)

    assertEquals(listOf(ALLOW_CENTER), sim.taps, "the second tap would land on the app")
    assertNotNull(tree.findFirst { (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Pay" })
  }

  @Test
  fun `the capture logged before an answer change leaves the alert for the new answer`() {
    val sim = Simulator(alertApp = "Shop")
    val manager = manager(sim, NotificationAlertAnswers().apply { arm(APP_ID, "Shop", "allow") })
    val deny = IosDriverAction.SetPermissions(APP_ID, mapOf("notifications" to "deny"))

    manager.sharedScreenStateBefore(deny).trailblazeNodeTree
    assertEquals(emptyList(), sim.taps, "the old answer would have allowed notifications")

    manager.execute(deny)
    manager.getScreenState().trailblazeNodeTree
    assertEquals(listOf(DONT_ALLOW_CENTER), sim.taps)
  }

  @Test
  fun `a screenshot shows the screen the alert answer left`() {
    val sim = Simulator(alertApp = "Shop")
    val manager = manager(sim, NotificationAlertAnswers().apply { arm(APP_ID, "Shop", "allow") })

    val screenState = manager.getScreenState()

    assertEquals("app", screenState.screenshotBytes?.decodeToString(), "the hierarchy will show the app")
    assertEquals(listOf(ALLOW_CENTER), sim.taps)
  }

  @Test
  fun `a new answer waits for a tap already using the old one`() {
    val deviceAnswer = NotificationAlertAnswers().apply { arm(APP_ID, "Shop", "allow") }
    val tapping = CountDownLatch(1)
    val release = CountDownLatch(1)
    val sim = Simulator(alertApp = "Shop", onTap = {
      tapping.countDown()
      release.await(5, TimeUnit.SECONDS)
    })
    val reader = thread { manager(sim, deviceAnswer).getScreenState().trailblazeNodeTree }
    assertTrue(tapping.await(5, TimeUnit.SECONDS))

    val denied = thread { deviceAnswer.arm(APP_ID, "Shop", "deny") }
    denied.join(300)
    assertTrue(denied.isAlive, "deny returned while Allow was still being tapped")

    release.countDown()
    reader.join(5_000)
    denied.join(5_000)
    assertFalse(denied.isAlive)
  }

  @Test
  fun `every connection to one simulator sees the same answers`() {
    // A YAML run and an MCP inspection each hold their own connection to the simulator.
    val run = NotificationAlertAnswers.forSimulator("shared-sim")
    val inspection = NotificationAlertAnswers.forSimulator("shared-sim")
    run.arm(APP_ID, "Shop", "deny")

    val sim = Simulator(alertApp = "Shop")
    manager(sim, inspection).getScreenState().trailblazeNodeTree

    assertEquals(listOf(DONT_ALLOW_CENTER), sim.taps)
    assertTrue(NotificationAlertAnswers.forSimulator("other-sim").isEmpty())
  }

  @Test
  fun `a new session on the simulator leaves the alert an earlier trail answered`() {
    NotificationAlertAnswers.forSimulator("next-trail-sim").arm(APP_ID, "Shop", "allow")

    AxeConnectedDevice(udid = "next-trail-sim", deviceWidth = 402, deviceHeight = 874).onSessionStart(SessionId("next-trail"))

    val sim = Simulator(alertApp = "Shop")
    manager(sim, NotificationAlertAnswers.forSimulator("next-trail-sim")).getScreenState().trailblazeNodeTree
    assertEquals(emptyList(), sim.taps, "the earlier trail's Allow answered this trail's alert")
  }

  @Test
  fun `a session announced again keeps the answer its trail armed since`() {
    val answers = NotificationAlertAnswers()
    answers.enterSession("run-1")
    answers.arm(APP_ID, "Shop", "allow")

    // The trail run announces the session the device manager already moved the simulator onto.
    answers.enterSession("run-1")

    val sim = Simulator(alertApp = "Shop")
    manager(sim, answers).getScreenState().trailblazeNodeTree
    assertEquals(listOf(ALLOW_CENTER), sim.taps)
  }

  @Test
  fun `a late announcement of an earlier session keeps the current session's answer`() {
    val answers = NotificationAlertAnswers()
    answers.enterSession("run-1")
    answers.enterSession("run-2")
    answers.arm(APP_ID, "Shop", "allow")

    answers.announceSession("run-1")

    val sim = Simulator(alertApp = "Shop")
    manager(sim, answers).getScreenState().trailblazeNodeTree
    assertEquals(listOf(ALLOW_CENTER), sim.taps)
  }

  @Test
  fun `moving back onto an earlier session forgets the answer armed in between`() {
    val answers = NotificationAlertAnswers()
    answers.enterSession("cast")
    answers.enterSession("own")
    answers.arm(APP_ID, "Shop", "allow")

    answers.enterSession("cast")

    val sim = Simulator(alertApp = "Shop")
    manager(sim, answers).getScreenState().trailblazeNodeTree
    assertEquals(emptyList(), sim.taps, "the other session's Allow answered the cast session's alert")
  }

  @Test
  fun `an answer from a session that was replaced meanwhile is refused`() {
    val answers = NotificationAlertAnswers()
    answers.enterSession("old")
    val since = answers.generation()

    answers.enterSession("new")

    assertFalse(answers.arm(APP_ID, "Shop", "allow", since))
    val sim = Simulator(alertApp = "Shop")
    manager(sim, answers).getScreenState().trailblazeNodeTree
    assertEquals(emptyList(), sim.taps, "the replaced session's Allow answered the new session's alert")
  }

  @Test
  fun `a session change does not wait for an alert tap in flight`() {
    val answers = NotificationAlertAnswers()
    val tapping = CountDownLatch(1)
    val release = CountDownLatch(1)
    val tap = thread { answers.dismissing { tapping.countDown(); release.await(5, TimeUnit.SECONDS) } }
    tapping.await(5, TimeUnit.SECONDS)

    val entered = CountDownLatch(1)
    thread { answers.enterSession("next"); entered.countDown() }

    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS), "the session change waited on the simulator's tap")
    } finally {
      release.countDown()
      tap.join()
    }
  }

  @Test
  fun `an app whose answer was cleared keeps its alert even when one other app is armed`() {
    val deviceAnswer = NotificationAlertAnswers().apply {
      arm("com.example.alpha", "Alpha", "allow")
      disarm(APP_ID)
    }

    val sim = Simulator(alertApp = "Shop")
    manager(sim, deviceAnswer).getScreenState().trailblazeNodeTree

    assertEquals(emptyList(), sim.taps, "Alpha's Allow must not answer Shop's alert")
  }

  @Test
  fun `a disarmed app sharing a name with an armed one leaves their alert alone`() {
    // Two build variants of one app, both called "Shop".
    val deviceAnswer = NotificationAlertAnswers().apply {
      arm("com.example.app.beta", "Shop", "allow")
      arm(APP_ID, "Shop", "allow")
      disarm(APP_ID)
    }

    val sim = Simulator(alertApp = "Shop")
    manager(sim, deviceAnswer).getScreenState().trailblazeNodeTree

    assertEquals(emptyList(), sim.taps, "the beta's Allow answered an alert that may be the disarmed app's")
  }

  @Test
  fun `clearing a never-armed app sharing a name with an armed one leaves their alert alone`() {
    // Two installed build variants, both called "Shop"; only the beta was armed.
    val deviceAnswer = NotificationAlertAnswers().apply { arm("com.example.app.beta", "Shop", "allow") }
    manager(Simulator(), deviceAnswer).execute(IosDriverAction.SetPermissions(APP_ID, mapOf("notifications" to "unset")))

    val sim = Simulator(alertApp = "Shop")
    manager(sim, deviceAnswer).getScreenState().trailblazeNodeTree

    assertEquals(emptyList(), sim.taps, "the beta's Allow answered an alert that may be the cleared app's")
  }

  @Test
  fun `ios_setPermissions' notifications driver call arms the answer, and null stops it`() {
    val deviceAnswer = NotificationAlertAnswers()
    manager(Simulator(), deviceAnswer).execute(IosDriverAction.AnswerNotificationsAlert(APP_ID, allow = false))

    val denied = Simulator(alertApp = "Shop")
    manager(denied, deviceAnswer).getScreenState().trailblazeNodeTree
    assertEquals(listOf(DONT_ALLOW_CENTER), denied.taps)

    manager(Simulator(), deviceAnswer).execute(IosDriverAction.AnswerNotificationsAlert(APP_ID, allow = null))
    val left = Simulator(alertApp = "Shop")
    manager(left, deviceAnswer).getScreenState().trailblazeNodeTree
    assertEquals(emptyList(), left.taps)
  }

  /** One simulator screen: the alert for [alertApp] stays up until one of its buttons is tapped. */
  private class Simulator(@Volatile var alertApp: String? = null, private val onTap: () -> Unit = {}) {
    val taps = mutableListOf<Pair<Int, Int>>()
    fun describe(): String = alertApp?.let(::alertScreen) ?: APP_SCREEN
    fun tap(x: Int, y: Int) {
      onTap()
      taps += x to y
      alertApp = null
    }
  }

  /** A manager on [sim]; [firstRead], when given, is what its first `describe-ui` returns. */
  private fun manager(
    sim: Simulator,
    answers: NotificationAlertAnswers,
    displayNames: Map<String, String> = mapOf(APP_ID to "Shop"),
    firstRead: String? = null,
  ): AxeDeviceManager {
    var pending = firstRead
    return AxeDeviceManager(
      udid = "test-udid",
      deviceWidth = 402,
      deviceHeight = 874,
      notificationAlertAnswers = answers,
      describeUi = {
        val screen = pending ?: sim.describe()
        pending = null
        AxeCli.Result(exitCode = 0, stdout = screen, stderr = "")
      },
      tapNotificationAlert = { x, y ->
        sim.tap(x, y)
        AxeCli.Result(exitCode = 0, stdout = "", stderr = "")
      },
      appDisplayName = displayNames::get,
      captureScreenshot = { (if (sim.alertApp == null) "app" else "alert").encodeToByteArray() },
    )
  }

  private companion object {
    const val APP_ID = "com.example.app"

    /** Centres of the alert's buttons below. */
    val ALLOW_CENTER = 296 to 422
    val DONT_ALLOW_CENTER = 106 to 422

    val APP_SCREEN = """
      [
        {
          "role": "AXApplication",
          "type": "Application",
          "AXLabel": "Shop",
          "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
          "children": [
            {"role": "AXButton", "type": "Button", "AXLabel": "Pay", "frame": {"x": 16, "y": 300, "width": 370, "height": 44}}
          ]
        }
      ]
    """.trimIndent()

    fun alertScreen(appName: String) = """
      [
        {
          "role": "AXApplication",
          "type": "Application",
          "AXLabel": "Shop",
          "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
          "children": [
            {
              "role": "AXGroup",
              "type": "Group",
              "frame": {"x": 16, "y": 300, "width": 370, "height": 160},
              "children": [
                {"role": "AXStaticText", "type": "StaticText", "AXLabel": "“$appName” Would Like to Send You Notifications", "frame": {"x": 16, "y": 320, "width": 370, "height": 40}},
                {"role": "AXButton", "type": "Button", "AXLabel": "Don’t Allow", "frame": {"x": 16, "y": 400, "width": 180, "height": 44}},
                {"role": "AXButton", "type": "Button", "AXLabel": "Allow", "frame": {"x": 206, "y": 400, "width": 180, "height": 44}}
              ]
            }
          ]
        }
      ]
    """.trimIndent()
  }
}
