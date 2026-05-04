package xyz.block.trailblaze.host.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.util.Console
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents the state of a single managed web browser slot.
 */
sealed class WebBrowserState {
  /** No browser is running */
  data object Idle : WebBrowserState()

  /** Browser is in the process of launching */
  data object Launching : WebBrowserState()

  /** Browser is running and ready for tests */
  data object Running : WebBrowserState()

  /** Browser encountered an error */
  data class Error(val message: String) : WebBrowserState()
}

/**
 * Manages the lifecycle of one or more web browser instances for Trailblaze testing.
 *
 * Unlike Android/iOS devices which are discovered via USB/network, web browsers are
 * "virtual" — provisioned on demand and addressed by an arbitrary instance ID so
 * that multiple parallel CLI commands can each operate on their own browser.
 *
 * The default instance — [PLAYWRIGHT_NATIVE_INSTANCE_ID] — is the one the desktop
 * "Launch Browser" button operates on; CLI commands can address it as `--device web`.
 * Any other instance ID (e.g. `--device web/foo`) is provisioned on first use and
 * reused by subsequent commands that target the same ID.
 */
class WebBrowserManager {

  private val scope = CoroutineScope(Dispatchers.IO)
  val playwrightInstaller = PlaywrightBrowserInstaller()

  /** Per-instance state. The default playwright-native slot is pre-registered. */
  private class BrowserSlot(
    val instanceId: String,
    val description: String,
  ) {
    val launchMutex = Mutex()
    val state: MutableStateFlow<WebBrowserState> = MutableStateFlow(WebBrowserState.Idle)

    @Volatile var manager: PlaywrightBrowserManager? = null

    /** Headless preference recorded by callers (e.g. CLI `--headless false`). */
    @Volatile var headlessPreference: Boolean = true
  }

  private val slots: ConcurrentHashMap<String, BrowserSlot> = ConcurrentHashMap<String, BrowserSlot>().also {
    it[PLAYWRIGHT_NATIVE_INSTANCE_ID] = BrowserSlot(
      instanceId = PLAYWRIGHT_NATIVE_INSTANCE_ID,
      description = "Chrome Browser",
    )
  }

  init {
    Runtime.getRuntime().addShutdownHook(
      Thread {
        // Snapshot before iterating so concurrent slot creation can't surprise the loop,
        // and include any slot whose `manager` is non-null even if its state never reached
        // Running (adopted browsers stay Idle until somebody calls launchBrowser on them).
        slots.values.toList().forEach { slot ->
          val hasLiveBrowser = slot.state.value is WebBrowserState.Running || slot.manager != null
          if (!hasLiveBrowser) return@forEach
          Console.log("WebBrowserManager: Closing browser '${slot.instanceId}' on app shutdown")
          // 5s/slot timeout so a wedged Chromium can't hang JVM exit. We're already
          // in the shutdown hook on a non-daemon thread, so blocking here is fine.
          val closed = runBlocking {
            withTimeoutOrNull(SHUTDOWN_CLOSE_TIMEOUT_MS) {
              slot.launchMutex.withLock {
                try {
                  slot.manager?.close()
                } catch (e: Exception) {
                  Console.log("WebBrowserManager: Error closing browser '${slot.instanceId}' on shutdown: ${e.message}")
                }
                slot.manager = null
              }
            }
          }
          if (closed == null) {
            Console.log("WebBrowserManager: Timed out closing browser '${slot.instanceId}' after ${SHUTDOWN_CLOSE_TIMEOUT_MS}ms; leaving it for the OS to reap")
          }
        }
      },
    )
    playwrightInstaller.checkInstallStatus()
  }

  /**
   * Returns the slot for [instanceId], creating one atomically if absent. Uses
   * [ConcurrentHashMap.computeIfAbsent] (not Kotlin's [getOrPut], which is racy on
   * concurrent maps) so concurrent callers always agree on the same slot — and the
   * same `Mutex` and `StateFlow` — for a given ID. Refuses to create new named
   * slots once [MAX_NAMED_SLOTS] is exceeded; reserved IDs and existing slots
   * always succeed.
   *
   * @throws IllegalStateException if the slot cap is hit on a brand-new named ID.
   */
  private fun slotFor(instanceId: String): BrowserSlot {
    val existing = slots[instanceId]
    if (existing != null) return existing

    val isReserved = instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE ||
      instanceId == WebInstanceIds.PLAYWRIGHT_ELECTRON
    if (!isReserved) {
      val namedCount = slots.keys.count { id ->
        id != WebInstanceIds.PLAYWRIGHT_NATIVE && id != WebInstanceIds.PLAYWRIGHT_ELECTRON
      }
      if (namedCount >= MAX_NAMED_SLOTS) {
        throw IllegalStateException(
          "Refusing to provision web browser instance '$instanceId': $MAX_NAMED_SLOTS " +
            "named instances already exist. Close one with the desktop UI or restart the daemon.",
        )
      }
    }

    var created = false
    val slot = slots.computeIfAbsent(instanceId) { id ->
      created = true
      BrowserSlot(
        instanceId = id,
        description = if (id == PLAYWRIGHT_NATIVE_INSTANCE_ID) "Chrome Browser" else "Chrome Browser ($id)",
      )
    }
    if (created) {
      // Surface a single, concise log line on first provisioning so a user who typed
      // `--device web/PLAYWRIGTH-native` (typo) sees that they made a new instance
      // rather than reusing the singleton.
      Console.log("WebBrowserManager: Provisioned new browser slot 'web/$instanceId'")
    }
    return slot
  }

  /**
   * State flow for the default playwright-native browser. Used by the desktop UI's
   * launch panel which has no concept of named instances.
   *
   * Cached at init so the property has stable identity — Compose code or anything
   * that compares references (`===`) sees the same instance every time.
   */
  val browserStateFlow: StateFlow<WebBrowserState> =
    slots.getValue(PLAYWRIGHT_NATIVE_INSTANCE_ID).state.asStateFlow()

  /**
   * Returns the state flow for the browser identified by [instanceId], creating
   * a new idle slot if none exists yet.
   */
  fun browserStateFlow(instanceId: String): StateFlow<WebBrowserState> =
    slotFor(instanceId).state.asStateFlow()

  /**
   * Launches a web browser instance asynchronously on [Dispatchers.IO].
   *
   * @param instanceId Identifier for the browser slot. Defaults to the singleton
   *   [PLAYWRIGHT_NATIVE_INSTANCE_ID] (the desktop UI's "Launch Browser" target).
   * @param headless Whether to launch the browser headless. Defaults to `false`
   *   for the singleton (visible window for desktop debugging) and `true` for any
   *   other named instance, but callers may override for either case.
   * @param onComplete optional callback invoked after the browser is ready.
   */
  fun launchBrowser(
    instanceId: String = PLAYWRIGHT_NATIVE_INSTANCE_ID,
    headless: Boolean = (instanceId != PLAYWRIGHT_NATIVE_INSTANCE_ID),
    onComplete: (() -> Unit)? = null,
  ) {
    val slot = slotFor(instanceId)
    scope.launch {
      slot.launchMutex.withLock {
        if (slot.state.value is WebBrowserState.Running) {
          Console.log("WebBrowserManager: Browser '${slot.instanceId}' already running, reusing existing instance")
          onComplete?.invoke()
          return@withLock
        }

        slot.state.value = WebBrowserState.Launching

        try {
          val newBrowserManager = PlaywrightBrowserManager(
            headless = headless,
            onBrowserInstallProgress = { percent, message ->
              playwrightInstaller.reportInstallProgress(percent, message)
            },
          )
          slot.manager = newBrowserManager
          slot.headlessPreference = headless
          playwrightInstaller.reportInstallComplete()

          slot.state.value = WebBrowserState.Running
          startBrowserMonitor(slot)

          Console.log("WebBrowserManager: Launched browser instance '${slot.instanceId}' (headless=$headless)")
          onComplete?.invoke()
        } catch (e: Exception) {
          Console.log("WebBrowserManager: Failed to launch browser '${slot.instanceId}': ${e.message}")
          val errorMessage = e.message ?: "Unknown error launching browser"
          if (playwrightInstaller.installState.value is PlaywrightInstallState.Installing) {
            playwrightInstaller.reportInstallError(errorMessage)
          }
          slot.state.value = WebBrowserState.Error(errorMessage)
        }
      }
    }
  }

  /**
   * Closes the browser instance asynchronously on [Dispatchers.IO].
   */
  fun closeBrowser(
    instanceId: String = PLAYWRIGHT_NATIVE_INSTANCE_ID,
    onComplete: (() -> Unit)? = null,
  ) {
    val slot = slots[instanceId]
    if (slot == null) {
      onComplete?.invoke()
      return
    }
    scope.launch {
      slot.launchMutex.withLock {
        try {
          slot.manager?.close()
          Console.log("WebBrowserManager: Closed browser instance '${slot.instanceId}'")
        } catch (e: Exception) {
          Console.log("WebBrowserManager: Error closing browser '${slot.instanceId}': ${e.message}")
        }
        slot.manager = null
        slot.state.value = WebBrowserState.Idle
      }
      onComplete?.invoke()
    }
  }

  /**
   * Returns a device summary for the default singleton browser if it is running.
   * Used by the desktop UI; CLI/MCP code should use [getAllRunningBrowserSummaries].
   */
  fun getRunningBrowserSummary(): TrailblazeConnectedDeviceSummary? =
    summaryFor(slots[PLAYWRIGHT_NATIVE_INSTANCE_ID])

  /**
   * Returns a device summary for the named browser if it is running.
   */
  fun getRunningBrowserSummary(instanceId: String): TrailblazeConnectedDeviceSummary? =
    summaryFor(slots[instanceId])

  /**
   * Returns device summaries for all browsers that are currently running or have
   * an adopted manager attached. The default singleton is always included if
   * running so it stays visible in CLI device lists alongside named instances.
   */
  fun getAllRunningBrowserSummaries(): List<TrailblazeConnectedDeviceSummary> =
    slots.values.toList().mapNotNull { summaryFor(it) }

  private fun summaryFor(slot: BrowserSlot?): TrailblazeConnectedDeviceSummary? {
    if (slot == null) return null
    val isLive = slot.state.value is WebBrowserState.Running || slot.manager != null
    if (!isLive) return null
    return TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      instanceId = slot.instanceId,
      description = slot.description,
    )
  }

  /** Whether the default singleton browser is running. */
  fun isRunning(): Boolean = slots[PLAYWRIGHT_NATIVE_INSTANCE_ID]?.state?.value is WebBrowserState.Running

  /** Whether the named browser is running. */
  fun isRunning(instanceId: String): Boolean = slots[instanceId]?.state?.value is WebBrowserState.Running

  /**
   * Returns the [PlaywrightBrowserManager] for the default singleton browser if running.
   */
  fun getPageManager(): PlaywrightBrowserManager? = slots[PLAYWRIGHT_NATIVE_INSTANCE_ID]?.manager

  /**
   * Returns the [PlaywrightBrowserManager] for the named browser if running.
   */
  fun getPageManager(instanceId: String): PlaywrightBrowserManager? = slots[instanceId]?.manager

  /**
   * Stores an externally-created [PlaywrightBrowserManager] in the singleton slot
   * for reuse across MCP sessions. Preserved for backwards compatibility — new
   * callers should use the instance-aware overload.
   */
  fun adoptManagedBrowser(manager: PlaywrightBrowserManager) {
    adoptManagedBrowser(PLAYWRIGHT_NATIVE_INSTANCE_ID, manager, headless = true)
  }

  /**
   * Stores an externally-created [PlaywrightBrowserManager] for [instanceId] so
   * that future calls to [getPageManager] reuse the same browser instead of
   * spawning a new one. Idempotent — does nothing if a manager is already set.
   */
  fun adoptManagedBrowser(
    instanceId: String,
    manager: PlaywrightBrowserManager,
    headless: Boolean = true,
  ) {
    val slot = slotFor(instanceId)
    if (slot.manager == null) {
      slot.manager = manager
      slot.headlessPreference = headless
      Console.log("WebBrowserManager: Adopted MCP-managed browser '$instanceId' for session reuse (headless=$headless)")
    }
  }

  /**
   * Records a caller's preferred headless mode for [instanceId] so that the next
   * MCP-driven browser launch for that ID picks it up. Stored on the slot — does
   * not affect a browser that is already running.
   */
  fun setHeadlessPreference(instanceId: String, headless: Boolean) {
    slotFor(instanceId).headlessPreference = headless
  }

  /**
   * Returns the recorded headless preference for [instanceId].
   *
   * Existing slots return their current preference, which defaults to `true`
   * (headless) at slot creation. The pre-registered singleton slot always
   * returns a non-null result, so a non-null `true` does NOT mean a caller
   * explicitly opted in — only that the slot exists. Returns null only when
   * no slot has been provisioned for [instanceId] yet.
   */
  fun getHeadlessPreference(instanceId: String): Boolean? =
    slots[instanceId]?.headlessPreference

  /**
   * Monitors the singleton browser for disconnection/crash. Named instances are
   * monitored by their slot identity.
   */
  private fun startBrowserMonitor(slot: BrowserSlot) {
    scope.launch {
      while (isActive && slot.state.value is WebBrowserState.Running) {
        try {
          if (!checkBrowserConnected(slot)) {
            Console.log("WebBrowserManager: Browser '${slot.instanceId}' disconnected (user closed or crashed)")
            handleBrowserDisconnected(slot)
            break
          }
        } catch (e: Exception) {
          Console.log("WebBrowserManager: Error monitoring browser '${slot.instanceId}': ${e.message}")
          handleBrowserDisconnected(slot)
          break
        }
        delay(1000)
      }
    }
  }

  private fun checkBrowserConnected(slot: BrowserSlot): Boolean {
    val manager = slot.manager ?: return false
    return try {
      manager.currentPage.url()
      true
    } catch (_: Exception) {
      false
    }
  }

  private suspend fun handleBrowserDisconnected(slot: BrowserSlot) {
    // Serialize with launch/close/shutdown so we don't double-close the same manager.
    slot.launchMutex.withLock {
      slot.state.value = WebBrowserState.Idle
      try {
        slot.manager?.close()
      } catch (_: Exception) {
        // Ignore - browser is already gone
      }
      slot.manager = null
    }
  }

  fun close() {
    playwrightInstaller.close()
    scope.cancel()
    // Snapshot before iterating; close() can race with the shutdown hook and with
    // concurrent slot creation. Best-effort cleanup — no need to hold the per-slot
    // mutex here because scope.cancel() above already torn down the monitor coroutines.
    slots.values.toList().forEach { slot ->
      try {
        slot.manager?.close()
      } catch (_: Exception) {
        // Ignore - best-effort cleanup
      }
      slot.manager = null
      slot.state.value = WebBrowserState.Idle
    }
  }

  companion object {
    /** Reserved instance ID for the singleton/default web browser. */
    const val PLAYWRIGHT_NATIVE_INSTANCE_ID: String = WebInstanceIds.PLAYWRIGHT_NATIVE

    /**
     * Soft cap on simultaneously-provisioned named browser slots. Each slot can hold
     * a Chromium process (~150–250 MB RAM), so an unbounded loop creating
     * `web/test-1`…`web/test-1000` would exhaust the daemon. Reserved IDs
     * (`playwright-native`, `playwright-electron`) don't count toward this cap.
     */
    const val MAX_NAMED_SLOTS: Int = 32

    /** Per-slot timeout when the shutdown hook reaps live browsers. */
    private const val SHUTDOWN_CLOSE_TIMEOUT_MS: Long = 5_000L
  }
}
