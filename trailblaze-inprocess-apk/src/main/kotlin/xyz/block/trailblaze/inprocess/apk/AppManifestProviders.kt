package xyz.block.trailblaze.inprocess.apk

import java.io.File

/**
 * The `<provider>` half of a probe, on its own.
 *
 * `make-test-apk` warns about one declaration — `androidx.startup`'s `InitializationProvider` — and
 * needs nothing else [ApkProbe] computes: no dex scan, no era map, no certificate read. This reads
 * one zip entry so that warning does not cost a full probe of an APK the caller already trusts.
 */
object AppManifestProviders {

  /**
   * Every `<provider>` [appApk]'s manifest declares.
   *
   * @throws ApkReadException when the manifest is missing or unreadable.
   */
  fun of(appApk: File): List<DeclaredProvider> =
    ApkArchive.open(appApk).use { ManifestFacts.read(it).providers }
}
