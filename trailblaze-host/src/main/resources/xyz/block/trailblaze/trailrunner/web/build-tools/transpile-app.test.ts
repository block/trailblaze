import { describe, expect, test } from "bun:test";
import {
  SCRIPTS_PROVIDED_BY_OTHER_MODULES,
  scriptSrcs,
  transpiledPath,
  wiringProblems,
} from "./transpile-app";

const OK_HTML = `
<script src="https://unpkg.com/react@18.3.1/umd/react.production.min.js"></script>
<script src="./app/rpc/daemon.bundle.js"></script>
<script src="./app/ui.js"></script>
<script src="./app/screens/home.js"></script>
`;

// The .tsx and the committed .js that make every tag in OK_HTML resolve.
const OK_TSX = ["app/ui.tsx", "app/screens/home.tsx"];
const OK_COMMITTED = ["app/rpc/daemon.bundle.js"];

describe("scriptSrcs", () => {
  test("collects local script sources in document order and ignores CDN tags", () => {
    expect(scriptSrcs(OK_HTML)).toEqual(["app/rpc/daemon.bundle.js", "app/ui.js", "app/screens/home.js"]);
  });
});

describe("transpiledPath", () => {
  test("maps a .tsx source to its sibling .js artifact", () => {
    expect(transpiledPath("app/screens/home.tsx")).toBe("app/screens/home.js");
  });
});

describe("wiringProblems", () => {
  test("passes when every .tsx is loaded as its transpiled .js", () => {
    expect(wiringProblems(OK_TSX, OK_HTML, OK_COMMITTED)).toEqual([]);
  });

  test("reports a screen that no <script> tag loads", () => {
    const problems = wiringProblems([...OK_TSX, "app/screens/orphan.tsx"], OK_HTML, OK_COMMITTED);
    expect(problems).toHaveLength(1);
    expect(problems[0]).toContain("app/screens/orphan.tsx");
    expect(problems[0]).toContain('<script src="./app/screens/orphan.js">');
  });

  test("reports a transpiled artifact that would clobber a committed .js of the same name", () => {
    const problems = wiringProblems(OK_TSX, OK_HTML, [...OK_COMMITTED, "app/ui.js"]);
    expect(problems).toHaveLength(1);
    expect(problems[0]).toContain("app/ui.js");
  });

  test("reports a tag whose .tsx was deleted or renamed away", () => {
    const html = `${OK_HTML}<script src="./app/screens/ghost.js"></script>`;
    const problems = wiringProblems(OK_TSX, html, OK_COMMITTED);
    expect(problems).toHaveLength(1);
    expect(problems[0]).toContain("app/screens/ghost.js");
    expect(problems[0]).toContain("app/screens/ghost.tsx");
  });

  test("reports a .tsx loaded directly, with or without a babel type attribute", () => {
    const plain = `${OK_HTML}<script src="./app/screens/home.tsx"></script>`;
    expect(wiringProblems(OK_TSX, plain, OK_COMMITTED)).toEqual([
      expect.stringContaining("loads app/screens/home.tsx directly"),
    ]);
  });

  test("accepts the scripts another module publishes onto the same classpath path", () => {
    const tags = SCRIPTS_PROVIDED_BY_OTHER_MODULES.map((p) => `<script src="./${p}"></script>`).join("");
    expect(wiringProblems(OK_TSX, `${OK_HTML}${tags}`, OK_COMMITTED)).toEqual([]);
  });

  test("reports a leftover in-browser babel tag", () => {
    const html = `${OK_HTML}<script type="text/babel" src="./app/screens/late.tsx"></script>`;
    expect(wiringProblems(OK_TSX, html, OK_COMMITTED).join("\n")).toContain('type="text/babel"');
  });

  test("reports a leftover @babel/standalone download", () => {
    const html = `<script src="https://unpkg.com/@babel/standalone@7.29.0/babel.min.js"></script>${OK_HTML}`;
    expect(wiringProblems(OK_TSX, html, OK_COMMITTED).join("\n")).toContain("@babel/standalone");
  });
});
