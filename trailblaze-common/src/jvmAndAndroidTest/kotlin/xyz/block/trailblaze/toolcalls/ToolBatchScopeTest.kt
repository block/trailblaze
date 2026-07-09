package xyz.block.trailblaze.toolcalls

import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract tests for [ToolBatchScope] in isolation — [xyz.block.trailblaze.BaseTrailblazeAgentTest]
 * and [xyz.block.trailblaze.rules.TrailblazeRunnerUtilTest] exercise it indirectly through
 * real dispatch; these pin the primitive's own lifecycle so a future refactor of that file has a
 * safety net independent of those integration-level tests.
 */
class ToolBatchScopeTest {

  /** Always leave the scope closed between cases so a failing test doesn't leak state. */
  @AfterTest
  fun cleanup() {
    if (ToolBatchScope.isActive()) ToolBatchScope.exit()
    repeat(SnapshotCache.frameDepth()) { SnapshotCache.popFrame() }
    ToolExecutionContextThreadLocal.clear()
  }

  private fun buildContext() = TrailblazeToolExecutionContext(
    screenState = null,
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
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  @Test
  fun `isActive reflects enter and exit`() {
    assertFalse(ToolBatchScope.isActive())
    ToolBatchScope.enter()
    assertTrue(ToolBatchScope.isActive())
    ToolBatchScope.exit()
    assertFalse(ToolBatchScope.isActive())
  }

  @Test
  fun `enter while already active fails fast`() {
    ToolBatchScope.enter()
    assertFailsWith<IllegalStateException> { ToolBatchScope.enter() }
  }

  @Test
  fun `exit when not active is a no-op`() {
    // Doesn't throw, doesn't touch SnapshotCache or the context ThreadLocal.
    val initialFrameDepth = SnapshotCache.frameDepth()
    ToolBatchScope.exit()
    assertEquals(initialFrameDepth, SnapshotCache.frameDepth())
    assertFalse(ToolBatchScope.isActive())
  }

  @Test
  fun `exit is idempotent`() {
    ToolBatchScope.enter()
    ToolBatchScope.contextOrBuild { buildContext() }
    ToolBatchScope.exit()
    ToolBatchScope.exit() // second call must not throw or double-pop
    assertFalse(ToolBatchScope.isActive())
  }

  @Test
  fun `contextOrBuild without an active scope throws`() {
    assertFailsWith<IllegalStateException> { ToolBatchScope.contextOrBuild { buildContext() } }
  }

  @Test
  fun `contextOrBuild builds once and reuses the same instance`() {
    var buildCount = 0
    ToolBatchScope.enter()
    val first = ToolBatchScope.contextOrBuild {
      buildCount++
      buildContext()
    }
    val second = ToolBatchScope.contextOrBuild {
      buildCount++
      buildContext()
    }
    assertSame(first, second)
    assertEquals(1, buildCount)
  }

  @Test
  fun `contextOrBuild installs the context ThreadLocal and pushes one SnapshotCache frame`() {
    val initialFrameDepth = SnapshotCache.frameDepth()
    ToolBatchScope.enter()
    assertNull(ToolExecutionContextThreadLocal.get())

    val ctx = ToolBatchScope.contextOrBuild { buildContext() }

    assertSame(ctx, ToolExecutionContextThreadLocal.get())
    assertEquals(initialFrameDepth + 1, SnapshotCache.frameDepth())

    // A second dispatch within the same scope must not push a second frame or reinstall.
    ToolBatchScope.contextOrBuild { buildContext() }
    assertEquals(initialFrameDepth + 1, SnapshotCache.frameDepth())

    ToolBatchScope.exit()
    assertNull(ToolExecutionContextThreadLocal.get())
    assertEquals(initialFrameDepth, SnapshotCache.frameDepth())
  }

  @Test
  fun `exit without ever building a context does not pop a frame or clear the ThreadLocal`() {
    // A scope opened but never dispatched into (empty recording) established neither the frame
    // nor the ThreadLocal install, so exit() must leave both untouched — popping here would
    // corrupt a frame pushed by an unrelated, already-active caller.
    val initialFrameDepth = SnapshotCache.frameDepth()
    SnapshotCache.pushFrame() // simulate an outer, unrelated frame already on the stack
    val unrelatedContext = buildContext()
    ToolExecutionContextThreadLocal.install(unrelatedContext)

    ToolBatchScope.enter()
    ToolBatchScope.exit()

    assertEquals(initialFrameDepth + 1, SnapshotCache.frameDepth())
    assertSame(unrelatedContext, ToolExecutionContextThreadLocal.get())

    ToolExecutionContextThreadLocal.clear()
    SnapshotCache.popFrame()
  }
}
