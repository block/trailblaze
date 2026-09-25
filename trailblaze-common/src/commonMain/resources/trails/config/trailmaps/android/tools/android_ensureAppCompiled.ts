import { trailblaze } from "@trailblaze/scripting";

/**
 * Makes sure ART has compiled artifacts for an app, so its cold start does not verify the whole
 * APK from scratch on every process start.
 *
 * A sideloaded install compiles nothing. `adb install` writes the APK and returns, leaving the
 * package at `status=run-from-apk`: with no profile to compile against, ART's install-time step
 * produces no artifacts and defers to background dexopt, which needs an idle, charging device and
 * never runs on an emulator that lives for one job. So ART loads and verifies the dex on every cold
 * start, and nothing reports it: the app runs, only slower. This is the normal state of a freshly
 * installed debug app, not an accident. Once made, the artifacts live beside the APK, so they
 * survive `pm clear` and reboots and die only on reinstall. Measured, a large app's cold start went
 * from 9.6–10.2s to 2.2–3.1s once compiled; the one-time compile cost 27.6s, so it pays for itself
 * after about four cold starts and is a loss below that.
 *
 * Host-driven sessions already run this check once at session start (`SessionAppCompileEnsurer`,
 * which is Kotlin because it runs before any scripted runtime exists), so a trail running from a
 * host needs this step only to force a recompile or to read the state. It earns its place for runs
 * with no host — an instrumented (`connectedAndroidTest`) run pays the missing artifacts on every
 * cold start exactly the same way — and for a scripted launch tool wanting the guarantee before its
 * first launch.
 *
 * Composes only the dual-mode `android_adbShell` primitive, so the same body serves the host
 * transport and the on-device instrumentation runner. It throws on a non-zero exit, so there is no
 * silent-failure path here.
 *
 * There is no timeout to thread through because no tool call in this framework takes one. That is
 * fine on the on-device transport, which bounds every dispatch itself. It is NOT fine on the host
 * transport, whose adb shell read is unbounded: a wedged device hangs the call rather than failing
 * it. The Kotlin session-start hook does not share that exposure — it reaches adb through a
 * primitive that bounds the wait on a separate thread, which is the only shape that returns
 * against a wedged socket read.
 */

/** The cheapest filter that stops per-launch verification, and all ART allows for a debug build. */
const DEFAULT_COMPILER_FILTER = "verify";

/**
 * The filters `pm compile -m` accepts. Also the injection guard: a closed set is simpler to reason
 * about than an escape, even though `android_adbShell` is argv-shaped and escapes each element.
 */
const KNOWN_COMPILER_FILTERS = [
  "assume-verified",
  "verify",
  "quicken",
  "space-profile",
  "space",
  "speed-profile",
  "speed",
  "everything-profile",
  "everything",
];

const DEXOPT_SECTION_HEADER = /^Dexopt state:\s*$/;
/** `  [com.example.app]` — one package's entry inside `Dexopt state:`. */
const DEXOPT_PACKAGE_HEADER = /^\s*\[([A-Za-z0-9._]+)\]\s*$/;
/** `      x86_64: [status=run-from-apk] [reason=unknown] [primary-abi]`. */
const DEXOPT_STATUS_LINE = /^\s*([A-Za-z0-9_-]+): \[status=([^\]]+)\]/;
const DEXOPT_REASON = /\[reason=([^\]]+)\]/;
/** `    path: /data/app/~~ab==/com.example-cd==/base.apk` — one primary code path. */
const DEXOPT_PATH_LINE = /^\s*path: /;
const SECONDARY_DEX_HEADER = /known secondary dex files:/;

/**
 * Anchored, so a status ART names that this list does not know reads as NOT compiled: a needless
 * compile costs seconds once, while a missed one costs every cold start of the run. `run-from-apk`
 * (and its `-fallback` twin) and `extract` mean the dex is verified from the APK every launch.
 */
const COMPILED_STATUS =
  /^(assume-verified|verify|quicken|space(-profile)?|speed(-profile)?|everything(-profile)?)$/;

/** Shape of an appId that can appear as a `Dexopt state:` block header at all. */
const PACKAGE_NAME = /^[A-Za-z0-9._]+$/;

interface DexoptEntry {
  abi: string;
  status: string;
  reason?: string;
  primaryAbi: boolean;
}

/**
 * The entries that decide whether the app is compiled: every entry whose ABI is marked
 * `[primary-abi]` on at least one path, when the dump marks any (a secondary-ABI status describes
 * code the process will not run), else all.
 *
 * Matched by ABI name rather than requiring the marker on every line: a split's status line for the
 * same ABI as a marked base entry is still code the process runs, whether or not that particular
 * line repeats the marker.
 */
function decisiveEntries(entries: DexoptEntry[]): DexoptEntry[] {
  const primaryAbis = new Set(entries.filter((e) => e.primaryAbi).map((e) => e.abi));
  if (primaryAbis.size === 0) return entries;
  return entries.filter((e) => primaryAbis.has(e.abi));
}

/** Whether ART has artifacts for every decisive code path. False for an empty entry list. */
function isCompiled(entries: DexoptEntry[]): boolean {
  const decisive = decisiveEntries(entries);
  return decisive.length > 0 && decisive.every((e) => COMPILED_STATUS.test(e.status));
}

/** `x86_64: status=verify reason=install` style summary of the decisive entries, for a message. */
function summarize(entries: DexoptEntry[]): string {
  const shown = decisiveEntries(entries);
  const lines = (shown.length > 0 ? shown : entries).map(
    (e) => `${e.abi}: status=${e.status}` + (e.reason ? ` reason=${e.reason}` : ""),
  );
  return [...new Set(lines)].join("; ") || "no dexopt status lines";
}

/**
 * `appId`'s `Dexopt state:` entries in a `dumpsys package` dump, or undefined when the dump has no
 * such entry — the package is not installed, or this is not a `dumpsys package` dump at all.
 *
 * Scoped to the `[<appId>]` block rather than matched against the whole text: an argument
 * `dumpsys package` does not recognise makes it dump every package, and the `Dexopt state:` section
 * then lists them all — so a global match would let any other package on the device answer for the
 * target. The block ends at the next `[<other.package>]` header or the next unindented section
 * header (`Compiler stats:`).
 */
export function parseDexoptState(dump: string, appId: string): DexoptEntry[] | undefined {
  let inSection = false;
  let inTarget = false;
  let inSecondaryDex = false;
  let found = false;
  const entries: DexoptEntry[] = [];

  for (const line of dump.split("\n")) {
    if (!inSection) {
      if (DEXOPT_SECTION_HEADER.test(line)) inSection = true;
      continue;
    }
    // An unindented, non-blank line is the next top-level section: the dexopt section is over.
    if (line.trim() !== "" && !/^\s/.test(line)) break;

    const header = DEXOPT_PACKAGE_HEADER.exec(line);
    if (header) {
      inTarget = header[1] === appId;
      if (inTarget) found = true;
      inSecondaryDex = false;
      continue;
    }
    if (!inTarget) continue;
    if (DEXOPT_PATH_LINE.test(line)) {
      // A new primary code path ends any secondary-dex list before it. Android 8.1 through 13
      // print `known secondary dex files:` inside EVERY `path:` block (it is the package-level
      // map, repeated), so a flag that only cleared at the next package header would swallow the
      // status of every split after the base — an uncompiled split would read as absent and the
      // repair would be skipped.
      inSecondaryDex = false;
      continue;
    }
    if (SECONDARY_DEX_HEADER.test(line)) {
      // A secondary (multidex) file the app loads at runtime, not the base APK or a split.
      // `pm compile` without `--secondary-dex` never touches it, so its status is not evidence
      // about the app's own artifacts — an app that always ships one uncompiled would otherwise
      // read as permanently broken with no compile that could ever fix it.
      inSecondaryDex = true;
      continue;
    }
    if (inSecondaryDex) continue;

    const status = DEXOPT_STATUS_LINE.exec(line);
    if (!status) continue;
    const reason = DEXOPT_REASON.exec(line);
    entries.push({
      abi: status[1]!,
      status: status[2]!,
      reason: reason ? reason[1]! : undefined,
      primaryAbi: line.includes("[primary-abi]"),
    });
  }
  return found ? entries : undefined;
}

interface EnsureAppCompiledInput {
  appId?: string;
  compilerFilter?: string;
  force?: boolean;
  checkOnly?: boolean;
}

export const android_ensureAppCompiled = trailblaze.tool<EnsureAppCompiledInput>(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (input, ctx) => {
    const appId = input.appId?.trim() || ctx.target?.resolveAppId();
    if (!appId) {
      throw new Error(
        "android_ensureAppCompiled needs an `appId`: none was given and the session has no " +
          "resolved target app.",
      );
    }
    if (!PACKAGE_NAME.test(appId)) {
      throw new Error(`android_ensureAppCompiled: '${appId}' is not a valid Android package name.`);
    }
    const compilerFilter = input.compilerFilter ?? DEFAULT_COMPILER_FILTER;
    if (!KNOWN_COMPILER_FILTERS.includes(compilerFilter)) {
      throw new Error(
        `android_ensureAppCompiled: unknown compilerFilter '${compilerFilter}'. Known filters: ` +
          KNOWN_COMPILER_FILTERS.join(", "),
      );
    }

    const readState = async () =>
      parseDexoptState(
        await ctx.tools.android_adbShell({ command: ["dumpsys", "package", appId] }),
        appId,
      );

    const before = await readState();
    if (!before) {
      throw new Error(
        `android_ensureAppCompiled could not read the dexopt state of '${appId}' from ` +
          "`dumpsys package` — is the app installed on this device?",
      );
    }
    // An unreadable state wins over everything, `force` included: the block was there but held no
    // status line in a shape this parser reads, so "uncompiled" was never established. Android 8.x
    // is the known case — 8.1 prints `arm64: kOatUpToDate` under each `path:`, 8.0 an
    // `Instruction Set:` header with `path:`/`status:` pairs. Compiling on that would run on every
    // session and the re-read after it would be just as unreadable, so it could only ever report
    // failure. Kept in step with `EnsureAppCompiled.decide`.
    if (before.length === 0) {
      return (
        `android_ensureAppCompiled: '${appId}' is installed, but this device reports no dexopt ` +
        "status in a shape this tool reads (Android 8.x prints an older one), so whether it has " +
        "compiled artifacts is unknown. Nothing was compiled."
      );
    }
    const compiledBefore = isCompiled(before);

    // checkOnly wins over the rest: a caller that asked only to look never compiles.
    if (input.checkOnly) {
      return compiledBefore
        ? `'${appId}' has compiled artifacts (${summarize(before)}). checkOnly=true, nothing was compiled.`
        : `'${appId}' has NO compiled artifacts (${summarize(before)}); checkOnly=true, so nothing ` +
            "was compiled. Every cold start verifies the APK until it is.";
    }
    if (compiledBefore && !input.force) {
      return `'${appId}' already has compiled artifacts (${summarize(before)}); nothing to do.`;
    }

    // `-f` because a compile is only reached once the artifacts are known to be missing or the
    // caller forced it — and without it `pm compile` would see existing artifacts and no-op, which
    // is what would make `force` meaningless.
    const startedAtMs = Date.now();
    const compileOutput = await ctx.tools.android_adbShell({
      command: ["pm", "compile", "-m", compilerFilter, "-f", appId],
    });
    const elapsedMs = Date.now() - startedAtMs;

    // The state is read again from the device rather than trusting `pm compile`'s exit line: the
    // only broken state observed in the wild (`[location is error]`) is one ART may consider up to
    // date.
    const after = await readState();
    // A forced recompile of a package that was already compiled is the one case where `after`
    // alone cannot tell success from a no-op or a refusal: both leave artifacts in place, and a
    // same-filter recompile leaves the device reporting the exact same status either way. AOSP's
    // `pm compile` prints exactly `Success` on success and something else otherwise.
    const forcedNoOp = Boolean(input.force) && compiledBefore && !/success/i.test(compileOutput);

    if (!after || !isCompiled(after) || forcedNoOp) {
      throw new Error(
        `android_ensureAppCompiled: \`pm compile -m ${compilerFilter} -f ${appId}\` ran ` +
          `(${elapsedMs}ms) but '${appId}' still has no compiled artifacts. ` +
          `Before: ${summarize(before)}. After: ${after ? summarize(after) : "unreadable"}. ` +
          `pm compile said: ${compileOutput.trim().slice(0, 200) || "(nothing)"}`,
      );
    }
    return (
      `Compiled '${appId}' with filter ${compilerFilter} in ${elapsedMs}ms. ` +
      `Before: ${summarize(before)}. After: ${summarize(after)}.`
    );
  },
);
