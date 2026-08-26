package xyz.block.trailblaze.host.devices

/**
 * A resource several owners hold at once, closed when the last of them lets go.
 *
 * Written for the one iOS driver [HostIosDriverFactory] caches: an agent session driving a device
 * through the MCP bridge and a Trail Runner viewer streaming its screen are handed the same driver
 * whenever their target wrappers agree, and each tears down on its own schedule. Handing every
 * owner the right to close it meant whichever finished first left the other holding a dead
 * connection.
 *
 * Owners take an [acquire] handle and close that instead of the resource. [closeResource] runs once,
 * on the release that drops the count to zero, and must not throw - a caller closing its own handle
 * has no way to react to someone else's resource failing to close.
 */
internal class SharedLease(private val closeResource: () -> Unit) {

  private var owners = 0
  private var closed = false

  /**
   * One owner's hold on the resource, or null once the resource has been closed - there is nothing
   * left to hold, and handing back a handle on it would give the caller a dead resource that no
   * later release can revive. Callers treat null as "build a fresh one".
   *
   * Refusing here rather than letting callers check a flag first is what makes it safe: the last
   * owner can let go at any moment, so a caller that read "still open" and then acquired would be
   * racing the close it just checked for.
   *
   * Closing the returned handle releases that owner and only that owner; the resource itself is
   * closed by whichever release drops the count to zero.
   *
   * The handle is idempotent on purpose: several teardown paths can reach the same one (a run's
   * driver is closed by its own cleanup and again by an explicit cancel), and a second close that
   * decremented the count again would release an owner that is still using the resource.
   */
  @Synchronized
  fun acquire(): AutoCloseable? {
    if (closed) return null
    owners++
    var released = false
    return AutoCloseable {
      synchronized(this@SharedLease) {
        if (!released) {
          released = true
          owners--
          if (owners == 0) closeNow()
        }
      }
    }
  }

  /**
   * Closes the resource now, however many owners still hold it, and makes their later releases
   * no-ops. For a caller that cannot wait for them - [HostIosDriverFactory] replacing a driver built
   * for the wrong target wrapper needs the port the old one is holding, so leaving it open until its
   * owners let go would mean never building the replacement.
   */
  @Synchronized
  fun closeNow() {
    if (closed) return
    closed = true
    closeResource()
  }
}
