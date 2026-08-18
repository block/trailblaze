package xyz.block.trailblaze.quickjs.tools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.toolcalls.DeclaredSensitiveArgs
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.toLogPayload

/**
 * Pins the log-encode redaction contract for a **scripted (TypeScript) tool** on the in-process
 * QuickJS runtime. Before this, a credential passed as a scripted tool's argument landed verbatim
 * in the persisted session log — which ships as a CI artifact — because only Kotlin class-backed
 * tools could implement `SensitiveArgsTrailblazeTool`. See `SensitiveArgsLogRedactionTest` in
 * `:trailblaze-common` for the class-backed side of the same contract.
 *
 * Only observable output is asserted — the payload `toLogPayload()` returns — never internals.
 * Redaction is log-only, so each case also pins that the real value still reaches the bundle via
 * `rawToolArguments`.
 */
class QuickJsSensitiveArgsLogRedactionTest {

  private val hosts = mutableListOf<QuickJsToolHost>()

  @AfterTest
  fun teardown() {
    runBlocking { hosts.forEach { runCatching { it.shutdown() } } }
    hosts.clear()
  }

  @Test
  fun `declared sensitive args are masked in the log payload`() {
    val tool = QuickJsTrailblazeTool(
      host = noopHost(),
      advertisedName = ToolName("myapp_signIn"),
      args = buildJsonObject {
        put("email", "user@example.com")
        put("password", "s3cret-staging-pw")
      },
      binding = null,
      sensitiveArgs = DeclaredSensitiveArgs.Named(setOf("password")),
    )

    val payload = tool.toLogPayload()

    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), payload.raw["password"])
    assertEquals(JsonPrimitive("user@example.com"), payload.raw["email"], "non-sensitive args survive")
  }

  @Test
  fun `execution still receives the real value`() {
    val tool = QuickJsTrailblazeTool(
      host = noopHost(),
      advertisedName = ToolName("myapp_signIn"),
      args = buildJsonObject { put("password", "s3cret-staging-pw") },
      binding = null,
      sensitiveArgs = DeclaredSensitiveArgs.Named(setOf("password")),
    )

    assertEquals(
      JsonPrimitive("s3cret-staging-pw"),
      tool.rawToolArguments["password"],
      "the args the bundle dispatches with must stay unredacted",
    )
  }

  @Test
  fun `a tool declaring nothing sensitive logs its args unchanged`() {
    val tool = QuickJsTrailblazeTool(
      host = noopHost(),
      advertisedName = ToolName("myapp_tap"),
      args = buildJsonObject { put("password", "not-declared-so-not-masked") },
    )

    assertEquals(JsonPrimitive("not-declared-so-not-masked"), tool.toLogPayload().raw["password"])
  }

  @Test
  fun `registration threads the declared names from _meta onto the decoded tool`() {
    // The production advertise-then-decode path: `_meta.trailblaze/sensitiveArgNames` — written by
    // the analyzer from the TS spec, or by the `.tool.yaml` shortcut — must reach the decoded tool.
    val spec = buildJsonObject {
      put(
        "_meta",
        buildJsonObject {
          put("trailblaze/sensitiveArgNames", buildJsonArray { add(JsonPrimitive("password")) })
        },
      )
    }
    val registration = QuickJsToolRegistration(
      host = noopHost(),
      spec = RegisteredToolSpec(name = "myapp_signIn", spec = spec),
      sensitiveArgs = QuickJsToolMeta.fromSpec(spec).sensitiveArgs,
    )

    val decoded = registration.decodeToolCall("""{"email":"user@example.com","password":"s3cret"}""")

    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), decoded.toLogPayload().raw["password"])
    assertEquals(
      JsonPrimitive("s3cret"),
      (decoded as RawArgumentTrailblazeTool).rawToolArguments["password"],
      "the decoded tool must still dispatch the real value",
    )
  }

  @Test
  fun `an unreadable declaration masks every arg in the log payload`() {
    // A bundle's `_meta` never passes through the descriptor loader's shape validation, so a
    // malformed declaration reaches the runtime. Masking nothing there would leak the credential
    // the author was trying to protect; masking everything is the only reading that can't.
    val spec = buildJsonObject {
      put("_meta", buildJsonObject { put("trailblaze/sensitiveArgNames", "password") })
    }
    val tool = QuickJsTrailblazeTool(
      host = noopHost(),
      advertisedName = ToolName("myapp_signIn"),
      args = buildJsonObject {
        put("email", "user@example.com")
        put("password", "s3cret-staging-pw")
      },
      binding = null,
      sensitiveArgs = QuickJsToolMeta.fromSpec(spec).sensitiveArgs,
    )

    val payload = tool.toLogPayload()

    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), payload.raw["password"])
    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), payload.raw["email"])
    assertEquals(
      JsonPrimitive("s3cret-staging-pw"),
      tool.rawToolArguments["password"],
      "over-redacting the log must still not change what the bundle receives",
    )
  }

  /** A connected host registering one no-op tool. Never dispatched — log encoding never calls it. */
  private fun noopHost(): QuickJsToolHost = runBlocking {
    QuickJsToolHost.connect(bundleJs = NOOP_BUNDLE, bundleFilename = "tools.bundle.js")
      .also { hosts.add(it) }
  }

  private companion object {
    val NOOP_BUNDLE = """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["noop"] = {
        name: "noop",
        spec: {},
        handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
      };
    """.trimIndent()
  }
}
