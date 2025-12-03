package xyz.block.trailblaze.viewmatcher.matching

import maestro.Capability
import maestro.DeviceInfo
import maestro.DeviceOrientation
import maestro.Driver
import maestro.KeyCode
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import okio.Sink
import java.io.File

/**
 * Minimal Driver implementation for using Maestro's internal view matching logic.
 *
 * This class provides only the essential functionality needed by [ElementMatcherUsingMaestro] to perform
 * element matching based on a view hierarchy. Most methods are intentionally unimplemented (throwing `TODO`)
 * because they're not needed for pure view hierarchy analysis without actual device interaction.
 *
 * ## Implemented methods
 * - [deviceInfo]: Returns the provided device metadata
 * - [contentDescriptor]: Returns the provided view hierarchy tree
 * - [waitForAppToSettle]: Returns the initial hierarchy unchanged
 * - [capabilities]: Returns FAST_HIERARCHY capability
 * - [takeScreenshot]: No-op implementation
 * - [tap]: No-op implementation (no actual device interaction)
 *
 * ## Why most methods throw TODO
 * This driver is designed solely for static view hierarchy analysis and element matching.
 * It doesn't interact with real devices, so operations like launching apps, pressing keys,
 * swiping, or managing device state are not applicable and intentionally left unimplemented.
 *
 * @property rootTreeNode The view hierarchy to analyze
 * @property deviceInfo Device metadata for platform-specific matching semantics
 */
class ViewHierarchyOnlyDriver(
  val rootTreeNode: TreeNode,
  val deviceInfo: DeviceInfo,
) : Driver {

  override fun name(): String {
    TODO("Not yet implemented")
  }

  override fun open() {
    TODO("Not yet implemented")
  }

  override fun close() {
    TODO("Not yet implemented")
  }

  override fun deviceInfo(): DeviceInfo = deviceInfo

  override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
    TODO("Not yet implemented")
  }

  override fun stopApp(appId: String) {
    TODO("Not yet implemented")
  }

  override fun killApp(appId: String) {
    TODO("Not yet implemented")
  }

  override fun clearAppState(appId: String) {
    TODO("Not yet implemented")
  }

  override fun clearKeychain() {
    TODO("Not yet implemented")
  }

  override fun tap(point: Point) {
    // No-op: this is a view hierarchy-only driver
  }

  override fun longPress(point: Point) {
    TODO("Not yet implemented")
  }

  override fun pressKey(code: KeyCode) {
    TODO("Not yet implemented")
  }

  override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode = rootTreeNode

  override fun scrollVertical() {
    TODO("Not yet implemented")
  }

  override fun isKeyboardVisible(): Boolean {
    TODO("Not yet implemented")
  }

  override fun swipe(start: Point, end: Point, durationMs: Long) {
    TODO("Not yet implemented")
  }

  override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
    TODO("Not yet implemented")
  }

  override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
    TODO("Not yet implemented")
  }

  override fun backPress() {
    TODO("Not yet implemented")
  }

  override fun inputText(text: String) {
    TODO("Not yet implemented")
  }

  override fun openLink(
    link: String,
    appId: String?,
    autoVerify: Boolean,
    browser: Boolean,
  ) {
    TODO("Not yet implemented")
  }

  override fun hideKeyboard() {
    TODO("Not yet implemented")
  }

  override fun takeScreenshot(out: Sink, compressed: Boolean) {
    // Do Nothing
  }

  override fun startScreenRecording(out: Sink): ScreenRecording {
    TODO("Not yet implemented")
  }

  override fun setLocation(latitude: Double, longitude: Double) {
    TODO("Not yet implemented")
  }

  override fun setOrientation(orientation: DeviceOrientation) {
    TODO("Not yet implemented")
  }

  override fun eraseText(charactersToErase: Int) {
    TODO("Not yet implemented")
  }

  override fun setProxy(host: String, port: Int) {
    TODO("Not yet implemented")
  }

  override fun resetProxy() {
    TODO("Not yet implemented")
  }

  override fun isShutdown(): Boolean {
    TODO("Not yet implemented")
  }

  override fun isUnicodeInputSupported(): Boolean {
    TODO("Not yet implemented")
  }

  override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
    TODO("Not yet implemented")
  }

  override fun waitForAppToSettle(
    initialHierarchy: ViewHierarchy?,
    appId: String?,
    timeoutMs: Int?,
  ): ViewHierarchy? = initialHierarchy

  override fun capabilities(): List<Capability> = listOf(Capability.FAST_HIERARCHY)

  override fun setPermissions(appId: String, permissions: Map<String, String>) {
    TODO("Not yet implemented")
  }

  override fun addMedia(mediaFiles: List<File>) {
    TODO("Not yet implemented")
  }

  override fun isAirplaneModeEnabled(): Boolean {
    TODO("Not yet implemented")
  }

  override fun setAirplaneMode(enabled: Boolean) {
    TODO("Not yet implemented")
  }
}
