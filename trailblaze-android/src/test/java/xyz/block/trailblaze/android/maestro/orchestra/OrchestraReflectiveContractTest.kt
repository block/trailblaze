package xyz.block.trailblaze.android.maestro.orchestra

import maestro.MaestroException
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewmatcher.matching.ElementMatcherUsingMaestro
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The only thing that uses this package is `ElementMatcherUsingMaestro` in `trailblaze-common`, and
 * it gets here through kotlin-reflect: the class by fully-qualified name, then the PRIVATE
 * `buildFilter` by name and parameter count. Nothing about that coupling is visible to the compiler —
 * rename `Orchestra`, move its package, rename `buildFilter`, change its arity or make it `suspend`,
 * or add a constructor parameter without a default, and the build stays green while every recorded
 * selector stops resolving on a real device.
 *
 * That class only ever picks this fork up when it runs on-device: on the host the lookup misses and
 * it falls back to Maestro's own `Orchestra`. So on-device is the only place the breakage shows,
 * which is the worst place to find out. This test is the compiler that coupling doesn't have — it
 * drives the real matcher, on the JVM, with this fork on the classpath, so a broken contract fails
 * here instead of on a device.
 */
class OrchestraReflectiveContractTest {

  /**
   * A two-branch tree: one "Continue" sits inside the panel, a second "Continue" and "Cancel" sit
   * outside it. The duplicate is what makes a `childOf` selector prove scoping: matched against the
   * whole screen, "Continue" is ambiguous; scoped to the panel, it's the one inside. `childOf` runs
   * `buildFilter` once for the parent and once for the target.
   */
  private val root = ViewHierarchyTreeNode(
    nodeId = 1,
    className = "android.widget.FrameLayout",
    x1 = 0,
    y1 = 0,
    x2 = 1080,
    y2 = 2400,
    children = listOf(
      ViewHierarchyTreeNode(
        nodeId = 2,
        className = "android.widget.LinearLayout",
        resourceId = "com.example:id/panel",
        x1 = 0,
        y1 = 0,
        x2 = 1080,
        y2 = 1200,
        children = listOf(
          ViewHierarchyTreeNode(
            nodeId = 3,
            className = "android.widget.TextView",
            text = "Continue",
            clickable = true,
            x1 = 100,
            y1 = 100,
            x2 = 300,
            y2 = 200,
          ),
        ),
      ),
      ViewHierarchyTreeNode(
        nodeId = 4,
        className = "android.widget.TextView",
        text = "Cancel",
        clickable = true,
        x1 = 100,
        y1 = 1500,
        x2 = 300,
        y2 = 1600,
      ),
      ViewHierarchyTreeNode(
        nodeId = 5,
        className = "android.widget.TextView",
        text = "Continue",
        clickable = true,
        x1 = 100,
        y1 = 1700,
        x2 = 300,
        y2 = 1800,
      ),
    ),
  )

  private fun match(selector: TrailblazeElementSelector): ElementMatches =
    ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
      rootTreeNode = root,
      trailblazeElementSelector = selector,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = 1080,
      heightPixels = 2400,
    )

  /**
   * The rest of this class drives the matcher and asserts the RESULT — and the result is the same
   * whether the reflective lookup found this fork or fell back to Maestro's own `Orchestra`. So
   * this is the assertion that makes the others mean what they claim: it names the class the
   * lookup landed on, which is the thing a rename or a package move silently changes.
   */
  @Test
  fun `the matcher resolves this fork's Orchestra, not Maestro's`() {
    assertEquals(
      "xyz.block.trailblaze.android.maestro.orchestra.Orchestra",
      ElementMatcherUsingMaestro.resolvedOrchestraClassName,
      "The reflective Class.forName lookup fell back to Maestro's own Orchestra. On-device " +
        "selector matching runs on this fork; a miss here means it was renamed or repackaged, " +
        "and every recorded selector would stop resolving on a real device with the build green.",
    )
  }

  @Test
  fun `the matcher resolves a plain selector through this fork's buildFilter`() {
    val matches = match(TrailblazeElementSelector(textRegex = "Cancel"))

    assertTrue(
      matches is ElementMatches.SingleMatch,
      "Expected the reflectively-invoked buildFilter to match exactly the one \"Cancel\" node, " +
        "but got $matches. If this fails with \"Could not find buildFilter method\", the private " +
        "function ElementMatcherUsingMaestro looks up by name and arity was renamed or resigned.",
    )
    assertEquals("Cancel", matches.node.attributes["text"])
  }

  @Test
  fun `the matcher scopes a childOf selector to the parent's subtree`() {
    val matches = match(
      TrailblazeElementSelector(
        textRegex = "Continue",
        childOf = TrailblazeElementSelector(idRegex = "com\\.example:id/panel"),
      ),
    )

    assertTrue(
      matches is ElementMatches.SingleMatch,
      "Expected the childOf parent to scope the search to the panel and match its one " +
        "\"Continue\" node, but got $matches. Two matches means childOf was ignored and the " +
        "\"Continue\" outside the panel matched too.",
    )
    assertEquals("[100,100][300,200]", matches.node.attributes["bounds"], "Matched the \"Continue\" outside the panel.")
  }

  @Test
  fun `a childOf selector whose parent is absent matches nothing rather than matching loosely`() {
    val attempt = runCatching {
      match(
        TrailblazeElementSelector(
          textRegex = "Continue",
          childOf = TrailblazeElementSelector(idRegex = "com\\.example:id/does-not-exist"),
        ),
      )
    }

    // "Didn't match loosely" is not enough to assert, because ANY throw satisfies it — including
    // the ExceptionInInitializerError a broken reflective lookup raises out of this matcher's
    // object initializer, which is the one failure this whole class exists to catch. So pin WHICH
    // outcome: Maestro's own ElementNotFound from the parent lookup, or an empty match set.
    val failure = attempt.exceptionOrNull()
    if (failure == null) {
      assertTrue(
        attempt.getOrNull() is ElementMatches.NoMatches,
        "A childOf selector naming a parent that isn't in the hierarchy must not fall back to " +
          "matching the element anywhere on screen, but got ${attempt.getOrNull()}.",
      )
    } else {
      assertTrue(
        generateSequence(failure) { it.cause }.any { it is MaestroException.ElementNotFound },
        "The absent-parent lookup must fail as Maestro's own ElementNotFound (or return " +
          "NoMatches). Any other throwable means the reflective contract itself broke — an " +
          "ExceptionInInitializerError here is a renamed/re-arityed buildFilter, not a selector " +
          "that matched nothing. Got: $failure",
      )
    }
  }
}
