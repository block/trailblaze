package xyz.block.trailblaze.android.accessibility

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

/**
 * Holds one session's tool repo so every dispatch in that session resolves tools against the same
 * instance.
 *
 * Needed by [ScriptedToolBundleReuse]: a reused QuickJS launch registered its scripted tools INTO a
 * repo, so reusing the launch while building a fresh repo per dispatch would leave every scripted
 * tool unresolvable ("Unknown tool" at dispatch). The launch and the repo are cached together or
 * not at all.
 *
 * The key is everything that changes what the repo contains, so a dispatch that would have built a
 * different repo still builds one.
 */
class SessionToolRepoCache {

  data class Key(
    val sessionId: String?,
    val targetId: String?,
    val driverType: TrailblazeDriverType,
  )

  private val lock = Any()
  private var cachedKey: Key? = null
  private var cachedRepo: TrailblazeToolRepo? = null

  /**
   * The repo for [key], building it with [build] on a miss.
   *
   * The lookup, the build and the publish are one critical section. Two `run_yaml` dispatches can
   * overlap — starting one only *launches* the previous job's cancellation — so a second dispatch
   * can arrive while the first is still building. Publishing the key before the repo would let that
   * second dispatch see its own key already cached and read a repo that is still null, or still the
   * previous session's. Building under the lock costs the overlapping dispatch a wait it would
   * otherwise have spent building a repo of its own.
   *
   * A dispatch with no session id gets a fresh repo and never touches the cache: there is no
   * session to key reuse on, and caching under a null key would hand one session's repo to the
   * next.
   */
  fun repoFor(key: Key, build: () -> TrailblazeToolRepo): TrailblazeToolRepo {
    if (key.sessionId == null) return build()
    return synchronized(lock) {
      cachedRepo?.takeIf { cachedKey == key }
        ?: build().also {
          cachedRepo = it
          cachedKey = key
        }
    }
  }
}
