package xyz.block.trailblaze.quickjs.tools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation

/**
 * Unit coverage for [QuickJsToolRegistration] — the on-device QuickJS-bundle registration.
 *
 * The load-bearing property here is that threading `isRecordable` onto the decoded
 * [QuickJsTrailblazeTool] does NOT change its runtime type. `SessionScopedHostBinding`'s same-host
 * re-entry guard keys off `resolved is QuickJsTrailblazeTool`; an earlier wrapper-based approach
 * broke that (a non-recordable same-bundle compose would bypass the guard and deadlock the host's
 * non-reentrant `evalMutex`). These tests pin the type-preserving contract alongside the
 * `surfaceToLlm` / `isRecordable` flag plumbing.
 */
class QuickJsToolRegistrationTest {

  private val hosts = mutableListOf<QuickJsToolHost>()

  @AfterTest
  fun teardown() = runBlocking {
    hosts.forEach { runCatching { it.shutdown() } }
    hosts.clear()
  }

  private suspend fun registrationFor(
    isRecordable: Boolean,
    surfaceToLlm: Boolean = true,
  ): QuickJsToolRegistration {
    val host = QuickJsToolHost.connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["greet"] = {
        name: "greet",
        spec: { description: "hi" },
        handler: async () => ({ content: [{ type: "text", text: "hi" }] }),
      };
      """.trimIndent(),
      bundleFilename = "greet.bundle.js",
    )
    hosts += host
    val spec = host.listTools().single()
    return QuickJsToolRegistration(
      host = host,
      spec = spec,
      surfaceToLlm = surfaceToLlm,
      isRecordable = isRecordable,
    )
  }

  @Test
  fun `decodeToolCall preserves QuickJsTrailblazeTool type so the same-host re-entry guard still fires`() = runBlocking {
    // Regression for the deadlock a wrapper-based override would introduce: the decoded instance
    // MUST stay a QuickJsTrailblazeTool, since SessionScopedHostBinding's same-host re-entry guard
    // only refuses a same-bundle compose when `resolved is QuickJsTrailblazeTool`.
    val decoded = registrationFor(isRecordable = false).decodeToolCall("{}")
    assertIs<QuickJsTrailblazeTool>(
      decoded,
      "a non-recordable on-device tool must remain a QuickJsTrailblazeTool, not a wrapper",
    )
    assertFalse(
      decoded.getIsRecordableFromAnnotation(),
      "isRecordable=false must thread onto the decoded tool's recordable bit",
    )
  }

  @Test
  fun `decodeToolCall keeps a recordable tool recordable`() = runBlocking {
    val decoded = registrationFor(isRecordable = true).decodeToolCall("{}")
    assertIs<QuickJsTrailblazeTool>(decoded)
    assertTrue(
      decoded.getIsRecordableFromAnnotation(),
      "the default (recordable) tool must report recordable",
    )
  }

  @Test
  fun `surfaceToLlm reflects the registration flag`() = runBlocking {
    assertFalse(registrationFor(isRecordable = true, surfaceToLlm = false).surfaceToLlm)
    assertTrue(registrationFor(isRecordable = true, surfaceToLlm = true).surfaceToLlm)
  }
}
