package xyz.block.trailblaze.android

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.quickjs.tools.AndroidAssetBundleSource

/**
 * Pins `AndroidAssetBundleSource`'s path-traversal rejection — the only assertion this class makes,
 * and the only one its former home (`:examples:android-sample-app-uitests`) carried that nothing
 * else covers.
 *
 * The asset path originates in target YAML config (`mcp_servers.script`), which is
 * consumer-controlled, so a `..`-bearing path must fail at construction with a clear error rather
 * than relying on `AssetManager.open` to refuse traversal at I/O time on some Android versions and
 * not others.
 *
 * **This does not need a device, and lives here anyway.** The check is a `require()` on a string.
 * It is an instrumentation test only because `AndroidAssetBundleSource` is `androidMain` and its
 * default `assetManager` argument resolves through `InstrumentationRegistry` — giving it a JVM home
 * would mean adding Robolectric to `:trailblaze-quickjs-tools` (the repo uses it nowhere today) for
 * one string assertion. Riding the CI step that already boots an emulator for
 * [xyz.block.trailblaze.android.accessibility.HierarchyCoverageOnDeviceTest] costs nothing. Move it
 * down if Robolectric ever arrives for another reason.
 *
 * The rest of the deleted `QuickJsToolBundleOnDeviceTest` is not reproduced here: loading a bundle
 * and dispatching a tool from it under ART is exercised on every PR by agent-evaluation trail runs,
 * which route a real TypeScript tool through on-device QuickJS, and launcher
 * registration/shutdown is covered by the JVM `QuickJsToolBundleLauncherTest`.
 */
class AndroidAssetBundleSourceTest {

  @Test
  fun assetPathContainingDotDotIsRejectedAtConstruction() {
    val attempts =
      listOf("../secret.js", "./../secret.js", "fixtures/quickjs/../typed.bundle.js", "..")
    attempts.forEach { badPath ->
      val err = runCatching { AndroidAssetBundleSource(assetPath = badPath) }.exceptionOrNull()
      assertNotNull("expected '$badPath' to be rejected", err)
      assertTrue(
        "expected the rejection message to name the bad path, got: ${err!!.message}",
        err.message.orEmpty().contains(badPath) || err.message.orEmpty().contains(".."),
      )
    }
  }
}
