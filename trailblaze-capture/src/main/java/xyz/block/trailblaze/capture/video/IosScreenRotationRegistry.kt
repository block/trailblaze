package xyz.block.trailblaze.capture.video

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The last screen rotation observed on each iOS device, and a way to be told when it changes.
 *
 * Rotation cannot be read from any feed the recorder has: neither the baguette stream nor
 * `simctl io enumerate` reports it, and the framebuffer is portrait either way (see
 * [IosScreenRotation]). The only place it is knowable is the accessibility tree, which the screen
 * state already reads on every capture. This registry is the seam between those two: the screen
 * state publishes what it saw, the recorder consumes it.
 *
 * Today the Maestro/XCUITest screen state is the only publisher. The AXe screen state reads a
 * differently shaped tree and does not publish, so a device driven that way is never observed here
 * and its recordings assume portrait. Subscribers are notified on the publishing thread — a screen
 * capture — while it holds the device's lock, so a subscriber must hand real work off rather than
 * do it in the callback, and must not wait on anything that could be waiting to subscribe.
 *
 * ### Why an observation and not a parameter
 * A Trailblaze session is not always a trail run. In the MCP path a session opens the first time
 * any tool touches a device and stays open while calls arrive at arbitrary times, so there is no
 * moment at session start when the eventual orientation is knowable — the app is usually not even
 * launched yet, and the simulator is sitting on a portrait home screen. Over a session of that
 * shape a rotation partway through is ordinary rather than exceptional. So the recorder subscribes
 * and reacts, rather than sampling once and hoping.
 *
 * Keyed by device instance id (the simulator udid), because rotation belongs to the device and
 * outlives any one session on it.
 */
object IosScreenRotationRegistry {

  private val observed = ConcurrentHashMap<String, IosScreenRotation>()
  private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(IosScreenRotation) -> Unit>>()

  /**
   * One per device, held across recording an observation and notifying it. Two captures on the same
   * device can publish at once; without this, one could record A, the other record and deliver B,
   * and then A be delivered last, leaving a subscriber on A while every later B is a repeat that
   * never notifies.
   */
  private val deviceLocks = ConcurrentHashMap<String, Any>()

  private fun lockFor(deviceInstanceId: String): Any = deviceLocks.computeIfAbsent(deviceInstanceId) { Any() }

  /**
   * Records the rotation seen on [deviceInstanceId] and notifies subscribers **only when it
   * differs** from the last observation. Every screen capture calls this, many times a session; a
   * subscriber that had to filter the repeats itself would roll a recording segment per tool call.
   */
  fun observe(deviceInstanceId: String, rotation: IosScreenRotation) = synchronized(lockFor(deviceInstanceId)) {
    val previous = observed.put(deviceInstanceId, rotation)
    if (previous == rotation) return@synchronized
    listeners[deviceInstanceId]?.forEach { it(rotation) }
  }

  /** The most recent rotation seen on [deviceInstanceId], or null if it has never been observed. */
  fun current(deviceInstanceId: String): IosScreenRotation? = observed[deviceInstanceId]

  /**
   * Calls [onRotation] for every future change on [deviceInstanceId], and **immediately** with the
   * current value if one has already been observed. That immediate delivery is what lets a
   * recorder started mid-session pick up an orientation that was established before it existed.
   */
  fun subscribe(deviceInstanceId: String, onRotation: (IosScreenRotation) -> Unit): AutoCloseable {
    // Under the device's lock too, so a change observed while subscribing is delivered after the
    // current value rather than overtaken by it.
    val forDevice = synchronized(lockFor(deviceInstanceId)) {
      listeners.computeIfAbsent(deviceInstanceId) { CopyOnWriteArrayList() }.also { forDevice ->
        forDevice += onRotation
        observed[deviceInstanceId]?.let(onRotation)
      }
    }
    return AutoCloseable { forDevice -= onRotation }
  }

  /** Test seam: drop all observations and subscribers. */
  internal fun reset() {
    observed.clear()
    listeners.clear()
    deviceLocks.clear()
  }
}
