package xyz.block.trailblaze.host.axe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.host.ios.IosDriverAction

/**
 * One tool call used to spawn four or five `axe describe-ui` processes against a screen nothing
 * had touched. These pin the rule that replaced that: inside a [AxeDeviceManager.shareScreenReads]
 * span, reads share the latest capture until an action that can change the screen runs.
 */
class AxeDeviceManagerSharedReadsTest {

  private val withButton = screen("Pay")
  private val withoutButton = screen("Loading")

  private val pay = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "^Pay$"))

  /** Serves [screens] in order (repeating the last) and counts the reads. */
  private class FakeDescribeUi(vararg screens: String) {
    private val queue = screens.toList()
    var calls = 0
      private set

    fun read(): AxeCli.Result {
      val json = queue[minOf(calls, queue.lastIndex)]
      calls++
      return AxeCli.Result(exitCode = 0, stdout = json, stderr = "")
    }
  }

  private fun manager(fake: FakeDescribeUi) = AxeDeviceManager(
    udid = "test-udid",
    deviceWidth = 402,
    deviceHeight = 874,
    describeUi = fake::read,
  )

  @Test
  fun `a tool's read, its log capture and an assert's first poll share one describe-ui`() {
    val fake = FakeDescribeUi(withButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      assertNotNull(manager.sharedScreenState().trailblazeNodeTree) // the tool's context read
      assertNotNull(manager.sharedScreenState().trailblazeNodeTree) // the pre-action log capture
      manager.execute(IosDriverAction.AssertVisible(pay, timeoutMs = 1_000))
    }

    assertEquals(1, fake.calls)
  }

  @Test
  fun `a plain read inside a span is always fresh, so a caller waiting for a change sees it`() {
    // openApp's shape: capture a baseline, launch through simctl (no driver action, so nothing
    // drops the kept capture), then poll until the screen differs from the baseline.
    val fake = FakeDescribeUi(withoutButton, withButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      assertNotNull(manager.sharedScreenState().trailblazeNodeTree) // the tool's context read
      val polled = assertNotNull(manager.getScreenState().trailblazeNodeTree)
      assertNotNull(polled.findFirst { (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Pay" })
    }

    assertEquals(2, fake.calls)
  }

  @Test
  fun `a poll's first try shares the latest plain read`() {
    val fake = FakeDescribeUi(withButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      manager.getScreenState().trailblazeNodeTree
      manager.execute(IosDriverAction.AssertVisible(pay, timeoutMs = 1_000))
    }

    assertEquals(1, fake.calls)
  }

  @Test
  fun `without a span every read is fresh`() {
    val fake = FakeDescribeUi(withButton)
    val manager = manager(fake)

    manager.sharedScreenState().trailblazeNodeTree
    manager.sharedScreenState().trailblazeNodeTree
    manager.execute(IosDriverAction.AssertVisible(pay, timeoutMs = 1_000))

    assertEquals(3, fake.calls)
  }

  @Test
  fun `an action that can change the screen ends the sharing`() {
    val fake = FakeDescribeUi(withButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      manager.sharedScreenState().trailblazeNodeTree
      manager.execute(IosDriverAction.WaitForSettle(timeoutMs = 0))
      manager.sharedScreenState().trailblazeNodeTree
    }

    assertEquals(2, fake.calls)
  }

  @Test
  fun `a poll that misses on the shared capture re-reads instead of re-checking the same tree`() {
    // The shared capture predates the button; only a fresh read can see it arrive.
    val fake = FakeDescribeUi(withoutButton, withButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      manager.sharedScreenState().trailblazeNodeTree
      manager.execute(IosDriverAction.AssertVisible(pay, timeoutMs = 5_000))
    }

    assertEquals(2, fake.calls)
  }

  @Test
  fun `assertNotVisible still needs a fresh capture to agree with the shared one`() {
    val fake = FakeDescribeUi(withoutButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      manager.sharedScreenState().trailblazeNodeTree
      manager.execute(IosDriverAction.AssertNotVisible(pay, timeoutMs = 5_000))
    }

    assertEquals(2, fake.calls)
  }

  @Test
  fun `a later read in the span sees the capture the poll matched on`() {
    val fake = FakeDescribeUi(withoutButton, withButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      manager.sharedScreenState().trailblazeNodeTree
      manager.execute(IosDriverAction.AssertVisible(pay, timeoutMs = 5_000))
      val afterAssert = assertNotNull(manager.sharedScreenState().trailblazeNodeTree)
      assertNotNull(afterAssert.findFirst { (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Pay" })
    }

    assertEquals(2, fake.calls)
  }

  @Test
  fun `closing the span drops the kept capture`() {
    val fake = FakeDescribeUi(withButton)
    val manager = manager(fake)

    manager.shareScreenReads().use { manager.sharedScreenState().trailblazeNodeTree }
    manager.shareScreenReads().use { manager.sharedScreenState().trailblazeNodeTree }

    assertEquals(2, fake.calls)
  }

  @Test
  fun `a nested span keeps sharing until the outer one closes`() {
    val fake = FakeDescribeUi(withButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      manager.shareScreenReads().use { manager.sharedScreenState().trailblazeNodeTree }
      manager.sharedScreenState().trailblazeNodeTree
    }

    assertEquals(1, fake.calls)
  }

  @Test
  fun `a failed action still ends the sharing`() {
    val fake = FakeDescribeUi(withoutButton)
    val manager = manager(fake)

    manager.shareScreenReads().use {
      manager.sharedScreenState().trailblazeNodeTree
      assertFailsWith<IllegalStateException> {
        // Element never appears and there is no fallback, so the tap throws without tapping.
        manager.execute(IosDriverAction.TapOnElement(pay, timeoutMs = 200))
      }
      val callsBefore = fake.calls
      manager.sharedScreenState().trailblazeNodeTree
      assertEquals(callsBefore + 1, fake.calls)
    }
  }

  private fun screen(label: String) = """
    [
      {
        "role": "AXApplication",
        "type": "Application",
        "AXLabel": "Shop",
        "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
        "children": [
          {
            "role": "AXButton",
            "type": "Button",
            "AXLabel": "$label",
            "frame": {"x": 16, "y": 300, "width": 370, "height": 44}
          }
        ]
      }
    ]
  """.trimIndent()
}
