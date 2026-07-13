package xyz.block.trailblaze.toolcalls

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Pins the lazy screen-state contract on [TrailblazeToolExecutionContext.screenState] — the
 * dispatch-side mechanism behind https://github.com/block/trailblaze/issues/210: dispatchers pass
 * null, tools that never read the field never pay a capture, and tools that DO read it always see
 * a current (captured-on-read) state.
 */
class TrailblazeToolExecutionContextTest {

  private class FakeScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  private fun context(
    screenState: ScreenState? = null,
    screenStateProvider: (() -> ScreenState)? = null,
  ) = TrailblazeToolExecutionContext(
    screenState = screenState,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    screenStateProvider = screenStateProvider,
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  @Test
  fun `null field with a provider captures on first read and caches for later reads`() {
    var calls = 0
    val captured = FakeScreenState()
    val ctx = context(screenStateProvider = {
      calls++
      captured
    })

    val first = ctx.screenState
    val second = ctx.screenState

    assertThat(calls).isEqualTo(1)
    assertThat(first).isSameInstanceAs(captured)
    assertThat(second).isSameInstanceAs(captured)
  }

  @Test
  fun `preset screenState is returned without invoking the provider`() {
    var calls = 0
    val preset = FakeScreenState()
    val ctx = context(
      screenState = preset,
      screenStateProvider = {
        calls++
        FakeScreenState()
      },
    )

    assertThat(ctx.screenState).isSameInstanceAs(preset)
    assertThat(calls).isEqualTo(0)
  }

  @Test
  fun `assigning null re-arms lazy capture`() {
    // The batch loop relies on this: reassigning null before a dispatch clears the previous
    // dispatch's cached state so a reading tool captures CURRENT UI, not the stale snapshot.
    var calls = 0
    val ctx = context(screenStateProvider = {
      calls++
      FakeScreenState()
    })

    val first = ctx.screenState
    ctx.screenState = null
    val second = ctx.screenState

    assertThat(calls).isEqualTo(2)
    assertThat(second === first).isEqualTo(false)
  }

  @Test
  fun `no provider and no state reads null`() {
    assertThat(context().screenState).isNull()
  }

  @Test
  fun `concurrent first reads capture exactly once`() {
    // Parallel nested callbacks (`Promise.all([ctx.tools.a(), ctx.tools.b()])`) share one
    // context — the lazy capture must be single-flight, not once per racing reader. The provider
    // parks the first reader on a latch so the second reader provably arrives mid-capture.
    var calls = 0
    val captured = FakeScreenState()
    val firstReaderInProvider = CountDownLatch(1)
    val releaseProvider = CountDownLatch(1)
    val ctx = context(screenStateProvider = {
      firstReaderInProvider.countDown()
      releaseProvider.await()
      calls++
      captured
    })

    val results = arrayOfNulls<ScreenState>(2)
    val reader1 = thread { results[0] = ctx.screenState }
    firstReaderInProvider.await()
    val reader2 = thread { results[1] = ctx.screenState }
    releaseProvider.countDown()
    reader1.join()
    reader2.join()

    assertThat(calls).isEqualTo(1)
    assertThat(results[0]).isSameInstanceAs(captured)
    assertThat(results[1]).isSameInstanceAs(captured)
  }
}
