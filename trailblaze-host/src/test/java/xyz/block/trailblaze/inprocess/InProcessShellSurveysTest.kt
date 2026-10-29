package xyz.block.trailblaze.inprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.HostBinding
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration
import xyz.block.trailblaze.surveys.PackagedSurveyBundleSource
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.surveys.SurveyHostFunctions
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * `ctx.surveys` on the device: `make-test-apk` packages a trailmap's surveys, and a tool read out
 * of that APK runs one through the source a device installs over the APK's assets.
 */
class InProcessShellSurveysTest {

  @get:Rule val temp = TemporaryFolder()

  private lateinit var appKeystore: File
  private lateinit var shell: File
  private lateinit var appApk: File

  @Before
  fun setUp() {
    appKeystore = InProcessTestApks.keystore(temp.root, "app.p12")
    shell = InProcessTestApks.shellApk(temp.root)
    appApk = InProcessTestApks.signedAppApk(temp.root, appKeystore)
  }

  @After
  fun restore() {
    SurveyBundles.source = null
  }

  @Test
  fun `a tool packaged into the test APK runs a packaged survey through ctx surveys`() = runBlocking {
    val esbuild = LazyYamlScriptedToolRegistration.resolveEsbuildBinary()
    val sdkEntry = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry()
    val ready = esbuild != null && sdkEntry != null
    // CI installs both, so a skip there would report green over the end-to-end proof.
    if (System.getenv("CI") == "true") assertThat(ready).isTrue()
    assumeTrue("needs esbuild and the TypeScript SDK source (bun install in sdks/typescript)", ready)
    val trailmap = shopTrailmap()
    File(trailmap, "tools/checkoutWasDone.ts").writeText(
      """
      |import { trailblaze } from "@trailblaze/scripting";
      |export const checkoutWasDone = trailblaze.tool(async (input: any, ctx) => {
      |  const result = await (ctx.surveys as any)[input.survey]();
      |  return JSON.stringify({ passed: result.passed, data: result.findings.map((f: any) => f.data), message: result.message });
      |});
      """.trimMargin(),
    )

    val result = InProcessShellPackager.make(request(trailmap, esbuild, sdkEntry))

    // What the device sees: the APK's survey assets, and its tool bundle.
    val assets = extract(result.outputApk, "assets/${SurveyBundles.PACKAGED_DIR}/")
    SurveyBundles.source = PackagedSurveyBundleSource.forDirectory(assets)
    val toolBundle = ApkZipRewriter.readEntry(result.outputApk, "assets/trails/config/trailmaps/shop/tools/checkoutWasDone.bundle.js")!!.decodeToString()

    val passed = run(toolBundle, "checkoutDone", listOf("checkout_started", "checkout_done"))
    assertEquals(JsonPrimitive(true), passed["passed"], passed.toString())
    assertEquals("[{\"step\":\"checkout\"}]", passed["data"].toString())
    val failed = run(toolBundle, "checkoutDone", listOf("checkout_started"))
    assertEquals(JsonPrimitive(false), failed["passed"], failed.toString())
    assertThat(failed["message"]!!.jsonPrimitive.content).contains("found nothing")
    // Packaged without a bundle, and still answered on the device.
    val afterwards = run(toolBundle, "weeklyTotals", emptyList())
    assertEquals(JsonPrimitive(false), afterwards["passed"], afterwards.toString())
    assertThat(afterwards["message"]!!.jsonPrimitive.content).contains("runs only after the session ends")
  }

  @Test
  fun `packages each survey file, and says why one could not be bundled without failing the APK`() {
    val trailmap = shopTrailmap()

    val result = InProcessShellPackager.make(request(trailmap, esbuild = null, sdkEntry = null))

    val dir = "assets/${SurveyBundles.PACKAGED_DIR}/trailmaps/shop/surveys"
    val entries = ApkZipRewriter.entryNames(result.outputApk)
    assertThat(entries).contains("$dir/checkout.survey.ts")
    assertThat(entries).contains("$dir/weekly.survey.ts")
    // A survey that runs only after the session needs no bundle, so nothing is reported missing.
    assertThat(entries).doesNotContain("$dir/weekly.survey.ts${SurveyBundles.PACKAGED_ERROR_SUFFIX}")
    val error = ApkZipRewriter.readEntry(result.outputApk, "$dir/checkout.survey.ts${SurveyBundles.PACKAGED_ERROR_SUFFIX}")!!.decodeToString()
    assertThat(error).contains("Pass --esbuild to make-test-apk")
    assertThat(result.warnings.any { it.startsWith("Survey file shop/surveys/checkout.survey.ts cannot run mid-trail") }).isTrue()
    assertThat(result.warnings.none { "weekly.survey.ts" in it }).isTrue()
    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(trailmap))).isEqualTo(true)
  }

  @Test
  fun `leaves surveys out of a shell that cannot load them, and says so`() {
    shell = InProcessTestApks.shellApk(temp.newFolder(), properties = "shellVersion=20260831.000000.abc1234\nfloored=true\n")

    val result = InProcessShellPackager.make(request(shopTrailmap(), esbuild = null, sdkEntry = null))

    assertThat(ApkZipRewriter.entryNames(result.outputApk).none { it.startsWith("assets/${SurveyBundles.PACKAGED_DIR}/") }).isTrue()
    assertThat(result.warnings.any { it.startsWith("Surveys not packaged:") && "Rebuild the shell" in it }).isTrue()
  }

  @Test
  fun `refuses two trailmaps whose surveys would be packaged at the same path`() {
    val error = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(listOf(shopTrailmap(), shopTrailmap()), esbuild = null, sdkEntry = null))
    }
    assertThat(error.message!!).contains("Two inputs both map to assets/${SurveyBundles.PACKAGED_DIR}/trailmaps/shop/surveys/checkout.survey.ts")
  }

  private fun shopTrailmap(): File = File(temp.newFolder(), "shop").apply {
    File(this, "tools").mkdirs()
    File(this, "surveys").mkdirs()
    File(this, "surveys/checkout.survey.ts").writeText(
      """
      |import { trailblaze } from "@trailblaze/scripting/surveys";
      |export const checkoutDone = trailblaze.survey({ description: "Checkout finished", finding: { analytics: { name: "checkout_done" }, data: { step: "checkout" } } });
      """.trimMargin(),
    )
    // Imports what the sandbox lacks, as a survey that runs only after the session may.
    File(this, "surveys/weekly.survey.ts").writeText(
      """
      |import { readFileSync } from "node:fs";
      |import { trailblaze } from "@trailblaze/scripting/surveys";
      |export const weeklyTotals = trailblaze.survey({ description: "Weekly totals", requiresFinishedSession: true, finding: { analytics: { name: readFileSync("x", "utf8") } } });
      """.trimMargin(),
    )
  }

  /** Runs the packaged tool in QuickJS, its host functions answering as a device session's would. */
  private suspend fun run(toolBundle: String, survey: String, analytics: List<String>): JsonObject {
    val context = context()
    val binding = object : HostBinding {
      override suspend fun callFromBundle(name: String, argsJson: String): String = error("unused")
      override suspend fun callHostFunction(name: String, argsJson: String): String {
        val args = Json.parseToJsonElement(argsJson).jsonObject
        val value = when (name) {
          SurveyHostFunctions.LOAD -> SurveyHostFunctions.call(args, context)
          "trailblaze.sessionEvents.list" -> buildJsonArray { add(JsonPrimitive("analytics")) }
          "trailblaze.sessionEvents.query" -> {
            val after = args["after"]?.jsonPrimitive?.content?.toInt() ?: 0
            val page = analytics.drop(after)
            buildJsonObject {
              put("captured", true)
              put("complete", true)
              put("cursor", after + page.size)
              put("events", JsonArray(page.mapIndexed { i, n -> buildJsonObject { put("timeMs", i); put("data", buildJsonObject { put("name", n) }) } }))
            }
          }
          else -> error("unexpected host function $name")
        }
        return buildJsonObject { put("ok", true); put("value", value) }.toString()
      }
    }
    val host = QuickJsToolHost.connect(toolBundle, hostBinding = binding)
    try {
      val response = host.callTool("checkoutWasDone", buildJsonObject { put("survey", survey) })
      val text = (response["content"] as JsonArray).first().jsonObject["text"]!!.jsonPrimitive.content
      return Json.parseToJsonElement(text).jsonObject
    } finally {
      host.shutdown()
    }
  }

  private fun context() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "emulator-5554", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 2400,
    ),
    sessionProvider = { TrailblazeSession(sessionId = SessionId("on-device"), startTime = Clock.System.now()) },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  private fun extract(apk: File, prefix: String): File {
    val root = temp.newFolder("extracted")
    ApkZipRewriter.entryNames(apk).filter { it.startsWith(prefix) && !it.endsWith("/") }.forEach { entry ->
      File(root, entry.removePrefix(prefix)).apply { parentFile.mkdirs() }.writeBytes(ApkZipRewriter.readEntry(apk, entry)!!)
    }
    return root
  }

  private fun request(trailmap: File, esbuild: File?, sdkEntry: File?) = request(listOf(trailmap), esbuild, sdkEntry)

  private fun request(trailmaps: List<File>, esbuild: File?, sdkEntry: File?) = InProcessShellPackager.Request(
    shellApk = shell,
    outputApk = File(temp.newFolder(), "out.apk"),
    targetPackage = InProcessTestApks.APP_PACKAGE,
    keystore = appKeystore,
    keyAlias = "test",
    storePassword = InProcessTestApks.PASSWORD.toCharArray(),
    keyPassword = InProcessTestApks.PASSWORD.toCharArray(),
    appApk = appApk,
    trailmapDirs = trailmaps,
    esbuildBinary = esbuild,
    inProcessSdkEntry = sdkEntry,
  )
}
