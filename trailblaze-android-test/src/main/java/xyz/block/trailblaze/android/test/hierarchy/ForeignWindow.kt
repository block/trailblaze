package xyz.block.trailblaze.android.test.hierarchy

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.android.test.AndroidTestInstrumentation
import xyz.block.trailblaze.android.test.encodeScreenshot
import xyz.block.trailblaze.android.test.hierarchy.a11y.isTextAcceptingNode
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.android.test.AnotherAppInFrontException
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Another app's window in front of the app under test: a browser the app opened, a runtime
 * permission prompt, a system dialog.
 *
 * Nothing in this process draws that window, so Espresso and the Compose rule can neither see nor
 * touch it, and the app's own Activity sits paused or stopped behind it. The platform's
 * accessibility tree and UiAutomation's input injection reach every window, which is how the
 * accessibility driver drives every screen, so while another app is in front this driver reads and
 * acts the same way. It is asked again on every poll, so the in-process path takes over again the
 * moment the app is back in front.
 *
 * Every action here returns once the other app has reacted to it: see [settle].
 */
object ForeignWindow {

  private val targetPackage: String by lazy {
    InstrumentationRegistry.getInstrumentation().targetContext.packageName
  }

  /**
   * The same flags every other UiAutomation caller here uses: a different set reconnects, and each
   * reconnect switches accessibility off and on for every app.
   */
  private fun uiAutomation(): UiAutomation = InstrumentationRegistry.getInstrumentation()
    .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    .also(InProcessAccessibilityTree::ensureDriverFlags)

  /**
   * The package of the front window when another app owns it, else null: this app's window.
   *
   * The front window is the active one unless that is the keyboard, which belongs to the IME's
   * package while this app is still the one in front; then it is the topmost application window.
   *
   * The front window can have no readable root for a moment while another app opens or closes.
   * Reading that as "this app is in front" would send the next action to the app's own Activity,
   * which is paused behind the other app and waited on for minutes, so a missing root is asked
   * again for up to [ROOT_SETTLE_MS] first. Still missing after that is treated as this app.
   */
  fun frontPackage(): String? {
    val deadline = SystemClock.uptimeMillis() + ROOT_SETTLE_MS
    while (true) {
      frontRoot()?.let { root ->
        return root.packageName?.toString()?.takeIf { it != targetPackage }
      }
      if (SystemClock.uptimeMillis() >= deadline) return null
      SystemClock.sleep(FOCUS_POLL_MS)
    }
  }

  /** How many application windows are on screen: more than one means a popup, sheet or dialog. */
  fun applicationWindowCount(): Int =
    runCatching { uiAutomation().windows }.getOrNull().orEmpty()
      .count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

  private fun frontRoot(): AccessibilityNodeInfo? {
    val automation = uiAutomation()
    val windows = runCatching { automation.windows }.getOrNull().orEmpty()
    val active = windows.firstOrNull { it.isActive }
    val front = active?.takeIf { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
      ?: windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
    front?.let { window -> runCatching { window.root }.getOrNull()?.let { return it } }
    // No window list (or no root in it): the active window's root, unless that is the keyboard.
    if (windows.isNotEmpty()) return null
    return runCatching { automation.rootInActiveWindow }.getOrNull()
      ?.takeIf { root -> root.window?.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
  }

  /**
   * A tap at a screen point, delivered to whichever window is there. A [doubleTap] is two taps
   * [DOUBLE_TAP_GAP_MS] apart, inside the platform's double-tap timeout, settled once after both.
   */
  fun tap(x: Int, y: Int, longPress: Boolean = false, doubleTap: Boolean = false) {
    repeat(if (doubleTap) 2 else 1) { index ->
      if (index > 0) SystemClock.sleep(DOUBLE_TAP_GAP_MS)
      val downAt = SystemClock.uptimeMillis()
      inject(MotionEvent.ACTION_DOWN, downAt, downAt, x, y)
      SystemClock.sleep(if (longPress) LONG_PRESS_MS else TAP_MS)
      inject(MotionEvent.ACTION_UP, downAt, SystemClock.uptimeMillis(), x, y)
    }
    settle()
  }

  /**
   * A finger drag from one screen point to another over [durationMs], delivered to whichever
   * window is there, as `adb input swipe` does: a down, moves every frame along the line, an up.
   */
  fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long, fling: Boolean = true) {
    val downAt = SystemClock.uptimeMillis()
    inject(MotionEvent.ACTION_DOWN, downAt, downAt, startX, startY)
    val steps = (durationMs / FRAME_MS).coerceAtLeast(1)
    for (step in 1..steps) {
      SystemClock.sleep(FRAME_MS)
      val fraction = step.toFloat() / steps
      inject(
        MotionEvent.ACTION_MOVE,
        downAt,
        SystemClock.uptimeMillis(),
        startX + ((endX - startX) * fraction).toInt(),
        startY + ((endY - startY) * fraction).toInt(),
      )
    }
    if (!fling) {
      // Resting on the end point before lifting leaves the finger with no velocity, so the list
      // stops where the drag did, as under the accessibility driver's gesture strokes.
      SystemClock.sleep(NO_FLING_HOLD_MS)
      inject(MotionEvent.ACTION_MOVE, downAt, SystemClock.uptimeMillis(), endX, endY)
    }
    inject(MotionEvent.ACTION_UP, downAt, SystemClock.uptimeMillis(), endX, endY)
    settle()
  }

  /** The whole display in pixels, system bars included: the space recorded coordinates are in. */
  fun screenSize(): Pair<Int, Int> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val windowManager = context.getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
      @Suppress("DEPRECATION")
      android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
    }
  }

  private fun inject(action: Int, downAt: Long, at: Long, x: Int, y: Int) {
    val event = MotionEvent.obtain(downAt, at, action, x.toFloat(), y.toFloat(), 0)
    event.source = InputDevice.SOURCE_TOUCHSCREEN
    try {
      check(uiAutomation().injectInputEvent(event, true)) { "The device refused an injected touch at ($x, $y)." }
    } finally {
      event.recycle()
    }
  }

  /** What [appendToFocusedField] found. */
  enum class AppendOutcome {
    /** The field shows a change. */
    APPLIED,

    /**
     * The action was taken but the field could not be read back. Typing again could enter the text
     * twice, so it is not retried.
     */
    UNCONFIRMED,

    /**
     * No field, the action refused, the field kept its text, or its text cannot be appended to
     * safely: only key events reach it. Nothing this call set is left in the field.
     */
    REJECTED,
  }

  /**
   * Appends [text] to the field holding input focus, as the accessibility driver types:
   * `ACTION_SET_TEXT` on the focused node. Polled briefly, because the tap that focused the field
   * reaches another process and its focus can land a frame later. A masked field can accept the
   * action and its TextWatcher reject the value, so the field is read back until it changes; a
   * browser reports an accepted edit a little later, which is why that read is polled too.
   *
   * A password field shows bullets or nothing rather than its value, so setting it to what it
   * shows plus [text] would replace its value: unless [fieldKnownEmpty] (it was just cleared), it
   * gets key events, which insert without reading it. Its readback compares what it shows, which
   * for a field that shows nothing proves nothing either way.
   */
  fun appendToFocusedField(text: String, fieldKnownEmpty: Boolean = false): AppendOutcome {
    // Nothing to add, so nothing to write: setting a field that shows bullets or nothing to what
    // it shows would replace its value.
    if (text.isEmpty()) return AppendOutcome.APPLIED
    val field = awaitFocusedField() ?: return AppendOutcome.REJECTED
    if (field.isPassword && !fieldKnownEmpty) return AppendOutcome.REJECTED
    val existing = if (fieldKnownEmpty) "" else field.currentText()
    if (!field.replaceText(existing + text)) return AppendOutcome.REJECTED
    settle()
    val deadline = SystemClock.uptimeMillis() + READBACK_MS
    while (true) {
      if (!field.refresh()) return AppendOutcome.UNCONFIRMED
      val shown = field.currentText()
      if (shown != existing) return AppendOutcome.APPLIED
      if (SystemClock.uptimeMillis() >= deadline) {
        if (field.isPassword && shown.isEmpty()) return AppendOutcome.UNCONFIRMED
        // The action was accepted, so the field may yet show it: a browser can report an edit
        // later than this waits. It is set back first, and key events follow only once the field
        // reads as set back, so they cannot land on top of a late edit and enter the text twice.
        // A refused or unconfirmed reset leaves that possible, so nothing more is typed.
        if (!field.replaceText(existing)) return AppendOutcome.UNCONFIRMED
        settle()
        val reset = currentFocusedField() ?: field
        return if (reset.refresh() && reset.currentText() == existing) AppendOutcome.REJECTED else AppendOutcome.UNCONFIRMED
      }
      SystemClock.sleep(FOCUS_POLL_MS)
    }
  }

  /** What [clearFocusedField] found. */
  enum class ClearOutcome { CLEARED, NO_FIELD, KEPT_TEXT }

  /**
   * Empties the field holding input focus: `ACTION_SET_TEXT` first, then, when the field kept its
   * text (a masked field's TextWatcher can reject the action), a delete key per character.
   */
  fun clearFocusedField(): ClearOutcome {
    val field = awaitFocusedField() ?: return ClearOutcome.NO_FIELD
    val shownBefore = field.currentText()
    val accepted = field.replaceText("")
    if (accepted) settle()
    // Read from the field focused now: an edit can make the page redraw the field as a new node,
    // which leaves the one acted on detached and unreadable.
    val cleared = currentFocusedField() ?: field
    if (cleared.isEmptyNow()) {
      // A password field that shows nothing reads empty whether or not it is: an accepted clear
      // there is taken on trust, and a refused one proves nothing. One that shows bullets is read
      // back like any other field.
      val unreadable = field.isPassword && shownBefore.isEmpty()
      return if (!unreadable || accepted) ClearOutcome.CLEARED else ClearOutcome.KEPT_TEXT
    }
    val length = cleared.currentText().length
    // To the end, then a delete per character, in one shell call.
    pressKeys(listOf(KeyEvent.KEYCODE_MOVE_END) + List(length) { KeyEvent.KEYCODE_DEL })
    return if ((currentFocusedField() ?: cleared).isEmptyNow()) ClearOutcome.CLEARED else ClearOutcome.KEPT_TEXT
  }

  private fun AccessibilityNodeInfo.isEmptyNow(): Boolean = refresh() && currentText().isEmpty()

  /** The text field holding input focus now, without waiting for one. */
  private fun currentFocusedField(): AccessibilityNodeInfo? =
    runCatching { uiAutomation().findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
      ?.takeIf(::acceptsText)

  private fun awaitFocusedField(): AccessibilityNodeInfo? {
    val deadline = SystemClock.uptimeMillis() + FOCUS_SETTLE_MS
    while (true) {
      currentFocusedField()?.let { return it }
      if (SystemClock.uptimeMillis() >= deadline) return null
      SystemClock.sleep(FOCUS_POLL_MS)
    }
  }

  /** The accessibility driver's test: a Compose field can offer SET_TEXT without isEditable. */
  private fun acceptsText(node: AccessibilityNodeInfo): Boolean =
    isTextAcceptingNode(node.isEditable, node.className?.toString(), node.actionList.map { it.id })

  /** An empty field reports its hint as its text; that is not text to keep. */
  private fun AccessibilityNodeInfo.currentText(): String =
    if (isShowingHintText) "" else text?.toString().orEmpty()

  private fun AccessibilityNodeInfo.replaceText(value: String): Boolean = performAction(
    AccessibilityNodeInfo.ACTION_SET_TEXT,
    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
  )

  /**
   * Dismisses the keyboard the way the accessibility driver does, with BACK, and only while one is
   * on screen to take it: with no keyboard up, BACK would navigate the other app instead. While it
   * is shown, the keyboard takes BACK before the app's window does and hides itself, so the app
   * never sees it. Nothing in this process has a keyboard-only hide for another app's window: the
   * input method manager hides only for a window this process owns.
   */
  fun hideKeyboard() {
    // A keyboard window with empty bounds is listed mid-transition but not drawn; BACK there would
    // leave the page rather than close a keyboard.
    val imeShown = runCatching {
      uiAutomation().windows.any { window ->
        window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
          !android.graphics.Rect().also(window::getBoundsInScreen).isEmpty
      }
    }.getOrDefault(false)
    if (imeShown) pressBack()
  }

  /** BACK, delivered to whichever window is in front. */
  fun pressBack() {
    uiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    settle()
  }

  /**
   * [text] as key events into the focused window, through the shell: this process may not inject
   * keys into a window it does not own. `input text` is not run through a shell, but its arguments
   * are split on whitespace, it reads `%` as an escape, and it types only what the virtual keyboard
   * map has a key for, which is printable ASCII: a newline and a tab are sent as the ENTER and TAB
   * keys, and any other character it would drop or change (other whitespace, `%`, emoji, accented
   * letters) is refused before anything is typed, rather than typing a different value.
   *
   * Returns why [text] cannot be typed, or null once it has been.
   */
  fun typeKeys(text: String): String? {
    text.firstOrNull { it != '\n' && it != '\t' && (it !in ' '..'~' || it == '%') }?.let { char ->
      return "Cannot type U+%04X as key events into another app's window.".format(char.code)
    }
    var segment = StringBuilder()
    fun flush() {
      if (segment.isNotEmpty()) shell("input text ${segment.toString().replace(" ", "%s")}")
      segment = StringBuilder()
    }
    text.forEach { char ->
      when (char) {
        '\n' -> { flush(); shell("input keyevent ${KeyEvent.KEYCODE_ENTER}") }
        '\t' -> { flush(); shell("input keyevent ${KeyEvent.KEYCODE_TAB}") }
        else -> segment.append(char)
      }
    }
    flush()
    settle()
    return null
  }

  /** One key event into the focused window, through the shell for the same reason as [typeKeys]. */
  fun pressKey(keyCode: Int) = pressKeys(listOf(keyCode))

  /** Key events in order, in one shell call. */
  private fun pressKeys(keyCodes: List<Int>) {
    shell("input keyevent ${keyCodes.joinToString(" ")}")
    settle()
  }

  /**
   * Runs [command] and returns once it has exited. Closing the command's output does not wait for
   * it, so a settle that followed could finish before the input had even started. Its output is
   * read to the end, which comes when the command exits.
   *
   * Throws when it has not exited after [SHELL_TIMEOUT_MS]: its input may still be arriving, so
   * the step cannot report it as done, nor let the next step act while it is.
   */
  private fun shell(command: String) {
    val output = uiAutomation().executeShellCommand(command)
    val reader = Thread { runCatching { ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() } } }
    reader.start()
    reader.join(SHELL_TIMEOUT_MS)
    check(!reader.isAlive) {
      // The command's name only: `input text` carries what was typed, which can be a password.
      "`${command.split(' ').take(2).joinToString(" ")}` had not finished after ${SHELL_TIMEOUT_MS}ms, so its input may still be arriving in another app's window."
    }
  }

  /**
   * Activates [info] with the accessibility click action, as TalkBack would, then waits for the
   * window to settle. False when the node refused the action.
   */
  fun accessibilityClick(info: AccessibilityNodeInfo): Boolean =
    info.performAction(AccessibilityNodeInfo.ACTION_CLICK).also { if (it) settle() }

  /**
   * Returns once the other app has reacted to an action: no accessibility events from it for
   * [SETTLE_QUIET_MS], measured from after the action. Nothing in this process can see that app go
   * idle, and its accessibility events are the signal the accessibility driver settles on too.
   *
   * The first beat is unconditional. The other app handles the action in its own process, so
   * straight after it nothing has been sent yet, and the screen reads as quiet before it has
   * changed. A soft keyboard opening after a field is tapped is the case that matters: it resizes
   * the page, and a tap aimed before then misses what it was aimed at.
   */
  private fun settle() {
    SystemClock.sleep(SETTLE_QUIET_MS)
    InProcessAccessibilityTree.awaitEventsQuiet(SETTLE_QUIET_MS, SETTLE_TIMEOUT_MS)
  }

  /**
   * The screen as it is now: another app's window through [screenStateIfInFront], else the app's
   * own capture from [appScreenState].
   *
   * The front can change between the two reads. An app launched a moment ago is not in front at
   * the first and leaves this app's Activity paused by the second, whose wait then gives up with
   * [AnotherAppInFrontException], so the screen is read again rather than waited on.
   */
  fun screenState(
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
    includeScreenshot: Boolean,
    appScreenState: () -> ScreenState,
  ): ScreenState = retryWhileAnotherAppComesToFront {
    screenStateIfInFront(deviceClassifiers, includeScreenshot) ?: appScreenState()
  }

  /**
   * Runs [read] again when it gave up with [AnotherAppInFrontException], so a read that checks the
   * front first then sees the other app there and reads its window instead.
   */
  fun <T> retryWhileAnotherAppComesToFront(read: () -> T): T {
    val deadline = SystemClock.uptimeMillis() + FRONT_CHANGE_TIMEOUT_MS
    while (true) {
      try {
        return read()
      } catch (e: AnotherAppInFrontException) {
        if (SystemClock.uptimeMillis() >= deadline) throw e
        SystemClock.sleep(FRONT_CHANGE_POLL_MS)
      }
    }
  }

  /**
   * The screen while another app is in front: its accessibility tree and a full-device screenshot.
   * Null while this app is in front, where the in-process capture is the screen, and when a tree
   * was asked for and no window root resolved.
   */
  fun screenStateIfInFront(
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
    includeScreenshot: Boolean,
    /** Scaled and encoded as the app's own capture would be; see [encodeScreenshot]. */
    screenshotScalingConfig: ScreenshotScalingConfig? = null,
    /** False skips the tree walk, for the mirror's pixels-only polls. */
    includeTree: Boolean = true,
  ): ScreenState? {
    if (frontPackage() == null) return null
    // Null only when this app is in front: an Activity-backed capture would wait on the Activity
    // paused behind the other app. A tree the other app does not give up for a moment is asked for
    // again, and after that the screen is reported without one rather than not at all.
    val captured = if (includeTree) awaitForeignTree() else null
    val bitmap = if (includeScreenshot) AndroidTestInstrumentation.deviceScreenshot() else null
    val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
    val encoded = bitmap?.encodeScreenshot(screenshotScalingConfig)
    val foregroundAppId = frontPackage()
    return object : ScreenState {
      private val compactElements: CompactScreenElements? by lazy {
        captured?.tree?.let { CompactScreenElements.buildForAndroid(it, screenHeight = deviceHeight, screenWidth = deviceWidth) }
      }

      // The element list an agent reads the screen from, as the app's own capture builds it.
      override val viewHierarchyTextRepresentation: String? by lazy {
        compactElements?.buildTextRepresentation(foregroundAppId = foregroundAppId)
      }
      override val annotationElements: List<AnnotationElement>? by lazy { compactElements?.buildAnnotationElements() }
      override val screenshotBytes: ByteArray? = encoded
      override val deviceWidth: Int = bitmap?.width ?: metrics.widthPixels
      override val deviceHeight: Int = bitmap?.height ?: metrics.heightPixels
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeNodeTree: TrailblazeNode? = captured?.tree

      // So an absence read from a capture that lost a subtree is not taken as "not on screen".
      override val droppedNodeFetches: Int? = captured?.droppedFetches
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = deviceClassifiers
    }
  }

  private fun awaitForeignTree(): InProcessAccessibilityTree.Captured? {
    val deadline = SystemClock.uptimeMillis() + ROOT_SETTLE_MS
    while (true) {
      InProcessAccessibilityTree.capture()?.let { return it }
      if (SystemClock.uptimeMillis() >= deadline) return null
      SystemClock.sleep(FOCUS_POLL_MS)
    }
  }

  private const val TAP_MS = 50L
  private const val FRONT_CHANGE_TIMEOUT_MS = 10_000L
  private const val FRONT_CHANGE_POLL_MS = 100L
  private const val LONG_PRESS_MS = 800L
  private const val DOUBLE_TAP_GAP_MS = 50L
  private const val NO_FLING_HOLD_MS = 150L
  private const val FRAME_MS = 16L
  private const val FOCUS_SETTLE_MS = 2_000L
  private const val FOCUS_POLL_MS = 100L
  private const val READBACK_MS = 1_000L
  private const val ROOT_SETTLE_MS = 2_000L
  private const val SETTLE_QUIET_MS = 300L
  private const val SHELL_TIMEOUT_MS = 10_000L

  /** A page that never stops sending events still moves on after this. */
  private const val SETTLE_TIMEOUT_MS = 5_000L
}
