# Session Surveys

A **survey** is a named check over a session: a small TypeScript function that reads what the
session captured and reports what it found, pointing at the records that prove it. Its answer is
a **finding**: a summary, the evidence, and whatever `data` the survey chose to carry, such as
*"Completed a $5.00 CARD payment, evidenced by `network.ndjson:411` and
`events/analytics.ndjson:1531`"* with `{ amount: 500, tender: "CARD" }`. What a survey looks for
is up to its author: a product feature, a user journey's outcome, a server error, a slow launch.
Run a set of surveys over a set of sessions and you get the findings per session plus a report
per survey across the whole batch.

Most surveys run after a session, over the session directory the runner writes (or the zip CI
uploads), so adding one never changes how a trail executes. A survey that reads only network,
analytics and events can also run while the trail does, from a TypeScript tool: see
[Running a survey mid-trail](#running-a-survey-mid-trail).

## Defining a survey

Surveys follow the shape of `trailblaze.tool()`: a spec plus a function, exported from a
`*.survey.ts` file. The export name is the survey's name: in reports, in `--survey`, and in
`ctx.surveys.<name>()`. Nothing in the spec is required.

```ts
import { trailblaze } from "@trailblaze/scripting/surveys";

export const paymentCompleted = trailblaze.survey(
  { description: "Completed a payment", appIds: ["com.example.pos"] },
  (session) =>
    session.network
      .filter({ method: "POST", path: "/v1/checkout/complete", status: "ok" })
      .map((complete) => {
        const finished = session.analytics.find({ name: "Payment Complete" });
        return {
          summary: `Completed a payment`,
          evidence: finished ? [complete, finished] : [complete],
          data: { paymentId: /payment_id:\s*"([^"]+)"/.exec(complete.requestBody ?? "")?.[1] },
        };
      }),
);
```

Return one finding, an array of findings, or nothing. Every finding carries a human summary and
the evidence records. `data` is free-form: any JSON-shaped object a reader or a tool needs, the
way an event row carries whatever its stream wrote. Nothing else is part of a finding, so what a
survey can report is not limited to any one idea such as a product feature: the survey decides
what its findings are about, and says so in `data`.

When the check is "these things all happened", state the finding instead of writing a handler:
its `summary` (else the survey's `description`), any `data`, and the queries that prove it:

```ts
export const signedIn = trailblaze.survey({
  finding: {
    summary: "Signed in with email and password",
    network: { method: "POST", path: /\/v1\/auth\/login$/, status: "ok" },
    analytics: { name: "Login" },
    absent: { network: { path: "/v1/auth/logout" } },
    data: { method: "password" },
  },
});
```

Every listed query must match at least one record; anything under `absent` must match none.
The evidence is the first match of each query (`evidence: "all"` keeps every match).
`footprint` is the old name of `finding` and still works for now.

### Shaping what a finding carries

A survey's `data` is a contract between it and whatever reads its findings, the way a stream's
rows are between the app that writes them and the tool that queries `ctx.events`. Give it a type
and the handler is checked against it:

```ts
interface Journey {
  journey: string;
  outcome: "succeeded" | "abandoned";
  frictions: string[];
}

export const journeys = trailblaze.survey<Journey>({ description: "Journeys the app uploaded" }, (session) =>
  session.network.filter({ method: "POST", path: "/v1/journeys" }).flatMap((upload) =>
    parseJourneys(upload.requestBody).map((j) => ({
      summary: `${j.name} ${j.outcome}`,
      evidence: [upload],
      data: { journey: j.name, outcome: j.outcome, frictions: j.frictions },
    })),
  ),
);
```

A reader that knows the survey reads its findings by survey name and expects that shape. One
survey can report many different things, such as every row of a product catalog or every journey
an app names: each finding says which in its `data`, and a reader groups on that field. Use
`matchFinding(session, finding)` to test declarative findings from a handler, one per row of a
table. A handler made of independent checks reports one that failed with `ctx.error(message)`
instead of throwing, so the report lists the error and keeps what the other checks found.

Surveys written before findings were free-form set `feature`, `kind` or `confidence` beside
`summary`, or a default `feature` or `kind` on the spec. They still run: those fields are carried into the
finding's `data`, where the survey's own `data` keys win. Move them into `data` yourself to type
them.

### Scoping

`platforms`, `appIds`, and `targets` on the spec skip sessions the survey was never meant for,
so an iOS payments survey is not blamed for silence on an Android dashboard run. `tags` lets
the CLI run a subset.

Prefer `targets` for "this is a feature of app X": a target is what the trailmap defines, and it
already lists the app ids of every build of that app on every platform. Pass the workspace's
trailmaps to a run (`--trailmaps`, or `targets` on `runSurveys`) and a session counts as the
target's when its trail named the target *or* it drove one of the target's app ids, so sessions
run without a target are still placed. Use `appIds` when a survey is specific to one build,
such as a debug-only bundle.

### Surveys in a trailmap

A target's surveys can ship inside its trailmap, in a `surveys/` directory beside `tools/`:

```
trailmaps/acme/
  trailmap.yaml
  tools/
  surveys/
    checkout.survey.ts
```

Surveys for any target go in the workspace config dir's own `surveys/`. A run inside the
workspace loads both kinds without being told where they are, so the repo that owns a target
owns its surveys and nothing has to list them. `*.survey.ts` files elsewhere in a trailmap are
not loaded. A survey there still declares `targets` itself: the sessions page
re-runs survey source in the browser, where a scope inferred from the file's location would be
lost. Export names stay unique across every survey loaded, trailmap or not.

A survey imports the SDK as `@trailblaze/scripting/surveys` wherever it lives. The loader
supplies that module itself (and `@trailblaze/scripting/matcher`), so a trailmap's `surveys/`
needs no tsconfig `paths` mapping or installed package to run.

To typecheck a trailmap's surveys and run their `*.test.ts` in the repo that owns the trailmap,
point a `surveys/tsconfig.json` at the SDK `trailblaze check` materializes — the same mapping the
generated `tools/tsconfig.json` uses:

```json
{ "compilerOptions": { "paths": { "@trailblaze/scripting/*": ["../../../../.trailblaze/sdk/dist/*"] } } }
```

`dist/surveys.{d.ts,js}` is the whole authoring surface, including the waypoint engine and session
snapshots, and its declarations need no node types. The module runs on the host under Bun, not in a
browser: it reads sessions through `node:fs`, and its YAML readers use `Bun.YAML`. A survey may import a helper from a sibling
trailmap's `surveys/` by relative path when the two trailmaps always ship together.

### Waypoints

A waypoint names a screen by the view-tree nodes that prove it, so a survey can report which screens
a session reached. `loadYamlWaypoints(dir, repoRoot)` reads `*.waypoint.yaml` files, and
`waypointFindings(session, waypoints, { screenshotHosts })` matches them against every view tree
the session logged. A finding's screenshot link is kept only when it points at a host the caller
names in `screenshotHosts`, so a report never links somewhere unexpected.

## Running a survey mid-trail

A TypeScript tool can run a survey mid-trail to check that what it looks for has happened by now:
"the checkout request and the `checkout_done` event have both arrived". When something happens
matters as much as whether it did, so a trail can fail at the step where it was due.

By default a survey can run mid-trail. While a trail runs, only `session.network`,
`session.analytics` and `session.events` hold records, and the survey runs in the scripted-tool
sandbox. A survey that needs more sets `requiresFinishedSession: true` and runs only after the
session, on the host, where anything is allowed. That includes the outcome, tool calls, Trailblaze logs,
screen text and session files, and `node:*` or Bun imports:

```ts
export const crashed = trailblaze.survey(
  { requiresFinishedSession: true },
  (session) => {
    const failed = session.trailblazeLogs.findLast({ type: "TrailblazeSessionStatusChangeLog" });
    return session.summary.outcome === "FAILED" && failed ? { summary: "Run failed", evidence: [failed] } : undefined;
  },
);
```

A tool calls a survey by its export name and decides what the result means: fail the step,
branch, or keep going.

```ts
export const verifyCheckout = trailblaze.tool(async (_args, ctx) => {
  const done = await ctx.surveys.checkoutDone({ waitMs: 10_000 });
  if (!done.passed) throw new Error(done.message);
  return done.message;
});
```

- A survey covers everything the session has captured so far, and returns on its first finding.
  `waitMs` (default 0) waits for a finding to arrive.
- It returns `{ passed, message, findings, waitedMs }` and never throws when it finds nothing.
  Each finding carries its survey's name, summary, evidence and `data`, so a tool can assert on
  the fields a survey pulled out (a journey's variant, its tags) as well as on the pass. It
  does not pass when the wait runs out, and `message` names the finding queries that matched and
  the ones that did not. It also does not pass when a capture recorded losing data, since the
  missing record may be the one it looks for.
- `absent` in a finding means "not seen so far".
- A request still waiting for its response is in flight: `status: failed` matches it only once
  the capture records the failure.
- A finding that looks for an analytics event by name reads only that event's records, so a
  long session costs it what it matches. Any other finding query reads its streams whole, and a
  handler reads every stream whole. Mid-trail, a survey holds at most 20,000 records (16 million
  characters of them). Past that it fails, naming the limit: write the check as a finding on an
  event name, or move it after the session.

A trailmap's generated types list its mid-trail surveys under `ctx.surveys`. A survey with
`requiresFinishedSession` is left out on purpose. `session.final` is `false` mid-trail and `true`
after the session, for a helper shared by both; `trailblaze check` lets a survey that tests it
read finished-session data.

`trailblaze check` fails, naming the fix, on:

- a mid-trail survey that reads finished-session data, or whose file does not build for the
  sandbox; and
- a survey name that two files export.

Mid-trail surveys run wherever scripted tools run: trails run by `trailblaze run` and the daemon,
on every platform, and trails a test APK runs on an Android device. The device has no esbuild, so
the APK build bundles each trailmap's `surveys/` and packages it: the Android Gradle plugin's
`trailmap { }` block does this for the trailmap it bundles tools for (`surveysDir` overrides the
`surveys/` beside `toolsDir`), and so does `trailblaze inprocess make-test-apk --trailmap`. A survey
file that does not build for the sandbox does not fail the APK build; `ctx.surveys` on the device
reports why to a trail that asks for one of its surveys. Surveys in the workspace-wide
`<config>/surveys/` are not packaged.

## What a session exposes

`session.summary` is the verdict and identity: outcome, platform, app id and version, trail id,
title, duration, device classifiers, CI job id, and which captures are present.

Each of the following is a queryable collection with `find`, `findLast`, `filter`, `has`,
`count`, `all`, and iteration:

| Collection | Source | Query fields |
|---|---|---|
| `session.network` | `network.ndjson`, with request and response bodies filled in from any in-app network stream under `events/` | `method`, `url`, `host`, `path`, `status` (`number`, `"ok"`, `"error"`, `"failed"`, or predicate), `requestBody`, `responseBody`, `requestHeaders`, `responseHeaders`, `where` |
| `session.analytics` | every `events/*analytic*.ndjson` stream, normalized to `{name, source, properties}` | `name`, `source`, `stream`, `properties` (subset match), `contains`, `where` |
| `session.events` | every `events/*.ndjson` stream, raw | `stream`, `data` (subset match), `contains`, `where` |
| `session.tools` | `TrailblazeToolLog` records | `name`, `successful`, `args` (subset match), `deviceName`, `where` |
| `session.objectives` | paired `ObjectiveStart`/`ObjectiveComplete` records | `prompt`, `status`, `where` |
| `session.trailblazeLogs` | every record Trailblaze logged (not the device log); `ofType("TrailblazeLlmRequestLog")` narrows it, typed | `type` (short class name), `contains`, `where` |
| `session.screenText` | the strings each capture log recorded (`visibleStrings`); older sessions fall back to `visible-strings.ndjson` | `text`, `source`, `captureId`, `visible`, `where` |
| `session.deviceLog` | `device.log` / `logcat.txt` | `text` |
| `session.trace` | `trace.json` spans | `name`, `category`, `where` |

Matching rules are uniform: a string is an exact match on identifiers (`path`, `name`, `method`)
and a case-insensitive substring on free text (`contains`, bodies, screen text, device log); a
`RegExp` is tested; a function is a predicate. `session.stream("crash")` returns one raw stream,
and `session.readJson` / `session.readText` reach any other file in the directory.

Every verb also takes a plain function of the record: `session.network.filter((r) => (r.status ?? 0) >= 500)`.

Trailblaze's own logs are typed from the Kotlin classes that write them. `ofType` narrows them to
one or more types, so their fields need no cast:

```ts
const failed = session.trailblazeLogs.ofType("TrailblazeToolLog").filter((r) => !r.log.successful);
const slowLlm = session.trailblazeLogs.ofType("TrailblazeLlmRequestLog").filter((r) => r.log.durationMs > 10_000);
```

For tool calls, `session.tools` is simpler still: `session.tools.filter({ successful: false })`.

Name the shape a stream's rows have to read them typed. It is a cast, not a check, so a union
narrowed on a field the rows always carry is safer than trusting every field:

```ts
type CheckoutStep = { type: "started"; cart: string } | { type: "paid"; amountCents: number };

export const checkoutPaid = trailblaze.survey({ description: "Checkout was paid" }, (session) => {
  for (const step of session.stream<CheckoutStep>("checkoutSteps")) {
    if (step.data.type === "paid") return { summary: `Paid ${step.data.amountCents}¢`, evidence: [step] };
  }
});
```

Every record is already an `SessionRecord`: it knows its file and line, so returning it as evidence
gives the report a clickable location.

## Running

```bash
# from anywhere inside a workspace: every survey it carries, over ~/.trailblaze/logs
bun sdks/typescript/src/surveys/cli.ts --target acme

# over a downloaded batch, JSON to a file
bun sdks/typescript/src/surveys/cli.ts --target acme ./sessions --out report.json
```

`--sessions` accepts session directories, directories of sessions, session zips as CI uploads
them, or directories of zips (extracted to a temp dir, or `--extract-dir`). `--format` is
`markdown` (default), `summary` (one line per session), `json`, or `none`; `--out` always writes
the full JSON report. `--survey <name>` and `--tag` narrow which surveys run; `--platform`, `--device` (a device
classifier such as `iphone`, `ipad`, `tablet`), `--app-id`, `--target`, and `--outcome` narrow
which sessions are analysed, judged on each session's own summary rather than on where the files
came from. The workspace is found the way the trailblaze CLI finds it (the closest
`trailblaze-config/` or `trails/config/` holding a `trailblaze.yaml`, or `TRAILBLAZE_CONFIG_DIR`),
and its trailmaps make `--target` and survey `targets` cover a target's app ids. `--target` also
drops surveys scoped to other targets. `--surveys` and `--trailmaps` replace the workspace with
explicit paths. The process exits
non-zero if any survey threw, and each throw is recorded against the session it happened on
rather than aborting the batch.

Programmatic use is the same surface the CLI is built on:

```ts
import { runSurveys, findWorkspaceConfigDir, loadWorkspace, discoverSessions, renderMarkdown } from "@trailblaze/scripting/surveys";

const workspace = await loadWorkspace(findWorkspaceConfigDir()!);
const surveys = workspace.surveys.map((c) => c.definition);
const sessions = await discoverSessions(["./sessions"]);
const targets = workspace.targets;
const report = await runSurveys({ surveys, sessions, targets, where: (s) => s.platform === "ios" });
console.log(renderMarkdown(report));
```

## Report shape

`SurveyReport` has one `sessions[]` entry per readable session with its summary, findings,
skipped surveys, and errors; a `surveys[]` row per survey (name, description, the number of
sessions it found something in and which ones, and its findings count); and `unreadable[]` for
inputs that were not session directories. A finding is `{ survey, session, summary, evidence }`
plus its `data`, when it has any.

## Session snapshots

`sdks/typescript/src/surveys/snapshot/cli.ts` packs a batch of sessions into snapshots, so surveys
can run over them without the original session directories:

```bash
bun sdks/typescript/src/surveys/snapshot/cli.ts --out snapshots/ <sessions-dir|zip>...
```

Each session is written as the in-memory model a handler reads, gzipped: every log record, stream
row, request, analytics event and screen string, minus the bytes no survey reads — the view tree
and LLM conversation on a log record, response bodies, request bodies over 32 KB, and the app's own
copy of its network capture. Snapshots are typically more than a hundred times smaller than the
sessions they come from. A run fetches one session at a time, runs every survey over it and lets it
go, so only the findings are held; a session no survey in the run applies to is never fetched.

**What a snapshot drops refuses to be read.** Reading a response body or a view tree throws by name
rather than returning nothing, so a survey cannot report "this was never exercised" about records
the snapshot never had. A survey that matches screens against view trees (waypoints) needs the
session directories.

## Writing good surveys

- Key on the request that commits the action (the checkout-complete call, the install), then
  attach the app's own analytics event as corroboration. Polling and prefetch requests fire in
  sessions that never used the feature.
- Parse the amount, add-on name, or role out of the body into `summary` and `data`. A report
  that says *which* add-on was installed is worth far more than one that says an add-on was.
- One finding per occurrence. If two payments happened, report two findings.
- Prefer the function form when a check needs a join (find the checkout that preceded this
  completion); prefer the rule form when it is a bare conjunction.
