package xyz.block.trailblaze.device

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.device.EnsureAppCompiled.Outcome

/**
 * The shared check-then-compile sequence behind `android_ensureAppCompiled` and the host-side
 * session start, driven through a scripted device shell that records every command it was asked
 * to run — so each test asserts not only the outcome but that the device was consulted exactly as
 * many times as the outcome claims.
 */
class EnsureAppCompiledTest {

  private val pkg = "com.example.pos"

  /** Verbatim `dumpsys package` shape from an API 36 emulator whose install produced no artifacts. */
  private val brokenDump = """
    Dexopt state:
      [com.example.pos]
        path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
          x86_64: [status=run-from-apk] [reason=unknown] [primary-abi]
            [location is error]
  """.trimIndent()

  private val compiledDump = """
    Dexopt state:
      [com.example.pos]
        path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
          x86_64: [status=verify] [reason=install] [primary-abi]
            [location is /data/app/~~Ab12==/com.example.pos-Cd34==/oat/x86_64/base.odex]
  """.trimIndent()

  /**
   * The same package on Android 8.1, whose dump predates `[status=…]`: the status is the bare
   * `kOat…` token after the ABI. Nothing here is a status line this parser reads.
   */
  private val oreoDump = """
    Dexopt state:
      [com.example.pos]
        path: /data/app/com.example.pos-Cd34==/base.apk
          x86_64: kOatUpToDate
  """.trimIndent()

  private val dumpsys = listOf("dumpsys", "package", pkg)
  private val compile = listOf("pm", "compile", "-m", "verify", "-f", pkg)

  /**
   * Answers each `dumpsys` from [dumps] in order and every `pm compile` with [compileOutput];
   * records every argv it was handed. A null answer stands for "did not finish in time".
   */
  private class ScriptedShell(dumps: List<String?>, private val compileOutput: String? = "Success") {
    private val dumps = ArrayDeque(dumps)
    val calls = mutableListOf<List<String>>()
    val timeoutsSeen = mutableListOf<Long>()

    suspend fun run(args: List<String>, timeoutMs: Long): String? {
      calls += args
      timeoutsSeen += timeoutMs
      return when (args.first()) {
        "dumpsys" -> dumps.removeFirstOrNull()
        "pm" -> compileOutput
        else -> error("unexpected command: $args")
      }
    }
  }

  private fun ensure(
    shell: ScriptedShell,
    force: Boolean = false,
    checkOnly: Boolean = false,
    compilerFilter: String = EnsureAppCompiled.DEFAULT_COMPILER_FILTER,
  ): Outcome = runBlocking {
    EnsureAppCompiled.ensure(
      appId = pkg,
      compilerFilter = compilerFilter,
      force = force,
      checkOnly = checkOnly,
      shell = shell::run,
    )
  }

  @Test fun `a compiled app costs one read and nothing else`() {
    val shell = ScriptedShell(listOf(compiledDump))
    val outcome = assertIs<Outcome.AlreadyCompiled>(ensure(shell))
    assertThat(outcome.state.compiled).isEqualTo(true)
    assertThat(shell.calls).isEqualTo(listOf(dumpsys))
  }

  @Test fun `missing artifacts are compiled, forced, and the device is read again before reporting success`() {
    val shell = ScriptedShell(listOf(brokenDump, compiledDump))
    val outcome = assertIs<Outcome.Compiled>(ensure(shell))
    assertThat(outcome.before.compiled).isEqualTo(false)
    assertThat(outcome.after.compiled).isEqualTo(true)
    assertThat(outcome.compilerFilter).isEqualTo("verify")
    // Read, compile, read — never trusting `pm compile`'s own exit line.
    assertThat(shell.calls).isEqualTo(listOf(dumpsys, compile, dumpsys))
  }

  @Test fun `a compile that changes nothing is a failure, not a success`() {
    // `pm compile` printed Success and exited, yet the device still has no artifacts. Reporting
    // success here would be exactly the silent green this exists to prevent.
    val shell = ScriptedShell(listOf(brokenDump, brokenDump), compileOutput = "Success")
    val outcome = assertIs<Outcome.StillUncompiled>(ensure(shell))
    assertThat(outcome.after?.compiled).isEqualTo(false)
    assertThat(outcome.compileOutput).isEqualTo("Success")
    assertThat(shell.calls).isEqualTo(listOf(dumpsys, compile, dumpsys))
  }

  @Test fun `a re-read that fails after the compile is still not a success`() {
    val shell = ScriptedShell(listOf(brokenDump, null))
    val outcome = assertIs<Outcome.StillUncompiled>(ensure(shell))
    assertThat(outcome.after).isNull()
  }

  @Test fun `a compile that does not return in time is reported as such and the device is not re-read`() {
    val shell = ScriptedShell(listOf(brokenDump), compileOutput = null)
    val outcome = assertIs<Outcome.CompileTimedOut>(ensure(shell))
    assertThat(outcome.timeoutMs).isEqualTo(EnsureAppCompiled.COMPILE_TIMEOUT_MS)
    assertThat(shell.calls).isEqualTo(listOf(dumpsys, compile))
  }

  @Test fun `an app with no dexopt entry is not installed and nothing is compiled`() {
    val otherApp = brokenDump.replace("com.example.pos", "com.example.other")
    val shell = ScriptedShell(listOf(otherApp))
    assertThat(ensure(shell)).isInstanceOf(Outcome.NotInstalled::class)
    assertThat(shell.calls).isEqualTo(listOf(dumpsys))
  }

  @Test fun `a dump that does not come back is a timeout, not a verdict that the app is absent`() {
    // Conflating the two would let a caller iterating candidate app ids read a wedged device as
    // "not installed, try the next one" and silently never report that nothing was actually
    // checked. Compiling on the strength of this would also run `pm compile -f` blind.
    val shell = ScriptedShell(listOf(null))
    val outcome = assertIs<Outcome.DumpTimedOut>(ensure(shell))
    assertThat(outcome.appId).isEqualTo(pkg)
    assertThat(outcome.timeoutMs).isEqualTo(EnsureAppCompiled.DUMP_TIMEOUT_MS)
    assertThat(shell.calls).isEqualTo(listOf(dumpsys))
  }

  @Test fun `a custom dump timeout is the one actually passed to the shell`() {
    val shell = ScriptedShell(listOf(compiledDump))
    runBlocking {
      EnsureAppCompiled.ensure(appId = pkg, dumpTimeoutMs = 999L, shell = shell::run)
    }
    assertThat(shell.calls).isEqualTo(listOf(dumpsys))
    assertThat(shell.timeoutsSeen).isEqualTo(listOf(999L))
  }

  @Test fun `checkOnly reports a broken app without compiling it`() {
    val shell = ScriptedShell(listOf(brokenDump))
    val outcome = assertIs<Outcome.Reported>(ensure(shell, checkOnly = true))
    assertThat(outcome.state.compiled).isEqualTo(false)
    assertThat(shell.calls).isEqualTo(listOf(dumpsys))
  }

  @Test fun `force recompiles an app that is already compiled`() {
    val shell = ScriptedShell(listOf(compiledDump, compiledDump))
    assertThat(ensure(shell, force = true)).isInstanceOf(Outcome.Compiled::class)
    assertThat(shell.calls).isEqualTo(listOf(dumpsys, compile, dumpsys))
  }

  @Test fun `a forced compile pm compile refuses on an already-compiled app is not reported as success`() {
    // `after.compiled` is true either way here — the app was already compiled — so it cannot be
    // the signal. `pm compile`'s own report is the only one available, and it did not say Success.
    val shell = ScriptedShell(listOf(compiledDump, compiledDump), compileOutput = "Failure")
    val outcome = assertIs<Outcome.StillUncompiled>(ensure(shell, force = true))
    assertThat(outcome.compileOutput).isEqualTo("Failure")
    assertThat(shell.calls).isEqualTo(listOf(dumpsys, compile, dumpsys))
  }

  @Test fun `the requested filter is the one passed to pm compile`() {
    val shell = ScriptedShell(listOf(brokenDump, compiledDump))
    ensure(shell, compilerFilter = "speed-profile")
    assertThat(shell.calls[1]).isEqualTo(listOf("pm", "compile", "-m", "speed-profile", "-f", pkg))
  }

  @Test fun `a dump whose status lines this parser cannot read compiles nothing`() {
    // Android 8.x prints the status in a shape that predates `[status=…]`. Reading the empty
    // result as "no artifacts" would compile a healthy app on every single session, and the
    // re-read afterwards would be just as unreadable — so it would report failure every time too.
    val shell = ScriptedShell(listOf(oreoDump))
    val outcome = assertIs<Outcome.StatusUnreadable>(ensure(shell))
    assertThat(outcome.state.statusUnreadable).isEqualTo(true)
    assertThat(shell.calls).isEqualTo(listOf(dumpsys))
  }

  @Test fun `force does not compile against a state that could not be read`() {
    // `force` means "compile even though it looks compiled", not "compile even though I cannot
    // tell" — and the verification after it reads the same unreadable dump, so it could only ever
    // report failure.
    val shell = ScriptedShell(listOf(oreoDump))
    assertThat(ensure(shell, force = true)).isInstanceOf(Outcome.StatusUnreadable::class)
    assertThat(shell.calls).isEqualTo(listOf(dumpsys))
  }

  @Test fun `a filter outside the known set is refused before the device is touched`() {
    // The filter is interpolated into a shell command; the closed set is the injection guard.
    val shell = ScriptedShell(listOf(brokenDump))
    assertFailsWith<IllegalArgumentException> { ensure(shell, compilerFilter = "verify; rm -rf /") }
    assertThat(shell.calls).isEqualTo(emptyList<List<String>>())
  }
}
