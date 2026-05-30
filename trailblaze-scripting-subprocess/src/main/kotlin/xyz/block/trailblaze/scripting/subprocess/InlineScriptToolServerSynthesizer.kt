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
 * **One wrapper per unique author script.** Multiple `InlineScriptToolConfig` entries sharing the
 * same `script:` path (the multi-tool authoring shape — one TS file exporting N named functions,
 * declared in YAML as one descriptor with `tools: [...]`) are grouped into a single wrapper that
 * imports the file once and registers all N tools against the same MCP server. This gives the
 * framework's "you author functions, we hide the MCP server" contract teeth: a group of 8 tools
 * costs one subprocess + one module load, not 8 of each.
 *
 * Each generated wrapper:
 * 1. Side-effect imports the committed `@trailblaze/scripting` bundle shipped in-tree.
 * 2. Imports the author file ONCE and looks up each registered tool's named export.
 * 3. Registers one MCP tool per entry, all on the same `trailblaze.tool(...)` server,
 *    using the YAML-authored name / description / inputSchema.
 * 4. Normalizes each author's return value into MCP text content when they return a plain string.
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

    // Group tools by resolved absolute author-file path. Tools that resolve to the same on-disk
    // module become one wrapper that loads the module once and registers all of them. The map
    // preserves insertion order (`linkedMapOf`) so the generated filename is stable across runs
    // even when entry order varies — important because the wrapper filename ends up in session
    // logs and a stable name is friendlier when comparing two CI builds.
    val grouped = linkedMapOf<File, MutableList<InlineScriptToolConfig>>()
    for (tool in tools) {
      val authorFile = McpSubprocessSpawner.resolveScriptPath(tool.script, anchor = pathAnchor)
      grouped.getOrPut(authorFile) { mutableListOf() } += tool
    }

    val usedBaseNames = mutableMapOf<String, Int>()
    return grouped.entries.map { (authorFile, group) ->
      // Validate per-group that no two tools share a name. Cross-group collisions are detected
      // later in `toolRepo.addDynamicTools` against the full registry; this catch is for the
      // specific case where one descriptor's `tools:` list contains a duplicate, which would
      // otherwise surface as a confusing "registerTool: name already registered" error inside
      // the spawned subprocess. Catching at synthesis time gives a descriptor-aware message.
      val seenNames = mutableSetOf<String>()
      for (t in group) {
        require(seenNames.add(t.name)) {
          "Inline scripted-tool group for script '${t.script}' contains duplicate tool name '${t.name}'. " +
            "Each entry under `tools:` must have a unique `name:`."
        }
      }
      val base = sanitize(group.first().name) + if (group.size > 1) "_group" else ""
      val collisionIndex = usedBaseNames.getOrDefault(base, 0)
      usedBaseNames[base] = collisionIndex + 1
      val suffix = if (collisionIndex == 0) "" else "_$collisionIndex"
      val wrapperFile = File(outputDir, "inline_tool_${base}$suffix.mjs")
      wrapperFile.writeText(
        renderWrapperScript(
          tools = group,
          authorFile = authorFile,
          sdkBundleFile = sdkBundleFile,
        ),
      )
      McpServerConfig(script = wrapperFile.absolutePath)
    }
  }

  /**
   * Convenience overload preserved for callers and tests written against the original
   * single-tool API. Delegates to the multi-tool-aware [renderWrapperScript] with a one-element
   * list so the generated wrapper structure is identical in the N=1 case.
   */
  internal fun renderWrapperScript(
    tool: InlineScriptToolConfig,
    authorFile: File,
    sdkBundleFile: File = defaultSdkBundleFile(),
  ): String = renderWrapperScript(listOf(tool), authorFile, sdkBundleFile)

  internal fun renderWrapperScript(
    tools: List<InlineScriptToolConfig>,
    authorFile: File,
    sdkBundleFile: File = defaultSdkBundleFile(),
  ): String {
    require(tools.isNotEmpty()) { "renderWrapperScript() requires at least one tool" }
    val authorModulePath = authorFile.absolutePath.asJsStringLiteral()
    val sdkBundlePath = sdkBundleFile.absolutePath.asJsStringLiteral()
    // Server name picks the first tool's name as the human-readable identifier; for a multi-tool
    // group, append "+N" so a session log line that mentions the server name surfaces the size of
    // the group without listing every entry. Matches what an author would write in
    // `trailblaze.run({ name: ... })` themselves.
    val serverIdentity = if (tools.size == 1) {
      "trailblaze-inline-script-${tools.first().name}"
    } else {
      "trailblaze-inline-script-${tools.first().name}+${tools.size - 1}"
    }
    val serverNameLiteral = serverIdentity.asJsStringLiteral()

    // Per-tool registration blocks. Each block resolves its named export from `authorModule`
    // (validated at registration time so a missing export fails at spawn, not at first call)
    // and registers the tool on the shared `trailblaze` server. The `authorModule` import is
    // emitted ONCE above the registration blocks regardless of group size.
    val toolRegistrations = tools.joinToString("\n\n") { tool ->
      val toolNameLiteral = tool.name.asJsStringLiteral()
      val descriptionLiteral = tool.description?.asJsStringLiteral() ?: "undefined"
      val resolvedMeta = mergeRequiresHost(tool.meta, tool.requiresHost)
      val metaLiteral = resolvedMeta?.let { JSON.encodeToString(JsonObject.serializer(), it) } ?: "undefined"
      val inputSchemaLiteral = JSON.encodeToString(JsonObject.serializer(), tool.inputSchema)
      """
      {
        const handler_${sanitize(tool.name)} = authorModule[$toolNameLiteral];
        if (typeof handler_${sanitize(tool.name)} !== "function") {
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
          async (args, ctx, client) => normalizeInlineToolResult(await handler_${sanitize(tool.name)}(args, ctx, client)),
        );
      }
      """.trimIndent()
    }

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

      $toolRegistrations

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
          // Author hand-rolled an MCP envelope (legacy / advanced path) — pass through
          // unchanged. If they want structured content they populate it themselves.
          return result;
        }
        // Typed-overload return: the handler gave us a structured TS value directly. Send
        // it BOTH as the JSON-stringified text content (so legacy/raw-MCP consumers that
        // only read text get a value) AND as structuredContent (so the Trailblaze SDK's
        // client proxy unwraps it as the typed `result` declared in TrailblazeToolMap).
        // Without the structuredContent half, a typed-overload tool's caller silently
        // receives the JSON STRING cast as the structured type — accessing `.field` on it
        // returns undefined. Closes the typed-result-on-callsite chain end-to-end.
        return {
          content: [{ type: "text", text: JSON.stringify(result) }],
          structuredContent: result,
        };
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
