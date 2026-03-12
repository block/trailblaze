package xyz.block.trailblaze.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages sessions and state for multiple devices simultaneously.
 *
 * This enables:
 * 1. Controlling multiple devices from a single MCP session
 * 2. Maintaining screen state per device after tool execution
 * 3. Running parallel operations on different devices
 *
 * Usage:
 * ```kotlin
 * val manager = MultiDeviceSessionManager()
 *
 * // Select devices
 * manager.selectDevice(androidDeviceId)
 * manager.selectDevice(iosDeviceId)
 *
 * // Each device maintains its own state
 * manager.updateScreenState(androidDeviceId, screenState)
 * val state = manager.getScreenState(androidDeviceId)
 * ```
 */
class MultiDeviceSessionManager {

  /**
   * Per-device session data.
   */
  class DeviceSession(
    val deviceId: TrailblazeDeviceId,
    @Volatile var sessionId: String? = null,
    @Volatile var lastScreenState: ScreenState? = null,
    @Volatile var lastActionTimestamp: Long = System.currentTimeMillis(),
    @Volatile var isActive: Boolean = true,
  )

  private val _deviceSessions = ConcurrentHashMap<String, DeviceSession>()

  private val _selectedDeviceIds = MutableStateFlow<Set<TrailblazeDeviceId>>(emptySet())
  val selectedDeviceIds: StateFlow<Set<TrailblazeDeviceId>> = _selectedDeviceIds.asStateFlow()

  private val _primaryDeviceId = MutableStateFlow<TrailblazeDeviceId?>(null)
  val primaryDeviceId: StateFlow<TrailblazeDeviceId?> = _primaryDeviceId.asStateFlow()

  private val selectionLock = Any()

  /**
   * Selects a device for use. Multiple devices can be selected simultaneously.
   *
   * @param deviceId The device to select
   * @param makePrimary If true, this becomes the primary/default device
   */
  fun selectDevice(deviceId: TrailblazeDeviceId, makePrimary: Boolean = false) {
    val key = deviceId.toKey()

    _deviceSessions.computeIfAbsent(key) { DeviceSession(deviceId = deviceId) }

    synchronized(selectionLock) {
      _selectedDeviceIds.value = _selectedDeviceIds.value + deviceId

      if (makePrimary || _primaryDeviceId.value == null) {
        _primaryDeviceId.value = deviceId
      }
    }
  }

  /**
   * Deselects a device.
   */
  fun deselectDevice(deviceId: TrailblazeDeviceId) {
    val key = deviceId.toKey()
    _deviceSessions[key]?.let { session ->
      session.isActive = false
      session.lastScreenState = null
    }

    synchronized(selectionLock) {
      _selectedDeviceIds.value = _selectedDeviceIds.value - deviceId

      if (_primaryDeviceId.value == deviceId) {
        _primaryDeviceId.value = _selectedDeviceIds.value.firstOrNull()
      }
    }
  }

  /**
   * Gets the primary (default) device ID.
   */
  fun getPrimaryDeviceId(): TrailblazeDeviceId? = _primaryDeviceId.value

  /**
   * Sets the primary device.
   */
  fun setPrimaryDevice(deviceId: TrailblazeDeviceId) {
    synchronized(selectionLock) {
      if (deviceId in _selectedDeviceIds.value) {
        _primaryDeviceId.value = deviceId
      }
    }
  }

  /**
   * Updates the screen state for a device.
   * Call this after each tool execution to cache the screen state.
   */
  fun updateScreenState(deviceId: TrailblazeDeviceId, screenState: ScreenState?) {
    val key = deviceId.toKey()
    _deviceSessions[key]?.let { session ->
      session.lastScreenState = screenState
      session.lastActionTimestamp = System.currentTimeMillis()
    }
  }

  /**
   * Gets the cached screen state for a device.
   *
   * @param deviceId The device to query, or null for the primary device
   * @return The cached screen state, or null if not available
   */
  fun getScreenState(deviceId: TrailblazeDeviceId? = null): ScreenState? {
    val targetId = deviceId ?: _primaryDeviceId.value ?: return null
    return _deviceSessions[targetId.toKey()]?.lastScreenState
  }

  /**
   * Gets the session for a device.
   */
  fun getSession(deviceId: TrailblazeDeviceId): DeviceSession? {
    return _deviceSessions[deviceId.toKey()]
  }

  /**
   * Updates the session ID for a device.
   */
  fun updateSessionId(deviceId: TrailblazeDeviceId, sessionId: String) {
    _deviceSessions[deviceId.toKey()]?.sessionId = sessionId
  }

  /**
   * Lists all selected devices with their status.
   */
  fun listSelectedDevices(): List<DeviceSessionSummary> {
    return _deviceSessions.values.map { session ->
      DeviceSessionSummary(
        deviceId = session.deviceId,
        isPrimary = session.deviceId == _primaryDeviceId.value,
        hasScreenState = session.lastScreenState != null,
        lastActionMs = System.currentTimeMillis() - session.lastActionTimestamp,
        isActive = session.isActive,
      )
    }
  }

  /**
   * Clears all sessions.
   */
  fun clearAll() {
    _deviceSessions.clear()
    synchronized(selectionLock) {
      _selectedDeviceIds.value = emptySet()
      _primaryDeviceId.value = null
    }
  }

  private fun TrailblazeDeviceId.toKey(): String = "${this.trailblazeDevicePlatform}_${this.instanceId}"
}

/**
 * Summary of a device session for display.
 */
data class DeviceSessionSummary(
  val deviceId: TrailblazeDeviceId,
  val isPrimary: Boolean,
  val hasScreenState: Boolean,
  val lastActionMs: Long,
  val isActive: Boolean,
) {
  fun describe(): String = buildString {
    val platform = deviceId.trailblazeDevicePlatform.name
    val instance = deviceId.instanceId
    val primary = if (isPrimary) " [PRIMARY]" else ""
    val state = if (hasScreenState) "has screen" else "no screen"
    val active = if (isActive) "" else " (inactive)"

    append("$platform:$instance$primary - $state, ${lastActionMs}ms ago$active")
  }
}
