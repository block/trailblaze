package xyz.block.trailblaze.bundle

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.PackScriptedToolFile
import xyz.block.trailblaze.config.project.ScriptedToolProperty

/**
 * Tests for [WorkspaceClientDtsGenerator] — per-pack codegen that emits
 * `<packDir>/tools/.trailblaze/client.d.ts` declaring typed `client.callTool` overloads
 * for the tools an author's `.ts` code in this pack can dispatch.
 *
 * Coverage:
 *  - Empty inputs → emits a valid, parse-able `.d.ts` with an empty `TrailblazeToolMap`.
 *  - Mixed Kotlin descriptors + scripted-tool YAMLs → all entries present, alphabetised by name.
 *  - Optional parameters (koog `optionalParameters` / scripted `required: false`) → `propName?: type`.
 *  - Hyphenated tool / property names → emitted as quoted properties.
 *  - Idempotent write — second `generateForPack()` with same inputs leaves the output mtime unchanged.
 *  - Output path lives under `<packDir>/tools/.trailblaze/client.d.ts`.
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
    val packDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

    val outputPath = generator.generateForPack(packDir, emptyList(), emptyList())

    assertTrue(Files.isRegularFile(outputPath), "expected file at $outputPath")
    val rendered = Files.readString(outputPath)
    assertTrue("rendered: $rendered") { rendered.contains("declare module \"@trailblaze/scripting\"") }
    assertTrue("rendered: $rendered") { rendered.contains("interface TrailblazeToolMap {") }
    assertTrue("rendered: $rendered") { rendered.contains("interface TrailblazeToolMap {\n  }") }
    assertTrue("rendered: $rendered") { rendered.endsWith("export {};\n") }
    // Output goes under `<packDir>/tools/.trailblaze/client.d.ts`.
    val rel = packDir.relativize(outputPath).toString().replace(File.separatorChar, '/')
    assertEquals("tools/.trailblaze/client.d.ts", rel)
  }

  @Test
  fun `kotlin and scripted tools both surface as typed entries`() {
    val packDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

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

    val outputPath = generator.generateForPack(packDir, toolDescriptors, scriptedTools)
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("runCommand: {") }
    assertTrue("rendered: $rendered") { rendered.contains("command: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("cwd?: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("tapOnPoint: {") }
    assertTrue("rendered: $rendered") { rendered.contains("x: number;") }
    assertTrue("rendered: $rendered") { rendered.contains("longPress?: boolean;") }
    assertTrue("rendered: $rendered") { rendered.contains("clock_android_launchApp: {") }
    assertTrue("rendered: $rendered") { rendered.contains("appId?: string;") }
    assertTrue("rendered: $rendered") {
      rendered.contains("contacts_android_launchApp: Record<string, never>;")
    }
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
    val packDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

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

    val outputPath = generator.generateForPack(packDir, toolDescriptors, emptyList())
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("swipe: {") }
    assertTrue("rendered: $rendered") {
      rendered.contains("direction?: \"UP\" | \"DOWN\" | \"LEFT\" | \"RIGHT\";")
    }
  }

  @Test
  fun `hyphenated names are emitted as quoted properties`() {
    val packDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

    val toolDescriptors = listOf(
      ToolDescriptor(
        name = "clock-android-launchApp",
        description = "Hyphenated tool.",
        requiredParameters = listOf(
          ToolParameterDescriptor("app-id", "Package id", ToolParameterType.String),
        ),
      ),
    )

    val outputPath = generator.generateForPack(packDir, toolDescriptors, emptyList())
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("\"clock-android-launchApp\": {") }
    assertTrue("rendered: $rendered") { rendered.contains("\"app-id\": string;") }
    assertFalse("rendered: $rendered") { rendered.contains("\"launchApp\":") }
  }

  @Test
  fun `idempotent write leaves mtime unchanged on second run with same inputs`() {
    val packDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

    val toolDescriptors = listOf(
      ToolDescriptor(
        name = "noop",
        description = "Does nothing",
        requiredParameters = emptyList(),
        optionalParameters = emptyList(),
      ),
    )

    val outputPath = generator.generateForPack(packDir, toolDescriptors, emptyList())
    val firstMtime = Files.getLastModifiedTime(outputPath)

    Thread.sleep(1_100)

    val outputPathAgain = generator.generateForPack(packDir, toolDescriptors, emptyList())
    val secondMtime = Files.getLastModifiedTime(outputPathAgain)

    assertEquals(outputPath, outputPathAgain)
    assertEquals(
      firstMtime,
      secondMtime,
      "Idempotent generator should not rewrite on identical inputs (firstMtime=$firstMtime, secondMtime=$secondMtime)",
    )
  }

  @Test
  fun `each pack produces its own client_d_ts in its own packDir`() {
    // Slicing rule: per-pack codegen lands at `<packDir>/tools/.trailblaze/client.d.ts`,
    // so two pack dirs receive two distinct files. Tests that one generator instance can
    // serve many packs.
    val alphaPackDir = newPackDir()
    val betaPackDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

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

    val alphaPath = generator.generateForPack(alphaPackDir, emptyList(), alphaScripted)
    val betaPath = generator.generateForPack(betaPackDir, emptyList(), betaScripted)

    assertTrue(Files.isRegularFile(alphaPath))
    assertTrue(Files.isRegularFile(betaPath))
    assertEquals("client.d.ts", alphaPath.fileName.toString())
    assertEquals("client.d.ts", betaPath.fileName.toString())
    // Different parents — one per pack.
    assertTrue(alphaPath.parent.startsWith(alphaPackDir))
    assertTrue(betaPath.parent.startsWith(betaPackDir))

    val alphaRendered = Files.readString(alphaPath)
    val betaRendered = Files.readString(betaPath)
    assertTrue("alpha file should contain alpha_login: $alphaRendered") { alphaRendered.contains("alpha_login:") }
    assertFalse("alpha file should NOT contain beta_login: $alphaRendered") { alphaRendered.contains("beta_login:") }
    assertTrue("beta file should contain beta_login: $betaRendered") { betaRendered.contains("beta_login:") }
    assertFalse("beta file should NOT contain alpha_login: $betaRendered") { betaRendered.contains("alpha_login:") }
  }

  @Test
  fun `generateForPackFromResolved walks JSON Schema inputSchema from InlineScriptToolConfig`() {
    val packDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

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
                buildJsonObject { put("type", JsonPrimitive("boolean")) },
              )
            },
          )
          put("required", buildJsonArray { add(JsonPrimitive("appId")) })
        },
      ),
    )

    val outputPath = generator.generateForPackFromResolved(
      packDir = packDir,
      toolDescriptors = emptyList(),
      scriptedTools = resolvedTools,
    )
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("clock_android_launchApp: {") }
    assertTrue("rendered: $rendered") { rendered.contains("appId: string;") }
    assertFalse("rendered: $rendered") { rendered.contains("appId?: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("force?: boolean;") }
    assertTrue("rendered: $rendered") { rendered.contains("Override the default app id.") }
  }

  @Test
  fun `generateForPackFromResolved handles empty inputSchema as Record empty-string never`() {
    val packDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

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

    val outputPath = generator.generateForPackFromResolved(packDir, emptyList(), resolvedTools)
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("clock_clearAlarms: Record<string, never>;") }
  }

  @Test
  fun `generateForPackFromResolved tolerates non-object property entry without crashing`() {
    // A malformed `inputSchema.properties` entry used to crash codegen via `propEl.jsonObject`'s
    // `IllegalArgumentException`. Now degrades gracefully: the bad property is skipped.
    val packDir = newPackDir()
    val generator = WorkspaceClientDtsGenerator()

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
              put("good", buildJsonObject { put("type", JsonPrimitive("string")) })
              put("bad", JsonPrimitive("not-an-object"))
            },
          )
          put("required", buildJsonArray { add(JsonPrimitive("good")) })
        },
      ),
    )

    val outputPath = generator.generateForPackFromResolved(packDir, emptyList(), resolvedTools)
    val rendered = Files.readString(outputPath)

    assertTrue("good param should render: $rendered") { rendered.contains("good: string;") }
    assertFalse("bad param should be skipped: $rendered") { rendered.contains("bad:") }
  }

  // ---- helpers ----------------------------------------------------------------------------

  private fun newPackDir(): Path {
    val dir = createTempDirectory("client-dts-pack-test").toFile()
    tempDirs += dir
    return dir.toPath()
  }
}
