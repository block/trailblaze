package xyz.block.trailblaze.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.toSessionToolRepo
import xyz.block.trailblaze.scripting.RuntimeToolSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import java.io.File
import kotlin.reflect.KClass

/**
 * Proves the runtime tool source **on a device**: a scripted-tool bundle pushed to
 * [RuntimeToolSource.DEVICE_DIRECTORY] runs inside an instrumented app process when the signed
 * target config opted in, and is invisible when it did not.
 *
 * Three claims only a device can settle:
 *
 *  1. **The path is reachable.** `/data/local/tmp` is `drwxrwx--x shell shell`, so an app process
 *     has traverse-only access to it and SELinux labels what lands there `shell_data_file`. Whether
 *     an app may then read a `0644` file inside is a policy question, not a permissions-bit one, and
 *     the whole design rests on the answer. Confirmed here on whatever image CI runs, rather than
 *     assumed from the customary claim.
 *  2. **The opt-in is the gate.** The same pushed bundle, the same instrumented process, the same
 *     tool name — registered with `allow_runtime_tool_source: true` on the target, absent without
 *     it. That is the guarantee that lets a team sign one of these APKs at a key ceremony: what
 *     they signed is what runs, and handing the artifact back is not handing back a way to execute
 *     unsigned code inside their app from any host that later holds the device.
 *  3. **Only a host can plant one.** The app process cannot create the file it is allowed to read,
 *     so the runtime source is reachable by adb and by nothing running on the device.
 *
 * The bundle is planted the way a dispatching host plants one — a shell-side `cp` into the runtime
 * directory, run through `UiAutomation` (UID 2000, `shell`), which is the same write `adb push`
 * performs. The test writes the file into its own external files directory first because that is a
 * path both this process and the shell can see; `UiAutomationConnection.executeShellCommand` goes
 * through [Runtime.exec], so there is no shell to redirect with and every argument below is a
 * single whitespace-free token.
 *
 * The JVM-side `RuntimeToolSourceTest` pins the same gates as pure policy, including the
 * not-under-instrumentation case this class cannot reach — an instrumentation test is, definitionally,
 * running under instrumentation.
 */
class RuntimeToolSourceOnDeviceTest {

  private val stagingFile: File
    get() = File(
      InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null),
      "runtime-tool-source-probe.bundle.js",
    )

  @After
  fun cleanUp() {
    // Only the probe's own trailmap directory: the runtime root is shared device state, and a
    // host may have real pushed bundles sitting next to the probe's.
    AdbCommandUtil.execShellCommand("rm -rf $PROBE_TRAILMAP_DIR")
    stagingFile.delete()
  }

  @Test
  fun aPushedBundleRunsWhenTheSignedTargetConfigOptedIn() = runBlocking<Unit> {
    pushProbeBundle()

    val toolRepo = sessionRepo(optedIn = true)
    assertFalse(
      "nothing may have registered $PROBE_TOOL before the launcher runs, or the assertion below " +
        "would pass without the pushed bundle ever being read",
      toolRepo.getRegisteredDynamicTools().containsKey(PROBE_TOOL),
    )

    val runtime = OnDeviceScriptedToolBundleLauncher.launchAll(
      toolRepo = toolRepo,
      target = probeTarget(optedIn = true),
      sessionId = SessionId("runtime-tool-source-opted-in"),
      deviceInfo = DEVICE_INFO,
      engineExtension = null,
    )

    assertTrue(
      "$PROBE_TOOL must be dispatchable from the bundle at ${runtimeBundlePath()}. This APK " +
        "packages no such asset, so a failure here means the instrumented app process could not " +
        "read the pushed file — the premise the runtime tool source is built on. Registered: " +
        "${toolRepo.getRegisteredDynamicTools().keys}",
      toolRepo.getRegisteredDynamicTools().containsKey(PROBE_TOOL),
    )
    runtime?.shutdownAll()
  }

  @Test
  fun theSamePushedBundleIsIgnoredWhenTheSignedTargetConfigDidNotOptIn() = runBlocking<Unit> {
    pushProbeBundle()

    val toolRepo = sessionRepo(optedIn = false)
    val runtime = OnDeviceScriptedToolBundleLauncher.launchAll(
      toolRepo = toolRepo,
      target = probeTarget(optedIn = false),
      sessionId = SessionId("runtime-tool-source-opted-out"),
      deviceInfo = DEVICE_INFO,
      engineExtension = null,
    )

    assertFalse(
      "$PROBE_TOOL must NOT be dispatchable. Its only backing bundle is the file at " +
        "${runtimeBundlePath()}, which this APK packages nowhere in its assets. It is registered " +
        "here, so an APK signed at someone else's key ceremony would run tool code pushed by " +
        "whoever holds the device — the exact capability the baked opt-out exists to withhold. " +
        "Registered: ${toolRepo.getRegisteredDynamicTools().keys}",
      toolRepo.getRegisteredDynamicTools().containsKey(PROBE_TOOL),
    )
    runtime?.shutdownAll()
  }

  @Test
  fun theAppProcessCanReadTheRuntimeDirectoryButCannotWriteIt() {
    pushProbeBundle()

    assertEquals(
      "the pushed bundle must read back byte-for-byte from the app process",
      PROBE_BUNDLE_JS,
      File(runtimeBundlePath()).readText(),
    )
    // Not a nicety: if an app could write here, any app on the device could plant tool code for the
    // next instrumented run, and the adb-only property the design leans on would be false.
    val plantedByApp = File(RuntimeToolSource.DEVICE_DIRECTORY, "planted-by-app.bundle.js")
    val wrote: Result<Unit> = runCatching { plantedByApp.writeText("x") }
    assertTrue(
      "an app process must not be able to create files under ${RuntimeToolSource.DEVICE_DIRECTORY}",
      wrote.isFailure,
    )
  }

  /** Writes the probe bundle where only a shell-privileged writer can put it. */
  private fun pushProbeBundle() {
    stagingFile.parentFile?.mkdirs()
    stagingFile.writeText(PROBE_BUNDLE_JS)
    val runtimeToolsDir = File(runtimeBundlePath()).parent
    AdbCommandUtil.execShellCommand("mkdir -p $runtimeToolsDir")
    AdbCommandUtil.execShellCommand("cp ${stagingFile.absolutePath} ${runtimeBundlePath()}")
    // World-readable file, traversable directories — the contract a host pushing bundles has to
    // meet, since the app process shares neither the uid nor the group of whatever wrote them.
    // Recursive only inside the probe's own trailmap; the shared ancestors get a non-recursive
    // a+rX so a neighbor trailmap's modes are left exactly as its own host set them.
    AdbCommandUtil.execShellCommand(
      "chmod a+rX $RUNTIME_ROOT $RUNTIME_ROOT/trails $RUNTIME_ROOT/trails/config $RUNTIME_ROOT/trails/config/trailmaps",
    )
    AdbCommandUtil.execShellCommand("chmod -R a+rX $PROBE_TRAILMAP_DIR")
    check(File(runtimeBundlePath()).isFile) {
      "the probe bundle was not planted at ${runtimeBundlePath()}"
    }
  }

  private fun runtimeBundlePath(): String =
    "${RuntimeToolSource.DEVICE_DIRECTORY}/$PROBE_BUNDLE_ASSET_PATH"

  private fun sessionRepo(optedIn: Boolean): TrailblazeToolRepo =
    probeTarget(optedIn).toSessionToolRepo(driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY)

  /**
   * A target whose single tool is backed by a bundle that exists ONLY at the runtime path. Nothing
   * about the two variants differs except the opt-in, so a difference in outcome can only come from
   * the gate.
   */
  private fun probeTarget(optedIn: Boolean): TrailblazeHostAppTarget =
    object : TrailblazeHostAppTarget(id = "runtimetoolsourceprobe", displayName = "Runtime Tool Source Probe") {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = emptyList()

      override fun internalGetCustomToolsForDriver(
        driverType: TrailblazeDriverType,
      ): Set<KClass<out TrailblazeTool>> = emptySet()

      override fun getInlineScriptTools(): List<InlineScriptToolConfig> = listOf(
        InlineScriptToolConfig(script = PROBE_SCRIPT_PATH, name = PROBE_TOOL.toolName),
      )

      override val allowsRuntimeToolSource: Boolean = optedIn
    }

  companion object {
    private val PROBE_TOOL = ToolName("runtimeToolSourceProbe")

    private const val PROBE_SCRIPT_PATH =
      "trails/config/trailmaps/runtimetoolsourceprobe/tools/runtimeToolSourceProbe.ts"

    /** What `ScriptedToolNameDiscoverer.bundleResourcePathForScript` maps [PROBE_SCRIPT_PATH] to. */
    private const val PROBE_BUNDLE_ASSET_PATH =
      "trails/config/trailmaps/runtimetoolsourceprobe/tools/runtimeToolSourceProbe.bundle.js"

    private val RUNTIME_ROOT: String = RuntimeToolSource.DEVICE_DIRECTORY

    /** Everything this test creates or deletes on the device lives under here. */
    private val PROBE_TRAILMAP_DIR: String =
      "$RUNTIME_ROOT/trails/config/trailmaps/runtimetoolsourceprobe"

    private val PROBE_BUNDLE_JS =
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["runtimeToolSourceProbe"] = {
        name: "runtimeToolSourceProbe",
        spec: {},
        handler: async () => ({ content: [{ type: "text", text: "pushed" }] }),
      };
      """.trimIndent()

    private val DEVICE_INFO =
      TrailblazeDeviceInfo(
        trailblazeDeviceId =
          TrailblazeDeviceId(
            instanceId = "on-device",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 1920,
      )
  }
}
