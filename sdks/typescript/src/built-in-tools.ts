// Vendored bindings for Trailblaze framework built-in tools — augments [TrailblazeToolMap]
// so authors get autocomplete on tool names and type-checking on args for the framework
// toolset (mobile device control, basic verification) the moment they import anything from
// `@trailblaze/scripting`. No `npm install` step on the consumer side; the SDK ships these.
//
// Pulled in by `index.ts` via a bare side-effect import. The file has no runtime exports —
// only declaration-merging augmentations on the `@trailblaze/scripting` module — but it IS
// a module (the `export {}` below) so TypeScript treats the `declare module` block as
// augmentation rather than re-declaration.
//
// ## Drift policy
//
// This file is HAND-CURATED today. The upstream truth is the Kotlin `@TrailblazeToolClass`
// data classes under
//   `trailblaze-common/src/jvmAndAndroid/kotlin/.../toolcalls/commands/`
// plus their `@Serializable` shapes. Auto-generation from the Kotlin reflection path
// (`KClass.toKoogToolDescriptor()`) is tracked as a follow-up — when it lands, this file
// becomes generated output and the curated section below is deleted.
//
// Until then: when adding/removing a built-in tool or changing its arg shape, update this
// file alongside the Kotlin change. The JVM-side schema serves as the runtime contract;
// these TS types are an authoring convenience that mirror it.
//
// Coverage is intentionally a subset — common interaction tools that show up in tutorial
// flows and demos. Tools not listed here are unreachable from a typed `.ts` author: the
// public `TrailblazeClient` type omits `callTool`, so the only call path is
// `client.tools.<name>(args)`, which requires a `TrailblazeToolMap` entry. Additional
// framework tools (`android_adbShell`, `web_navigate`, etc.) land here as they're added to
// the curated subset; trailmap-local scripted tools land in their trailmap's generated
// `tools/trailblaze-client.d.ts` instead.
//
// ## Entry shape: `{ args; result }`
//
// Each entry is `{ args: <argsShape>; result: <resultShape> }`. The `args` half is the
// JSON-Schema-shaped tool input (the same shape the Kotlin dispatcher validates against).
// The `result` half is the static-typing-only return type — uniformly `result: string`
// today, matching what `WorkspaceClientDtsGenerator` emits for every Kotlin/scripted tool
// per trailmap. The two surfaces declaration-merge into the same `TrailblazeToolMap`
// interface, so they MUST declare identical `result` types for any tool name that appears
// in both files (`tapOnPoint`, `inputText`, `hideKeyboard`, etc.) — TypeScript errors on a
// mismatch.
//
// The wire-side `structured_content` field and the SDK's `client.tools.<name>(args)` unwrap
// are both in place — a producer that populates structured content flows it through the
// proxy as the typed `result`. What's still uniform here is the `result: string` declaration
// on every entry: `WorkspaceClientDtsGenerator` doesn't yet read per-tool typed-result
// schemas (the analyzer-driven follow-up will), so every built-in advertises `result: string`
// regardless of whether the underlying handler returns text or a structured payload. When
// the descriptor side ships, the entries below can declare richer `result` types and
// consumers will see the unwrapped typed value automatically. See the kdoc on
// `TrailblazeToolMethods` in `client.ts` and the matching note in `ToolMapEntryRenderer.kt`.
//
// Source-of-truth files for each entry below are linked next to the type. Changes to those
// Kotlin files MUST be reflected here.

// Selector-grammar types — `TrailblazeNodeSelector`, the six `DriverNodeMatch*` interfaces,
// `Bounds`, and `MatchDescriptor` — are generated from the Kotlin sealed-class hierarchy
// by `:trailblaze-models:generateSelectorsTs` and re-exported from `index.ts`. The shapes
// the `findMatches` tool entry below references resolve through that re-export; this file
// just imports the type names locally for the declaration-merging block at the bottom.
import type {
  MatchDescriptor,
  TrailblazeNodeSelector,
} from "./generated/selectors.js";

declare module "@trailblaze/scripting" {
  interface TrailblazeToolMap {
    /**
     * Tap or long-press at absolute device coordinates. The runtime upgrades the *recorded*
     * step to a selector-based `tapOn` when the coordinates resolve to a unique element —
     * the live tap still fires at the raw `(x, y)`.
     *
     * Source: `TapOnPointTrailblazeTool.kt`.
     */
    tapOnPoint: {
      args: {
        /** The center X coordinate for the clickable element. */
        x: number;
        /** The center Y coordinate for the clickable element. */
        y: number;
        /** Default `false` (standard tap). Pass `true` for a long press. */
        longPress?: boolean;
        /** Optional rationale logged alongside the tool call. */
        reasoning?: string;
      };
      result: string;
    };

    /**
     * Tap on an element by visible text (substring match). DEPRECATED upstream in favor of
     * `tapOn`/`TapOnByElementSelector` — included here because tutorials still reference it.
     *
     * Source: `TapOnElementWithTextTrailblazeTool.kt`.
     */
    tapOnElementWithText: {
      args: {
        /** Required. Text contained in the target element. */
        text: string;
        /** 0-based index of the view to select among matches. */
        index?: number;
        /** Regex for selecting by id when multiple elements share the text. */
        id?: string;
        enabled?: boolean;
        selected?: boolean;
      };
      result: string;
    };

    /**
     * Tap an Android accessibility node identified by its content description (plus optional
     * className and resourceId tiebreakers). Use for canvas widgets whose interactive regions
     * are virtual views of an `ExploreByTouchHelper` — PIN pads, drawing-app palettes,
     * custom map markers, etc. — where the buttons have a `contentDescription` but no `text`,
     * so `tapOnElementWithText` can't reach them.
     *
     * Routes through the same selector-resolved dispatch path the LLM `tap` tool uses, so
     * the per-tap `ACTION_CLICK` routing (see `AccessibilityDeviceManager.kt`) applies.
     *
     * Source: `TapOnAccessibilityNodeTrailblazeTool.kt`.
     */
    tapOnAccessibilityNode: {
      args: {
        /** Required. Regex matched against the node's content description. */
        contentDescriptionRegex: string;
        /** Optional regex matched against the node's className. */
        classNameRegex?: string;
        /** Optional regex matched against the node's resourceId. */
        resourceIdRegex?: string;
        /** Set to true for a long press instead of a tap. */
        longPress?: boolean;
      };
      result: string;
    };

    /**
     * Type characters into the currently-focused text field. The runtime auto-hides the
     * keyboard after typing on iOS so a follow-up screenshot isn't obscured.
     *
     * Source: `InputTextTrailblazeTool.kt`.
     */
    inputText: {
      args: {
        /** Required. Text to type into the focused field. */
        text: string;
        /** Optional rationale logged alongside the tool call. */
        reasoning?: string;
      };
      result: string;
    };

    /**
     * Dismiss the on-screen keyboard. No args (singleton tool object on the Kotlin side).
     *
     * Source: `HideKeyboardTrailblazeTool.kt`.
     */
    hideKeyboard: {
      args: Record<string, never>;
      result: string;
    };

    /**
     * Press a hardware/system key.
     *
     * Source: `PressKeyTrailblazeTool.kt`.
     */
    pressKey: {
      args: {
        /** Required. One of the supported `PressKeyCode` values. */
        keyCode: "BACK" | "ENTER" | "HOME";
      };
      result: string;
    };

    /**
     * Swipe in a cardinal direction. Direction is the *finger* direction — to scroll the
     * page DOWN (see content below), the finger swipes UP.
     *
     * Source: `SwipeTrailblazeTool.kt`.
     */
    swipe: {
      args: {
        /** Default `DOWN`. Direction of the finger gesture, not the scroll direction. */
        direction?: "UP" | "DOWN" | "LEFT" | "RIGHT";
        /** Element text to anchor the swipe on. Omit to swipe at screen center. */
        swipeOnElementText?: string;
        /** Optional rationale logged alongside the tool call. */
        reasoning?: string;
      };
      result: string;
    };

    /**
     * Launch an app by id, with control over whether state is cleared and whether a running
     * instance is forcibly stopped first.
     *
     * Source: `LaunchAppTrailblazeTool.kt`.
     */
    launchApp: {
      args: {
        /** Required. Package id (Android) or bundle id (iOS). */
        appId: string;
        /**
         * Default `REINSTALL` (clean state). `RESUME` picks up an in-memory app; `FORCE_RESTART`
         * stops then relaunches without clearing state. The runtime silently upgrades
         * `REINSTALL` → `FORCE_RESTART` for iOS system apps (`com.apple.*`).
         */
        launchMode?: "REINSTALL" | "RESUME" | "FORCE_RESTART";
        reasoning?: string;
      };
      result: string;
    };

    /**
     * Resolve a [TrailblazeNodeSelector] against the current view hierarchy and return
     * every match as a [MatchDescriptor] list. Read-only — never mutates the device.
     *
     * Use the result length as a visibility / uniqueness gate:
     *
     * ```ts
     * const matches = await client.tools.findMatches({
     *   selector: { androidAccessibility: { textRegex: "Submit" } },
     * });
     * // matches.length === 0  -> not visible
     * // matches.length === 1  -> unique match, safe to act on
     * // matches.length > 1    -> ambiguous, narrow the selector
     * ```
     *
     * Each match carries enough identity + position info (indexPath, bounds, text,
     * accessibilityId, resourceId) to act on without re-querying.
     *
     * Snapshot reuse: calling `findMatches` multiple times within one tool invocation
     * shares the captured view hierarchy via the host-side snapshot cache — the
     * multi-second hierarchy fetch is paid at most once per invocation. An action
     * tool dispatched in the same batch (tap, swipe, inputText, …) invalidates the
     * cache so a follow-up `findMatches` reads the post-action tree.
     *
     * Source: `FindMatchesTrailblazeTool.kt`.
     */
    findMatches: {
      args: {
        /** Selector to match against the current view hierarchy. */
        selector: TrailblazeNodeSelector;
        /**
         * Optional wait budget in milliseconds. Omit (the default) for a single point-in-time
         * snapshot. When set, the tool polls the LIVE hierarchy until at least one match appears
         * or the budget elapses, then returns whatever matched (an empty array if nothing did).
         * This is the non-throwing "wait until this selector is visible" probe for conditional
         * flows — use the result length as the gate (`matches.length === 0` → not visible within
         * the timeout). Prefer this over hand-rolling a poll loop on top of point-in-time calls.
         */
        timeoutMs?: number;
      };
      result: MatchDescriptor[];
    };

    /**
     * Wait (up to `timeoutMs`) for the element matching [nodeSelector] to become NOT visible,
     * returning a NON-THROWING boolean verdict: `true` when the selector is (or became) not
     * visible within the budget, `false` when it is still visible after the wait.
     *
     * The disappearance counterpart to `findMatches` (which waits for APPEARANCE). Use it for
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
     * Distinct from negating `findMatches`: a positive probe returns true the moment the text is
     * *currently* on screen, so negating it doesn't wait for the element to actually disappear.
     * This tool waits for the live tree to lose the match. Routes through the driver-native
     * not-visible wait on the accessibility driver, Maestro `extendedWaitUntil` otherwise.
     *
     * The `nodeSelector` arg shape mirrors `findMatches`/`tapOnElementBySelector` — see the
     * MAINTAINER NOTE below for why the generated descriptor omits the selector type and this
     * hand-curated typing is authoritative.
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

    // MAINTAINER NOTE (contract): the Kotlin Koog/scripted-tool descriptor — generated by
    // `TrailblazeKoogToolExt` — intentionally OMITS both selector types (`TrailblazeNodeSelector`
    // and the legacy `TrailblazeElementSelector`, via its `excludedParameterTypes`) because
    // reflecting their self-referencing fields (childOf / containsChild / …) overflows the stack
    // during descriptor codegen. So for `tapOnElementBySelector` (and `waitUntilNotVisible`) the
    // generated descriptor advertises NO selector arg, and THIS hand-curated `nodeSelector` typing
    // is the authoritative scripted-author surface for it. Keep it in sync with
    // `TapOnByElementSelector.kt` by hand (per the Drift policy at the top of this file).
    /**
     * Tap the element resolved by a [TrailblazeNodeSelector], using the runtime's
     * selector-resolved tap routing (ACTION_CLICK on a qualifying interactive leaf, coordinate
     * gesture otherwise — see `AccessibilityDeviceManager`) plus the usual animation settle.
     *
     * Prefer this over reading bounds from `findMatches` and tapping `tapOnPoint`: it preserves
     * the routing that makes clickable *wrapper* rows (text on a child view) actually fire, which
     * a raw coordinate tap can't guarantee. Pair with `findMatches({ selector, timeoutMs })` to
     * wait for the element first.
     *
     * Source: `TapOnByElementSelector.kt` (`tapOnElementBySelector`).
     */
    tapOnElementBySelector: {
      args: {
        /** The node selector identifying the element to tap. */
        nodeSelector: TrailblazeNodeSelector;
        /** Set to true for a long press instead of a tap. Default false. */
        longPress?: boolean;
        /** Optional rationale logged alongside the tool call. */
        reason?: string;
      };
      result: string;
    };

    /**
     * Wait for on-screen animations to settle, up to the given number of seconds (it returns
     * early once the UI is idle). Backed by Maestro's `WaitForAnimationToEnd` — use it to let a
     * transition finish before a follow-up tap or assertion, the scripted-tool equivalent of the
     * Kotlin agent's `WaitForAnimationToEndCommand` settle.
     *
     * Source: `WaitForIdleSyncTrailblazeTool.kt` (`wait`).
     */
    wait: {
      args: {
        /** Maximum seconds to wait for animations to settle. Default 5. */
        timeToWaitInSeconds?: number;
      };
      result: string;
    };

    /**
     * Execute raw Maestro commands directly — the escape hatch the Kotlin agent reaches via
     * `runMaestroCommands(...)`. Each entry in `commands` is one Maestro command map in the
     * documented YAML/JSON flow shape (e.g. `{ tapOn: { text: "Verify" } }`,
     * `{ inputText: "123456" }`, `{ extendedWaitUntil: { notVisible: "Loading", timeout: 45000 } }`,
     * `{ assertVisible: { text: "0%", optional: true } }`). Prefer the specific typed tools above
     * when one exists; this is for faithful 1:1 ports of Kotlin steps that built Maestro orchestra
     * commands by hand (e.g. app launch / sign-in flows).
     *
     * Source: `MaestroTrailblazeTool.kt` (`mobile_maestro`). The Kotlin tool's custom serializer maps
     * the `{ commands: [...] }` payload onto a Maestro commands-list YAML before execution.
     *
     * Mobile-only escape hatch: web is Playwright-native (`web_*`), so this is gated to
     * android + ios. Author-only (`surfaceToLlm: false`) — the LLM uses the semantic tools.
     */
    mobile_maestro: {
      args: {
        /** Ordered list of Maestro command maps to run, in `MaestroYamlParser` flow shape. */
        commands: Array<Record<string, unknown>>;
      };
      result: string;
    };

    /**
     * @deprecated Renamed to `mobile_maestro`. This is a back-compat alias kept so existing
     * `ctx.tools.maestro(...)` callsites and legacy `maestro:` trails keep working; it delegates
     * to `mobile_maestro` at runtime (see `MaestroDeprecatedTrailblazeTool.kt`). Migrate to
     * `ctx.tools.mobile_maestro(...)`.
     *
     * Source: `MaestroDeprecatedTrailblazeTool.kt` (`maestro`).
     */
    maestro: {
      args: {
        /** Ordered list of Maestro command maps to run, in `MaestroYamlParser` flow shape. */
        commands: Array<Record<string, unknown>>;
      };
      result: string;
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
      };
      result: string;
    };

    /**
     * Run a process on the **host** JVM via argv (no shell). Host-only (`requiresHost = true`) — for
     * **tool authors**, not the LLM (`surfaceToLlm = false`). The escape hatch for composing host
     * CLIs from a scripted tool: iOS `xcrun simctl …` (pair with `ctx.device.instanceId` for the
     * simulator UDID), `adb` host subcommands, build steps, etc.
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
     * Clear all data for an app, resetting it to a fresh-install state. Host-only
     * (`requiresHost = true`), author-facing (`surfaceToLlm = false`). On Android delegates to
     * `pm clear`; on iOS locates and empties the app's `simctl` data container. The escape hatch
     * for a scripted launch step that needs the Kotlin `clearAppData` behavior (e.g. the Square
     * iOS launch flow's data-clear prefix) without re-deriving the per-platform `simctl` / `adb`
     * mechanics in TS.
     *
     * Source: `ClearAppDataTrailblazeTool.kt` (`mobile_clearAppData`).
     */
    mobile_clearAppData: {
      args: {
        /** App id to clear — Android package id or iOS bundle id (e.g. `com.squareup.square`). */
        appId: string;
      };
      result: string;
    };

    /**
     * Assert an element with the given accessibility text is visible. DEPRECATED upstream
     * in favor of the unified `assertVisible` selector path — kept here because the
     * recorded-trail format still emits this name.
     *
     * Source: `AssertVisibleWithAccessibilityTextTrailblazeTool.kt`.
     */
    assertVisibleWithAccessibilityText: {
      args: {
        /** Required. Accessibility text to assert is visible. */
        accessibilityText: string;
        /** Regex selector for disambiguating duplicates. */
        id?: string;
        /** 0-based index of the view to select among matches. */
        index?: number;
        enabled?: boolean;
        selected?: boolean;
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
     * Grant a dangerous runtime permission to an app via `pm grant`. Android-only.
     *
     * Source: `AndroidGrantPermissionTrailblazeTool.kt` (`android_grantPermission`).
     */
    android_grantPermission: {
      args: {
        /** App id (package) to grant to. */
        appId: string;
        /** Permission name, e.g. `android.permission.CAMERA`. */
        permission: string;
      };
      result: string;
    };

    /**
     * Grant an AppOps permission to an app via `appops set <op> allow`. Android-only.
     *
     * Source: `AndroidGrantAppOpsPermissionTrailblazeTool.kt` (`android_grantAppOpsPermission`).
     */
    android_grantAppOpsPermission: {
      args: {
        /** App id (package) to grant to. */
        appId: string;
        /** AppOps op, e.g. `MANAGE_EXTERNAL_STORAGE`. */
        permission: string;
      };
      result: string;
    };

    /**
     * List the app ids installed on the device. Android-only. The result is a JSON string of
     * `{ "appIds": string[] }` (sorted) — `JSON.parse` it to read the array.
     *
     * Source: `ListInstalledAppsTrailblazeTool.kt` (`mobile_listInstalledApps`).
     */
    mobile_listInstalledApps: {
      args: Record<string, never>;
      result: string;
    };
  }
}

// Marks this file as a module (rather than a global script) so the `declare module`
// block above is treated as an augmentation of `@trailblaze/scripting` instead of a
// redeclaration that overrides the package's actual exports.
export {};
