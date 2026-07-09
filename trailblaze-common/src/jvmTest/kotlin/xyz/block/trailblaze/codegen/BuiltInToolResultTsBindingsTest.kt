package xyz.block.trailblaze.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Correctness gate for [BuiltInToolResultTsBindings]: pins the exact generated TypeScript before
 * `built-in-tools.ts` is wired to import it, so a regression here fails a fast unit test instead
 * of only surfacing as a `built-in-tools.ts` `tsc` failure or, worse, a silently-wrong shape.
 *
 * The `label`/`version`/`buildNumber`/`installPath` fields on [xyz.block.trailblaze.device.InstalledApp]
 * all default to `null` in Kotlin, so [SerialDescriptorTsCodegen] renders them `?: string | null` —
 * NOT the narrower `?: string` the hand-written predecessor in `built-in-tools.ts` used. This is
 * more correct: `TrailblazeJsonInstance` doesn't set `explicitNulls = false`, so the wire can
 * legitimately send an explicit JSON `null` for these fields, which the old hand-written type
 * never accounted for.
 */
class BuiltInToolResultTsBindingsTest {

  @Test
  fun `generates the expected result types for the installed-apps tools`() {
    val ts = BuiltInToolResultTsBindings.generate()

    val expected = """
// AUTO-GENERATED — do not edit by hand. Regenerate: ./gradlew :trailblaze-common:generateDtoTs
// CI's verifyDtoTs fails the build on drift.

export interface InstalledApp {
  appId: string;
  isSystemApp: boolean;
  label?: string | null;
  version?: string | null;
  buildNumber?: string | null;
  installPath?: string | null;
}

/**
 * mobile_listInstalledAppsDetailed
 * xyz.block.trailblaze.mobile.tools.ListInstalledAppsDetailedResult
 */
export interface ListInstalledAppsDetailedResult {
  apps: InstalledApp[];
}

/**
 * mobile_listInstalledApps
 * xyz.block.trailblaze.mobile.tools.ListInstalledAppsResult
 */
export interface ListInstalledAppsResult {
  appIds: string[];
}
""".trimStart('\n')

    assertEquals(expected, ts)
  }
}
