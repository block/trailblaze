package xyz.block.trailblaze.devices

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import xyz.block.trailblaze.util.Console

/**
 * The device language a trail runs in. A trail that declares no `locale:` runs in [DEFAULT], so a
 * device that a localized trail switched does not carry that language into the next trail.
 */
object TrailDeviceLocale {

  /** The language a freshly created Android emulator or iOS Simulator starts in. */
  const val DEFAULT = "en-US"

  /**
   * The language to apply for a trail whose resolved `locale:` is [declared]. A skipped trail
   * applies nothing; callers return before reaching this.
   */
  fun forTrail(declared: String?): Request = Request(locale = declared ?: DEFAULT, declared = declared != null)

  /**
   * The language to apply for a run, or null to leave the device as it is. A run that continues a
   * session — a prompt or a single tool sent into it — keeps the session's language unless it
   * declares one; only a run that opens a session falls back to [DEFAULT].
   */
  fun forRun(declared: String?, continuesSession: Boolean): Request? =
    forTrail(declared).takeUnless { continuesSession && !it.declared }

  /**
   * [declared] is false for [DEFAULT]. A trail that asked for a language fails when the device
   * cannot be put in it. The default is best effort: a device that cannot be switched (an Android
   * image older than API 29, a physical phone the host has no command for) still runs the trail in
   * whatever language it is in, as it did before the default existed.
   */
  data class Request(val locale: String, val declared: Boolean)

  /**
   * A switch that failed after it had already disturbed the device — restarted an emulator's
   * framework, or wrote a simulator's language without restarting SpringBoard. Whatever ran in the
   * old process is gone or no longer matches, so a caller treats the device as changed.
   */
  class PartiallyAppliedException(message: String) : IllegalStateException(message)

  /** What [applyWithOutcome] did to the device's language. */
  enum class Outcome {
    UNCHANGED,

    CHANGED,

    /** The default could not be finished, but the attempt disturbed the device (see [PartiallyAppliedException]). */
    PARTIAL,
  }

  /**
   * Runs [apply] for [request] and returns whether it changed the device's language. A failure to
   * apply a declared locale propagates. A failure to apply the default is logged and counts as no
   * change, unless it is a [PartiallyAppliedException], which counts as a change.
   */
  fun applyBestEffort(request: Request, deviceLabel: String, apply: (String) -> Boolean): Boolean =
    applyWithOutcome(request, deviceLabel, apply) != Outcome.UNCHANGED

  /** [applyBestEffort], telling a finished change apart from a default that was left half done. */
  fun applyWithOutcome(request: Request, deviceLabel: String, apply: (String) -> Boolean): Outcome {
    if (request.declared) return if (apply(request.locale)) Outcome.CHANGED else Outcome.UNCHANGED
    return try {
      if (apply(request.locale)) Outcome.CHANGED else Outcome.UNCHANGED
    } catch (e: PartiallyAppliedException) {
      Console.log(
        "[device-locale] could not finish putting $deviceLabel in the default ${request.locale}; the " +
          "target app restarts with whatever language it gets: ${e.message}",
      )
      Outcome.PARTIAL
    } catch (e: InterruptedException) {
      // A timeout or cancel landing mid-switch stops the trail; it is not a failed default.
      Thread.currentThread().interrupt()
      throw e
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Console.log(
        "[device-locale] could not put $deviceLabel in the default ${request.locale}; the trail runs " +
          "in the device's current language: ${e.message}",
      )
      Outcome.UNCHANGED
    }
  }

  /**
   * Devices whose language changed, or may have, with the app restart that must follow still not
   * done. A run that changes the language and then fails or is cancelled before restarting the app
   * leaves the device already in the language a retry asks for, so the retry would see nothing to
   * change and keep the stale app.
   */
  private val cleanupPending: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /**
   * Runs [attempt], which returns whether it changed the language of [deviceKey], and returns
   * whether the app needs a fresh process: this attempt changed the language (or may have), or an
   * earlier change's restart was never confirmed with [cleanupDone]. A caller that gets true
   * restarts the app and then calls [cleanupDone].
   */
  fun changeNeedingCleanup(deviceKey: String, attempt: () -> Boolean): Boolean {
    val pending = deviceKey in cleanupPending
    val changed = try {
      attempt()
    } catch (e: Throwable) {
      // A cancel, or a declared locale that failed after disturbing the device.
      if (e is InterruptedException || e is CancellationException || e is PartiallyAppliedException) {
        cleanupPending.add(deviceKey)
      }
      throw e
    }
    if (changed) cleanupPending.add(deviceKey)
    return changed || pending
  }

  /** The app on [deviceKey] was restarted after a change [changeNeedingCleanup] reported. */
  fun cleanupDone(deviceKey: String) {
    cleanupPending.remove(deviceKey)
  }
}
