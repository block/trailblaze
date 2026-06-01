package xyz.block.trailblaze.cli

import java.io.File
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

/**
 * JUnit rule that redirects `System.getProperty("user.home")` to a
 * `TemporaryFolder` for the duration of one test, then restores it.
 *
 * **Why a rule.** Several CLI tests need to drive helpers that walk
 * [xyz.block.trailblaze.ui.TrailblazeDesktopUtil.getDefaultAppDataDirectory]
 * (e.g. [writeShellDevicePinIfPossible], [clearShellDevicePinIfPossible],
 * [ShellDevicePinStore.pinFileFor]). Those all anchor on `user.home`, so
 * tests must redirect it to avoid touching the real `~/.trailblaze/`.
 *
 * **Nullable backing field.** The captured `originalUserHome` is intentionally
 * `String?` (rather than `lateinit var`) so a test that fails inside the
 * rule's `before()` block can still cleanly `after()` without crashing on an
 * uninitialized backing var — the original test failure stays the visible
 * one, not a confusing `UninitializedPropertyAccessException` from the
 * teardown path.
 *
 * Wraps a private [TemporaryFolder] with manual `create()` / `delete()` so
 * callers only have to declare ONE rule field on their test class. The temp
 * folder's path is exposed via [root] for tests that need it directly
 * (e.g. to seed a specific file pre-test).
 *
 * Usage:
 * ```kotlin
 * @Rule @JvmField val userHome = UserHomeRule()
 *
 * @Test fun something() {
 *   // user.home now points at userHome.root for the duration of the test
 * }
 * ```
 */
class UserHomeRule : ExternalResource() {

  private val tmp = TemporaryFolder()
  private var originalUserHome: String? = null

  /** The test's redirected `user.home` directory. */
  val root: File get() = tmp.root

  override fun before() {
    tmp.create()
    // Capture only AFTER tmp.create() succeeds — if it throws, we don't want
    // a stale `originalUserHome` poisoning the next test's @Before/@After.
    originalUserHome = System.getProperty("user.home")
    System.setProperty("user.home", tmp.root.absolutePath)
  }

  override fun after() {
    // Restore conditionally so a `before()` that throws before assignment
    // can still complete teardown without a NullPointerException, surfacing
    // the original failure rather than masking it.
    originalUserHome?.let { System.setProperty("user.home", it) }
    originalUserHome = null
    tmp.delete()
  }
}
