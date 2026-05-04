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
// flows and demos. Tools not listed here fall through to the untyped `(string, Record)`
// fallback overload on `client.callTool` — fully usable, just no autocomplete on args.
//
// Source-of-truth files for each entry below are linked next to the type. Changes to those
// Kotlin files MUST be reflected here.

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
      /** The center X coordinate for the clickable element. */
      x: number;
      /** The center Y coordinate for the clickable element. */
      y: number;
      /** Default `false` (standard tap). Pass `true` for a long press. */
      longPress?: boolean;
      /** Optional rationale logged alongside the tool call. */
      reasoning?: string;
    };

    /**
     * Tap on an element by visible text (substring match). DEPRECATED upstream in favor of
     * `tapOn`/`TapOnByElementSelector` — included here because tutorials still reference it.
     *
     * Source: `TapOnElementWithTextTrailblazeTool.kt`.
     */
    tapOnElementWithText: {
      /** Required. Text contained in the target element. */
      text: string;
      /** 0-based index of the view to select among matches. */
      index?: number;
      /** Regex for selecting by id when multiple elements share the text. */
      id?: string;
      enabled?: boolean;
      selected?: boolean;
    };

    /**
     * Type characters into the currently-focused text field. The runtime auto-hides the
     * keyboard after typing on iOS so a follow-up screenshot isn't obscured.
     *
     * Source: `InputTextTrailblazeTool.kt`.
     */
    inputText: {
      /** Required. Text to type into the focused field. */
      text: string;
      /** Optional rationale logged alongside the tool call. */
      reasoning?: string;
    };

    /**
     * Dismiss the on-screen keyboard. No args (singleton tool object on the Kotlin side).
     *
     * Source: `HideKeyboardTrailblazeTool.kt`.
     */
    hideKeyboard: Record<string, never>;

    /**
     * Press a hardware/system key.
     *
     * Source: `PressKeyTrailblazeTool.kt`.
     */
    pressKey: {
      /** Required. One of the supported `PressKeyCode` values. */
      keyCode: "BACK" | "ENTER" | "HOME";
    };

    /**
     * Swipe in a cardinal direction. Direction is the *finger* direction — to scroll the
     * page DOWN (see content below), the finger swipes UP.
     *
     * Source: `SwipeTrailblazeTool.kt`.
     */
    swipe: {
      /** Default `DOWN`. Direction of the finger gesture, not the scroll direction. */
      direction?: "UP" | "DOWN" | "LEFT" | "RIGHT";
      /** Element text to anchor the swipe on. Omit to swipe at screen center. */
      swipeOnElementText?: string;
      /** Optional rationale logged alongside the tool call. */
      reasoning?: string;
    };

    /**
     * Launch an app by id, with control over whether state is cleared and whether a running
     * instance is forcibly stopped first.
     *
     * Source: `LaunchAppTrailblazeTool.kt`.
     */
    launchApp: {
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

    /**
     * Assert an element with the given accessibility text is visible. DEPRECATED upstream
     * in favor of the unified `assertVisible` selector path — kept here because the
     * recorded-trail format still emits this name.
     *
     * Source: `AssertVisibleWithAccessibilityTextTrailblazeTool.kt`.
     */
    assertVisibleWithAccessibilityText: {
      /** Required. Accessibility text to assert is visible. */
      accessibilityText: string;
      /** Regex selector for disambiguating duplicates. */
      id?: string;
      /** 0-based index of the view to select among matches. */
      index?: number;
      enabled?: boolean;
      selected?: boolean;
    };
  }
}

// Marks this file as a module (rather than a global script) so the `declare module`
// block above is treated as an augmentation of `@trailblaze/scripting` instead of a
// redeclaration that overrides the package's actual exports.
export {};
