package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.ui.recordings.ExistingTrail

/**
 * Renders the desktop Trails UI composables that the unified single-file trail work changed, and
 * pins their observable output — the chips a unified trail shows, the detail-panel rows it lists,
 * and that the editor is text-only (no Visual/Text mode toggle for any trail format). This is the
 * render-layer fence around the logic that [TrailUnifiedScanTest], [ExistingTrailTest], and the
 * helper tests cover in isolation: it proves the composables actually wire those helpers up.
 *
 * Fixtures are intentionally generic (`target: myapp`, placeholder classifiers) so no
 * product-specific names land in this public tree. Set `TRAILBLAZE_RENDER_DUMP_DIR=<dir>` (or
 * `-Drender.dump.dir`) to also dump PNGs of each rendered surface for visual validation; unset,
 * the test is assertion-only and writes nothing.
 */
@OptIn(ExperimentalTestApi::class)
class TrailUnifiedRenderTest {

  private val tempRoot: File = File.createTempFile("trails-render", "").let { probe ->
    probe.delete()
    probe.mkdirs()
    probe
  }

  @AfterTest
  fun cleanup() {
    tempRoot.deleteRecursively()
  }

  private fun writeTrail(relativePath: String, content: String): File {
    val file = File(tempRoot, relativePath)
    file.parentFile.mkdirs()
    file.writeText(content)
    TrailConfigCache.invalidate(file.absolutePath)
    return file
  }

  // A unified single-file trail: config.devices names android-tablet (with no recording), while the
  // ios coverage comes only from a recording (not config.devices) — so Targets must be the union.
  private val unifiedYaml =
    """
      config:
        target: myapp
        title: Sample Login
        priority: P1
        devices:
          android-phone: ANDROID_ONDEVICE_ACCESSIBILITY
          android-tablet: ANDROID_ONDEVICE_INSTRUMENTATION
          ios: IOS_HOST
      trail:
        - step: Go back to the previous screen
          recording:
            android-phone:
              - pressBack: {}
            ios:
              - pressBack: {}
    """.trimIndent()

  // A legacy v1 per-device recording (a top-level list, no config/trail mapping).
  private val legacyYaml =
    """
      - config:
          target: myapp
      - tools:
        - pressBack: {}
    """.trimIndent()

  private fun dumpDir(): File? =
    (System.getenv("TRAILBLAZE_RENDER_DUMP_DIR") ?: System.getProperty("render.dump.dir"))
      ?.let { File(it).apply { mkdirs() } }

  private fun ComposeUiTest.dump(name: String) {
    val dir = dumpDir() ?: return
    val bitmap = onRoot().captureToImage().asSkiaBitmap()
    val data = SkiaImage.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG) ?: return
    File(dir, "$name.png").writeBytes(data.bytes)
  }

  @Test
  fun `unified trail card shows every platform its content covers, legacy card only its filename platform`() =
    runComposeUiTest {
      writeTrail("case_unified/trail.yaml", unifiedYaml)
      val unified = TrailsDirectoryScanner.scanForTrails(tempRoot).single { it.id == "case_unified" }

      setContent {
        MaterialTheme {
          Surface(color = Color.White) {
            Box(Modifier.width(560.dp).padding(16.dp)) {
              TrailCard(trail = unified, isSelected = false, onClick = {})
            }
          }
        }
      }
      waitForIdle()
      // Unified card derives coverage from content: Android (phone + tablet) AND iOS all show.
      onNodeWithText("android").assertIsDisplayed()
      onNodeWithText("phone").assertIsDisplayed()
      onNodeWithText("tablet").assertIsDisplayed()
      onNodeWithText("ios").assertIsDisplayed()
      dump("trail-card-unified")
    }

  @Test
  fun `legacy trail card shows only its filename platform`() =
    runComposeUiTest {
      writeTrail("case_v1/android.trail.yaml", legacyYaml)
      val legacy = TrailsDirectoryScanner.scanForTrails(tempRoot).single { it.id == "case_v1" }

      setContent {
        MaterialTheme {
          Surface(color = Color.White) {
            Box(Modifier.width(560.dp).padding(16.dp)) {
              TrailCard(trail = legacy, isSelected = false, onClick = {})
            }
          }
        }
      }
      waitForIdle()
      onNodeWithText("android").assertIsDisplayed()
      onNodeWithText("ios").assertDoesNotExist()
      dump("trail-card-legacy")
    }

  @Test
  fun `details panel lists unified type, targets and drivers`() =
    runComposeUiTest {
      val file = writeTrail("case_unified/trail.yaml", unifiedYaml)
      val node = TrailNode.TrailFile(
        name = file.name,
        path = file.absolutePath,
        existingTrail = ExistingTrail(
          absolutePath = file.absolutePath,
          relativePath = "case_unified/trail.yaml",
          fileName = file.name,
          isUnifiedContent = true,
        ),
      )

      setContent {
        MaterialTheme {
          Surface(color = Color.White) {
            Box(Modifier.width(560.dp).padding(16.dp)) {
              TrailDetailsPanel(trailFile = node, onViewYaml = {}, onOpenInFinder = {})
            }
          }
        }
      }
      waitForIdle()
      onNodeWithText("Type:").assertIsDisplayed()
      onNodeWithText("Unified single-file trail").assertIsDisplayed()
      onNodeWithText("Targets:").assertIsDisplayed()
      onNodeWithText("android-phone, android-tablet, ios").assertIsDisplayed()
      onNodeWithText("Drivers:").assertIsDisplayed()
      dump("details-panel-unified")
    }

  @Test
  fun `details panel lists platform and classifiers for a legacy trail`() =
    runComposeUiTest {
      val file = writeTrail("case_v1/ios-iphone.trail.yaml", legacyYaml)
      val node = TrailNode.TrailFile(
        name = file.name,
        path = file.absolutePath,
        existingTrail = ExistingTrail(
          absolutePath = file.absolutePath,
          relativePath = "case_v1/ios-iphone.trail.yaml",
          fileName = file.name,
        ),
      )

      setContent {
        MaterialTheme {
          Surface(color = Color.White) {
            Box(Modifier.width(560.dp).padding(16.dp)) {
              TrailDetailsPanel(trailFile = node, onViewYaml = {}, onOpenInFinder = {})
            }
          }
        }
      }
      waitForIdle()
      onNodeWithText("Platform:").assertIsDisplayed()
      onNodeWithText("Classifiers:").assertIsDisplayed()
      onNodeWithText("Type:").assertDoesNotExist()
      dump("details-panel-legacy")
    }

  @Test
  fun `editor renders a unified trail as text with no visual toggle`() =
    runComposeUiTest {
      val file = writeTrail("case_unified/trail.yaml", unifiedYaml)
      val variant = TrailVariant(
        fileName = file.name,
        absolutePath = file.absolutePath,
        classifiers = emptyList(),
      )

      setContent {
        MaterialTheme {
          TrailYamlEditorModal(
            variant = variant,
            initialContent = unifiedYaml,
            onSave = { Result.success(Unit) },
            onDismiss = {},
          )
        }
      }
      waitForIdle()
      onNodeWithText("Edit Trail").assertIsDisplayed()
      // Every trail is edited as raw YAML now — there is no Visual/Text mode toggle.
      onNodeWithText("Visual").assertDoesNotExist()
      onNodeWithText("Text").assertDoesNotExist()
      dump("editor-unified")
    }

  @Test
  fun `editor renders a legacy v1 trail as text with no visual toggle`() =
    runComposeUiTest {
      val file = writeTrail("case_v1/android.trail.yaml", legacyYaml)
      val variant = TrailVariant(
        fileName = file.name,
        absolutePath = file.absolutePath,
        classifiers = listOf(TrailblazeDeviceClassifier("android")),
      )

      setContent {
        MaterialTheme {
          TrailYamlEditorModal(
            variant = variant,
            initialContent = legacyYaml,
            onSave = { Result.success(Unit) },
            onDismiss = {},
          )
        }
      }
      waitForIdle()
      onNodeWithText("Edit Trail").assertIsDisplayed()
      onNodeWithText("Visual").assertDoesNotExist()
      onNodeWithText("Text").assertDoesNotExist()
      dump("editor-legacy")
    }
}
