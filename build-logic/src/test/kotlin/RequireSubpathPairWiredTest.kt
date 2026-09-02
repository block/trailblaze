import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException

/**
 * Unit tests for [requireSubpathPairWired] — the "both halves or neither" gate each
 * `@trailblaze/scripting/<subpath>` artifact pair goes through at bundle time.
 *
 * The invariant is worth a direct test because the cost of it not holding is paid far from
 * the cause: a workspace that receives a declaration bundle with no runtime module fails
 * later, inside a trailmap author's `bun test`, as an opaque module-resolution error (and the
 * reverse fails as a `tsc` unresolved import). This gate is the only thing that turns that
 * into a Gradle-time message naming the missing property, so both directions and the message
 * content are pinned here rather than left to a functional test that would need a real
 * Gradle build to reach the same branch.
 */
class RequireSubpathPairWiredTest {

  private fun check(dtsSet: Boolean, runtimeSet: Boolean) = requireSubpathPairWired(
    subpath = "matcher",
    dtsPropertyName = "sdkDtsMatcherBundleOutputFile",
    dtsFileName = "matcher.d.ts",
    dtsSet = dtsSet,
    runtimePropertyName = "sdkMatcherRuntimeOutputFile",
    runtimeFileName = "matcher.js",
    runtimeSet = runtimeSet,
  )

  @Test
  fun `both halves wired passes`() {
    check(dtsSet = true, runtimeSet = true)
  }

  @Test
  fun `neither half wired passes — the subpath is simply not shipped`() {
    check(dtsSet = false, runtimeSet = false)
  }

  @Test
  fun `declaration bundle without a runtime module fails, naming the missing property`() {
    val error = assertFailsWith<GradleException> { check(dtsSet = true, runtimeSet = false) }
    val message = error.message ?: ""
    // Assert the DIRECTION, not just that both names appear: the two property names are
    // interchangeable arguments, so a swap would still mention both while telling the
    // developer to add the line they already have.
    assertTrue(
      message.contains("sdkDtsMatcherBundleOutputFile is set but sdkMatcherRuntimeOutputFile is not"),
      "expected the runtime property named as the missing one; got: $message",
    )
    assertTrue(message.contains("matcher.js"), "expected the runtime file name; got: $message")
  }

  @Test
  fun `runtime module without a declaration bundle fails, naming the missing property`() {
    val error = assertFailsWith<GradleException> { check(dtsSet = false, runtimeSet = true) }
    val message = error.message ?: ""
    assertTrue(
      message.contains("sdkMatcherRuntimeOutputFile is set but sdkDtsMatcherBundleOutputFile is not"),
      "expected the declaration property named as the missing one; got: $message",
    )
    assertTrue(message.contains("matcher.d.ts"), "expected the declaration file name; got: $message")
  }
}
