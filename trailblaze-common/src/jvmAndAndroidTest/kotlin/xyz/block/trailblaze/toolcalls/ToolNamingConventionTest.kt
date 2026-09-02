package xyz.block.trailblaze.toolcalls

import org.junit.Test
import kotlin.reflect.KClass
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Convention test for `@TrailblazeToolClass(name = ...)` values.
 *
 * Enforces the structural shape from the 2026-01-14 tool-naming-convention devlog
 * (see `docs/devlog/2026-01-14-tool-naming-convention.md`): snake-separated camelCase
 * segments, lowercase letter to start, no double underscores, no trailing underscore.
 * That regex catches sloppy formatting (`Adb_Shell`, `_foo`, `2tap`) but cannot tell
 * whether a single bare-verb name like `clearAppData` should have been
 * `android_clearAppData` — that requires reading the tool's semantics. Those
 * semantically-wrong-but-structurally-valid names are tracked in
 * [grandfatheredNonConformantNames] with a "rename to X" annotation. The list burns
 * down as renames land; when it empties, the convention is fully enforced.
 *
 * **Adding a new tool**: name it per the convention. Don't extend the allowlist.
 * **Renaming an existing grandfathered tool**: rename the `@TrailblazeToolClass(name = ...)`
 * value AND remove the old name from the allowlist in the same change. The
 * "allowlist must be a subset of actual names" check below fails loud if you forget,
 * preventing a stale entry from silently masking a future regression on the same name.
 */
class ToolNamingConventionTest {

  /**
   * Tool names known to violate the documented convention. Empty as of 2026-05-15 —
   * the migration completed when `adbShell` -> `android_adbShell`,
   * `androidSystemUiDemoMode` -> `android_systemUiDemoMode`, and
   * `clearAppData` -> `mobile_clearAppData` all landed together. New entries should
   * be added only when a pre-existing non-conformant name is discovered (not for
   * newly-authored tools — those must conform from the start). Each entry should
   * carry a "rename to X" comment naming the intended destination.
   */
  private val grandfatheredNonConformantNames: Set<String> = emptySet()

  /**
   * Structural shape:
   *   - first char is a lowercase letter
   *   - segments are camelCase identifiers (`[a-z][a-zA-Z0-9]*`)
   *   - segments are separated by single underscores (no `__`, no trailing `_`)
   *   - at most three segments, plus an optional `_v2`-style version suffix
   *
   * Accepts every valid form from the convention table — bare verb (`tap`), platform
   * primitive (`android_adbShell`), org+platform (`org_ios_configureTestUser`),
   * app+platform (`myapp_ios_scroll`). Rejects malformed shapes (`Tap`, `adb-shell`,
   * `android__foo`, `_tap`, `2tap`, `foo_`).
   *
   * The three-segment cap is what forces the verbNoun to stay a single camelCase token:
   * an unbounded `(_segment)*` accepts a fully snake_case name like
   * `android_view_assert_visible`, which the convention does not permit. The cap cannot
   * catch a snake_case verbNoun that happens to fit in three segments
   * (`compose_request_details`) — that still needs a reader.
   */
  private val conventionRegex = Regex("^[a-z][a-zA-Z0-9]*(_[a-z][a-zA-Z0-9]*){0,2}(_v[0-9]+)?$")

  private fun productionToolNames(): Map<String, KClass<out TrailblazeTool>> {
    val classes = TrailblazeToolSet.NonLlmTrailblazeTools +
      TrailblazeToolSet.DefaultLlmTrailblazeTools
    return classes.associateBy { it.trailblazeToolClassAnnotation().name }
  }

  @Test
  fun `every tool name matches the structural convention regex, or is grandfathered`() {
    val violations = productionToolNames().keys
      .filterNot { conventionRegex.matches(it) }
      .filterNot { it in grandfatheredNonConformantNames }
      .sorted()

    if (violations.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Tool name(s) violate the convention from 2026-01-14-tool-naming-convention.md:")
          appendLine()
          violations.forEach { appendLine("  - $it") }
          appendLine()
          appendLine("Use `{prefix}_{verbNoun}` with a camelCase verbNoun — `androidView_assertVisible`,")
          appendLine("not `android_view_assert_visible`. At most three underscore-separated segments,")
          appendLine("plus an optional `_v2` version suffix. Do NOT add to the")
          appendLine("grandfather allowlist without a 'rename to X' justification — the allowlist")
          appendLine("is for known pre-existing names only, not new violations.")
        },
      )
    }
  }

  @Test
  fun `grandfather allowlist is a strict subset of actual tool names (no stale entries)`() {
    // If a grandfathered name no longer appears in the production tool set, the allowlist
    // entry is stale — almost certainly because someone renamed the tool but forgot to
    // remove it here. Force the cleanup; otherwise a future regression that re-introduces
    // the same old name would be silently masked.
    val actualNames = productionToolNames().keys
    val stale = grandfatheredNonConformantNames.filterNot { it in actualNames }

    assertTrue(
      stale.isEmpty(),
      "Grandfather allowlist contains entries that don't match any current tool name: $stale. " +
        "Did you just rename one of these? Remove the old name from grandfatheredNonConformantNames.",
    )
  }
}
