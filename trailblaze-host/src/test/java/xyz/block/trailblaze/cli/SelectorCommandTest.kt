package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import picocli.CommandLine
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.waypoint.SessionLogScreenState

/**
 * Behavior of `trailblaze selector suggest` / `selector match`, driven through the real picocli
 * tree against session logs written to disk.
 *
 * Screen A has one "Pay" button; screen B is the same screen with a second "Pay" button, the way
 * another run or another state of the same screen can differ. `--live` reads one of them through a
 * fake device.
 */
class SelectorCommandTest {

  private val tempDirs = mutableListOf<File>()
  private val capturedOut = StringBuilder()
  private val capturedErr = StringBuilder()
  private lateinit var dir: File
  private lateinit var screenA: File
  private lateinit var screenB: File

  private val payText = """{ androidAccessibility: { textRegex: "^Pay$" } }"""

  @BeforeTest
  fun setUp() {
    dir = createTempDirectory(prefix = "selector-test-").toFile().also { tempDirs += it }
    screenA = writeScreen("001_TrailblazeLlmRequestLog.json", secondPayButton = false)
    screenB = writeScreen("002_TrailblazeLlmRequestLog.json", secondPayButton = true)
  }

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  @Test
  fun `match reports each screen's matches and --expect unique fails on the screen with two`() {
    val exit = run("selector", "match", "--selector", payText, "--expect", "unique", screenA.path, screenB.path)

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, exit, "stderr=${stderr()}")
    val out = stdout()
    assertTrue("${screenA.name}: matches 1  ok" in out, out)
    assertTrue("${screenB.name}: matches 2  FAIL (expected unique)" in out, out)
    assertTrue("[a2] Button text=\"Pay\"" in out && "[a3] Button text=\"Pay\"" in out, out)
    assertTrue("FAILED on 1 of 2 screen(s)" in out, out)
  }

  @Test
  fun `match --expect present passes when every screen has the selector, absent fails`() {
    assertEquals(
      TrailblazeExitCode.SUCCESS.code,
      run("selector", "match", "--selector", payText, "--expect", "present", screenA.path, screenB.path),
    )
    assertEquals(
      TrailblazeExitCode.ASSERTION_FAILED.code,
      run("selector", "match", "--selector", payText, "--expect", "absent", screenA.path),
    )
    assertEquals(
      TrailblazeExitCode.SUCCESS.code,
      run(
        "selector", "match", "--selector", """{ androidAccessibility: { textRegex: "^Refund$" } }""",
        "--expect", "absent", screenA.path,
      ),
    )
  }

  @Test
  fun `match without --expect only reports, even when nothing matches`() {
    val exit = run("selector", "match", "--selector", """{ androidAccessibility: { textRegex: "^Refund$" } }""", screenA.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertTrue("${screenA.name}: matches 0" in stdout(), stdout())
  }

  @Test
  fun `every selector suggest offers matches the target as many times as it says when fed to match`() {
    val exit = run("selector", "suggest", "--ref", "a2", "--json", screenA.path)
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    val menu = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject
    assertEquals("a2", menu["target"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
    val candidates = menu["candidates"]!!.jsonArray
    assertTrue(candidates.isNotEmpty(), "suggest offered nothing: $menu")

    for (candidate in candidates.map { it.jsonObject }) {
      val selectorJson = candidate["selector"].toString()
      val reported = candidate["matches"]!!.jsonPrimitive.int
      capturedOut.clear()
      assertEquals(TrailblazeExitCode.SUCCESS.code, run("selector", "match", "--selector", selectorJson, "--json", screenA.path))
      val screen = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject["screens"]!!.jsonArray.single().jsonObject
      val refs = screen["nodes"]!!.jsonArray.map { it.jsonObject["ref"]?.jsonPrimitive?.content }
      assertEquals(reported, screen["matches"]!!.jsonPrimitive.int, "count drift for $selectorJson")
      assertTrue("a2" in refs, "$selectorJson does not match the element it was suggested for: $refs")
    }
  }

  @Test
  fun `suggest --xy picks the element under the point, same as --at`() {
    run("selector", "suggest", "--xy", "270,250", "--json", screenA.path)
    val viaXy = stdout()
    capturedOut.clear()
    run("selector", "suggest", "--at", "270,250", "--json", screenA.path)

    assertEquals("a2", selectorCliJson.parseToJsonElement(viaXy.trim()).jsonObject["target"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
    assertEquals(viaXy.substringAfter("\"target\""), stdout().substringAfter("\"target\""))
  }

  @Test
  fun `waypoint suggest-selector still works and prints what selector suggest prints`() {
    assertEquals(TrailblazeExitCode.SUCCESS.code, run("selector", "suggest", "--ref", "a2", screenA.path))
    val renamed = stdout()
    capturedOut.clear()
    assertEquals(TrailblazeExitCode.SUCCESS.code, run("waypoint", "suggest-selector", "--ref", "a2", screenA.path))

    assertEquals(renamed, stdout())
    assertTrue("Strategy:" in renamed, renamed)
  }

  @Test
  fun `suggest --batch answers each line in order, relative to the caller's cwd, and keeps going past a bad line`() {
    val batch = File(dir, "questions.jsonl").apply {
      writeText(
        """
        {"log": "${screenA.name}", "ref": "a2"}
        {"log": "${screenA.name}", "ref": "nope"}

        {"session": ".", "step": 1, "at": "270,250", "max": 1}
        """.trimIndent(),
      )
    }

    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "suggest", "--batch", batch.name) }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "a bad line must surface in the exit code")
    val lines = stdout().trim().lines().map { selectorCliJson.parseToJsonElement(it).jsonObject }
    assertEquals(listOf(1, 2, 4), lines.map { it["line"]!!.jsonPrimitive.int })
    assertEquals("a2", lines[0]["target"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
    assertTrue("Ref not found" in lines[1]["error"]!!.jsonPrimitive.content, lines[1].toString())
    assertEquals("a2", lines[2]["target"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
    assertEquals(1, lines[2]["candidates"]!!.jsonArray.size, "per-line max must apply")
  }

  @Test
  fun `match --batch takes a selector object per line and applies each line's own expectation`() {
    val batch = File(dir, "checks.jsonl").apply {
      writeText(
        """
        {"selector": {"androidAccessibility": {"textRegex": "^Pay$"}}, "logs": ["${screenA.name}", "${screenB.name}"], "expect": "present"}
        {"selector": "{ androidAccessibility: { textRegex: \"^Pay$\" } }", "logs": ["${screenB.name}"], "expect": "unique"}
        """.trimIndent(),
      )
    }

    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "match", "--batch", batch.name) }

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, exit, "stderr=${stderr()}")
    val lines = stdout().trim().lines().map { selectorCliJson.parseToJsonElement(it).jsonObject }
    assertEquals(listOf("true", "false"), lines.map { it["ok"]!!.jsonPrimitive.content })
    assertEquals(2, lines[1]["screens"]!!.jsonArray.single().jsonObject["matches"]!!.jsonPrimitive.int)
  }

  @Test
  fun `match rejects a misspelled field instead of matching without it`() {
    val exit = run("selector", "match", "--selector", """{ androidAccessibility: { textRegexx: "^Pay$" } }""", screenA.path)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stdout=${stdout()}")
    assertTrue("Could not parse --selector" in stderr() && "textRegexx" in stderr(), stderr())
  }

  @Test
  fun `match rejects an empty selector, which would match every node`() {
    val exit = run("selector", "match", "--selector", "{}", screenA.path)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stdout=${stdout()}")
    assertTrue("would match every node" in stderr(), stderr())
  }

  @Test
  fun `suggest --live resolves a ref on the device's screen and names that screen, not a log`() {
    val live = FakeLiveScreen(screenB)

    val exit = run("selector", "suggest", "--live", "--device", "android/emulator-5554", "--ref", "a3", "--json", live = live)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    assertEquals(listOf<String?>("android/emulator-5554"), live.devices)
    val menu = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject
    assertEquals("a3", menu["target"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
    assertEquals("true", menu["live"]!!.jsonPrimitive.content)
    assertEquals(null, menu["log"], "a live screen has no log path: $menu")
  }

  @Test
  fun `match --live checks the device's screen beside the logs it was given`() {
    val live = FakeLiveScreen(screenB)

    val exit = run("selector", "match", "--selector", payText, "--expect", "unique", "--live", screenA.path, live = live)

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, exit, "stderr=${stderr()}")
    assertTrue("${screenA.name}: matches 1  ok" in stdout(), stdout())
    assertTrue("live screen: matches 2  FAIL (expected unique)" in stdout(), stdout())
    assertEquals(listOf<String?>(null), live.devices, "no --device means the only connected device")
  }

  @Test
  fun `a --batch file that asks about the live screen on every line captures it once`() {
    val live = FakeLiveScreen(screenA)
    val batch = File(dir, "live.jsonl").apply {
      writeText(
        """
        {"selector": {"androidAccessibility": {"textRegex": "^Pay$"}}, "live": true, "expect": "unique"}
        {"selector": {"androidAccessibility": {"textRegex": "^Balance$"}}, "live": true, "logs": ["${screenB.name}"]}
        """.trimIndent(),
      )
    }

    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "match", "--batch", batch.name, "--live", live = live) }

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stderr=${stderr()}")
    assertEquals(1, live.devices.size, "the screen was captured ${live.devices.size} times")
    val lines = stdout().trim().lines().map { selectorCliJson.parseToJsonElement(it).jsonObject }
    val secondScreens = lines[1]["screens"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf(null, "true"), secondScreens.map { it["live"]?.jsonPrimitive?.content })
  }

  @Test
  fun `when the live screen can't be read, the command exits with the capture's code and reports nothing`() {
    val noDevice = LiveScreenProvider { throw LiveScreenUnavailable(TrailblazeExitCode.MISUSE.code) }

    val exit = run("selector", "match", "--selector", payText, "--live", screenA.path, live = noDevice)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertEquals("", stdout(), "a partial report would read as a result")
  }

  @Test
  fun `suggest refuses --live together with a log file, before touching the device`() {
    val live = FakeLiveScreen(screenA)

    val exit = run("selector", "suggest", "--live", "--ref", "a2", screenA.path, live = live)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue("Pass one screen" in stderr(), stderr())
    assertEquals(emptyList(), live.devices)
  }

  @Test
  fun `the daemon's answer reads back as the screen, or as the reason there is none`() {
    val screenJson = screenA.readText()
    val captured = decodeLiveScreen(CliMcpClient.ToolResult(screenJson))
    assertTrue(captured is LiveScreenResult.Captured, captured.toString())
    assertEquals(listOf("a1", "a2"), captured.screen.trailblazeNodeTree!!.aggregate().mapNotNull { it.ref })

    val noDevice = decodeLiveScreen(CliMcpClient.ToolResult("""{"executed":false,"error":"No device connected."}"""))
    assertEquals(LiveScreenResult.Failed("No device connected."), noDevice)

    // A daemon that doesn't know the request answers with its usual summary text.
    val oldDaemon = decodeLiveScreen(CliMcpClient.ToolResult("**✅ Done** — Snapshot captured"))
    assertTrue(oldDaemon is LiveScreenResult.Failed && "restart" in oldDaemon.hint.orEmpty(), oldDaemon.toString())

    val refused = decodeLiveScreen(CliMcpClient.ToolResult("Daemon unreachable", isError = true))
    assertEquals(LiveScreenResult.Failed("Daemon unreachable"), refused)

    // A screen with no element tree is a failed capture (exit 2), not bad input (exit 3).
    val treeless = JsonObject(selectorCliJson.parseToJsonElement(screenJson).jsonObject - "trailblazeNodeTree")
    val noTree = decodeLiveScreen(CliMcpClient.ToolResult(treeless.toString()))
    assertTrue(noTree is LiveScreenResult.Failed && "no element tree" in noTree.reason, noTree.toString())
  }

  @Test
  fun `match rejects an empty containsDescendants list, which constrains nothing`() {
    val exit = run("selector", "match", "--selector", "{ containsDescendants: [] }", "--expect", "present", screenA.path)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stdout=${stdout()}")
    assertTrue("would match every node" in stderr(), stderr())
  }

  @Test
  fun `match rejects an app-id placeholder instead of reporting the element absent`() {
    val exit = run(
      "selector", "match", "--selector",
      """{ androidAccessibility: { resourceIdRegex: "{{target.appId}}:id/pay" } }""",
      "--expect", "absent", screenA.path,
    )

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stdout=${stdout()}")
    assertTrue("{{target.appId}}" in stderr(), stderr())
  }

  @Test
  fun `a suggest batch line that can't be evaluated reports its own error and later lines still run`() {
    val batch = File(dir, "bad-lines.jsonl").apply {
      writeText(
        """
        {"log": "${screenA.name}", "ref": "a2", "max": -1}
        {"log": "${screenA.name}", "maestroSelector": "{ textRegex: \"^Pay$\", childOf: { textRegex: \"^Nowhere$\" } }"}
        {"log": "${screenA.name}", "ref": "a2"}
        """.trimIndent(),
      )
    }

    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "suggest", "--batch", batch.name) }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stderr=${stderr()}")
    val lines = stdout().trim().lines().map { selectorCliJson.parseToJsonElement(it).jsonObject }
    assertEquals(listOf(1, 2, 3), lines.map { it["line"]!!.jsonPrimitive.int }, stdout())
    assertTrue("--max must be at least 1" in lines[0]["error"]!!.jsonPrimitive.content, lines[0].toString())
    assertTrue("Maestro selector" in lines[1]["error"]!!.jsonPrimitive.content, lines[1].toString())
    assertEquals("a2", lines[2]["target"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a batch line's null fields count as unset`() {
    val batch = File(dir, "nulls.jsonl").apply {
      writeText("""{"log": "${screenA.name}", "ref": "a2", "at": null, "maestroSelector": null, "step": null, "max": null}""")
    }

    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "suggest", "--batch", batch.name) }

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stdout=${stdout()} stderr=${stderr()}")
    val line = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject
    assertEquals("a2", line["target"]!!.jsonObject["ref"]!!.jsonPrimitive.content, line.toString())
  }

  @Test
  fun `match rejects web cssSelector and nthIndex, which only the browser can evaluate`() {
    for (selector in listOf(
      """{ web: { cssSelector: "[id=\"pay\"]" } }""",
      """{ androidAccessibility: { textRegex: "^Pay$" }, childOf: { web: { ariaRole: button, nthIndex: 1 } } }""",
    )) {
      val exit = run("selector", "match", "--selector", selector, "--expect", "unique", screenA.path)

      assertEquals(TrailblazeExitCode.MISUSE.code, exit, "selector=$selector stdout=${stdout()}")
      assertTrue("cssSelector or nthIndex" in stderr(), stderr())
    }
  }

  @Test
  fun `match rejects a web selector's index and relative constraints, which web replay ignores`() {
    for (selector in listOf(
      """{ web: { ariaRole: button, ariaNameRegex: Pay }, index: 1 }""",
      """{ web: { ariaRole: button, ariaNameRegex: Pay }, childOf: { web: { ariaRole: dialog } } }""",
    )) {
      val exit = run("selector", "match", "--selector", selector, "--expect", "unique", screenA.path)

      assertEquals(TrailblazeExitCode.MISUSE.code, exit, "selector=$selector stdout=${stdout()}")
      assertTrue("Web replay finds the element from the web fields alone" in stderr(), stderr())
    }
  }

  @Test
  fun `match checks a web selector only on the fields web replay locates by`() {
    val combined = run("selector", "match", "--selector", """{ web: { dataTestId: pay, ariaNameRegex: Pay } }""", screenA.path)
    assertEquals(TrailblazeExitCode.MISUSE.code, combined, "stdout=${stdout()}")
    assertTrue("uses only { testid=\"pay\" }" in stderr(), stderr())

    val nameOnly = run("selector", "match", "--selector", """{ web: { ariaNameRegex: Pay } }""", screenA.path)
    assertEquals(TrailblazeExitCode.MISUSE.code, nameOnly, "stdout=${stdout()}")
    assertTrue("can't locate an element" in stderr(), stderr())

    val supported = run("selector", "match", "--selector", """{ web: { ariaRole: button, ariaNameRegex: Pay } }""", screenA.path)
    assertEquals(TrailblazeExitCode.SUCCESS.code, supported, "stderr=${stderr()}")
  }

  @Test
  fun `match --session skips captures with no element tree, like an app-launch driver log`() {
    val snapshot = writeScreen("003_TrailblazeSnapshotLog.json", secondPayButton = false)
    File(dir, "004_AgentDriverLog.json").writeText("""{"viewHierarchy":{}}""")

    val exit = run("selector", "match", "--selector", payText, "--expect", "unique", "--session", dir.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stdout=${stdout()} stderr=${stderr()}")
    assertTrue("${snapshot.name}: matches 1  ok" in stdout(), stdout())
  }

  @Test
  fun `match checks a tap's web role the way replay looks it up, regardless of case`() {
    val web = writeWebScreen()

    val exit = run("selector", "match", "--selector", """{ web: { ariaRole: BUTTON, ariaNameRegex: Pay } }""", "--expect", "unique", web.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stdout=${stdout()} stderr=${stderr()}")
    assertTrue("${web.name}: matches 1  ok" in stdout(), stdout())
  }

  @Test
  fun `match checks a waypoint signal as written, the way a waypoint matches it`() {
    val web = writeWebScreen()
    for (selector in listOf(
      """{ web: { ariaNameRegex: Pay } }""",
      """{ web: { ariaRole: main }, containsChild: { web: { ariaRole: button, ariaNameRegex: Pay } } }""",
    )) {
      val exit = run("selector", "match", "--selector", selector, "--expect", "present", web.path)

      assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "selector=$selector stdout=${stdout()} stderr=${stderr()}")
    }
    // A waypoint compares the role as written, so the capitalised role a tap would find matches nothing.
    val literalRole = run("selector", "match", "--selector", """{ web: { ariaRole: BUTTON, ariaNameRegex: Pay } }""", "--expect", "absent", web.path)
    assertEquals(TrailblazeExitCode.SUCCESS.code, literalRole, "stdout=${stdout()} stderr=${stderr()}")
  }

  @Test
  fun `match --session reports a capture it can't read instead of stepping past it`() {
    writeScreen("003_TrailblazeSnapshotLog.json", secondPayButton = false)
    File(dir, "004_TrailblazeSnapshotLog.json").mkdir()

    val exit = run("selector", "match", "--selector", payText, "--expect", "unique", "--session", dir.path)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stdout=${stdout()} stderr=${stderr()}")
    assertTrue("Could not read" in stderr(), stderr())
  }

  @Test
  fun `match refuses a tap selector web replay can't run as written`() {
    val web = writeWebScreen()
    for ((selector, reason) in listOf(
      """{ containsChild: { web: { ariaRole: button, ariaNameRegex: Pay } } }""" to "is a web capture",
      """{ web: { ariaRole: buton } }""" to "isn't an ARIA role web replay knows",
      """{ web: { ariaRole: button, ariaNameRegex: "Pay [" } }""" to "with the browser's regex engine",
      """{ web: { ariaRole: button, ariaNameRegex: "(?i)p\u00E1y" } }""" to "with the browser's regex engine",
      """{ androidAccessibility: { textRegex: Nope }, web: { ariaRole: button, ariaNameRegex: Pay } }""" to
        "alongside another driver",
      """{ web: { dataTestId: pay } }""" to "finds a test id with a DOM query",
      """{ web: { ariaRole: button, ariaNameRegex: '(P)\1ay' } }""" to "with the browser's regex engine",
    )) {
      val exit = run("selector", "match", "--selector", selector, "--expect", "unique", web.path)

      assertEquals(TrailblazeExitCode.MISUSE.code, exit, "selector=$selector stdout=${stdout()}")
      assertTrue(reason in stderr(), "selector=$selector stderr=${stderr()}")
      assertTrue("--expect present or absent" in stderr(), stderr())
    }
  }

  @Test
  fun `match checks a tap's web name only exactly, the way replay looks it up`() {
    val emoji = "\uD83D\uDE00"
    val web = writeWebScreen(buttonName = "Pay $emoji")

    val pattern = run("selector", "match", "--selector", """{ web: { ariaRole: button, ariaNameRegex: "Pay ." } }""", "--expect", "unique", web.path)
    assertEquals(TrailblazeExitCode.MISUSE.code, pattern, "stdout=${stdout()}")
    assertTrue("with the browser's regex engine" in stderr(), stderr())

    val exactName = run("selector", "match", "--selector", """{ web: { ariaRole: button, ariaNameRegex: "Pay $emoji" } }""", "--expect", "unique", web.path)
    assertEquals(TrailblazeExitCode.SUCCESS.code, exactName, "stdout=${stdout()} stderr=${stderr()}")
  }

  @Test
  fun `match counts a tap's anchored name as the plain name replay looks up`() {
    // Replay reads `^Pay$` as the name "Pay"; the resolver alone would also match it literally.
    val web = writeWebScreen(buttonName = "^Pay$")

    val exit = run("selector", "match", "--selector", """{ web: { ariaRole: button, ariaNameRegex: '^Pay$' } }""", "--expect", "unique", web.path)

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, exit, "stdout=${stdout()} stderr=${stderr()}")
    assertTrue("${web.name}: matches 0" in stdout(), stdout())
  }

  @Test
  fun `match --session reports a cut-off capture instead of stepping past it`() {
    writeScreen("003_TrailblazeSnapshotLog.json", secondPayButton = false)
    for (cutOff in listOf("""{"deviceWidth":1280,""", """{"trailblazeNodeTree":{"nodeId":1,""")) {
      File(dir, "004_TrailblazeSnapshotLog.json").writeText(cutOff)

      val exit = run("selector", "match", "--selector", payText, "--expect", "absent", "--session", dir.path)

      assertEquals(TrailblazeExitCode.MISUSE.code, exit, "log=$cutOff stdout=${stdout()} stderr=${stderr()}")
      assertTrue("empty or cut off" in stderr(), stderr())
    }
  }

  @Test
  fun `match compares a tap's exact web name the way Playwright normalizes it`() {
    // A no-break space and a soft hyphen in the page's name; Playwright reads both names as "Pay now".
    val web = writeWebScreen(buttonName = "Pay\u00A0n\u00ADow ")

    val exit = run("selector", "match", "--selector", """{ web: { ariaRole: button, ariaNameRegex: "Pay now" } }""", "--expect", "unique", web.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stdout=${stdout()} stderr=${stderr()}")
  }

  @Test
  fun `match finds an unnamed web element for a tap's empty name, as replay does`() {
    val web = writeWebScreen(buttonName = null)

    val exit = run("selector", "match", "--selector", """{ web: { ariaRole: button, ariaNameRegex: '^$' } }""", "--expect", "unique", web.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stdout=${stdout()} stderr=${stderr()}")
    assertTrue("${web.name}: matches 1  ok" in stdout(), stdout())
  }

  @Test
  fun `match checks a tap on a text run, counting its nthIndex among runs with that text`() {
    val web = writeWebScreen(
      DriverNodeDetail.Web(ariaRole = "text", ariaName = "Terms apply", nthIndex = 0),
      DriverNodeDetail.Web(ariaRole = "button", ariaName = "Terms apply", nthIndex = 0),
      DriverNodeDetail.Web(ariaRole = "text", ariaName = "Terms apply", nthIndex = 1),
    )

    val exit = run("selector", "match", "--selector", """{ web: { ariaRole: text, ariaNameRegex: "Terms apply" } }""", web.path)
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stdout=${stdout()} stderr=${stderr()}")
    assertTrue("${web.name}: matches 2" in stdout(), stdout())

    val second = run("selector", "match", "--selector", """{ web: { ariaRole: text, ariaNameRegex: "Terms apply", nthIndex: 1 } }""", "--expect", "unique", web.path)
    assertEquals(TrailblazeExitCode.SUCCESS.code, second, "stdout=${stdout()} stderr=${stderr()}")
  }

  @Test
  fun `match refuses a tap on a text run with no name or a heading level, which replay doesn't use`() {
    val web = writeWebScreen(DriverNodeDetail.Web(ariaRole = "text", ariaName = "Terms apply"))
    for ((selector, reason) in listOf(
      """{ web: { ariaRole: text } }""" to "isn't an ARIA role",
      """{ web: { ariaRole: text, ariaNameRegex: "Terms apply", headingLevel: 2 } }""" to "ignores the rest",
    )) {
      val exit = run("selector", "match", "--selector", selector, "--expect", "unique", web.path)

      assertEquals(TrailblazeExitCode.MISUSE.code, exit, "selector=$selector stdout=${stdout()}")
      assertTrue(reason in stderr(), stderr())
    }
  }

  @Test
  fun `match refuses a tap heading level replay can't look up`() {
    val web = writeWebScreen(DriverNodeDetail.Web(ariaRole = "heading", ariaName = "Total", headingLevel = 7))

    val exit = run("selector", "match", "--selector", """{ web: { ariaRole: heading, ariaNameRegex: Total, headingLevel: 7 } }""", "--expect", "unique", web.path)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stdout=${stdout()}")
    assertTrue("headingLevel 7" in stderr(), stderr())
  }

  @Test
  fun `match refuses a tap's empty name where a capture can't tell printed text from a name`() {
    // `<p>Pay</p>` has no accessible name, and replay finds it for an empty name; the capture
    // stores its printed text "Pay" as the name, the same as it would an aria-label.
    val web = writeWebScreen(DriverNodeDetail.Web(ariaRole = "paragraph", ariaName = "Pay"))

    val exit = run("selector", "match", "--selector", """{ web: { ariaRole: paragraph, ariaNameRegex: '^$' } }""", "--expect", "unique", web.path)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stdout=${stdout()}")
    assertTrue("printed text" in stderr(), stderr())
  }

  @Test
  fun `match refuses web tap checks a capture can't count the way replay does`() {
    val web = writeWebScreen(
      DriverNodeDetail.Web(ariaRole = "text", ariaName = "Terms apply"),
      DriverNodeDetail.Web(ariaRole = "generic", ariaName = "Menu"),
      DriverNodeDetail.Web(ariaRole = "paragraph", ariaName = "Pay now"),
    )
    for ((selector, reason) in listOf(
      // Replay matches a text run only for `text` exactly; `TEXT` is looked up as an ARIA role.
      """{ web: { ariaRole: TEXT, ariaNameRegex: "Terms apply" } }""" to "isn't an ARIA role",
      // A snapshot prints most generic elements as their content, so it under-counts them.
      """{ web: { ariaRole: generic, ariaNameRegex: Menu } }""" to "leaves out most elements",
      // Spacing is normalized against a name but not against an unnamed element's printed text.
      """{ web: { ariaRole: paragraph, ariaNameRegex: " Pay now " } }""" to "single spaces",
    )) {
      val exit = run("selector", "match", "--selector", selector, "--expect", "unique", web.path)

      assertEquals(TrailblazeExitCode.MISUSE.code, exit, "selector=$selector stdout=${stdout()}")
      assertTrue(reason in stderr(), "selector=$selector stderr=${stderr()}")
    }
  }

  @Test
  fun `match refuses a tap check on a Maestro capture, whose replay can use Maestro's own matcher`() {
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      driverDetail = DriverNodeDetail.AndroidMaestro(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          bounds = TrailblazeNode.Bounds(0, 0, 540, 100),
          driverDetail = DriverNodeDetail.AndroidMaestro(text = "Pay", className = "android.widget.Button"),
        ),
      ),
    )
    val treeJson = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(TrailblazeNode.serializer(), tree)
    val maestro = File(dir, "020_TrailblazeLlmRequestLog.json").apply {
      writeText("""{"deviceWidth":1080,"deviceHeight":1920,"trailblazeNodeTree":$treeJson}""")
    }
    val selector = """{ androidMaestro: { classNameRegex: android.widget.Button } }"""

    val tap = run("selector", "match", "--selector", selector, "--expect", "unique", maestro.path)
    assertEquals(TrailblazeExitCode.MISUSE.code, tap, "stdout=${stdout()}")
    assertTrue("Maestro driver capture" in stderr(), stderr())

    val waypoint = run("selector", "match", "--selector", selector, "--expect", "present", maestro.path)
    assertEquals(TrailblazeExitCode.SUCCESS.code, waypoint, "stdout=${stdout()} stderr=${stderr()}")
  }

  /** A web capture: a `main` landmark holding one button named [buttonName]. */
  private fun writeWebScreen(buttonName: String? = "Pay"): File =
    writeWebScreen(DriverNodeDetail.Web(ariaRole = "button", ariaName = buttonName))

  /** A web capture: a `main` landmark holding one node per entry of [elements], in order. */
  private fun writeWebScreen(vararg elements: DriverNodeDetail.Web): File {
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1280, 800),
      driverDetail = DriverNodeDetail.Web(ariaRole = "main"),
      children = elements.mapIndexed { i, detail ->
        TrailblazeNode(
          nodeId = 2L + i,
          ref = "e${i + 1}",
          bounds = TrailblazeNode.Bounds(0, 40 * i, 200, 40 * i + 40),
          driverDetail = detail,
        )
      },
    )
    val treeJson = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(TrailblazeNode.serializer(), tree)
    return File(dir, "010_TrailblazeLlmRequestLog.json").apply {
      writeText("""{"deviceWidth":1280,"deviceHeight":800,"trailblazeNodeTree":$treeJson}""")
    }
  }

  @Test
  fun `match --session steps through every screen capture, not only LLM requests`() {
    val snapshot = writeScreen("003_TrailblazeSnapshotLog.json", secondPayButton = false)

    val exit = run("selector", "match", "--selector", payText, "--expect", "unique", "--session", dir.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stdout=${stdout()} stderr=${stderr()}")
    assertTrue("${snapshot.name}: matches 1  ok" in stdout(), stdout())
  }

  @Test
  fun `a batch keeps answering its other lines when the live screen can't be read`() {
    var captures = 0
    val noDevice = LiveScreenProvider {
      captures++
      throw LiveScreenUnavailable(TrailblazeExitCode.MISUSE.code)
    }
    val batch = File(dir, "live-down.jsonl").apply {
      writeText(
        """
        {"selector": {"androidAccessibility": {"textRegex": "^Pay$"}}, "live": true}
        {"selector": {"androidAccessibility": {"textRegex": "^Pay$"}}, "logs": ["${screenA.name}"], "expect": "unique"}
        {"selector": {"androidAccessibility": {"textRegex": "^Balance$"}}, "live": true}
        """.trimIndent(),
      )
    }

    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "match", "--batch", batch.name, "--live", live = noDevice) }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "the capture's exit code, stderr=${stderr()}")
    val lines = stdout().trim().lines().map { selectorCliJson.parseToJsonElement(it).jsonObject }
    assertEquals(listOf(1, 2, 3), lines.map { it["line"]!!.jsonPrimitive.int }, stdout())
    assertEquals(LIVE_SCREEN_UNAVAILABLE, lines[0]["error"]!!.jsonPrimitive.content)
    assertEquals("true", lines[1]["ok"]!!.jsonPrimitive.content, lines[1].toString())
    assertEquals(LIVE_SCREEN_UNAVAILABLE, lines[2]["error"]!!.jsonPrimitive.content)
    assertEquals(1, captures, "a failed capture is not retried line by line")
  }

  @Test
  fun `a batch line reads the device only when the command says --live`() {
    // A daemon from before --live ignores "live" and would answer from the logs alone; it does
    // reject --live, so the flag is what makes such a call fail instead of passing quietly.
    val live = FakeLiveScreen(screenA)
    val matchBatch = File(dir, "match-live.jsonl").apply {
      writeText(
        """
        {"selector": {"androidAccessibility": {"textRegex": "^Pay$"}}, "logs": ["${screenA.name}"], "live": true}
        {"selector": {"androidAccessibility": {"textRegex": "^Pay$"}}, "logs": ["${screenA.name}"]}
        """.trimIndent(),
      )
    }
    val suggestBatch = File(dir, "suggest-live.jsonl").apply { writeText("""{"live": true, "ref": "a2"}""") }

    val matchExit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "match", "--batch", matchBatch.name, live = live) }
    val matchLines = stdout().trim().lines().map { selectorCliJson.parseToJsonElement(it).jsonObject }
    capturedOut.clear()
    val suggestExit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "suggest", "--batch", suggestBatch.name, live = live) }
    val suggestLine = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject

    assertEquals(TrailblazeExitCode.MISUSE.code, matchExit, "stderr=${stderr()}")
    assertTrue("pass --live" in matchLines[0]["error"]!!.jsonPrimitive.content, matchLines[0].toString())
    assertEquals(1, matchLines[1]["screens"]!!.jsonArray.size, matchLines[1].toString())
    assertEquals(TrailblazeExitCode.MISUSE.code, suggestExit, "stderr=${stderr()}")
    assertTrue("pass --live" in suggestLine["error"]!!.jsonPrimitive.content, suggestLine.toString())
    assertEquals(emptyList(), live.devices, "the device was read without --live")
  }

  @Test
  fun `a suggest batch line naming the live screen and a log is refused, not answered from one of them`() {
    val live = FakeLiveScreen(screenA)
    val batch = File(dir, "two-screens.jsonl").apply {
      writeText(
        """
        {"log": "${screenB.name}", "live": true, "ref": "a2"}
        {"log": "${screenB.name}", "ref": "a3"}
        """.trimIndent(),
      )
    }

    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "suggest", "--batch", batch.name, "--live", live = live) }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "stderr=${stderr()}")
    val lines = stdout().trim().lines().map { selectorCliJson.parseToJsonElement(it).jsonObject }
    assertTrue("names one screen" in lines[0]["error"]!!.jsonPrimitive.content, lines[0].toString())
    assertEquals("a3", lines[1]["target"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
    assertEquals(emptyList(), live.devices)
  }

  @Test
  fun `a capture that lost nodes answers nothing from its primary tree`() {
    // The capture also carries a migration tree, captured separately and complete.
    val screen = selectorCliJson.parseToJsonElement(screenA.readText()).jsonObject
    val partial = File(dir, "partial.json").apply {
      writeText(
        JsonObject(
          screen + ("droppedNodeFetches" to JsonPrimitive(2)) +
            ("driverMigrationTreeNode" to screen.getValue("trailblazeNodeTree")),
        ).toString(),
      )
    }
    val live = FakeLiveScreen(partial)
    val refund = """{ androidAccessibility: { textRegex: "^Refund$" } }"""

    // A lost node can hide a match, shift an index or anchor, or change which wrappers the
    // accessibility filter keeps, so even a match found there isn't trusted. A capture problem,
    // not a bad command: capturing again may answer it.
    for ((selector, expect) in listOf(refund to "absent", payText to "unique", payText to "present")) {
      assertEquals(
        TrailblazeExitCode.INFRA_FAILED.code,
        run("selector", "match", "--selector", selector, "--expect", expect, "--live", live = live),
        "--expect $expect",
      )
      assertTrue("lost 2 node fetch(es)" in stderr() && "Capture the screen again" in stderr(), stderr())
    }
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, run("selector", "suggest", "--ref", "a2", "--live", live = live))
    assertTrue("lost 2 node fetch(es)" in stderr(), stderr())
    // The same capture saved as a log is fixed: bad input, and capturing again can't help it.
    for (args in listOf(
      arrayOf("selector", "match", "--selector", payText, "--expect", "present", partial.path),
      arrayOf("selector", "suggest", "--ref", "a2", partial.path),
    )) {
      assertEquals(TrailblazeExitCode.MISUSE.code, run(*args), args.joinToString(" "))
      val refusal = stderr().lines().last { it.isNotBlank() }
      assertTrue("lost 2 node fetch(es)" in refusal && "Capture the screen again" !in refusal, refusal)
    }

    val batch = File(dir, "partial.jsonl").apply {
      writeText(
        listOf(
          buildJsonObject {
            put("selector", payText)
            put("expect", "present")
            put("live", true)
          },
          buildJsonObject {
            put("selector", payText)
            put("expect", "present")
            put("logs", buildJsonArray { add(screenA.name) })
          },
        ).joinToString("\n"),
      )
    }
    assertEquals(
      TrailblazeExitCode.INFRA_FAILED.code,
      CliCallerContext.withCallerCwd(dir.toPath()) { run("selector", "match", "--batch", batch.name, "--live", live = live) },
    )
    val lines = stdout().trim().lines().map { selectorCliJson.parseToJsonElement(it).jsonObject }
    assertTrue("lost 2 node fetch(es)" in lines[0]["error"]!!.jsonPrimitive.content, lines[0].toString())
    assertEquals(null, lines[1]["error"], "a capture problem on one line costs the next line nothing: ${lines[1]}")

    assertEquals(
      TrailblazeExitCode.SUCCESS.code,
      run("selector", "match", "--selector", refund, "--expect", "absent", "--migration-tree", "--live", live = live),
      "the lost nodes were the primary tree's, not the migration tree's; stderr=${stderr()}",
    )
  }

  @Test
  fun `a live capture missing the asked-for tree is a capture failure, a log missing it is bad input`() {
    // A dual-tree capture that kept the migration tree and lost the primary one.
    val screen = selectorCliJson.parseToJsonElement(screenA.readText()).jsonObject
    val lostPrimary = File(dir, "lost-primary.json").apply {
      writeText(JsonObject(screen - "trailblazeNodeTree" + ("driverMigrationTreeNode" to screen.getValue("trailblazeNodeTree"))).toString())
    }
    val live = FakeLiveScreen(lostPrimary)

    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, run("selector", "match", "--selector", payText, "--live", live = live))
    assertTrue("Capture the screen again" in stderr(), stderr())
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, run("selector", "suggest", "--ref", "a2", "--live", live = live))
    assertTrue("Capture the screen again" in stderr(), stderr())
    assertEquals(
      TrailblazeExitCode.INFRA_FAILED.code,
      run("selector", "match", "--selector", payText, "--migration-tree", "--live", live = FakeLiveScreen(screenA)),
    )

    assertEquals(TrailblazeExitCode.MISUSE.code, run("selector", "match", "--selector", payText, lostPrimary.path))
    assertEquals(TrailblazeExitCode.MISUSE.code, run("selector", "suggest", "--ref", "a2", lostPrimary.path))
  }

  @Test
  fun `every selector suggest offers for an --all-only element reaches that element on the device`() {
    // Two unlabeled Views: the target is not important for accessibility, so only an --all
    // capture has it; the device's filtered tree has only the other one.
    fun view(id: Long, ref: String, important: Boolean, top: Int) = TrailblazeNode(
      nodeId = id,
      ref = ref,
      bounds = TrailblazeNode.Bounds(0, top, 1080, top + 100),
      driverDetail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View", isImportantForAccessibility = important),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 2400),
      driverDetail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.FrameLayout"),
      children = listOf(view(2, "t2", important = false, top = 0), view(3, "o3", important = true, top = 200)),
    )
    val treeJson = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(TrailblazeNode.serializer(), root)
    val allElements = File(dir, "all-elements.json").apply {
      writeText("""{"deviceWidth":1080,"deviceHeight":2400,"trailblazeNodeTree":$treeJson}""")
    }

    assertEquals(TrailblazeExitCode.SUCCESS.code, run("selector", "suggest", "--ref", "t2", "--json", allElements.path))
    val menu = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject
    val selectors = menu["candidates"]!!.jsonArray.map { it.jsonObject["selector"]!!.toString() }

    for (selector in selectors) {
      capturedOut.clear()
      run("selector", "match", "--selector", selector, "--json", allElements.path)
      val nodes = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject["screens"]!!.jsonArray
        .single().jsonObject["nodes"]!!.jsonArray.map { it.jsonObject["ref"]?.jsonPrimitive?.content }
      assertTrue("t2" in nodes, "suggested $selector reaches $nodes, not the target")
    }
  }

  @Test
  fun `suggest --max counts only selectors that reach the target on the device`() {
    // An editable EditText only an --all capture has, beside an important EditText. Its class
    // reaches the other field on the device; isEditable falls through to it.
    fun field(id: Long, ref: String, important: Boolean, editable: Boolean, top: Int) = TrailblazeNode(
      nodeId = id,
      ref = ref,
      bounds = TrailblazeNode.Bounds(0, top, 1080, top + 100),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        isImportantForAccessibility = important,
        isEditable = editable,
      ),
    )
    val screen = androidScreen(field(2, "t2", important = false, editable = true, top = 0), field(3, "o3", important = true, editable = false, top = 200))

    val selectors = suggestedSelectors(screen, "t2", "--max", "1")

    assertEquals(1, selectors.size, "stdout=${stdout()}")
    assertEverySelectorReaches(screen, selectors, "t2")
  }

  @Test
  fun `suggest counts sibling indexes the way the device does`() {
    // Three same-class Views: [not important, target, important]. The device's tree drops the
    // first, so the target is index 0 there, not index 1.
    fun view(id: Long, ref: String, important: Boolean, top: Int) = TrailblazeNode(
      nodeId = id,
      ref = ref,
      bounds = TrailblazeNode.Bounds(0, top, 1080, top + 100),
      driverDetail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View", isImportantForAccessibility = important),
    )
    val screen = androidScreen(view(2, "u2", important = false, top = 0), view(3, "t3", important = true, top = 200), view(4, "o4", important = true, top = 400))

    val selectors = suggestedSelectors(screen, "t3")

    val reached = assertEverySelectorReaches(screen, selectors, "t3")
    assertTrue(listOf("t3") in reached.values, "no suggestion picks out only the target: $reached")
  }

  @Test
  fun `suggest ranks selectors from both trees together, stable before indexed`() {
    // The target sits in a wrapper with an id that the device's tree drops, beside a same-class
    // View. The wrapper's id only shows up in the whole tree's selectors; indexes in both.
    fun view(id: Long, ref: String, top: Int, children: List<TrailblazeNode> = emptyList(), resourceId: String? = null, important: Boolean = true) =
      TrailblazeNode(
        nodeId = id,
        ref = ref,
        bounds = TrailblazeNode.Bounds(0, top, 1080, top + 100),
        driverDetail = DriverNodeDetail.AndroidAccessibility(
          className = "android.view.View",
          resourceId = resourceId,
          isImportantForAccessibility = important,
        ),
        children = children,
      )
    val target = view(3, "t3", top = 0)
    val screen = androidScreen(view(2, "w2", top = 0, children = listOf(target), resourceId = "app:id/card", important = false), view(4, "o4", top = 200))

    val menu = suggestedSelectors(screen, "t3")
    val indexed = menu.map { "index" in selectorCliJson.parseToJsonElement(it).jsonObject }
    val top = suggestedSelectors(screen, "t3", "--max", "2")

    assertTrue(menu.any { "app:id/card" in it }, "no selector uses the wrapper's id: $menu")
    assertEquals(indexed.sortedBy { it }, indexed, "an indexed selector ranks above a stable one: $menu")
    assertEquals(menu.take(2), top)
  }

  @Test
  fun `suggest --anchor builds a waypoint selector a waypoint capture can match`() {
    // Tab (selected) > wrapper (also selected, not important, unlabeled) > "Money". A waypoint
    // capture drops the wrapper, so the anchor must name the tab, not the wrapper.
    fun a11y(className: String, important: Boolean = true, selected: Boolean = false, text: String? = null) =
      DriverNodeDetail.AndroidAccessibility(className = className, isImportantForAccessibility = important, isSelected = selected, text = text)
    val bounds = TrailblazeNode.Bounds(0, 2200, 360, 2400)
    val money = TrailblazeNode(nodeId = 4, ref = "m4", bounds = bounds, driverDetail = a11y("android.widget.TextView", text = "Money"))
    val wrapper = TrailblazeNode(nodeId = 3, ref = "w3", bounds = bounds, driverDetail = a11y("android.widget.FrameLayout", important = false, selected = true), children = listOf(money))
    val tab = TrailblazeNode(nodeId = 2, ref = "t2", bounds = bounds, driverDetail = a11y("android.widget.LinearLayout", selected = true), children = listOf(wrapper))
    val screen = androidScreen(tab)

    assertEquals(TrailblazeExitCode.SUCCESS.code, run("selector", "suggest", "--ref", "m4", "--anchor", "parent-selected", "--json", screen.path))
    val anchored = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject["anchored"]!!.toString()
    capturedOut.clear()
    val exit = run("selector", "match", "--expect", "present", "--selector", anchored, screen.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "anchor $anchored: ${stdout()} ${stderr()}")
  }

  private fun androidScreen(vararg children: TrailblazeNode): File {
    val root = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 2400),
      driverDetail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.FrameLayout"),
      children = children.toList(),
    )
    val treeJson = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(TrailblazeNode.serializer(), root)
    return File(dir, "all-elements.json").apply {
      writeText("""{"deviceWidth":1080,"deviceHeight":2400,"trailblazeNodeTree":$treeJson}""")
    }
  }

  private fun suggestedSelectors(screen: File, ref: String, vararg extra: String): List<String> {
    capturedOut.clear()
    assertEquals(TrailblazeExitCode.SUCCESS.code, run("selector", "suggest", "--ref", ref, "--json", *extra, screen.path))
    return selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject["candidates"]!!.jsonArray
      .map { it.jsonObject["selector"]!!.toString() }
  }

  /** Returns the refs each selector reaches on the device. */
  private fun assertEverySelectorReaches(screen: File, selectors: List<String>, ref: String): Map<String, List<String?>> =
    selectors.associateWith { selector ->
      capturedOut.clear()
      run("selector", "match", "--selector", selector, "--json", screen.path)
      val nodes = selectorCliJson.parseToJsonElement(stdout().trim()).jsonObject["screens"]!!.jsonArray
        .single().jsonObject["nodes"]!!.jsonArray.map { it.jsonObject["ref"]?.jsonPrimitive?.content }
      assertTrue(ref in nodes, "suggested $selector reaches $nodes on the device, not $ref")
      nodes
    }

  @Test
  fun `match sees through a non-important wrapper the way the device does`() {
    // Row > (wrapper, not important for accessibility) > "Pay". The device drops the wrapper
    // before matching, so "Pay" is the row's child; an --all capture still has the wrapper.
    fun a11y(className: String, text: String? = null, resourceId: String? = null, important: Boolean = true) =
      DriverNodeDetail.AndroidAccessibility(
        className = className,
        text = text,
        resourceId = resourceId,
        isImportantForAccessibility = important,
      )
    val bounds = TrailblazeNode.Bounds(0, 0, 1080, 200)
    val pay = TrailblazeNode(nodeId = 4, ref = "p4", bounds = bounds, driverDetail = a11y("android.widget.TextView", text = "Pay"))
    val wrapper = TrailblazeNode(nodeId = 3, bounds = bounds, driverDetail = a11y("android.view.View", important = false), children = listOf(pay))
    val row = TrailblazeNode(nodeId = 2, ref = "r2", bounds = bounds, driverDetail = a11y("android.view.View", resourceId = "app:id/row"), children = listOf(wrapper))
    val root = TrailblazeNode(nodeId = 1, bounds = bounds, driverDetail = a11y("android.widget.FrameLayout"), children = listOf(row))
    val treeJson = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(TrailblazeNode.serializer(), root)
    val allElements = File(dir, "all-elements.json").apply {
      writeText("""{"deviceWidth":1080,"deviceHeight":2400,"trailblazeNodeTree":$treeJson}""")
    }

    val exit = run(
      "selector", "match", "--expect", "unique", "--selector",
      """{ androidAccessibility: { resourceIdRegex: "^app:id/row$" }, containsChild: { androidAccessibility: { textRegex: "^Pay$" } } }""",
      allElements.path,
    )

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "stdout=${stdout()} stderr=${stderr()}")
  }

  @Test
  fun `match checks a waypoint signal only where a waypoint capture looks`() {
    // A wrapper with an id, not important for accessibility and unlabeled: a tap still finds it
    // through the device's whole-tree fallback, but a waypoint capture leaves it out.
    fun a11y(className: String, resourceId: String? = null, important: Boolean = true, text: String? = null) =
      DriverNodeDetail.AndroidAccessibility(className = className, resourceId = resourceId, text = text, isImportantForAccessibility = important)
    val bounds = TrailblazeNode.Bounds(0, 0, 1080, 200)
    val label = TrailblazeNode(nodeId = 3, ref = "l3", bounds = bounds, driverDetail = a11y("android.widget.TextView", text = "Total"))
    val wrapper = TrailblazeNode(nodeId = 2, ref = "w2", bounds = bounds, driverDetail = a11y("android.view.View", resourceId = "app:id/card", important = false), children = listOf(label))
    val screen = androidScreen(wrapper)
    val selector = """{ androidAccessibility: { resourceIdRegex: "^app:id/card$" } }"""

    assertEquals(TrailblazeExitCode.SUCCESS.code, run("selector", "match", "--expect", "unique", "--selector", selector, screen.path))
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, run("selector", "match", "--expect", "present", "--selector", selector, screen.path))
    assertEquals(TrailblazeExitCode.SUCCESS.code, run("selector", "match", "--expect", "absent", "--selector", selector, screen.path))
  }

  @Test
  fun `suggest --ref on a capture with no refs at all points at --at instead`() {
    val refless = File(dir, "refless.json").apply {
      writeText(screenA.readText().replace(Regex(""""ref":\s*"a\d","""), ""))
    }
    assertTrue("\"ref\"" !in refless.readText(), refless.readText())

    val exit = run("selector", "suggest", "--ref", "e5", refless.path)

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    // Web selectors can't print as YAML, so the hint names the output that can carry them.
    assertTrue("--at x,y --json" in stderr(), stderr())
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  private fun writeScreen(name: String, secondPayButton: Boolean): File {
    fun node(id: Long, ref: String, text: String, resourceId: String, top: Int, clickable: Boolean) = TrailblazeNode(
      nodeId = id,
      ref = ref,
      bounds = TrailblazeNode.Bounds(left = 0, top = top, right = 540, bottom = top + 100),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.${if (clickable) "Button" else "TextView"}",
        text = text,
        resourceId = resourceId,
        isClickable = clickable,
      ),
    )
    val children = listOfNotNull(
      node(2, "a1", "Balance", "app:id/balance_label", top = 0, clickable = false),
      node(3, "a2", "Pay", "app:id/pay", top = 200, clickable = true),
      node(4, "a3", "Pay", "app:id/pay_again", top = 400, clickable = true).takeIf { secondPayButton },
    )
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 2400),
      driverDetail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.FrameLayout"),
      children = children,
    )
    val treeJson = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(TrailblazeNode.serializer(), tree)
    return File(dir, name).apply {
      writeText("""{"deviceWidth":1080,"deviceHeight":2400,"trailblazeNodeTree":$treeJson}""")
    }
  }

  /** A device whose current screen is [screen]; records the `--device` of every capture. */
  private class FakeLiveScreen(private val screen: File) : LiveScreenProvider {
    val devices = mutableListOf<String?>()

    override fun capture(device: String?): ScreenState {
      devices += device
      return SessionLogScreenState.decode(screen.readText())
    }
  }

  /**
   * Runs the CLI, appending what it printed to [stdout] / [stderr] (see [captureConsole]).
   * [live] stands in for the device `--live` reads; without it, reaching the device fails the test.
   */
  private fun run(vararg args: String, live: LiveScreenProvider? = null): Int {
    val provider = live ?: LiveScreenProvider { error("this test must not read a device") }
    val captured = captureConsole {
      val cli = CommandLine(
        TrailblazeCliCommand(
          appProvider = { error("selector commands must not need the app") },
          configProvider = { error("selector commands must not need the config") },
        ),
      ).setCaseInsensitiveEnumValuesAllowed(true)
      val selector = cli.subcommands.getValue("selector").subcommands
      selector.getValue("suggest").getCommand<SelectorSuggestCommand>().liveScreenProvider = provider
      selector.getValue("match").getCommand<SelectorMatchCommand>().liveScreenProvider = provider
      cli.execute(*args)
    }
    capturedOut.append(captured.out)
    capturedErr.append(captured.err)
    return captured.result
  }

  private fun stdout(): String = capturedOut.toString()
  private fun stderr(): String = capturedErr.toString()
}
