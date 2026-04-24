package xyz.block.trailblaze.scripting.bundle

/**
 * Loads the JS shim we evaluate inside QuickJS before the author's bundle runs.
 *
 * The shim installs `globalThis.__trailblazeInProcessTransport` — a plain object shaped
 * like the MCP JS SDK's `Transport` interface. The author's bundle finishes setup with
 *
 * ```ts
 * await server.connect(globalThis.__trailblazeInProcessTransport);
 * ```
 *
 * and from that point every message the JS `Server` sends goes through the QuickJS
 * bridge into [InProcessMcpTransport] on the Kotlin side.
 *
 * ### Where the JS lives
 *
 * The actual `.js` ships as a classpath resource at
 * `src/jvmAndAndroid/resources/trailblaze/scripting/bundle/trailblaze-bundle-prelude.js`.
 * Keeping it as a standalone file means it's syntax-highlighted, lintable, and diffable
 * as JS — not trapped in a triple-quoted Kotlin string.
 *
 * Two placeholders in the file get substituted at load time:
 *
 *  - `__DELIVER_TO_KOTLIN__`     → [DELIVER_TO_KOTLIN]
 *  - `__IN_PROCESS_TRANSPORT__`  → [IN_PROCESS_TRANSPORT]
 *
 * This keeps the binding/global names owned here (so a rename updates everywhere) while
 * the `.js` stays as plain identifiers a human can skim.
 *
 * ### Contract the JS side exposes
 *
 *  - `__trailblazeInProcessTransport.send(msg)` → ships the message to Kotlin via
 *    `__trailblazeDeliverToKotlin(jsonString)`.
 *  - `__trailblazeInProcessTransport.onmessage` → the handler the SDK's `Server` installs.
 *    Kotlin invokes it when delivering a request.
 *  - `start()` is a no-op (the bridge is live the moment QuickJS evaluates this).
 *  - `close()` fires any `onclose` handler the bundle installed.
 */
internal object BundleRuntimePrelude {

  /** Name of the JS→Kotlin binding registered by [QuickJsBridge]. */
  const val DELIVER_TO_KOTLIN: String = "__trailblazeDeliverToKotlin"

  /** Name of the JS global the author's bundle hands to `server.connect(…)`. */
  const val IN_PROCESS_TRANSPORT: String = "__trailblazeInProcessTransport"

  /**
   * Name of the JS→Kotlin binding used by the TS SDK's on-device `client.callTool` path.
   * The SDK checks `typeof globalThis.__trailblazeCallback === "function"` to detect the
   * bundle runtime (alongside `ctx.runtime === "ondevice"`) and calls it with a JSON-
   * serialized `JsScriptingCallbackRequest`; Kotlin dispatches through [JsScriptingCallbackDispatcher] and
   * resolves with a JSON-serialized `JsScriptingCallbackResponse`. No HTTP hop; same registry the
   * daemon's `/scripting/callback` endpoint reads from.
   */
  const val CALLBACK_BINDING: String = "__trailblazeCallback"

  /**
   * Name of the JS→Kotlin binding that backs `globalThis.console.*` on-device. The prelude
   * installs a `console` object whose `log` / `info` / `warn` / `error` / `debug` methods
   * call this binding with a (level, message) pair; Kotlin routes each through
   * [xyz.block.trailblaze.util.Console.log] so author output lands in logcat alongside the
   * Trailblaze runtime's own tagged logs.
   *
   * Why it exists: QuickJS ships without a `console` global, so an author porting a TS tool
   * from the host-subprocess path (where Node/bun defines `console`) to on-device would
   * otherwise crash on the first `console.log`. The shim makes `console.*` behave
   * identically to how MCP stdio authors already expect on host — `console.error` always
   * lands somewhere visible — so no new `trailblaze.log(...)` API is needed.
   *
   * ### Install-order invariant
   *
   * The prelude that installs `globalThis.console` is evaluated at session start **before**
   * the author bundle runs, and [QuickJsBridge] registers the Kotlin-side binding during
   * its `init` block — also before the bundle evaluates. By the time an author's code
   * executes, `globalThis.console` is the shim and its methods route through Kotlin.
   *
   * Authors MUST NOT overwrite `globalThis.console` in their bundle's top level; doing so
   * would shadow the shim and subsequent log calls would either become no-ops (if they
   * replace `console` with `{}`) or, in exotic cases, re-enable stdout-writing semantics
   * that aren't present on-device anyway. This is not currently enforced — the prelude
   * doesn't use `Object.freeze` because the MCP SDK's own init paths occasionally augment
   * globals and we'd rather not break them — but any future regression in install order
   * would silently lose log routing. Protect it with the binding-name drift guard in
   * `QuickJsBridgeCallbackBindingTest.consoleBindingNameMatchesConstant`.
   */
  const val CONSOLE_BINDING: String = "__trailblazeConsole"

  /**
   * The prelude JS, with placeholders substituted. Lazy + cached — the file doesn't
   * change across sessions, and a classpath read per session start is wasted IO.
   */
  val SOURCE: String by lazy { loadSource() }

  /**
   * Classpath path to the prelude `.js`. `jvmAndAndroid/resources/` is wired into both
   * the JVM jar and the Android AAR's Java-style resources (see the module's
   * `build.gradle.kts`), so `ClassLoader.getResourceAsStream` reaches it on both
   * runtimes without needing an `android.content.res.AssetManager`.
   */
  private const val PRELUDE_RESOURCE_PATH: String =
    "trailblaze/scripting/bundle/trailblaze-bundle-prelude.js"

  private fun loadSource(): String {
    val raw = BundleRuntimePrelude::class.java.classLoader
      ?.getResourceAsStream(PRELUDE_RESOURCE_PATH)
      ?.use { it.readBytes().decodeToString() }
      ?: error(
        "trailblaze-scripting-bundle: missing prelude resource at classpath:$PRELUDE_RESOURCE_PATH. " +
          "Check that src/jvmAndAndroid/resources/ is wired into both the JVM jar and the " +
          "Android AAR (see build.gradle.kts sourceSets config).",
      )
    return raw
      .replace("__DELIVER_TO_KOTLIN__", DELIVER_TO_KOTLIN)
      .replace("__IN_PROCESS_TRANSPORT__", IN_PROCESS_TRANSPORT)
      .replace("__CONSOLE_BINDING__", CONSOLE_BINDING)
  }
}
