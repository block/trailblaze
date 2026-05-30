package xyz.block.trailblaze.docs

import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.toolcalls.ResolvedTargetIdempotentWrite
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer.Header
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer.ToolDetail
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import xyz.block.trailblaze.util.Console
import java.io.File
import kotlin.reflect.KClass

/**
 * Generates per-tool reference docs for the framework catalog under the
 * `docs/generated/functions/custom/` directory of this repo (one
 * `<toolName>.md` per tool). The per-tool body is rendered
 * by [ResolvedTargetToolDetailRenderer] — the same renderer the workspace `trailblaze check`
 * command uses for per-target sidecars — so authoring one fix to that renderer (e.g. adding
 * a new metadata field) flows to every per-tool doc surface in the project. If a tool's
 * metadata has a gap, every consumer of the renderer surfaces the same gap, which is the
 * point of routing both pipelines through one entry point.
 *
 * The framework variant emits a "Regenerate with: ./gradlew :docs:generator:run" header so
 * the file matches the canonical regeneration command for this directory; the orphan-banner
 * first line is identical to the sidecar's so any future cleanup logic that recognizes
 * emitter-owned files works across both pipelines without forking.
 */
class DocsGenerator(
    private val generatedFunctionsDocsDir: File,
) {

    /**
     * Renders [toolKClass] to its `custom/<name>.md` file via the shared renderer. Returns
     * the filename written (e.g. `tapOnPoint.md`) so [generate] can track which pages are
     * current and prune orphans, or null if the tool was skipped by the surface gate or
     * couldn't be classified (e.g. a class on the classpath that's missing the
     * `@TrailblazeToolClass` annotation — a configuration bug worth logging but not worth
     * aborting the rest of the docs build over). Uses [ResolvedTargetIdempotentWrite] so
     * unchanged content doesn't churn mtimes.
     */
    fun createPageForCommand(
        toolKClass: KClass<out TrailblazeTool>,
    ): String? {
        // Surface gate: tools annotated `surfaceToLlm = false` (e.g. `setActiveToolSets`)
        // intentionally don't appear in the LLM-facing catalog. They aren't part of the
        // documented public surface either — skip them so the framework docs match what
        // an end user could realistically pick.
        //
        // Defensive `runCatching`: a class on the classpath that's missing the
        // `@TrailblazeToolClass` annotation, or whose annotation fails to instantiate,
        // shouldn't kill the entire OSS docs build. `trailblazeToolClassAnnotation()`
        // throws `error()` on missing annotation. Log + skip so one misconfigured class
        // doesn't take down the framework's entire reference docs. Mirrors the same
        // resilience pattern in `ResolvedTargetReportEmitter`'s YAML-discovery path.
        val annotation = runCatching { toolKClass.trailblazeToolClassAnnotation() }
            .onFailure { e ->
                Console.error(
                    "DocsGenerator: skipping ${toolKClass.qualifiedName ?: toolKClass.simpleName} " +
                        "— failed to read @TrailblazeToolClass annotation " +
                        "(${e::class.simpleName}: ${e.message}). The framework reference docs " +
                        "won't include this class.",
                )
            }
            .getOrNull()
            ?: return null
        if (!annotation.surfaceToLlm) return null
        val name = toolKClass.toolName().toolName
        val fileName = "$name.md"
        val detail = ToolDetail.ClassBacked(name = name, kclass = toolKClass)
        val file = File(generatedFunctionsDocsDir, "custom/$fileName")
        ResolvedTargetIdempotentWrite.writeIfChanged(
            file,
            ResolvedTargetToolDetailRenderer.renderMarkdown(detail = detail, header = FRAMEWORK_HEADER),
        )
        return fileName
    }

    fun generate() {
        val customDir = File(generatedFunctionsDocsDir, "custom").apply { mkdirs() }
        val keepNames = mutableSetOf<String>()

        // Generator commits canonical bundled-classpath baselines — pin discovery to the
        // classpath-only source so a developer running this from a workspace doesn't fold
        // their `trails/config/` tools into the committed output.
        val classpathOnly = ClasspathConfigResourceSource
        ToolYamlLoader.discoverAndLoadAll(classpathOnly).values
            .forEach { toolClass: KClass<out TrailblazeTool> ->
                createPageForCommand(toolClass)?.let { keepNames += it }
            }
        ToolYamlLoader.discoverYamlDefinedTools(classpathOnly).values
            .forEach { config: ToolYamlConfig ->
                createPageForYamlDefinedTool(config).let { keepNames += it }
            }
        // Orphan-prune emitter-owned pages whose tool no longer exists. Recognizes the
        // shared `GENERATED_BANNER` first line — hand-authored files in the same directory
        // (no banner) are left untouched.
        customDir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name !in keepNames }
            ?.forEach { file ->
                val firstLine = runCatching { file.bufferedReader().use { it.readLine().orEmpty() } }.getOrNull()
                if (firstLine == ResolvedTargetToolDetailRenderer.GENERATED_BANNER) file.delete()
            }
    }

    fun createPageForYamlDefinedTool(config: ToolYamlConfig): String {
        val fileName = "${config.id}.md"
        val detail = ToolDetail.YamlDefined(name = config.id, config = config)
        val file = File(generatedFunctionsDocsDir, "custom/$fileName")
        ResolvedTargetIdempotentWrite.writeIfChanged(
            file,
            ResolvedTargetToolDetailRenderer.renderMarkdown(detail = detail, header = FRAMEWORK_HEADER),
        )
        return fileName
    }

    companion object {
        private val FRAMEWORK_HEADER = Header(
            origin = "Trailblaze framework tool reference",
            regenerateHint = "Regenerate with: ./gradlew :docs:generator:run",
        )

        val THIS_DOC_IS_GENERATED_MESSAGE = """
<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION
      """.trimIndent()
    }
}
