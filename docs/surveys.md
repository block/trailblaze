# Session Surveys

This is fingerprinting for sessions: every feature leaves a footprint, and surveys recognize
them. A **survey** is a small TypeScript function that reads one finished session and says
whether a product feature was exercised, pointing at the records that prove it. Its answer is a
**finding**: *"Completed a $5.00 CARD payment, evidenced by `network.ndjson:411` and
`events/analytics.ndjson:1531`"*. Run a set of surveys over a set of sessions and you get the
findings per session plus a report of features across the whole batch.

Surveys do not run during a trail. They are post-hoc analysis over the session directory the
runner writes (or the zip CI uploads), so adding one never changes how a trail executes.

## Defining a survey

Surveys follow the shape of `trailblaze.tool()`: a spec plus a function, exported from a
`*.survey.ts` file. The export name becomes the survey's id in reports.

```ts
import { trailblaze } from "@trailblaze/scripting/surveys";

export const paymentCompleted = trailblaze.survey(
  { feature: "checkout.payment-completed", description: "Completed a payment", appIds: ["com.example.pos"] },
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

Return one finding, an array of findings, or nothing. Every finding carries a human summary, the
evidence records, and optional `data` for downstream tooling.

When a feature is "these things all happened", describe its footprint instead of writing a
handler:

```ts
export const signedIn = trailblaze.survey({
  feature: "auth.signed-in",
  footprint: {
    summary: "Signed in with email and password",
    network: { method: "POST", path: /\/v1\/auth\/login$/, status: "ok" },
    analytics: { name: "Login" },
    absent: { network: { path: "/v1/auth/logout" } },
  },
});
```

Every listed query must match at least one record; anything under `absent` must match none.
The evidence is the first match of each query (`evidence: "all"` keeps every match).

### One survey, many features

When you have a catalog of features rather than one, describe every footprint in a single
survey and let it report each feature it recognizes. The keys are the feature ids the report
groups by; the survey's own `feature` is only a family name for it:

```ts
export const catalog = trailblaze.survey({
  feature: "pos.catalog",
  description: "Footprints of the product's feature catalog",
  appIds: ["com.example.pos"],
  footprints: {
    refunds: { summary: "Issued a refund", network: { method: "POST", path: "/v1/refunds", status: "ok" } },
    "clock-in-out": { network: { path: /\/v1\/timecards\/(start|stop)$/, status: "ok" }, evidence: "all" },
    "split-checks": { analytics: { name: "checks_split" } },
  },
});
```

Every declared key is a report row, so a feature no session exercised reads `0/N` instead of
disappearing. A handler can do the same by setting `feature` on each finding it returns; list
what it may report in `features` so those are report rows too, and use
`matchFootprint(session, spec)` to evaluate declarative footprints alongside computed ones.

Not every match is a feature. A finding's `kind` (defaulting to the survey's, then
`"feature"`) says which axis the id lives on, so one run can report catalog features next to
jobs-to-be-done or gaps a catalog has no row for; the report shows the kind wherever it is not
`feature`.

### Coverage and diagnosis

A survey is for one of two things, and its kind says which (`purposeOf(kind)`):

- **Coverage** says what a run exercised: the features it used, the screens it passed, the gaps a
  catalog has no row for. These answer "which features does this trail use?" and "which trails
  use this feature?". Kinds `feature`, `waypoint`, `gap`, and any kind a survey invents.
- **Diagnosis** says how the run went: why it failed (`diagnosis`), something that happened to it
  such as a crash, server errors or the LLM stepping in (`signal`), and where its time went
  (`measure`).

Give a survey about the run itself one of the diagnosis kinds, so its findings are not listed as
features the trail covers.

### Scoping

`platforms`, `appIds`, and `targets` on the spec skip sessions the survey was never meant for,
so an iOS payments survey is not blamed for silence on an Android dashboard run. `tags` lets
the CLI run a subset.

Prefer `targets` for "this is a feature of app X": a target is what the trailmap defines, and it
already lists the app ids of every build of that app on every platform. Pass the workspace's
trailmaps to a run (`--trailmaps`, or `targets` on `runSurveys`) and a session counts as the
target's when its trail named the target *or* it drove one of the target's app ids, so sessions
run without a target are still placed. Use `appIds` when a footprint is specific to one build,
such as a debug-only bundle.

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
| `session.logs` | every log record | `type` (short class name), `contains`, `where` |
| `session.screenText` | `visible-strings.ndjson` | `text`, `source` |
| `session.deviceLog` | `device.log` / `logcat.txt` | `text` |
| `session.trace` | `trace.json` spans | `name`, `category`, `where` |

Matching rules are uniform: a string is an exact match on identifiers (`path`, `name`, `method`)
and a case-insensitive substring on free text (`contains`, bodies, screen text, device log); a
`RegExp` is tested; a function is a predicate. `session.stream("crash")` returns one raw stream,
and `session.readJson` / `session.readText` reach any other file in the directory.

Every record is already an `SessionRecord`: it knows its file and line, so returning it as evidence
gives the report a clickable location.

## Running

```bash
bun sdks/typescript/src/surveys/cli.ts \
  --surveys path/to/surveys \
  --sessions ~/.trailblaze/logs \
  --format markdown --out report.json
```

`--sessions` accepts session directories, directories of sessions, session zips as CI uploads
them, or directories of zips (extracted to a temp dir, or `--extract-dir`). `--format` is
`markdown` (default), `summary` (one line per session), `json`, or `none`; `--out` always writes
the full JSON report. `--feature` and `--tag` narrow which surveys run; `--platform`, `--device` (a device
classifier such as `iphone`, `ipad`, `tablet`), `--app-id`, `--target`, and `--outcome` narrow
which sessions are analysed, judged on each session's own summary rather than on where the files
came from. `--trailmaps <dir>` loads the target catalog that makes `--target` and survey
`targets` cover a target's app ids. The process exits
non-zero if any survey threw, and each throw is recorded against the session it happened on
rather than aborting the batch.

Programmatic use is the same surface the CLI is built on:

```ts
import { runSurveys, loadSurveys, loadTargetCatalog, discoverSessions, renderMarkdown } from "@trailblaze/scripting/surveys";

const surveys = (await loadSurveys(["./surveys"])).map((c) => c.definition);
const sessions = await discoverSessions(["./sessions"]);
const targets = loadTargetCatalog(["./trailmaps"]);
const report = await runSurveys({ surveys, sessions, targets, where: (s) => s.platform === "ios" });
console.log(renderMarkdown(report));
```

## Report shape

`SurveyReport` has one `sessions[]` entry per readable session with its summary, findings,
skipped surveys, and errors; a `features[]` roll-up (feature id, kind, surveys, the number of
sessions it matched and which ones) covering every feature a survey declared or reported; and
`unreadable[]` for inputs that were not session directories.

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
- Prefer the function form when a feature needs a join (find the checkout that preceded this
  completion); prefer the rule form when it is a bare conjunction.
