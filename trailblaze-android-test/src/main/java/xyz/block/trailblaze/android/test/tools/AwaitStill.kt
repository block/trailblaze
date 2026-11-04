package xyz.block.trailblaze.android.test.tools

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.android.test.onMainThread

/**
 * Waits until [read] — an element's on-screen geometry — answers the same on two consecutive
 * frames, and returns that answer. Called before a touch is sent to the element.
 *
 * Idle is not still. A sheet sliding into place is driven by frame callbacks (`postOnAnimation`,
 * a `ViewDragHelper` settle), which Espresso's idle check never sees: it reports idle with the
 * sheet halfway up, and the animator scale does not shorten that slide. A touch sent then is
 * taken by the sheet's drag helper, which catches a settling sheet on `ACTION_DOWN`, and the
 * button under it never hears the tap, and the trail spends its next step looking for a screen
 * that never opened. The drivers that run a host round trip per tap get this wait for free.
 *
 * On a still screen this costs one frame. Bounded by [STILLNESS_CAP_MS], because an element that
 * moves forever (a marquee, a carousel) is still tappable and the tap must not wait on it.
 */
internal fun <T> awaitStill(read: () -> T): T {
  // From the UI thread there is no frame to wait for: this thread would be the one drawing it.
  if (Looper.myLooper() == Looper.getMainLooper()) return read()
  val deadline = SystemClock.uptimeMillis() + STILLNESS_CAP_MS
  var last = onMainThread(read)
  var moved = false
  var stillFrames = 0
  while (true) {
    awaitNextFrame()
    val now = onMainThread(read)
    if (now == last) stillFrames++ else { moved = true; stillFrames = 0 }
    last = now
    if (stillFrames >= (if (moved) STILL_FRAMES_AFTER_MOTION else 1)) return now
    if (SystemClock.uptimeMillis() >= deadline) return now
  }
}

/**
 * Returns once the next frame has been laid out: a frame callback runs before that frame's
 * traversal, so the read is posted from inside it and runs after the traversal finishes.
 * Requesting the callback is what schedules the frame, so a screen with nothing animating still
 * produces one.
 */
private fun awaitNextFrame() {
  val main = Handler(Looper.getMainLooper())
  val laidOut = CountDownLatch(1)
  main.post { Choreographer.getInstance().postFrameCallback { main.post { laidOut.countDown() } } }
  laidOut.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)
}

private const val STILLNESS_CAP_MS = 2_000L

/**
 * Once something has moved, one unchanged frame is not enough to call it settled: a decelerating
 * slide rounds to the same pixel on consecutive frames well before it ends, and its drag helper
 * keeps catching touches until it does.
 */
private const val STILL_FRAMES_AFTER_MOTION = 4

/** A frame is 16ms; a busy main thread can hold one much longer, which is not this wait's concern. */
private const val FRAME_WAIT_MS = 250L
