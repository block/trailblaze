import { describe, expect, test } from "bun:test";
import NotInstalledTargetsModel from "./not-installed-targets-model.js";

describe("groupByRepo", () => {
  const appTrails = (id: string) => ({
    id,
    repo: "git@github.com:example-org/app-trails.git",
    shortName: "example-org/app-trails",
    cloneCommand: "git clone git@github.com:example-org/app-trails.git && cd app-trails",
    url: "https://github.com/example-org/app-trails",
    description: "App and App Lite",
  });

  test("entries sharing a repo collapse into one row carrying every target", () => {
    const groups = NotInstalledTargetsModel.groupByRepo([
      appTrails("myapp"),
      appTrails("myappLite"),
      {
        id: "otherapp",
        repo: "git@github.com:example-org/other-trails.git",
        shortName: "example-org/other-trails",
        cloneCommand: "git clone git@github.com:example-org/other-trails.git && cd other-trails",
      },
    ]);

    expect(groups.map((g) => g.shortName)).toEqual(["example-org/app-trails", "example-org/other-trails"]);
    expect(groups[0].targets).toEqual(["myapp", "myappLite"]);
    expect(groups[0].url).toBe("https://github.com/example-org/app-trails");
    expect(groups[0].description).toBe("App and App Lite");
    expect(groups[1].targets).toEqual(["otherapp"]);
    expect(groups[1].url).toBeNull();
  });

  test("a repeated target id renders once per repo", () => {
    const groups = NotInstalledTargetsModel.groupByRepo([appTrails("myapp"), appTrails("myapp")]);
    expect(groups).toHaveLength(1);
    expect(groups[0].targets).toEqual(["myapp"]);
  });

  test("one repo spelled via SSH and HTTPS across registry files renders as one row", () => {
    const groups = NotInstalledTargetsModel.groupByRepo([
      appTrails("myapp"),
      { ...appTrails("myappLite"), repo: "https://github.com/example-org/app-trails" },
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].targets).toEqual(["myapp", "myappLite"]);
    expect(groups[0].repo).toBe("git@github.com:example-org/app-trails.git");
  });

  test("entries missing a repo or id are dropped rather than rendered blank", () => {
    const groups = NotInstalledTargetsModel.groupByRepo([
      { id: "", repo: "git@github.com:example-org/app-trails.git" },
      { id: "ok", repo: "" },
      null,
    ]);
    expect(groups).toEqual([]);
  });

  test("no entries means no section", () => {
    expect(NotInstalledTargetsModel.groupByRepo([])).toEqual([]);
    expect(NotInstalledTargetsModel.groupByRepo(undefined)).toEqual([]);
  });

  test("a non-http(s) url never reaches an href", () => {
    const hostile = NotInstalledTargetsModel.groupByRepo([{ ...appTrails("myapp"), url: "javascript:alert(1)" }]);
    expect(hostile[0].url).toBeNull();
    expect(NotInstalledTargetsModel.safeHttpUrl("https://github.com/example-org/app-trails")).toBe(
      "https://github.com/example-org/app-trails",
    );
    expect(NotInstalledTargetsModel.safeHttpUrl("data:text/html,x")).toBeNull();
    expect(NotInstalledTargetsModel.safeHttpUrl(null)).toBeNull();
  });
});
