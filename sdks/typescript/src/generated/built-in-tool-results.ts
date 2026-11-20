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
