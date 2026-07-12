package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool

/**
 * Pins the credential pass-through contract for `android_sendBroadcast`: `{{token}}` references
 * in the broadcast args resolve at the dispatch boundary ([interpolateMemoryInTool]) — INCLUDING
 * values seeded via `rememberSensitive`, which are filtered out of the ctx.memory snapshot the
 * scripting envelope ships. That is what lets a fully-TypeScript caller forward an opaque token
 * without the plaintext ever entering the JS heap: the secret materializes only in the trusted
 * Kotlin layer, and only on the dispatched instance (the authored instance keeps the token for
 * logs and failure metadata). The executor path itself ([AndroidDeviceCommandExecutor] is an
 * `expect class`) is integration-tested on a real device via the Square launch flow.
 */
class AndroidSendBroadcastInterpolationTest {

  private fun tool(extras: List<BroadcastExtra>, action: String = "com.example.LOGIN") =
    AndroidSendBroadcastTrailblazeTool(
      action = action,
      componentPackage = "com.example",
      componentClass = "com.example.Receiver",
      extras = extras,
    )

  @Test fun `tokens in action and extra values resolve at the dispatch boundary`() {
    val memory = AgentMemory().apply {
      remember("account_email", "owner@example.com")
      remember("login_action", "com.example.LOGIN")
    }
    val authored = tool(
      action = "{{login_action}}",
      extras = listOf(
        BroadcastExtra(key = "email", value = "{{account_email}}"),
        BroadcastExtra(key = "type", value = "emailLogin"),
      ),
    )

    val dispatched = interpolateMemoryInTool(authored, memory)

    assertThat(dispatched.action).isEqualTo("com.example.LOGIN")
    assertThat(dispatched.extras[0].value).isEqualTo("owner@example.com")
    assertThat(dispatched.extras[1].value).isEqualTo("emailLogin") // literal value untouched
    // The authored instance is untouched — it is what logs and failure metadata carry.
    assertThat(authored.action).isEqualTo("{{login_action}}")
    assertThat(authored.extras[0].value).isEqualTo("{{account_email}}")
  }

  @Test fun `a sensitive token still resolves in the trusted Kotlin layer`() {
    val memory = AgentMemory().apply {
      rememberSensitive("account_password", "s3cret-pw")
    }

    val dispatched = interpolateMemoryInTool(
      tool(extras = listOf(BroadcastExtra(key = "password", value = "{{account_password}}"))),
      memory,
    )

    assertThat(dispatched.extras.single().value).isEqualTo("s3cret-pw")
  }

  @Test fun `unknown token is left in place as a literal`() {
    // Memory is non-empty so the boundary's same-instance short-circuit doesn't apply — this
    // proves the RESOLVER leaves the unknown token, not the empty-memory fast path. A typo'd
    // credential key must surface as the visible literal (plus a diagnostic) rather than
    // silently broadcasting an empty extra.
    val memory = AgentMemory().apply { remember("unrelated", "x") }

    val dispatched = interpolateMemoryInTool(
      tool(extras = listOf(BroadcastExtra(key = "password", value = "{{missing}}"))),
      memory,
    )

    assertThat(dispatched.extras.single().value).isEqualTo("{{missing}}")
  }
}
