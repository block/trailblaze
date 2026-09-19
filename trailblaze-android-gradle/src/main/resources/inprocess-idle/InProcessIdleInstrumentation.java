package xyz.block.trailblaze.inprocessidle;

import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

/**
 * Trailblaze in-process idle, hosted as a bare {@link Instrumentation} (no JUnit, no
 * androidx.test) that {@code am instrument} attaches to the app under test. Runs the
 * {@link InProcessIdleServer} in that app's process.
 *
 * Attaching a foreign-package instrumentation makes the framework open the app's APK in a
 * second class loader on every cold start, which costs a heavy app seconds per launch. Where the
 * app's exact installed build is known at build time, prefer the split-APK host
 * ({@link InProcessIdleProvider}), which has no such cost; this host remains for the cases where
 * it is not (a device with an app of unknown version, or a platform without split support).
 */
public class InProcessIdleInstrumentation extends Instrumentation {

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    Log.i(InProcessIdleServer.TAG, "onCreate in process of " + getTargetContext().getPackageName());
    start(); // move to onStart without launching any activity
  }

  @Override
  public void onStart() {
    InProcessIdleServer.start(getTargetContext().getPackageName());
    // Do NOT call finish(): keep the instrumentation (and the server thread) alive until killed.
  }
}
