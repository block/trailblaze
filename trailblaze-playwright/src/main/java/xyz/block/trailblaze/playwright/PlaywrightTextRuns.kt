package xyz.block.trailblaze.playwright

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Selectors
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.WaitForSelectorState
import java.util.Base64
import java.util.Collections
import java.util.WeakHashMap

/**
 * Finds elements by the text an ARIA snapshot prints for them, live, as a Playwright selector
 * engine.
 *
 * A snapshot prints some nodes by their text rather than an accessible name: `paragraph: Some
 * text` for an unnamed element whose content is one run of text, and `text: Some text` for a run
 * that sits beside other nodes. A selector recorded from either holds that printed text, and
 * Playwright's own text matching cannot replay it: it reads raw `textContent`, which keeps hidden
 * descendants, keeps the page's whitespace, and runs sibling blocks together where the snapshot
 * puts a space.
 *
 * The engine rebuilds the snapshot's text in the page the way Playwright's `generateAriaTree`
 * does in its standard mode: hidden subtrees are skipped, every non-inline element is spaced
 * apart, CSS `::before`/`::after` content counts, whitespace is collapsed, and an element with a
 * role ends the run it interrupts and keeps its own text. A selector engine is re-run on every
 * poll, so a locator built on it waits for text that has not rendered yet.
 *
 * Mirrors Playwright 1.63's injected `ariaSnapshot.ts` and `roleUtils.ts`. A divergence makes a
 * recorded selector miss rather than match something else, because both sides must agree on the
 * whole run.
 */
object PlaywrightTextRuns {

  /** The selector engine's name, as registered with [register]. */
  const val ENGINE = "trailblaze-snapshot-text"

  /**
   * Registers the engine on [playwright]. Contexts that already exist get it too. Registering a
   * second time is a no-op.
   *
   * It runs as a content script, in a world of its own, so a page that replaces globals such as
   * `JSON.parse` or `RegExp` cannot change what it matches. A context [isRegistered] found
   * without the engine is asked again, as it may have it now.
   */
  fun register(playwright: Playwright) {
    try {
      playwright.selectors().register(ENGINE, SCRIPT, Selectors.RegisterOptions().setContentScript(true))
    } catch (e: PlaywrightException) {
      if (e.message?.contains("already registered") != true) throw e
    }
    synchronized(registeredByContext) { registeredByContext.values.removeAll { !it } }
  }

  private val registeredByContext: MutableMap<BrowserContext, Boolean> =
    Collections.synchronizedMap(WeakHashMap())

  /**
   * Whether [page]'s Playwright has the engine. Callers that build a [Playwright] of their own
   * and never call [register] get `false`, and text-recorded selectors simply do not resolve
   * there; querying an unregistered engine would instead throw on every role selector.
   *
   * Playwright rejects an unknown engine while parsing the selector, before it reaches the page,
   * so a probe that times out still proves the engine exists, and the wait is bounded. Only those
   * two answers are kept: any other failure (the page navigating or closing mid-probe) says
   * nothing about the engine, so this lookup goes without text matching and the next one asks
   * again.
   */
  fun isRegistered(page: Page): Boolean {
    val context = page.context()
    registeredByContext[context]?.let { return it }
    val registered = try {
      page.locator("$ENGINE=$PROBE").waitFor(
        Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(PROBE_TIMEOUT_MS),
      )
      true
    } catch (_: TimeoutError) {
      true
    } catch (e: PlaywrightException) {
      if (e.message?.contains(UNKNOWN_ENGINE) != true) return false
      false
    }
    registeredByContext[context] = registered
    return registered
  }

  /**
   * Elements printed as `role: text` whose text matches: an element whose content is exactly one
   * run, matched in full. Combine with a role locator; this side knows nothing of roles.
   */
  fun content(page: Page, source: String, flags: Int, literal: String): Locator =
    page.locator(selector("content", source, flags, literal, occurrence = null))

  /**
   * Runs printed as `text: ...` whose text matches in full, each resolved to the smallest element
   * that holds the whole run. With [occurrence], only that run, counted over every matching run in
   * snapshot order.
   */
  fun runs(page: Page, source: String, flags: Int, literal: String, occurrence: Int? = null): Locator =
    page.locator(selector("run", source, flags, literal, occurrence))

  /**
   * Base64 keeps the body clear of Playwright's selector syntax: a pattern may contain `>>`,
   * which would otherwise split the selector in two.
   */
  private fun selector(mode: String, source: String, flags: Int, literal: String, occurrence: Int?): String {
    val body = buildString {
      append("{\"mode\":").append(jsonString(mode))
      append(",\"source\":").append(jsonString(source))
      append(",\"flags\":").append(jsonString(jsFlags(flags)))
      append(",\"literal\":").append(jsonString(literal))
      if (occurrence != null) append(",\"occurrence\":").append(occurrence)
      append('}')
    }
    return "$ENGINE=" + Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
  }

  private fun jsFlags(flags: Int): String = buildString {
    if (flags and java.util.regex.Pattern.CASE_INSENSITIVE != 0) append('i')
    if (flags and java.util.regex.Pattern.DOTALL != 0) append('s')
    if (flags and java.util.regex.Pattern.MULTILINE != 0) append('m')
  }

  private fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
      when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
      }
    }
    append('"')
  }

  /**
   * The engine. Matching is a full match of the pattern OR equality with the recorded value —
   * the same two legs `TrailblazeNodeSelectorResolver.matchesPattern` uses — so a value such as
   * `$5.00`, whose bare `$` can never match as a regex, still replays.
   */
  private val SCRIPT = """
(() => {
  const VALID_ROLES = new Set($VALID_ROLES_JSON);
  const GLOBAL_ARIA = $GLOBAL_ARIA_JSON;
  const LANDMARK_BLOCKERS = 'article:not([role]), aside:not([role]), main:not([role]), nav:not([role]), section:not([role]), [role=article], [role=complementary], [role=main], [role=navigation], [role=region]';
  const PRESENTATION_PARENTS = { DD: ['DL', 'DIV'], DIV: ['DL'], DT: ['DL', 'DIV'], LI: ['OL', 'UL'], TBODY: ['TABLE'], TD: ['TR'], TFOOT: ['TABLE'], TH: ['TR'], THEAD: ['TABLE'], TR: ['THEAD', 'TBODY', 'TFOOT', 'TABLE'] };
  const SIMPLE_ROLES = { ARTICLE: 'article', ASIDE: 'complementary', BLOCKQUOTE: 'blockquote', BUTTON: 'button', CAPTION: 'caption', CODE: 'code', DATALIST: 'listbox', DD: 'definition', DEL: 'deletion', DETAILS: 'group', DFN: 'term', DIALOG: 'dialog', DT: 'term', EM: 'emphasis', FIELDSET: 'group', FIGURE: 'figure', H1: 'heading', H2: 'heading', H3: 'heading', H4: 'heading', H5: 'heading', H6: 'heading', HR: 'separator', HTML: 'document', INS: 'insertion', LI: 'listitem', MAIN: 'main', MARK: 'mark', MATH: 'math', MENU: 'list', METER: 'meter', NAV: 'navigation', OL: 'list', OPTGROUP: 'group', OPTION: 'option', OUTPUT: 'status', P: 'paragraph', PROGRESS: 'progressbar', SEARCH: 'search', STRONG: 'strong', SUB: 'subscript', SUP: 'superscript', SVG: 'img', TABLE: 'table', TBODY: 'rowgroup', TD: 'cell', TEXTAREA: 'textbox', TFOOT: 'rowgroup', TH: 'columnheader', THEAD: 'rowgroup', TIME: 'time', TR: 'row', UL: 'list' };
  const TEXT_INPUTS = ['email', 'tel', 'text', 'url', 'search', ''];
  const INPUT_ROLES = { button: 'button', checkbox: 'checkbox', image: 'button', number: 'spinbutton', radio: 'radio', range: 'slider', reset: 'button', submit: 'button' };

  const tagOf = (e) => (typeof e.tagName === 'string' ? e.tagName : 'FORM').toUpperCase();
  const style = (e, pseudo) => e.ownerDocument.defaultView.getComputedStyle(e, pseudo);
  const hasTabIndex = (e) => !Number.isNaN(Number(String(e.getAttribute('tabindex'))));
  const isFocusable = (e) => {
    if (e.disabled) return false;
    const tag = tagOf(e);
    if (['BUTTON', 'DETAILS', 'SELECT', 'TEXTAREA'].includes(tag)) return true;
    if (tag === 'A' || tag === 'AREA') return e.hasAttribute('href');
    if (tag === 'INPUT') return !e.hidden;
    return hasTabIndex(e);
  };
  const hasGlobalAria = (e, role) =>
    GLOBAL_ARIA.some(([attr, prohibited]) => !(prohibited || []).includes(role || '') && e.hasAttribute(attr));
  const explicitRole = (e) =>
    (e.getAttribute('role') || '').split(' ').map((r) => r.trim()).find((r) => VALID_ROLES.has(r)) || null;
  const implicitRoleOfTag = (e) => {
    const tag = tagOf(e);
    switch (tag) {
      case 'A': case 'AREA': return e.hasAttribute('href') ? 'link' : null;
      case 'FOOTER': return e.parentElement && e.parentElement.closest(LANDMARK_BLOCKERS) ? null : 'contentinfo';
      case 'HEADER': return e.parentElement && e.parentElement.closest(LANDMARK_BLOCKERS) ? null : 'banner';
      case 'FORM': case 'SECTION':
        return e.hasAttribute('aria-label') || e.hasAttribute('aria-labelledby') ? (tag === 'FORM' ? 'form' : 'region') : null;
      case 'IMG':
        return e.getAttribute('alt') === '' && !e.getAttribute('title') && !hasGlobalAria(e) && !hasTabIndex(e) ? 'presentation' : 'img';
      case 'INPUT': {
        const type = (e.type || '').toLowerCase();
        if (TEXT_INPUTS.includes(type)) {
          const list = idRefs(e, 'list')[0];
          if (list && tagOf(list) === 'DATALIST') return 'combobox';
          return type === 'search' ? 'searchbox' : 'textbox';
        }
        if (type === 'hidden') return null;
        if (type === 'file') return 'button';
        return INPUT_ROLES[type] || 'textbox';
      }
      case 'SELECT': return e.hasAttribute('multiple') || e.size > 1 ? 'listbox' : 'combobox';
      default: return SIMPLE_ROLES[tag] || null;
    }
  };
  const implicitRole = (e) => {
    const role = implicitRoleOfTag(e);
    if (!role) return null;
    for (let ancestor = e; ancestor; ) {
      const parent = ancestor.parentElement;
      const parents = PRESENTATION_PARENTS[tagOf(ancestor)];
      if (!parents || !parent || !parents.includes(tagOf(parent))) break;
      const parentRole = explicitRole(parent);
      if ((parentRole === 'none' || parentRole === 'presentation') && !(hasGlobalAria(parent, parentRole) || isFocusable(parent))) return parentRole;
      ancestor = parent;
    }
    return role;
  };
  const roleOf = (e) => {
    const explicit = explicitRole(e);
    if (!explicit) return implicitRole(e);
    if (explicit === 'none' || explicit === 'presentation') {
      const implicit = implicitRole(e);
      if (hasGlobalAria(e, implicit) || isFocusable(e)) return implicit;
    }
    return explicit;
  };
  // Per-build caches, as Playwright keeps them for one snapshot.
  let hiddenCache = new Map();
  const parentOrHost = (e) => e.parentElement || (e.parentNode && e.parentNode.nodeType === 11 ? e.parentNode.host : null) || null;
  const belongsToHidden = (e) => {
    let hidden = hiddenCache.get(e);
    if (hidden !== undefined) return hidden;
    hidden = !!(e.parentElement && e.parentElement.shadowRoot && !e.assignedSlot);
    if (!hidden) {
      const s = style(e);
      hidden = !s || s.display === 'none' || (e.getAttribute('aria-hidden') || '').toLowerCase() === 'true';
    }
    if (!hidden) {
      const parent = parentOrHost(e);
      if (parent) hidden = belongsToHidden(parent);
    }
    hiddenCache.set(e, hidden);
    return hidden;
  };
  const isVisibleText = (node) => {
    const range = node.ownerDocument.createRange();
    range.selectNode(node);
    const rect = range.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  };
  const isHidden = (e) => {
    const tag = tagOf(e);
    if (['STYLE', 'SCRIPT', 'NOSCRIPT', 'TEMPLATE'].includes(tag)) return true;
    const s = style(e);
    // A display:contents box has no box of its own: it is hidden when everything in it is.
    if (s && s.display === 'contents' && tag !== 'SLOT') {
      for (let child = e.firstChild; child; child = child.nextSibling) {
        if (child.nodeType === Node.ELEMENT_NODE && !isHidden(child)) return false;
        if (child.nodeType === Node.TEXT_NODE && isVisibleText(child)) return false;
      }
      return true;
    }
    const optionInSelect = tag === 'OPTION' && !!e.closest('select');
    if (!optionInSelect && tag !== 'SLOT' && s) {
      if (e.checkVisibility && !e.checkVisibility()) return true;
      if (s.visibility !== 'visible') return true;
    }
    return belongsToHidden(e);
  };
  // The text a CSS `content` value prints: its strings and attr() values, after a `/` when it
  // has one (the alternative text). Anything else in it, an image url() or a counter, means it
  // prints nothing at all.
  const CSS_TOKEN = /\s+|"((?:[^"\\]|\\[\s\S])*)"|'((?:[^'\\]|\\[\s\S])*)'|(\/)|attr\(\s*([\w-]+)\s*\)|url\(\s*(?:"(?:[^"\\]|\\[\s\S])*"|'(?:[^'\\]|\\[\s\S])*'|[^)]*)\s*\)|[\s\S]/g;
  const unescapeCss = (text) => text.replace(/\\(?:([0-9a-fA-F]{1,6})\s?|\n|([\s\S]))/g, (_, hex, ch) =>
    hex ? String.fromCodePoint(parseInt(hex, 16)) : (ch === undefined ? '' : ch));
  const parseCssContent = (e, value, pseudo) => {
    const tokens = [];
    for (let m; (m = CSS_TOKEN.exec(value)); ) {
      if (!m[0].trim()) continue;
      if (m[1] !== undefined || m[2] !== undefined) tokens.push({ text: unescapeCss(m[1] !== undefined ? m[1] : m[2]) });
      else if (m[3]) tokens.push({ slash: true });
      else if (m[4] !== undefined) tokens.push({ text: e.getAttribute(m[4]) || '' });
      else tokens.push({});
    }
    const slash = tokens.findIndex((token) => token.slash);
    // An element's own content reads only by its alternative text.
    if (slash === -1 && !pseudo) return undefined;
    const used = slash === -1 ? tokens : tokens.slice(slash + 1);
    if (used.some((token) => token.text === undefined)) return undefined;
    return used.map((token) => token.text).join('');
  };
  const cssContent = (e, pseudo) => {
    const s = style(e, pseudo);
    if (!s || !s.content || s.content === 'none' || s.content === 'normal') return '';
    if (s.display === 'none' || s.visibility === 'hidden') return '';
    const text = parseCssContent(e, s.content, true);
    if (text === undefined) return '';
    return (s.display || 'inline') !== 'inline' ? ' ' + text + ' ' : text;
  };
  const ownCssContent = (e) => {
    const s = style(e);
    if (!s || !s.content || s.content === 'none' || s.content === 'normal') return undefined;
    if (s.display === 'none' || s.visibility === 'hidden') return undefined;
    return parseCssContent(e, s.content, false);
  };
  const normalize = (text) => text.replace(/[\u200b\u00ad]/g, '').trim().replace(/\s+/g, ' ');

  // One record per element with a role: its children in order, strings (with the text node
  // each came from, if any) and nested records. This is the snapshot's tree with only what
  // decides printed text.
  const build = (rootElement) => {
    hiddenCache = new Map();
    const records = [];
    const visit = (record, node) => {
      if (node.nodeType === Node.TEXT_NODE) {
        if (node.nodeValue && record.role !== 'textbox') record.children.push({ text: node.nodeValue, source: node });
        return;
      }
      if (node.nodeType !== Node.ELEMENT_NODE || isHidden(node)) return;
      // A frame is a node of its own in the snapshot, whatever its role, so it ends a run.
      const role = ['IFRAME', 'FRAME'].includes(tagOf(node)) ? 'iframe' : roleOf(node);
      let target = record;
      if (role && role !== 'none' && role !== 'presentation' && !isInlineTextGeneric(node, role)) {
        target = { element: node, role, children: [] };
        // A text field shows its value, not its child text.
        if ((node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement) && !['checkbox', 'radio', 'file'].includes(node.type))
          target.children.push({ text: node.value, source: node });
        records.push(target);
        record.children.push(target);
      }
      processElement(target, node);
    };
    const processElement = (record, element) => {
      const display = (style(element) || {}).display || 'inline';
      const block = display !== 'inline' || element.nodeName === 'BR';
      if (block) record.children.push({ text: ' ' });
      record.children.push({ text: cssContent(element, '::before'), source: element });
      const assigned = element.nodeName === 'SLOT' ? element.assignedNodes() : [];
      if (assigned.length) {
        for (const child of assigned) visit(record, child);
      } else {
        for (let child = element.firstChild; child; child = child.nextSibling)
          if (!child.assignedSlot) visit(record, child);
        if (element.shadowRoot)
          for (let child = element.shadowRoot.firstChild; child; child = child.nextSibling) visit(record, child);
      }
      record.children.push({ text: cssContent(element, '::after'), source: element });
      if (block) record.children.push({ text: ' ' });
    };
    const fragment = { element: rootElement, role: 'fragment', children: [] };
    if (rootElement && !isHidden(rootElement)) visit(fragment, rootElement);
    // Adjacent strings become one run, as the snapshot's mergeStringChildren joins them, nested
    // records first. An unnamed generic left with nothing in it is then dropped, as
    // unwrapSingleChildGenerics drops it, so the runs either side of it join.
    for (const record of [...records].reverse().concat(fragment)) {
      const merged = [];
      let buffer = [];
      const flush = () => {
        const text = normalize(buffer.map((part) => part.text).join(''));
        // Layout whitespace between blocks belongs to the run but not to where it is shown.
        const sourced = buffer.filter((part) => part.source);
        const shown = sourced.filter((part) => part.text.trim());
        if (text) merged.push({ run: text, nodes: (shown.length ? shown : sourced).map((part) => part.source) });
        buffer = [];
      };
      for (const child of record.children) {
        if (child.dropped) continue;
        if (child.element) { flush(); merged.push(child); } else buffer.push(child);
      }
      flush();
      record.children = merged;
      if (record.role === 'generic' && !merged.length) record.dropped = true;
    }
    return { fragment, records: records.filter((record) => !record.dropped) };
  };
  // An inline generic around a single text node is no node of the snapshot's; its text joins
  // the text around it.
  const isInlineTextGeneric = (e, role) => {
    if (role !== 'generic' || e.childNodes.length !== 1 || e.firstChild.nodeType !== Node.TEXT_NODE) return false;
    const s = style(e);
    if (!s) return false;
    return s.display === 'contents' ? isVisibleText(e.firstChild) : s.display === 'inline';
  };

  // Id references resolve in the element's own document or shadow root.
  const idRefs = (e, attr) => {
    const root = e.getRootNode();
    if (!root || !root.querySelector) return [];
    const found = [];
    for (const id of (e.getAttribute(attr) || '').split(/\s+/).filter(Boolean)) {
      const target = root.querySelector('#' + CSS.escape(id));
      if (target && !found.includes(target)) found.push(target);
    }
    return found;
  };
  // Descendants matching a selector, then those of the elements it aria-owns.
  const ownedMatching = (e, selector) => {
    const found = [...e.querySelectorAll(selector)];
    for (const owned of idRefs(e, 'aria-owns')) {
      if (owned.matches(selector)) found.push(owned);
      found.push(...owned.querySelectorAll(selector));
    }
    return found;
  };
  const NAMED_FROM_CONTENT = ['button', 'cell', 'checkbox', 'columnheader', 'gridcell', 'heading', 'link', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'option', 'radio', 'row', 'rowheader', 'switch', 'tab', 'tooltip', 'treeitem'];
  const DESCENDANT_NAMED_FROM_CONTENT = ['', 'caption', 'code', 'contentinfo', 'definition', 'deletion', 'emphasis', 'insertion', 'list', 'listitem', 'mark', 'none', 'paragraph', 'presentation', 'region', 'row', 'rowgroup', 'section', 'strong', 'subscript', 'superscript', 'table', 'term', 'time'];
  const VALUE_ROLES = ['progressbar', 'scrollbar', 'slider', 'spinbutton', 'meter'];
  // The text an element in a label, an aria-labelledby target or a link gives the name, as
  // Playwright's text alternative computes it. An aria-label or alt text stands in for the
  // element's content, a field inside gives its value, and content is read the way it is shown:
  // across shadow roots and slots, with line breaks and blocks set apart by spaces. Hidden
  // elements are left out unless the label itself is hidden. `ctx` holds the elements already
  // read, and `inTarget` when reading inside the named element itself.
  const textAlternative = (e, ctx) => {
    if (ctx.visited.has(e)) return '';
    const tag = tagOf(e);
    if (['STYLE', 'SCRIPT', 'NOSCRIPT', 'TEMPLATE'].includes(tag) || (!ctx.includeHidden && isHidden(e))) {
      ctx.visited.add(e);
      return '';
    }
    const refs = idRefs(e, 'aria-labelledby');
    if (!ctx.inLabelledBy) {
      const text = refs.map((ref) => textAlternative(ref, { visited: ctx.visited, inLabelledBy: true, includeHidden: isHidden(ref) })).join(' ');
      if (refs.length && text) return text;
    }
    const role = roleOf(e) || '';
    if (!refs.includes(e)) {
      if (role === 'textbox' || role === 'searchbox') {
        ctx.visited.add(e);
        return tag === 'INPUT' || tag === 'TEXTAREA' ? e.value : e.textContent || '';
      }
      // A dropdown reads as its chosen options; a combobox's are in the listbox it owns.
      if (role === 'combobox' || role === 'listbox') {
        ctx.visited.add(e);
        let chosen;
        if (tag === 'SELECT') {
          chosen = e.selectedOptions.length ? [...e.selectedOptions] : [...e.options].slice(0, 1);
        } else {
          const listbox = role === 'combobox' ? ownedMatching(e, '*').find((child) => roleOf(child) === 'listbox') : e;
          chosen = listbox ? ownedMatching(listbox, '[aria-selected="true"]').filter((child) => roleOf(child) === 'option') : [];
        }
        if (!chosen.length && tag === 'INPUT') return e.value;
        return chosen.map((option) => textAlternative(option, ctx)).join(' ');
      }
      if (VALUE_ROLES.includes(role)) {
        ctx.visited.add(e);
        for (const attr of ['aria-valuetext', 'aria-valuenow', 'value'])
          if (e.hasAttribute(attr)) return e.getAttribute(attr);
        return '';
      }
      if (role === 'menu') {
        ctx.visited.add(e);
        return '';
      }
    }
    const ariaLabel = e.getAttribute('aria-label') || '';
    if (ariaLabel.trim()) {
      ctx.visited.add(e);
      return ariaLabel;
    }
    const presentational = role === 'none' || role === 'presentation';
    if (!presentational && tag === 'INPUT' && ['button', 'submit', 'reset', 'image'].includes(e.type)) {
      ctx.visited.add(e);
      if (e.type === 'image') {
        const alt = e.getAttribute('alt') || '';
        return alt.trim() ? alt : (e.getAttribute('title') || '').trim() ? e.getAttribute('title') : 'Submit';
      }
      if (e.value.trim()) return e.value;
      return e.type === 'submit' ? 'Submit' : e.type === 'reset' ? 'Reset' : e.getAttribute('title') || '';
    }
    if (!presentational && tag === 'IMG') {
      ctx.visited.add(e);
      const alt = e.getAttribute('alt') || '';
      return alt.trim() ? alt : e.getAttribute('title') || '';
    }
    // A fieldset, figure or table is named by its legend, caption or summary; an SVG by its title.
    const caption = (childTag) => [...e.children].find((child) => tagOf(child) === childTag);
    const captionText = (child) => textAlternative(child, { visited: ctx.visited, inLabelledBy: ctx.inLabelledBy, includeHidden: isHidden(child) });
    if (!presentational && !refs.length && (tag === 'FIELDSET' || tag === 'FIGURE')) {
      ctx.visited.add(e);
      const child = caption(tag === 'FIELDSET' ? 'LEGEND' : 'FIGCAPTION');
      return child ? captionText(child) : e.getAttribute('title') || '';
    }
    if (!presentational && tag === 'TABLE') {
      ctx.visited.add(e);
      const child = caption('CAPTION');
      if (child) return captionText(child);
      const summary = e.getAttribute('summary') || '';
      if (summary) return summary;
    }
    if (!presentational && (tag === 'SVG' || e.ownerSVGElement)) {
      ctx.visited.add(e);
      const title = [...e.children].find((child) => tagOf(child) === 'TITLE' && child.ownerSVGElement);
      if (title) return textAlternative(title, { visited: ctx.visited, inLabelledBy: true, includeHidden: isHidden(title) });
    }
    if (!presentational && e.ownerSVGElement && tag === 'A') {
      const title = e.getAttribute('xlink:title') || '';
      if (title.trim()) {
        ctx.visited.add(e);
        return title;
      }
    }
    ctx.visited.add(e);
    // Inside a named element, only some roles give their content to the name.
    const content = !ctx.inTarget || NAMED_FROM_CONTENT.includes(role) || DESCENDANT_NAMED_FROM_CONTENT.includes(role) ? contentText(e, ctx) : '';
    if (content) return content;
    const title = e.getAttribute('title') || '';
    return (!presentational || ['IFRAME', 'FRAME'].includes(tag)) && title.trim() ? title : '';
  };
  // An element's content as a name reads it, Playwright's innerAccumulatedElementText.
  const contentText = (e, ctx) => {
    let text = cssContent(e, '::before');
    const own = ownCssContent(e);
    if (own !== undefined) {
      text += own;
    } else {
      const visit = (node, skipSlotted) => {
        if (skipSlotted && node.assignedSlot) return;
        if (node.nodeType === Node.ELEMENT_NODE) {
          const token = textAlternative(node, ctx);
          const block = ((style(node) || {}).display || 'inline') !== 'inline' || tagOf(node) === 'BR';
          text += block ? ' ' + token + ' ' : token;
        } else if (node.nodeType === Node.TEXT_NODE) {
          text += node.textContent || '';
        }
      };
      const assigned = tagOf(e) === 'SLOT' ? e.assignedNodes() : [];
      if (assigned.length) {
        for (const node of assigned) visit(node, false);
      } else {
        for (let child = e.firstChild; child; child = child.nextSibling) visit(child, true);
        if (e.shadowRoot)
          for (let child = e.shadowRoot.firstChild; child; child = child.nextSibling) visit(child, true);
        for (const owned of idRefs(e, 'aria-owns')) visit(owned, true);
      }
    }
    return text + cssContent(e, '::after');
  };
  // The accessible name of a link or text field, the two records whose properties decide how
  // their text prints. Follows the sources those roles use, in order.
  const nameOf = (record, run) => {
    const e = record.element;
    const targets = idRefs(e, 'aria-labelledby');
    const visited = new Set();
    const labelled = targets.map((ref) => textAlternative(ref, { visited, inLabelledBy: true, includeHidden: isHidden(ref) })).join(' ');
    if (targets.length && labelled) return normalize(labelled);
    const label = normalize(e.getAttribute('aria-label') || '');
    if (label) return label;
    if (record.role === 'link') {
      const content = normalize(contentText(e, { visited: new Set([e]), inLabelledBy: false, includeHidden: false, inTarget: true }));
      return content || normalize(e.getAttribute('title') || '');
    }
    if (!targets.length && e.labels && e.labels.length) {
      // The field itself reads as nothing inside its own label.
      visited.add(e);
      return normalize([...e.labels]
        .map((label) => textAlternative(label, { visited, inLabelledBy: false, includeHidden: isHidden(label) }))
        .filter(Boolean).join(' '));
    }
    const title = normalize(e.getAttribute('title') || '');
    const usesPlaceholder = e instanceof HTMLTextAreaElement ||
      (e instanceof HTMLInputElement && ['text', 'password', 'number', 'search', 'tel', 'email', 'url'].includes(e.type));
    return !usesPlaceholder || title ? title : normalize(e.getAttribute('placeholder') || '');
  };
  // Whether the snapshot prints `/url` or `/placeholder` under the record. Then a single run
  // prints as its own `text:` line rather than as the record's content, unless it repeats the
  // name, when it does not print at all.
  const hasProps = (record, run) => {
    const e = record.element;
    if (record.role === 'link') return e.hasAttribute('href');
    if (record.role === 'textbox') return e.hasAttribute('placeholder') && e.getAttribute('placeholder') !== nameOf(record, run);
    return false;
  };
  const standalone = (record, child) =>
    record.children.length > 1 || (hasProps(record, child.run) && child.run !== nameOf(record, child.run));

  // Parents as the page is shown: a slotted node sits in its slot, and text directly in a shadow
  // root is shown by its host.
  const composedParent = (node) => node.assignedSlot || node.parentElement || (node.parentNode && node.parentNode.host) || null;
  const holds = (element, node) => {
    for (let at = node; at; at = composedParent(at)) if (at === element) return true;
    return false;
  };
  // The smallest element under owner that holds every node of a run. A run's nodes are text
  // nodes and, for CSS-generated text, the element it was generated for.
  const holder = (nodes, owner) => {
    if (!nodes.length) return owner;
    const first = nodes[0];
    let candidate = (first.nodeType === Node.ELEMENT_NODE ? first : composedParent(first)) || owner;
    while (candidate !== owner && !nodes.every((node) => holds(candidate, node))) candidate = composedParent(candidate) || owner;
    return candidate;
  };

  const decode = (body) => {
    const bytes = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes));
  };

  const queryAll = (root, body) => {
    if (!body) return [];
    const spec = decode(body);
    if (spec.probe) return [(root.ownerDocument || root).documentElement];
    if (!spec.mode) return [];
    let pattern = null;
    try { pattern = new RegExp('^(?:' + spec.source + ')${'$'}', spec.flags); } catch (_) { pattern = null; }
    const matches = (text) => text === spec.literal || (pattern !== null && pattern.test(text));
    const doc = root.ownerDocument || root;
    const { fragment, records } = build(doc.documentElement);
    let result = [];
    if (spec.mode === 'content') {
      for (const record of records) {
        const children = record.children;
        if (children.length === 1 && children[0].run !== undefined && !hasProps(record, children[0].run) && matches(children[0].run)) result.push(record.element);
      }
    } else {
      // Snapshot order: a record's runs and nested records interleave as the snapshot prints them.
      const walk = (record) => {
        for (const child of record.children) {
          if (child.element) walk(child);
          else if (standalone(record, child) && matches(child.run)) result.push(holder(child.nodes, record.element));
        }
      };
      walk(fragment);
      if (typeof spec.occurrence === 'number') result = result[spec.occurrence] ? [result[spec.occurrence]] : [];
    }
    return root === doc ? result : result.filter((element) => root.contains(element));
  };

  return { query: (root, body) => queryAll(root, body)[0] || null, queryAll };
})()
  """.trimIndent()
}

/** `{"probe":true}`: the engine answers with the document element. */
private const val PROBE = "eyJwcm9iZSI6dHJ1ZX0="

private const val PROBE_TIMEOUT_MS = 2_000.0

/** How Playwright words the error for a selector naming an engine it does not have. */
private const val UNKNOWN_ENGINE = "Unknown engine"

private const val VALID_ROLES_JSON =
  """["alert","alertdialog","application","article","banner","blockquote","button","caption","cell","checkbox","code","columnheader","combobox","complementary","contentinfo","definition","deletion","dialog","directory","document","emphasis","feed","figure","form","generic","grid","gridcell","group","heading","img","insertion","link","list","listbox","listitem","log","main","mark","marquee","math","meter","menu","menubar","menuitem","menuitemcheckbox","menuitemradio","navigation","none","note","option","paragraph","presentation","progressbar","radio","radiogroup","region","row","rowgroup","rowheader","scrollbar","search","searchbox","separator","slider","spinbutton","status","strong","subscript","superscript","switch","tab","table","tablist","tabpanel","term","textbox","time","timer","toolbar","tooltip","tree","treegrid","treeitem"]"""

private const val NAME_PROHIBITED =
  """["caption","code","deletion","emphasis","generic","insertion","paragraph","presentation","strong","subscript","superscript"]"""

private const val GLOBAL_ARIA_JSON =
  """[["aria-atomic"],["aria-busy"],["aria-controls"],["aria-current"],["aria-describedby"],["aria-details"],["aria-dropeffect"],["aria-flowto"],["aria-grabbed"],["aria-hidden"],["aria-keyshortcuts"],["aria-label",$NAME_PROHIBITED],["aria-labelledby",$NAME_PROHIBITED],["aria-live"],["aria-owns"],["aria-relevant"],["aria-roledescription",["generic"]]]"""
