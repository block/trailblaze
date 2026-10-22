package xyz.block.trailblaze.scripting

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.HostBinding
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.surveys.SurveyHostFunctions
import xyz.block.trailblaze.surveys.SurveyNotFoundException
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

class WorkspaceSurveyBundleSourceTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val sessions = mutableListOf<SessionId>()

  @After
  fun restore() {
    sessions.forEach(SurveyBundles::unregister)
  }

  private fun workspace(name: String = "config", survey: String = "checkoutDone"): File = tempFolder.newFolder(name).also { root ->
    File(root, "trailmaps/shop/surveys").mkdirs()
    File(root, "trailmaps/shop/surveys/checkout.survey.ts").writeText(
      """
      |import { trailblaze } from "@trailblaze/scripting/surveys";
      |export const $survey = trailblaze.survey({ description: "Checkout finished", finding: { analytics: { name: "checkout_done" }, data: { flow: "checkout" } } });
      """.trimMargin(),
    )
  }

  private fun context(session: String): TrailblazeToolExecutionContext {
    sessions += SessionId(session)
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(instanceId = "web", trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB),
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        widthPixels = 1280,
        heightPixels = 800,
      ),
      sessionProvider = { TrailblazeSession(sessionId = SessionId(session), startTime = Clock.System.now()) },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }

  private fun shopTarget(workspace: File) = YamlBackedHostAppTarget(
    config = AppTargetYamlConfig(id = "shop", displayName = "Shop"),
    toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    trailmapDirs = mapOf("shop" to File(workspace, "trailmaps/shop")),
  )

  @Test
  fun `each session loads surveys from the workspace its target came from`() = runBlocking {
    val built = File(tempFolder.root, "built.js").apply { writeText("// built") }
    val shop = workspace("shop-config", survey = "checkoutDone")
    val other = workspace("other-config", survey = "refundDone")
    for ((session, ws) in listOf("a" to shop, "b" to other)) {
      val configDir = WorkspaceSurveyBundleSource.configDirOf(shopTarget(ws))
      assertEquals(ws.absoluteFile, configDir)
      SurveyBundles.register(SessionId(session), WorkspaceSurveyBundleSource(configDir = { configDir }, bundle = { built }))
    }
    val args = buildJsonObject { put("name", "checkoutDone") }

    assertEquals(
      "trailmaps/shop/surveys/checkout.survey.ts",
      SurveyHostFunctions.call(args, context("a")).jsonObject["file"]!!.jsonPrimitive.content,
    )
    val otherSession = assertFailsWith<TrailblazeHostFunctionException> { SurveyHostFunctions.call(args, context("b")) }
    assertTrue(otherSession.message!!.contains("the workspace exports: refundDone."), otherSession.message)

    // A session that registered nothing, or has finished, gets no workspace's surveys.
    SurveyBundles.unregister(SessionId("a"))
    val finished = assertFailsWith<TrailblazeHostFunctionException> { SurveyHostFunctions.call(args, context("a")) }
    assertTrue(finished.message!!.contains("packaged no surveys"), finished.message)
  }

  @Test
  fun `a session's surveys stay until the last of its runtimes shuts down`() = runBlocking {
    val built = File(tempFolder.root, "built.js").apply { writeText("// built") }
    val shop = workspace()
    // A multi-device session launches one runtime per device, each registering the session.
    repeat(2) { SurveyBundles.register(SessionId("pair"), WorkspaceSurveyBundleSource(configDir = { shop }, bundle = { built })) }
    val args = buildJsonObject { put("name", "checkoutDone") }

    SurveyBundles.unregister(SessionId("pair"))
    assertEquals("// built", SurveyHostFunctions.call(args, context("pair")).jsonObject["source"]!!.jsonPrimitive.content)

    SurveyBundles.unregister(SessionId("pair"))
    val finished = assertFailsWith<TrailblazeHostFunctionException> { SurveyHostFunctions.call(args, context("pair")) }
    assertTrue(finished.message!!.contains("packaged no surveys"), finished.message)
  }

  @Test
  fun `survey files in every extension the post-run loader reads are found`() {
    val root = tempFolder.newFolder("extensions")
    val surveys = File(root, "trailmaps/shop/surveys").apply { mkdirs() }
    listOf("a.survey.ts", "b.survey.mts", "c.survey.js", "d.survey.mjs", "e.survey.cjs", "f.ts").forEach { File(surveys, it).writeText("") }
    assertEquals(
      listOf("a.survey.ts", "b.survey.mts", "c.survey.js", "d.survey.mjs"),
      WorkspaceSurveyBundleSource.surveyFiles(root).map { it.name },
    )
  }

  @Test
  fun `a launch nested in a session keeps the survey source the session registered first`() = runBlocking {
    val built = File(tempFolder.root, "built.js").apply { writeText("// built") }
    val trailWorkspace = workspace("trail-config", survey = "checkoutDone")
    val elsewhere = workspace("target-config", survey = "refundDone")
    SurveyBundles.register(SessionId("nested"), WorkspaceSurveyBundleSource(configDir = { trailWorkspace }, bundle = { built }))
    SurveyBundles.register(SessionId("nested"), WorkspaceSurveyBundleSource(configDir = { elsewhere }, bundle = { built }))
    val args = buildJsonObject { put("name", "checkoutDone") }
    assertEquals("// built", SurveyHostFunctions.call(args, context("nested")).jsonObject["source"]!!.jsonPrimitive.content)
    SurveyBundles.unregister(SessionId("nested"))
  }

  @Test
  fun `a load sends the workspace's targets, so a target-scoped survey matches a session on its apps`() = runBlocking {
    val built = File(tempFolder.root, "built.js").apply { writeText("// built") }
    val root = workspace("targets-config")
    File(root, "trailmaps/shop/trailmap.yaml").writeText(
      "id: shop\ntarget:\n  display_name: Shop\n  platforms:\n    android:\n      app_ids: [com.example.shop]\n    ios:\n      app_ids: [com.example.shop.ios]\n",
    )
    File(root, "trailmaps/broken").mkdirs()
    File(root, "trailmaps/broken/trailmap.yaml").writeText("id: [not, a, string\n")
    SurveyBundles.register(SessionId("targets"), WorkspaceSurveyBundleSource(configDir = { root }, bundle = { built }))
    val loaded = SurveyHostFunctions.call(buildJsonObject { put("name", "checkoutDone") }, context("targets")).jsonObject
    assertEquals(
      Json.parseToJsonElement("""{"shop":{"android":["com.example.shop"],"ios":["com.example.shop.ios"]}}"""),
      loaded["targets"],
    )
  }

  @Test
  fun `a quoted key counts, and a key-like run inside a string or after a comment does not mislead`() {
    val surveys = SurveyBundles.exportedSurveys(
      """
      |export const quoted = survey({ "feature": "shop.quoted", "requiresFinishedSession": true });
      |export const described = survey({ feature: "shop.described", description: "x, requiresFinishedSession: true" });
      |export const commented = survey({ feature: "shop.commented", finding: { events: { text: /* note */ /\}/ } }, requiresFinishedSession: true });
      """.trimMargin(),
    )
    assertEquals(
      listOf(Triple("quoted", "shop.quoted", true), Triple("described", "shop.described", false), Triple("commented", "shop.commented", true)),
      surveys.map { Triple(it.name, it.feature, it.requiresFinishedSession) },
    )
  }

  @Test
  fun `a survey that types its data with a type argument is still found`() {
    val surveys = SurveyBundles.exportedSurveys(
      """
      |export const typed = trailblaze.survey<Journey>({ description: "x", requiresFinishedSession: true }, () => undefined);
      |export const nested = survey<Record<string, { id: string }>>({ description: "y" }, () => undefined);
      |export const parenthesized = survey<{ outcomes: ("ok" | "lost")[]; at: () => number }>({ requiresFinishedSession: true }, () => undefined);
      |export const notACall = surveyHelpers;
      """.trimMargin(),
    )
    assertEquals(
      listOf("typed" to true, "nested" to false, "parenthesized" to true),
      surveys.map { it.name to it.requiresFinishedSession },
    )
  }

  @Test
  fun `only a spec's own requiresFinishedSession moves a survey after the session`() {
    val surveys = SurveyBundles.exportedSurveys(
      """
      |export const live = survey({
      |  feature: "shop.live",
      |  finding: { analytics: { name: "x", properties: { requiresFinishedSession: true, feature: "nested" } } },
      |});
      |export const after = survey({ feature: "shop.after", finding: { network: { path: "/x" } }, requiresFinishedSession: true });
      """.trimMargin(),
    )
    assertEquals(listOf("live" to false, "after" to true), surveys.map { it.name to it.requiresFinishedSession })
    assertEquals(listOf("shop.live", "shop.after"), surveys.map { it.feature })
  }

  @Test
  fun `a session's surveys come from its trail file's workspace before its target's`() {
    val trailWorkspace = tempFolder.newFolder("checkout-repo")
    val config = File(trailWorkspace, "trails/config").apply { mkdirs() }
    File(config, "trailblaze.yaml").writeText("targets:\n  - shop\n")
    val trail = File(trailWorkspace, "trails/flows/checkout.trail.yaml").apply { parentFile.mkdirs(); writeText("trail: []\n") }
    val targetWorkspace = workspace("served-config")

    assertEquals(config.canonicalFile, WorkspaceSurveyBundleSource.configDirFor(trail.path, shopTarget(targetWorkspace))?.canonicalFile)
    // A trail outside any workspace falls back to the target's.
    val loose = File(tempFolder.newFolder("loose"), "one.trail.yaml").apply { writeText("trail: []\n") }
    assertEquals(targetWorkspace.absoluteFile, WorkspaceSurveyBundleSource.configDirFor(loose.path, shopTarget(targetWorkspace)))
  }

  @Test
  fun `a target bundled with the CLI anchors no workspace`() {
    val bundled = YamlBackedHostAppTarget(
      config = AppTargetYamlConfig(id = "shop", displayName = "Shop"),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
      trailmapDirs = mapOf("shop" to null),
    )
    assertEquals(null, WorkspaceSurveyBundleSource.configDirOf(bundled))
    assertEquals(null, WorkspaceSurveyBundleSource.configDirOf(null))
  }

  @Test
  fun `finds a survey by its export name in a trailmap's surveys directory`() {
    val root = workspace()
    // A survey file outside any surveys/ directory is not one, even when an ancestor of the
    // workspace is named surveys.
    File(root, "trailmaps/shop/tools").mkdirs()
    File(root, "trailmaps/shop/tools/stray.survey.ts").writeText("export const stray = survey({ feature: \"x\" });")
    val built = File(tempFolder.root, "built.js").apply { writeText("// built") }
    val bundled = mutableListOf<String>()
    val source = WorkspaceSurveyBundleSource(configDir = { root }, bundle = { bundled += it.name; built })

    val bundle = source.load("checkoutDone")

    assertEquals("trailmaps/shop/surveys/checkout.survey.ts", bundle.file)
    assertEquals("// built", bundle.source)
    assertEquals(listOf("checkout.survey.ts"), bundled)
    val missing = assertFailsWith<SurveyNotFoundException> { source.load("stray") }
    assertTrue(missing.message!!.contains("the workspace exports: checkoutDone."), missing.message)
  }

  @Test
  fun `a survey name exported by two files is refused, naming both`() {
    val root = workspace()
    File(root, "surveys").mkdirs()
    File(root, "surveys/other.survey.ts").writeText("export const checkoutDone = survey({ feature: \"other\", finding: { network: { path: \"/x\" } } });")
    val source = WorkspaceSurveyBundleSource(configDir = { root }, bundle = { error("not built") })
    val e = assertFailsWith<IllegalStateException> { source.load("checkoutDone") }
    assertTrue(e.message!!.contains("exported by surveys/other.survey.ts and trailmaps/shop/surveys/checkout.survey.ts"), e.message)
  }

  @Test
  fun `a survey that cannot be built for the sandbox names the flag that moves it after the session`() {
    val source = WorkspaceSurveyBundleSource(configDir = ::workspace, bundle = { error("Could not resolve \"node:fs\"") })
    val e = assertFailsWith<IllegalStateException> { source.load("checkoutDone") }
    assertTrue(e.message!!.contains("requiresFinishedSession: true"), e.message)
    assertTrue(e.message!!.contains("Could not resolve \"node:fs\""), e.message)
  }

  /**
   * The whole path in the engine tools run in: a tool calls `ctx.surveys.checkoutDone()`, the host
   * function builds the survey file with the daemon's toolchain, and the tool's engine evaluates it.
   */
  @Test
  fun `a tool runs a workspace survey mid-trail in QuickJS`() = runBlocking {
    val esbuild = LazyYamlScriptedToolRegistration.resolveEsbuildBinary()
    val sdkEntry = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry()
    assumeTrue("needs esbuild and the TypeScript SDK source (bun install in sdks/typescript)", esbuild != null && sdkEntry != null)
    val root = workspace()
    val cache = tempFolder.newFolder("cache")
    val toolContext = context("quickjs")
    SurveyBundles.register(
      SessionId("quickjs"),
      WorkspaceSurveyBundleSource(
        configDir = { root },
        bundle = { WorkspaceSurveyBundleSource.bundleWithResolvedToolchain(it, cache) },
      ),
    )
    val toolFile = File(root, "trailmaps/shop/tools/checkoutWasDone.ts").apply {
      parentFile.mkdirs()
      writeText(
        """
        |import { trailblaze } from "@trailblaze/scripting";
        |export const checkoutWasDone = trailblaze.tool(async (_input, ctx) => {
        |  const result = await (ctx.surveys as any).checkoutDone();
        |  return JSON.stringify({ passed: result.passed, data: result.findings.map((f: any) => f.data), message: result.message });
        |});
        """.trimMargin(),
      )
    }
    val toolBundle = DaemonScriptedToolBundler(
      esbuild!!,
      inProcessSdkEntryOverride = sdkEntry,
      allowLegacyEsbuildWalkup = false,
      cacheDir = cache,
    ).bundleOne(toolFile, toolName = "checkoutWasDone")

    suspend fun run(analytics: List<String>): JsonObject {
      val binding = object : HostBinding {
        override suspend fun callFromBundle(name: String, argsJson: String): String = error("unused")
        override suspend fun callHostFunction(name: String, argsJson: String): String {
          val args = Json.parseToJsonElement(argsJson).jsonObject
          val value = when (name) {
            SurveyHostFunctions.LOAD -> SurveyHostFunctions.call(args, toolContext)
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
      val host = QuickJsToolHost.connect(toolBundle.readText(), hostBinding = binding)
      try {
        val text = (host.callTool("checkoutWasDone", JsonObject(emptyMap()))["content"] as JsonArray).first().jsonObject["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
      } finally {
        host.shutdown()
      }
    }

    val passed = run(listOf("checkout_started", "checkout_done"))
    assertEquals(JsonPrimitive(true), passed["passed"], passed.toString())
    assertEquals("[{\"flow\":\"checkout\"}]", passed["data"].toString())
    val failed = run(listOf("checkout_started"))
    assertEquals(JsonPrimitive(false), failed["passed"], failed.toString())
    assertTrue(failed["message"]!!.jsonPrimitive.content.contains("found nothing"), failed.toString())
  }
}
