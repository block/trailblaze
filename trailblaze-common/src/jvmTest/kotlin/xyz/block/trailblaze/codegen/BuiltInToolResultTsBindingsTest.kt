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
  fun `generates the expected result types for every tool that declares one`() {
    val ts = BuiltInToolResultTsBindings.generate()

    val expected = """
// AUTO-GENERATED — do not edit by hand. Regenerate: ./gradlew :trailblaze-common:generateDtoTs
// CI's verifyDtoTs fails the build on drift.

export interface Bounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface InstalledApp {
  appId: string;
  isSystemApp: boolean;
  label?: string | null;
  version?: string | null;
  buildNumber?: string | null;
  installPath?: string | null;
}

export interface IosKeychainItem {
  service: string;
  account: string;
  accessGroup: string;
  itemClass: string;
}

/**
 * ios_keychain
 * xyz.block.trailblaze.ios.tools.IosKeychainResult
 */
export interface IosKeychainResult {
  action: string;
  found: boolean;
  memoryKey?: string | null;
  items?: IosKeychainItem[] | null;
}

/**
 * ios_simctl
 * xyz.block.trailblaze.ios.tools.IosSimctlResult
 */
export interface IosSimctlResult {
  exitCode: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
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

/**
 * readScreenImage
 * xyz.block.trailblaze.toolcalls.commands.screenimage.ReadScreenImageResult
 */
export interface ReadScreenImageResult {
  width: number;
  height: number;
  data: string;
  lossless: boolean;
  pixelRect: Bounds;
  source: ScreenImageSource;
}

export interface ScreenImageSource {
  encoding: string;
  width: number;
  height: number;
  scaleX: number;
  scaleY: number;
}
""".trimStart('\n')

    assertEquals(expected, ts)
  }
}
