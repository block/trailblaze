package xyz.block.trailblaze.scripting.subprocess

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.scripting.bundle.SdkBundleResource
import java.io.File

/**
 * Turns target-level inline script-tool declarations into temporary MCP server wrapper files that
 * can run on the existing host subprocess path.
 *
 * Each generated wrapper:
 * 1. Side-effect imports the committed `@trailblaze/scripting` bundle shipped in-tree.
 * 2. Imports the author file and resolves the named export matching the YAML-authored tool name.
 * 3. Registers one MCP tool using the YAML-authored name / description / inputSchema.
 * 4. Normalizes the author's return value into MCP text content when they return a plain string.
 */
object InlineScriptToolServerSynthesizer {
  private val JSON = Json { prettyPrint = false }

  fun synthesize(
    tools: List<InlineScriptToolConfig>,
    outputDir: File,
    pathAnchor: File = File(System.getProperty("user.dir")),
    sdkBundleFile: File = defaultSdkBundleFile(),
  ): List<McpServerConfig> {
    if (tools.isEmpty()) return emptyList()
    require(sdkBundleFile.isFile) {
      "trailblaze inline-tool SDK bundle not found at ${sdkBundleFile.absolutePath}"
    }
    outputDir.mkdirs()

    val usedBaseNames = mutableMapOf<String, Int>()
    return tools.map { tool ->
      val authorFile = McpSubprocessSpawner.resolveScriptPath(tool.script, anchor = pathAnchor)
      val base = sanitize(tool.name)
      val collisionIndex = usedBaseNames.getOrDefault(base, 0)
      usedBaseNames[base] = collisionIndex + 1
      val suffix = if (collisionIndex == 0) "" else "_$collisionIndex"
      val wrapperFile = File(outputDir, "inline_tool_${base}$suffix.mjs")
      wrapperFile.writeText(
        renderWrapperScript(
          tool = tool,
          authorFile = authorFile,
          sdkBundleFile = sdkBundleFile,
        ),
      )
      McpServerConfig(script = wrapperFile.absolutePath)
    }
  }

  internal fun renderWrapperScript(
    tool: InlineScriptToolConfig,
    authorFile: File,
    sdkBundleFile: File = defaultSdkBundleFile(),
  ): String {
    val toolNameLiteral = tool.name.asJsStringLiteral()
    val descriptionLiteral = tool.description?.asJsStringLiteral() ?: "undefined"
    val resolvedMeta = mergeRequiresHost(tool.meta, tool.requiresHost)
    val metaLiteral = resolvedMeta?.let { JSON.encodeToString(JsonObject.serializer(), it) } ?: "undefined"
    val inputSchemaLiteral = JSON.encodeToString(JsonObject.serializer(), tool.inputSchema)
    val authorModulePath = authorFile.absolutePath.asJsStringLiteral()
    val sdkBundlePath = sdkBundleFile.absolutePath.asJsStringLiteral()
    val serverNameLiteral = "trailblaze-inline-script-${tool.name}".asJsStringLiteral()

    return """
      import { pathToFileURL } from "node:url";

      await import(pathToFileURL($sdkBundlePath).href);

      const trailblaze = globalThis.trailblaze;
      const z = globalThis.z;
      if (!trailblaze || typeof trailblaze.tool !== "function" || typeof trailblaze.run !== "function") {
        throw new Error("Trailblaze inline tool wrapper couldn't find globalThis.trailblaze after loading the SDK bundle.");
      }
      if (!z) {
        throw new Error("Trailblaze inline tool wrapper couldn't find globalThis.z after loading the SDK bundle.");
      }

      const authorModule = await import(pathToFileURL($authorModulePath).href);
      const authorHandler = authorModule[$toolNameLiteral];
      if (typeof authorHandler !== "function") {
        throw new Error(
          `Trailblaze inline tool author file must export a function named "${'$'}{$toolNameLiteral}".`,
        );
      }

      trailblaze.tool(
        $toolNameLiteral,
        {
          description: $descriptionLiteral,
          _meta: $metaLiteral,
          inputSchema: jsonSchemaToInputSchema($inputSchemaLiteral),
        },
        async (args, ctx, client) => normalizeInlineToolResult(await authorHandler(args, ctx, client)),
      );

      await trailblaze.run({ name: $serverNameLiteral });

      function normalizeInlineToolResult(result) {
        if (typeof result === "string") {
          return { content: [{ type: "text", text: result }] };
        }
        if (typeof result === "number" || typeof result === "boolean") {
          return { content: [{ type: "text", text: String(result) }] };
        }
        if (result == null) {
          return { content: [{ type: "text", text: "" }] };
        }
        if (typeof result === "object" && Array.isArray(result.content)) {
          return result;
        }
        return { content: [{ type: "text", text: JSON.stringify(result) }] };
      }

      function jsonSchemaToInputSchema(schema) {
        if (!schema || Object.keys(schema).length === 0) {
          return {};
        }
        if (schema.type !== "object") {
          throw new Error("Inline tool inputSchema must have a root JSON Schema type of 'object'.");
        }
        const properties = isPlainObject(schema.properties) ? schema.properties : {};
        const required = new Set(Array.isArray(schema.required) ? schema.required : []);
        const shape = {};
        for (const [key, value] of Object.entries(properties)) {
          shape[key] = jsonSchemaPropertyToZod(key, value, required.has(key));
        }
        return shape;
      }

      function jsonSchemaPropertyToZod(key, schema, isRequired) {
        if (!isPlainObject(schema)) {
          throw new Error(`Inline tool inputSchema property "${'$'}{key}" must be an object.`);
        }
        let base;
        if (Array.isArray(schema.enum) && schema.enum.length > 0) {
          if (!schema.enum.every((value) => typeof value === "string")) {
            throw new Error(`Inline tool inputSchema property "${'$'}{key}" only supports string enums.`);
          }
          base = z.enum(schema.enum);
        } else {
          switch (schema.type) {
            case "string":
              base = z.string();
              break;
            case "number":
              base = z.number();
              break;
            case "integer":
              base = z.number().int();
              break;
            case "boolean":
              base = z.boolean();
              break;
            case "array":
              base = z.array(
                schema.items ? jsonSchemaPropertyToZod(`${'$'}{key}[]`, schema.items, true) : z.any(),
              );
              break;
            case "object":
              base = z.object(jsonSchemaToInputSchema(schema)).passthrough();
              break;
            default:
              throw new Error(
                `Inline tool inputSchema property "${'$'}{key}" uses unsupported JSON Schema type "${'$'}{schema.type}".`,
              );
          }
        }
        return isRequired ? base : base.optional();
      }

      function isPlainObject(value) {
        return typeof value === "object" && value !== null && !Array.isArray(value);
      }
    """.trimIndent() + "\n"
  }

  private fun mergeRequiresHost(meta: JsonObject?, requiresHost: Boolean): JsonObject? {
    if (!requiresHost) return meta
    val merged = (meta?.toMutableMap() ?: mutableMapOf())
    merged["trailblaze/requiresHost"] = JsonPrimitive(true)
    return JsonObject(merged)
  }

  private fun sanitize(name: String): String = buildString {
    for (ch in name) {
      append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_')
    }
  }.ifBlank { "tool" }

  /**
   * Default SDK bundle that the synthesized wrapper imports via `pathToFileURL(...)`.
   *
   * Delegates to [SdkBundleResource.extractToFile] in `:trailblaze-scripting-bundle` — the
   * module that owns the bundle resource and the safe extract-to-temp-file logic. Callers
   * that want a non-default bundle (test fixtures, future packaging variants) pass
   * `sdkBundleFile` explicitly.
   */
  private fun defaultSdkBundleFile(): File = SdkBundleResource.extractToFile()

  private fun String.asJsStringLiteral(): String = JSON.encodeToString(this)
}
