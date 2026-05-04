package xyz.block.trailblaze.device

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Validates the input contract for `AndroidDeviceCommandExecutor.executeShellCommandAs`.
 *
 * The validator lives in commonMain so both `actual` impls enforce identical rules; this
 * test suite covers the validator directly without spinning up either platform's transport.
 * The validator name still references `RunAs` because the underlying shell command being
 * invoked is `run-as` — the public method name was changed for clarity but the validator
 * name reflects the actual platform mechanism it's protecting.
 *
 * Rules under test:
 * - `appId` must be non-blank and match the Android package-name grammar.
 * - `command` must be non-blank (otherwise `run-as <pkg>` drops into an interactive shell
 *   and hangs the non-interactive transport).
 */
class AndroidDeviceCommandExecutorRunAsValidationTest {

  @Test
  fun `valid package name and command pass`() {
    // Should not throw.
    validateRunAsArgs("com.example.app.beta.debug", "ls /data/data/com.example.app.beta.debug")
  }

  @Test
  fun `single-segment package names are rejected`() {
    // Android package names require at least two segments ("com.foo"), not bare "foo".
    val ex = assertFailsWith<IllegalArgumentException> {
      validateRunAsArgs("foo", "ls")
    }
    assertEquals(true, ex.message?.contains("syntactically valid Android package name"))
  }

  @Test
  fun `package names starting with a digit are rejected`() {
    assertFailsWith<IllegalArgumentException> {
      validateRunAsArgs("9foo.bar", "ls")
    }
  }

  @Test
  fun `package names with shell metacharacters are rejected`() {
    // The whole point of the validator: prevent shell injection through appId.
    val attempts = listOf(
      "com.foo; rm -rf /",
      "com.foo`whoami`",
      "com.foo|cat /etc/passwd",
      "com.foo bar.baz",
      "com.foo&touch /tmp/pwned",
      "\$(whoami)",
      "com.foo'bar",
      "com.foo\"bar",
    )
    for (bad in attempts) {
      assertFailsWith<IllegalArgumentException>("Should reject '$bad'") {
        validateRunAsArgs(bad, "ls")
      }
    }
  }

  @Test
  fun `blank appId is rejected`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      validateRunAsArgs("", "ls")
    }
    assertEquals("appId must not be blank", ex.message)
  }

  @Test
  fun `whitespace-only appId is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      validateRunAsArgs("   ", "ls")
    }
  }

  @Test
  fun `blank command is rejected`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      validateRunAsArgs("com.example.app", "")
    }
    assertEquals(true, ex.message?.contains("command must not be blank"))
    assertEquals(true, ex.message?.contains("interactive shell"))
  }

  @Test
  fun `whitespace-only command is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      validateRunAsArgs("com.example.app", "   ")
    }
  }

  @Test
  fun `package names with underscores in segments are accepted`() {
    // Real-world case: some packages use underscores, e.g. com.foo.my_app.
    validateRunAsArgs("com.foo.my_app", "ls")
  }

  @Test
  fun `deeply nested package names are accepted`() {
    validateRunAsArgs("com.example.app.beta.debug.flavor.thing", "ls")
  }
}
