package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Coverage for [AndroidGrantPermissionsTrailblazeTool] (`android_grantPermissions`, `pm grant`) and
 * [AndroidGrantAppOpsPermissionTrailblazeTool] (`android_grantAppOpsPermission`,
 * `appops set <op> allow`). Both share the same shape — non-Android platform check,
 * executor-missing diagnostic, dual-mode annotation contract — so the tests cover them together to
 * keep the symmetry visible. The runtime-perm tool additionally pins its fan-out contract (one
 * grant per permission, in list order; a genuine failure stops the batch) on its extracted pure
 * helper.
 *
 * Happy-path execution against a live `AndroidDeviceCommandExecutor` is integration-tested (the
 * Square launch-app flow composes both tools end-to-end on a real device). Unit coverage here
 * mirrors `AdbShellTrailblazeToolTest`'s scope: every branch of `execute()` that doesn't require a
 * real executor, plus the static annotation contract.
 */
class AndroidGrantPermissionToolsTest {

  // -------------------------------------------------------------------------------------------
  // android_grantPermissions (batch runtime perms via `pm grant`, one dispatch)
  // -------------------------------------------------------------------------------------------

  @Test fun `android_grantPermissions errors on iOS platform`() = runBlocking {
    val tool = AndroidGrantPermissionsTrailblazeTool(
      appId = "com.example.app",
      permissions = listOf("android.permission.CAMERA"),
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
    assertThat(result.errorMessage).contains("IOS")
  }

  @Test fun `android_grantPermissions errors when executor is missing`() = runBlocking {
    val tool = AndroidGrantPermissionsTrailblazeTool(
      appId = "com.example.app",
      permissions = listOf("android.permission.CAMERA"),
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  @Test fun `android_grantPermissions annotation contract`() {
    // Same dual-mode + naming contract as the singular sibling. The annotation `name` MUST match
    // the `tools/android_grantPermissions.tool.yaml` descriptor (registration is a name-keyed
    // lookup) and it must NOT carry the host-only marker (the underlying executor grants on-device
    // too — that's the whole point of collapsing the pre-launch grant loop into one on-device call).
    val tool = AndroidGrantPermissionsTrailblazeTool(
      appId = "com.example.app",
      permissions = listOf("android.permission.CAMERA"),
    )
    assertThat(tool).isInstanceOf(ExecutableTrailblazeTool::class)
    assertThat(tool).isNotInstanceOf(HostLocalExecutableTrailblazeTool::class)

    val annotation = AndroidGrantPermissionsTrailblazeTool::class.java
      .getAnnotation(TrailblazeToolClass::class.java)
      ?: fail("@TrailblazeToolClass annotation missing from AndroidGrantPermissionsTrailblazeTool")
    assertThat(annotation.name).isEqualTo("android_grantPermissions")
    assertThat(annotation.surfaceToLlm).isEqualTo(false)
    assertThat(annotation.requiresHost).isEqualTo(false)
  }

  @Test fun `android_grantPermissions grants every permission in list order`() {
    // The observable contract of the batch tool: it fans out to one grant per permission, in the
    // order given (no dedup, no reorder, no drop). Tested on the pure `grantEach` helper with the
    // grant side effect injected, so no device/executor is needed — mirrors the CLAUDE.md guidance
    // to unit-test the extracted pure logic rather than reaching inside `execute()`.
    val granted = mutableListOf<String>()
    val permissions = listOf(
      "android.permission.CAMERA",
      "android.permission.RECORD_AUDIO",
      "android.permission.CAMERA", // duplicate is preserved, not collapsed
    )

    AndroidGrantPermissionsTrailblazeTool.grantEach(permissions) { granted += it }

    assertThat(granted).isEqualTo(permissions)
  }

  @Test fun `android_grantPermissions with an empty list grants nothing`() {
    // Empty list is a no-op: no `pm grant` is attempted. (Its Success message is also special-cased
    // in execute() to avoid a dangling "…: " — but that's wording, not asserted here.)
    val granted = mutableListOf<String>()

    AndroidGrantPermissionsTrailblazeTool.grantEach(emptyList()) { granted += it }

    assertThat(granted).isEmpty()
  }

  @Test fun `android_grantPermissions stops the batch when a grant throws`() {
    // Genuine executor-level exceptions abort the batch (matching the per-call loop this replaces:
    // the JS `await` would throw and skip the rest). The undeclared-permission case never reaches
    // here — the executor swallows-and-logs it — so only a real failure propagates.
    val granted = mutableListOf<String>()
    val boom = RuntimeException("device I/O failed")

    val thrown = assertFailsWith<RuntimeException> {
      AndroidGrantPermissionsTrailblazeTool.grantEach(
        listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.BLUETOOTH_CONNECT"),
      ) { permission ->
        granted += permission
        if (permission == "android.permission.RECORD_AUDIO") throw boom
      }
    }

    assertThat(thrown).isEqualTo(boom)
    // Stopped at the throwing entry — the third permission was never attempted.
    assertThat(granted).isEqualTo(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"))
  }

  // -------------------------------------------------------------------------------------------
  // android_grantAppOpsPermission (AppOps via `appops set <op> allow`)
  // -------------------------------------------------------------------------------------------

  @Test fun `android_grantAppOpsPermission errors on iOS platform`() = runBlocking {
    val tool = AndroidGrantAppOpsPermissionTrailblazeTool(
      appId = "com.example.app",
      permission = "MANAGE_EXTERNAL_STORAGE",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
    assertThat(result.errorMessage).contains("IOS")
  }

  @Test fun `android_grantAppOpsPermission errors when executor is missing`() = runBlocking {
    val tool = AndroidGrantAppOpsPermissionTrailblazeTool(
      appId = "com.example.app",
      permission = "MANAGE_EXTERNAL_STORAGE",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  @Test fun `android_grantAppOpsPermission annotation contract`() {
    // Same dual-mode + naming contract as the runtime-perm sibling. The annotation `name`
    // string MUST match the entry in `tools/android_grantAppOpsPermission.tool.yaml` —
    // the registration is a name-keyed lookup, so a typo here is a silent miss at
    // runtime registry-build time.
    val tool = AndroidGrantAppOpsPermissionTrailblazeTool(
      appId = "com.example.app",
      permission = "MANAGE_EXTERNAL_STORAGE",
    )
    assertThat(tool).isInstanceOf(ExecutableTrailblazeTool::class)
    assertThat(tool).isNotInstanceOf(HostLocalExecutableTrailblazeTool::class)

    val annotation = AndroidGrantAppOpsPermissionTrailblazeTool::class.java
      .getAnnotation(TrailblazeToolClass::class.java)
      ?: fail("@TrailblazeToolClass annotation missing from AndroidGrantAppOpsPermissionTrailblazeTool")
    assertThat(annotation.name).isEqualTo("android_grantAppOpsPermission")
    assertThat(annotation.surfaceToLlm).isEqualTo(false)
    assertThat(annotation.requiresHost).isEqualTo(false)
  }

  // -------------------------------------------------------------------------------------------
  // Shared fixtures
  // -------------------------------------------------------------------------------------------

  private fun createContext(platform: TrailblazeDevicePlatform): TrailblazeToolExecutionContext {
    val driverType = when (platform) {
      TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
      TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
      TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.PLAYWRIGHT_NATIVE
      TrailblazeDevicePlatform.DESKTOP -> TrailblazeDriverType.COMPOSE
    }
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-device",
          trailblazeDevicePlatform = platform,
        ),
        trailblazeDriverType = driverType,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }
}
