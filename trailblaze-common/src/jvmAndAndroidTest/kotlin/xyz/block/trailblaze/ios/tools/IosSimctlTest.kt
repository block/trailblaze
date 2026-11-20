package xyz.block.trailblaze.ios.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils
import xyz.block.trailblaze.util.SimctlCommand

/** `ios_simctl`'s allowlist and rewrites, and `ios_keychain`'s helper logic, against a fake runner. */
class IosSimctlTest {

  private data class Call(
    val argv: List<String>,
    val isSimctl: Boolean,
    val timeoutSeconds: Long,
    val environment: Map<String, String>,
    val stdin: String? = null,
    val maxOutputBytes: Int? = null,
  ) {
    /** The command after `simctl` (for simctl calls) or the whole host command. */
    override fun toString() = (if (isSimctl) "simctl " else "") + argv.joinToString(" ")
  }

  private class FakeRunner(private val respond: (Call) -> SimctlCommand.Result) : IosSimctlRunner {
    val calls = mutableListOf<Call>()

    override suspend fun simctl(
      args: List<String>,
      timeoutSeconds: Long,
      environment: Map<String, String>,
      stdin: String?,
      maxOutputBytes: Int?,
    ) = Call(args, isSimctl = true, timeoutSeconds, environment, stdin, maxOutputBytes).also(calls::add).let(respond)

    override suspend fun host(command: List<String>, timeoutSeconds: Long) =
      Call(command, isSimctl = false, timeoutSeconds, emptyMap()).also(calls::add).let(respond)
  }

  private val tempDirs = mutableListOf<Path>()
  private val logs = mutableListOf<String>()

  private fun tempDir(): Path = Files.createTempDirectory("ios-simctl-test").also(tempDirs::add)

  @OptIn(ExperimentalPathApi::class)
  @AfterTest
  fun cleanUp() = tempDirs.forEach { it.deleteRecursively() }

  /** A device data directory with one app data container, the shape CoreSimulator lays out. */
  private val deviceData: Path by lazy { tempDir().resolve("data").also { Files.createDirectories(it.resolve(APP_DATA)) } }

  private fun container(): Path = deviceData.resolve(APP_DATA).toRealPath()

  private fun simctl(runner: FakeRunner, cache: Path = tempDir()) =
    IosSimctl(UDID, runner, helperCacheRoot = cache, hostArch = "arm64", deviceDataRoot = deviceData, log = { logs += it })

  private fun run(runner: FakeRunner, vararg argv: String, env: Map<String, String> = emptyMap(), stdin: String? = null) =
    runBlocking { simctl(runner).run(argv.toList(), env, 10, stdin) }

  /** Asserts [argv] is refused before any process runs, and returns the message. */
  private fun refused(vararg argv: String, env: Map<String, String> = emptyMap(), stdin: String? = null): String {
    val runner = FakeRunner { ok() }
    val e = assertFailsWith<IllegalArgumentException> { run(runner, *argv, env = env, stdin = stdin) }
    assertEquals(emptyList(), runner.calls, "nothing may run for a refused argv")
    return e.message!!
  }

  private fun ok(stdout: String = "") = SimctlCommand.Result(0, stdout, "")

  private fun fail(stderr: String) = SimctlCommand.Result(1, "", stderr)

  private fun FakeRunner.commands() = calls.map { it.toString() }

  // --- the device ---

  @Test
  fun `booted, the session UDID and an omitted device all resolve to the session simulator`() {
    val runner = FakeRunner { ok() }
    run(runner, "openurl", "booted", "myapp://a")
    run(runner, "openurl", UDID, "myapp://a")
    run(runner, "openurl", "myapp://a")
    run(runner, "xcrun", "simctl", "keychain", "reset")
    assertEquals(
      listOf("simctl openurl $UDID myapp://a", "simctl openurl $UDID myapp://a", "simctl openurl $UDID myapp://a", "simctl keychain $UDID reset"),
      runner.commands(),
    )
  }

  @Test
  fun `another simulator's UDID is refused`() {
    assertContains(refused("terminate", OTHER_UDID, APP), "only target this session's simulator")
    assertContains(refused("spawn", OTHER_UDID, "notifyutil", "-p", "x"), "only target this session's simulator")
  }

  // --- the allowlist ---

  @Test
  fun `a subcommand outside the allowlist is refused, naming what is allowed`() {
    for (sub in listOf("erase", "delete", "install", "uninstall", "shutdown", "boot", "io", "addmedia", "diagnose", "pbsync", "getenv")) {
      val message = refused(sub, "booted", "x")
      assertContains(message, "`$sub` is not allowed")
      assertContains(message, IosSimctlArgv.ALLOWED)
    }
  }

  @Test
  fun `spawn runs only defaults, notifyutil and log show`() {
    for (program in listOf("sh", "/bin/sh", "launchctl", "cp", "plutil", "open")) {
      assertContains(refused("spawn", "booted", program, "-c", "x"), "spawn runs only defaults, notifyutil and log show")
    }
    assertContains(refused("spawn", "-s", "booted", "defaults", "read", "-g"), "spawn flag -s is not allowed")
    assertContains(refused("spawn", "booted", "log", "stream"), "Only `log show`")
  }

  @Test
  fun `keychain allows only reset`() {
    assertContains(refused("keychain", "booted", "add-root-cert", "/tmp/cert.pem"), "keychain <device> reset")
  }

  @Test
  fun `location takes checked coordinates, scenario names and numbers, never a file or stdin`() {
    val runner = FakeRunner { ok() }
    run(runner, "location", "booted", "list")
    run(runner, "location", "booted", "clear")
    run(runner, "location", "booted", "run", "City Run")
    run(runner, "location", "booted", "start", "--speed=260", "--distance=1000", "37.62,-122.39", "40.62,-73.76")
    assertEquals(
      listOf(
        "simctl location $UDID list",
        "simctl location $UDID clear",
        "simctl location $UDID run City Run",
        "simctl location $UDID start --speed=260 --distance=1000 37.62,-122.39 40.62,-73.76",
      ),
      runner.commands(),
    )
    assertContains(refused("location", "booted", "set", "91,0"), "within -90..90")
    assertContains(refused("location", "booted", "run", "../x;y"), "location scenario")
    assertContains(refused("location", "booted", "start", "-"), "reading them from stdin or a file is not allowed")
    assertContains(refused("location", "booted", "start", "37,-122"), "2..100 waypoints")
    assertContains(refused("location", "booted", "start", "--speed=fast", "37,-122", "38,-122"), "positive number")
    assertContains(refused("location", "booted", "start", "--file=/tmp/x", "37,-122", "38,-122"), "must be one of")
    assertContains(refused("location", "booted", "teleport"), "Usage: location")
  }

  @Test
  fun `ui takes only its three options and their enum values`() {
    val runner = FakeRunner { ok("dark\n") }
    assertEquals("dark\n", run(runner, "ui", "booted", "appearance").stdout)
    run(runner, "ui", "booted", "appearance", "light")
    run(runner, "ui", "booted", "content_size", "accessibility-large")
    run(runner, "ui", "booted", "increase_contrast", "enabled")
    assertEquals(
      listOf(
        "simctl ui $UDID appearance",
        "simctl ui $UDID appearance light",
        "simctl ui $UDID content_size accessibility-large",
        "simctl ui $UDID increase_contrast enabled",
      ),
      runner.commands(),
    )
    assertContains(refused("ui", "booted", "appearance", "sepia"), "takes one of light, dark")
    assertContains(refused("ui", "booted", "content_size", "huge"), "takes one of")
    assertContains(refused("ui", "booted", "reduce_motion", "on"), "Usage: ui")
  }

  @Test
  fun `status_bar overrides take a fixed flag set with checked values`() {
    val runner = FakeRunner { ok() }
    run(runner, "status_bar", "booted", "override", "--time", "9:41", "--batteryLevel", "100", "--dataNetwork", "5g", "--operatorName", "Carrier")
    run(runner, "status_bar", "booted", "clear")
    assertEquals(
      listOf(
        "simctl status_bar $UDID override --time 9:41 --batteryLevel 100 --dataNetwork 5g --operatorName Carrier",
        "simctl status_bar $UDID clear",
      ),
      runner.commands(),
    )
    assertContains(refused("status_bar", "booted", "override"), "one or more")
    assertContains(refused("status_bar", "booted", "override", "--batteryLevel", "101"), "not allowed")
    assertContains(refused("status_bar", "booted", "override", "--wifiBars", "x"), "not allowed")
    assertContains(refused("status_bar", "booted", "override", "--time", "\$(id)"), "not allowed")
    assertContains(refused("status_bar", "booted", "override", "--foo", "1"), "status_bar flag --foo is not allowed")
    assertContains(refused("status_bar", "booted", "override", "--wifiBars", "1", "--wifiBars", "2"), "twice")
  }

  @Test
  fun `push sends a checked JSON payload through stdin, never a file`() {
    val runner = FakeRunner { ok() }
    val payload = """{"aps":{"alert":"Hi"}}"""
    run(runner, "push", "booted", APP, stdin = payload)
    run(runner, "push", "booted", APP, "-", stdin = payload)
    assertEquals(listOf("simctl push $UDID $APP -", "simctl push $UDID $APP -"), runner.commands())
    assertEquals(listOf(payload, payload), runner.calls.map { it.stdin })
    assertContains(refused("push", "booted", APP, "/tmp/payload.json", stdin = payload), "A payload file is not allowed")
    assertContains(refused("push", "booted", APP), "needs its JSON payload in stdin")
    assertContains(refused("push", "booted", APP, stdin = "not json"), "not valid JSON")
    assertContains(refused("push", "booted", APP, stdin = "[1]"), "\"aps\" key")
    assertContains(refused("push", "booted", APP, stdin = """{"aps":"${"x".repeat(4_100)}"}"""), "at most 4096 bytes")
  }

  @Test
  fun `pbcopy reads stdin, pbpaste, listapps and appinfo take only the device and an app`() {
    val runner = FakeRunner { ok() }
    run(runner, "pbcopy", "booted", stdin = "copied")
    run(runner, "pbpaste", "booted")
    run(runner, "listapps", "booted")
    run(runner, "appinfo", "booted", APP)
    assertEquals(
      listOf("simctl pbcopy $UDID", "simctl pbpaste $UDID", "simctl listapps $UDID", "simctl appinfo $UDID $APP"),
      runner.commands(),
    )
    assertEquals("copied", runner.calls.first().stdin)
    assertTrue(logs.none { "copied" in it }, "stdin is never logged")
    assertContains(refused("pbcopy", "booted"), "needs the text in stdin")
    assertContains(refused("pbcopy", "-v", "booted", stdin = "x"), "Usage: pbcopy")
    assertContains(refused("appinfo", "booted", "--all"), "not a bundle id")
    assertContains(refused("openurl", "booted", "myapp://a", stdin = "x"), "stdin is only for push and pbcopy")
  }

  /** Holds the host pasteboard lock on another thread, as `inputText` does between staging and paste. */
  private fun holdPasteboardLock(): Pair<CountDownLatch, Thread> {
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val holder = thread { IosHostSimctlUtils.withPasteboardLock { held.countDown(); release.await() } }
    assertTrue(held.await(30, TimeUnit.SECONDS))
    return release to holder
  }

  @Test
  fun `pbcopy and pbpaste wait for another clipboard operation to finish`() {
    val runner = FakeRunner { ok() }
    val (release, holder) = holdPasteboardLock()
    try {
      val copy = thread { run(runner, "pbcopy", "booted", stdin = "copied") }
      val paste = thread { run(runner, "pbpaste", "booted") }
      copy.join(300)
      assertEquals(emptyList(), runner.calls, "nothing may touch the pasteboard while another operation holds it")
      release.countDown()
      copy.join(30_000)
      paste.join(30_000)
      assertEquals(setOf("simctl pbcopy $UDID", "simctl pbpaste $UDID"), runner.commands().toSet())
    } finally {
      release.countDown()
      holder.join()
    }
  }

  @Test
  fun `cancelling pbcopy stops its wait for a busy clipboard`() {
    val runner = FakeRunner { ok() }
    val (release, holder) = holdPasteboardLock()
    try {
      runBlocking {
        val copy = launch(Dispatchers.Default) { simctl(runner).run(listOf("pbcopy", "booted"), emptyMap(), 300, stdin = "copied") }
        delay(300)
        assertTrue(copy.isActive, "pbcopy should still be waiting for the lock")
        withTimeout(5_000) { copy.cancelAndJoin() }
      }
      assertEquals(emptyList(), runner.calls)
    } finally {
      release.countDown()
      holder.join()
    }
  }

  @Test
  fun `pbcopy times out without running when the clipboard stays busy`() {
    val runner = FakeRunner { ok() }
    val (release, holder) = holdPasteboardLock()
    try {
      val result = runBlocking { simctl(runner).run(listOf("pbcopy", "booted"), emptyMap(), 1, stdin = "copied") }
      assertTrue(result.timedOut)
      assertContains(result.stderr, "another clipboard operation")
      assertEquals(emptyList(), runner.calls)
    } finally {
      release.countDown()
      holder.join()
    }
  }

  @Test
  fun `allowed subcommands pass through with the device resolved`() {
    val runner = FakeRunner { ok("out\n") }
    assertEquals("out\n", run(runner, "location", "booted", "set", "37.77,-122.41").stdout)
    run(runner, "privacy", "booted", "grant", "photos", APP)
    run(runner, "privacy", "booted", "reset", "all")
    run(runner, "get_app_container", "booted", APP, "data")
    run(runner, "spawn", "booted", "notifyutil", "-s", "com.apple.x", "1")
    run(runner, "spawn", "booted", "notifyutil", "-p", "com.apple.x")
    assertEquals(
      listOf(
        "simctl location $UDID set 37.77,-122.41",
        "simctl privacy $UDID grant photos $APP",
        "simctl privacy $UDID reset all",
        "simctl get_app_container $UDID $APP data",
        "simctl spawn $UDID notifyutil -s com.apple.x 1",
        "simctl spawn $UDID notifyutil -p com.apple.x",
      ),
      runner.commands(),
    )
  }

  @Test
  fun `privacy, get_app_container, openurl and notifyutil arguments are checked`() {
    assertContains(refused("privacy", "booted", "grant", "photos"), "needs a bundle id")
    assertContains(refused("privacy", "booted", "allow", "photos", APP), "grant|revoke|reset")
    assertContains(refused("get_app_container", "booted", APP, "/tmp"), "must be app, data, groups")
    assertContains(refused("openurl", "booted", "not a url"), "must start with a scheme")
    assertContains(refused("openurl", "booted", "file:///etc/passwd"), "file: URLs")
    assertContains(refused("spawn", "booted", "notifyutil", "-w", "x"), "notifyutil -p")
    assertContains(refused("spawn", "booted", "notifyutil", "-p", "bad name;"), "Notification name")
  }

  @Test
  fun `an app id that could be read as a simctl option is refused`() {
    assertContains(refused("terminate", "booted", "--all"), "not a bundle id")
  }

  // --- terminate ---

  @Test
  fun `terminate kills the app and waits until launchd no longer lists it`() {
    var listed = 2
    val runner = FakeRunner { call ->
      if (call.argv.contains("launchctl")) ok(if (listed-- > 0) "123\t0\tUIKitApplication:$APP[abcd]\n" else "") else ok()
    }
    assertEquals(IosSimctlResult(0, "Terminated $APP.", "", false), run(runner, "terminate", "booted", APP))
    assertEquals("simctl terminate $UDID $APP", runner.commands().first())
    assertEquals(4, runner.calls.size)
  }

  @Test
  fun `terminate of an app that was not running is not an error`() {
    val runner = FakeRunner { fail("found nothing to terminate") }
    assertEquals(IosSimctlResult(0, "$APP was not running.", "", false), run(runner, "terminate", APP))
  }

  @Test
  fun `terminate returns any other simctl error as its exit code`() {
    val runner = FakeRunner { fail("Invalid device") }
    assertEquals(IosSimctlResult(1, "", "Invalid device", false), run(runner, "terminate", APP))
  }

  @Test
  fun `a timed out command returns timedOut and exit -1, never a success`() {
    val runner = FakeRunner { SimctlCommand.Result(-1, "", "simctl timed out after 10s", timedOut = true) }
    assertEquals(IosSimctlResult(-1, "", "simctl timed out after 10s", true), run(runner, "openurl", "myapp://a"))
  }

  // --- launch ---

  @Test
  fun `launch passes app arguments as argv with no environment`() {
    val runner = FakeRunner { ok("$APP: 4242\n") }
    val out = run(runner, "launch", "--terminate-running-process", "booted", APP, "-flag", "a b; rm -rf /")
    assertEquals("$APP: 4242\n", out.stdout)
    val call = runner.calls.single()
    assertEquals(listOf("launch", "--terminate-running-process", UDID, APP, "-flag", "a b; rm -rf /"), call.argv)
    assertEquals(emptyMap(), call.environment)
  }

  @Test
  fun `launch refuses stdout, stderr and console flags`() {
    assertContains(refused("launch", "--stdout=/Users/me/.zshrc", "booted", APP), "writes to a host path")
    assertContains(refused("launch", "--stderr=/tmp/x", "booted", APP), "writes to a host path")
    assertContains(refused("launch", "--console", "booted", APP), "launch --console is refused")
    assertContains(refused("launch", "--console-pty", "booted", APP), "launch --console-pty is refused")
    assertContains(refused("launch", "-w", "booted", APP), "launch flag -w is not allowed")
    for (arg in listOf("--stdout=/Users/me/.zshrc", "--stderr=/tmp/x", "--console", "--console-pty", "--wait-for-debugger", "-w")) {
      assertContains(refused("launch", "booted", APP, "-ok", arg), "launch app argument $arg is refused")
    }
  }

  @Test
  fun `any launch environment is refused, even an ordinary-looking variable, until an allowlist exists`() {
    for (name in listOf("MY_FLAG", "DYLD_INSERT_LIBRARIES", "__XPC_DYLD_INSERT_LIBRARIES", "MallocStackLogging")) {
      val message = refused("launch", "booted", APP, env = mapOf(name to "x"))
      assertContains(message, "launchEnvironment is not supported yet")
      assertContains(message, "allowlist")
    }
    assertContains(refused("openurl", "booted", "myapp://a", env = mapOf("FLAG" to "1")), "not supported yet")
  }

  // --- log show ---

  @Test
  fun `log show needs a capped predicate and window, and returns the whole capture`() {
    val many = (1..6_000).joinToString("\n") { "line $it" }
    val runner = FakeRunner { ok("$many\n") }
    val out = run(runner, "spawn", "booted", "log", "show", "--last", "60s", "--predicate", "process == \"App\"", "--style", "compact", "--debug").stdout
    assertEquals(6_000, out.trimEnd().lines().size)
    assertEquals("line 1", out.lines().first())
    assertEquals(
      listOf("spawn", UDID, "log", "show", "--last", "60s", "--predicate", "process == \"App\"", "--style", "compact", "--debug"),
      runner.calls.single().argv,
    )
    assertEquals(IosSimctlArgv.MAX_LOG_BYTES, runner.calls.single().maxOutputBytes)
  }

  @Test
  fun `log show past the output cap fails, telling the caller to narrow it`() {
    val runner = FakeRunner { SimctlCommand.Result(-1, "partial", "stopped", outputCapped = true) }
    val e = assertFailsWith<IllegalStateException> { run(runner, "spawn", "booted", "log", "show", "--predicate", "x", "--last", "60s") }
    assertContains(e.message!!, "narrow --predicate or --last")
  }

  @Test
  fun `log show refuses predicates that call methods, build classes or walk objects`() {
    for (predicate in listOf(
      "FUNCTION(process, 'description') == 'x'",
      "CAST('x', 'NSString') == 'x'",
      "SUBQUERY(x, ${'$'}y, ${'$'}y == 1).@count > 0",
      "self == 'x'",
      "process == ${'$'}x",
      "process.@count > 0",
      "process \uFF20 'x'",
    )) {
      assertContains(refused("spawn", "booted", "log", "show", "--predicate", predicate, "--last", "60s"), "must not use")
    }
    // Words that only contain the keywords are fine.
    val runner = FakeRunner { ok() }
    run(runner, "spawn", "booted", "log", "show", "--predicate", "subsystem == \"selfie.functions\"", "--last", "5s")
  }

  @Test
  fun `log show refuses a missing or oversized predicate or window, and unknown flags`() {
    assertContains(refused("spawn", "booted", "log", "show", "--last", "60s"), "needs --predicate")
    assertContains(refused("spawn", "booted", "log", "show", "--predicate", "x".repeat(2_001), "--last", "60s"), "1..2000 characters")
    assertContains(refused("spawn", "booted", "log", "show", "--predicate", "x"), "needs --last")
    assertContains(refused("spawn", "booted", "log", "show", "--predicate", "x", "--last", "2h"), "1s..600s")
    assertContains(refused("spawn", "booted", "log", "show", "--predicate", "x", "--last", "11m"), "1s..600s")
    assertContains(refused("spawn", "booted", "log", "show", "--predicate", "x", "--last", "1d"), "s, m or h")
    assertContains(refused("spawn", "booted", "log", "show", "--predicate", "x", "--last", "60s", "--start", "2020-01-01"), "--start is not allowed")
    assertContains(refused("spawn", "booted", "log", "show", "--predicate", "x", "--last", "60s", "/tmp/a.logarchive"), "is not allowed")
  }

  // --- defaults ---

  private fun containerRunner(rest: (Call) -> SimctlCommand.Result = { ok() }) = FakeRunner { call ->
    if (call.argv.first() == "get_app_container") ok("${container()}\n") else rest(call)
  }

  @Test
  fun `a bundle-id domain is rewritten to the app's container plist`() {
    val runner = containerRunner()
    run(runner, "spawn", "booted", "defaults", "write", APP, "Flag", "-bool", "YES")
    run(runner, "spawn", "booted", "defaults", "read", APP, "Flag")
    assertEquals(
      listOf(
        "simctl get_app_container $UDID $APP data",
        "simctl spawn $UDID defaults write ${container()}/Library/Preferences/$APP Flag -bool YES",
        "simctl get_app_container $UDID $APP data",
        "simctl spawn $UDID defaults read ${container()}/Library/Preferences/$APP Flag",
      ),
      runner.commands(),
    )
  }

  @Test
  fun `a bundle-id domain whose Preferences folder links outside the containers is refused`() {
    Files.createDirectories(container().resolve("Library"))
    Files.createSymbolicLink(container().resolve("Library/Preferences"), tempDir())
    val runner = containerRunner()
    val e = assertFailsWith<IllegalArgumentException> { run(runner, "spawn", "booted", "defaults", "write", APP, "K", "v") }
    assertContains(e.message!!, "outside this simulator's app containers")
    assertEquals(listOf("simctl get_app_container $UDID $APP data"), runner.commands(), "defaults must not run")
  }

  @Test
  fun `the global domain goes straight to the simulator's defaults`() {
    val runner = FakeRunner { ok("(\n    en\n)\n") }
    assertEquals("(\n    en\n)\n", run(runner, "spawn", "booted", "defaults", "read", "-g", "AppleLanguages").stdout)
    run(runner, "spawn", "booted", "defaults", "write", "NSGlobalDomain", "K", "-int", "7")
    assertEquals(
      listOf("simctl spawn $UDID defaults read -g AppleLanguages", "simctl spawn $UDID defaults write -g K -int 7"),
      runner.commands(),
    )
  }

  @Test
  fun `a path domain inside this simulator's app containers is allowed`() {
    val runner = FakeRunner { ok() }
    val plist = "${container()}/Library/Preferences/$APP"
    run(runner, "spawn", "booted", "defaults", "write", plist, "K", "v")
    assertEquals("simctl spawn $UDID defaults write $plist K v", runner.commands().single())
  }

  @Test
  fun `a path domain outside this simulator's app containers is refused`() {
    val outside = tempDir().resolve("evil").toString()
    assertContains(refused("spawn", "booted", "defaults", "write", outside, "K", "v"), "outside this simulator's app containers")
    assertContains(refused("spawn", "booted", "defaults", "write", "/Users/me/Library/LaunchAgents/x", "K", "v"), "outside")
    // The device data directory itself, and a sibling device, are not app containers.
    assertContains(refused("spawn", "booted", "defaults", "write", "$deviceData/Library/Preferences/x", "K", "v"), "outside")
  }

  @Test
  fun `a path domain that climbs out with dot-dot is refused`() {
    val climbing = "${container()}/../../../../../../evil"
    assertContains(refused("spawn", "booted", "defaults", "write", climbing, "K", "v"), "outside")
  }

  @Test
  fun `a path domain through a symlink that leaves the containers is refused`() {
    val outside = tempDir()
    Files.createSymbolicLink(container().resolve("link"), outside)
    assertContains(refused("spawn", "booted", "defaults", "write", "${container()}/link/prefs", "K", "v"), "outside")
    // `link/..` is the parent of the link's target, outside, not the container.
    assertContains(refused("spawn", "booted", "defaults", "write", "${container()}/link/../x", "K", "v"), "outside")
  }

  @Test
  fun `a path domain whose plist is a dangling symlink is refused`() {
    Files.createSymbolicLink(container().resolve("prefs.plist"), tempDir().resolve("not-yet"))
    assertContains(refused("spawn", "booted", "defaults", "write", "${container()}/prefs", "K", "v"), "dangling symlink")
  }

  @Test
  fun `defaults allows only read, read-type, write and delete, with a domain`() {
    assertContains(refused("spawn", "booted", "defaults", "export", APP, "/tmp/out.plist"), "defaults runs only")
    assertContains(refused("spawn", "booted", "defaults", "import", APP, "/tmp/in.plist"), "defaults runs only")
    assertContains(refused("spawn", "booted", "defaults", "read"), "needs a domain")
    assertContains(refused("spawn", "booted", "defaults", "read", "-currentHost", "x"), "must be -g, a bundle id")
    assertContains(refused("spawn", "booted", "defaults", "write", APP, "K"), "Usage: defaults write")
    assertContains(refused("spawn", "booted", "defaults", "delete", APP, "-x"), "must not start with '-'")
  }

  @Test
  fun `defaults delete needs a key, so it never wipes a whole domain`() {
    assertContains(refused("spawn", "booted", "defaults", "delete", "-g"), "needs a key")
    assertContains(refused("spawn", "booted", "defaults", "delete", APP), "needs a key")
  }

  @Test
  fun `the scheme approval domain is passed by name with only its own key and a bundle id`() {
    val runner = FakeRunner { ok() }
    val key = "com.apple.CoreSimulator.CoreSimulatorBridge-->square-register"
    run(runner, "spawn", "booted", "defaults", "write", "com.apple.launchservices.schemeapproval", key, "-string", APP)
    run(runner, "spawn", "booted", "defaults", "read", "com.apple.launchservices.schemeapproval", key)
    assertEquals(
      listOf(
        "simctl spawn $UDID defaults write com.apple.launchservices.schemeapproval $key -string $APP",
        "simctl spawn $UDID defaults read com.apple.launchservices.schemeapproval $key",
      ),
      runner.commands(),
    )
    val domain = "com.apple.launchservices.schemeapproval"
    assertContains(refused("spawn", "booted", "defaults", "write", domain, "OtherKey", "-string", APP), "allows only")
    assertContains(refused("spawn", "booted", "defaults", "write", domain, "$key;x", "-string", APP), "allows only")
    assertContains(refused("spawn", "booted", "defaults", "write", domain, key, "-bool", "YES"), "allows only")
    assertContains(refused("spawn", "booted", "defaults", "write", domain, key, "-string", "not an app"), "allows only")
    assertContains(refused("spawn", "booted", "defaults", "delete", domain, key), "allows only")
  }

  @Test
  fun `a defaults failure is its exit code and defaults' own message`() {
    val runner = containerRunner { fail("The domain/default pair of (x, Missing) does not exist") }
    val result = run(runner, "spawn", "booted", "defaults", "read", APP, "Missing")
    assertEquals(1, result.exitCode)
    assertContains(result.stderr, "does not exist")
  }

  @Test
  fun `without a given data directory, the simulator's own HOME is used, so a custom device set works`() {
    val runner = FakeRunner { call ->
      when {
        call.argv.first() == "getenv" -> ok("$deviceData\n")
        else -> ok()
      }
    }
    val simctl = IosSimctl(UDID, runner, helperCacheRoot = tempDir(), hostArch = "arm64", log = { logs += it })
    val plist = "${container()}/Library/Preferences/$APP"
    runBlocking { simctl.run(listOf("spawn", "booted", "defaults", "write", plist, "K", "v"), emptyMap(), 10) }
    runBlocking { simctl.run(listOf("spawn", "booted", "defaults", "write", plist, "K", "v"), emptyMap(), 10) }
    assertEquals(
      listOf("simctl getenv $UDID HOME", "simctl spawn $UDID defaults write $plist K v", "simctl spawn $UDID defaults write $plist K v"),
      runner.commands(),
    )
  }

  // --- ios_keychain ---

  /**
   * A host with one installed simulator build of [APP] whose entitlements give [GROUP]. Builds a
   * fake helper file where swiftc's `-o` points, and records the entitlements swiftc was handed.
   */
  private inner class KeychainHost(
    val segeditWorks: Boolean = true,
    /** What the helper prints: on stdout when it is JSON (exit 0), otherwise on stderr (exit 1). */
    val helperOutput: String = ITEM_JSON,
    /** stdout on a failure, to check it is never quoted. */
    val failureStdout: String = "",
  ) {
    var builtEntitlements: String? = null
    val runner = FakeRunner { call -> respond(call) }

    private fun respond(call: Call): SimctlCommand.Result {
      val argv = call.argv
      return when {
        call.isSimctl && argv.first() == "get_app_container" -> ok("/sim/App.app\n")
        call.isSimctl && argv.first() == "spawn" ->
          if (helperOutput.startsWith("{")) ok("$helperOutput\n") else SimctlCommand.Result(1, failureStdout, helperOutput)
        argv == listOf("xcrun", "--sdk", "iphonesimulator", "--show-sdk-version") -> ok("26.5\n")
        argv.first() == "/usr/libexec/PlistBuddy" -> ok("App\n")
        argv.take(2) == listOf("xcrun", "segedit") ->
          if (segeditWorks) ok().also { Path.of(argv.last()).writeText(ENTITLEMENTS) } else fail("no section")
        argv.take(2) == listOf("codesign", "-d") -> ok(ENTITLEMENTS)
        argv.first() == "plutil" -> ok("$GROUP\n")
        argv.contains("swiftc") -> {
          builtEntitlements = Path.of(argv.last()).readText()
          val out = Path.of(argv[argv.indexOf("-o") + 1])
          out.writeText("binary")
          out.toFile().setExecutable(true)
          ok()
        }
        argv.take(2) == listOf("codesign", "-f") -> ok()
        else -> error("unexpected command $call")
      }
    }
  }

  private suspend fun IosSimctl.set(group: String? = null) = keychain(
    IosKeychainAction.SET, APP, "svc", "acct", PASSWORD, IosKeychainAccessibility.WHEN_UNLOCKED, group, 10,
  )

  @Test
  fun `the keychain value reaches the helper only through the environment`() = runBlocking {
    val cache = tempDir()
    val host = KeychainHost()
    val outcome = simctl(host.runner, cache).keychain(
      IosKeychainAction.SET, APP, "svc", "SessionToken", PASSWORD, IosKeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, null, 10,
    )
    assertEquals(listOf(IosKeychainItem("svc", "acct", GROUP, "genericPassword")), outcome.items)
    assertTrue(outcome.found)
    val spawn = host.runner.calls.last()
    assertEquals(
      listOf("spawn", UDID, helperIn(cache).toString(), "set", GROUP, "svc", "SessionToken", "AfterFirstUnlockThisDeviceOnly"),
      spawn.argv,
    )
    assertEquals(mapOf("SIMCTL_CHILD_TRAILBLAZE_KEYCHAIN_VALUE" to PASSWORD), spawn.environment)
    assertTrue(host.runner.calls.none { call -> call.argv.any { PASSWORD in it } })
    assertTrue(logs.none { PASSWORD in it })
  }

  @Test
  fun `the helper carries the app's group in its entitlements section and a plain ad-hoc signature`() = runBlocking {
    val host = KeychainHost()
    simctl(host.runner).set()
    val swiftc = host.runner.calls.single { "swiftc" in it.argv }.argv
    assertEquals(
      listOf("-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__entitlements", "-Xlinker"),
      swiftc.subList(swiftc.size - 8, swiftc.size - 1),
    )
    assertContains(swiftc, "arm64-apple-ios15.0-simulator")
    assertContains(host.builtEntitlements!!, "<key>keychain-access-groups</key><array><string>$GROUP</string></array>")
    assertContains(host.builtEntitlements!!, "<key>application-identifier</key><string>$GROUP</string>")
    val sign = host.runner.calls.single { it.argv.take(2) == listOf("codesign", "-f") }.argv
    assertEquals(listOf("codesign", "-f", "-s", "-"), sign.take(4))
    assertTrue(host.runner.calls.none { "--entitlements" in it.argv && "-f" in it.argv })
  }

  @Test
  fun `the helper is built once per group and reused`() = runBlocking {
    val cache = tempDir()
    val host = KeychainHost()
    repeat(2) {
      simctl(host.runner, cache).set()
    }
    assertEquals(1, host.runner.calls.count { "swiftc" in it.argv })
    assertEquals(2, host.runner.calls.count { it.isSimctl && it.argv.first() == "spawn" })
  }

  private fun helperIn(cache: Path) =
    cache.resolve("v${IosSimctl.KEYCHAIN_HELPER_VERSION}/26.5/arm64/$GROUP/${IosSimctl.KEYCHAIN_HELPER_NAME}")

  @Test
  fun `the helper cache lives in the user's own Caches, not a temp directory`() {
    assertEquals(Path.of("/Users/me/Library/Caches/trailblaze/ios-keychain-helper"), IosSimctl.defaultHelperCacheRoot("/Users/me"))
  }

  @Test
  fun `a cached helper that others can write is rebuilt, and the new one is owner-only`() = runBlocking {
    val cache = tempDir()
    val helper = helperIn(cache)
    Files.createDirectories(helper.parent)
    helper.writeText("planted")
    Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwxrwxrwx"))
    val host = KeychainHost()
    simctl(host.runner, cache).set()
    assertEquals(1, host.runner.calls.count { "swiftc" in it.argv })
    assertEquals("binary", helper.readText())
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(helper)))
  }

  @Test
  fun `a symlinked helper is rebuilt rather than followed`() = runBlocking {
    val cache = tempDir()
    val helper = helperIn(cache)
    Files.createDirectories(helper.parent)
    val elsewhere = tempDir().resolve("other-helper")
    elsewhere.writeText("planted")
    elsewhere.toFile().setExecutable(true)
    Files.createSymbolicLink(helper, elsewhere)
    val host = KeychainHost()
    simctl(host.runner, cache).set()
    assertEquals(1, host.runner.calls.count { "swiftc" in it.argv })
    assertFalse(Files.isSymbolicLink(helper))
    assertEquals("planted", elsewhere.readText())
  }

  @Test
  fun `the cache root is tightened to owner-only, and a symlinked root is refused`() = runBlocking {
    val cache = tempDir().resolve("open")
    Files.createDirectories(cache)
    Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwxrwxrwx"))
    simctl(KeychainHost().runner, cache).set()
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cache)))

    val linked = tempDir().resolve("linked")
    Files.createSymbolicLink(linked, tempDir())
    val host = KeychainHost()
    val e = assertFailsWith<IllegalStateException> {
      simctl(host.runner, linked).set()
    }
    assertContains(e.message!!, "not a symlink")
    assertTrue(host.runner.calls.none { it.isSimctl && it.argv.first() == "spawn" }, "the password must not reach any helper")
  }

  @Test
  fun `a signed build's entitlements are read from its signature when it has no section`() = runBlocking {
    val host = KeychainHost(segeditWorks = false)
    simctl(host.runner).set()
    assertTrue(host.runner.calls.any { it.argv == listOf("codesign", "-d", "--entitlements", ":-", "/sim/App.app") })
  }

  @Test
  fun `an explicit access group skips the entitlement lookup`() = runBlocking {
    val host = KeychainHost()
    simctl(host.runner).set(group = "TEAM.shared")
    assertTrue(host.runner.calls.none { it.argv.first() == "plutil" || it.argv.first() == "get_app_container" })
    assertEquals("TEAM.shared", host.runner.calls.last().argv[4])
  }

  @Test
  fun `a helper failure never quotes the value, and never quotes stdout at all`() {
    val host = KeychainHost(helperOutput = "read back -25300 for $PASSWORD", failureStdout = """{"value":"$GOT_SECRET"}""")
    val e = assertFailsWith<IllegalStateException> {
      runBlocking {
        simctl(host.runner).set()
      }
    }
    assertFalse(PASSWORD in e.message!!)
    assertFalse(GOT_SECRET in e.message!!)
    assertContains(e.message!!, "-25300")
  }

  @Test
  fun `unreadable helper output is reported without quoting it`() {
    val host = KeychainHost(helperOutput = """{"found":true,"value":"$GOT_SECRET",""")
    val e = assertFailsWith<IllegalStateException> {
      runBlocking { simctl(host.runner).keychain(IosKeychainAction.GET, APP, "svc", "acct", null, IosKeychainAccessibility.WHEN_UNLOCKED, GROUP, 10) }
    }
    assertContains(e.message!!, "unreadable output")
    assertFalse(GOT_SECRET in e.message!!)
  }

  @Test
  fun `the helper source pins the group, prints data only on success, and only get returns a value`() {
    val source = IosSimctl.KEYCHAIN_HELPER_SWIFT
    assertContains(source, "environment[\"TRAILBLAZE_KEYCHAIN_VALUE\"]")
    assertContains(source, "SecItemCopyMatching(read as CFDictionary, &out)")
    assertContains(source, "kSecUseDataProtectionKeychain as String: true")
    // Every query is built by one function that pins the access group.
    assertContains(source, "kSecAttrAccessGroup as String: group,")
    assertEquals(1, Regex("kSecAttrAccessGroup as String:").findAll(source).count())
    // set's read-back and get ask for data; only get emits it.
    assertEquals(2, Regex("kSecReturnData").findAll(source).count())
    assertEquals(1, Regex("emit\\(found: true, value: value\\)").findAll(source).count())
    // Failures go to stderr; stdout carries one line and only from emit.
    assertContains(source, "FileHandle.standardError.write")
    assertEquals(1, Regex("print\\(").findAll(source).count(), "only emit prints to stdout")
    // list covers both password classes in every sync state.
    assertContains(source, "(kSecClassGenericPassword, \"genericPassword\"), (kSecClassInternetPassword, \"internetPassword\")")
    assertContains(source, "kSecAttrSynchronizableAny")
    // set updates in place and adds only when missing, so a failed write keeps the old item.
    assertContains(source, "SecItemUpdate(item as CFDictionary, attributes as CFDictionary)")
    assertEquals(1, Regex("SecItemDelete\\(").findAll(source).count(), "only delete may delete")
  }

  @Test
  fun `a cancelled keychain call stays a cancellation, not a tool failure`() {
    val runner = FakeRunner { call ->
      if (call.isSimctl && call.argv.first() == "spawn") throw kotlinx.coroutines.CancellationException("session aborted")
      ok("26.5\n")
    }
    val cache = tempDir()
    Files.createDirectories(helperIn(cache).parent)
    helperIn(cache).writeText("binary")
    Files.setPosixFilePermissions(helperIn(cache), PosixFilePermissions.fromString("rwx------"))
    assertFailsWith<kotlinx.coroutines.CancellationException> {
      runBlocking { simctl(runner, cache).set(group = GROUP) }
    }
  }

  @Test
  fun `list and delete pass their filters, send no value, and return the items`() = runBlocking {
    val host = KeychainHost(helperOutput = """{"found":true,"items":[{"accessGroup":"$GROUP","account":"a1","itemClass":"genericPassword","service":"svc"},{"accessGroup":"$GROUP","account":"a2","itemClass":"internetPassword","service":"example.com"}]}""")
    val simctl = simctl(host.runner)
    val listed = simctl.keychain(IosKeychainAction.LIST, APP, null, null, null, IosKeychainAccessibility.WHEN_UNLOCKED, GROUP, 10)
    assertEquals(listOf("a1", "a2"), listed.items.map { it.account })
    assertEquals(listOf("genericPassword", "internetPassword"), listed.items.map { it.itemClass })
    simctl.keychain(IosKeychainAction.DELETE, APP, "svc", null, null, IosKeychainAccessibility.WHEN_UNLOCKED, GROUP, 10)
    val spawns = host.runner.calls.filter { it.isSimctl && it.argv.first() == "spawn" }
    assertEquals(listOf("list", GROUP, "", ""), spawns[0].argv.subList(3, 7))
    assertEquals(listOf("delete", GROUP, "svc", ""), spawns[1].argv.subList(3, 7))
    assertTrue(spawns.all { it.environment.isEmpty() })
  }

  @Test
  fun `each keychain action checks its arguments before anything runs`() {
    fun refusedKeychain(action: IosKeychainAction, service: String?, account: String?, value: String?): String {
      val host = KeychainHost()
      val e = assertFailsWith<IllegalArgumentException> {
        runBlocking { simctl(host.runner).keychain(action, APP, service, account, value, IosKeychainAccessibility.WHEN_UNLOCKED, null, 10) }
      }
      assertEquals(emptyList(), host.runner.calls)
      return e.message!!
    }
    assertContains(refusedKeychain(IosKeychainAction.SET, null, "a", "v"), "needs a service and an account")
    assertContains(refusedKeychain(IosKeychainAction.SET, "s", "a", null), "non-empty value")
    assertContains(refusedKeychain(IosKeychainAction.DELETE, null, null, null), "needs a service")
    assertContains(refusedKeychain(IosKeychainAction.GET, "s", null, null), "get needs a service and an account")
    assertContains(refusedKeychain(IosKeychainAction.GET, "s", "a", "v"), "only for keychain set")
    assertContains(refusedKeychain(IosKeychainAction.LIST, null, null, "v"), "only for keychain set")
    assertContains(refusedKeychain(IosKeychainAction.LIST, "s\u0000x", null, null), "control characters")
  }

  // --- the tools ---

  private fun decode(json: String): IosSimctlTrailblazeTool =
    Json.decodeFromString(IosSimctlTrailblazeTool.serializer(), json)

  @Test
  fun `the tool returns the exit code and output as structured content`() = runBlocking {
    val runner = FakeRunner { ok("$APP: 7\n") }
    val result = decode("""{"argv":["launch","booted","$APP"]}""").execute(simctl(runner))
    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(
      IosSimctlResult(0, "$APP: 7\n", "", false),
      TrailblazeJsonInstance.decodeFromJsonElement(IosSimctlResult.serializer(), result.structuredContent!!),
    )
    assertEquals("$APP: 7", result.message)
  }

  @Test
  fun `a non-zero exit is a result with its code, not a tool error`() = runBlocking {
    val runner = FakeRunner { fail("Unable to lookup in current state: Shutdown") }
    val result = decode("""{"argv":["openurl","booted","myapp://a"]}""").execute(simctl(runner))
    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(1, result.structuredContent!!.jsonObject["exitCode"]!!.jsonPrimitive.content.toInt())
    assertContains(result.message.orEmpty(), "exited 1: Unable to lookup")
  }

  @Test
  fun `the tool passes stdin through, masks it and keeps it out of its own string`() = runBlocking {
    val runner = FakeRunner { ok() }
    val tool = decode("""{"argv":["pbcopy","booted"],"stdin":"$PASSWORD"}""")
    assertIs<TrailblazeToolResult.Success>(tool.execute(simctl(runner)))
    assertEquals(PASSWORD, runner.calls.single().stdin)
    assertContains(tool.sensitiveArgNames, "stdin")
    assertContains(tool.sensitiveValues, PASSWORD)
    assertFalse(PASSWORD in tool.toString())
    assertTrue(logs.none { PASSWORD in it })
  }

  @Test
  fun `a refused argv is a tool error with the reason, and runs nothing`() = runBlocking {
    val runner = FakeRunner { ok() }
    val result = decode("""{"argv":["erase","booted"]}""").execute(simctl(runner))
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertContains(result.errorMessage, "`erase` is not allowed")
    assertEquals(emptyList(), runner.calls)
  }

  @Test
  fun `the tool refuses launchEnvironment, runs nothing, and keeps its values out of the error`() = runBlocking {
    val runner = FakeRunner { ok() }
    val tool = decode("""{"argv":["launch","booted","$APP"],"launchEnvironment":[{"name":"TOKEN","value":"$PASSWORD"}]}""")
    val result = tool.execute(simctl(runner))
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertContains(result.errorMessage, "launchEnvironment is not supported yet")
    assertTrue(PASSWORD !in result.errorMessage)
    assertEquals(emptyList(), runner.calls)
    assertContains(tool.sensitiveArgNames, "launchEnvironment")
    assertTrue(PASSWORD !in tool.toString())
    assertContains(decode("""{"argv":["launch","booted","$APP"],"launchEnvironment":[{"name":"PIN","value":"1234"}]}""").sensitiveValues, "1234")
    assertContains(tool.sensitiveValues, PASSWORD)
  }

  @Test
  fun `the default timeout is 10s, 30s for log show, and the caller can raise it`() = runBlocking {
    val runner = FakeRunner { ok() }
    decode("""{"argv":["keychain","booted","reset"]}""").execute(simctl(runner))
    decode("""{"argv":["spawn","booted","log","show","--predicate","x","--last","5s"]}""").execute(simctl(runner))
    decode("""{"argv":["keychain","booted","reset"],"timeoutSeconds":45}""").execute(simctl(runner))
    // "log show" as app arguments is a launch, so it keeps the 10s default.
    decode("""{"argv":["launch","booted","$APP","log","show"]}""").execute(simctl(runner))
    assertEquals(listOf(10L, 30L, 45L, 10L), runner.calls.map { it.timeoutSeconds })
    val tooLong = decode("""{"argv":["keychain","booted","reset"],"timeoutSeconds":301}""").execute(simctl(runner))
    assertIs<TrailblazeToolResult.Error>(tooLong)
    Unit
  }

  private fun keychainTool(json: String) = Json.decodeFromString(IosKeychainTrailblazeTool.serializer(), json)

  private val remembered = mutableMapOf<String, String>()

  private suspend fun IosKeychainTrailblazeTool.run(host: KeychainHost) =
    execute(simctl(host.runner)) { key, value -> remembered[key] = value }

  private fun TrailblazeToolResult.keychainResult() =
    TrailblazeJsonInstance.decodeFromJsonElement(IosKeychainResult.serializer(), (this as TrailblazeToolResult.Success).structuredContent!!)

  @Test
  fun `ios_keychain set reports found and never the value`() = runBlocking {
    val tool = keychainTool("""{"action":"set","appId":"$APP","service":"svc","account":"acct","value":"$PASSWORD","accessGroup":"$GROUP"}""")
    val result = tool.run(KeychainHost())
    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(IosKeychainResult(IosKeychainAction.SET, found = true), result.keychainResult())
    assertContains(result.message.orEmpty(), "Stored svc / acct")
    assertFalse(PASSWORD in result.toString())
    assertEquals(emptyMap(), remembered)
  }

  @Test
  fun `ios_keychain get puts the value in sensitive memory, never in the result, logs or message`() = runBlocking {
    val tool = keychainTool("""{"action":"get","appId":"$APP","service":"svc","account":"SessionToken","memoryKey":"sessionToken","accessGroup":"$GROUP"}""")
    val result = tool.run(KeychainHost(helperOutput = """{"found":true,"items":[],"value":"$GOT_SECRET"}"""))
    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(mapOf("sessionToken" to GOT_SECRET), remembered)
    assertEquals(IosKeychainResult(IosKeychainAction.GET, found = true, memoryKey = "sessionToken"), result.keychainResult())
    assertContains(result.message.orEmpty(), "{{sessionToken}}")
    assertFalse(GOT_SECRET in result.toString())
    assertFalse(GOT_SECRET in result.structuredContent.toString())
    assertTrue(logs.none { GOT_SECRET in it })
  }

  @Test
  fun `ios_keychain get of a missing item is found false and stores nothing`() = runBlocking {
    val tool = keychainTool("""{"action":"get","appId":"$APP","service":"svc","account":"acct","memoryKey":"k","accessGroup":"$GROUP"}""")
    val result = tool.run(KeychainHost(helperOutput = """{"found":false,"items":[]}"""))
    assertEquals(IosKeychainResult(IosKeychainAction.GET, found = false), result.keychainResult())
    assertEquals(emptyMap(), remembered)
  }

  @Test
  fun `ios_keychain get needs a memory key, and only get takes one`() = runBlocking {
    val host = KeychainHost()
    val noKey = keychainTool("""{"action":"get","appId":"$APP","service":"svc","account":"acct"}""").run(host)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(noKey)
    assertContains(noKey.errorMessage, "needs memoryKey")
    val badKey = keychainTool("""{"action":"get","appId":"$APP","service":"svc","account":"acct","memoryKey":"{{x}}"}""").run(host)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(badKey)
    val onList = keychainTool("""{"action":"list","appId":"$APP","memoryKey":"k"}""").run(host)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(onList)
    assertContains(onList.errorMessage, "only for keychain get")
    assertEquals(emptyList(), host.runner.calls, "nothing runs for a refused call")
  }

  @Test
  fun `ios_keychain list returns names only, delete reports whether anything was there`() = runBlocking {
    val listing = """{"found":true,"items":[{"accessGroup":"$GROUP","account":"acct","itemClass":"genericPassword","service":"svc"}]}"""
    val list = keychainTool("""{"action":"list","appId":"$APP","accessGroup":"$GROUP"}""").run(KeychainHost(helperOutput = listing))
    assertEquals(
      IosKeychainResult(IosKeychainAction.LIST, found = true, items = listOf(IosKeychainItem("svc", "acct", GROUP, "genericPassword"))),
      list.keychainResult(),
    )
    val delete = keychainTool("""{"action":"delete","appId":"$APP","service":"svc","accessGroup":"$GROUP"}""")
      .run(KeychainHost(helperOutput = """{"found":false,"items":[]}"""))
    assertEquals(IosKeychainResult(IosKeychainAction.DELETE, found = false), delete.keychainResult())
    assertContains((delete as TrailblazeToolResult.Success).message.orEmpty(), "Nothing to delete")
  }

  @Test
  fun `ios_keychain decodes its action and accessibility in any case and never prints the value`() = runBlocking {
    val tool = Json.decodeFromString(
      IosKeychainTrailblazeTool.serializer(),
      """{"action":"SET","appId":"$APP","service":"s","account":"a","value":"$PASSWORD","accessible":"afterFirstUnlockThisDeviceOnly"}""",
    )
    assertEquals(IosKeychainAction.SET, tool.action)
    assertEquals(IosKeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, tool.accessible)
    assertContains(tool.sensitiveArgNames, "value")
    assertContains(tool.sensitiveValues, PASSWORD)
    assertFalse(PASSWORD in tool.toString())
    val host = KeychainHost(helperOutput = "read back -25300 for $PASSWORD", failureStdout = PASSWORD)
    val result = tool.run(host)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertFalse(PASSWORD in result.errorMessage)
    assertFalse(PASSWORD in result.toString())
  }

  @Test
  fun `the host arch maps to swiftc's simulator target names`() {
    assertEquals("arm64", IosSimctl.hostSimulatorArch("aarch64"))
    assertEquals("x86_64", IosSimctl.hostSimulatorArch("x86_64"))
  }

  private companion object {
    const val UDID = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
    const val OTHER_UDID = "FFFFFFFF-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
    const val APP = "com.example.app"
    const val APP_DATA = "Containers/Data/Application/AAAA-BBBB"
    const val GROUP = "TEAMID1234.com.example.app"
    const val PASSWORD = "s3cr3t-session-token-value"
    /** A value `get` reads out of the keychain. */
    const val GOT_SECRET = "read-back-s3cret-from-the-app"
    val ITEM_JSON = """{"found":true,"items":[{"accessGroup":"$GROUP","account":"acct","itemClass":"genericPassword","service":"svc"}]}"""
    val ENTITLEMENTS = """<?xml version="1.0"?><plist><dict><key>application-identifier</key><string>$GROUP</string></dict></plist>"""
  }
}
