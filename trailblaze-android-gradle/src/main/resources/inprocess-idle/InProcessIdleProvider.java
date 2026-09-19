package xyz.block.trailblaze.inprocessidle;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * Trailblaze in-process idle, hosted as a {@link ContentProvider} declared by a split APK of the
 * app under test itself. The platform instantiates every provider of a package before
 * {@code Application.onCreate}, so the {@link InProcessIdleServer} is listening before the app
 * draws its first frame — on every cold start, with nothing attached and nothing restarted.
 *
 * Nothing here is a real provider: every data method is a stub, and the manifest marks it
 * {@code exported="false"}. The class exists only for its {@link #onCreate()} hook.
 *
 * Because the split is part of the app's own package, there is no second class loader and no
 * instrumentation — the app starts exactly as it does without Trailblaze. The trade is that the
 * split must carry the installed app's exact {@code versionCode} and signature, so it is built per
 * app build (see the Gradle plugin's {@code inProcessIdle { }} and the
 * {@code trailblaze.inProcessIdle.targetApk} property).
 */
public class InProcessIdleProvider extends ContentProvider {

  @Override
  public boolean onCreate() {
    String appPackage = getContext().getPackageName();
    Log.i(InProcessIdleServer.TAG, "split provider onCreate in process of " + appPackage);
    InProcessIdleServer.start(appPackage);
    return true;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
    return null;
  }

  @Override
  public String getType(Uri uri) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    return 0;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    return 0;
  }
}
