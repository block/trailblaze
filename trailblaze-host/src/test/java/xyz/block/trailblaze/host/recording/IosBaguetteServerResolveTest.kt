package xyz.block.trailblaze.host.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Behavioral tests for baguette resolution: they pin the observable contract the `/devices` endpoint
 * relies on — which binary (if any) the iOS H.264 path runs, given what's installed — so the
 * optional-dependency fallback (no baguette → decline → browser JPEG poll) can't silently regress.
 * The real filesystem/PATH lookups are injected, so no environment setup is needed.
 */
class IosBaguetteServerResolveTest {

  private val homebrewPath = "/opt/homebrew/bin/baguette"

  private fun resolve(
    envOverride: String? = null,
    executable: Set<String> = emptySet(),
    onPath: Set<String> = emptySet(),
  ): String? =
    IosBaguetteServer.resolveBaguettePath(
      envOverride = envOverride,
      isExecutable = { it in executable },
      isOnPath = { it in onPath },
    )

  @Test
  fun `returns null when baguette is installed nowhere`() {
    assertNull(resolve(), "with no override, PATH, or Homebrew binary the iOS path must decline")
  }

  @Test
  fun `an executable env override is used verbatim`() {
    val custom = "/custom/bin/baguette"
    assertEquals(custom, resolve(envOverride = custom, executable = setOf(custom)))
  }

  @Test
  fun `a non-executable env override falls through to the next source`() {
    val custom = "/custom/bin/baguette"
    assertEquals(
      "baguette",
      resolve(envOverride = custom, executable = emptySet(), onPath = setOf("baguette")),
      "a bad override must not win; PATH is the next candidate",
    )
  }

  @Test
  fun `a blank env override is ignored`() {
    assertEquals("baguette", resolve(envOverride = "   ", onPath = setOf("baguette")))
  }

  @Test
  fun `PATH is preferred over the Homebrew location`() {
    assertEquals(
      "baguette",
      resolve(onPath = setOf("baguette"), executable = setOf(homebrewPath)),
    )
  }

  @Test
  fun `falls back to the native Homebrew binary when it is the only source`() {
    assertEquals(homebrewPath, resolve(executable = setOf(homebrewPath)))
  }

  @Test
  fun `env override outranks both PATH and Homebrew`() {
    val custom = "/custom/bin/baguette"
    assertEquals(
      custom,
      resolve(
        envOverride = custom,
        executable = setOf(custom, homebrewPath),
        onPath = setOf("baguette"),
      ),
    )
  }
}
