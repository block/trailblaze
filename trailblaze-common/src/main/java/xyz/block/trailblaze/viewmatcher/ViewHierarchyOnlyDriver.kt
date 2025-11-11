package xyz.block.trailblaze.viewmatcher

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
 * Shell of a driver that allows us to use Maestro's internal implementation for view matching in [ElementMatcherUsingMaestro]
 * */
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

  /** This records requested taps so that we have the ability to assert on them */
  val recordedTaps = mutableListOf<Point>()

  override fun tap(point: Point) {
    recordedTaps.add(point)
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
