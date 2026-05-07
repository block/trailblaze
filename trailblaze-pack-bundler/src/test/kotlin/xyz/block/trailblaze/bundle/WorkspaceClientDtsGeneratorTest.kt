package xyz.block.trailblaze.bundle

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.PackScriptedToolFile
import xyz.block.trailblaze.config.project.ScriptedToolProperty

/**
 * Tests for [WorkspaceClientDtsGenerator] — daemon-time codegen that emits the workspace-local
 * `client.d.ts` declaring typed `client.callTool` overloads for every registered tool.
 *
 * Coverage:
 *  - Empty inputs → emits a valid, parse-able `.d.ts` with an empty `TrailblazeToolMap`.
 *  - Mixed Kotlin descriptors + scripted-tool YAMLs → all entries present, alphabetised by name.
 *  - Optional parameters (koog `optionalParameters` / scripted `required: false`) → `propName?: type`.
 *  - Hyphenated tool / property names → emitted as quoted properties (matches per-pack writer convention).
 *  - Idempotent write — second `generate()` with same inputs leaves the output mtime unchanged.
 */
class WorkspaceClientDtsGeneratorTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `empty inputs emit valid d_ts with empty TrailblazeToolMap`() {
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val outputPath = generator.generate(emptyList(), emptyList())

    assertTrue(Files.isRegularFile(outputPath), "expected file at $outputPath")
    val rendered = Files.readString(outputPath)
    assertTrue("rendered: $rendered") { rendered.contains("declare module \"@trailblaze/scripting\"") }
    assertTrue("rendered: $rendered") { rendered.contains("interface TrailblazeToolMap {") }
    // The `}` closing the empty interface should be on the line right after the opening — no
    // entries between them.
    assertTrue("rendered: $rendered") { rendered.contains("interface TrailblazeToolMap {\n  }") }
    assertTrue("rendered: $rendered") { rendered.endsWith("export {};\n") }
    // Output goes under <workspaceRoot>/config/tools/.trailblaze/ to match the documented path
    // (`<workspace>/trails/config/tools/.trailblaze/client.d.ts` — input is the `trails/` dir).
    val rel = workspace.toPath().relativize(outputPath).toString().replace(File.separatorChar, '/')
    assertEquals("config/tools/.trailblaze/client.d.ts", rel)
  }

  @Test
  fun `kotlin and scripted tools both surface as typed entries`() {
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val toolDescriptors = listOf(
      ToolDescriptor(
        name = "runCommand",
        description = "Run a shell command on the host.",
        requiredParameters = listOf(
          ToolParameterDescriptor("command", "Shell command to run", ToolParameterType.String),
        ),
        optionalParameters = listOf(
          ToolParameterDescriptor("cwd", "Working directory", ToolParameterType.String),
        ),
      ),
      ToolDescriptor(
        name = "tapOnPoint",
        description = "Tap at coordinates.",
        requiredParameters = listOf(
          ToolParameterDescriptor("x", "X coord", ToolParameterType.Integer),
          ToolParameterDescriptor("y", "Y coord", ToolParameterType.Integer),
        ),
        optionalParameters = listOf(
          ToolParameterDescriptor("longPress", "Long press", ToolParameterType.Boolean),
        ),
      ),
    )
    val scriptedTools = listOf(
      PackScriptedToolFile(
        script = "./tools/clock_android_launchApp.ts",
        name = "clock_android_launchApp",
        description = "Launch the system Clock app on Android.",
        inputSchema = mapOf(
          "appId" to ScriptedToolProperty(
            type = "string",
            description = "Override the default Clock app id.",
            required = false,
          ),
        ),
      ),
      PackScriptedToolFile(
        script = "./tools/contacts_android_launchApp.ts",
        name = "contacts_android_launchApp",
        description = "Launch the system Contacts app on Android.",
        inputSchema = emptyMap(),
      ),
    )

    val outputPath = generator.generate(toolDescriptors, scriptedTools)
    val rendered = Files.readString(outputPath)

    // Every entry surfaces with the expected typed shape.
    assertTrue("rendered: $rendered") { rendered.contains("runCommand: {") }
    assertTrue("rendered: $rendered") { rendered.contains("command: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("cwd?: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("tapOnPoint: {") }
    assertTrue("rendered: $rendered") { rendered.contains("x: number;") }
    assertTrue("rendered: $rendered") { rendered.contains("longPress?: boolean;") }
    // Scripted tool with one optional param.
    assertTrue("rendered: $rendered") { rendered.contains("clock_android_launchApp: {") }
    assertTrue("rendered: $rendered") { rendered.contains("appId?: string;") }
    // Scripted tool with no params surfaces as `Record<string, never>` per the bundler convention.
    assertTrue("rendered: $rendered") {
      rendered.contains("contacts_android_launchApp: Record<string, never>;")
    }
    // Entries are sorted alphabetically — `clock_*` before `contacts_*` before `runCommand`
    // before `tapOnPoint`. Verify by index ordering in the rendered string.
    val idxClock = rendered.indexOf("clock_android_launchApp")
    val idxContacts = rendered.indexOf("contacts_android_launchApp")
    val idxRunCommand = rendered.indexOf("runCommand:")
    val idxTapOnPoint = rendered.indexOf("tapOnPoint:")
    assertTrue("entries should be sorted alphabetically") {
      idxClock in 0..idxContacts && idxContacts < idxRunCommand && idxRunCommand < idxTapOnPoint
    }
  }

  @Test
  fun `optional koog parameter surfaces as questionMark`() {
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    // A tool with one optional koog parameter — should emit `name?: type`.
    val toolDescriptors = listOf(
      ToolDescriptor(
        name = "swipe",
        description = "",
        requiredParameters = emptyList(),
        optionalParameters = listOf(
          ToolParameterDescriptor(
            name = "direction",
            description = "Swipe direction",
            type = ToolParameterType.Enum(arrayOf("UP", "DOWN", "LEFT", "RIGHT")),
          ),
        ),
      ),
    )

    val outputPath = generator.generate(toolDescriptors, emptyList())
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("swipe: {") }
    // Enum becomes a string-literal union; optional gets the `?`.
    assertTrue("rendered: $rendered") {
      rendered.contains("direction?: \"UP\" | \"DOWN\" | \"LEFT\" | \"RIGHT\";")
    }
  }

  @Test
  fun `hyphenated names are emitted as quoted properties`() {
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val toolDescriptors = listOf(
      ToolDescriptor(
        // Hyphenated tool name. Real-world example: `clock-android-launchApp` style.
        name = "clock-android-launchApp",
        description = "Hyphenated tool.",
        requiredParameters = listOf(
          // Hyphenated property name too — exercise the same quoting path on params.
          ToolParameterDescriptor("app-id", "Package id", ToolParameterType.String),
        ),
      ),
    )

    val outputPath = generator.generate(toolDescriptors, emptyList())
    val rendered = Files.readString(outputPath)

    // Tool name is emitted as a quoted property; same for the hyphenated property name.
    // Matches `built-in-tools.ts`'s pattern where TS-unsafe identifiers use quoted keys.
    assertTrue("rendered: $rendered") { rendered.contains("\"clock-android-launchApp\": {") }
    assertTrue("rendered: $rendered") { rendered.contains("\"app-id\": string;") }
    // CamelCased identifier (`launchApp`) is unquoted as a sanity check that quoting is targeted.
    assertFalse("rendered: $rendered") { rendered.contains("\"launchApp\":") }
  }

  @Test
  fun `idempotent write leaves mtime unchanged on second run with same inputs`() {
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val toolDescriptors = listOf(
      ToolDescriptor(
        name = "noop",
        description = "Does nothing",
        requiredParameters = emptyList(),
        optionalParameters = emptyList(),
      ),
    )

    val outputPath = generator.generate(toolDescriptors, emptyList())
    val firstMtime = Files.getLastModifiedTime(outputPath)

    // Sleep just enough that any rewrite would produce a *different* mtime on systems whose
    // mtime granularity is 1 s (older HFS+, some network filesystems). The assertion below
    // verifies the mtime is byte-equal — so a no-op generator stays no-op even across this
    // resolution boundary.
    Thread.sleep(1_100)

    val outputPathAgain = generator.generate(toolDescriptors, emptyList())
    val secondMtime = Files.getLastModifiedTime(outputPathAgain)

    assertEquals(outputPath, outputPathAgain)
    assertEquals(
      firstMtime,
      secondMtime,
      "Idempotent generator should not rewrite on identical inputs (firstMtime=$firstMtime, secondMtime=$secondMtime)",
    )
  }

  @Test
  fun `outputFileName parameter writes per-target sliced files instead of single client_d_ts`() {
    // Per-target slicing: the wire-in code calls generate*-once per resolved target with a
    // distinct `outputFileName` so each target ends up with its own `.d.ts`. Two calls into
    // the same generator instance must produce two distinct files, not overwrite one.
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val alphaScripted = listOf(
      PackScriptedToolFile(
        script = "./tools/alpha_login.ts",
        name = "alpha_login",
        description = "Sign into Alpha.",
        inputSchema = mapOf("email" to ScriptedToolProperty(type = "string", required = true)),
      ),
    )
    val betaScripted = listOf(
      PackScriptedToolFile(
        script = "./tools/beta_login.ts",
        name = "beta_login",
        description = "Sign into Beta.",
        inputSchema = mapOf("merchantId" to ScriptedToolProperty(type = "string", required = true)),
      ),
    )

    val alphaPath = generator.generate(emptyList(), alphaScripted, outputFileName = "client.alpha.d.ts")
    val betaPath = generator.generate(emptyList(), betaScripted, outputFileName = "client.beta.d.ts")

    // Two distinct files in the same `.trailblaze/` dir.
    assertEquals(alphaPath.parent, betaPath.parent)
    assertTrue(Files.isRegularFile(alphaPath))
    assertTrue(Files.isRegularFile(betaPath))
    assertEquals("client.alpha.d.ts", alphaPath.fileName.toString())
    assertEquals("client.beta.d.ts", betaPath.fileName.toString())

    // Each file contains ONLY its target's tool — slicing isolates the per-target surface.
    val alphaRendered = Files.readString(alphaPath)
    val betaRendered = Files.readString(betaPath)
    assertTrue("alpha file should contain alpha_login: $alphaRendered") { alphaRendered.contains("alpha_login:") }
    assertFalse("alpha file should NOT contain beta_login: $alphaRendered") { alphaRendered.contains("beta_login:") }
    assertTrue("beta file should contain beta_login: $betaRendered") { betaRendered.contains("beta_login:") }
    assertFalse("beta file should NOT contain alpha_login: $betaRendered") { betaRendered.contains("alpha_login:") }
  }

  @Test
  fun `generateFromResolved walks JSON Schema inputSchema from InlineScriptToolConfig`() {
    // The post-compile data flow: TrailblazeCompiler emits InlineScriptToolConfig with
    // already-shaped `inputSchema: { type: object, properties: {...}, required: [...] }`.
    // Generator's resolved-shape path must walk that JsonObject and produce the same TS
    // output it would have produced from the flat-author-shape PackScriptedToolFile path.
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val resolvedTools = listOf(
      InlineScriptToolConfig(
        script = "./tools/clock_android_launchApp.ts",
        name = "clock_android_launchApp",
        description = "Force-stop and relaunch the Clock app.",
        inputSchema = buildJsonObject {
          put("type", JsonPrimitive("object"))
          put(
            "properties",
            buildJsonObject {
              put(
                "appId",
                buildJsonObject {
                  put("type", JsonPrimitive("string"))
                  put("description", JsonPrimitive("Override the default app id."))
                },
              )
              put(
                "force",
                buildJsonObject {
                  put("type", JsonPrimitive("boolean"))
                },
              )
            },
          )
          // Only `appId` required; `force` is optional via absence-from-required-array.
          put("required", buildJsonArray { add(JsonPrimitive("appId")) })
        },
      ),
    )

    val outputPath = generator.generateFromResolved(
      toolDescriptors = emptyList(),
      scriptedTools = resolvedTools,
      outputFileName = "client.clock.d.ts",
    )
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("clock_android_launchApp: {") }
    // Required parameter — no question mark.
    assertTrue("rendered: $rendered") { rendered.contains("appId: string;") }
    assertFalse("rendered: $rendered") { rendered.contains("appId?: string;") }
    // Optional parameter — has question mark.
    assertTrue("rendered: $rendered") { rendered.contains("force?: boolean;") }
    // Description is attached as a JSDoc comment on the property.
    assertTrue("rendered: $rendered") { rendered.contains("Override the default app id.") }
  }

  @Test
  fun `generateFromResolved handles empty inputSchema as Record empty-string never`() {
    // Tool that takes no args: the compiler emits `inputSchema: { type: object, properties: {} }`
    // (no `required` key, no properties). The renderer's no-params branch should fire.
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val resolvedTools = listOf(
      InlineScriptToolConfig(
        script = "./tools/clock_clearAlarms.ts",
        name = "clock_clearAlarms",
        description = "Clear all alarms.",
        inputSchema = buildJsonObject {
          put("type", JsonPrimitive("object"))
          put("properties", buildJsonObject { /* no entries */ })
        },
      ),
    )

    val outputPath = generator.generateFromResolved(
      toolDescriptors = emptyList(),
      scriptedTools = resolvedTools,
      outputFileName = "client.clock.d.ts",
    )
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("clock_clearAlarms: Record<string, never>;") }
  }

  @Test
  fun `outputFileName with path separator is rejected to prevent path traversal`() {
    // Defense-in-depth: a tainted `outputFileName` (forwarded from a user-authored target
    // id like `../../../etc/passwd`) must not let the generator escape the
    // `<workspaceRoot>/config/tools/.trailblaze/` write boundary.
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val ex = assertFailsWith<IllegalArgumentException> {
      generator.generate(emptyList(), emptyList(), outputFileName = "../escaped.d.ts")
    }
    val msg = ex.message ?: ""
    assertTrue("expected message to name the rejected filename: $msg") { msg.contains("../escaped.d.ts") }
    assertTrue("expected message to mention 'safe filename': $msg") { msg.contains("safe filename") }
  }

  @Test
  fun `outputFileName with forward slash is rejected`() {
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    assertFailsWith<IllegalArgumentException> {
      generator.generate(emptyList(), emptyList(), outputFileName = "subdir/client.d.ts")
    }
  }

  @Test
  fun `outputFileName with leading dot is rejected`() {
    // `.malicious.d.ts` is also blocked — leading dot would interact awkwardly with
    // glob patterns (and is forbidden by the safe-filename pattern's first-char class).
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    assertFailsWith<IllegalArgumentException> {
      generator.generate(emptyList(), emptyList(), outputFileName = ".malicious.d.ts")
    }
  }

  @Test
  fun `generateFromResolved tolerates non-object property entry without crashing`() {
    // A malformed `inputSchema.properties` entry (e.g. a JsonPrimitive instead of a
    // JsonObject — possible from a hand-rolled legacy descriptor or a busted
    // `target.tools:` inline) used to crash codegen via `propEl.jsonObject`'s
    // `IllegalArgumentException`. Now degrades gracefully: the bad property is skipped
    // and the rest of the tool's params still surface.
    val workspace = newWorkspaceRoot()
    val generator = WorkspaceClientDtsGenerator(workspace.toPath())

    val resolvedTools = listOf(
      InlineScriptToolConfig(
        script = "./tools/foo.ts",
        name = "foo",
        description = "Test tool with one good and one malformed property.",
        inputSchema = buildJsonObject {
          put("type", JsonPrimitive("object"))
          put(
            "properties",
            buildJsonObject {
              put(
                "good",
                buildJsonObject { put("type", JsonPrimitive("string")) },
              )
              // Malformed: a string primitive where a JsonObject is expected.
              put("bad", JsonPrimitive("not-an-object"))
            },
          )
          put("required", buildJsonArray { add(JsonPrimitive("good")) })
        },
      ),
    )

    val outputPath = generator.generateFromResolved(emptyList(), resolvedTools, outputFileName = "client.foo.d.ts")
    val rendered = Files.readString(outputPath)

    assertTrue("good param should render: $rendered") { rendered.contains("good: string;") }
    assertFalse("bad param should be skipped: $rendered") { rendered.contains("bad:") }
  }

  // ---- helpers ----------------------------------------------------------------------------

  private fun newWorkspaceRoot(): File {
    // The generator treats its input as the `trails/` directory; output lands at
    // `<this>/config/tools/.trailblaze/client.d.ts`. The fixture mirrors that — caller owns
    // an empty trails/ directory, generator creates the .trailblaze/ subtree on demand.
    val dir = createTempDirectory("workspace-client-dts-test").toFile()
    tempDirs += dir
    return dir
  }
}
