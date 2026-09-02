package xyz.block.trailblaze.examples.sampleapp.startup

import android.util.Log

/**
 * What the app's `androidx.startup` initializers did, readable from outside the app.
 *
 * The in-process (`ANDROID_TEST`) driver runs its tests inside this app's own process, and whether
 * the app's declared initializers ran there is a property worth pinning rather than assuming — so
 * each initializer appends its own name here, and the log line carries the same information for a
 * run that only has logcat.
 *
 * Reflection-friendly on purpose: the in-process test module compiles against the app's APK, not
 * its sources, so a reader gets here through [Class.forName] and a static method.
 */
object StartupInitializerRecord {

  const val TAG: String = "TbSampleAppStartup"

  private val ran = mutableListOf<String>()

  /** Appends [name] in the order it initialized. */
  @JvmStatic
  @Synchronized
  fun record(name: String) {
    ran += name
    Log.i(TAG, "initializer ran: $name (order so far: ${ran.joinToString(",")})")
  }

  /** Every initializer that has run, in the order it ran, repeats included. */
  @JvmStatic
  @Synchronized
  fun ranInOrder(): List<String> = ran.toList()

  /** [ranInOrder] as one comma-separated string, for a caller reaching in reflectively. */
  @JvmStatic
  @Synchronized
  fun summary(): String = ran.joinToString(",")
}
