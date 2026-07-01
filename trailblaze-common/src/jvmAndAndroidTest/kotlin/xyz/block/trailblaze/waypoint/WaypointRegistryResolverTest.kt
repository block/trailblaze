package xyz.block.trailblaze.waypoint

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the observable contract of [WaypointRegistryResolver] that doesn't depend on a populated
 * registry: it is process-cached (a second call for the same working directory returns the same
 * resolver instance rather than rebuilding), and an id the registry doesn't contain resolves to
 * null. The full match behavior is covered by `WaypointAssertionTest` / `WaypointMatcherTest`.
 */
class WaypointRegistryResolverTest {

  @AfterTest
  fun tearDown() {
    // Don't leak the cached resolver (or a test-installed enrichment provider) into other tests
    // that run in the same JVM.
    WaypointRegistryResolver.clearCache()
  }

  @Test
  fun `resolver is cached per process - second call returns the same instance`() {
    WaypointRegistryResolver.clearCache()
    val first = WaypointRegistryResolver.resolver()
    val second = WaypointRegistryResolver.resolver()
    // getOrPut returns the same closure; rebuilding would re-walk trailmaps (and, with enrichment
    // installed, re-run the analyzer subprocess) on every assertWaypoint call.
    assertSame(first, second, "resolver() must return the cached instance for the same working dir")
  }

  @Test
  fun `unknown waypoint id resolves to null`() {
    WaypointRegistryResolver.clearCache()
    val resolver = WaypointRegistryResolver.resolver()
    assertNull(
      resolver("definitely/not/a/real/waypoint/id"),
      "an id absent from the registry must resolve to null (assertWaypoint renders this as unknown)",
    )
  }
}
