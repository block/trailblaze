package xyz.block.trailblaze.capture.video

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * What a recorder watching a device through [WebScreencastFeedRegistry.watch] is told: the feed
 * there now, then every real change to it — and nothing once it stops watching.
 */
class WebScreencastFeedRegistryTest {

  private val deviceId = "registry-test-browser"
  private val handles = mutableListOf<AutoCloseable>()

  private class Feed : WebScreencastFeedRegistry.Feed {
    override fun subscribe(onFrame: (jpeg: ByteArray, hostTimestampMs: Long) -> Unit) = AutoCloseable {}
  }

  /** Every feed a watcher was handed, in order (null: the browser went away). */
  private fun watch(seen: MutableList<WebScreencastFeedRegistry.Feed?> = mutableListOf()) =
    seen.also { handles += WebScreencastFeedRegistry.watch(deviceId) { feed -> it += feed } }

  @AfterTest
  fun tearDown() {
    handles.forEach { it.close() }
    WebScreencastFeedRegistry.get(deviceId)?.let { WebScreencastFeedRegistry.unregister(deviceId, it) }
  }

  @Test
  fun `a watcher is told the feed already there, then each change`() {
    val first = Feed()
    WebScreencastFeedRegistry.register(deviceId, first)
    val seen = watch()
    assertEquals(listOf<WebScreencastFeedRegistry.Feed?>(first), seen, "a browser up before the watch is reported at once")

    WebScreencastFeedRegistry.unregister(deviceId, first)
    val relaunched = Feed()
    WebScreencastFeedRegistry.register(deviceId, relaunched)
    assertEquals(listOf<WebScreencastFeedRegistry.Feed?>(first, null, relaunched), seen)
  }

  @Test
  fun `a watcher of a device with no browser yet is told so, then told when one comes up`() {
    val seen = watch()
    val feed = Feed()
    WebScreencastFeedRegistry.register(deviceId, feed)
    assertEquals(listOf<WebScreencastFeedRegistry.Feed?>(null, feed), seen)
  }

  @Test
  fun `registering the same feed again is not a change`() {
    val feed = Feed()
    WebScreencastFeedRegistry.register(deviceId, feed)
    val seen = watch()
    WebScreencastFeedRegistry.register(deviceId, feed)
    assertEquals(listOf<WebScreencastFeedRegistry.Feed?>(feed), seen)
  }

  @Test
  fun `an old browser closing after its replacement registered is not a change`() {
    val old = Feed()
    val current = Feed()
    WebScreencastFeedRegistry.register(deviceId, old)
    WebScreencastFeedRegistry.register(deviceId, current)
    val seen = watch()

    WebScreencastFeedRegistry.unregister(deviceId, old)
    assertEquals(listOf<WebScreencastFeedRegistry.Feed?>(current), seen)
    assertSame(current, WebScreencastFeedRegistry.get(deviceId), "the live browser stays registered")
  }

  @Test
  fun `a watcher that throws does not stop the others or the registration`() {
    handles += WebScreencastFeedRegistry.watch(deviceId) { feed -> if (feed != null) error("recorder broke") }
    val seen = watch()
    val feed = Feed()
    WebScreencastFeedRegistry.register(deviceId, feed)
    assertEquals(listOf<WebScreencastFeedRegistry.Feed?>(null, feed), seen)
    assertSame(feed, WebScreencastFeedRegistry.get(deviceId))
  }

  @Test
  fun `a closed watch hears nothing more`() {
    val seen = mutableListOf<WebScreencastFeedRegistry.Feed?>()
    WebScreencastFeedRegistry.watch(deviceId) { seen += it }.close()
    WebScreencastFeedRegistry.register(deviceId, Feed())
    assertEquals(listOf<WebScreencastFeedRegistry.Feed?>(null), seen)
  }
}
