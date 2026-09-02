package xyz.block.trailblaze.scripting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two gates on the runtime tool source, as policy.
 *
 * Both are security properties rather than conveniences, so each is asserted on its own and in the
 * combination that would defeat it — an opted-in target in a production process, and an
 * instrumented process against a target that never opted in. `RuntimeToolSourceOnDeviceTest` pins
 * the same gates end to end with a real pushed bundle; this pins the decision itself, including the
 * production case an instrumentation test cannot reach.
 */
class RuntimeToolSourceTest {

  @Test
  fun `a production install ignores the runtime path even for a target that opted in`() {
    // The dangerous combination: an APK whose signed config says "yes" that is installed and simply
    // launched. Nothing about that process is a test, and it must not load tool code off disk.
    assertEquals(
      RuntimeToolSource.Decision.Ignored(RuntimeToolSource.IgnoredReason.NOT_UNDER_INSTRUMENTATION),
      RuntimeToolSource.resolve(underInstrumentation = false, targetOptedIn = true),
    )
  }

  @Test
  fun `an instrumented run ignores the runtime path when the signed config did not opt in`() {
    // The ceremony guarantee. A shell someone else signed with the opt-in off replays from its
    // frozen assets even when a host is driving it, because it cannot tell one host from another.
    assertEquals(
      RuntimeToolSource.Decision.Ignored(RuntimeToolSource.IgnoredReason.TARGET_DID_NOT_OPT_IN),
      RuntimeToolSource.resolve(underInstrumentation = true, targetOptedIn = false),
    )
  }

  @Test
  fun `both gates open honors the device directory`() {
    assertEquals(
      RuntimeToolSource.Decision.Honored(RuntimeToolSource.DEVICE_DIRECTORY),
      RuntimeToolSource.resolve(underInstrumentation = true, targetOptedIn = true),
    )
  }

  @Test
  fun `a bundle path resolves under the directory, mirroring its asset path`() {
    assertEquals(
      "/data/local/tmp/trailblaze/tool-bundles/trails/config/trailmaps/checkout/tools/" +
        "checkout_launchAppSignedIn.bundle.js",
      RuntimeToolSource.filePathFor(
        directory = "/data/local/tmp/trailblaze/tool-bundles",
        bundlePath = "trails/config/trailmaps/checkout/tools/checkout_launchAppSignedIn.bundle.js",
      ),
    )
  }

  @Test
  fun `a bundle path authored with a leading dot-slash or slash resolves the same`() {
    // `McpServerConfig.script` accepts `./foo/bar.js`; the asset route normalizes the same way, and
    // a runtime file that silently failed to resolve would look like "the push didn't happen". The
    // trailing slash on the second root pins that a slash on either side does not double up.
    assertEquals("/tmp/bundles/a/b.bundle.js", RuntimeToolSource.filePathFor("/tmp/bundles", "./a/b.bundle.js"))
    assertEquals("/tmp/bundles/a/b.bundle.js", RuntimeToolSource.filePathFor("/tmp/bundles/", "/a/b.bundle.js"))
  }

  @Test
  fun `a traversing bundle path resolves to nothing`() {
    // Bundle paths come from target YAML, so they are consumer-controlled. A `..` must not be able
    // to point the loader at a file outside the pushed directory.
    assertNull(RuntimeToolSource.filePathFor("/tmp/bundles", "../etc/passwd"))
    assertNull(RuntimeToolSource.filePathFor("/tmp/bundles", "a/../../b.bundle.js"))
    assertNull(RuntimeToolSource.filePathFor("/tmp/bundles", ""))
  }
}
