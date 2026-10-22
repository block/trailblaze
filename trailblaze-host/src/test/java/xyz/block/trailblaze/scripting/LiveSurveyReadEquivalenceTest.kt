package xyz.block.trailblaze.scripting

import com.dokar.quickjs.QuickJs
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.events.SessionCaptureHealth
import xyz.block.trailblaze.events.SessionEventsHostFunctions
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.HostBinding
import xyz.block.trailblaze.quickjs.tools.QuickJsEngineExtension
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionDispatcher
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionDispatcher.toEnvelopeJson
import xyz.block.trailblaze.surveys.SurveyBundle
import xyz.block.trailblaze.surveys.SurveyBundleSource
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * A mid-trail survey reads through a host that filters (`match`, `count`) and through one that
 * can't, which is how every survey read before the host filtered: both must reach the same verdict,
 * findings and message. Only the records that cross into QuickJS, and the heap they take, differ.
 *
 * Runs in QuickJS against the real `ctx.events` host functions, over a session this test writes.
 * `TRAILBLAZE_LIVE_EQUIVALENCE_SESSIONS` adds recorded sessions (directories or zips) and
 * `TRAILBLAZE_LIVE_EQUIVALENCE_SURVEYS` more survey files, both separated by the path separator;
 * `TRAILBLAZE_LIVE_EQUIVALENCE_BASE_SDK` names another SDK checkout whose reader stands in for the
 * one that can't filter; `TRAILBLAZE_LIVE_EQUIVALENCE_REPORT` is where the comparison is written,
 * as tab-separated rows. Run with `--no-configuration-cache` when setting them: a cached
 * configuration hands the test the environment of the build that stored it.
 */
class LiveSurveyReadEquivalenceTest {

  @get:Rule val temp = TemporaryFolder()

  @Test
  fun `a survey reaches the same verdict through a host that filters and one that cannot`() = runBlocking {
    val headBundler = WorkspaceSurveyBundleSource.resolvedToolchainBundler(temp.newFolder("cache-head"))
    // CI installs both, so a skip there would report green over the comparison.
    if (System.getenv("CI") == "true") assertTrue(headBundler != null, "esbuild and the TypeScript SDK are needed")
    assumeTrue("needs esbuild and the TypeScript SDK source (bun install in sdks/typescript)", headBundler != null)
    val baseSdk = System.getenv("TRAILBLAZE_LIVE_EQUIVALENCE_BASE_SDK")?.takeIf { it.isNotBlank() }?.let(::File)
    val baseBundler = baseSdk?.let {
      DaemonScriptedToolBundler(
        LazyYamlScriptedToolRegistration.resolveEsbuildBinary()!!,
        inProcessSdkEntryOverride = File(it, "src/in-process.ts"),
        cacheDir = temp.newFolder("cache-base"),
      )
    }
    val sessions = listOf(cleanSession(), lossySession()) + paths("TRAILBLAZE_LIVE_EQUIVALENCE_SESSIONS").flatMap(::sessionDirs)
    val surveyFiles = listOf(fixtureSurveys()) + paths("TRAILBLAZE_LIVE_EQUIVALENCE_SURVEYS").flatMap(::surveyFilesIn)
    val tool = File(temp.newFolder("tool"), "probe.ts").apply { writeText(PROBE_TOOL) }
    val headTool = headBundler!!.bundleOne(tool, "probe").readText()

    val rows = mutableListOf<Comparison>()
    for (file in surveyFiles) {
      val names = SurveyBundles.exportedSurveys(file.readText()).filterNot { it.requiresFinishedSession }.map { it.name }
      if (names.isEmpty()) continue
      // One that won't bundle for QuickJS (it reads files, say) can't run mid-trail at all.
      val head = runCatching { headBundler.bundleSurvey(file).readText() }.getOrElse {
        println("not live-capable, skipped: ${file.path}: ${it.message?.lineSequence()?.firstOrNull { line -> "ERROR" in line } ?: it.message}")
        continue
      }
      val base = baseBundler?.bundleSurvey(file)?.readText()
      // A trailmap's survey is scoped to its target, so the session runs as that target's.
      val target = file.parentFile?.takeIf { it.name == "surveys" }?.parentFile?.name
      for (session in sessions) {
        for (name in names) {
          val filtering = run(headTool, head, file.name, name, session, target, filtering = true)
          val whole = if (base != null) run(headTool, base, file.name, name, session, target, filtering = true) else run(headTool, head, file.name, name, session, target, filtering = false)
          rows += Comparison(file.name, name, session, filtering, whole)
        }
      }
    }
    report(rows)

    val differing = rows.filterNot { it.same }
    assertTrue(differing.isEmpty(), differing.joinToString("\n\n") { it.describe() })
    // The fixture's name finding holds only what it matched; a handler holds the same either way.
    val clean = rows.filter { it.session.name == "clean" && it.file == "fixture.survey.ts" }
    assertEquals(SurveyBundles.exportedSurveyNames(FIXTURE_SURVEYS), clean.map { it.survey })
    val byName = clean.associateBy { it.survey }
    // The one matching analytics row, plus the 51 flat legacy-analytics rows a table-row match can't filter.
    assertEquals(1 + 51, byName.getValue("itemAdded").filtering.crossed)
    assertTrue(byName.getValue("itemAdded").whole.crossed > 1_000)
    assertEquals(byName.getValue("countsEverything").whole.crossed, byName.getValue("countsEverything").filtering.crossed)
    val itemAdded = byName.getValue("itemAdded")
    assertTrue(itemAdded.filtering.heldHeap < itemAdded.whole.heldHeap, "a finding's heap (${itemAdded.filtering.heldHeap}) is below a whole read's (${itemAdded.whole.heldHeap})")
  }

  /** One survey on one session, read both ways. */
  private class Comparison(
    val file: String,
    val survey: String,
    val session: File,
    val filtering: Outcome,
    val whole: Outcome,
  ) {
    /** The limit a handler holds is the one difference allowed: past it the filtering reader stops. */
    val capped = filtering.message.contains("cannot judge this session mid-trail")
    val same = capped || (filtering.passed == whole.passed && filtering.findings == whole.findings && comparable(filtering.message) == comparable(whole.message))

    /**
     * A message without how long the survey waited, which differs from run to run; the stack of an
     * error it threw, whose line numbers are the bundle's; or how many oversized records a stream
     * skipped. The host counts those per scan, and a reader that can't count kept the number from
     * its first page, so it under-reports; a count scans the whole file.
     */
    private fun comparable(message: String) =
      message.lines().filterNot { it.trimStart().startsWith("at ") }.joinToString("\n")
        .replace(Regex("""in \d+ ms"""), "in N ms").replace(Regex("""\d+ record\(s\) over"""), "N record(s) over")

    fun describe() = "$file#$survey on ${session.name}:\n  filtering: $filtering\n  whole:     $whole"
  }

  private data class Outcome(
    val passed: Boolean,
    val message: String,
    val findings: String,
    /** Records the host's answers carried into QuickJS. */
    val crossed: Int,
    val queries: Int,
    /**
     * Bytes of QuickJS heap the host's answers took, kept alive until the survey returned. A
     * reader holds every record it is sent, so this is what it held.
     */
    val heldHeap: Long,
  ) {
    override fun toString() = "passed=$passed findings=$findings message=$message"
  }

  /** Runs [survey] from [surveyBundle] through a QuickJS tool on [target], its `ctx.events` the real host functions over [session]. */
  private suspend fun run(
    toolBundle: String,
    surveyBundle: String,
    file: String,
    survey: String,
    session: File,
    target: String?,
    filtering: Boolean,
  ): Outcome {
    val sessionId = SessionId("equivalence")
    SurveyBundles.register(sessionId, SurveyBundleSource { SurveyBundle(surveyBundle, file) })
    val context = context(sessionId, session)
    var engine: QuickJs? = null
    var crossed = 0
    var queries = 0
    val binding = object : HostBinding {
      override suspend fun callFromBundle(name: String, argsJson: String): String = error("unused")
      override suspend fun callHostFunction(name: String, argsJson: String): String {
        var args = argsJson
        if (!filtering && name == SessionEventsHostFunctions.QUERY) {
          // A host that can't count answers every query with its records.
          val parsed = Json.parseToJsonElement(argsJson).jsonObject
          if (parsed["count"] != null) args = JsonObject(parsed - "count" - "match").toString()
        }
        val envelope = TrailblazeHostFunctionDispatcher.call(name, args, context).toEnvelopeJson()
        if (name == SessionEventsHostFunctions.QUERY) {
          queries++
          crossed += (Json.parseToJsonElement(envelope).jsonObject["value"] as? JsonObject)?.get("events")?.jsonArray?.size ?: 0
        }
        return envelope
      }
    }
    val host = QuickJsToolHost.connect(
      toolBundle,
      hostBinding = binding,
      engineExtension = QuickJsEngineExtension { quickJs -> engine = quickJs },
    )
    try {
      // Read before the survey runs: the engine's own lock makes a read during it wait forever.
      val start = engine!!.also { it.gc() }.memoryUsage.mallocSize
      val text = runCatching {
        val ctx = buildJsonObject {
          putJsonObject("device") { put("platform", "android"); put("driverType", "android-ondevice-accessibility") }
          target?.let { putJsonObject("target") { put("id", it) } }
        }
        val response = host.callTool("probe", buildJsonObject { put("survey", survey) }, ctx)
        (response["content"] as JsonArray).first().jsonObject["text"]!!.jsonPrimitive.content
      }.getOrElse { it.toString() }
      val held = engine!!.also { it.gc() }.memoryUsage.mallocSize - start
      val result = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(mapOf("message" to JsonPrimitive(text))) }
      return Outcome(
        passed = result["passed"]?.jsonPrimitive?.booleanOrNull ?: false,
        message = (result["message"] as? JsonPrimitive)?.content ?: text,
        findings = result["findings"].toString(),
        crossed = crossed,
        queries = queries,
        heldHeap = held,
      )
    } finally {
      host.shutdown()
      SurveyBundles.unregister(sessionId)
    }
  }

  private fun context(sessionId: SessionId, dir: File) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "emulator-5554", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 2400,
    ),
    sessionProvider = { TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now()) },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    sessionDirProvider = { dir },
  )

  private fun report(rows: List<Comparison>) {
    val out = System.getenv("TRAILBLAZE_LIVE_EQUIVALENCE_REPORT")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
    out.parentFile?.mkdirs()
    out.writeText(
      buildString {
        appendLine("file\tsurvey\tsession\tsame\tcapped\tpassed\tcrossedFiltering\tcrossedWhole\tqueriesFiltering\tqueriesWhole\theldHeapFiltering\theldHeapWhole\tmessage")
        for (r in rows) {
          appendLine(
            listOf(r.file, r.survey, r.session.name, r.same, r.capped, r.filtering.passed, r.filtering.crossed, r.whole.crossed, r.filtering.queries, r.whole.queries, r.filtering.heldHeap, r.whole.heldHeap, r.filtering.message.replace(Regex("\\s+"), " ").take(300))
              .joinToString("\t"),
          )
        }
      },
    )
  }

  private fun paths(env: String): List<File> =
    System.getenv(env)?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.map(::File).orEmpty()

  private fun surveyFilesIn(path: File): List<File> =
    if (path.isDirectory) path.walkTopDown().filter { it.isFile && SurveyBundles.isSurveyFile(it.name) }.sortedBy { it.path }.toList() else listOf(path)

  /** Session directories under [path]: the ones there, and the ones its zips hold, unpacked. */
  private fun sessionDirs(path: File): List<File> {
    if (path.isDirectory) {
      val found = path.walkTopDown().toList()
      return found.filter { it.isDirectory && (File(it, "events").isDirectory || File(it, "network.ndjson").isFile) } +
        found.filter { it.isFile && it.extension == "zip" }.sortedBy { it.name }.flatMap(::sessionDirs)
    }
    val into = temp.newFolder(path.nameWithoutExtension.take(60) + "-" + System.nanoTime())
    ZipFile(path).use { zip ->
      for (entry in zip.entries()) {
        if (entry.isDirectory) continue
        val target = File(into, entry.name).canonicalFile
        require(target.path.startsWith(into.canonicalPath + File.separator)) { "${entry.name} leaves the zip" }
        target.parentFile.mkdirs()
        zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
      }
    }
    return sessionDirs(into).map { dir -> File(dir.parentFile, path.nameWithoutExtension).takeIf { dir.renameTo(it) } ?: dir }
  }

  /** A session as a capture writes one: table and flat analytics rows, network rows, a log. */
  private fun cleanSession(name: String = "clean"): File = temp.newFolder(name).also { dir ->
    val events = File(dir, "events").apply { mkdirs() }
    var t = 1_000L
    fun envelope(data: JsonObject) = buildJsonObject { put("timeMs", t++); put("data", data) }.toString()
    fun table(event: String, properties: String = "{}") = buildJsonObject {
      put("id", "row-$t")
      put("columnItems", buildJsonObject { put("Time", "12:00"); put("Source", "app"); put("Event", event); put("Properties", properties) })
    }
    File(events, "analytics.ndjson").writeText(
      buildString {
        repeat(1_200) { appendLine(envelope(table(if (it % 3 == 0) "flag_read" else "screen_view", """{"i":$it}"""))) }
        appendLine(envelope(table("item_added", """{"item":"bagel"}""")))
        appendLine(envelope(table("say \"hi\"")))
        appendLine(envelope(table("back\\slash/and/slash")))
        appendLine(envelope(table("ITEM_ADDED")))
        // Written the way a JSON library may escape a slash or any character.
        appendLine("""{"timeMs":${t++},"data":{"id":"esc","columnItems":{"Event":"esc\/aped click","Source":"app"}}}""")
        repeat(300) { appendLine(envelope(table("screen_view"))) }
      },
    )
    File(events, "legacy-analytics.ndjson").writeText(
      buildString {
        repeat(50) { appendLine(envelope(buildJsonObject { put("name", "legacy_view") })) }
        appendLine(envelope(buildJsonObject { put("name", "legacy_open"); put("source", "app") }))
      },
    )
    File(events, "app-log.ndjson").writeText(
      buildString {
        repeat(200) { appendLine(envelope(buildJsonObject { put("msg", "tick $it") })) }
        appendLine(envelope(buildJsonObject { put("msg", "checkout complete"); put("total", 1.50) }))
      },
    )
    File(dir, "network.ndjson").writeText(
      buildString {
        appendLine("""{"id":"1","phase":"REQUEST_START","method":"POST","url":"https://api.example.com/v2/payments","urlPath":"/v2/payments","timestampMs":${t++}}""")
        appendLine("""{"id":"1","phase":"RESPONSE_END","statusCode":200,"timestampMs":${t++}}""")
        appendLine("""{"id":"2","phase":"REQUEST_START","method":"GET","url":"https://api.example.com/v2/items","urlPath":"/v2/items","timestampMs":${t++}}""")
      },
    )
  }

  /** The clean session, with a loss recorded on analytics and a record too long to read on the log. */
  private fun lossySession(): File = cleanSession("lossy").also { dir ->
    SessionCaptureHealth.writeMarker(dir, "3 records dropped", "analytics")
    File(dir, "events/app-log.ndjson").appendText("""{"timeMs":${9_999_999},"data":{"msg":"${"x".repeat(SessionEventsHostFunctions.MAX_LINE_BYTES)}"}}""" + "\n")
  }

  private fun fixtureSurveys(): File = File(temp.newFolder("fixture-surveys"), "fixture.survey.ts").apply { writeText(FIXTURE_SURVEYS) }

  private companion object {
    val PROBE_TOOL = """
      |import { trailblaze } from "@trailblaze/scripting";
      |// Keeps every answer ctx.events gives alive until the survey returns, so the heap read after it
      |// shows what the reader held.
      |export const probe = trailblaze.tool(async (input: any, ctx) => {
      |  const events = ctx.events as any;
      |  const kept: unknown[] = ((globalThis as any).kept = []);
      |  for (const method of ["query", "count"]) {
      |    const call = events[method];
      |    if (typeof call === "function") events[method] = async (...args: unknown[]) => { const answer = await call.apply(events, args); kept.push(answer); return answer; };
      |  }
      |  return JSON.stringify(await (ctx.surveys as any)[input.survey]({ waitMs: 0 }));
      |});
    """.trimMargin()

    /** One survey per way a finding or handler reads, each over the session [cleanSession] writes. */
    val FIXTURE_SURVEYS = """
      |import { trailblaze } from "@trailblaze/scripting/surveys";
      |export const itemAdded = trailblaze.survey({ description: "item added", finding: { analytics: { name: "item_added" } } });
      |export const itemAddedAll = trailblaze.survey({ description: "every item added", finding: { analytics: { name: /item_added/i }, evidence: "all" } });
      |export const quoted = trailblaze.survey({ description: "quoted", finding: { analytics: { name: 'say "hi"' } } });
      |export const slashes = trailblaze.survey({ description: "slashes", finding: { analytics: { name: "back\\slash/and/slash" } } });
      |export const escaped = trailblaze.survey({ description: "escaped", finding: { analytics: { name: "esc/aped click" } } });
      |export const pattern = trailblaze.survey({ description: "pattern", finding: { analytics: { name: /item_.dded/ } } });
      |export const predicate = trailblaze.survey({ description: "predicate", finding: { analytics: { name: (n: string) => n.startsWith("item") } } });
      |export const withWhere = trailblaze.survey({ description: "where", finding: { analytics: { name: "item_added", where: (e: any) => e.properties.item === "bagel" } } });
      |export const withProperties = trailblaze.survey({ description: "properties", finding: { analytics: { name: "item_added", properties: { item: "bagel" } }, data: { flow: "add" } } });
      |export const noDecline = trailblaze.survey({ description: "no decline", finding: { analytics: { name: "item_added" }, absent: { analytics: { name: "card_declined" } } } });
      |export const declined = trailblaze.survey({ description: "vetoed", finding: { analytics: { name: "item_added" }, absent: { analytics: { name: "flag_read" } } } });
      |export const otherStream = trailblaze.survey({ description: "other stream", finding: { analytics: { name: "item_added" }, absent: { analytics: { name: "legacy_open", stream: "legacy-analytics" } } } });
      |export const flatRows = trailblaze.survey({ description: "flat rows", finding: { analytics: { name: "legacy_open", stream: "legacy-analytics" } } });
      |export const neverCaptured = trailblaze.survey({ description: "never captured", finding: { analytics: { name: "x", stream: "missing-analytics" } } });
      |export const nothing = trailblaze.survey({ description: "nothing", finding: { analytics: { name: "never_sent" } } });
      |export const paid = trailblaze.survey({ description: "paid", finding: { network: { path: "/v2/payments", status: 200 }, analytics: { name: "item_added" } } });
      |export const inFlight = trailblaze.survey({ description: "in flight", finding: { network: { path: "/v2/items", status: "failed" } } });
      |export const logged = trailblaze.survey({ description: "logged", finding: { events: { stream: "app-log", contains: "checkout" } } });
      |export const noRefund = trailblaze.survey({ description: "no refund", finding: { events: { stream: "app-log", contains: "checkout" }, absent: { network: { path: "/v2/refunds" } } } });
      |export const countsEverything = trailblaze.survey({ description: "counts" }, (session) => {
      |  const all = session.analytics.all();
      |  return all.length === 0 ? undefined : { summary: "saw " + all.length + " analytics, " + session.events.all().length + " events, " + session.network.all().length + " requests", evidence: [all[0]] };
      |});
    """.trimMargin()
  }
}
