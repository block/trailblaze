// Tests for the `ConditionalAction` primitive — #3455 Phase 2.
//
// Coverage focuses on the architectural invariants the issue's design relies on:
//   - Snapshot is captured at most once when nothing matches (cost contract).
//   - Postcondition's pre-action role: already-satisfied entries skip without action.
//   - Postcondition's post-action role: failing verify throws ConditionalActionFailedError.
//   - ConditionalActionFailedError carries the offending entry's id, for all failure modes
//     (postcondition false, action throw, predicate throw).
//   - Presnapshot piggyback path avoids the extra round-trip.
//   - Bulk evaluation handles 0/1/N matching entries in a single pass.
//   - Boolean coercion: truthy-but-not-boolean returns from predicates behave per
//     the declared `boolean` type (1 → true, "" → false, etc.).
//   - Empty `conditionalActions` array short-circuits cleanly without requiring a snapshot.
//   - Phase 2 limitation: omitting presnapshot throws a clear error pointing at
//     captureViewHierarchy.

import { describe, expect, test } from "bun:test";

import {
  captureViewHierarchy,
  ConditionalActionFailedError,
  runConditionalActions,
  type ConditionalAction,
  type ViewHierarchy,
} from "./conditional-action.js";
import type { TrailblazeNodeSelector } from "./generated/selectors.js";
import { selectors } from "./generated/selectors.js";
import { createQueuedFindMatchesClient } from "./testing.js";

const POPUP = selectors.androidAccessibility({ textRegex: "Accept" });
const HOME = selectors.androidAccessibility({ textRegex: "Home" });
const TOS = selectors.androidAccessibility({ textRegex: "Terms" });

// Use the public `createQueuedFindMatchesClient` test helper rather than defining
// a per-file mock — the queued-response shape is also useful for future
// waypoint / catalog tests, so the fixture lives in `testing.ts` as part of the
// public test-helper surface. See its kdoc for the semantics.
function createQueuedClient() {
  return createQueuedFindMatchesClient();
}

describe("ConditionalActionFailedError", () => {
  test("carries the failing conditional's id", () => {
    const err = new ConditionalActionFailedError("dismiss-tos-popup", "boom");
    expect(err.conditionalActionId).toBe("dismiss-tos-popup");
    expect(err.message).toBe("boom");
    expect(err.name).toBe("ConditionalActionFailedError");
    expect(err instanceof Error).toBe(true);
    expect(err instanceof ConditionalActionFailedError).toBe(true);
  });

  test("carries the original `cause` when wrapping an underlying throw", () => {
    const inner = new Error("network down");
    const err = new ConditionalActionFailedError("dismiss-popup", "action threw", inner);
    expect(err.cause).toBe(inner);
  });

  test("defaults `cause` to null when no underlying error", () => {
    const err = new ConditionalActionFailedError("dismiss-popup", "postcondition false");
    expect(err.cause).toBeNull();
  });
});

describe("runConditionalActions: snapshot acquisition", () => {
  test("throws a Phase 2 limitation error when presnapshot is undefined (non-empty catalog)", async () => {
    const client = createQueuedClient();
    const conditional: ConditionalAction = {
      id: "needs-snapshot",
      description: "any non-empty catalog requires a snapshot",
      condition: () => true,
      action: async () => {},
    };
    await expect(runConditionalActions(client, [conditional])).rejects.toThrow(
      /presnapshot is required/,
    );
  });

  test("short-circuits with empty handled list when conditionalActions array is empty (no snapshot needed)", async () => {
    // Empty catalog has nothing to evaluate; the snapshot would be a wasted
    // round-trip. The contract is that `runConditionalActions(client, [])` returns
    // `{ handled: [] }` without throwing the missing-presnapshot error.
    const client = createQueuedClient();
    const result = await runConditionalActions(client, []);
    expect(result.handled).toEqual([]);
    expect(client.calls.length).toBe(0);
  });

  test("uses the provided presnapshot without re-fetching when nothing matches", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[]]);
    const snap = await captureViewHierarchy(client, [POPUP]);
    const callsBefore = client.calls.length;
    expect(callsBefore).toBe(1);

    const conditional: ConditionalAction = {
      id: "dismiss-popup",
      description: "Dismiss the accept popup if it appears.",
      condition: (s) => s.visible(POPUP),
      action: async () => {
        throw new Error("action must not run when condition is false");
      },
    };
    const result = await runConditionalActions(client, [conditional], snap);
    expect(result.handled).toEqual([]);
    // Verify the snapshot was reused — no further findMatches dispatch happened.
    expect(client.calls.length).toBe(callsBefore);
  });
});

describe("runConditionalActions: postcondition skip-fast", () => {
  test("entry whose postcondition is already satisfied skips before evaluating condition", async () => {
    // Scenario: POPUP already dismissed → postcondition `!visible(POPUP)` is true →
    // entry skips. The condition would also match (we throw from it to prove the
    // short-circuit) but it should never run because postcondition is checked first.
    const client = createQueuedClient();
    client.queueFindMatches([[]]); // POPUP not visible
    const snap = await captureViewHierarchy(client, [POPUP]);

    let conditionEvaluated = false;
    const conditional: ConditionalAction = {
      id: "dismiss-popup",
      description: "Dismiss the accept popup.",
      condition: () => {
        conditionEvaluated = true;
        return true;
      },
      action: async () => {
        throw new Error("action must not run on skip-fast path");
      },
      postcondition: (s) => !s.visible(POPUP),
    };
    const result = await runConditionalActions(client, [conditional], snap);
    expect(result.handled).toEqual([]);
    expect(conditionEvaluated).toBe(false);
  });
});

describe("runConditionalActions: matched entry executes action and verifies postcondition", () => {
  test("happy path — condition matches, action runs, postcondition verifies", async () => {
    const client = createQueuedClient();
    // Initial: POPUP visible (condition=true, postcondition=false → entry runs).
    // Verify: POPUP not visible (postcondition satisfied).
    client.queueFindMatches([
      [{ indexPath: [0, 1] }],
      [],
    ]);
    const snap = await captureViewHierarchy(client, [POPUP]);

    let actionRan = false;
    const conditional: ConditionalAction = {
      id: "dismiss-popup",
      description: "Dismiss the accept popup.",
      condition: (s) => s.visible(POPUP),
      action: async () => {
        actionRan = true;
      },
      postcondition: (s) => !s.visible(POPUP),
    };
    const result = await runConditionalActions(client, [conditional], snap);
    expect(actionRan).toBe(true);
    expect(result.handled).toEqual(["dismiss-popup"]);
  });

  test("postcondition fails after action → throws ConditionalActionFailedError with the entry id", async () => {
    const client = createQueuedClient();
    // Initial: POPUP visible. Verify: POPUP still visible (action didn't dismiss it).
    client.queueFindMatches([
      [{ indexPath: [0, 1] }],
      [{ indexPath: [0, 1] }],
    ]);
    const snap = await captureViewHierarchy(client, [POPUP]);

    const conditional: ConditionalAction = {
      id: "dismiss-popup",
      description: "Dismiss the accept popup.",
      condition: (s) => s.visible(POPUP),
      action: async () => {
        // Pretend to act — no-op for the test.
      },
      postcondition: (s) => !s.visible(POPUP),
    };
    try {
      await runConditionalActions(client, [conditional], snap);
      throw new Error("expected ConditionalActionFailedError");
    } catch (e) {
      expect(e).toBeInstanceOf(ConditionalActionFailedError);
      expect((e as ConditionalActionFailedError).conditionalActionId).toBe("dismiss-popup");
      expect((e as ConditionalActionFailedError).message).toMatch(/postcondition not satisfied/);
      // Postcondition-false failures have no underlying error to wrap.
      expect((e as ConditionalActionFailedError).cause).toBeNull();
    }
  });

  test("entry without a postcondition runs action without a verify round-trip", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[{ indexPath: [0] }]]); // initial: matches
    const snap = await captureViewHierarchy(client, [POPUP]);
    const callsBefore = client.calls.length;

    let actionRan = false;
    const conditional: ConditionalAction = {
      id: "no-verify",
      description: "Action without postcondition verify.",
      condition: (s) => s.visible(POPUP),
      action: async () => {
        actionRan = true;
      },
    };
    const result = await runConditionalActions(client, [conditional], snap);
    expect(actionRan).toBe(true);
    expect(result.handled).toEqual(["no-verify"]);
    // No verify snapshot — same call count.
    expect(client.calls.length).toBe(callsBefore);
  });
});

describe("runConditionalActions: predicate / action throw wrapping", () => {
  test("condition that throws → ConditionalActionFailedError carries id and wraps the cause", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[]]);
    const snap = await captureViewHierarchy(client, [POPUP]);
    const inner = new Error("predicate exploded");
    const conditional: ConditionalAction = {
      id: "buggy-condition",
      description: "condition throws.",
      condition: () => {
        throw inner;
      },
      action: async () => {},
    };
    try {
      await runConditionalActions(client, [conditional], snap);
      throw new Error("expected ConditionalActionFailedError");
    } catch (e) {
      expect(e).toBeInstanceOf(ConditionalActionFailedError);
      expect((e as ConditionalActionFailedError).conditionalActionId).toBe("buggy-condition");
      expect((e as ConditionalActionFailedError).message).toMatch(/condition predicate threw/);
      expect((e as ConditionalActionFailedError).cause).toBe(inner);
    }
  });

  test("postcondition that throws during skip-fast → ConditionalActionFailedError with the entry id", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[]]);
    const snap = await captureViewHierarchy(client, [POPUP]);
    const inner = new Error("postcondition exploded");
    const conditional: ConditionalAction = {
      id: "buggy-postcondition",
      description: "postcondition throws during skip-fast.",
      condition: () => true,
      action: async () => {},
      postcondition: () => {
        throw inner;
      },
    };
    try {
      await runConditionalActions(client, [conditional], snap);
      throw new Error("expected ConditionalActionFailedError");
    } catch (e) {
      expect(e).toBeInstanceOf(ConditionalActionFailedError);
      expect((e as ConditionalActionFailedError).conditionalActionId).toBe("buggy-postcondition");
      expect((e as ConditionalActionFailedError).message).toMatch(/postcondition predicate threw/);
      expect((e as ConditionalActionFailedError).cause).toBe(inner);
    }
  });

  test("action that throws → ConditionalActionFailedError carries id and wraps the cause", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[{ indexPath: [0] }]]); // POPUP visible
    const snap = await captureViewHierarchy(client, [POPUP]);
    const inner = new Error("network down during click");
    const conditional: ConditionalAction = {
      id: "click-fails",
      description: "action throws.",
      condition: (s) => s.visible(POPUP),
      action: async () => {
        throw inner;
      },
    };
    try {
      await runConditionalActions(client, [conditional], snap);
      throw new Error("expected ConditionalActionFailedError");
    } catch (e) {
      expect(e).toBeInstanceOf(ConditionalActionFailedError);
      expect((e as ConditionalActionFailedError).conditionalActionId).toBe("click-fails");
      expect((e as ConditionalActionFailedError).message).toMatch(/action threw/);
      expect((e as ConditionalActionFailedError).cause).toBe(inner);
    }
  });

  test("action that throws a ConditionalActionFailedError preserves the inner id (composed catalogs)", async () => {
    // A composed sub-catalog inside an action would have already wrapped its own
    // failure with the sub-entry id. Don't re-wrap — the inner id is more
    // specific and the outer entry's id would mask the actual offender.
    const client = createQueuedClient();
    client.queueFindMatches([[{ indexPath: [0] }]]);
    const snap = await captureViewHierarchy(client, [POPUP]);
    const inner = new ConditionalActionFailedError("inner-entry", "inner failed");
    const conditional: ConditionalAction = {
      id: "outer-entry",
      description: "delegates to a sub-catalog that already threw.",
      condition: (s) => s.visible(POPUP),
      action: async () => {
        throw inner;
      },
    };
    try {
      await runConditionalActions(client, [conditional], snap);
      throw new Error("expected ConditionalActionFailedError");
    } catch (e) {
      expect(e).toBeInstanceOf(ConditionalActionFailedError);
      expect((e as ConditionalActionFailedError).conditionalActionId).toBe("inner-entry");
    }
  });

  test("postcondition that throws during POST-ACTION verify wraps with entry id (distinct from skip-fast throw)", async () => {
    // Coverage gap from the lead-dev review: the postcondition-throws test above
    // exercises the SKIP-FAST phase (postcondition called inside the bulk filter
    // before any action runs). The post-action verify phase is a separate code
    // path — postcondition is called via [evalPostcondition] from inside the
    // main `for ... of applicable` loop, AFTER the action has already run. We
    // need a test that lands in that branch specifically.
    const client = createQueuedClient();
    // Pre-snapshot: POPUP visible (condition matches, postcondition false → applicable).
    // Verify snapshot (post-action): we don't care about the content; the
    // postcondition itself will throw before reading.
    client.queueFindMatches([
      [{ indexPath: [0] }], // pre: matches
      [], // verify: doesn't matter — postcondition throws below
    ]);
    const snap = await captureViewHierarchy(client, [POPUP]);
    const inner = new Error("verify-time postcondition exploded");
    let postCalls = 0;
    const conditional: ConditionalAction = {
      id: "verify-throws",
      description: "postcondition throws on the verify snapshot, not the skip-fast one.",
      condition: (s) => s.visible(POPUP),
      action: async () => {
        // No-op; the verify-phase throw is what we're testing, not action behavior.
      },
      postcondition: (s) => {
        postCalls += 1;
        // First call (skip-fast): return false so the entry is applicable.
        // Second call (post-action verify): throw to exercise the verify-phase
        // throw-wrapping path. Without this branch the test would be identical
        // to the skip-fast case.
        if (postCalls === 1) return s.visible(POPUP) === false; // false → applicable
        throw inner;
      },
    };
    try {
      await runConditionalActions(client, [conditional], snap);
      throw new Error("expected ConditionalActionFailedError");
    } catch (e) {
      expect(e).toBeInstanceOf(ConditionalActionFailedError);
      expect((e as ConditionalActionFailedError).conditionalActionId).toBe("verify-throws");
      expect((e as ConditionalActionFailedError).message).toMatch(/postcondition predicate threw/);
      expect((e as ConditionalActionFailedError).cause).toBe(inner);
      // The action DID run before the verify-phase throw — both postcondition
      // calls happened (pre + post).
      expect(postCalls).toBe(2);
    }
  });
});

describe("runConditionalActions: boolean coercion of predicate returns", () => {
  test("truthy non-boolean condition return is treated as match", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[]]);
    const snap = await captureViewHierarchy(client, [POPUP]);

    let actionRan = false;
    const conditional: ConditionalAction = {
      id: "truthy-condition",
      description: "Returns 1 instead of true.",
      // Returning `1` here would coerce to `true` even without Boolean(); we
      // assert the runtime contract holds (`1` matches as truthy).
      condition: (() => 1) as unknown as (snap: ViewHierarchy) => boolean,
      action: async () => {
        actionRan = true;
      },
    };
    const result = await runConditionalActions(client, [conditional], snap);
    expect(actionRan).toBe(true);
    expect(result.handled).toEqual(["truthy-condition"]);
  });

  test("falsy non-boolean postcondition return is NOT treated as 'already satisfied'", async () => {
    // Postcondition returning `0` (falsy) → entry is NOT skip-fast'd → it runs.
    // Verify snapshot then needs a postcondition result; we make it return `1`
    // (truthy → satisfied). The flow exercises both directions of coercion.
    const client = createQueuedClient();
    client.queueFindMatches([
      [{ indexPath: [0] }], // pre: POPUP visible (condition match)
      [], // verify: POPUP not visible — postcondition will return truthy below
    ]);
    const snap = await captureViewHierarchy(client, [POPUP]);

    let actionRan = false;
    let postCalls = 0;
    const conditional: ConditionalAction = {
      id: "coerced-postcondition",
      description: "Postcondition returns 0 then 1.",
      condition: (s) => s.visible(POPUP),
      action: async () => {
        actionRan = true;
      },
      // First (skip-fast) call returns 0 (falsy → not satisfied → run).
      // Second (verify) call returns 1 (truthy → satisfied → no throw).
      postcondition: (() => {
        postCalls += 1;
        return postCalls === 1 ? 0 : 1;
      }) as unknown as (snap: ViewHierarchy) => boolean,
    };
    const result = await runConditionalActions(client, [conditional], snap);
    expect(actionRan).toBe(true);
    expect(result.handled).toEqual(["coerced-postcondition"]);
  });
});

describe("runConditionalActions: bulk evaluation", () => {
  test("zero matching entries → no actions, empty handled list", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[], []]);
    const snap = await captureViewHierarchy(client, [POPUP, TOS]);

    const conditionalActions: ConditionalAction[] = [
      {
        id: "a",
        description: "no-match a",
        condition: (s) => s.visible(POPUP),
        action: async () => {
          throw new Error("a must not run");
        },
      },
      {
        id: "b",
        description: "no-match b",
        condition: (s) => s.visible(TOS),
        action: async () => {
          throw new Error("b must not run");
        },
      },
    ];
    const result = await runConditionalActions(client, conditionalActions, snap);
    expect(result.handled).toEqual([]);
  });

  test("multiple applicable entries run in order, handled list reflects order", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([
      [{ indexPath: [0] }], // POPUP visible
      [{ indexPath: [0] }], // HOME visible
      [], // TOS not visible
    ]);
    const snap = await captureViewHierarchy(client, [POPUP, HOME, TOS]);

    const order: string[] = [];
    const conditionalActions: ConditionalAction[] = [
      {
        id: "first",
        description: "POPUP match",
        condition: (s) => s.visible(POPUP),
        action: async () => {
          order.push("first");
        },
      },
      {
        id: "skipped",
        description: "TOS no-match",
        condition: (s) => s.visible(TOS),
        action: async () => {
          order.push("skipped");
        },
      },
      {
        id: "second",
        description: "HOME match",
        condition: (s) => s.visible(HOME),
        action: async () => {
          order.push("second");
        },
      },
    ];
    const result = await runConditionalActions(client, conditionalActions, snap);
    expect(result.handled).toEqual(["first", "second"]);
    expect(order).toEqual(["first", "second"]);
  });

  test("multi-postcondition sequence — mid-sequence failure leaves prior successes in handled before throwing", async () => {
    // Two entries with postconditions; first verifies cleanly, second's
    // postcondition fails. The throw discards the in-flight `handled` array
    // (it's only populated on success), but a `try/catch` on the call site
    // can confirm that `handled[0]` ran and the second entry is the failing id.
    const client = createQueuedClient();
    client.queueFindMatches([
      // Pre-snapshot: POPUP visible, HOME visible
      [{ indexPath: [0] }],
      [{ indexPath: [0] }],
      // Verify after first action: POPUP not visible (passes)
      [],
      [{ indexPath: [0] }], // HOME still visible (incidental; first postcondition only probes POPUP)
      // Verify after second action: HOME STILL visible (fails)
      [],
      [{ indexPath: [0] }],
    ]);
    const snap = await captureViewHierarchy(client, [POPUP, HOME]);

    const actionsRan: string[] = [];
    const conditionalActions: ConditionalAction[] = [
      {
        id: "first-pass",
        description: "first entry succeeds.",
        condition: (s) => s.visible(POPUP),
        action: async () => {
          actionsRan.push("first-pass");
        },
        postcondition: (s) => !s.visible(POPUP),
      },
      {
        id: "second-fail",
        description: "second entry's postcondition fails.",
        condition: (s) => s.visible(HOME),
        action: async () => {
          actionsRan.push("second-fail");
        },
        postcondition: (s) => !s.visible(HOME),
      },
    ];
    try {
      await runConditionalActions(client, conditionalActions, snap);
      throw new Error("expected ConditionalActionFailedError");
    } catch (e) {
      expect(e).toBeInstanceOf(ConditionalActionFailedError);
      expect((e as ConditionalActionFailedError).conditionalActionId).toBe("second-fail");
    }
    // Both actions actually ran before the second's postcondition failed.
    expect(actionsRan).toEqual(["first-pass", "second-fail"]);
  });
});

describe("captureViewHierarchy", () => {
  test("pre-resolves selectors via findMatches in parallel", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([
      [{ indexPath: [0] }],
      [{ indexPath: [1] }, { indexPath: [2] }],
    ]);
    const snap = await captureViewHierarchy(client, [POPUP, HOME]);

    expect(snap.visible(POPUP)).toBe(true);
    expect(snap.findAll(HOME)).toHaveLength(2);
    expect(snap.find(HOME)?.indexPath).toEqual([1]);
  });

  test("querying a selector not in the pre-resolved list throws", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[]]);
    const snap = await captureViewHierarchy(client, [POPUP]);
    expect(() => snap.visible(HOME)).toThrow(/was not pre-resolved/);
  });

  test("visible/find/findAll agree on no-match selectors", async () => {
    const client = createQueuedClient();
    client.queueFindMatches([[]]);
    const snap = await captureViewHierarchy(client, [POPUP]);
    expect(snap.visible(POPUP)).toBe(false);
    expect(snap.find(POPUP)).toBeNull();
    expect(snap.findAll(POPUP)).toEqual([]);
  });

  test("empty selectors list builds a snapshot whose only operation is throwing on any query", async () => {
    // An empty selectors list resolves zero findMatches calls and returns a
    // ViewHierarchy that throws on every visible/find/findAll. This is the
    // "no predicates use the snapshot" edge case — legal to build but useless
    // to query. Verified to confirm the builder doesn't crash on empty input.
    const client = createQueuedClient();
    const snap = await captureViewHierarchy(client, []);
    expect(client.calls.length).toBe(0);
    expect(() => snap.visible(POPUP)).toThrow(/was not pre-resolved/);
  });

  test("custom ViewHierarchy (not built via captureViewHierarchy) cannot be refreshed for postcondition verify", async () => {
    const client = createQueuedClient();
    // A unit-test-fake ViewHierarchy that doesn't carry the internal selector
    // registry — legitimate for tests that pre-build a synthetic snapshot, but
    // `runConditionalActions` can't refresh it for a post-action verify. The
    // postcondition must return false on the PRE-action snapshot (so the entry
    // is applicable and the action runs); the refresh path then trips on the
    // missing registry instead of being short-circuited by skip-fast.
    const fake: ViewHierarchy = {
      visible: (_s: TrailblazeNodeSelector) => true,
      find: (_s: TrailblazeNodeSelector) => ({ indexPath: [] }),
      findAll: (_s: TrailblazeNodeSelector) => [{ indexPath: [] }],
    };
    const conditional: ConditionalAction = {
      id: "needs-refresh",
      description: "Entry with a postcondition can't refresh a custom snapshot.",
      condition: () => true,
      action: async () => {},
      postcondition: (s) => !s.visible(POPUP), // false against the fake → applicable
    };
    await expect(runConditionalActions(client, [conditional], fake)).rejects.toThrow(
      /cannot refresh this snapshot/,
    );
  });

  test("mutating the caller's selectors array after captureViewHierarchy doesn't perturb the snapshot", async () => {
    // The kdoc on captureViewHierarchy promises that the snapshot takes ownership of
    // an internal copy of the selectors list. Prove that promise here — mutate the
    // caller's array post-capture and assert the snapshot still resolves the originals.
    const client = createQueuedClient();
    client.queueFindMatches([
      [{ indexPath: [0] }], // POPUP visible
      [], // HOME absent
    ]);
    const mutableSelectors: TrailblazeNodeSelector[] = [POPUP, HOME];
    const snap = await captureViewHierarchy(client, mutableSelectors);

    // Mutate the caller's array in-place — replace POPUP with TOS (a selector
    // never registered). If the snapshot held the array by reference, querying
    // TOS would now resolve to POPUP's matches (wrong) and querying POPUP
    // would throw "was not pre-resolved" (also wrong).
    mutableSelectors[0] = TOS;
    mutableSelectors.push(TOS);

    expect(snap.visible(POPUP)).toBe(true);
    expect(snap.visible(HOME)).toBe(false);
    // TOS was never registered against the captured snapshot — the mutation
    // can't smuggle it in.
    expect(() => snap.visible(TOS)).toThrow(/was not pre-resolved/);
  });

  test("mutating the array returned by findAll doesn't corrupt later findAll calls", async () => {
    // The kdoc on captureViewHierarchy promises immutability: a caller that mutates
    // a findAll(...) return shouldn't see those mutations on a subsequent call.
    // Prove that — push a fake descriptor onto array1, then verify array2 still
    // matches the original captured length.
    const client = createQueuedClient();
    client.queueFindMatches([
      [{ indexPath: [0] }, { indexPath: [1] }], // POPUP: 2 matches
    ]);
    const snap = await captureViewHierarchy(client, [POPUP]);

    const array1 = snap.findAll(POPUP);
    expect(array1).toHaveLength(2);
    // Mutate the returned array — push a fabricated descriptor and clear an entry.
    array1.push({ indexPath: [99] });
    array1[0] = { indexPath: [-1] };
    expect(array1).toHaveLength(3); // mutation took effect on array1

    const array2 = snap.findAll(POPUP);
    expect(array2).toHaveLength(2); // snapshot internal state unchanged
    expect(array2[0]!.indexPath).toEqual([0]);
    expect(array2[1]!.indexPath).toEqual([1]);
    // And array1's mutations did not bleed into array2 by reference.
    expect(array1).not.toBe(array2);
  });
});
