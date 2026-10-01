package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Frame
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * What a replay capture adds beyond what the live capture has: a box on each text run of its own,
 * and the content of the page's iframes as trees of their own. Both are for reading the page —
 * the strings on it and where they sat — and neither touches the nodes or selector fields a
 * recorded selector resolves against.
 */
internal object PlaywrightReplayTree {

  /**
   * Gives each text run the box of the DOM text it came from. The snapshot boxes elements only, so
   * a run otherwise takes its nearest element's box, which is the whole page when the element
   * around it (a `<label>`, a `<span>`) isn't in the accessibility tree.
   *
   * Runs and DOM text nodes are both in reading order, so each run matches the next DOM text
   * whose whitespace-normalized concatenation equals it, and the match must sit inside the box the
   * run already had. A run with no such match keeps that box.
   */
  fun locateTextRuns(tree: TrailblazeNode, frame: Frame, offsetX: Int = 0, offsetY: Int = 0): TrailblazeNode {
    val texts = captureTextBoxes(frame, offsetX, offsetY)
    if (texts.isEmpty()) return tree
    var cursor = 0

    fun locate(node: TrailblazeNode): TrailblazeNode {
      val detail = node.driverDetail as? DriverNodeDetail.Web
      if (detail?.ariaRole == "text" && detail.ariaName != null) {
        val target = normalize(PlaywrightTrailblazeNodeMapper.yamlScalar(detail.ariaName!!))
        val match = findRun(texts, cursor, target, node.bounds)
        if (match != null) {
          cursor = match.second + 1
          return node.copy(bounds = match.first)
        }
        return node
      }
      // An iframe's content is its own document; its runs are matched against that frame's text.
      if (detail?.ariaRole == "iframe") return node
      return node.copy(children = node.children.map(::locate))
    }
    return locate(tree)
  }

  /**
   * Each visible iframe's own boxed snapshot, boxes shifted into the page's viewport, down to
   * [MAX_FRAME_DEPTH] levels of nesting, in document order. Kept apart from the page tree because
   * a recorded selector resolves against the whole tree, and a frame's "Continue" button would
   * make the page's ambiguous. Each root carries its frame's URL; ids are the frame's own.
   */
  fun captureFrames(tree: TrailblazeNode, page: Page): List<TrailblazeNode> {
    val out = mutableListOf<TrailblazeNode>()
    collectFrames(tree, page.mainFrame(), 0, 0, 1, out)
    return out
  }

  private fun collectFrames(
    tree: TrailblazeNode,
    frame: Frame,
    offsetX: Int,
    offsetY: Int,
    depth: Int,
    out: MutableList<TrailblazeNode>,
  ) {
    val iframes = tree.aggregate().filter { it.isIframe() && it.bounds != null }
    if (iframes.isEmpty()) return
    val origins = captureFrameOrigins(frame, offsetX, offsetY)
    val used = mutableSetOf<Int>()
    for (node in iframes) {
      val box = node.bounds ?: continue
      val index = origins.indices.firstOrNull { it !in used && origins[it].sameBox(box) } ?: continue
      used += index
      val origin = origins[index]
      val content = snapshotFrame(origin.frame, origin.contentX, origin.contentY) ?: continue
      out += content
      if (depth < MAX_FRAME_DEPTH) collectFrames(content, origin.frame, origin.contentX, origin.contentY, depth + 1, out)
    }
  }

  /**
   * [tree] with every [secrets] value replaced wherever a node's properties carry it. Longest
   * first: replacing a secret that another contains would leave the rest of the longer one.
   */
  fun redact(tree: TrailblazeNode, secrets: Set<String>): TrailblazeNode {
    val ordered = longestFirst(secrets)
    if (ordered.isEmpty()) return tree
    fun String.clean() = ordered.fold(this) { s, secret -> s.replace(secret, REDACTED) }
    fun visit(node: TrailblazeNode): TrailblazeNode {
      val w = node.driverDetail as? DriverNodeDetail.Web
      val detail = w?.copy(
        ariaName = w.ariaName?.clean(),
        ariaDescriptor = w.ariaDescriptor?.clean(),
        placeholder = w.placeholder?.clean(),
        title = w.title?.clean(),
        url = w.url?.clean(),
        cssSelector = w.cssSelector?.clean(),
        dataTestId = w.dataTestId?.clean(),
      ) ?: node.driverDetail
      return node.copy(driverDetail = detail, children = node.children.map(::visit))
    }
    return visit(tree)
  }

  /** [text] with every [secrets] value replaced, in the order [redact] uses. */
  fun redactText(text: String, secrets: Set<String>): String =
    longestFirst(secrets).fold(text) { s, secret -> s.replace(secret, REDACTED) }

  private fun longestFirst(secrets: Set<String>) = secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }

  /** Whether any node of [trees] carries one of [secrets] in the properties [redact] cleans. */
  fun mentions(trees: List<TrailblazeNode>, secrets: Set<String>): Boolean {
    val values = secrets.filter { it.isNotBlank() }
    if (values.isEmpty()) return false
    fun String?.has() = this != null && values.any { contains(it) }
    fun visit(node: TrailblazeNode): Boolean {
      val w = node.driverDetail as? DriverNodeDetail.Web
      val own = w != null && (w.ariaName.has() || w.ariaDescriptor.has() || w.placeholder.has() ||
        w.title.has() || w.url.has() || w.cssSelector.has() || w.dataTestId.has())
      return own || node.children.any(::visit)
    }
    return trees.any(::visit)
  }

  /**
   * Whether the page, in any of its frames, still shows one of [secrets]: in a field's value (an
   * input, a textarea or a contenteditable, which Playwright's query finds through open shadow
   * roots) or anywhere in the page's rendered text. A frame that can't be read counts as showing
   * one, so a failure costs a screenshot and never leaks a value.
   */
  fun holdsSecret(page: Page, secrets: Set<String>): Boolean {
    val values = secrets.filter { it.isNotBlank() }
    if (values.isEmpty()) return false
    return page.frames().any { frame ->
      try {
        frame.locator("input, textarea, [contenteditable]").evaluateAll(HOLDS_SECRET_SCRIPT, values) == true
      } catch (_: Exception) {
        true
      }
    }
  }

  private fun snapshotFrame(frame: Frame, offsetX: Int, offsetY: Int): TrailblazeNode? {
    val yaml = try {
      frame.locator(":root").ariaSnapshot(Locator.AriaSnapshotOptions().setBoxes(true).setTimeout(FRAME_TIMEOUT_MS))
    } catch (_: Exception) {
      return null
    }
    val split = PlaywrightAriaSnapshot.splitBoxes(yaml)
    val shifted = PlaywrightAriaSnapshot.BoxedAriaSnapshot(
      split.lines,
      split.boxes.map { b -> b?.let { TrailblazeNode.Bounds(it.left + offsetX, it.top + offsetY, it.right + offsetX, it.bottom + offsetY) } },
    )
    val tree = PlaywrightTrailblazeNodeMapper.mapBoxedSnapshot(shifted) ?: return null
    val located = locateTextRuns(tree, frame, offsetX, offsetY)
    val url = try { frame.url() } catch (_: Exception) { null }
    val detail = located.driverDetail as? DriverNodeDetail.Web ?: return located
    return located.copy(driverDetail = detail.copy(url = url))
  }

  private fun TrailblazeNode.isIframe() = (driverDetail as? DriverNodeDetail.Web)?.ariaRole == "iframe"

  private class TextBox(val text: String, val x: Int, val y: Int, val w: Int, val h: Int)

  private class FrameOrigin(
    val frame: Frame,
    val box: TrailblazeNode.Bounds,
    val contentX: Int,
    val contentY: Int,
  ) {
    fun sameBox(other: TrailblazeNode.Bounds) =
      kotlin.math.abs(box.left - other.left) <= 1 && kotlin.math.abs(box.top - other.top) <= 1 &&
        kotlin.math.abs(box.right - other.right) <= 1 && kotlin.math.abs(box.bottom - other.bottom) <= 1
  }

  /** The DOM text runs [start]..k whose joined text is [target], as one box, if inside [within]. */
  private fun findRun(
    texts: List<TextBox>,
    start: Int,
    target: String,
    within: TrailblazeNode.Bounds?,
  ): Pair<TrailblazeNode.Bounds, Int>? {
    if (target.isEmpty()) return null
    val end = minOf(texts.size, start + SEARCH_WINDOW)
    for (first in start until end) {
      val joined = StringBuilder()
      for (last in first until texts.size) {
        joined.append(texts[last].text)
        val normalized = normalize(joined.toString())
        if (normalized == target) {
          val box = union(texts.subList(first, last + 1))
          if (within == null || within.contains(box)) return box to last
          // A rendered duplicate outside the run's element (an aria-hidden visual label before
          // its sr-only twin) is not the run; the real text may sit a few entries later.
          break
        }
        if (!target.startsWith(normalized)) break
      }
    }
    return null
  }

  private fun union(parts: List<TextBox>) = TrailblazeNode.Bounds(
    left = parts.minOf { it.x },
    top = parts.minOf { it.y },
    right = parts.maxOf { it.x + it.w },
    bottom = parts.maxOf { it.y + it.h },
  )

  private fun TrailblazeNode.Bounds.contains(b: TrailblazeNode.Bounds) =
    b.left >= left - SLACK_PX && b.top >= top - SLACK_PX && b.right <= right + SLACK_PX && b.bottom <= bottom + SLACK_PX

  /** Playwright's own normalization: zero-width and soft hyphens out, any whitespace run to one space. */
  private fun normalize(s: String) =
    s.replace(INVISIBLE, "").replace(WHITESPACE, " ").trim()

  private fun captureTextBoxes(frame: Frame, offsetX: Int, offsetY: Int): List<TextBox> = try {
    val json = frame.evaluate(TEXT_BOXES_SCRIPT) as? String
    json?.let { Json.parseToJsonElement(it).jsonArray }?.map { e ->
      val a = e.jsonArray
      TextBox(a[0].jsonPrimitive.content, a[1].jsonPrimitive.int + offsetX, a[2].jsonPrimitive.int + offsetY, a[3].jsonPrimitive.int, a[4].jsonPrimitive.int)
    } ?: emptyList()
  } catch (_: Exception) {
    emptyList()
  }

  private fun captureFrameOrigins(frame: Frame, offsetX: Int, offsetY: Int): List<FrameOrigin> = try {
    // Each origin is read off its own handle: Playwright's query pierces open shadow roots and a
    // page script's does not, so two separate walks can pair a frame with another's box.
    val handles = frame.querySelectorAll("iframe, frame")
    // Disposed once read: this runs before every replayed action, and a page that never
    // navigates would otherwise hold one handle per iframe per action.
    try {
      handles.mapNotNull { handle ->
        val child = handle.contentFrame() ?: return@mapNotNull null
        val json = handle.evaluate(FRAME_ORIGIN_SCRIPT) as? String ?: return@mapNotNull null
        val a = Json.parseToJsonElement(json).jsonArray
        val x = a[0].jsonPrimitive.int + offsetX
        val y = a[1].jsonPrimitive.int + offsetY
        FrameOrigin(
          frame = child,
          box = TrailblazeNode.Bounds(x, y, x + a[2].jsonPrimitive.int, y + a[3].jsonPrimitive.int),
          contentX = Math.round(a[4].jsonPrimitive.double).toInt() + offsetX,
          contentY = Math.round(a[5].jsonPrimitive.double).toInt() + offsetY,
        )
      }
    } finally {
      handles.forEach { it.dispose() }
    }
  } catch (_: Exception) {
    emptyList()
  }

  private const val REDACTED = "•••"
  private const val HOLDS_SECRET_SCRIPT = "(els, secrets) => {" +
    " const text = (document.body && document.body.innerText) || '';" +
    " return secrets.some(s => text.includes(s))" +
    " || els.some(e => { const v = typeof e.value === 'string' ? e.value : (e.textContent || ''); return secrets.some(s => v.includes(s)); });" +
    " }"
  private const val SEARCH_WINDOW = 400
  private const val SLACK_PX = 2
  private const val MAX_FRAME_DEPTH = 3
  private const val FRAME_TIMEOUT_MS = 1_000.0
  private val INVISIBLE = Regex("[\\u200b\\u00ad]")
  private val WHITESPACE = Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

  /**
   * Every rendered text node, as `[text, x, y, w, h]` in the frame's viewport, in the order the
   * snapshot reads them: into open shadow roots, and slotted content at its slot.
   */
  private val TEXT_BOXES_SCRIPT = """
    () => {
      const out = [];
      const range = document.createRange();
      const visit = n => {
        if (n.nodeType === Node.TEXT_NODE) {
          const t = n.textContent;
          if (!t || !t.trim()) return;
          range.selectNodeContents(n);
          const r = range.getBoundingClientRect();
          if (r.width === 0 && r.height === 0) return;
          out.push([t, Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]);
          return;
        }
        if (n.nodeType !== Node.ELEMENT_NODE) return;
        if (n.localName === 'slot') {
          const assigned = n.assignedNodes();
          for (const c of (assigned.length ? assigned : n.childNodes)) visit(c);
          return;
        }
        for (const c of (n.shadowRoot || n).childNodes) visit(c);
      };
      visit(document.documentElement);
      return JSON.stringify(out);
    }
  """.trimIndent()

  /**
   * An `iframe`/`frame` element's border box, rounded as the snapshot rounds, and where its
   * content starts, past border and padding.
   */
  private val FRAME_ORIGIN_SCRIPT = """
    e => {
      const r = e.getBoundingClientRect();
      const s = getComputedStyle(e);
      return JSON.stringify([Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height),
        r.x + e.clientLeft + parseFloat(s.paddingLeft), r.y + e.clientTop + parseFloat(s.paddingTop)]);
    }
  """.trimIndent()
}
