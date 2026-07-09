package xyz.block.trailblaze.trailrunner

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coverage for the pure workspace-icon resolver behind `/trailrunner/api/app-icon/{target}`
 * (GitHub block/trailblaze issue 200) — turning a target's icon config into an on-disk file across the candidate
 * workspace base dirs, with a containment guard.
 */
class WorkspaceIconResolutionTest {

  @get:Rule
  val tmp = TemporaryFolder()

  @Test
  fun `explicit icon wins and resolves under the config dir`() {
    val configDir = tmp.newFolder("config")
    val icon = File(configDir, "assets/icons/custom.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
    val resolved = resolveWorkspaceIconFile(
      configIcon = "assets/icons/custom.png",
      androidAppId = "com.example.app",
      webBaseUrl = null,
      baseDirs = listOf(configDir),
    )
    assertEquals(icon.canonicalPath, resolved?.canonicalPath)
  }

  @Test
  fun `android convention resolves the launcher icon by app id`() {
    val configDir = tmp.newFolder("config")
    val icon = File(configDir, "assets/icons/android_com.example.app.png")
      .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
    val resolved = resolveWorkspaceIconFile(
      configIcon = null,
      androidAppId = "com.example.app",
      webBaseUrl = null,
      baseDirs = listOf(configDir),
    )
    assertEquals(icon.canonicalPath, resolved?.canonicalPath)
  }

  @Test
  fun `ios convention resolves the launcher icon by bundle id`() {
    val configDir = tmp.newFolder("config")
    val icon = File(configDir, "assets/icons/ios_com.example.app.png")
      .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
    val resolved = resolveWorkspaceIconFile(
      configIcon = null,
      androidAppId = null,
      iosBundleId = "com.example.app",
      webBaseUrl = null,
      baseDirs = listOf(configDir),
    )
    assertEquals(icon.canonicalPath, resolved?.canonicalPath)
  }

  @Test
  fun `web convention resolves the favicon by host`() {
    val configDir = tmp.newFolder("config")
    val icon = File(configDir, "assets/icons/favicon_example.com.png")
      .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
    val resolved = resolveWorkspaceIconFile(
      configIcon = null,
      androidAppId = null,
      webBaseUrl = "https://example.com/travel",
      baseDirs = listOf(configDir),
    )
    assertEquals(icon.canonicalPath, resolved?.canonicalPath)
  }

  @Test
  fun `falls through to a later base dir when the first has no file`() {
    val configDir = tmp.newFolder("config")
    val workspaceRoot = tmp.newFolder("workspaceRoot")
    val icon = File(workspaceRoot, "assets/icons/android_com.example.app.png")
      .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
    val resolved = resolveWorkspaceIconFile(
      configIcon = null,
      androidAppId = "com.example.app",
      webBaseUrl = null,
      baseDirs = listOf(configDir, workspaceRoot),
    )
    assertEquals(icon.canonicalPath, resolved?.canonicalPath)
  }

  @Test
  fun `returns null when the resolved file does not exist`() {
    val configDir = tmp.newFolder("config")
    assertNull(
      resolveWorkspaceIconFile(
        configIcon = null,
        androidAppId = "com.example.app",
        webBaseUrl = null,
        baseDirs = listOf(configDir),
      ),
    )
  }

  @Test
  fun `returns null when nothing resolves to a path`() {
    val configDir = tmp.newFolder("config")
    assertNull(
      resolveWorkspaceIconFile(
        configIcon = null,
        androidAppId = null,
        webBaseUrl = null,
        baseDirs = listOf(configDir),
      ),
    )
  }

  @Test
  fun `rejects a path that escapes the base dir`() {
    val configDir = tmp.newFolder("config")
    // A secret sibling of the config dir the crafted icon path tries to reach.
    File(tmp.root, "secret.png").writeBytes(byteArrayOf(1))
    assertNull(
      resolveWorkspaceIconFile(
        configIcon = "../secret.png",
        androidAppId = null,
        webBaseUrl = null,
        baseDirs = listOf(configDir),
      ),
    )
  }

  @Test
  fun `rejects a symlink that resolves outside the base dir`() {
    val configDir = tmp.newFolder("config")
    // A secret sibling of the config dir, reached only by following the symlink below — a literal
    // `..` string check wouldn't catch this, since the icon path itself never contains `..`.
    val secret = File(tmp.root, "secret.png").apply { writeBytes(byteArrayOf(1)) }
    val link = File(configDir, "assets/icons/custom.png").apply { parentFile.mkdirs() }
    Files.createSymbolicLink(link.toPath(), secret.toPath())
    assertNull(
      resolveWorkspaceIconFile(
        configIcon = "assets/icons/custom.png",
        androidAppId = null,
        webBaseUrl = null,
        baseDirs = listOf(configDir),
      ),
    )
  }

  @Test
  fun `no platform reproduces the original target-level inputs unchanged`() {
    assertEquals(
      PlatformScopedIconInputs("target-icon.png", "com.example.app", "com.example.app.ios", "https://example.com"),
      platformScopedIconInputs(
        configIcon = "target-icon.png",
        platformIcon = null,
        androidAppId = "com.example.app",
        iosBundleId = "com.example.app.ios",
        webBaseUrl = "https://example.com",
        platform = null,
      ),
    )
  }

  @Test
  fun `platform icon wins over the target-level icon`() {
    val scoped = platformScopedIconInputs(
      configIcon = "target-icon.png",
      platformIcon = "android-icon.png",
      androidAppId = "com.example.app",
      iosBundleId = null,
      webBaseUrl = null,
      platform = "android",
    )
    assertEquals("android-icon.png", scoped.icon)
  }

  @Test
  fun `falls back to the target-level icon when no platform icon is set`() {
    val scoped = platformScopedIconInputs(
      configIcon = "target-icon.png",
      platformIcon = null,
      androidAppId = "com.example.app",
      iosBundleId = null,
      webBaseUrl = null,
      platform = "android",
    )
    assertEquals("target-icon.png", scoped.icon)
  }

  @Test
  fun `android convention is scoped to android only`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = "com.example.app",
      iosBundleId = "com.example.app.ios",
      webBaseUrl = "https://example.com",
      platform = "android",
    )
    assertEquals("com.example.app", scoped.androidAppId)
    assertNull(scoped.iosBundleId)
    assertNull(scoped.webBaseUrl)
  }

  @Test
  fun `ios convention is scoped to ios only`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = "com.example.app",
      iosBundleId = "com.example.app.ios",
      webBaseUrl = "https://example.com",
      platform = "ios",
    )
    assertNull(scoped.androidAppId)
    assertEquals("com.example.app.ios", scoped.iosBundleId)
    assertNull(scoped.webBaseUrl)
  }

  @Test
  fun `web convention is scoped to web only`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = "com.example.app",
      iosBundleId = "com.example.app.ios",
      webBaseUrl = "https://example.com",
      platform = "web",
    )
    assertNull(scoped.androidAppId)
    assertNull(scoped.iosBundleId)
    assertEquals("https://example.com", scoped.webBaseUrl)
  }

  @Test
  fun `a platform with no convention falls through to explicit icons alone`() {
    val scoped = platformScopedIconInputs(
      configIcon = "target-icon.png",
      platformIcon = "visionos-icon.png",
      androidAppId = "com.example.app",
      iosBundleId = "com.example.app.ios",
      webBaseUrl = "https://example.com",
      platform = "visionos",
    )
    assertEquals("visionos-icon.png", scoped.icon)
    assertNull(scoped.androidAppId)
    assertNull(scoped.iosBundleId)
    assertNull(scoped.webBaseUrl)
  }

  @Test
  fun `explicit android app id wins over the caller's default (first declared)`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = "com.example.app.prod",
      iosBundleId = null,
      webBaseUrl = null,
      platform = "android",
      explicitAndroidAppId = "com.example.app.internal",
    )
    assertEquals("com.example.app.internal", scoped.androidAppId)
  }

  @Test
  fun `null explicit android app id falls back to the caller's default`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = "com.example.app.prod",
      iosBundleId = null,
      webBaseUrl = null,
      platform = "android",
      explicitAndroidAppId = null,
    )
    assertEquals("com.example.app.prod", scoped.androidAppId)
  }

  @Test
  fun `explicit android app id is scoped to android only`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = "com.example.app.prod",
      iosBundleId = null,
      webBaseUrl = "https://example.com",
      platform = "web",
      explicitAndroidAppId = "com.example.app.internal",
    )
    assertNull(scoped.androidAppId)
    assertEquals("https://example.com", scoped.webBaseUrl)
  }

  @Test
  fun `explicit android app id also applies when no platform is requested`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = "com.example.app.prod",
      iosBundleId = null,
      webBaseUrl = null,
      platform = null,
      explicitAndroidAppId = "com.example.app.internal",
    )
    assertEquals("com.example.app.internal", scoped.androidAppId)
  }

  @Test
  fun `explicit ios bundle id wins over the caller's default (first declared)`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = null,
      iosBundleId = "com.example.app.prod",
      webBaseUrl = null,
      platform = "ios",
      explicitIosBundleId = "com.example.app.internal",
    )
    assertEquals("com.example.app.internal", scoped.iosBundleId)
  }

  @Test
  fun `null explicit ios bundle id falls back to the caller's default`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = null,
      iosBundleId = "com.example.app.prod",
      webBaseUrl = null,
      platform = "ios",
      explicitIosBundleId = null,
    )
    assertEquals("com.example.app.prod", scoped.iosBundleId)
  }

  @Test
  fun `explicit ios bundle id is scoped to ios only`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = null,
      iosBundleId = "com.example.app.prod",
      webBaseUrl = "https://example.com",
      platform = "web",
      explicitIosBundleId = "com.example.app.internal",
    )
    assertNull(scoped.iosBundleId)
    assertEquals("https://example.com", scoped.webBaseUrl)
  }

  @Test
  fun `explicit ios bundle id also applies when no platform is requested`() {
    val scoped = platformScopedIconInputs(
      configIcon = null,
      platformIcon = null,
      androidAppId = null,
      iosBundleId = "com.example.app.prod",
      webBaseUrl = null,
      platform = null,
      explicitIosBundleId = "com.example.app.internal",
    )
    assertEquals("com.example.app.internal", scoped.iosBundleId)
  }

  @Test
  fun `resolveExplicitAndroidAppId accepts a declared app id`() {
    assertEquals(
      "com.example.app.internal",
      resolveExplicitAndroidAppId("com.example.app.internal", listOf("com.example.app.prod", "com.example.app.internal")),
    )
  }

  @Test
  fun `resolveExplicitAndroidAppId rejects an app id the target never declared`() {
    // A charset-valid id that isn't actually one of the target's declared appIds is treated as
    // absent — otherwise the appId param could probe a convention icon unrelated to this target.
    assertNull(resolveExplicitAndroidAppId("com.attacker.unrelated", listOf("com.example.app.prod")))
  }

  @Test
  fun `resolveExplicitAndroidAppId rejects any value when the target declares no app ids`() {
    assertNull(resolveExplicitAndroidAppId("com.example.app.prod", emptyList()))
  }

  @Test
  fun `resolveExplicitAndroidAppId passes through a null value`() {
    assertNull(resolveExplicitAndroidAppId(null, listOf("com.example.app.prod")))
  }

  @Test
  fun `resolveExplicitIosBundleId accepts a declared bundle id`() {
    assertEquals(
      "com.example.app.internal",
      resolveExplicitIosBundleId("com.example.app.internal", listOf("com.example.app.prod", "com.example.app.internal")),
    )
  }

  @Test
  fun `resolveExplicitIosBundleId rejects a bundle id the target never declared`() {
    assertNull(resolveExplicitIosBundleId("com.attacker.unrelated", listOf("com.example.app.prod")))
  }

  @Test
  fun `resolveExplicitIosBundleId rejects any value when the target declares no bundle ids`() {
    assertNull(resolveExplicitIosBundleId("com.example.app.prod", emptyList()))
  }

  @Test
  fun `resolveExplicitIosBundleId passes through a null value`() {
    assertNull(resolveExplicitIosBundleId(null, listOf("com.example.app.prod")))
  }
}
