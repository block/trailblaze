package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionId

class TrailblazeLoggerTest {

  private val logger = TrailblazeLogger(
    logEmitter = LogEmitter { },
    screenStateLogger = ScreenStateLogger { "" },
  )

  /** Minimal valid PNG header — what a healthy capture returns. */
  private val pngHeaderBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

  private fun session() = TrailblazeSession(
    sessionId = SessionId("test_session"),
    startTime = Clock.System.now(),
  )

  private fun screenStateWith(bytes: ByteArray?): ScreenState = object : ScreenState {
    override val screenshotBytes: ByteArray? = bytes
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(nodeId = 1, className = "FrameLayout")
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  @Test
  fun `logScreenState drops empty screenshot bytes - no file written`() {
    // Regression for the 0-byte PNG defect: a degraded device returns a non-null but empty
    // ByteArray. It must be treated as "no screenshot" — return null and never write a file.
    val writes = mutableListOf<TrailblazeScreenStateLog>()
    val logger = TrailblazeLogger(
      logEmitter = LogEmitter { },
      screenStateLogger = ScreenStateLogger { writes.add(it); it.fileName },
    )

    val fileName = logger.logScreenState(session(), screenStateWith(ByteArray(0)))

    assertNull("empty bytes must not yield a screenshot filename", fileName)
    assertTrue("empty bytes must not write a screenshot file", writes.isEmpty())
  }

  @Test
  fun `logScreenState keeps real screenshot bytes - file written`() {
    // Positive control: a healthy capture is logged exactly as before.
    val writes = mutableListOf<TrailblazeScreenStateLog>()
    val logger = TrailblazeLogger(
      logEmitter = LogEmitter { },
      screenStateLogger = ScreenStateLogger { writes.add(it); it.fileName },
    )

    val fileName = logger.logScreenState(session(), screenStateWith(pngHeaderBytes))

    assertTrue("real bytes must yield a screenshot filename", fileName != null && fileName.isNotEmpty())
    assertEquals("real bytes must write exactly one screenshot file", 1, writes.size)
  }

  @Test
  fun `logSnapshot emits no screenshot-bearing log for empty bytes`() {
    // The 0-byte defect at the report layer: an empty capture must not produce a
    // TrailblazeSnapshotLog, so the report renders no broken blank keyframe.
    val emitted = mutableListOf<TrailblazeLog>()
    val logger = TrailblazeLogger(
      logEmitter = LogEmitter(emitted::add),
      screenStateLogger = ScreenStateLogger { it.fileName },
    )

    val fileName = logger.logSnapshot(session(), screenStateWith(ByteArray(0)))

    assertNull(fileName)
    assertFalse(
      "empty bytes must not emit a TrailblazeSnapshotLog",
      emitted.any { it is TrailblazeLog.TrailblazeSnapshotLog },
    )
  }

  @Test
  fun `logSnapshot emits a screenshot-bearing log for real bytes`() {
    // Positive control mirroring the negative one above.
    val emitted = mutableListOf<TrailblazeLog>()
    val logger = TrailblazeLogger(
      logEmitter = LogEmitter(emitted::add),
      screenStateLogger = ScreenStateLogger { it.fileName },
    )

    val fileName = logger.logSnapshot(session(), screenStateWith(pngHeaderBytes))

    assertTrue(fileName != null && fileName.isNotEmpty())
    val snapshots = emitted.filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>()
    assertEquals(1, snapshots.size)
    assertTrue(snapshots.single().screenshotFile.isNotEmpty())
  }

  @Test
  fun `Tool_Call renders as tool_use and Tool_Result renders as tool_result`() {
    // Koog 1.0.0: Tool.Call / Tool.Result are now `MessagePart` parts that live inside an
    // enclosing Assistant / User message (the role-level Tool message was removed).
    val callPart = MessagePart.Tool.Call(
      id = "call-1",
      tool = "tap",
      args = "{\"x\":1,\"y\":2}",
    )
    val resultPart = MessagePart.Tool.Result(
      id = "call-1",
      tool = "tap",
      output = "ok",
      isError = false,
    )
    val callMessage = Message.Assistant(
      part = callPart,
      metaInfo = ResponseMetaInfo.create(KoogClock.System),
    )
    val resultMessage = Message.User(
      part = resultPart,
      metaInfo = RequestMetaInfo.create(KoogClock.System),
    )

    val rendered = with(logger) { listOf<Message>(callMessage, resultMessage).toTrailblazeLlmMessages() }

    assertEquals(2, rendered.size)
    assertEquals("tool_use", rendered[0].role)
    assertEquals("tool_result", rendered[1].role)
    assertEquals("tap", rendered[0].toolName)
    assertEquals("tap", rendered[1].toolName)
    assertTrue(
      "tool name should appear in rendered call payload",
      rendered[0].message!!.contains("**tap**"),
    )
    assertTrue(
      "tool result content should appear in rendered result payload",
      rendered[1].message!!.contains("ok"),
    )
  }
}
