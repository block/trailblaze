package xyz.block.trailblaze.mcp.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.time.KoogClock
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import xyz.block.trailblaze.mcp.agent.KoogStrategyGraphAgent.Companion.STALE_SCREEN_OUTPUT_THRESHOLD_CHARS
import xyz.block.trailblaze.mcp.agent.KoogStrategyGraphAgent.Companion.STALE_SCREEN_PLACEHOLDER
import xyz.block.trailblaze.mcp.agent.KoogStrategyGraphAgent.Companion.pruneScreenStateHistory
import kotlin.test.Test

/**
 * [KoogStrategyGraphAgent.pruneScreenStateHistory] — the rewrite that keeps only the latest screen
 * state in the prompt so context stays flat across a multi-snapshot run.
 *
 * Every KOOG request passes through this, and its failure mode is silent: if it stops recognising a
 * stale payload, nothing breaks — the prompt just grows every turn until cost and latency climb and
 * the model starts reasoning over screens that no longer exist. Nothing else in the graph reports
 * that, so the properties are pinned here.
 *
 * The threshold test is load-bearing beyond the obvious. `Tool.Result` stores a
 * `List<ContentPart>`, and `output` is a derived view over it — so a result built from parts (which
 * is how Koog itself builds them) has to report a length through `output` for the size comparison
 * to fire at all. A change that decoupled the two would leave this pruning permanently inert.
 */
class KoogScreenStatePruningTest {

  private fun meta() = RequestMetaInfo.create(KoogClock.System)

  private fun promptOf(vararg parts: MessagePart.RequestPart): Prompt =
    promptOfMessages(Message.User(parts = parts.toList(), metaInfo = meta()))

  private fun promptOfMessages(vararg messages: Message): Prompt = Prompt(
    messages = messages.toList(),
    id = "test",
    params = LLMParams(temperature = null, speculation = null, schema = null),
  )

  private fun userMessage(vararg parts: MessagePart.RequestPart) =
    Message.User(parts = parts.toList(), metaInfo = meta())

  /**
   * One result per message, i.e. one per turn — which is what makes the earlier ones *older*.
   * Packing several into a single message instead would make them all current perception, and the
   * rule under test would (correctly) keep every one of them.
   */
  private fun promptOfTurns(vararg results: MessagePart.Tool.Result): Prompt =
    promptOfMessages(*results.map { userMessage(it) }.toTypedArray())

  /** Built from `parts`, not the `output` convenience constructor — see the class kdoc. */
  private fun resultOf(id: String, vararg parts: MessagePart.ContentPart) = MessagePart.Tool.Result(
    id = id,
    tool = "snapshot",
    parts = parts.toList(),
  )

  private fun screenResult(id: String, text: String) = resultOf(id, MessagePart.Text(text))

  private fun imageAttachment() = MessagePart.Attachment(
    source = AttachmentSource.Image(
      content = AttachmentContent.Binary.Bytes(byteArrayOf(0x12, 0x34)),
      format = "png",
    ),
  )

  private fun largeScreen(marker: String) =
    marker + "x".repeat(STALE_SCREEN_OUTPUT_THRESHOLD_CHARS)

  private fun Prompt.toolResults(): List<MessagePart.Tool.Result> =
    messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()

  @Test
  fun `the latest screen state is kept verbatim, however large`() {
    val latest = largeScreen("LATEST")
    val pruned = pruneScreenStateHistory(
      promptOfTurns(screenResult("1", largeScreen("OLDER")), screenResult("2", latest)),
    )

    // The whole point of the rewrite is that the model still sees the CURRENT screen in full —
    // pruning that would break the agent rather than just make it expensive.
    assertThat(pruned.toolResults().last().output).isEqualTo(latest)
  }

  @Test
  fun `an older large screen state is replaced with the placeholder`() {
    val pruned = pruneScreenStateHistory(
      promptOfTurns(screenResult("1", largeScreen("OLDER")), screenResult("2", largeScreen("LATEST"))),
    )

    assertThat(pruned.toolResults().first().output).isEqualTo(STALE_SCREEN_PLACEHOLDER)
  }

  @Test
  fun `an older small result is kept, preserving the action trail`() {
    val tapConfirmation = "tapped OK"
    val pruned = pruneScreenStateHistory(
      promptOfTurns(screenResult("1", tapConfirmation), screenResult("2", largeScreen("LATEST"))),
    )

    // Only the big stale screen payloads go. Stripping the tap/swipe confirmations too would erase
    // the record of what the agent already tried, which is what it reasons over to pick a next move.
    assertThat(pruned.toolResults().first().output).isEqualTo(tapConfirmation)
  }

  @Test
  fun `stripping an older result drops its non-text parts, not just its text`() {
    val stale = resultOf("1", MessagePart.Text(largeScreen("OLDER")), imageAttachment())

    val pruned = pruneScreenStateHistory(
      promptOfTurns(stale, screenResult("2", largeScreen("LATEST"))),
    )

    // A tool result can carry images alongside its text. Replacing only the text would leave a
    // stale screenshot in the prompt — the most expensive thing in it — while the log claims the
    // payload was stripped.
    assertThat(pruned.toolResults().first().parts)
      .isEqualTo(listOf(MessagePart.Text(STALE_SCREEN_PLACEHOLDER)))
  }

  @Test
  fun `an older result whose bulk is an attachment is stripped even though its text is short`() {
    val pruned = pruneScreenStateHistory(
      promptOfTurns(
        resultOf("1", MessagePart.Text("screenshot attached"), imageAttachment()),
        screenResult("2", largeScreen("LATEST")),
      ),
    )

    // `output` cannot see the image, so this result measures as ~19 characters. Gating on that
    // alone kept the screenshot — the single most expensive thing in the prompt — for the rest of
    // the run, because the cheapest measurement of it said it was small.
    assertThat(pruned.toolResults().first().parts)
      .isEqualTo(listOf(MessagePart.Text(STALE_SCREEN_PLACEHOLDER)))
  }

  @Test
  fun `an older attachment-only result is stripped`() {
    val pruned = pruneScreenStateHistory(
      promptOfTurns(resultOf("1", imageAttachment()), screenResult("2", largeScreen("LATEST"))),
    )

    // Zero text, so every text-length gate reads it as empty and leaves it alone.
    assertThat(pruned.toolResults().first().parts)
      .isEqualTo(listOf(MessagePart.Text(STALE_SCREEN_PLACEHOLDER)))
  }

  @Test
  fun `the latest result keeps its attachment`() {
    val latest = resultOf("2", MessagePart.Text("current screen"), imageAttachment())
    val pruned = pruneScreenStateHistory(
      promptOfTurns(screenResult("1", largeScreen("OLDER")), latest),
    )

    // Stripping attachments from older results must not turn into stripping the current
    // screenshot, which is the one the model needs to act on.
    assertThat(pruned.toolResults().last().parts).isEqualTo(latest.parts)
  }

  @Test
  fun `every result in the latest message survives, not just the last one`() {
    val siblingA = resultOf("a", MessagePart.Text(largeScreen("SIBLING-A")))
    val siblingB = resultOf("b", MessagePart.Text(largeScreen("SIBLING-B")), imageAttachment())

    val pruned = pruneScreenStateHistory(
      promptOfMessages(
        userMessage(screenResult("old", largeScreen("OLDER"))),
        userMessage(siblingA, siblingB),
      ),
    )

    // A turn can call several tools at once, and all of their results are current perception.
    // Keying "latest" to the last result *part* stripped the siblings that arrived beside it —
    // which, under the attachment rule, discards a screenshot on the very pass that receives it.
    val latest = pruned.messages.last().parts.filterIsInstance<MessagePart.Tool.Result>()
    assertThat(latest).isEqualTo(listOf(siblingA, siblingB))
  }

  @Test
  fun `older results are stripped across earlier messages, not only the immediately preceding one`() {
    val pruned = pruneScreenStateHistory(
      promptOfMessages(
        userMessage(screenResult("1", largeScreen("OLDEST"))),
        userMessage(screenResult("2", largeScreen("MIDDLE"))),
        userMessage(screenResult("3", largeScreen("LATEST"))),
      ),
    )

    val stripped = pruned.messages
      .dropLast(1)
      .flatMap { it.parts }
      .filterIsInstance<MessagePart.Tool.Result>()
      .map { it.output }

    assertThat(stripped).isEqualTo(listOf(STALE_SCREEN_PLACEHOLDER, STALE_SCREEN_PLACEHOLDER))
  }

  @Test
  fun `a result exactly at the threshold is kept, one character over is stripped`() {
    val atThreshold = "x".repeat(STALE_SCREEN_OUTPUT_THRESHOLD_CHARS)
    val overThreshold = "x".repeat(STALE_SCREEN_OUTPUT_THRESHOLD_CHARS + 1)

    val pruned = pruneScreenStateHistory(
      promptOfMessages(
        userMessage(screenResult("1", atThreshold), screenResult("2", overThreshold)),
        userMessage(screenResult("3", largeScreen("LATEST"))),
      ),
    )

    // The kdoc says "exceeds", so the boundary itself is kept. Pinned because flipping `>` to `>=`
    // is otherwise invisible: every other case sits hundreds of characters clear of the line.
    val older = pruned.messages.first().parts.filterIsInstance<MessagePart.Tool.Result>()
    assertThat(older.map { it.output })
      .isEqualTo(listOf(atThreshold, STALE_SCREEN_PLACEHOLDER))
  }

  @Test
  fun `a stripped result keeps the identity Koog pairs it to its tool call by`() {
    val pruned = pruneScreenStateHistory(
      promptOfMessages(
        userMessage(
          MessagePart.Tool.Result(
            id = "call-42",
            tool = "snapshot",
            parts = listOf(MessagePart.Text(largeScreen("OLDER"))),
            isError = true,
          ),
        ),
        userMessage(screenResult("2", largeScreen("LATEST"))),
      ),
    )

    // Only the content is meant to change. Losing `id` would break the tool-call/result pairing
    // Koog's bookkeeping depends on, which fails as a malformed conversation rather than as a
    // bigger prompt.
    val stripped = pruned.toolResults().first()
    assertThat(stripped.id).isEqualTo("call-42")
    assertThat(stripped.tool).isEqualTo("snapshot")
    assertThat(stripped.isError).isEqualTo(true)
  }

  @Test
  fun `a prompt with no tool results is returned unchanged`() {
    val input = promptOf(MessagePart.Text("do the thing"))

    assertThat(pruneScreenStateHistory(input)).isSameInstanceAs(input)
  }
}
