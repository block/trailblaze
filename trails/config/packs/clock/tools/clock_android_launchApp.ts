// Custom scripted tool: launch the Google Clock app via Android shell commands —
// demonstrates the per-file scripted-tool authoring path on Trailblaze's Scriptine
// runtime, and the dual-mode `adbShell` framework primitive.
//
// App-id resolution: `ctx.target.resolveAppId({ defaultAppId })` — a framework-
// provided method that consults `ctx.target.resolvedAppId` (framework-resolved
// at session start, picking whichever of the manifest's `app_ids` is actually
// installed on the connected device), falls back to `ctx.target.appIds[0]`
// (first declared candidate) when nothing was resolved, then to the caller's
// `defaultAppId` if neither is reachable. Returns `undefined` if all three
// layers miss — hence the explicit no-resolve check below.
//
// File extension is `.ts` so the IDE treats it as TypeScript; the body uses
// JSDoc-only types (no `:` parameter annotations, no `import type`) because
// per-file scripted tools are evaluated by the QuickJS host as raw
// ECMAScript with no transpile step today.

/**
 * Registered as `clock_android_launchApp` by the workspace `clock` pack.
 *
 * @param {Record<string, never>} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 * @returns {Promise<string>}
 */
export async function clock_android_launchApp(args, ctx, client) {
  if (!ctx) {
    throw new Error("clock_android_launchApp requires a live Trailblaze session context.");
  }
  const appId = ctx.target?.resolveAppId({ defaultAppId: "com.google.android.deskclock" });
  if (!appId) {
    throw new Error("clock_android_launchApp could not resolve an Android app id from ctx.target.");
  }

  // Use `adbShell` (the dual-mode device-shell primitive) rather than `launchApp`
  // (Maestro-shaped). The host-side `launchApp` requires a `MaestroTrailblazeAgent` on
  // the execution context, which V3-accessibility sessions don't have — they dispatch
  // via the accessibility RPC, not Maestro. The `am` invocation below performs the same
  // user-visible effect (launches the app's default launcher activity) and works on
  // every Android driver path, with no agent dependency.
  //
  // `adbShell` is `requiresHost: false` (dual-mode) so this scripted tool composes
  // cleanly on both deployment paths: when the daemon dispatches it on host the call
  // routes through dadb to the device; when (in the future) the on-device QuickJS
  // bundle path lights up, the same call runs natively inside the instrumentation
  // process. No `requiresHost: true` needed on this tool's descriptor.
  //
  // `am force-stop` first matches the previous `launchMode: FORCE_RESTART` semantics —
  // resets a running app back to launch state. Then `am start -a MAIN -c LAUNCHER -p <pkg>`
  // launches the package's default launcher activity without hardcoding the activity name.
  // Used `am start` (not `monkey`) because monkey's exit code is unreliable on stock
  // emulators (returns non-zero even on successful launches), and the host shell command
  // path strictly checks exit-code-zero for success — it would otherwise surface the
  // launch as a failure even when the app opened correctly.
  await client.callTool("adbShell", {
    command: `am force-stop ${appId}`,
  });
  await client.callTool("adbShell", {
    command: `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${appId}`,
  });

  return `Launched ${appId} (force-stop + am start MAIN/LAUNCHER).`;
}
