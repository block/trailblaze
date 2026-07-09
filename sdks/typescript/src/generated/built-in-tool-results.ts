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
