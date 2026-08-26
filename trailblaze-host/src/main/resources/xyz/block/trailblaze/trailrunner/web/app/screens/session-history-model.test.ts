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

  test("summaries are recomputed from the filtered rows", () => {
    const all = [
      { id: "a", timestampMs: 2, device: "android · phone", status: "passed" },
      { id: "b", timestampMs: 1, device: "android · phone", status: "timeout" },
    ];
    expect(HM.groupSessions(all, { timeZone: "UTC" })[0].counts.total).toBe(2);
    expect(HM.groupSessions(all.filter((s) => s.id === "b"), { timeZone: "UTC" })[0].counts).toMatchObject({ total: 1, failed: 1, passed: 0 });
  });
});
