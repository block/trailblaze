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
      };
      result: MatchDescriptor[];
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
  }
}

// Marks this file as a module (rather than a global script) so the `declare module`
// block above is treated as an augmentation of `@trailblaze/scripting` instead of a
// redeclaration that overrides the package's actual exports.
export {};
