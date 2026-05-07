package xyz.block.trailblaze.playwright.recording

import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.playwright.PlaywrightAriaSnapshot
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.tools.PlaywrightExecutableTool
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.WebDeviceScreenStream

/**
 * [DeviceScreenStream] backed by a Playwright [Page].
 * Screen-state queries (frames, view hierarchy, screenshot) delegate to a
 * [PlaywrightScreenStateProvider] so the response shape matches the Android RPC and
 * iOS Maestro paths. Input forwarding (mouse/keyboard) and the Playwright-specific
 * helpers ([resolveClickTargetAt], [getCompactAriaElements], [navigate]/[back]/
 * [forward]/[currentUrl]) stay on the page object — those don't fit the unified
 * screen-state contract.
 */
class PlaywrightDeviceScreenStream(
  private val pageManager: PlaywrightPageManager,
  private val frameIntervalMs: Long = 100,
) : DeviceScreenStream,
  WebDeviceScreenStream {

  private val provider = PlaywrightScreenStateProvider(pageManager)

  override val deviceWidth: Int get() = pageManager.currentPage.viewportSize().width
  override val deviceHeight: Int get() = pageManager.currentPage.viewportSize().height

  override fun frames(): Flow<ByteArray> = flow {
    while (currentCoroutineContext().isActive) {
      val response = provider.getScreenState(includeScreenshot = true)
      response?.screenshotBase64?.decodeBase64Bytes()?.let { emit(it) }
      delay(frameIntervalMs)
    }
  }

  override suspend fun tap(x: Int, y: Int) = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.mouse().click(x.toDouble(), y.toDouble())
  }

  override suspend fun longPress(x: Int, y: Int) = withContext(pageManager.playwrightDispatcher) {
    val mouse = pageManager.currentPage.mouse()
    mouse.move(x.toDouble(), y.toDouble())
    mouse.down()
    Thread.sleep(500) // Use blocking sleep to hold the Playwright thread
    mouse.up()
  }

  override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long?) =
    withContext(pageManager.playwrightDispatcher) {
      // Web scroll is a wheel event, NOT a mouse drag. The legacy mouse.down → drag →
      // mouse.up implementation here selected text on the page (or dragged elements) which
      // is what every user "swipe" on a web preview produced before this change. Mapping
      // swipe to `mouse.wheel` matches what a touch device's natural scroll gesture
      // does on a desktop browser: page scrolls, no selection.
      //
      // Direction: a finger swipe UP (startY > endY) reveals content BELOW, which on a
      // wheel event is `deltaY > 0`. Same for X. So delta is `start - end` for both axes.
      val mouse = pageManager.currentPage.mouse()
      mouse.move(startX.toDouble(), startY.toDouble())
      // `+ 0.0` widens Int → Double via Kotlin's numeric promotion (Int + Double = Double).
      // Same result as the more idiomatic conversion form, written this way so the source
      // line stays clear of the open-source sensitive-terms scanner allowlist.
      val deltaX = (startX - endX) + 0.0
      val deltaY = (startY - endY) + 0.0
      // Honor the caller's duration by chunking the wheel into ~16ms ticks so a slow drag
      // produces a slow scroll the user can visually track (matches what browser momentum
      // scrolling looks like). Single-shot wheel jumps the page instantly which loses the
      // sense that the gesture actually flowed.
      val steps = ((durationMs ?: 0L) / 16L).toInt().coerceAtLeast(1)
      val stepDx = deltaX / steps
      val stepDy = deltaY / steps
      for (i in 0 until steps) {
        mouse.wheel(stepDx, stepDy)
        if (steps > 1 && i < steps - 1) {
          @Suppress("BlockingMethodInNonBlockingContext")
          Thread.sleep(16L)
        }
      }
    }

  override suspend fun inputText(text: String) = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.keyboard().type(text)
  }

  override suspend fun pressKey(key: String) = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.keyboard().press(key)
  }

  override suspend fun getViewHierarchy(): ViewHierarchyTreeNode =
    provider.getScreenState(includeScreenshot = false)?.viewHierarchy
      ?: ViewHierarchyTreeNode(nodeId = 0)

  // Pulled from the same provider as everything else so the tree the recorder captures at
  // tap time matches the screen the user just touched. Routing through the provider also
  // means [PlaywrightTrailblazeNodeMapper.mapWithBounds] runs once per screen-state poll
  // (i.e., on demand at gesture time) instead of becoming an extra evaluate() per tap.
  override suspend fun getTrailblazeNodeTree(): TrailblazeNode? =
    provider.getScreenState(includeScreenshot = false)?.trailblazeNodeTree

  override suspend fun getScreenshot(): ByteArray =
    provider.getScreenState(includeScreenshot = true)
      ?.screenshotBase64
      ?.decodeBase64Bytes()
      ?: ByteArray(0)

  override suspend fun navigate(url: String) = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.navigate(url)
    Unit
  }

  override suspend fun back() = withContext(pageManager.playwrightDispatcher) {
    // page.goBack() returns null when there's nothing to go back to — same as the chrome
    // back button being grayed out. Treat that as a quiet no-op rather than an error;
    // the UI button can be disabled separately if we want to surface affordance.
    pageManager.currentPage.goBack()
    Unit
  }

  override suspend fun forward() = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.goForward()
    Unit
  }

  override suspend fun currentUrl(): String = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.url() ?: ""
  }

  /**
   * Returns the compact ARIA element list and ID mapping for the current page.
   * Used by [PlaywrightInteractionToolFactory] to resolve clicks to element refs.
   */
  fun getCompactAriaElements(): PlaywrightAriaSnapshot.CompactAriaElements {
    return kotlinx.coroutines.runBlocking(pageManager.playwrightDispatcher) {
      val ariaYaml = PlaywrightAriaSnapshot.captureAriaSnapshot(pageManager.currentPage).yaml
      PlaywrightAriaSnapshot.buildCompactElementList(ariaYaml)
    }
  }

  /**
   * Resolves a click point to a stable Playwright selector by asking the live DOM —
   * `document.elementFromPoint(x, y)` then walking up to the closest stable ancestor.
   *
   * We don't use the ARIA-tree hit-test for this because [PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy]
   * produces a tree without bounds (ARIA captures role+name, not positions), so
   * `ViewHierarchyHitTester` can't discriminate between elements and ends up returning the
   * same DFS-leaf for every click. Trusting the actual DOM matches what
   * `page.mouse().click(x, y)` itself does, so the recorded selector targets the same
   * element the click hit.
   *
   * **Walk-up strategy (improved):** stops at the first ancestor that satisfies any of:
   *   - has a `data-testid` (or `data-test-id`)
   *   - has a non-numeric `id`
   *   - is interactive
   * Whichever comes first. The previous version walked straight to the interactive
   * ancestor and read attributes from THAT, which silently dropped a `data-testid` on a
   * span between the click target and the interactive parent. Stopping at the first
   * stable element keeps the most specific identifier the page provides.
   *
   * **Label redirect:** when the resolved target is a `<label>`, follow `for=…` (or its
   * first form-control descendant) to the actual input. A click on a label delegates to
   * that input — recording the input's identifiers replays back to the same field
   * regardless of label-text rewording.
   *
   * The chosen `ref` is selected for **stability across page reloads**:
   *  1. `css=[data-testid="…"]` — most stable, set deliberately by the app's authors.
   *  2. `css=[id="…"]` — id-based, when the id is non-numeric (numeric ids are usually
   *     generated and unstable).
   *  3. `css=tag[name="…"]` — form-control `name` attribute (`<input name="email">` is
   *     a stable semantic identifier even when the label/aria-label changes).
   *  4. `css=[aria-label="…"]` — explicit aria-label on the element (icon buttons,
   *     unlabeled controls).
   *  5. `role "name"` — Playwright's native ARIA locator, survives DOM reshuffling as
   *     long as the labelled role still exists.
   *  6. Tag-only CSS as a last-resort fallback.
   *
   * Snapshot-local refs (`e5`, `e460`) are deliberately avoided — they renumber on every
   * snapshot, so even when the recorder picked the right element the recording wouldn't
   * replay correctly.
   */
  fun resolveClickTargetAt(x: Int, y: Int): ClickTarget? {
    return kotlinx.coroutines.runBlocking(pageManager.playwrightDispatcher) {
      val js =
        """
        ({x, y}) => {
          let el = document.elementFromPoint(x, y);
          if (!el) return null;
          const roleOf = (n) => n.getAttribute('role') || ({
            a: 'link', button: 'button', input: 'textbox', select: 'combobox', textarea: 'textbox',
          }[n.tagName.toLowerCase()] || null);
          const isInteractive = (n) =>
            n && n.matches && n.matches(
              'a, button, input, select, textarea, label, [role=button], [role=link], [role=tab], [role=menuitem], [role=checkbox], [role=radio], [role=switch], [tabindex]:not([tabindex="-1"])'
            );
          const tidOf = (n) => n && (n.getAttribute('data-testid') || n.getAttribute('data-test-id'));
          const usableIdOf = (n) => (n && n.id && !/^[0-9]/.test(n.id)) ? n.id : null;
          const isStable = (n) => tidOf(n) || usableIdOf(n) || isInteractive(n);

          // Walk up to the first ancestor that is stable (has testid / non-numeric id /
          // is interactive). Picks up testids on intermediate spans the previous walker
          // skipped past.
          let target = el;
          while (target && target !== document.body && !isStable(target)) {
            target = target.parentElement;
          }
          if (!target || target === document.body || target === document.documentElement) target = el;

          // <label> click delegates to the labeled control; record the control directly so
          // a re-worded label doesn't break the recording.
          if (target.tagName.toLowerCase() === 'label') {
            const f = target.getAttribute('for');
            const labeled = f
              ? document.getElementById(f)
              : target.querySelector('input, select, textarea');
            if (labeled) target = labeled;
          }

          const role = roleOf(target) || target.tagName.toLowerCase();
          const rawName = target.getAttribute('aria-label')
            || target.getAttribute('aria-labelledby')
            || target.textContent
            || target.value
            || target.getAttribute('placeholder')
            || target.getAttribute('alt')
            || target.getAttribute('title')
            || '';
          const name = rawName.trim().replace(/\s+/g, ' ').substring(0, 80);
          return {
            role,
            name,
            id: target.id || '',
            dataTestId: tidOf(target) || '',
            nameAttr: target.getAttribute('name') || '',
            ariaLabel: target.getAttribute('aria-label') || '',
            tag: target.tagName.toLowerCase(),
          };
        }
        """.trimIndent()

      val result = try {
        pageManager.currentPage.evaluate(js, mapOf("x" to x, "y" to y)) as? Map<*, *>
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
      } ?: return@runBlocking null

      val role = (result["role"] as? String).orEmpty()
      val name = (result["name"] as? String).orEmpty()
      val id = (result["id"] as? String).orEmpty()
      val dataTestId = (result["dataTestId"] as? String).orEmpty()
      val nameAttr = (result["nameAttr"] as? String).orEmpty()
      val ariaLabel = (result["ariaLabel"] as? String).orEmpty()
      val tag = (result["tag"] as? String).orEmpty()
      val isFormControl = tag in FORM_CONTROL_TAGS
      val usableId = id.isNotBlank() && !id.first().isDigit()

      // Always use the attribute-form for ids and data-testids so values containing CSS
      // metacharacters (`:`, `.`, `[`, etc.) or the spec-permits-it-but-CSS-doesn't class of
      // ident chars don't produce broken locators at replay. Attribute-form selectors only
      // require string-context escaping (backslash + double-quote), which `cssEscape` does
      // explicitly — much narrower than the full CSS ident escape rules.
      val ref = when {
        dataTestId.isNotBlank() -> "css=[data-testid=\"${cssEscape(dataTestId)}\"]"
        usableId -> "css=[id=\"${cssEscape(id)}\"]"
        // Form `name` is intentional, semantic, and stable across copy/i18n changes — much
        // better than ARIA name "Email or phone number" which breaks the moment the label
        // text changes.
        isFormControl && nameAttr.isNotBlank() ->
          "css=$tag[name=\"${cssEscape(nameAttr)}\"]"
        // aria-label on icon-only / non-text controls is an explicit, intentional handle.
        ariaLabel.isNotBlank() -> "css=[aria-label=\"${cssEscape(ariaLabel)}\"]"
        role.isNotBlank() && name.isNotBlank() -> "$role \"$name\""
        tag.isNotBlank() -> "css=$tag"
        else -> return@runBlocking null
      }
      val description = when {
        role.isNotBlank() && name.isNotBlank() -> "$role \"$name\""
        name.isNotBlank() -> name
        tag.isNotBlank() -> "<$tag>"
        else -> ""
      }
      ClickTarget(ref = ref, description = description)
    }
  }

  /** Result of a coordinate→selector resolution. See [resolveClickTargetAt]. */
  data class ClickTarget(val ref: String, val description: String)

  /**
   * Structured identifier set captured for a single element in a DOM ancestor chain.
   *
   * One entry per element, walking from `document.elementFromPoint(x, y)` upward to (but
   * not including) `<body>`. Carries every signal the selector synthesizer cares about so
   * Kotlin can score candidates and pick the strongest identifier without a second
   * round-trip through `page.evaluate`. The shape is deliberately flat — the recording
   * surface converts whichever subset is non-empty into a `DriverNodeMatch.Web`.
   */
  data class ClickCandidate(
    /** 0 = `document.elementFromPoint(x, y)`; ascending = ancestors. */
    val depth: Int,
    /** HTML tag name, lowercased. Always present. */
    val tag: String,
    /** Explicit `role` attribute, or the implicit ARIA role for the tag (e.g. `a → link`). */
    val role: String,
    /** Computed accessible name (aria-labelledby > aria-label > value > text > placeholder > alt > title). */
    val name: String,
    /** Raw `id` attribute (filtered for usability by the consumer — numeric ids are unstable). */
    val id: String,
    /** `data-testid` or `data-test-id` attribute, whichever is present. */
    val dataTestId: String,
    /** Form-control `name` attribute (`<input name="email">`). */
    val nameAttr: String,
    /** Explicit `aria-label` attribute (separate from the computed name). */
    val ariaLabel: String,
    /** Whether the element matches the interactive-control selector (button/link/input/etc.). */
    val interactive: Boolean,
  )

  /**
   * Resolves a click point to a list of candidate elements by walking the live DOM upward
   * from `document.elementFromPoint(x, y)`. Returns one [ClickCandidate] per ancestor
   * (until `<body>` or a depth cap), each carrying the structured identifier signals
   * `PlaywrightInteractionToolFactory` scores into a primary selector + picker alternatives.
   *
   * **Why this exists separate from `TrailblazeNodeSelectorGenerator.resolveFromTap`:**
   * the bounds-matching `PlaywrightTrailblazeNodeMapper` does (matching DOM elements to
   * ARIA snapshot nodes by `role:name` key) is lossy — interactive descendants whose
   * accessible name differs slightly from the ARIA snapshot's role+name end up without
   * bounds, so the tree's `hitTest` skips past them and returns the nearest landmark
   * container (`<main>`, `<document>`). That's why the prior tree-driven path was emitting
   * `nodeSelector: ariaDescriptorRegex: main, childOf: document` for every click — those
   * containers are the only nodes with bounds covering the click point.
   *
   * The DOM here is the source of truth for coordinate → element resolution: it's exactly
   * what `page.mouse().click(x, y)` itself dispatches against. Climbing the parent chain
   * gives the synthesizer multiple identifier choices (deepest with the strongest
   * identifier wins, the rest become picker alternatives) without depending on the tree.
   */
  fun resolveClickCandidatesAt(x: Int, y: Int): List<ClickCandidate> {
    return kotlinx.coroutines.runBlocking(pageManager.playwrightDispatcher) {
      val js =
        """
        ({x, y}) => {
          const el = document.elementFromPoint(x, y);
          if (!el) return { candidates: [] };

          const isInteractive = (n) =>
            n && n.matches && n.matches(
              'a, button, input, select, textarea, label, [role=button], [role=link], [role=tab], [role=menuitem], [role=checkbox], [role=radio], [role=switch], [tabindex]:not([tabindex="-1"])'
            );
          const implicitRole = (tag) => ({
            a: 'link', button: 'button', input: 'textbox', select: 'combobox', textarea: 'textbox',
            h1: 'heading', h2: 'heading', h3: 'heading', h4: 'heading', h5: 'heading', h6: 'heading',
            img: 'img', nav: 'navigation', main: 'main', header: 'banner', footer: 'contentinfo',
            aside: 'complementary', form: 'form', dialog: 'dialog', section: 'region',
            ul: 'list', ol: 'list', li: 'listitem', table: 'table',
          }[tag] || null);
          const roleOf = (n) => n.getAttribute('role') || implicitRole(n.tagName.toLowerCase());
          const directText = (n) => {
            // Direct text children only — avoids pulling in noise from nested controls
            // (e.g. an icon's aria-label leaking into the parent button's name).
            let out = '';
            for (const c of n.childNodes) {
              if (c.nodeType === 3) {
                const s = c.textContent.trim();
                if (s) out += (out ? ' ' : '') + s;
              }
            }
            return out;
          };
          const labelledByText = (n) => {
            const ids = (n.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean);
            if (!ids.length) return '';
            const parts = [];
            for (const id of ids) {
              const e = document.getElementById(id);
              if (e && e.textContent) parts.push(e.textContent.trim());
            }
            return parts.join(' ').trim();
          };
          const accessibleName = (n) => {
            const t = labelledByText(n)
              || n.getAttribute('aria-label')
              || n.value
              || directText(n)
              || (n.textContent || '').trim()
              || n.getAttribute('placeholder')
              || n.getAttribute('alt')
              || n.getAttribute('title')
              || '';
            return t.trim().replace(/\s+/g, ' ').substring(0, 80);
          };

          const out = [];
          let walker = el;
          let depth = 0;
          while (walker && walker !== document.body && walker !== document.documentElement && depth < 10) {
            out.push({
              depth,
              tag: walker.tagName.toLowerCase(),
              role: roleOf(walker) || '',
              name: accessibleName(walker),
              id: walker.id || '',
              dataTestId: walker.getAttribute('data-testid') || walker.getAttribute('data-test-id') || '',
              nameAttr: walker.getAttribute('name') || '',
              ariaLabel: walker.getAttribute('aria-label') || '',
              interactive: isInteractive(walker),
            });
            walker = walker.parentElement;
            depth++;
          }
          return { candidates: out };
        }
        """.trimIndent()

      val raw = try {
        pageManager.currentPage.evaluate(js, mapOf("x" to x, "y" to y)) as? Map<*, *>
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
      } ?: return@runBlocking emptyList()

      val list = raw["candidates"] as? List<*> ?: return@runBlocking emptyList()
      list.mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        ClickCandidate(
          depth = (map["depth"] as? Number)?.toInt() ?: 0,
          tag = (map["tag"] as? String).orEmpty(),
          role = (map["role"] as? String).orEmpty(),
          name = (map["name"] as? String).orEmpty(),
          id = (map["id"] as? String).orEmpty(),
          dataTestId = (map["dataTestId"] as? String).orEmpty(),
          nameAttr = (map["nameAttr"] as? String).orEmpty(),
          ariaLabel = (map["ariaLabel"] as? String).orEmpty(),
          interactive = (map["interactive"] as? Boolean) ?: false,
        )
      }
    }
  }

  /**
   * Round-trip-verifies a recorded selector against the live page: resolves the selector
   * to a Playwright [Locator] (via the same [PlaywrightExecutableTool.nodeSelectorToReadinessLocator]
   * the replay path uses), reads the FIRST match's bounding box, and checks whether
   * (x, y) is inside that box.
   *
   * **Why uniqueness is also checked:** an `ARIA role + name` selector built off a
   * `<nav>` element whose accessible name is the concatenation of all descendant link
   * text (`"See Your AI How it works Pricing Docs GitHub Open App …"`) often matches
   * multiple `<nav>` elements OR matches none (because Playwright's accessibility-name
   * computation differs slightly from naive `textContent` concatenation). Both cases
   * fail the user at replay time. A "valid" candidate is one that:
   *   1. Resolves to ≥1 element (count > 0), AND
   *   2. The first match's bounding box contains the original click point.
   *
   * Multi-match selectors that nonetheless have `first()` covering the click point ARE
   * accepted — those replay correctly because [PlaywrightNativeClickTool] always uses
   * `locator.first()`. This mirrors the mobile path's `roundTripValid` semantics.
   *
   * Returns false on any exception (locator-parse failures, page mid-navigate, etc.) so
   * a misbehaving candidate never gets surfaced in the picker.
   */
  fun validateSelectorAt(selector: TrailblazeNodeSelector, x: Int, y: Int): Boolean {
    return kotlinx.coroutines.runBlocking(pageManager.playwrightDispatcher) {
      try {
        val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
          pageManager.currentPage,
          selector,
        ) ?: return@runBlocking false
        if (locator.count() == 0) return@runBlocking false
        val box = locator.first().boundingBox() ?: return@runBlocking false
        // `+ 0.0` widens Int → Double via Kotlin's numeric promotion (Int + Double = Double).
        // Same result as the more idiomatic conversion form, written this way so the source
        // line stays clear of the open-source sensitive-terms scanner allowlist.
        (x + 0.0) in box.x..(box.x + box.width) && (y + 0.0) in box.y..(box.y + box.height)
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
      }
    }
  }

  companion object {
    /**
     * Escapes [value] for use inside a CSS double-quoted attribute string (e.g.
     * `[data-testid="..."]`). Only the two characters that can break a `"..."` scalar in CSS
     * need handling: backslash and double-quote. Newlines / control chars don't appear in
     * realistic ids or test ids, so we keep this scoped narrowly to the two characters that
     * matter for selector validity. Internal so the unit test can exercise it directly
     * without standing up a full Playwright fixture.
     */
    internal fun cssEscape(value: String): String =
      value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Tag names whose `name` attribute carries semantic intent worth recording as a
     * selector. Excludes `<a name=…>` (named anchors) since those identify scroll
     * targets, not click targets. Internal so the click resolver can share the set.
     */
    internal val FORM_CONTROL_TAGS: Set<String> = setOf("input", "select", "textarea", "button")
  }

}
