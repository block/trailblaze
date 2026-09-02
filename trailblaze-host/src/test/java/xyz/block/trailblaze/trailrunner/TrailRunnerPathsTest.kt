package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus

class TrailRunnerPathsTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun dirWith(vararg fileNames: String): File {
    val dir = tmp.newFolder()
    fileNames.forEach { File(dir, it).writeText("x") }
    return dir
  }

  @Test
  fun `a bare unified trail-yaml counts as trails`() {
    assertTrue(containsTrails(dirWith("trail.yaml")))
  }

  @Test
  fun `a classifier-named recording counts as trails`() {
    assertTrue(containsTrails(dirWith("android-phone.trail.yaml")))
  }

  @Test
  fun `an NL-only definition counts as trails`() {
    // Deliberate: a folder holding only NL trails is still a runnable trails workspace.
    assertTrue(containsTrails(dirWith("blaze.yaml")))
  }

  @Test
  fun `unrelated yaml does not count as trails`() {
    assertFalse(containsTrails(dirWith("notes.yaml", "config.yaml")))
  }

  @Test
  fun `a nested bare unified trail-yaml is found within the scan depth`() {
    val root = tmp.newFolder()
    val nested = File(root, "suite/case").apply { mkdirs() }
    File(nested, "trail.yaml").writeText("x")
    assertTrue(containsTrails(root))
  }

  private fun sessionOn(instanceId: String, vararg classifiers: String): SessionInfo = SessionInfo(
    sessionId = SessionId("session_$instanceId"),
    latestStatus = SessionStatus.Unknown,
    timestamp = Instant.fromEpochMilliseconds(0),
    durationMs = 0,
    trailFilePath = null,
    hasRecordedSteps = true,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId, TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 1920,
      classifiers = classifiers.map { TrailblazeDeviceClassifier(it) },
    ),
  )

  @Test
  fun `a session summary names the device instance the run actually used`() {
    assertEquals("emulator-5556", toSessionSummary(sessionOn("emulator-5556", "android", "tablet")).deviceInstanceId)
  }

  @Test
  fun `two devices sharing a classifier stay distinguishable`() {
    // `device` is a list of classifiers, so two connected emulators of the same shape produce the
    // same string. Retrying "on the same device" can only work off the instance id.
    val one = toSessionSummary(sessionOn("emulator-5554", "android", "phone"))
    val two = toSessionSummary(sessionOn("emulator-5556", "android", "phone"))
    assertEquals(one.device, two.device)
    assertNotEquals(one.deviceInstanceId, two.deviceInstanceId)
  }

  @Test
  fun `an electron session names the device instance discovery advertises, not its per-session one`() {
    // The Electron runner mints a per-session instance id nothing ever advertises. A summary
    // naming it can't be resolved back to a device, so "retry on the same device" silently lands
    // on whatever web device happens to be first.
    val info = SessionInfo(
      sessionId = SessionId("session_electron"),
      latestStatus = SessionStatus.Unknown,
      timestamp = Instant.fromEpochMilliseconds(0),
      durationMs = 0,
      trailFilePath = null,
      hasRecordedSteps = true,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId("playwright-electron-94372894", TrailblazeDevicePlatform.WEB),
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
        widthPixels = 1280,
        heightPixels = 800,
        classifiers = listOf(TrailblazeDeviceClassifier("web")),
        advertisedInstanceId = WebInstanceIds.PLAYWRIGHT_ELECTRON,
      ),
    )
    assertEquals(WebInstanceIds.PLAYWRIGHT_ELECTRON, toSessionSummary(info).deviceInstanceId)
  }

  @Test
  fun `a session with no captured device has no instance id`() {
    val info = SessionInfo(
      sessionId = SessionId("session_none"),
      latestStatus = SessionStatus.Unknown,
      timestamp = Instant.fromEpochMilliseconds(0),
      durationMs = 0,
      trailFilePath = null,
      hasRecordedSteps = false,
    )
    assertNull(toSessionSummary(info).deviceInstanceId)
  }
}
