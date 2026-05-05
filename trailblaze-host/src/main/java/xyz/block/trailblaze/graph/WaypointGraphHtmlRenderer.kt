package xyz.block.trailblaze.graph

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Turns a [WaypointGraphData] into a complete, self-contained HTML page that renders
 * the navigation graph via React Flow.
 *
 * ## How the page is assembled
 *
 * The template at `resources/xyz/block/trailblaze/graph/waypoint-graph-template.html`
 * is a static HTML file with one substitution point: a marked-up `null` literal that
 * sits between `JSON_DATA_PLACEHOLDER_BEGIN` and `JSON_DATA_PLACEHOLDER_END` markers.
 * We swap that `null` for the JSON-encoded graph data and ship the result as-is.
 *
 * No string interpolation in the template means:
 *  - The template can be edited and previewed in any browser (the `null` graph
 *    triggers an "empty" branch that renders gracefully) without first running through
 *    a build step.
 *  - There's no risk of accidental escaping mistakes inside Kotlin string literals —
 *    the template is plain text; only one well-defined region is replaced.
 *  - The marker pair is a pure JS comment, so even if the substitution somehow fails
 *    the page still loads (the empty-state shows up instead of a syntax error).
 *
 * ## Output is a single self-contained file
 *
 * The CLI's `--out` flag and the daemon endpoint both produce identical output —
 * usable by anyone who has the file, regardless of whether they have the daemon
 * running or even the trailblaze repo cloned. Screenshots are inlined as data URIs
 * (see [WaypointGraphBuilder]), and the React Flow / dagre dependencies load from
 * esm.sh on first view (cached after that). No build pipeline, no companion files.
 */
object WaypointGraphHtmlRenderer {

  /**
   * `kotlinx.serialization` instance configured for embedding in HTML. We don't pretty-
   * print to keep the page small (dropping ~20% of bytes for typical graphs) and
   * `ignoreUnknownKeys` doesn't apply on the encode path. `prettyPrint = false` is the
   * default; making it explicit so a future "give me a debug-friendly version" override
   * is one flag away.
   */
  private val json = Json {
    prettyPrint = false
    encodeDefaults = true
  }

  private val template: String by lazy { loadTemplate() }

  /**
   * Renders the page. Returns the full HTML as a string — caller decides whether to
   * write it to a file (CLI) or stream it as a Ktor response (endpoint).
   */
  fun render(data: WaypointGraphData): String {
    val payload = json.encodeToString(data)
    return template.replace(
      PLACEHOLDER_REGEX,
      // String.replace(Regex, String) treats `$` as a back-reference. Use the lambda
      // form so the JSON payload is inserted verbatim, with no group expansion drama.
      transform = { _ -> payload },
    )
  }

  /**
   * Loaded once per JVM. The template is a small static file (~14 KB), but caching
   * keeps the per-render cost bounded by JSON encoding alone — a hot path if the
   * desktop server endpoint sees rapid refreshes.
   *
   * `getResourceAsStream` uses the class's classloader, which is the same loader that
   * sees the resource directory we control — so this works regardless of how the JAR
   * is packaged downstream.
   */
  private fun loadTemplate(): String {
    val resource = WaypointGraphHtmlRenderer::class.java
      .getResourceAsStream(TEMPLATE_RESOURCE_PATH)
      ?: error(
        "Waypoint graph template not found at classpath:$TEMPLATE_RESOURCE_PATH. " +
          "This is a packaging bug — the template should ship alongside the renderer.",
      )
    return resource.bufferedReader(Charsets.UTF_8).use { it.readText() }
  }

  /**
   * Placeholder region inside the template, written as a JS comment so the unsubstit-
   * uted template still parses and renders the empty state. The `(?s)` flag is for
   * dotall — the body might span lines after a future template edit, but defensively
   * we don't depend on that today.
   */
  private val PLACEHOLDER_REGEX = Regex(
    "/\\*JSON_DATA_PLACEHOLDER_BEGIN\\*/.*?/\\*JSON_DATA_PLACEHOLDER_END\\*/",
    RegexOption.DOT_MATCHES_ALL,
  )

  /**
   * Resource path is relative to the renderer's class — the leading `/` makes it
   * absolute under the classloader root. Keeping the template colocated with this
   * file's package means moving either one moves the other together.
   */
  private const val TEMPLATE_RESOURCE_PATH = "/xyz/block/trailblaze/graph/waypoint-graph-template.html"
}
