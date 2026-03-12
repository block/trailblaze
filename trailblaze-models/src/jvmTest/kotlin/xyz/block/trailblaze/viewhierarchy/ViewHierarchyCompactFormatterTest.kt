package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ViewHierarchyCompactFormatterTest {

  // -- Context header --

  @Test
  fun `context header includes platform and screen dimensions`() {
    val root = ViewHierarchyTreeNode(nodeId = 1, className = "android.widget.FrameLayout")
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
    )
    assertContains(result, "Platform: Android")
    assertContains(result, "Screen: 1080x1920")
  }

  @Test
  fun `context header includes foreground app when provided`() {
    val root = ViewHierarchyTreeNode(nodeId = 1, className = "android.widget.FrameLayout")
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
      foregroundAppId = "com.example.myapp",
    )
    assertContains(result, "App: com.example.myapp")
  }

  @Test
  fun `context header omits app line when foregroundAppId is null`() {
    val root = ViewHierarchyTreeNode(nodeId = 1, className = "android.widget.FrameLayout")
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
    )
    assertFalse(result.contains("App:"))
  }

  @Test
  fun `context header includes device classifiers when provided`() {
    val root = ViewHierarchyTreeNode(nodeId = 1, className = "android.widget.FrameLayout")
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
      deviceClassifiers = listOf(
        TrailblazeDeviceClassifier("android"),
        TrailblazeDeviceClassifier("phone"),
      ),
    )
    assertContains(result, "Device: android, phone")
  }

  @Test
  fun `context header omits device line when classifiers are empty`() {
    val root = ViewHierarchyTreeNode(nodeId = 1, className = "android.widget.FrameLayout")
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
    )
    assertFalse(result.contains("Device:"))
  }

  // -- Single interactive element --

  @Test
  fun `single interactive element with correct format`() {
    val root = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.Button",
      text = "Sign In",
      clickable = true,
      resourceId = "btn_sign_in",
    )
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
    )
    assertContains(result, "[1] Button \"Sign In\" (clickable, id: \"btn_sign_in\")")
  }

  // -- Nested hierarchy with indentation --

  @Test
  fun `nested hierarchy has proper 2-space indentation`() {
    val root = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.LinearLayout",
      clickable = true,
      children = listOf(
        ViewHierarchyTreeNode(
          nodeId = 2,
          className = "android.widget.Button",
          text = "OK",
          clickable = true,
        ),
      ),
    )
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
    )
    assertContains(result, "[1] LinearLayout (clickable)")
    assertContains(result, "  [2] Button \"OK\" (clickable)")
  }

  // -- All nodes included (no structural skipping) --

  @Test
  fun `structural nodes are included with their children properly indented`() {
    val root = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.FrameLayout",
      children = listOf(
        ViewHierarchyTreeNode(
          nodeId = 2,
          className = "android.widget.LinearLayout",
          children = listOf(
            ViewHierarchyTreeNode(
              nodeId = 3,
              className = "android.widget.Button",
              text = "Click me",
              clickable = true,
            ),
          ),
        ),
      ),
    )
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
    )
    assertContains(result, "[1] FrameLayout")
    assertContains(result, "  [2] LinearLayout")
    assertContains(result, "    [3] Button \"Click me\" (clickable)")
  }

  @Test
  fun `structural node WITH resourceId is kept`() {
    val root = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.FrameLayout",
      resourceId = "container",
      children = listOf(
        ViewHierarchyTreeNode(
          nodeId = 2,
          className = "android.widget.TextView",
          text = "Hello",
        ),
      ),
    )
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
    )
    assertContains(result, "[1] FrameLayout (id: \"container\")")
    assertContains(result, "  [2] TextView \"Hello\"")
  }

  // -- State attributes --

  @Test
  fun `all state attributes rendered correctly`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 7,
      className = "android.widget.CheckBox",
      text = "Remember me",
      checked = true,
      selected = true,
      focused = true,
      scrollable = true,
      password = true,
      focusable = true,
      clickable = true,
    )
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "clickable")
    assertContains(line, "focusable")
    assertContains(line, "scrollable")
    assertContains(line, "selected")
    assertContains(line, "checked")
    assertContains(line, "focused")
    assertContains(line, "password")
  }

  @Test
  fun `hint text shown in attributes`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 3,
      className = "android.widget.EditText",
      focusable = true,
      hintText = "Enter your email",
    )
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "hint: \"Enter your email\"")
  }

  // -- Class name stripping --

  @Test
  fun `className strips package prefix`() {
    val node = ViewHierarchyTreeNode(nodeId = 1, className = "android.widget.Button")
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "Button")
    assertFalse(line.contains("android.widget"))
  }

  @Test
  fun `null className becomes View`() {
    val node = ViewHierarchyTreeNode(nodeId = 1, className = null, text = "Hello")
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "[1] View \"Hello\"")
  }

  // -- Text display --

  @Test
  fun `text takes priority over accessibilityText`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.Button",
      text = "Primary",
      accessibilityText = "Secondary",
    )
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "\"Primary\"")
    assertFalse(line.contains("\"Secondary\""))
  }

  @Test
  fun `accessibilityText used when text is null`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.ImageButton",
      accessibilityText = "Close",
      clickable = true,
    )
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "\"Close\"")
  }

  // -- Resource ID --

  @Test
  fun `resource ID shown when present`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.Button",
      text = "OK",
      clickable = true,
      resourceId = "my_button",
    )
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "id: \"my_button\"")
  }

  // -- Bounds and dimensions always included --

  @Test
  fun `bounds and dimensions always included when present`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.Button",
      text = "Sign In",
      clickable = true,
      enabled = true,
      centerPoint = "540,960",
      dimensions = "200x48",
    )
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "center: 540,960")
    assertContains(line, "size: 200x48")
  }

  @Test
  fun `disabled shown when enabled is false`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.Button",
      text = "Submit",
      clickable = true,
      enabled = false,
    )
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertContains(line, "disabled")
    assertFalse(line.contains("enabled"))
  }

  @Test
  fun `enabled true does not add enabled attribute since it is the default`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.Button",
      text = "Submit",
      clickable = true,
      enabled = true,
    )
    val line = ViewHierarchyCompactFormatter.formatSingleNode(node)
    assertFalse(line.contains("enabled"))
    assertFalse(line.contains("disabled"))
  }

  // -- Root-only tree --

  @Test
  fun `root-only tree still shows the root node`() {
    val root = ViewHierarchyTreeNode(nodeId = 1, className = "android.widget.FrameLayout")
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
    )
    assertContains(result, "[1] FrameLayout")
  }

  @Test
  fun `full header with app and classifiers`() {
    val root = ViewHierarchyTreeNode(nodeId = 1, className = "android.widget.FrameLayout")
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = 1080,
      screenHeight = 1920,
      foregroundAppId = "com.example.app",
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("tablet")),
    )
    assertContains(result, "Platform: Android")
    assertContains(result, "Screen: 1080x1920")
    assertContains(result, "App: com.example.app")
    assertContains(result, "Device: tablet")
  }

  // -- iOS platform --

  @Test
  fun `iOS platform shown in header`() {
    val root = ViewHierarchyTreeNode(nodeId = 1, className = "UIButton", text = "Tap", clickable = true)
    val result = ViewHierarchyCompactFormatter.format(
      root = root,
      platform = TrailblazeDevicePlatform.IOS,
      screenWidth = 390,
      screenHeight = 844,
    )
    assertContains(result, "Platform: iOS")
    assertContains(result, "Screen: 390x844")
  }
}
