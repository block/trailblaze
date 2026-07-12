package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import xyz.block.trailblaze.AgentMemory

/**
 * Covers [interpolateBroadcastArgs] — the pure interpolation step `android_sendBroadcast` runs
 * before dispatch. The executor path itself ([AndroidDeviceCommandExecutor] is an `expect class`)
 * is integration-tested on a real device via the Square launch flow; here we pin the token
 * resolution contract that makes credential pass-through from a TypeScript caller work.
 */
class AndroidSendBroadcastInterpolationTest {

  @Test fun `interpolates tokens in action and extra values, leaves keys and literals verbatim`() {
    val memory = AgentMemory().apply {
      remember("account_email", "owner@example.com")
      remember("login_action", "com.example.LOGIN")
    }

    val (action, extras) = interpolateBroadcastArgs(
      action = "{{login_action}}",
      extras = listOf(
        BroadcastExtra(key = "email", value = "{{account_email}}"),
        BroadcastExtra(key = "type", value = "emailLogin"),
      ),
      memory = memory,
    )

    assertThat(action).isEqualTo("com.example.LOGIN")
    assertThat(extras[0].key).isEqualTo("email") // key is a structural identifier, left verbatim
    assertThat(extras[0].value).isEqualTo("owner@example.com") // value token resolved
    assertThat(extras[1].value).isEqualTo("emailLogin") // literal value untouched
  }

  @Test fun `a sensitive token still resolves in the trusted Kotlin layer`() {
    // The whole point of routing credentials through this non-recordable tool: a value seeded via
    // rememberSensitive (and therefore filtered OUT of the ctx.memory snapshot the scripting
    // envelope ships) still interpolates here. That lets a fully-TypeScript caller forward the
    // opaque `{{token}}` without ever holding the plaintext — the secret materializes only here.
    val memory = AgentMemory().apply {
      rememberSensitive("account_password", "s3cret-pw")
    }

    val (_, extras) = interpolateBroadcastArgs(
      action = "com.example.LOGIN",
      extras = listOf(BroadcastExtra(key = "password", value = "{{account_password}}")),
      memory = memory,
    )

    assertThat(extras.single().value).isEqualTo("s3cret-pw")
  }

  @Test fun `unknown token is left in place as a literal`() {
    // A typo'd credential key must surface as the visible literal (plus a diagnostic) rather
    // than silently broadcasting an empty extra.
    val (_, extras) = interpolateBroadcastArgs(
      action = "com.example.LOGIN",
      extras = listOf(BroadcastExtra(key = "password", value = "{{missing}}")),
      memory = AgentMemory(),
    )

    assertThat(extras.single().value).isEqualTo("{{missing}}")
  }
}
