import { describe, expect, test } from "bun:test";
import HM from "./session-history-model.js";

const at = (iso: string) => new Date(iso).getTime();

describe("History grouping", () => {
  test("groups newest-first by local day and device leg", () => {
    const rows = [
      { id: "older", timestampMs: at("2026-08-17T23:00:00Z"), device: "android · phone", status: "failed" },
      { id: "new", timestampMs: at("2026-08-18T08:00:00Z"), device: "android · tablet", status: "healed" },
      { id: "middle", timestampMs: at("2026-08-18T07:00:00Z"), device: "android · tablet", status: "cancelled", imported: true },
    ];
    const groups = HM.groupSessions(rows, { timeZone: "UTC", nowMs: at("2026-08-18T12:00:00Z") });
    expect(groups.map((g: any) => g.label)).toEqual(["Today", "Yesterday"]);
    expect(groups[0].legs[0].label).toBe("android-tablet");
    expect(groups[0].legs[0].rows.map((r: any) => r.id)).toEqual(["new", "middle"]);
    expect(groups[0].counts).toEqual({ total: 2, passed: 1, failed: 0, cancelled: 1, imported: 1 });
  });

  test("uses the requested timezone at a date boundary", () => {
    const row = { id: "late", timestampMs: at("2026-08-18T00:30:00Z"), platform: "web", status: "passed" };
    expect(HM.groupSessions([row], { timeZone: "UTC", nowMs: at("2026-08-18T12:00:00Z") })[0].label).toBe("Today");
    expect(HM.groupSessions([row], { timeZone: "America/Los_Angeles", nowMs: at("2026-08-18T12:00:00Z") })[0].label).toBe("Yesterday");
  });

  test("keeps legacy metadata under an explicit unknown-device leg", () => {
    const groups = HM.groupSessions([{ id: "legacy", timestampMs: 0, status: "stopped" }], { timeZone: "UTC", nowMs: 1 });
    expect(groups[0].legs[0].label).toBe("Unknown device");
    expect(groups[0].counts.cancelled).toBe(1);
  });

  test("collapses a trail's repeat runs on one device, newest run first", () => {
    const rows = [
      { id: "3rd", timestampMs: 3, device: "android · phone", trailId: "a/no-sale.trail.yaml", status: "passed" },
      { id: "2nd", timestampMs: 2, device: "android · phone", trailId: "a/no-sale.trail.yaml", status: "failed" },
      { id: "1st", timestampMs: 1, device: "android · phone", trailId: "a/no-sale.trail.yaml", status: "failed" },
    ];
    const leg = HM.groupSessions(rows, { timeZone: "UTC" })[0].legs[0];
    expect(leg.groups.length).toBe(1);
    expect(leg.groups[0].rows.map((r: any) => r.id)).toEqual(["3rd", "2nd", "1st"]);
    // The group's own summary covers every attempt, so a passing latest can't hide two failures.
    expect(leg.groups[0].counts).toMatchObject({ total: 3, passed: 1, failed: 2 });
    expect(leg.counts.total).toBe(3);
  });

  test("orders trail groups by how recently each trail last ran", () => {
    const rows = [
      { id: "sale-new", timestampMs: 4, device: "android · phone", trailId: "sale", status: "passed" },
      { id: "tip-old", timestampMs: 3, device: "android · phone", trailId: "tip", status: "passed" },
      { id: "sale-old", timestampMs: 1, device: "android · phone", trailId: "sale", status: "failed" },
    ];
    const leg = HM.groupSessions(rows, { timeZone: "UTC" })[0].legs[0];
    expect(leg.groups.map((t: any) => t.rows[0].id)).toEqual(["sale-new", "tip-old"]);
  });

  test("keeps the same trail on different devices in separate groups", () => {
    const rows = [
      { id: "phone", timestampMs: 2, device: "android · phone", trailId: "no-sale", status: "passed" },
      { id: "tablet", timestampMs: 1, device: "android · tablet", trailId: "no-sale", status: "passed" },
    ];
    const day = HM.groupSessions(rows, { timeZone: "UTC" })[0];
    expect(day.legs.map((l: any) => l.groups.length)).toEqual([1, 1]);
  });

  test("keeps one trail run at two targets apart", () => {
    const rows = [
      { id: "at-retail", timestampMs: 2, device: "android · phone", target: "retail", trailId: "shared", status: "passed" },
      { id: "at-wallet", timestampMs: 1, device: "android · phone", target: "wallet", trailId: "shared", status: "passed" },
    ];
    expect(HM.groupSessions(rows, { timeZone: "UTC" })[0].legs[0].groups.length).toBe(2);
  });

  test("never groups runs that have no trailId, even when their titles match", () => {
    // `title` is `SessionInfo.displayName`, which its own docs say "may collide between unrelated
    // tests", and only browser dispatches record a `trailId`. Keying on title would file two
    // unrelated CLI trails under one disclosure and hide the older one - a lost run, where not
    // grouping merely leaves today's behaviour in place. Grounding this properly is follow-up work.
    const rows = [
      { id: "t2", timestampMs: 4, device: "android · phone", title: "Sign in", status: "passed" },
      { id: "t1", timestampMs: 3, device: "android · phone", title: "Sign in", status: "failed" },
      { id: "anon-a", timestampMs: 2, device: "android · phone", status: "passed" },
      { id: "anon-b", timestampMs: 1, device: "android · phone", status: "passed" },
    ];
    const groups = HM.groupSessions(rows, { timeZone: "UTC" })[0].legs[0].groups;
    expect(groups.map((g: any) => g.rows.map((r: any) => r.id))).toEqual([["t2"], ["t1"], ["anon-a"], ["anon-b"]]);
  });

  test("a trail's label names the target, so two targets are two distinguishable sections", () => {
    // `trailKeyOf` separates on target, so the same trail at two targets is two legs. If the label
    // were the title alone, both section headers would read "Sign in" and the reader would be
    // looking at two sections that claim to be one thing.
    const at = (target: string) => HM.trailLabelOf({ title: "Sign in", trailId: "a.trail.yaml", target });
    expect(at("register")).not.toBe(at("sample-app"));
    expect(at("register")).toContain("Sign in");
    expect(at("register")).toContain("register");
    // Nothing appended when there is no target to append, rather than a dangling separator.
    expect(HM.trailLabelOf({ title: "Sign in", trailId: "a.trail.yaml" })).toBe("Sign in");
  });

  test("a titleless run falls back to its trail, not to its session id", () => {
    // A section header naming a raw session id names nothing the reader has ever seen; the trail
    // path at least says which trail ran.
    expect(HM.trailLabelOf({ id: "sess-4f2a", trailId: "pos/no-sale.trail.yaml" }))
      .toBe("pos/no-sale.trail.yaml");
    // The session id is still the last resort, so a run with neither is labelled rather than blank.
    expect(HM.trailLabelOf({ id: "sess-4f2a" })).toBe("sess-4f2a");
  });

  test("a target containing the key separator cannot forge a different trail's group", () => {
    // Split on a character either half can contain and "a" + "b c" collides with "a b" + "c",
    // filing two unrelated trails under one disclosure.
    expect(HM.trailKeyOf({ target: "a", trailId: "b c" }))
      .not.toBe(HM.trailKeyOf({ target: "a b", trailId: "c" }));
  });

  test("the same trail and device on two days get separate disclosure keys", () => {
    // Sharing a key makes the two groups toggle each other: opening yesterday's earlier runs would
    // silently open today's as well, from the other end of the rail.
    const rows = [
      { id: "today-2", timestampMs: at("2026-08-18T09:00:00Z"), device: "android · phone", trailId: "sale", status: "passed" },
      { id: "today-1", timestampMs: at("2026-08-18T08:00:00Z"), device: "android · phone", trailId: "sale", status: "failed" },
      { id: "yday-2", timestampMs: at("2026-08-17T09:00:00Z"), device: "android · phone", trailId: "sale", status: "passed" },
      { id: "yday-1", timestampMs: at("2026-08-17T08:00:00Z"), device: "android · phone", trailId: "sale", status: "failed" },
    ];
    const days = HM.groupSessions(rows, { timeZone: "UTC", nowMs: at("2026-08-18T12:00:00Z") });
    expect(days[0].legs[0].groups[0].key).not.toBe(days[1].legs[0].groups[0].key);
  });

  test("summaries are recomputed from the filtered rows", () => {
    const all = [
      { id: "a", timestampMs: 2, device: "android · phone", status: "passed" },
      { id: "b", timestampMs: 1, device: "android · phone", status: "timeout" },
    ];
    expect(HM.groupSessions(all, { timeZone: "UTC" })[0].counts.total).toBe(2);
    expect(HM.groupSessions(all.filter((s) => s.id === "b"), { timeZone: "UTC" })[0].counts).toMatchObject({ total: 1, failed: 1, passed: 0 });
  });
});

describe("Counts summaries", () => {
  const full = { total: 9, passed: 2, failed: 3, cancelled: 1, imported: 4 };

  test("lists every bucket, in one fixed order, as glyphs and as words", () => {
    expect(HM.countsGlyphs(full)).toBe("2✓ · 3× · 1■ · 4↥");
    expect(HM.countsWords(full)).toBe("2 passed, 3 failed, 1 cancelled, 4 imported");
  });

  test("counts imported runs, so an all-archive group never summarises as blank", () => {
    // The disclosure's whole job is saying what it hides. A group of three imported runs used to
    // render "3 earlier runs" beside an empty summary.
    expect(HM.countsGlyphs({ total: 3, imported: 3 })).toBe("3↥");
    expect(HM.countsWords({ total: 3, imported: 3 })).toBe("3 imported");
  });

  test("drops empty buckets without leaving a dangling separator", () => {
    expect(HM.countsGlyphs({ total: 2, failed: 2 })).toBe("2×");
    expect(HM.countsGlyphs({ total: 2, passed: 1, cancelled: 1 })).toBe("1✓ · 1■");
    expect(HM.countsWords({ total: 1, passed: 1 })).toBe("1 passed");
  });

  test("a group with nothing to report summarises as empty rather than as punctuation", () => {
    expect(HM.countsGlyphs({ total: 0 })).toBe("");
    expect(HM.countsWords({ total: 0 })).toBe("");
    expect(HM.countsGlyphs(null)).toBe("");
    expect(HM.countsWords(null)).toBe("");
  });
});

describe("Trail file stem vs title", () => {
  test("recognises the stem as the title in slug form, however the title is punctuated", () => {
    expect(HM.isSlugOfTitle("Checkout with a card", "checkout-with-a-card")).toBe(true);
    expect(HM.isSlugOfTitle("No sale", "no-sale")).toBe(true);
    expect(HM.isSlugOfTitle("Refund a payment!", "refund-a-payment")).toBe(true);
    // Both sides are normalised: a stem written in another style is still the same words.
    expect(HM.isSlugOfTitle("No sale", "No_Sale")).toBe(true);
  });

  test("keeps a stem that says something the title does not", () => {
    // This is the case the chip exists for: same title, different file.
    expect(HM.isSlugOfTitle("No sale", "no-sale-v2")).toBe(false);
    expect(HM.isSlugOfTitle("No sale", "tablet-no-sale")).toBe(false);
  });

  test("an empty title never matches, so a nameless run keeps its stem", () => {
    // Slugging both sides to "" would otherwise match everything and hide the only label there is.
    expect(HM.isSlugOfTitle("", "no-sale")).toBe(false);
    expect(HM.isSlugOfTitle(null, null)).toBe(false);
    expect(HM.isSlugOfTitle("!!!", "no-sale")).toBe(false);
  });
});

describe("Grouping axis", () => {
  // Two trails, two devices, four runs each pair - enough that the two axes have to disagree.
  const rows = [
    { id: "sale-phone-2", timestampMs: 8, device: "android · phone", trailId: "sale", title: "No sale", status: "passed" },
    { id: "sale-phone-1", timestampMs: 7, device: "android · phone", trailId: "sale", title: "No sale", status: "failed" },
    { id: "sale-pad-1", timestampMs: 6, device: "ios · ipad", trailId: "sale", title: "No sale", status: "passed" },
    { id: "refund-phone-1", timestampMs: 5, device: "android · phone", trailId: "refund", title: "Refund", status: "failed" },
  ];
  const shape = (days: any) =>
    days[0].legs.map((l: any) => [l.label, l.groups.map((g: any) => g.rows.map((r: any) => r.id))]);

  test("by device, a leg is one device and its groups are that device's trails", () => {
    expect(shape(HM.groupSessions(rows, { timeZone: "UTC", groupBy: "device" }))).toEqual([
      ["android-phone", [["sale-phone-2", "sale-phone-1"], ["refund-phone-1"]]],
      ["ios-ipad", [["sale-pad-1"]]],
    ]);
  });

  test("by trail, a leg is one trail and its groups are that trail's devices", () => {
    // The same four runs, re-nested: "No sale" now owns both devices, so you can read one trail's
    // latest result per device without hunting through a device section for each.
    expect(shape(HM.groupSessions(rows, { timeZone: "UTC", groupBy: "trail" }))).toEqual([
      ["No sale", [["sale-phone-2", "sale-phone-1"], ["sale-pad-1"]]],
      ["Refund", [["refund-phone-1"]]],
    ]);
  });

  test("defaults to device, and falls back to it rather than throwing on an unknown axis", () => {
    const byDefault = shape(HM.groupSessions(rows, { timeZone: "UTC" }));
    expect(byDefault).toEqual(shape(HM.groupSessions(rows, { timeZone: "UTC", groupBy: "device" })));
    expect(shape(HM.groupSessions(rows, { timeZone: "UTC", groupBy: "nonsense" }))).toEqual(byDefault);
    expect(HM.axisOf("trail")).toBe("trail");
    expect(HM.axisOf(undefined)).toBe("device");
    // The axis is read back out of localStorage, so "unknown" has to include the names every object
    // answers to. `constructor` is the one that reaches a node with no `keyOf` and throws.
    expect(HM.axisOf("constructor")).toBe("device");
    expect(shape(HM.groupSessions(rows, { timeZone: "UTC", groupBy: "constructor" }))).toEqual(byDefault);
  });

  test("no two groups anywhere in a tree share a disclosure key, on either axis", () => {
    // A shared key makes two groups toggle each other from opposite ends of the rail. This is the
    // invariant the day and the separator are both in the key to protect.
    ["device", "trail"].forEach((groupBy) => {
      const keys = HM.groupSessions(rows, { timeZone: "UTC", groupBy }).flatMap((d: any) =>
        d.legs.flatMap((l: any) => l.groups.map((g: any) => g.key)));
      expect(new Set(keys).size).toBe(keys.length);
    });
  });

  const keysOf = (rs: any[], groupBy: string) =>
    HM.groupSessions(rs, { timeZone: "UTC", groupBy }).flatMap((d: any) =>
      d.legs.flatMap((l: any) => l.groups.map((g: any) => g.key)));

  test("a group keeps one disclosure key across both axes", () => {
    // The reader's explicit closes live in one map that survives the switch, so the same device's
    // runs of the same trail have to answer to the same key however the rail is currently read.
    expect(keysOf(rows, "device").sort()).toEqual(keysOf(rows, "trail").sort());
  });

  test("two unrelated runs cannot share a key by permuting device, target and trail", () => {
    // Built in traversal order, `(device A, target B, trail C)` read by device concatenates to
    // exactly what `(device C, target A, trail B)` read by trail gives - so closing one group
    // silently closed an unrelated one after an axis switch.
    const rotated = [
      { id: "one", timestampMs: 2, device: "A", target: "B", trailId: "C", status: "passed" },
      { id: "two", timestampMs: 1, device: "C", target: "A", trailId: "B", status: "passed" },
    ];
    const keysFor = (id: string) =>
      new Set(["device", "trail"].map((groupBy) =>
        HM.groupSessions(rotated, { timeZone: "UTC", groupBy })
          .flatMap((d: any) => d.legs.flatMap((l: any) => l.groups))
          .find((g: any) => g.rows.some((r: any) => r.id === id)).key));
    const one = keysFor("one");
    const two = keysFor("two");
    expect(one.size).toBe(1);
    expect([...one].some((k) => two.has(k))).toBe(false);
  });

  test("both axes agree on which runs are visible when everything is collapsed", () => {
    // Re-nesting must not hide or duplicate a run: same latest-per-pair set, either way round.
    const ids = (groupBy: string) =>
      HM.visibleRuns(HM.groupSessions(rows, { timeZone: "UTC", groupBy }), {}, null)
        .map((r: any) => r.id).sort();
    expect(ids("trail")).toEqual(ids("device"));
    expect(ids("device")).toEqual(["refund-phone-1", "sale-pad-1", "sale-phone-2"]);
  });
});

describe("Run group disclosure", () => {
  const group = { key: "k", rows: [{ id: "latest" }, { id: "older" }, { id: "oldest" }] };
  const days = [{ legs: [{ groups: [group] }] }];

  test("stays closed by default, and selecting the visible latest run does not open it", () => {
    expect(HM.groupOpen(group, null, {})).toBe(false);
    expect(HM.groupOpen(group, "latest", {})).toBe(false);
  });

  test("opens on its own while a run it would hide holds the selection", () => {
    expect(HM.groupOpen(group, "oldest", {})).toBe(true);
  });

  test("an explicit toggle wins both ways, so closing actually closes", () => {
    expect(HM.groupOpen(group, "oldest", { k: false })).toBe(false);
    expect(HM.groupOpen(group, "latest", { k: true })).toBe(true);
  });

  test("landing on a hidden run drops the stored close, so the selection is never stranded", () => {
    expect(HM.revealSelectedGroup({ k: false }, days, "older")).toEqual({});
  });

  test("moving between two hidden runs of one closed group still reveals the second", () => {
    // The give-away bug: keyed on "is some hidden run selected" this stays true across the move,
    // never re-fires, and leaves the second run behind a closed disclosure.
    const afterFirst = HM.revealSelectedGroup({ k: false }, days, "older");
    expect(HM.revealSelectedGroup({ ...afterFirst, k: false }, days, "oldest")).toEqual({});
  });

  test("leaves a group closed when the selection is its own visible latest, or elsewhere", () => {
    expect(HM.revealSelectedGroup({ k: false }, days, "latest")).toEqual({ k: false });
    expect(HM.revealSelectedGroup({ k: false }, days, "someone-else")).toEqual({ k: false });
  });

  test("never reopens a group the reader deliberately opened, and returns the same object untouched", () => {
    const open = { k: true };
    expect(HM.revealSelectedGroup(open, days, "oldest")).toBe(open);
  });

  test("unusable inputs are inert rather than throwing", () => {
    expect(HM.groupOpen(null, "x", {})).toBe(false);
    expect(HM.revealSelectedGroup({ k: false }, null, "older")).toEqual({ k: false });
    expect(HM.revealSelectedGroup({ k: false }, days, null)).toEqual({ k: false });
  });
});

describe("Arrow-key navigation order", () => {
  // Two collapsed groups, so a bug that walks hidden runs shows up as a longer list AND as a
  // different row at the index the keyboard would land on.
  const build = () => HM.groupSessions([
    { id: "a2", timestampMs: 4, device: "android · phone", trailId: "sale", status: "passed" },
    { id: "a1", timestampMs: 3, device: "android · phone", trailId: "sale", status: "failed" },
    { id: "b2", timestampMs: 2, device: "ios · iphone", trailId: "refund", status: "passed" },
    { id: "b1", timestampMs: 1, device: "ios · iphone", trailId: "refund", status: "failed" },
  ], { timeZone: "UTC" });

  test("walks only the rendered rows, so a closed group contributes just its latest", () => {
    expect(HM.visibleRuns(build(), {}, null).map((r: any) => r.id)).toEqual(["a2", "b2"]);
  });

  test("an opened group puts its hidden runs back in the walk, right after their latest", () => {
    const days = build();
    const key = days[0].legs[0].groups[0].key;
    expect(HM.visibleRuns(days, { [key]: true }, null).map((r: any) => r.id)).toEqual(["a2", "a1", "b2"]);
  });

  test("a group auto-opened by the selection is walked too, matching what is on screen", () => {
    // `groupOpen` opens this group on its own because "a1" is hidden and selected. If
    // `visibleRuns` ignored that, the keyboard's index and the DOM's rows would disagree.
    expect(HM.visibleRuns(build(), {}, "a1").map((r: any) => r.id)).toEqual(["a2", "a1", "b2"]);
  });

  test("is empty rather than throwing when there is nothing to walk", () => {
    expect(HM.visibleRuns(null, {}, null)).toEqual([]);
    expect(HM.visibleRuns([{ legs: [{ groups: [{ key: "e", rows: [] }] }] }], {}, null)).toEqual([]);
  });
});
