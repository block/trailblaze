package xyz.block.trailblaze.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Env-var precedence for the discovery-cache TTL. Injected env so the parse/precedence logic is
 * tested without mutating the real process environment.
 */
class DiscoveryCacheTtlResolutionTest {

  private fun env(vars: Map<String, String>): (String) -> String? = { vars[it] }

  @Test
  fun `unset falls back to the default`() {
    assertEquals(
      TrailblazeDeviceManager.DEFAULT_DISCOVERY_CACHE_TTL_MS,
      TrailblazeDeviceManager.resolveDiscoveryCacheTtlMs(env(emptyMap())),
    )
  }

  @Test
  fun `kill switch disables the cache`() {
    assertEquals(
      0L,
      TrailblazeDeviceManager.resolveDiscoveryCacheTtlMs(
        env(mapOf("TRAILBLAZE_DISABLE_DEVICE_DISCOVERY_CACHE" to "1")),
      ),
    )
    assertEquals(
      0L,
      TrailblazeDeviceManager.resolveDiscoveryCacheTtlMs(
        env(mapOf("TRAILBLAZE_DISABLE_DEVICE_DISCOVERY_CACHE" to "TRUE")),
      ),
    )
  }

  @Test
  fun `explicit ttl is honored`() {
    assertEquals(
      500L,
      TrailblazeDeviceManager.resolveDiscoveryCacheTtlMs(
        env(mapOf("TRAILBLAZE_DEVICE_DISCOVERY_CACHE_TTL_MS" to "500")),
      ),
    )
    assertEquals(
      0L,
      TrailblazeDeviceManager.resolveDiscoveryCacheTtlMs(
        env(mapOf("TRAILBLAZE_DEVICE_DISCOVERY_CACHE_TTL_MS" to "0")),
      ),
    )
  }

  @Test
  fun `malformed or negative ttl falls back to the default`() {
    assertEquals(
      TrailblazeDeviceManager.DEFAULT_DISCOVERY_CACHE_TTL_MS,
      TrailblazeDeviceManager.resolveDiscoveryCacheTtlMs(
        env(mapOf("TRAILBLAZE_DEVICE_DISCOVERY_CACHE_TTL_MS" to "abc")),
      ),
    )
    assertEquals(
      TrailblazeDeviceManager.DEFAULT_DISCOVERY_CACHE_TTL_MS,
      TrailblazeDeviceManager.resolveDiscoveryCacheTtlMs(
        env(mapOf("TRAILBLAZE_DEVICE_DISCOVERY_CACHE_TTL_MS" to "-5")),
      ),
    )
  }

  @Test
  fun `disable takes precedence over an explicit ttl`() {
    assertEquals(
      0L,
      TrailblazeDeviceManager.resolveDiscoveryCacheTtlMs(
        env(
          mapOf(
            "TRAILBLAZE_DISABLE_DEVICE_DISCOVERY_CACHE" to "1",
            "TRAILBLAZE_DEVICE_DISCOVERY_CACHE_TTL_MS" to "9999",
          ),
        ),
      ),
    )
  }
}
