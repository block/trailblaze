package xyz.block.trailblaze.android.test

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.android.test.hierarchy.InProcessAccessibilityTree
import xyz.block.trailblaze.android.test.tools.AndroidTestTapTool
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * A link inside a run of text, tapped by its words.
 *
 * The platform's accessibility tree gives each link of a Compose text a node of its own, a
 * `TextView` reading the linked words, and recordings name the link that way. Compose's own tree
 * holds the link as a clickable child with no text, so without a projection a recorded tap on the
 * link finds nothing to tap.
 */
class InTextLinkOnDeviceTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private val linkClicks = AtomicInteger()

  @Test
  fun aLinkReadsAsThePlatformTreeReadsIt() {
    showTextWithLink()

    val platform = checkNotNull(InProcessAccessibilityTree.capture(fresh = true)) { "no accessibility tree" }.tree
      .flatten()
      .mapNotNull { (it.driverDetail as? DriverNodeDetail.AndroidAccessibility)?.takeIf { d -> d.text == LINK } }
      .map { it.className to it.text }
    val host = composeRule.activity
    val target = RuleBackedAndroidTestTarget(activityProvider = { host }, composeTestRule = composeRule)
    val collected = AndroidHybridHierarchyCollector.collect(host, target).trailblazeTree
      .flatten()
      .mapNotNull { (it.driverDetail as? DriverNodeDetail.Compose)?.takeIf { d -> d.text == LINK } }
      .map { it.accessibilityClassName to it.text }

    assertEquals(listOf("android.widget.TextView" to LINK), platform, "the platform tree publishes the link as its own node")
    assertEquals(platform, collected)
  }

  @Test
  fun aRecordedTapOnTheLinksWordsFollowsTheLink() {
    showTextWithLink()
    val host = composeRule.activity
    val target = RuleBackedAndroidTestTarget(activityProvider = { host }, composeTestRule = composeRule)
    val agent = agentFor(target)

    val result = agent.runTrailblazeTools(
      tools = listOf(
        AndroidTestTapTool(
          TrailblazeNodeSelector(androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^$LINK$")),
        ),
      ),
      traceId = null,
      screenState = null,
      elementComparator = NoOpElementComparator,
      screenStateProvider = agent.screenStateProvider,
    ).result

    assertIs<TrailblazeToolResult.Success>(result, "the link is tappable by its words, got: $result")
    composeRule.waitForIdle()
    assertEquals(1, linkClicks.get(), "the tap followed the link")
  }

  private fun showTextWithLink() {
    composeRule.setContent {
      Text(
        buildAnnotatedString {
          append("Read the ")
          withLink(LinkAnnotation.Clickable("terms") { linkClicks.incrementAndGet() }) { append(LINK) }
          append(".")
        },
      )
    }
    composeRule.waitForIdle()
  }

  private fun agentFor(target: AndroidTestTarget): AndroidTestTrailblazeAgent {
    val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
    return AndroidTestTrailblazeAgent(
      target = target,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "instrumentation",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_TEST,
          widthPixels = metrics.widthPixels,
          heightPixels = metrics.heightPixels,
        )
      },
      sessionProvider = {
        TrailblazeSession(sessionId = SessionId("in_text_link_on_device"), startTime = Clock.System.now())
      },
    )
  }

  private fun TrailblazeNode.flatten(): List<TrailblazeNode> = listOf(this) + children.flatMap { it.flatten() }

  private companion object {
    const val LINK = "terms of service"
  }
}
