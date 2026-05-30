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
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.config.project.ScriptedToolProperty

/**
 * Tests for [WorkspaceClientDtsGenerator] — per-trailmap codegen that emits
 * `<trailmapDir>/tools/trailblaze-client.d.ts` declaring typed `client.callTool` overloads
 * for the tools an author's `.ts` code in this trailmap can dispatch.
 *
 * Coverage:
 *  - Empty inputs → emits a valid, parse-able `.d.ts` with an empty `TrailblazeToolMap`.
 *  - Mixed Kotlin descriptors + scripted-tool YAMLs → all entries present, alphabetised by name.
 *  - Optional parameters (koog `optionalParameters` / scripted `required: false`) → `propName?: type`.
 *  - Hyphenated tool / property names → emitted as quoted properties.
 *  - Idempotent write — second `generateForTrailmap()` with same inputs leaves the output mtime unchanged.
 *  - Output path lives under `<trailmapDir>/tools/trailblaze-client.d.ts`.
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
    val trailmapDir = newTrailmapDir()
    val generator = WorkspaceClientDtsGenerator()

    val outputPath = generator.generateForTrailmap(trailmapDir, emptyList(), emptyList())

    assertTrue(Files.isRegularFile(outputPath), "expected file at $outputPath")
    val rendered = Files.readString(outputPath)
    assertTrue("rendered: $rendered") { rendered.contains("declare module \"@trailblaze/scripting\"") }
    assertTrue("rendered: $rendered") { rendered.contains("interface TrailblazeToolMap {") }
    assertTrue("rendered: $rendered") { rendered.contains("interface TrailblazeToolMap {\n  }") }
    assertTrue("rendered: $rendered") { rendered.endsWith("export {};\n") }
    // Output goes under `<trailmapDir>/tools/trailblaze-client.d.ts`.
    val rel = trailmapDir.relativize(outputPath).toString().replace(File.separatorChar, '/')
    assertEquals("tools/trailblaze-client.d.ts", rel)
    // Regression guard: the legacy `tools/.trailblaze/` subdir must NOT be created
    // by a fresh emit. Defends against a future refactor accidentally re-introducing
    // the hidden subdir (the rename was deliberate — see the file's class-level kdoc).
    assertFalse(
      Files.exists(trailmapDir.resolve("tools").resolve(".trailblaze")),
      "legacy .trailblaze/ subdir must not be created by a fresh emit",
    )
  }

  @Test
  fun `migration cleanup deletes legacy tools_dot_trailblaze_client_d_ts on emit`() {
    // Pre-rename framework versions wrote the typed surface to
    // `<trailmapDir>/tools/.trailblaze/client.d.ts`. After the rename, the output
    // lives at `tools/trailblaze-client.d.ts`. A developer who ran `trailblaze check`
    // before this PR has a stale copy of the legacy file on disk. Two augmentations
    // of `TrailblazeToolMap` from the same trailmap would produce duplicate-identifier
    // errors in strict TS, so emit() prunes the legacy file. This test pins that
    // behavior.
    val trailmapDir = newTrailmapDir()
    val legacyDir = trailmapDir.resolve("tools").resolve(".trailblaze")
    Files.createDirectories(legacyDir)
    val legacyFile = legacyDir.resolve("client.d.ts")
    Files.writeString(legacyFile, "// stale content from previous framework version\n")
    assertTrue(Files.isRegularFile(legacyFile), "fixture should pre-populate the legacy file")

    val generator = WorkspaceClientDtsGenerator()
    generator.generateForTrailmap(trailmapDir, emptyList(), emptyList())

    assertFalse(Files.exists(legacyFile), "legacy client.d.ts should be deleted by migration cleanup")
    assertFalse(
      Files.exists(legacyDir),
      "now-empty legacy .trailblaze/ dir should also be removed",
    )
  }

  @Test
  fun `migration cleanup preserves non-empty legacy_dot_trailblaze dir`() {
    // Defensive: if a future framework version writes another artifact into the
    // legacy `.trailblaze/` dir, the cleanup must NOT delete the dir while it
    // still contains other files. The cleanup only removes the empty-after-prune
    // case.
    val trailmapDir = newTrailmapDir()
    val legacyDir = trailmapDir.resolve("tools").resolve(".trailblaze")
    Files.createDirectories(legacyDir)
    Files.writeString(legacyDir.resolve("client.d.ts"), "// stale\n")
    Files.writeString(legacyDir.resolve("other-future-artifact.txt"), "keep me\n")

    val generator = WorkspaceClientDtsGenerator()
    generator.generateForTrailmap(trailmapDir, emptyList(), emptyList())

    assertFalse(
      Files.exists(legacyDir.resolve("client.d.ts")),
      "legacy client.d.ts is still deleted (it's the one we're migrating away from)",
    )
    assertTrue(
      Files.isDirectory(legacyDir),
      "legacy dir is preserved when other artifacts remain",
    )
    assertTrue(
      Files.isRegularFile(legacyDir.resolve("other-future-artifact.txt")),
      "other artifacts in the legacy dir are untouched",
    )
  }

  @Test
  fun `kotlin and scripted tools both surface as typed entries`() {
    val trailmapDir = newTrailmapDir()
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
      TrailmapScriptedToolFile(
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
      TrailmapScriptedToolFile(
        script = "./tools/contacts_android_launchApp.ts",
        name = "contacts_android_launchApp",
        description = "Launch the system Contacts app on Android.",
        inputSchema = emptyMap(),
      ),
    )

    val outputPath = generator.generateForTrailmap(trailmapDir, toolDescriptors, scriptedTools)
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("runCommand: {") }
    assertTrue("rendered: $rendered") { rendered.contains("command: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("cwd?: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("tapOnPoint: {") }
    assertTrue("rendered: $rendered") { rendered.contains("x: number;") }
    assertTrue("rendered: $rendered") { rendered.contains("longPress?: boolean;") }
    assertTrue("rendered: $rendered") { rendered.contains("clock_android_launchApp: {") }
    assertTrue("rendered: $rendered") { rendered.contains("appId?: string;") }
    // No-arg tool: assert the FULL block (scoped to `contacts_android_launchApp`) so a
    // regression that drops the `{ args; result }` wrapper or misplaces the trailing
    // `result: string;` for this entry can't slip through behind a substring elsewhere
    // in the file.
    val expectedContactsBlock = """
      |    contacts_android_launchApp: {
      |      args: Record<string, never>;
      |      result: string;
      |    };
    """.trimMargin()
    assertTrue("expected contacts block in rendered: $rendered") {
      rendered.contains(expectedContactsBlock)
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
    val trailmapDir = newTrailmapDir()
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

    val outputPath = generator.generateForTrailmap(trailmapDir, toolDescriptors, emptyList())
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("swipe: {") }
    assertTrue("rendered: $rendered") {
      rendered.contains("direction?: \"UP\" | \"DOWN\" | \"LEFT\" | \"RIGHT\";")
    }
  }

  @Test
  fun `hyphenated names are emitted as quoted properties`() {
    val trailmapDir = newTrailmapDir()
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

    val outputPath = generator.generateForTrailmap(trailmapDir, toolDescriptors, emptyList())
    val rendered = Files.readString(outputPath)

    assertTrue("rendered: $rendered") { rendered.contains("\"clock-android-launchApp\": {") }
    assertTrue("rendered: $rendered") { rendered.contains("\"app-id\": string;") }
    assertFalse("rendered: $rendered") { rendered.contains("\"launchApp\":") }
  }

  @Test
  fun `idempotent write leaves mtime unchanged on second run with same inputs`() {
    val trailmapDir = newTrailmapDir()
    val generator = WorkspaceClientDtsGenerator()

    val toolDescriptors = listOf(
      ToolDescriptor(
        name = "noop",
        description = "Does nothing",
        requiredParameters = emptyList(),
        optionalParameters = emptyList(),
      ),
    )

    val outputPath = generator.generateForTrailmap(trailmapDir, toolDescriptors, emptyList())
    val firstMtime = Files.getLastModifiedTime(outputPath)

    Thread.sleep(1_100)

    val outputPathAgain = generator.generateForTrailmap(trailmapDir, toolDescriptors, emptyList())
    val secondMtime = Files.getLastModifiedTime(outputPathAgain)

    assertEquals(outputPath, outputPathAgain)
    assertEquals(
      firstMtime,
      secondMtime,
      "Idempotent generator should not rewrite on identical inputs (firstMtime=$firstMtime, secondMtime=$secondMtime)",
    )
  }

  @Test
  fun `each trailmap produces its own client_d_ts in its own trailmapDir`() {
    // Slicing rule: per-trailmap codegen lands at `<trailmapDir>/tools/trailblaze-client.d.ts`,
    // so two trailmap dirs receive two distinct files. Tests that one generator instance can
    // serve many trailmaps.
    val alphaTrailmapDir = newTrailmapDir()
    val betaTrailmapDir = newTrailmapDir()
    val generator = WorkspaceClientDtsGenerator()

    val alphaScripted = listOf(
      TrailmapScriptedToolFile(
        script = "./tools/alpha_login.ts",
        name = "alpha_login",
        description = "Sign into Alpha.",
        inputSchema = mapOf("email" to ScriptedToolProperty(type = "string", required = true)),
      ),
    )
    val betaScripted = listOf(
      TrailmapScriptedToolFile(
        script = "./tools/beta_login.ts",
        name = "beta_login",
        description = "Sign into Beta.",
        inputSchema = mapOf("merchantId" to ScriptedToolProperty(type = "string", required = true)),
      ),
    )

    val alphaPath = generator.generateForTrailmap(alphaTrailmapDir, emptyList(), alphaScripted)
    val betaPath = generator.generateForTrailmap(betaTrailmapDir, emptyList(), betaScripted)

    assertTrue(Files.isRegularFile(alphaPath))
    assertTrue(Files.isRegularFile(betaPath))
    assertEquals("trailblaze-client.d.ts", alphaPath.fileName.toString())
    assertEquals("trailblaze-client.d.ts", betaPath.fileName.toString())
    // Different parents — one per trailmap.
    assertTrue(alphaPath.parent.startsWith(alphaTrailmapDir))
    assertTrue(betaPath.parent.startsWith(betaTrailmapDir))

    val alphaRendered = Files.readString(alphaPath)
    val betaRendered = Files.readString(betaPath)
    assertTrue("alpha file should contain alpha_login: $alphaRendered") { alphaRendered.contains("alpha_login:") }
    assertFalse("alpha file should NOT contain beta_login: $alphaRendered") { alphaRendered.contains("beta_login:") }
    assertTrue("beta file should contain beta_login: $betaRendered") { betaRendered.contains("beta_login:") }
    assertFalse("beta file should NOT contain alpha_login: $betaRendered") { betaRendered.contains("alpha_login:") }
  }

  @Test
  fun `generateForTrailmapFromResolved walks JSON Schema inputSchema from InlineScriptToolConfig`() {
    val trailmapDir = newTrailmapDir()
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

    val outputPath = generator.generateForTrailmapFromResolved(
      trailmapDir = trailmapDir,
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
  fun `generateForTrailmapFromResolved handles empty inputSchema as Record empty-string never`() {
    val trailmapDir = newTrailmapDir()
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

    val outputPath = generator.generateForTrailmapFromResolved(trailmapDir, emptyList(), resolvedTools)
    val rendered = Files.readString(outputPath)

    // No-arg tool wraps to `{ args: Record<string, never>; result: string }`. Asserting
    // the full block (with newlines + indents) catches regressions that loose substring
    // matching would silently allow — e.g. dropping the wrapping braces or unwrapping the
    // `args:` half.
    val expectedBlock = """
      |    clock_clearAlarms: {
      |      args: Record<string, never>;
      |      result: string;
      |    };
    """.trimMargin()
    assertTrue("expected full block in rendered: $rendered") { rendered.contains(expectedBlock) }
  }

  @Test
  fun `generateForTrailmapFromResolved tolerates non-object property entry without crashing`() {
    // A malformed `inputSchema.properties` entry used to crash codegen via `propEl.jsonObject`'s
    // `IllegalArgumentException`. Now degrades gracefully: the bad property is skipped.
    val trailmapDir = newTrailmapDir()
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

    val outputPath = generator.generateForTrailmapFromResolved(trailmapDir, emptyList(), resolvedTools)
    val rendered = Files.readString(outputPath)

    assertTrue("good param should render: $rendered") { rendered.contains("good: string;") }
    assertFalse("bad param should be skipped: $rendered") { rendered.contains("bad:") }
  }

  @Test
  fun `typed tool override produces real args and result types from analyzer schemas`() {
    // Mirrors the shape `ScriptedToolDefinitionAnalyzer` produces for the wikipedia_typed_demo:
    //   inputSchema = TypedDemoInput { message: string; prefix?: string }
    //   outputSchema = TypedDemoOutput { formatted: string; inputLength: number }
    // With this override in place, the emitted entry's `args:` and `result:` halves should
    // carry the full typed surface — including field-level TSDoc — instead of the YAML
    // decomposition or the today-default `result: string`.
    val trailmapDir = newTrailmapDir()
    val generator = WorkspaceClientDtsGenerator()
    val resolvedTools = listOf(
      InlineScriptToolConfig(
        script = "./tools/wikipedia_typed_demo.ts",
        name = "wikipedia_typed_demo",
        description = "(YAML-derived description should be overridden by the analyzer's TSDoc.)",
        inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
      ),
    )
    val overrides = mapOf(
      "wikipedia_typed_demo" to WorkspaceClientDtsGenerator.TypedToolOverride(
        description = "Typed-authoring demo: formats the input message with an optional prefix.",
        inputSchema = buildJsonObject {
          put("type", JsonPrimitive("object"))
          put(
            "properties",
            buildJsonObject {
              put(
                "message",
                buildJsonObject {
                  put("type", JsonPrimitive("string"))
                  put("description", JsonPrimitive("The message to format."))
                },
              )
              put(
                "prefix",
                buildJsonObject {
                  put("type", JsonPrimitive("string"))
                  put("description", JsonPrimitive("Optional prefix prepended to the formatted message."))
                },
              )
            },
          )
          put("required", buildJsonArray { add(JsonPrimitive("message")) })
        },
        outputSchema = buildJsonObject {
          put("type", JsonPrimitive("object"))
          put(
            "properties",
            buildJsonObject {
              put(
                "formatted",
                buildJsonObject {
                  put("type", JsonPrimitive("string"))
                  put("description", JsonPrimitive("The formatted message."))
                },
              )
              put(
                "inputLength",
                buildJsonObject {
                  put("type", JsonPrimitive("number"))
                  put("description", JsonPrimitive("Length of the original input message in code units."))
                },
              )
            },
          )
          put("required", buildJsonArray { add(JsonPrimitive("formatted")); add(JsonPrimitive("inputLength")) })
        },
      ),
    )

    val outputPath = generator.generateForTrailmapFromResolved(
      trailmapDir = trailmapDir,
      toolDescriptors = emptyList(),
      scriptedTools = resolvedTools,
      typedToolOverrides = overrides,
    )
    val rendered = Files.readString(outputPath)

    // Analyzer TSDoc on the exported const wins over the YAML-derived description.
    assertTrue("expected analyzer TSDoc in rendered: $rendered") {
      rendered.contains("Typed-authoring demo: formats the input message with an optional prefix.")
    }
    assertFalse("YAML description should be replaced: $rendered") {
      rendered.contains("YAML-derived description should be overridden")
    }
    // Args carry the input shape AND field-level TSDoc (verifies the JSDoc round-trip
    // the cross-trailmap IDE-hover use case depends on).
    assertTrue("expected typed args block: $rendered") { rendered.contains("message: string;") }
    assertTrue("expected optional prefix in args: $rendered") { rendered.contains("prefix?: string;") }
    assertTrue("expected field-level TSDoc on message: $rendered") {
      rendered.contains("/** The message to format. */")
    }
    // Result is the real `TypedDemoOutput` shape — NOT the today-default `string`.
    assertFalse("default `result: string;` must not appear: $rendered") {
      rendered.contains("result: string;")
    }
    assertTrue("expected typed result shape with formatted: $rendered") {
      rendered.contains("formatted: string;")
    }
    assertTrue("expected typed result shape with inputLength: $rendered") {
      rendered.contains("inputLength: number;")
    }
  }

  @Test
  fun `tool without analyzer override falls back to YAML-derived flat decomposition`() {
    // The legacy/coexistence guarantee: a tool whose author hasn't migrated to
    // `trailblaze.tool<I, O>({ handler })` keeps emitting the today-shape (YAML's flat
    // params + `result: string`). The analyzer-override path is strictly opt-in per tool.
    val trailmapDir = newTrailmapDir()
    val generator = WorkspaceClientDtsGenerator()
    val resolvedTools = listOf(
      InlineScriptToolConfig(
        script = "./tools/legacy.ts",
        name = "legacy_tool",
        description = "Untyped legacy tool.",
        inputSchema = buildJsonObject {
          put("type", JsonPrimitive("object"))
          put(
            "properties",
            buildJsonObject {
              put("query", buildJsonObject { put("type", JsonPrimitive("string")) })
            },
          )
          put("required", buildJsonArray { add(JsonPrimitive("query")) })
        },
      ),
    )

    val outputPath = generator.generateForTrailmapFromResolved(
      trailmapDir = trailmapDir,
      toolDescriptors = emptyList(),
      scriptedTools = resolvedTools,
      typedToolOverrides = emptyMap(),
    )
    val rendered = Files.readString(outputPath)

    assertTrue("expected YAML-derived flat args block: $rendered") {
      rendered.contains("legacy_tool: {") && rendered.contains("query: string;")
    }
    assertTrue("expected today-default result string: $rendered") { rendered.contains("result: string;") }
  }

  @Test
  fun `frameworkMetadataByName emits @trailblaze tags above matching tool entries`() {
    // End-to-end: a metadata map keyed by tool name flows through both generate methods
    // (the TrailmapScriptedToolFile path and the InlineScriptToolConfig path) to the
    // renderer's tag-emission, and tools not present in the map remain tag-free.
    val trailmapDir = newTrailmapDir()
    val generator = WorkspaceClientDtsGenerator()

    val toolDescriptors = listOf(
      ToolDescriptor(
        name = "android_adbShell",
        description = "Run an adb shell command.",
        requiredParameters = listOf(
          ToolParameterDescriptor("command", "Shell command", ToolParameterType.String),
        ),
      ),
      ToolDescriptor(
        name = "tap",
        description = "Tap an element.",
        requiredParameters = listOf(
          ToolParameterDescriptor("ref", "Element ref", ToolParameterType.String),
        ),
      ),
    )
    val metadataByName = mapOf(
      "android_adbShell" to ToolFrameworkMetadata(requiresHost = true),
      // `tap` is intentionally absent from the map — it should render with no tags.
    )

    val outputPath = generator.generateForTrailmap(
      trailmapDir = trailmapDir,
      toolDescriptors = toolDescriptors,
      scriptedTools = emptyList(),
      frameworkMetadataByName = metadataByName,
    )
    val rendered = Files.readString(outputPath)

    assertTrue("expected @trailblazeHostOnly above android_adbShell: $rendered") {
      rendered.contains("     * @trailblazeHostOnly\n     */\n    android_adbShell:")
    }
    // `tap` is not in the metadata map → no tags above it. The `tap:` block's JSDoc
    // closes immediately after the description with no `@trailblaze` tag in between.
    assertFalse("tap entry must not carry framework tags: $rendered") {
      val tapStart = rendered.indexOf("    tap: {")
      val tapBlockStart = rendered.lastIndexOf("/**", tapStart)
      rendered.substring(tapBlockStart, tapStart).contains("@trailblaze")
    }
  }

  @Test
  fun `frameworkMetadataByName flows through generateForTrailmapFromResolved`() {
    // Symmetric coverage for the resolved/InlineScriptToolConfig path so the wiring is
    // pinned on both API entry points.
    val trailmapDir = newTrailmapDir()
    val generator = WorkspaceClientDtsGenerator()

    val toolDescriptors = listOf(
      ToolDescriptor(
        name = "android_adbShell",
        description = "Run an adb shell command.",
        requiredParameters = emptyList(),
      ),
    )
    val metadataByName = mapOf(
      "android_adbShell" to ToolFrameworkMetadata(requiresHost = true),
    )

    val outputPath = generator.generateForTrailmapFromResolved(
      trailmapDir = trailmapDir,
      toolDescriptors = toolDescriptors,
      scriptedTools = emptyList(),
      typedToolOverrides = emptyMap(),
      frameworkMetadataByName = metadataByName,
    )
    val rendered = Files.readString(outputPath)

    assertTrue("expected @trailblazeHostOnly above android_adbShell: $rendered") {
      rendered.contains("     * @trailblazeHostOnly\n     */\n    android_adbShell:")
    }
  }

  // ---- helpers ----------------------------------------------------------------------------

  private fun newTrailmapDir(): Path {
    val dir = createTempDirectory("client-dts-trailmap-test").toFile()
    tempDirs += dir
    return dir.toPath()
  }
}
