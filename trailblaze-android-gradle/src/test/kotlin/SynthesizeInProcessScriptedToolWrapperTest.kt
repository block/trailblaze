import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unit tests for [synthesizeInProcessScriptedToolWrapper] — the multi-export registration wrapper
 * both the `trailblaze.author-tool-bundle` plugin (build-logic) and this plugin's `trailmap { }`
 * block hand esbuild as the bundle entry.
 *
 * These exercise the generated TS source DIRECTLY (no esbuild, no bun, no device), so the
 * multi-export registration contract is guarded even in environments where the end-to-end esbuild
 * path is `assumeTrue`-skipped. The end-to-end behavior (the generated wrapper actually bundles +
 * registers + dispatches) stays covered by `:trailblaze-quickjs-tools:jvmTest`'s
 * `SampleAppToolsDemoTest` and the on-device `QuickJsToolBundleOnDeviceTest`.
 *
 * The wrapper now renders from the ONE committed template
 * (`sdks/typescript/tools/in-process-wrapper-template.mjs`), located via the `trailblaze.sdkDir`
 * system property the `test` task wires to `../sdks/typescript`.
 */
class SynthesizeInProcessScriptedToolWrapperTest {

  private val templateFile: File = run {
    val sdkDir = System.getProperty("trailblaze.sdkDir")
    requireNotNull(sdkDir) {
      "trailblaze.sdkDir system property is not set. Run this through Gradle " +
        "(./gradlew :trailblaze-android-gradle:test) — the `test` task wires `trailblaze.sdkDir` " +
        "to sdks/typescript. A bare IDE run of a single test won't have it; failing fast here " +
        "avoids a confusing file-not-found from the template lookup below."
    }
    File(sdkDir, "tools/in-process-wrapper-template.mjs").also { file ->
      require(file.isFile) {
        "Scripted-tool wrapper template not found at ${file.absolutePath} (resolved from " +
          "trailblaze.sdkDir=$sdkDir). Expected sdks/typescript/tools/in-process-wrapper-template.mjs."
      }
    }
  }

  private val wrapper = synthesizeInProcessScriptedToolWrapper("./typed.ts", "typed.ts", templateFile)

  @Test
  fun `imports the user source as a namespace under the given filename`() {
    assertTrue("wrapper should import the user file as a namespace:\n$wrapper") {
      wrapper.contains("import * as __userModule from \"./typed.ts\";")
    }
  }

  @Test
  fun `registers every function-valued export under its own name`() {
    // The multi-export contract: enumerate the module's exports and register each typed tool on
    // `globalThis.__trailblazeTools[<exportName>]`. This is the key difference from the
    // single-export daemon-time wrapper — a regression that hard-codes one tool name (or drops the
    // loop) would silently register only the first/zero tools, which this asserts against.
    assertTrue("wrapper should enumerate module exports:\n$wrapper") {
      wrapper.contains("for (const __exportName of Object.keys(__userModule))")
    }
    assertTrue("wrapper should register each export by its own name:\n$wrapper") {
      wrapper.contains("globalThis.__trailblazeTools[__exportName] = {")
    }
  }

  @Test
  fun `skips non-function exports so type-only or marker exports are not registered`() {
    assertTrue("wrapper should filter to function-valued exports:\n$wrapper") {
      wrapper.contains("if (typeof __def !== 'function') continue;")
    }
  }

  @Test
  fun `injects the composition client as the handler's third argument`() {
    // Typed tools are 3-arg adapters `(args, ctx, client)`; the wrapper's handler is the 2-arg
    // `(args, ctx)` shape QuickJsToolHost.callTool invokes, and it must inject `__client` itself.
    assertTrue("handler should invoke the export with the injected client:\n$wrapper") {
      wrapper.contains("await __def(args, ctx, __client)")
    }
  }

  @Test
  fun `carries the composition shim and envelope normalization`() {
    assertTrue("wrapper should build the __client composition shim:\n$wrapper") {
      wrapper.contains("__client.tools = new Proxy(")
    }
    assertTrue("wrapper should normalize bare return values into a content envelope:\n$wrapper") {
      wrapper.contains("function __normalizeResult(result)")
    }
    // The slim profile never registers an MCP server; the wrapper must not reference it.
    assertTrue("wrapper should not reference the MCP SDK:\n$wrapper") {
      !wrapper.contains("@modelcontextprotocol")
    }
  }
}
