// Vendored bindings for the small RESIDUAL set of Trailblaze framework tools that the generated
// recordable-tool surface can't own. Augments [TrailblazeToolMap] so authors get autocomplete +
// type-checking the moment they import from `@trailblaze/scripting`. No `npm install` on the
// consumer side; the SDK ships these.
//
// ## Split with the generated recordable surface
//
// Every RECORDABLE framework tool (class-backed) is generated into each trailmap's /
// bundled target's `tools/trailblaze-client.d.ts` by `PerTrailmapClientDtsEmitter`, derived from the
// `@TrailblazeToolClass(isRecordable = true)` source of truth (see the devlog
// `2026-07-01-trail-recording-type-validation.md`). That generated surface is what the trail-recording
// type-validator checks against — sourcing it from `isRecordable` means it can't drift or silently
// miss a recordable tool (the failure mode this file used to have: a hand-added `tapOn` /
// `assertVisibleBySelector` while `assertNotVisibleBySelector` was forgotten).
//
// This hand file keeps ONLY the tools the generator deliberately does NOT emit, so the two never
// declare the same `TrailblazeToolMap` key (a duplicate key with a different shape is a TS2717 error
// that would break the whole validation compilation):
//
//  - **Non-recordable author utilities** (`isRecordable = false`, never in a recording): the
//    selector query tools `findSelectorMatches` / `waitUntilNotVisible` (rich `MatchDescriptor[][]`
//    / `boolean` results), `readScreenImage` (pixels for `ctx.screen.image`), plus the host/composition primitives `exec`, `android_writeBytesToFile`,
//    `android_adbShell`, `android_sendBroadcast`, `android_writeFileToDownloads`,
//    `android_reconnectSession`.
//  - **Recordable tools the generator can't fully model** — `mobile_listInstalledApps` /
//    `mobile_listInstalledAppsDetailed` (structured result types). These are listed in
//    `HandCuratedRecordableTools.NAMES` so the generator skips them;
//    `BuiltInToolsBindingDriftTest` asserts the two sets stay disjoint.
//
// Result types: `result` is `string` unless the tool declares `@TrailblazeToolClass(resultType = ...)`
// and populates `structuredContent`. `mobile_listInstalledApps[Detailed]` do — their result types
// here are *generated* (imported below from `built-in-tool-results.js`, emitted by
// `BuiltInToolResultTsBindings` in `trailblaze-common`), not hand-written, so this file and any
// generated surface render the identical type from that one annotation.
//
// Pulled in by `index.ts` via a bare side-effect import. The file has no runtime exports — only
// declaration-merging augmentations on the `@trailblaze/scripting` module — but it IS a module (the
// `export {}` below) so TypeScript treats the `declare module` block as augmentation rather than
// re-declaration.
//
// When adding/removing an entry here or changing its arg shape, mirror the Kotlin change (the
// `@Serializable` tool class is the runtime contract these TS types shadow). Each entry links its
// source-of-truth Kotlin file.

// Selector-grammar types — `TrailblazeNodeSelector` and `MatchDescriptor` — are generated from the
// Kotlin sealed-class hierarchy by `:trailblaze-models:generateSelectorsTs` and re-exported from
// `index.ts`. The `findSelectorMatches` / `waitUntilNotVisible` entries below reference them; this file
// imports the type names locally for the declaration-merging block.
import type {
  Bounds,
  MatchDescriptor,
  TrailblazeNodeSelector,
} from "./generated/selectors.js";
// Generated from each tool's declared `resultType` by `BuiltInToolResultTsBindings`
// (`:trailblaze-common:generateDtoTs`) — see `ListInstalledAppsTrailblazeTool.kt`.
import type {
  InstalledApp,
  ListInstalledAppsDetailedResult,
  IosKeychainResult,
  IosSimctlResult,
  ListInstalledAppsResult,
  ReadScreenImageResult,
} from "./generated/built-in-tool-results.js";

// Pins the imported shapes so `tsc` catches drift. Needed because `bun:test` never type-checks
// and `*.test.ts` is excluded from this SDK's tsconfig, so a test-file import wouldn't catch it.
const _installedAppShape: InstalledApp = { appId: "com.example.app", isSystemApp: false };
const _listResultShape: ListInstalledAppsResult = { appIds: [_installedAppShape.appId] };
const _detailedResultShape: ListInstalledAppsDetailedResult = { apps: [_installedAppShape] };
const _iosSimctlResultShape: IosSimctlResult = { exitCode: 0, stdout: "com.example.app: 4242\n", stderr: "", timedOut: false };
const _iosKeychainResultShape: IosKeychainResult = {
  action: "LIST",
  found: true,
  memoryKey: null,
  items: [{ service: "svc", account: "acct", accessGroup: "TEAM.com.example.app", itemClass: "genericPassword" }],
};

/** Arguments of `ios_simctl`. Source: `IosSimctlTrailblazeTool.kt`. */
export interface IosSimctlArgs {
  /**
   * `simctl` arguments without `xcrun simctl`, checked against the allowlist before anything runs:
   * `terminate`, `launch`, `openurl`, `privacy`, `get_app_container`, `keychain <device> reset`,
   * `location <device> list|clear|set|run|start`, `ui <device> appearance|content_size|increase_contrast`,
   * `status_bar <device> list|clear|override`, `push <device> <appId>` (payload in {@link stdin}),
   * `pbcopy` (text in {@link stdin}), `pbpaste`, `listapps`, `appinfo <device> <appId>`, and
   * `spawn <device>` with `defaults`, `notifyutil -p|-s` or `log show` (deprecated: read app
   * signals with `ctx.events` instead).
   * `booted` or an omitted device is the session's simulator; other UDIDs are refused.
   * Write an app's preferences while it is terminated: terminate, write, then launch.
   */
  argv: string[];
  /** Not supported yet: any value is refused, until an allowlist is added for a real caller. */
  launchEnvironment?: { name: string; value: string }[];
  /**
   * Input for `push` (a JSON object with an `aps` key, at most 4096 bytes) or `pbcopy` (at most
   * 64KB). Refused for every other subcommand. Never logged.
   */
  stdin?: string;
  /** Kill the command after this many seconds. Default 10 (30 for `log show`), at most 300. */
  timeoutSeconds?: number;
}

/** Arguments of `ios_keychain`. Source: `IosKeychainTrailblazeTool.kt`. */
export interface IosKeychainArgs {
  /**
   * `set` stores (updating any item at service/account in place), `get` reads one item's value into
   * sensitive memory under {@link memoryKey}, `delete` removes, `list` names every item in the group.
   */
  action: "set" | "get" | "delete" | "list";
  /** Bundle id of the installed app whose keychain group to use. */
  appId: string;
  /** `kSecAttrService`. Required for `set`, `get` and `delete`; a filter for `list`. */
  service?: string;
  /** `kSecAttrAccount`. Required for `set` and `get`; a filter for `delete` and `list`. */
  account?: string;
  /** `set` only: the password. Never logged, never on argv, never returned. */
  value?: string;
  /**
   * `get` only, and required there: the memory key to store the value in, marked sensitive. Later
   * steps use it as `{{key}}`; the value itself is never returned.
   */
  memoryKey?: string;
  /** `set` only: `kSecAttrAccessible`, in any case. Default `WHEN_UNLOCKED`. */
  accessible?:
    | "WHEN_UNLOCKED"
    | "WHEN_UNLOCKED_THIS_DEVICE_ONLY"
    | "AFTER_FIRST_UNLOCK"
    | "AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY"
    | "WHEN_PASSCODE_SET_THIS_DEVICE_ONLY"
    | (string & {});
  /** Defaults to the app's `application-identifier` entitlement. */
  accessGroup?: string;
  /** Per-step timeout. Default 10, at most 300; the first helper build per host gets 180s. */
  timeoutSeconds?: number;
}

declare module "@trailblaze/scripting" {
  interface TrailblazeToolMap {
    // MAINTAINER NOTE (contract): the Kotlin Koog/scripted-tool descriptor — generated by
    // `TrailblazeKoogToolExt` — intentionally OMITS the selector types (`TrailblazeNodeSelector`
    // and the legacy `TrailblazeElementSelector`, via its `excludedParameterTypes`) because
    // reflecting their self-referencing fields (childOf / containsChild / …) overflows the stack
    // during descriptor codegen. For RECORDABLE selector tools the generated recordable surface
    // re-injects the selector arg via `selectorParamsForTs` — but `findSelectorMatches` and
    // `waitUntilNotVisible` are `isRecordable = false` (never in a recording), so they aren't in
    // the generated surface and THIS hand-curated `selectors` / `nodeSelector` typing is their
    // authoritative scripted-author surface. Keep it in sync with the Kotlin tool classes by hand.
    /**
     * Capture the view hierarchy ONCE and resolve one or more selectors against that single
     * capture. Read-only — never mutates the device. The result is index-aligned to `selectors` —
     * `result[i]` is `selectors[i]`'s matches, empty array if it matched nothing, so the two
     * always have the same length.
     *
     * Use a result's length as a visibility / uniqueness gate:
     *
     * ```ts
     * const [matches] = await client.tools.findSelectorMatches({
     *   selectors: [{ androidAccessibility: { textRegex: "Submit" } }],
     * });
     * // matches.length === 0  -> not visible
     * // matches.length === 1  -> unique match, safe to act on
     * // matches.length > 1    -> ambiguous, narrow the selector
     * ```
     *
     * Each match carries enough identity + position info (indexPath, bounds, text,
     * accessibilityId, resourceId) to act on without re-querying.
     *
     * **Ask every question of one screen in ONE call.** A hierarchy capture is the expensive part
     * of a query (multiple seconds on a loaded Android device, where every node is a live
     * accessibility fetch); resolving a selector against a tree already in hand is free. The
     * host-side snapshot cache shares a captured hierarchy between sibling dispatches in one batch,
     * but does NOT deduplicate across scripted calls — each `client.tools.*` callback enters its
     * own cache frame — so four separate calls really do pay four captures. Batching is explicit,
     * not something the cache does for you.
     *
     * ```ts
     * const [wizard, home] = await client.tools.findSelectorMatches({
     *   selectors: [
     *     { androidAccessibility: { textRegex: "Select a mode" } },
     *     { androidAccessibility: { textRegex: "Charge \\$.*" } },
     *   ],
     *   timeoutMs: 30_000,
     * });
     * if (wizard.length > 0) {
     *   // the wizard is up — handle it
     * } else if (home.length > 0) {
     *   // already past it, nothing to do
     * }
     * ```
     *
     * Also more correct than sequential probes, not just faster: every answer comes from the SAME
     * tree, so it describes one instant of one screen. With sequential calls the screen can change
     * between captures, and a caller racing two conditions can see both true or neither depending
     * on which it probed first. Ties within one frame are the caller's to break — check the
     * selector you want to win first, as above.
     *
     * **An empty array is an ANSWER, not a failure.** The tool answers one question — did each
     * selector match, within the timeout — so `result[i].length === 0` is a *measured* absence and
     * is your absent branch. Every way this tool can throw means the question could not be asked
     * (no hierarchy from this driver, a capture too incomplete to read absence out of, an empty
     * `selectors`), never that the answer was no.
     *
     * **A selector may match many elements**, in reading order — sorted by `bounds.top` then
     * `bounds.left`. Detecting ambiguity is yours (`result[i].length > 1` → narrow it). To pick one
     * of several, prefer the selector's own `index`, which selects the Nth match after that sort,
     * over indexing into the array.
     *
     * Source: `FindSelectorMatchesTrailblazeTool.kt`.
     */
    findSelectorMatches: {
      args: {
        /**
         * Selectors to resolve against one capture. Order is the result's contract, so duplicates
         * and reordering are fine. Must be non-empty.
         */
        selectors: TrailblazeNodeSelector[];
        /**
         * Optional wait budget in milliseconds. Omit for a single point-in-time capture. When set,
         * the tool polls the LIVE hierarchy and returns as soon as ANY selector matches, or when
         * the budget elapses (all-empty arrays).
         *
         * "Any" is what makes this a race primitive: one wait that ends the instant whichever
         * screen you're waiting for renders, with the other selectors answered from that same
         * frame. To wait for one specific selector, pass it alone.
         *
         * Omitting this and passing `0` are NOT the same knob. Omitted goes through the host-side
         * snapshot cache and retries a capture that lost nodes; `0` is one immediate live capture
         * with no cache and no retry, so a holey capture has no second look and the call throws
         * rather than answering. Prefer omitting it. Polling in TypeScript over `timeoutMs: 0` is
         * possible but pays a round trip and a fresh cache frame per iteration, and turns that
         * retry-less throw into your loop's normal control flow — let the tool own the wait.
         */
        timeoutMs?: number;
        /**
         * Opt-in early stop for a `timeoutMs` wait that usually comes back empty. When `true`, a
         * wait in which nothing has matched ends once the screen has settled (the same screen for
         * 1.5s and the app idle) instead of running to `timeoutMs`; the result is all-empty, as a
         * timeout would have returned it.
         *
         * Use it for checks for something that usually never appears — an optional prompt, a
         * one-time sheet:
         *
         * ```ts
         * const [prompt] = await client.tools.findSelectorMatches({
         *   selectors: [{ androidAccessibility: { textRegex: ".*[Ss]tarting balance.*" } }],
         *   timeoutMs: 15_000,
         *   stopWhenSettled: true,
         * });
         * ```
         *
         * Leave it off when the thing waited for appears only after a long, silent load: a static
         * loading screen also looks settled. There is no counterpart for waits on something to go
         * away. Ignored without `timeoutMs`; a no-op on drivers with no app-idle signal (only the
         * Android accessibility driver has one today).
         */
        stopWhenSettled?: boolean;
      };
      result: MatchDescriptor[][];
    };

    /**
     * Pixels of one rectangle of the screen, or the whole screen, as base64 RGBA. Prefer
     * `ctx.screen.image({ bounds })`, which calls this and decodes the result into a `ScreenImage`.
     * `bounds` is in the driver's coordinates, the same space as `MatchDescriptor.bounds`.
     *
     * Source: `ReadScreenImageTrailblazeTool.kt` (`readScreenImage`).
     */
    readScreenImage: {
      args: {
        bounds?: Bounds;
        /** Longest side of the returned image, in pixels. Default 512. */
        maxDimension?: number;
      };
      result: ReadScreenImageResult;
    };

    /**
     * Wait (up to `timeoutMs`) for the element matching [nodeSelector] to become NOT visible,
     * returning a NON-THROWING boolean verdict: `true` when the selector is (or became) not
     * visible within the budget, `false` when it is still visible after the wait.
     *
     * The disappearance counterpart to `findSelectorMatches` (which waits for APPEARANCE). Use it for
     * conditional flows that must branch on a screen having gone away — and keep their own custom
     * error messages — rather than throwing like the verification-style `assertNotVisibleWithText`:
     *
     * ```ts
     * const gone = await client.tools.waitUntilNotVisible({
     *   nodeSelector: { androidAccessibility: { textRegex: "Loading" } },
     *   timeoutMs: 60_000,
     * });
     * if (!gone) throw new Error("…still loading after 60s; OAuth pipeline stuck.");
     * ```
     *
     * Distinct from negating `findSelectorMatches`: a positive probe returns true the moment the text is
     * *currently* on screen, so negating it doesn't wait for the element to actually disappear.
     * This tool waits for the live tree to lose the match. Routes through the driver-native
     * not-visible wait on the accessibility driver, Maestro `extendedWaitUntil` otherwise.
     *
     * Source: `WaitUntilNotVisibleTrailblazeTool.kt` (`waitUntilNotVisible`).
     */
    waitUntilNotVisible: {
      args: {
        /** Selector whose disappearance is awaited against the live view hierarchy. */
        nodeSelector: TrailblazeNodeSelector;
        /**
         * Optional wait budget in milliseconds for the element to disappear. Omit to let each
         * driver apply its own idle/wait policy (per-driver default).
         */
        timeoutMs?: number;
      };
      result: boolean;
    };

    /**
     * Run a process on the **host** JVM via argv (no shell). Host-only (`requiresHost = true`) — for
     * **tool authors**, not the LLM (`surfaceToLlm = false`). The escape hatch for composing host
     * CLIs from a scripted tool: `adb` host subcommands, build steps, etc. For the iOS simulator use
     * `ios_simctl`, which covers the simctl calls tools make without a host process of your own.
     *
     * Each `argv` element is passed verbatim to `ProcessBuilder` — no shell metacharacter
     * interpretation, so an interpolated value can't be re-read as `;`/`&&`/quotes. Opt into a shell
     * explicitly with `argv: ["sh","-c","<cmd>"]`. Returns combined stdout/stderr; a non-zero exit
     * (≠ `expectedExitCode`) surfaces as an error.
     *
     * Source: `ExecTrailblazeTool.kt` (`exec`).
     */
    exec: {
      args: {
        /** Process argv. Element 0 is the executable; the rest are literal arguments (no shell parsing). */
        argv: string[];
        /**
         * Extra variables inherited by the child process. Values are redacted in persisted logs;
         * use this for opaque credentials or request bodies rather than putting them in argv.
         */
        environment?: Record<string, string>;
        /** Working directory for the subprocess. Defaults to the daemon's current working directory. */
        workingDir?: string;
        /** Exit code treated as success. Default 0. */
        expectedExitCode?: number;
        /** Optional per-line regex; only matching lines are kept in the success output (ignored on failure). */
        outputFilterRegex?: string;
        /** Wall-clock timeout in seconds before the subprocess is killed. Default: wait indefinitely. */
        timeoutSeconds?: number;
      };
      result: string;
    };

    /**
     * Runs `xcrun simctl` on the session's iOS simulator: the iOS counterpart of `android_adbShell`.
     * Use it instead of `exec` of `xcrun simctl`. `argv` is plain simctl arguments, checked against an
     * allowlist (see {@link IosSimctlArgs.argv}); anything else fails with the list of what's allowed.
     * A `defaults` domain that is a bundle id is rewritten to that app's container plist, the only
     * way to reach a sandboxed app's preferences.
     *
     * ```ts
     * await ctx.tools.ios_simctl({ argv: ["terminate", "booted", appId] });
     * await ctx.tools.ios_simctl({ argv: ["spawn", "booted", "defaults", "write", appId, "Seen", "-bool", "YES"] });
     * const { exitCode, stdout } = await ctx.tools.ios_simctl({ argv: ["spawn", "booted", "defaults", "read", "-g", "AppleLanguages"] });
     * ```
     *
     * Returns `{ exitCode, stdout, stderr, timedOut }`. A refused argv throws; a command that exits
     * non-zero or times out does NOT, so check `exitCode`, or call `simctlOrThrow(ctx.tools, argv)` (from `@trailblaze/scripting`) to throw on
     * any failure. iOS simulators only.
     *
     * Source: `IosSimctlTrailblazeTool.kt` (`ios_simctl`).
     */
    ios_simctl: {
      args: IosSimctlArgs;
      result: IosSimctlResult;
    };

    /**
     * The keychain primitive: sets, gets, deletes or lists passwords in an installed app's keychain
     * group on the session's simulator, the one thing simctl can't do. `set` updates any existing
     * item in place and reads the value back.
     *
     * ```ts
     * await ctx.tools.ios_keychain({ action: "set", appId, service, account: "SessionToken", value: token, accessible: "afterFirstUnlockThisDeviceOnly" });
     * const { found } = await ctx.tools.ios_keychain({ action: "get", appId, service, account: "SessionToken", memoryKey: "sessionToken" });
     * const { items } = await ctx.tools.ios_keychain({ action: "list", appId, service });
     * await ctx.tools.ios_keychain({ action: "delete", appId, service });
     * ```
     *
     * Returns `{ action, found, memoryKey?, items? }`: `get` sets `memoryKey` when it stored the
     * value, `list` sets `items` (names only). A value is never returned and never logged; `get`
     * puts it in sensitive memory for `{{memoryKey}}`. iOS simulators only.
     *
     * Source: `IosKeychainTrailblazeTool.kt` (`ios_keychain`).
     */
    ios_keychain: {
      args: IosKeychainArgs;
      result: IosKeychainResult;
    };

    /**
     * Low-level framework primitive: write raw bytes (supplied as a base64 string) to an absolute
     * path on the Android device, creating parent dirs and overwriting any existing file. Use it
     * to seed any file — text or binary — that the device shell can't move reliably (a file body
     * hangs `adb shell` on the host, and a base64 argv hits ARG_MAX on-device; this uses
     * `adb push` / a direct file write instead).
     *
     * Filesystem only: it does NOT register MediaStore or set a MIME type (base64 carries no MIME,
     * and the filesystem has no MIME concept). To write UTF-8 text, base64-encode the string and
     * pass it here. If a consumer must find the file via a MediaStore query, register it separately
     * with `android_adbShell` (`cmd media_scanner scan <path>` / `content insert --bind mime_type:…`).
     * That layering — and keeping perms/owner/MIME on `adbShell` rather than as params here — is the
     * framework-provides-primitives decision (devlog 2026-06-22). Android-only.
     *
     * Source: `AndroidWriteBytesToFileTrailblazeTool.kt` (`android_writeBytesToFile`).
     */
    android_writeBytesToFile: {
      args: {
        /** Absolute destination path (must start with `/`), e.g. `/storage/emulated/0/Download/setup.json`. */
        devicePath: string;
        /** File content as a base64-encoded byte string; decoded to raw bytes before writing. */
        base64Content: string;
        /** Optional debuggable-app id to write as, via `run-as` — the only way to reach that app's private `/data/data/<appId>/` dir. Payload rides in the command line, so it is capped at 64 KiB. */
        runAs?: string;
      };
      result: string;
    };

    /**
     * Run an `adb shell` command, argv-shaped (injection-safe). Returns the command's stdout on a
     * zero exit; throws on a non-zero exit or I/O failure. Composition primitive for Android device
     * control from scripted tools. Android-only.
     *
     * Source: `AdbShellTrailblazeTool.kt` (`android_adbShell`).
     */
    android_adbShell: {
      args: {
        /** Argv-shaped command, e.g. `["am", "force-stop", "com.example"]` (NOT one shell string). */
        command: string[];
        /** Optional debuggable-app id to run the command under via `run-as`. */
        runAs?: string;
        /**
         * Values in this call that must never appear in a log. Purely a logging declaration —
         * put the credential in `command` where it belongs, then list the same value here. Every
         * occurrence is replaced with `<redacted>` in the persisted log payload, this tool's
         * output and error text, and the adb transport's own command log; everything around it
         * stays readable.
         */
        secrets?: string[];
      };
      result: string;
    };

    /**
     * Send a broadcast intent to the connected Android device. `action` and each extra `value`
     * support `{{token}}` / `${token}` references, resolved host-side against the full agent memory
     * at execution time — so a credential can be forwarded as an opaque token without the plaintext
     * ever entering the JS heap. Non-LLM, non-recordable composition primitive. Android-only.
     *
     * Source: `AndroidSendBroadcastTrailblazeTool.kt` (`android_sendBroadcast`).
     */
    android_sendBroadcast: {
      args: {
        /** Intent action, e.g. `com.example.SIGNIN`. */
        action: string;
        /** Target component package (usually the app id). */
        componentPackage: string;
        /** Target component (receiver) class. */
        componentClass: string;
        /** Intent extras. `value` is the literal `am broadcast` text; `type` defaults to `string`. */
        extras?: Array<{ key: string; value: string; type?: string }>;
      };
      result: string;
    };

    /**
     * Reconnect the host session to an Android device that has rebooted, so the trail's later
     * steps keep running against it. Relaunches the on-device runner and resumes the recording's
     * logcat/video on the new boot.
     *
     * The caller owns the reboot: trigger or detect it (via `exec adb`, since the on-device tools
     * are down until the device is back), wait for `sys.boot_completed=1`, then call this once.
     * Host-only (`trailblaze run`); Android-only.
     *
     * Source: `AndroidReconnectSessionTrailblazeTool.kt` (`android_reconnectSession`).
     */
    android_reconnectSession: {
      args: Record<string, never>;
      result: string;
    };

    /**
     * Write a UTF-8 text file into the device's public Downloads directory. On-device this writes via
     * `MediaStore.Downloads` (so a consuming app's MediaStore query finds it under scoped storage);
     * from the host it `adb push`es to `/storage/emulated/0/Download`. For an arbitrary raw path with
     * no MediaStore registration, use `android_writeBytesToFile`. Android-only.
     *
     * Source: `AndroidWriteFileToDownloadsTrailblazeTool.kt` (`android_writeFileToDownloads`).
     */
    android_writeFileToDownloads: {
      args: {
        /** File name to create in Downloads, e.g. `setup.json`. */
        fileName: string;
        /** UTF-8 text content. */
        content: string;
      };
      result: string;
    };

    /**
     * Write an image into the device's public Pictures directory and register it as a MediaStore
     * Images row, so a gallery / photo picker finds it. On-device this does a `MediaStore.Images`
     * `ContentResolver.insert` (scoped-storage-safe, auto-registered); from the host it `adb push`es
     * to `/sdcard/Pictures` then triggers a MediaStore `scan_file`. Bytes are passed base64-encoded.
     * Unlike `android_writeBytesToFile` (filesystem only), this produces an Images row in either
     * execution mode. Android-only.
     *
     * Source: `AndroidWriteImageToMediaStoreTrailblazeTool.kt` (`android_writeImageToMediaStore`).
     */
    android_writeImageToMediaStore: {
      args: {
        /** File name to create under Pictures, e.g. `photo.png`. */
        fileName: string;
        /** Image content as a base64-encoded byte string; decoded to raw bytes before writing. */
        base64Content: string;
        /** Image MIME type. Defaults to `image/png`. */
        mimeType?: string;
      };
      result: string;
    };

    /**
     * List the app ids installed on the device. Works on Android (`pm list packages`) and iOS
     * (`xcrun simctl listapps`). Returns the typed result directly (`{ appIds: string[] }`, sorted) —
     * no `JSON.parse` needed. The Kotlin handler populates `structuredContent` with this shape; the
     * SDK proxy unwraps it automatically.
     *
     * Recordable, but hand-curated here (listed in `HandCuratedRecordableTools.NAMES`)
     * so the structured `{ appIds }` result type is preserved — the generated recordable surface would
     * flatten it to `result: string`.
     *
     * The lean, common-case utility. For each app's metadata (display name, system/user, version,
     * build number, install path) use `mobile_listInstalledAppsDetailed` instead.
     *
     * Source: `ListInstalledAppsTrailblazeTool.kt` (`mobile_listInstalledApps`).
     */
    mobile_listInstalledApps: {
      args: Record<string, never>;
      result: ListInstalledAppsResult;
    };

    /**
     * The deep counterpart to `mobile_listInstalledApps`: lists installed apps with full per-app
     * metadata. Returns the typed result directly (sorted by `appId`) — no `JSON.parse` needed. The
     * Kotlin handler populates `structuredContent` with this shape; the SDK proxy unwraps it
     * automatically.
     *
     * Recordable, but hand-curated here (see `mobile_listInstalledApps`) to preserve the structured
     * result type.
     *
     * Pass `{ includeSystemApps: false }` to return only user-installed apps (drops OS apps). On the
     * Android host/adb path only `label` is absent (it needs on-device resource resolution);
     * `isSystemApp` / `version` / `buildNumber` / `installPath` are populated there too via a single
     * `dumpsys package packages` read, and `label` is populated on the Android on-device driver and on iOS.
     *
     * Not surfaced to the LLM — reachable from scripted tools via `callTool`. Use
     * `mobile_listInstalledApps` when you only need ids.
     *
     * Source: `ListInstalledAppsDetailedTrailblazeTool.kt` (`mobile_listInstalledAppsDetailed`).
     */
    mobile_listInstalledAppsDetailed: {
      args: { includeSystemApps?: boolean };
      result: ListInstalledAppsDetailedResult;
    };
  }
}

// Marks this file as a module (rather than a global script) so the `declare module`
// block above is treated as an augmentation of `@trailblaze/scripting` instead of a
// redeclaration that overrides the package's actual exports.
export {};
