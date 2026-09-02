package xyz.block.trailblaze.android.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.CountDownLatch

/**
 * Manifest receiver that records what a broadcast delivered, for
 * [AndroidTestSendBroadcastOnDeviceTest].
 *
 * Declared in the test APK's manifest rather than registered at runtime so a broadcast can address
 * it BY COMPONENT — that is the path a real harness uses to reach an app's sign-in receiver, and a
 * runtime-registered receiver has no class name to address.
 *
 * The delivery record is static because the framework instantiates the receiver, so the test never
 * holds the instance that runs.
 */
class BroadcastProofReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context?, intent: Intent?) {
    lastExtraValue = intent?.getStringExtra(EXTRA_KEY)
    delivered.countDown()
  }

  companion object {
    const val ACTION = "xyz.block.trailblaze.android.test.SEND_BROADCAST_PROOF"
    const val EXTRA_KEY = "payload"

    /** Reset by the test before it dispatches, so a stale delivery cannot pass a later run. */
    @Volatile var delivered = CountDownLatch(1)

    @Volatile var lastExtraValue: String? = null

    fun reset() {
      delivered = CountDownLatch(1)
      lastExtraValue = null
    }
  }
}
