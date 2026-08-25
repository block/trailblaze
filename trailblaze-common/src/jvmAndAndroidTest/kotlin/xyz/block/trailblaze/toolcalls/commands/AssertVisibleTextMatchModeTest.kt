package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Covers [TextMatchMode] on [AssertVisibleBySelectorTrailblazeTool]: the mode-driven replay
 * compare in `verifyTextEquality`, the legacy Maestro `textRegex` lowering, the capture-time
 * volatile-token detector on [AssertVisibleTrailblazeTool], and back-compat deserialization.
 */
class AssertVisibleTextMatchModeTest {

  // region replay compare (verifyTextEquality, accessibility node-selector path)

  @Test
  fun `EXACT passes only when live text equals expected verbatim`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale\n3 items",
      expectedText = "Review sale\n3 items",
      mode = TextMatchMode.EXACT,
    )
    assertTrue(result is TrailblazeToolResult.Success)
  }

  @Test
  fun `EXACT fails when live text differs from expected`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale\n2 items",
      expectedText = "Review sale\n3 items",
      mode = TextMatchMode.EXACT,
    )
    assertTrue(result is TrailblazeToolResult.Error)
  }

  @Test
  fun `PREFIX passes against live text with a differing volatile tail`() = runBlocking {
    val three = runReplay(
      liveText = "Review sale\n3 items",
      expectedText = "Review sale",
      mode = TextMatchMode.PREFIX,
    )
    val two = runReplay(
      liveText = "Review sale\n2 items",
      expectedText = "Review sale",
      mode = TextMatchMode.PREFIX,
    )
    assertTrue(three is TrailblazeToolResult.Success, "3-item tail should pass under PREFIX")
    assertTrue(two is TrailblazeToolResult.Success, "2-item tail should pass under PREFIX")
  }

  @Test
  fun `PREFIX fails when the stable head is not present`() = runBlocking {
    val result = runReplay(
      liveText = "Add items\n3 items",
      expectedText = "Review sale",
      mode = TextMatchMode.PREFIX,
    )
    assertTrue(result is TrailblazeToolResult.Error)
  }

  @Test
  fun `REGEX matches a wildcarded count pattern`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale\n7 items",
      expectedText = "Review sale\\n\\d+ items",
      mode = TextMatchMode.REGEX,
    )
    assertTrue(result is TrailblazeToolResult.Success)
  }

  @Test
  fun `REGEX fails when the pattern does not match`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale\nno items",
      expectedText = "Review sale\\n\\d+ items",
      mode = TextMatchMode.REGEX,
    )
    assertTrue(result is TrailblazeToolResult.Error)
  }

  @Test
  fun `detector-produced pattern replays against a screen that dropped the count`() = runBlocking {
    // End-to-end: a "Review sale\n3 items" capture is rewritten by the detector, then replayed
    // against a screen showing just "Review sale" (the motivating absent-count failure).
    val captured = VolatileTextDetector.resolve("Review sale\n3 items")
    val result = runReplay(
      liveText = "Review sale",
      expectedText = captured.expectedText!!,
      mode = captured.mode,
    )
    assertTrue(result is TrailblazeToolResult.Success, "absent count must replay green")
  }

  @Test
  fun `textless container match finds the asserted text on a child (regression - MaxCalls loop)`() = runBlocking {
    // Selector lands on a structural container (no readable text of its own) while the asserted
    // text lives on a child — a mode-card-style container whose label is on a child node. Pre-fix
    // this failed with "Matched element has no readable text" and the runtime LLM re-issued the
    // identical assertion until its per-step call budget was exhausted.
    val result = runReplayOnContainer(
      containerResourceId = "mode_card",
      childText = "Active on 12 devices",
      expectedText = "Active on 12 devices",
    )
    assertTrue(result is TrailblazeToolResult.Success, "text on a child of the matched container must be found")
  }

  @Test
  fun `textless container without the expected text anywhere still fails (no false green)`() = runBlocking {
    val result = runReplayOnContainer(
      containerResourceId = "mode_card",
      childText = "Standard mode",
      expectedText = "Active on 12 devices",
    )
    assertTrue(result is TrailblazeToolResult.Error, "absent text must still fail — the subtree fallback must not auto-pass")
  }

  // endregion

  // region legacy Maestro textRegex lowering (toMaestroCommands)

  @Test
  fun `EXACT lowers the full expectedText and matches it verbatim`() {
    val regex = lowerMaestroTextRegex(expectedText = "Review sale\n3 items", mode = TextMatchMode.EXACT)
    assertTrue(asOrchestraWould(regex).matches("Review sale\n3 items"), "must match the text it was captured from")
    assertFalse(asOrchestraWould(regex).matches("Review sale\n2 items"), "EXACT must still pin the whole value")
  }

  @Test
  fun `EXACT matches UI copy containing regex metacharacters`() {
    // Real UI copy is full of `?`, `.` and `$`. Lowered unescaped, `receipt?` makes the trailing `t`
    // optional and `$` anchors mid-string, so the pattern can never match the very string it was
    // captured from — an assertion that fails forever, which the runtime agent then retries until
    // the test times out.
    listOf(
      "How would you like your receipt?",
      "Out of \$2.00",
      "Charge \$2.00",
      "Delete item (1)",
    ).forEach { text ->
      val regex = lowerMaestroTextRegex(expectedText = text, mode = TextMatchMode.EXACT)
      assertTrue(asOrchestraWould(regex).matches(text), "EXACT must match its own captured text: $text")
    }
  }

  @Test
  fun `EXACT stays case-sensitive despite Orchestra compiling with IGNORE_CASE`() {
    // EXACT is case-sensitive equality on the modern path (verifyTextEquality), so the lowered
    // pattern has to opt out of Orchestra's IGNORE_CASE or the two paths disagree on casing.
    val regex = lowerMaestroTextRegex(expectedText = "Charge \$2.00", mode = TextMatchMode.EXACT)
    assertTrue(asOrchestraWould(regex).matches("Charge \$2.00"), "same case must match")
    assertFalse(asOrchestraWould(regex).matches("charge \$2.00"), "different case must not match")
  }

  @Test
  fun `EXACT tolerates surrounding whitespace on either side, like the modern path`() {
    // verifyTextEquality compares expectedText.trim() against the trimmed element text, so a
    // captured value or a live element with padding must land the same way here.
    val padded = lowerMaestroTextRegex(expectedText = "  Review sale  ", mode = TextMatchMode.EXACT)
    assertTrue(asOrchestraWould(padded).matches("Review sale"), "padded capture must match clean text")

    val clean = lowerMaestroTextRegex(expectedText = "Review sale", mode = TextMatchMode.EXACT)
    assertTrue(asOrchestraWould(clean).matches("  Review sale  "), "clean capture must match padded text")
    assertFalse(asOrchestraWould(clean).matches("Review sales"), "trimming must not loosen the value itself")
  }

  @Test
  fun `PREFIX lowers only the escaped stable head with a tolerant tail`() {
    val regex = lowerMaestroTextRegex(expectedText = "Review sale", mode = TextMatchMode.PREFIX)
    // The volatile tail must not be pinned; the head is escaped and any tail (incl. newline) is
    // allowed. Compiled the way Orchestra will and full-matched — `containsMatchIn` would pass even
    // if the head were not anchored at the start, which is not the contract the legacy path uses.
    assertTrue(asOrchestraWould(regex).matches("Review sale\n3 items"), "should match a 3-item tail")
    assertTrue(asOrchestraWould(regex).matches("Review sale\n2 items"), "should match a 2-item tail")
    assertFalse(
      asOrchestraWould(regex).matches("Completed Review sale\n3 items"),
      "the head must be anchored at the start, not found anywhere in the value",
    )
    // The head itself stays a literal requirement — metacharacters in it are escaped, and a
    // different head must not match.
    val metaHead = lowerMaestroTextRegex(expectedText = "Out of \$2.00", mode = TextMatchMode.PREFIX)
    assertTrue(asOrchestraWould(metaHead).matches("Out of \$2.00 — restock?"), "head must match its own text")
    assertFalse(asOrchestraWould(metaHead).matches("Out of 12000 — restock?"), "head must not match as a regex")
  }

  @Test
  fun `REGEX forwards the expectedText through as the Maestro pattern`() {
    val regex = lowerMaestroTextRegex(expectedText = "Review sale.*", mode = TextMatchMode.REGEX)
    assertEquals("Review sale.*", regex)
  }

  @Test
  fun `REGEX with a malformed pattern lowers to a compilable Maestro textRegex`() {
    // A bad pattern must not reach Maestro raw (it would throw at compile time). It is escaped
    // to a literal so Maestro always gets something it can compile.
    val regex = lowerMaestroTextRegex(expectedText = "Review [sale", mode = TextMatchMode.REGEX)
    assertTrue(runCatching { Regex(regex) }.isSuccess, "lowered pattern must compile")
    assertTrue(Regex(regex).containsMatchIn("Review [sale"), "literal must match its own text")
  }

  // endregion

  // region NO-BREAK SPACE equivalence for literal text modes

  /**
   * A receipt-prompt assertion that could never pass, driven through both paths.
   *
   * The receipt prompt renders with a NO-BREAK SPACE. The agent read the tree and put that
   * character in the selector verbatim, then normalized it to a plain space in `expectedText` —
   * the string a human would type. Every comparison then read as "expected X, got X" while
   * failing, so the agent re-issued the identical call until the test timed out with NO_VERDICT.
   */
  private val nbspReceiptOnScreen = "How would you like your\u00A0receipt?"
  private val plainReceiptAsTyped = "How would you like your receipt?"

  @Test
  fun `EXACT matches NO-BREAK SPACE screen text from a plain-space assertion`() = runBlocking {
    val result = runReplay(
      liveText = nbspReceiptOnScreen,
      expectedText = plainReceiptAsTyped,
      mode = TextMatchMode.EXACT,
    )
    assertTrue(result is TrailblazeToolResult.Success, "a plain space must match a NO-BREAK SPACE on screen")
  }

  @Test
  fun `EXACT lowers a plain-space assertion to a pattern that matches NO-BREAK SPACE text`() {
    val regex = lowerMaestroTextRegex(expectedText = plainReceiptAsTyped, mode = TextMatchMode.EXACT)
    assertTrue(
      asOrchestraWould(regex).matches(nbspReceiptOnScreen),
      "the legacy Maestro path must match the NO-BREAK SPACE text the assertion was written for",
    )
  }

  @Test
  fun `EXACT matches in the other direction too - NO-BREAK SPACE assertion against plain-space text`() = runBlocking {
    // Symmetric: an assertion captured off a NO-BREAK SPACE screen must not start failing when the
    // app ships a build that uses a plain space.
    val result = runReplay(
      liveText = plainReceiptAsTyped,
      expectedText = nbspReceiptOnScreen,
      mode = TextMatchMode.EXACT,
    )
    assertTrue(result is TrailblazeToolResult.Success)
    val regex = lowerMaestroTextRegex(expectedText = nbspReceiptOnScreen, mode = TextMatchMode.EXACT)
    assertTrue(asOrchestraWould(regex).matches(plainReceiptAsTyped), "legacy path must be symmetric too")
  }

  @Test
  fun `space folding covers every Zs separator, not just the non-breaking ones`() = runBlocking {
    // Two mechanisms were supposed to absorb these and each misses a different part of Zs, so no
    // subset is safe to special-case:
    //   - U+00A0 / U+2007 / U+202F are not `Char.isWhitespace`, so `trim()` leaves them.
    //   - the other 13 are not matched by Java's ASCII-only `\s`.
    // EN SPACE and THIN SPACE are in the second group \u2014 as invisible as NBSP and as common in
    // typeset UI copy \u2014 and were the gap in the first version of this fix.
    // All 17 Zs members, spelled as code points — these characters are invisible in source.
    val separators = listOf(
      0x0020, 0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
      0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x202F, 0x205F, 0x3000,
    ).map { it.toChar() }
    for (sep in separators) {
      val label = "U+%04X".format(sep.code)
      val replay = runReplay(
        liveText = "Total${sep}due",
        expectedText = "Total due",
        mode = TextMatchMode.EXACT,
      )
      assertTrue(replay is TrailblazeToolResult.Success, "$label should fold on the modern path")

      val regex = lowerMaestroTextRegex(expectedText = "Total due", mode = TextMatchMode.EXACT)
      assertTrue(
        asOrchestraWould(regex).matches("Total${sep}due"),
        "$label should fold on the legacy path",
      )
    }
  }

  @Test
  fun `edge padding tolerates a Zs separator the modern path trims away`() {
    // `trim()` strips the 13 Zs separators that ARE `Char.isWhitespace`, so the modern path absorbs
    // a leading EN SPACE silently. A plain `\s*` on the pattern cannot match one, which would make
    // the same screen pass on one driver and fail on the other.
    val exact = lowerMaestroTextRegex(expectedText = "Review sale", mode = TextMatchMode.EXACT)
    assertTrue(asOrchestraWould(exact).matches("\u2002Review sale\u2002"), "EXACT must tolerate Zs padding")
    assertTrue(asOrchestraWould(exact).matches("\u00A0Review sale"), "including the non-breaking ones")

    val prefix = lowerMaestroTextRegex(expectedText = "Review sale", mode = TextMatchMode.PREFIX)
    assertTrue(
      asOrchestraWould(prefix).matches("\u2002Review sale\n3 items"),
      "PREFIX must tolerate leading Zs padding the modern path trims",
    )
  }

  @Test
  fun `space folding does not widen to newlines or tabs`() = runBlocking {
    // Folding is strictly about characters that look like a space. A newline carries real layout
    // meaning in these assertions (the "Review sale\n3 items" family), so it must stay distinct.
    val newline = runReplay(
      liveText = "Review sale\n3 items",
      expectedText = "Review sale 3 items",
      mode = TextMatchMode.EXACT,
    )
    assertTrue(newline is TrailblazeToolResult.Error, "a newline must not fold into a space")

    val regex = lowerMaestroTextRegex(expectedText = "Review sale 3 items", mode = TextMatchMode.EXACT)
    assertFalse(asOrchestraWould(regex).matches("Review sale\n3 items"), "legacy path must not fold newlines")
    assertFalse(asOrchestraWould(regex).matches("Review sale\t3 items"), "legacy path must not fold tabs")
  }

  @Test
  fun `space folding keeps distinct text distinct`() = runBlocking {
    // Guard the widening: folding must not make unrelated strings equal.
    val result = runReplay(
      liveText = "How would you like your\u00A0receipt?",
      expectedText = "How would you like your refund?",
      mode = TextMatchMode.EXACT,
    )
    assertTrue(result is TrailblazeToolResult.Error, "folding spaces must not loosen the rest of the value")
  }

  @Test
  fun `PREFIX folds space variants in the stable head`() = runBlocking {
    val result = runReplay(
      liveText = "How would you like your\u00A0receipt?\nEmail or text",
      expectedText = plainReceiptAsTyped,
      mode = TextMatchMode.PREFIX,
    )
    assertTrue(result is TrailblazeToolResult.Success)
  }

  @Test
  fun `REGEX does not fold - the author's pattern means exactly what it spells`() = runBlocking {
    // A hand-written pattern is not a literal, so a space in it stays a space. Authors who want
    // tolerance spell the class themselves; silently rewriting their pattern would be worse.
    val plainPattern = runReplay(
      liveText = nbspReceiptOnScreen,
      expectedText = "How would you like your receipt\\?",
      mode = TextMatchMode.REGEX,
    )
    assertTrue(plainPattern is TrailblazeToolResult.Error, "a plain space in a REGEX pattern stays literal")

    val explicitClass = runReplay(
      liveText = nbspReceiptOnScreen,
      expectedText = "How would you like your[ \\u00A0]receipt\\?",
      mode = TextMatchMode.REGEX,
    )
    assertTrue(explicitClass is TrailblazeToolResult.Success, "the author-spelled class is the REGEX escape hatch")
  }

  // endregion

  // region selector textRegex + expectedText precedence (both must hold)

  @Test
  fun `a selector textRegex is not discarded when expectedText is also set`() {
    // Pre-fix, expectedText overwrote the selector's own textRegex outright — so the selector's
    // constraint silently stopped being enforced on the legacy path. Both must survive into the
    // single Maestro text slot.
    val regex = lowerMaestroTextRegex(
      expectedText = "Charge \$2.00",
      mode = TextMatchMode.EXACT,
      selectorTextRegex = "Charge .*",
    )
    assertTrue(asOrchestraWould(regex).matches("Charge \$2.00"), "text satisfying both must match")
    assertFalse(
      asOrchestraWould(regex).matches("Refund \$2.00"),
      "text failing the selector's textRegex must not match, even though it isn't what expectedText pins",
    )
  }

  @Test
  fun `expectedText still narrows a permissive selector textRegex`() {
    // The other direction: the selector is broad, expectedText is the precise pin. This is why the
    // fold exists at all — on a driver with no node tree, this pattern is the only thing enforcing
    // expectedText.
    val regex = lowerMaestroTextRegex(
      expectedText = "Charge \$2.00",
      mode = TextMatchMode.EXACT,
      selectorTextRegex = "Charge .*",
    )
    assertFalse(asOrchestraWould(regex).matches("Charge \$5.00"), "expectedText must still pin the value")
  }

  @Test
  fun `a selector textRegex narrower than expectedText is still enforced`() {
    // The isolating case for the clobber: PREFIX admits any tail, so "Charge $5.00" satisfies
    // expectedText outright. Only the selector's own textRegex rules it out — if that gets
    // overwritten, this passes when it should not. An EXACT expectedText cannot show this, because
    // pinning the value exactly leaves nothing that satisfies it while violating the selector.
    val regex = lowerMaestroTextRegex(
      expectedText = "Charge",
      mode = TextMatchMode.PREFIX,
      selectorTextRegex = "Charge \\\$2\\.00",
    )
    assertTrue(asOrchestraWould(regex).matches("Charge \$2.00"), "text satisfying both must match")
    assertFalse(
      asOrchestraWould(regex).matches("Charge \$5.00"),
      "expectedText's tail tolerance must not override the selector's narrower text constraint",
    )
  }

  @Test
  fun `conjunction preserves EXACT case-sensitivity on the expectedText side only`() {
    // EXACT's `(?-i)` has to stay scoped to its own side of the join: the selector was authored
    // under Maestro, where IGNORE_CASE applies, and must keep that meaning.
    val regex = lowerMaestroTextRegex(
      expectedText = "Charge \$2.00",
      mode = TextMatchMode.EXACT,
      selectorTextRegex = "charge .*",
    )
    assertTrue(asOrchestraWould(regex).matches("Charge \$2.00"), "selector keeps Maestro's IGNORE_CASE")
    assertFalse(asOrchestraWould(regex).matches("charge \$2.00"), "expectedText stays case-sensitive")
  }

  @Test
  fun `conjunction does not let the selector match only up to a line break`() {
    // Orchestra compiles with MULTILINE, so anchoring the selector side with `$` would let it stop
    // at a newline and pass on text it does not actually cover.
    val regex = lowerMaestroTextRegex(
      expectedText = "Review sale\n3 items",
      mode = TextMatchMode.EXACT,
      selectorTextRegex = "Review sale",
    )
    assertFalse(
      asOrchestraWould(regex).matches("Review sale\n3 items"),
      "the selector's textRegex must have to cover the whole value, not just its first line",
    )
  }

  @Test
  fun `conjunction leaves a top-level alternation in either side intact`() {
    // Overlapping-but-different alternations on each side: only their intersection may match, and a
    // top-level `|` must not span the join. "Offline" satisfies expectedText alone, so it also
    // catches the selector's constraint being dropped.
    val regex = lowerMaestroTextRegex(
      expectedText = "Ready|Offline",
      mode = TextMatchMode.REGEX,
      selectorTextRegex = "Ready|Busy",
    )
    assertTrue(asOrchestraWould(regex).matches("Ready"), "the value both sides admit must match")
    assertFalse(asOrchestraWould(regex).matches("Busy"), "expectedText must still apply")
    assertFalse(asOrchestraWould(regex).matches("Offline"), "the selector's textRegex must still apply")
  }

  @Test
  fun `an uncompilable selector textRegex degrades to a literal without swallowing expectedText`() {
    // Maestro's own `toRegexSafe` treats an invalid pattern as a literal. Inlining it raw would
    // make the *combined* pattern invalid, so Orchestra would degrade the whole thing — dropping
    // the expectedText constraint along with it. PREFIX here so "Review sale" satisfies
    // expectedText and only the degraded selector literal excludes it.
    val regex = lowerMaestroTextRegex(
      expectedText = "Review",
      mode = TextMatchMode.PREFIX,
      selectorTextRegex = "Review [sale",
    )
    assertTrue(runCatching { asOrchestraWould(regex) }.isSuccess, "combined pattern must compile")
    assertTrue(asOrchestraWould(regex).matches("Review [sale"), "literal must match its own text")
    assertFalse(
      asOrchestraWould(regex).matches("Review sale"),
      "the invalid selector pattern must still constrain, as Maestro's own toRegexSafe literal would",
    )
  }

  @Test
  fun `a selector textRegex that cannot match as a regex still matches as an exact literal`() {
    // Selectors are regex-OR-exact-literal everywhere else: the resolver's `matchesPattern` falls
    // back to `text == pattern`, mirroring Maestro's own `Filters.textMatches`. A recorded
    // `textRegex` of "$5.00" compiles fine but can never match — a bare `$` anchors the end, so
    // nothing can follow it — and is accepted only by that literal leg. Conjoining the regex alone
    // would fail every recorded price assertion on the fallback drivers.
    val regex = lowerMaestroTextRegex(
      expectedText = "\$5.00",
      mode = TextMatchMode.EXACT,
      selectorTextRegex = "\$5.00",
    )
    assertFalse(
      asOrchestraWould("\$5.00").matches("\$5.00"),
      "premise: the selector pattern genuinely cannot match its own text as a regex",
    )
    assertTrue(asOrchestraWould(regex).matches("\$5.00"), "the exact-literal leg must accept it")
    assertFalse(asOrchestraWould(regex).matches("\$9.00"), "and must not accept a different price")
  }

  @Test
  fun `a numbered backreference in expectedText survives the join`() {
    // Group numbers run left to right across the whole pattern, lookaheads included, so whichever
    // side is second gets its groups renumbered and its `\1` silently retargeted. expectedText is
    // the hand-authored side, so it goes first and keeps its numbering.
    val regex = lowerMaestroTextRegex(
      expectedText = "(foo)\\1",
      mode = TextMatchMode.REGEX,
      selectorTextRegex = "(f).*",
    )
    assertTrue(
      asOrchestraWould("(foo)\\1").matches("foofoo"),
      "premise: the expected pattern matches this on its own",
    )
    assertTrue(asOrchestraWould(regex).matches("foofoo"), "and must still match after the join")
    assertFalse(asOrchestraWould(regex).matches("foobar"), "the backreference must still bind")
  }

  @Test
  fun `a selector without a textRegex lowers to the expectedText pattern alone`() {
    // The overwhelmingly common shape (no selector text constraint) gets no conjunction wrapper —
    // just the escaped literal, with each space widened to the space-separator class. Pinned
    // exactly because this string is the contract with a third-party regex engine.
    val regex = lowerMaestroTextRegex(expectedText = "Charge \$2.00", mode = TextMatchMode.EXACT)
    assertEquals("""(?-i)[\s\p{Zs}]*\QCharge\E\p{Zs}\Q${'$'}2.00\E[\s\p{Zs}]*""", regex)
  }

  @Test
  fun `a NO-BREAK SPACE selector and a plain-space expectedText both match the screen`() {
    // The full original shape: selector carries the NO-BREAK SPACE it read out of the tree,
    // expectedText carries the normalized plain space, and both must hold against the real text.
    val regex = lowerMaestroTextRegex(
      expectedText = plainReceiptAsTyped,
      mode = TextMatchMode.EXACT,
      selectorTextRegex = Regex.escape(nbspReceiptOnScreen),
    )
    assertTrue(
      asOrchestraWould(regex).matches(nbspReceiptOnScreen),
      "selector + expectedText must both hold against the text they were captured from",
    )
  }

  // endregion

  // region capture detector

  @Test
  fun `detector pins the head and tolerates the count changing or disappearing`() {
    val resolved = VolatileTextDetector.resolve("Review sale\n3 items")
    assertEquals(TextMatchMode.REGEX, resolved.mode)
    val pattern = Regex(resolved.expectedText!!)
    // The changing count is tolerated, and so is the count vanishing entirely (the motivating
    // replay where "Review sale\n3 items" later renders as just "Review sale").
    assertTrue(pattern.matches("Review sale\n3 items"))
    assertTrue(pattern.matches("Review sale\n2 items"))
    assertTrue(pattern.matches("Review sale"), "count disappearing must still pass")
    // ...but the stable head stays an exact requirement: unrelated tails must NOT pass.
    assertTrue(!pattern.matches("Review sale\nInventory unavailable"), "unrelated tail must fail")
    assertTrue(!pattern.matches("Add items\n3 items"), "different head must fail")
  }

  @Test
  fun `detector keeps a count-only label EXACT so a pinned count still catches a wrong count`() {
    val resolved = VolatileTextDetector.resolve("1 item")
    assertEquals("1 item", resolved.expectedText)
    assertEquals(TextMatchMode.EXACT, resolved.mode)
  }

  @Test
  fun `detector leaves inline item-count copy EXACT (only a trailing subtitle is volatile)`() {
    // The count is part of stable authored copy, not a trailing volatile subtitle, so it must
    // stay an exact assertion rather than being relaxed.
    for (stable in listOf("Buy 2 items get 1 free", "Minimum 3 items required", "3 items in cart")) {
      val resolved = VolatileTextDetector.resolve(stable)
      assertEquals(stable, resolved.expectedText, "should not rewrite: $stable")
      assertEquals(TextMatchMode.EXACT, resolved.mode, "should stay EXACT: $stable")
    }
  }

  @Test
  fun `detector leaves stable currency text as EXACT`() {
    val resolved = VolatileTextDetector.resolve("Charge \$5.00")
    assertEquals("Charge \$5.00", resolved.expectedText)
    assertEquals(TextMatchMode.EXACT, resolved.mode)
  }

  @Test
  fun `detector leaves null as EXACT`() {
    val resolved = VolatileTextDetector.resolve(null)
    assertEquals(null, resolved.expectedText)
    assertEquals(TextMatchMode.EXACT, resolved.mode)
  }

  @Test
  fun `capture forwards a volatile item count as a tolerant REGEX through the delegate`() {
    val delegated = captureDelegate(expectedText = "Review sale\n3 items")
    assertEquals(TextMatchMode.REGEX, delegated.textMatchMode)
    val pattern = Regex(delegated.expectedText!!)
    assertTrue(pattern.matches("Review sale\n2 items"))
    assertTrue(pattern.matches("Review sale"))
    assertTrue(!pattern.matches("Review sale\narchived"))
  }

  @Test
  fun `capture leaves stable text as EXACT through the delegate`() {
    val delegated = captureDelegate(expectedText = "Charge \$5.00")
    assertEquals("Charge \$5.00", delegated.expectedText)
    assertEquals(TextMatchMode.EXACT, delegated.textMatchMode)
  }

  // endregion

  // region malformed REGEX is a clean assertion failure, not an infra crash

  @Test
  fun `malformed REGEX pattern fails the assertion instead of throwing`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale",
      expectedText = "Review [sale", // unbalanced bracket — invalid pattern
      mode = TextMatchMode.REGEX,
    )
    assertTrue(result is TrailblazeToolResult.Error, "invalid regex should surface as a failure")
  }

  // endregion

  // region back-compat deserialization

  @Test
  fun `tool deserialized from YAML lacking textMatchMode defaults to EXACT`() {
    val yaml = TrailblazeYaml.Default.getInstance()
    val decoded = yaml.decodeFromString(
      AssertVisibleBySelectorTrailblazeTool.serializer(),
      """
      |selector:
      |  textRegex: "Review sale"
      |expectedText: "Review sale"
      """.trimMargin(),
    )
    assertEquals(TextMatchMode.EXACT, decoded.textMatchMode)
    assertEquals("Review sale", decoded.expectedText)
  }

  @Test
  fun `recorded assertVisibleBySelector keeps an exact item-count assertion at replay`() = runBlocking {
    // Recordings store the lowered assertVisibleBySelector (not the ref-based assertVisible), so
    // the capture-time VolatileTextDetector never re-runs at replay. A trail that deliberately
    // pins "Review sale\n3 items" must therefore still fail when the live count differs — the
    // detector does not silently relax an already-recorded exact assertion.
    val yaml = TrailblazeYaml.Default.getInstance()
    val decoded = yaml.decodeFromString(
      AssertVisibleBySelectorTrailblazeTool.serializer(),
      """
      |expectedText: "Review sale\n3 items"
      """.trimMargin(),
    )
    assertEquals(TextMatchMode.EXACT, decoded.textMatchMode, "no rewrite happens on a recorded tool")
    val result = runReplay(
      liveText = "Review sale\n2 items",
      expectedText = "Review sale\n3 items",
      mode = decoded.textMatchMode,
    )
    assertTrue(result is TrailblazeToolResult.Error, "a pinned exact count must still fail on a mismatch")
  }

  // endregion

  // region helpers

  private suspend fun runReplay(
    liveText: String,
    expectedText: String,
    mode: TextMatchMode,
  ): TrailblazeToolResult {
    val matchedNode = TrailblazeNode(
      nodeId = 2,
      ref = "y778",
      bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        text = liveText,
        resourceId = "review_sale_row",
      ),
    )
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(matchedNode),
    )
    // Resolve by a stable resourceId so the post-pass re-resolution is independent of the
    // volatile text under test — the mode-driven compare is what we're exercising.
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "review_sale_row"),
    )
    val tool = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = nodeSelector,
      expectedText = expectedText,
      textMatchMode = mode,
    )
    return tool.execute(replayContext(tree))
  }

  /**
   * Resolves to a textless structural container (matched by resourceId) whose asserted text lives
   * on a [childText] child — exercises the subtree fallback in `collectTextCandidates`.
   */
  private suspend fun runReplayOnContainer(
    containerResourceId: String,
    childText: String,
    expectedText: String,
    mode: TextMatchMode = TextMatchMode.EXACT,
  ): TrailblazeToolResult {
    val child = TrailblazeNode(
      nodeId = 3,
      ref = "child1",
      bounds = TrailblazeNode.Bounds(110, 210, 290, 250),
      driverDetail = DriverNodeDetail.AndroidAccessibility(text = childText),
    )
    val container = TrailblazeNode(
      nodeId = 2,
      ref = "card",
      bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
      driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = containerResourceId),
      children = listOf(child),
    )
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(container),
    )
    val tool = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(resourceIdRegex = containerResourceId),
      ),
      expectedText = expectedText,
      textMatchMode = mode,
    )
    return tool.execute(replayContext(tree))
  }

  /**
   * Lowers to the Maestro `textRegex` the legacy fallback path would send to Orchestra.
   *
   * The selector anchors on a resourceId by default so the lowered pattern under test comes purely
   * from [expectedText]. Pass [selectorTextRegex] to exercise the case where the selector carries
   * its own text constraint too — those must both survive into the single Maestro text slot.
   */
  private fun lowerMaestroTextRegex(
    expectedText: String,
    mode: TextMatchMode,
    selectorTextRegex: String? = null,
  ): String {
    val tool = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(
          textRegex = selectorTextRegex,
          resourceIdRegex = "review_sale_row",
        ),
      ),
      expectedText = expectedText,
      textMatchMode = mode,
    )
    val commands: List<Command> = tool.toMaestroCommands()
    val assertCommand = assertIs<AssertConditionCommand>(commands.single())
    return assertCommand.condition.visible?.textRegex
      ?: error("expected a visible textRegex on the lowered Maestro selector")
  }

  /**
   * Compiles a lowered `textRegex` the way Maestro will at replay: Orchestra's `REGEX_OPTIONS`.
   * Compiling with Kotlin's defaults instead would hide whether the pattern survives IGNORE_CASE,
   * which is the whole question for [TextMatchMode.EXACT].
   */
  private fun asOrchestraWould(textRegex: String): Regex = Regex(
    textRegex,
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE),
  )

  private fun captureDelegate(expectedText: String): AssertVisibleBySelectorTrailblazeTool {
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          ref = "y778",
          bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = expectedText),
        ),
      ),
    )
    return assertIs(
      AssertVisibleTrailblazeTool(ref = "y778", expectedText = expectedText)
        .toExecutableTrailblazeTools(captureContext(tree))
        .single(),
    )
  }

  private fun replayContext(tree: TrailblazeNode): TrailblazeToolExecutionContext {
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode = tree
    }
    val agent = AlwaysVisibleAgent()
    return TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
      maestroTrailblazeAgent = agent,
      nodeSelectorMode = NodeSelectorMode.PREFER_NODE_SELECTOR,
    )
  }

  private fun captureContext(tree: TrailblazeNode): TrailblazeToolExecutionContext {
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode = tree
    }
    return TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "t",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1000,
        heightPixels = 1000,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("t"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }

  /** Reports the visibility check as passed so `execute()` reaches the text post-pass. */
  private class AlwaysVisibleAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-instance",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
  ) {
    override suspend fun executeNodeSelectorAssertVisible(
      nodeSelector: TrailblazeNodeSelector,
      timeoutMs: Long?,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  // endregion
}
