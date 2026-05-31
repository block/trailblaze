import { trailblaze } from "@trailblaze/scripting";

/**
 * Force-stops the Google Clock app and re-launches it via the package's default
 * launcher activity, so the next step starts from a clean app state. Equivalent to a
 * Maestro `launchApp({ launchMode: FORCE_RESTART })` against `com.google.android.deskclock`,
 * but composed entirely from the dual-mode `android_adbShell` primitive so the same tool
 * works on host- and on-device-dispatched scripted-tool sessions.
 *
 * Use this as the first step of any clock trail that wants a fresh launch state. No
 * arguments — the framework resolves the app id from the `clock` trailmap manifest's
 * `app_ids:` list against installed apps on the connected device.
 */
// Implementation notes — a worked example of the typed `trailblaze.tool()` authoring path
// and of composing the dual-mode `android_adbShell` framework primitive.
//
// The doc comment above the `export const` is the LLM-facing description — typed tools
// carry it there, not in a `.yaml` manifest. The spec object only carries registration
// gates (`supportedPlatforms`, `requiresContext`); because `requiresContext: true`, the
// runtime guarantees `ctx` is present before the handler runs, so there's no `if (!ctx)`
// guard. We still optional-chain `ctx.target` because a target-less session (web-only,
// scratch tool, unit-test fixture) leaves it undefined.
//
// App-id resolution: `ctx.target?.resolveAppId({ defaultAppId })` is a framework-provided
// method that consults `ctx.target.resolvedAppId` (framework-resolved at session start,
// picking whichever of the manifest's `app_ids` is actually installed on the connected
// device), falls back to `ctx.target.appIds[0]` (first declared candidate) when nothing
// was resolved, then to the caller's `defaultAppId` if neither is reachable. Returns
// `undefined` if all three layers miss — hence the explicit no-resolve check below.
//
// Use `android_adbShell` (the dual-mode device-shell primitive) rather than `launchApp`
// (Maestro-shaped). The host-side `launchApp` requires a `MaestroTrailblazeAgent` on the
// execution context, which V3-accessibility sessions don't have — they dispatch via the
// accessibility RPC, not Maestro. The `am` invocation below performs the same user-visible
// effect (launches the app's default launcher activity) and works on every Android driver
// path, with no agent dependency.
//
// `android_adbShell` is `requiresHost: false` (dual-mode), so this tool needs no
// `requiresHost` in its spec and composes cleanly on both deployment paths: when the daemon
// dispatches it on host the call routes through dadb to the device; when (in the future)
// the on-device QuickJS bundle path lights up, the same call runs natively inside the
// instrumentation process.
//
// `am force-stop` first matches the previous `launchMode: FORCE_RESTART` semantics — resets
// a running app back to launch state. Then `am start -a MAIN -c LAUNCHER -p <pkg>` launches
// the package's default launcher activity without hardcoding the activity name. Used
// `am start` (not `monkey`) because monkey's exit code is unreliable on stock emulators
// (returns non-zero even on successful launches), and the host shell command path strictly
// checks exit-code-zero for success — it would otherwise surface the launch as a failure
// even when the app opened correctly.
export const clock_android_launchApp = trailblaze.tool(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (_input, ctx) => {
    const appId = ctx.target?.resolveAppId({ defaultAppId: "com.google.android.deskclock" });
    if (!appId) {
      throw new Error("clock_android_launchApp could not resolve an Android app id from ctx.target.");
    }

    // Pre-grant the runtime POST_NOTIFICATIONS permission. On API 33+ the alarm app
    // prompts for this on first launch, and host-rpc trails (whose tool RPCs only see
    // the foreground app) can't tap on the cross-process permissioncontroller dialog.
    // Granting before `am start` keeps both CI paths (host-rpc and on-device) on the same
    // screen flow. Wrapped in try/catch so platforms that pre-date the runtime permission
    // (API < 33) — or builds where the permission isn't declared by the app — don't fail
    // the launch.
    try {
      await ctx.tools.android_adbShell({
        command: ["pm", "grant", appId, "android.permission.POST_NOTIFICATIONS"],
      });
    } catch (_) {
      // Best-effort grant — fall through to launch.
    }
    await ctx.tools.android_adbShell({
      command: ["am", "force-stop", appId],
    });
    await ctx.tools.android_adbShell({
      command: [
        "am", "start",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-p", appId,
      ],
    });

    return `Launched ${appId} (grant POST_NOTIFICATIONS + force-stop + am start MAIN/LAUNCHER).`;
  },
);
