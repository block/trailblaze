package xyz.block.trailblaze.logs.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.newtools.DeviceClassifiersProvider
import xyz.block.trailblaze.util.Console

/**
 * The device classifiers each MCP session's scripted tools are gated against
 * (`trailblaze/requiredClassifiers`), held apart from the scripted-tool runtime so a listing can
 * read them even when the gate rejected every tool and no runtime was kept.
 *
 * A device is probed once per (session, device). A probe's answer can be temporary — a failed probe
 * degrades to the bare platform, and a distribution override that misses because the device's shell
 * isn't ready yet falls back to a measured `android-phone` — so each fresh probe is re-checked in the
 * background after [recheckGapsMs]. A bare-platform answer, or a change that failed to apply, keeps
 * being re-checked at the last gap until it resolves. A stable answer re-checks as a resolver cache hit, with no device I/O.
 *
 * A changed answer is swapped in and applied (`onChanged`: rebuild the runtime, refresh `tools/list`)
 * inside the session's [RuntimeRebuildGate.rebuild], so no tool call sees the runtime change under
 * it. An apply that fails restores the previous answer, and the next re-check applies again — even
 * when the device is back to the previous answer, since the failed apply already changed the tools.
 */
internal class SessionDeviceClassifications(
  private val probe: DeviceClassifiersProvider,
  private val recheckGapsMs: List<Long> = DEFAULT_RECHECK_GAPS_MS,
) {

  /** [generation] tells apart two trackings of one device, so only the latest one re-checks. */
  private data class Entry(
    val deviceId: TrailblazeDeviceId,
    val classifiers: List<TrailblazeDeviceClassifier>,
    val generation: Long,
  )

  private val entries = ConcurrentHashMap<String, Entry>()
  private val generations = AtomicLong()

  /** [sessionId]'s classifiers for [deviceId], or null when that device hasn't been probed. */
  fun known(sessionId: String, deviceId: TrailblazeDeviceId?): List<TrailblazeDeviceClassifier>? =
    entries[sessionId]?.takeIf { it.deviceId == deviceId }?.classifiers

  /**
   * [sessionId]'s classifiers for [deviceId], probing on the first call for that device. A fresh
   * probe starts its re-checks in [scope]; a changed answer is applied through [onChanged] under
   * [gate].
   */
  suspend fun resolve(
    sessionId: String,
    deviceId: TrailblazeDeviceId,
    scope: CoroutineScope,
    gate: RuntimeRebuildGate,
    onChanged: suspend () -> Unit,
  ): List<TrailblazeDeviceClassifier> {
    known(sessionId, deviceId)?.let { return it }
    val probed = probe(deviceId)
    track(sessionId, deviceId, probed, scope, gate, onChanged)
    return probed
  }

  /** Records [classifiers] for [deviceId] without probing, and re-checks them in [scope]. */
  fun track(
    sessionId: String,
    deviceId: TrailblazeDeviceId,
    classifiers: List<TrailblazeDeviceClassifier>,
    scope: CoroutineScope,
    gate: RuntimeRebuildGate,
    onChanged: suspend () -> Unit,
  ) {
    val entry = Entry(deviceId, classifiers, generations.incrementAndGet())
    entries[sessionId] = entry
    scope.launch { recheck(sessionId, entry.deviceId, entry.generation, gate, onChanged) }
  }

  /** Drops [sessionId]'s entry; its re-checks stop at their next step. */
  fun forget(sessionId: String) {
    entries.remove(sessionId)
  }

  /** Test seam: record [classifiers] for [deviceId] without probing or re-checking. */
  fun install(sessionId: String, deviceId: TrailblazeDeviceId, classifiers: List<TrailblazeDeviceClassifier>) {
    entries[sessionId] = Entry(deviceId, classifiers, generations.incrementAndGet())
  }

  private suspend fun recheck(
    sessionId: String,
    deviceId: TrailblazeDeviceId,
    generation: Long,
    gate: RuntimeRebuildGate,
    onChanged: suspend () -> Unit,
  ) {
    if (recheckGapsMs.isEmpty()) return
    val platformOnly = listOf(deviceId.trailblazeDevicePlatform.asTrailblazeDeviceClassifier())
    var attempt = 0
    var applyFailed = false
    while (true) {
      val gapMs = recheckGapsMs.getOrNull(attempt++)
        ?: recheckGapsMs.last().takeIf { applyFailed || known(sessionId, deviceId) == platformOnly }
        ?: return
      delay(gapMs)
      // A forgotten session or a newer tracking (another device, or this one bound again) ends this
      // run; the newer tracking has its own.
      val previous = entries[sessionId]?.takeIf { it.generation == generation } ?: return
      val reprobed = probe(deviceId)
      if (reprobed == previous.classifiers && !applyFailed) continue
      val updated = previous.copy(classifiers = reprobed)
      applyFailed = gate.rebuild {
        // Conditional, so an entry replaced while this waited (device rebound, session closed) wins.
        if (!entries.replace(sessionId, previous, updated)) return@rebuild false
        Console.log(
          "[TrailblazeMcpServer] Device ${deviceId.instanceId} now classifies as ${reprobed.describe()} " +
            "(was ${previous.classifiers.describe()}${if (applyFailed) ", last apply failed" else ""}); " +
            "refreshing session $sessionId's tools",
        )
        try {
          onChanged()
          false
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          entries.replace(sessionId, updated, previous)
          Console.log(
            "[TrailblazeMcpServer] Refreshing session $sessionId's tools for ${reprobed.describe()} " +
              "failed (${e.message}); keeping ${previous.classifiers.describe()} and retrying at the next re-check",
          )
          true
        }
      }
    }
  }

  private fun List<TrailblazeDeviceClassifier>.describe(): String = joinToString("-") { it.classifier }

  companion object {
    /** Re-checks 30s, 1m, 2m and 4m after a probe — the window a just-booted device settles in. */
    val DEFAULT_RECHECK_GAPS_MS: List<Long> = listOf(30_000L, 30_000L, 60_000L, 120_000L)
  }
}
