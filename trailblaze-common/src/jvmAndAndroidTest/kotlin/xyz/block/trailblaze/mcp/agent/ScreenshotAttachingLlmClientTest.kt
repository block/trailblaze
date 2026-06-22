package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import kotlin.test.Test

/**
 * Unit tests for [ScreenshotAttachingLlmClient] — the decorator that gives the Koog strategy-graph
 * agent set-of-mark visual perception. Covers both the pure prompt rewrite
 * ([attachScreenshotToLatestUserMessage]: anchoring, stale-image stripping, no-op, idempotence) and
 * the decorator's gating + fallback (vision/tools/kill-switch gates, null bytes, provider failure),
 * without standing up the graph or a device.
 */
class ScreenshotAttachingLlmClientTest {

  private val screenshotBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)

  private fun meta() = RequestMetaInfo.create(KoogClock.System)

  private fun promptOf(vararg messages: Message): Prompt = Prompt(
    messages = messages.toList(),
    id = "test",
    params = LLMParams(temperature = null, speculation = null, schema = null),
  )

  private fun imageAttachment() = MessagePart.Attachment(
    source = AttachmentSource.Image(
      content = AttachmentContent.Binary.Bytes(screenshotBytes),
      format = "png",
    ),
  )

  private fun Message.imageCount(): Int = (this as? Message.User)
    ?.parts
    ?.count { it is MessagePart.Attachment && it.source is AttachmentSource.Image }
    ?: 0

  // ---------------------------------------------------------------------------------------------
  // attachScreenshotToLatestUserMessage — pure prompt rewrite
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `attaches one image to the only user message and leaves system untouched`() {
    val prompt = promptOf(
      Message.System(content = "system prompt", metaInfo = meta()),
      Message.User(content = "do the thing", metaInfo = meta()),
    )

    val result = attachScreenshotToLatestUserMessage(prompt, screenshotBytes)

    // System message is unchanged; the user message gained exactly one image.
    assertThat(result.messages[0].imageCount()).isEqualTo(0)
    assertThat(result.messages[1].imageCount()).isEqualTo(1)
    // The original text content of the user message is preserved alongside the new attachment.
    val userParts = (result.messages[1] as Message.User).parts
    assertThat(userParts.any { it is MessagePart.Text }).isEqualTo(true)
  }

  @Test
  fun `no user message is a no-op (returns the same prompt instance)`() {
    val prompt = promptOf(Message.System(content = "system only", metaInfo = meta()))

    val result = attachScreenshotToLatestUserMessage(prompt, screenshotBytes)

    assertThat(result).isSameInstanceAs(prompt)
  }

  @Test
  fun `anchors to the last USER message, not a trailing assistant message`() {
    val prompt = promptOf(
      Message.System(content = "system", metaInfo = meta()),
      Message.User(content = "the screen", metaInfo = meta()),
      // A trailing assistant message must NOT receive the image, and must not shadow the user.
      Message.Assistant(content = "assistant turn", metaInfo = ResponseMetaInfo.create(KoogClock.System)),
    )

    val result = attachScreenshotToLatestUserMessage(prompt, screenshotBytes)

    assertThat(result.messages[1].imageCount()).isEqualTo(1)
    assertThat(result.messages[2].imageCount()).isEqualTo(0)
  }

  @Test
  fun `strips a stale image from an earlier user message and attaches only to the latest`() {
    val prompt = promptOf(
      Message.System(content = "system", metaInfo = meta()),
      // An earlier turn that already carries a (now stale) screenshot.
      Message.User(
        parts = listOf(MessagePart.Text(text = "older screen"), imageAttachment()),
        metaInfo = meta(),
      ),
      // The freshest user message — this is the one the model is about to reason over.
      Message.User(content = "latest screen", metaInfo = meta()),
    )

    val result = attachScreenshotToLatestUserMessage(prompt, screenshotBytes)

    // Stale image removed from the earlier user message...
    assertThat(result.messages[1].imageCount()).isEqualTo(0)
    // ...and exactly one image lives on the latest user message.
    assertThat(result.messages[2].imageCount()).isEqualTo(1)
  }

  @Test
  fun `is idempotent — re-running keeps exactly one image on the latest user message`() {
    val prompt = promptOf(
      Message.System(content = "system", metaInfo = meta()),
      Message.User(content = "do the thing", metaInfo = meta()),
    )

    val once = attachScreenshotToLatestUserMessage(prompt, screenshotBytes)
    val twice = attachScreenshotToLatestUserMessage(once, screenshotBytes)

    assertThat(twice.messages[1].imageCount()).isEqualTo(1)
  }

  // ---------------------------------------------------------------------------------------------
  // ScreenshotAttachingLlmClient — gating + fallback (what actually reaches the delegate)
  // ---------------------------------------------------------------------------------------------

  // Build the test models off the real Koog capability id (`LLMCapability.Vision.Image.id` == "image"),
  // NOT the literal "vision" string the hardcoded TrailblazeLlmModels constants happen to use — that
  // string matches no Koog capability, so it would (correctly) read as non-vision. Using the canonical
  // id keeps this test asserting the decorator's gate, not a quirk of a particular model constant.
  private val visionModel = TrailblazeLlmModels.GPT_4O_MINI.copy(
    capabilityIds = listOf(LLMCapability.Vision.Image.id, LLMCapability.Tools.id),
  )
  private val textOnlyModel = TrailblazeLlmModels.GPT_4O_MINI.copy(
    capabilityIds = listOf(LLMCapability.Tools.id),
  )
  private val stubTools = listOf(
    ToolDescriptor(name = "stub", description = "stub", requiredParameters = emptyList()),
  )

  /** Records the prompt the decorator forwards, so a test can assert what the model actually sees. */
  private class CapturingLlmClient : LLMClient() {
    var lastPrompt: Prompt? = null
      private set

    override fun llmProvider(): LLMProvider = TrailblazeLlmProvider.NONE_KOOG_LLM_PROVIDER
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
      lastPrompt = prompt
      return Message.Assistant(content = "ok", metaInfo = ResponseMetaInfo.create(KoogClock.System))
    }
    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
      throw NotImplementedError()
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
      throw NotImplementedError()
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw NotImplementedError()
    override fun close() = Unit
  }

  private class FakeScreenState(
    override val annotatedScreenshotBytes: ByteArray?,
  ) : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode? = null
    override val annotationElements: List<AnnotationElement>? = null
  }

  private fun textPrompt() = promptOf(
    Message.System(content = "system", metaInfo = meta()),
    Message.User(content = "do the thing", metaInfo = meta()),
  )

  @Test
  fun `vision model with tools and bytes attaches the screenshot to the forwarded prompt`() {
    val capturing = CapturingLlmClient()
    val client = ScreenshotAttachingLlmClient(
      delegate = capturing,
      screenStateProvider = { FakeScreenState(screenshotBytes) },
      trailblazeLlmModel = visionModel,
      enabled = true,
    )

    runBlocking { client.execute(textPrompt(), visionModel.toKoogLlmModel(), stubTools) }

    assertThat(capturing.lastPrompt!!.messages.last().imageCount()).isEqualTo(1)
  }

  @Test
  fun `text-only model forwards the prompt unchanged`() {
    val capturing = CapturingLlmClient()
    val client = ScreenshotAttachingLlmClient(
      delegate = capturing,
      screenStateProvider = { FakeScreenState(screenshotBytes) },
      trailblazeLlmModel = textOnlyModel,
      enabled = true,
    )
    val input = textPrompt()

    runBlocking { client.execute(input, textOnlyModel.toKoogLlmModel(), stubTools) }

    assertThat(capturing.lastPrompt).isSameInstanceAs(input)
  }

  @Test
  fun `no tools (history-compression call) forwards the prompt unchanged`() {
    val capturing = CapturingLlmClient()
    val client = ScreenshotAttachingLlmClient(
      delegate = capturing,
      screenStateProvider = { FakeScreenState(screenshotBytes) },
      trailblazeLlmModel = visionModel,
      enabled = true,
    )
    val input = textPrompt()

    runBlocking { client.execute(input, visionModel.toKoogLlmModel(), emptyList()) }

    assertThat(capturing.lastPrompt).isSameInstanceAs(input)
  }

  @Test
  fun `null screenshot bytes forwards the prompt unchanged`() {
    val capturing = CapturingLlmClient()
    val client = ScreenshotAttachingLlmClient(
      delegate = capturing,
      screenStateProvider = { FakeScreenState(annotatedScreenshotBytes = null) },
      trailblazeLlmModel = visionModel,
      enabled = true,
    )
    val input = textPrompt()

    runBlocking { client.execute(input, visionModel.toKoogLlmModel(), stubTools) }

    assertThat(capturing.lastPrompt).isSameInstanceAs(input)
  }

  @Test
  fun `screen capture failure falls back to the text-only prompt without throwing`() {
    val capturing = CapturingLlmClient()
    val client = ScreenshotAttachingLlmClient(
      delegate = capturing,
      screenStateProvider = { throw RuntimeException("capture boom") },
      trailblazeLlmModel = visionModel,
      enabled = true,
    )
    val input = textPrompt()

    runBlocking { client.execute(input, visionModel.toKoogLlmModel(), stubTools) }

    // The run survived (delegate was still called) with the unmodified prompt.
    assertThat(capturing.lastPrompt).isSameInstanceAs(input)
  }

  @Test
  fun `kill-switch disables attachment even for a vision model with tools and bytes`() {
    val capturing = CapturingLlmClient()
    val client = ScreenshotAttachingLlmClient(
      delegate = capturing,
      screenStateProvider = { FakeScreenState(screenshotBytes) },
      trailblazeLlmModel = visionModel,
      enabled = false,
    )
    val input = textPrompt()

    runBlocking { client.execute(input, visionModel.toKoogLlmModel(), stubTools) }

    assertThat(capturing.lastPrompt).isSameInstanceAs(input)
  }
}
