package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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
 * Coverage for both [AndroidGrantPermissionTrailblazeTool] (`android_grantPermission`,
 * `pm grant`) and [AndroidGrantAppOpsPermissionTrailblazeTool]
 * (`android_grantAppOpsPermission`, `appops set <op> allow`). The two tools share the
 * same shape — non-Android platform check, executor-missing diagnostic, dual-mode
 * annotation contract — so the tests cover both together to keep the symmetry visible.
 *
 * Happy-path execution against a live `AndroidDeviceCommandExecutor` is integration-
 * tested (the Square launch-app flow composes both tools end-to-end on a real device).
 * Unit coverage here mirrors `AdbShellTrailblazeToolTest`'s scope: every branch of
 * `execute()` that doesn't require a real executor, plus the static annotation contract.
 */
class AndroidGrantPermissionToolsTest {

  // -------------------------------------------------------------------------------------------
  // android_grantPermission (runtime perms via `pm grant`)
  // -------------------------------------------------------------------------------------------

  @Test fun `android_grantPermission errors on iOS platform`() = runBlocking {
    val tool = AndroidGrantPermissionTrailblazeTool(
      appId = "com.example.app",
      permission = "android.permission.CAMERA",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
    assertThat(result.errorMessage).contains("IOS")
  }

  @Test fun `android_grantPermission errors when executor is missing`() = runBlocking {
    // Android platform but no `AndroidDeviceCommandExecutor` wired in — the diagnostic
    // names the executor field so a misconfigured host runner gets a routable hint, not
    // a `NullPointerException` deep in `pm grant`.
    val tool = AndroidGrantPermissionTrailblazeTool(
      appId = "com.example.app",
      permission = "android.permission.CAMERA",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  @Test fun `android_grantPermission annotation contract`() {
    // The class-level annotation pins:
    //  - Name matches the `*.tool.yaml` descriptor (registration depends on agreement).
    //  - `surfaceToLlm = false` (this is a composition primitive, not LLM-callable).
    //  - `requiresHost` defaults to `false` so on-device dispatch can serve it too —
    //    the underlying executor has both host and on-device actuals.
    // The dual-mode-marker inverse check (NOT `HostLocalExecutableTrailblazeTool`) catches
    // a future refactor that accidentally adds the host-only marker, which would silently
    // gate the tool off the on-device runner's registration even though `pm grant` works
    // on both sides.
    val tool = AndroidGrantPermissionTrailblazeTool(appId = "com.example.app", permission = "android.permission.CAMERA")
    assertThat(tool).isInstanceOf(ExecutableTrailblazeTool::class)
    assertk.assertThat(tool !is HostLocalExecutableTrailblazeTool).isEqualTo(true)

    val annotation = AndroidGrantPermissionTrailblazeTool::class.java
      .getAnnotation(TrailblazeToolClass::class.java)
      ?: fail("@TrailblazeToolClass annotation missing from AndroidGrantPermissionTrailblazeTool")
    assertThat(annotation.name).isEqualTo("android_grantPermission")
    assertThat(annotation.surfaceToLlm).isEqualTo(false)
    assertThat(annotation.requiresHost).isEqualTo(false)
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
    assertk.assertThat(tool !is HostLocalExecutableTrailblazeTool).isEqualTo(true)

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
