import { describe, expect, test } from 'bun:test';
import {
  arrangeLanes,
  bandAt,
  bandClock,
  bandY,
  buildSummaryModel,
  cardBeside,
  defaultLaneIds,
  describeError,
  emptyPreference,
  foldBursts,
  isLaneVisible,
  laneIdentity,
  layoutBand,
  momentsOf,
  noteWindow,
  previewTop,
  readArgsYaml,
  screenAt,
  screenBeside,
  searchNotes,
  stepMoment,
  type StreamNote,
  type StreamStep,
  type StreamsModel,
} from './run-report-summary-model';

const METRICS = { noteHeight: 20, padding: 4 };
const note = (t: number, label = 'n', extra: Partial<StreamNote> = {}): StreamNote => ({ t, label, badges: [], fields: [], ...extra });
const step = (startMs: number, endMs: number): StreamStep => ({ kind: 'step', number: 1, title: 's', startMs, endMs, status: 'ok' });

// Payloads are partial on purpose: the model reads only what it needs.
const session = (parts: Record<string, unknown>): SessionPayload => parts as unknown as SessionPayload;
const build = (parts: Record<string, unknown>, events: unknown[] | null = null, shots: string[] = []) =>
  buildSummaryModel({ session: session(parts), events: events as EventStream[] | null, hasShot: (file) => shots.includes(file) });
const stream = (name: string, events: Array<{ t: number; d: string }>) => ({ name, events });

const TRACE = [
  { label: 'Open the app', ts: 1000, ms: 0, ok: true, objective: true, trailhead: true },
  { label: 'tapOn', ts: 1200, ms: 300, ok: true, tool: 'tap', screenshotFile: 'a.webp', shotEndTs: 1500 },
  { label: 'Check the total', ts: 3000, ms: 0, ok: true, objective: true },
  { label: 'assertVisible', ts: 3100, ms: 50, ok: true, screenshotFile: 'b.webp', shotEndTs: 3200 },
];

describe('steps and bands', () => {
  test('each objective opens a band that runs to the next one', () => {
    const model = build({ trace: TRACE }, null, ['a.webp', 'b.webp']);
    expect(model.steps.map((s) => [s.kind, s.title, s.startMs, s.endMs])).toEqual([
      ['trailhead', 'Open the app', 1000, 3000],
      ['step', 'Check the total', 3000, 3200],
    ]);
    expect(model.steps[1].number).toBe(1);
  });

  test('tool rows land in the band that contains them', () => {
    const model = build({ trace: TRACE });
    const tools = model.lanes.find((lane) => lane.id === 'tools')!;
    expect(tools.bands.map((notes) => notes.map((n) => n.label))).toEqual([['tap'], ['assert']]);
  });

  test('a step shows the last screen captured inside its band', () => {
    const model = build({ trace: TRACE }, null, ['a.webp', 'b.webp']);
    expect(model.steps.map((s) => s.shot)).toEqual(['a.webp', 'b.webp']);
  });

  test('a screenshot the report does not carry is left out', () => {
    const model = build({ trace: TRACE }, null, ['a.webp']);
    expect(model.shots.map((shot) => shot.file)).toEqual(['a.webp']);
  });

  test('a frame is dated by when its capture ended, not when the action began', () => {
    const model = build({ trace: TRACE }, null, ['a.webp', 'b.webp']);
    expect(screenAt(model, 1400)?.file).toBe('a.webp');
    expect(screenAt(model, 1500)?.file).toBe('a.webp');
    expect(screenAt(model, 3300)?.file).toBe('b.webp');
    // Before any capture, the first screen stands in.
    expect(screenAt(model, 0)?.file).toBe('a.webp');
  });

  test('a recorded attempt that was self-healed reads as recovered and its retry repeats the number', () => {
    const model = build({
      trace: [
        { label: 'Pick a tip', ts: 0, ms: 0, ok: false, objective: true, selfHeal: true, selfHealError: 'nope' },
        { label: 'Pick a tip', ts: 500, ms: 0, ok: true, objective: true },
      ],
    });
    expect(model.steps.map((s) => [s.kind, s.status, s.number])).toEqual([['step', 'recovered', 1], ['heal', 'ok', 1]]);
  });

  test('a same-named objective after a self-heal that succeeded is its own step', () => {
    const model = build({
      trace: [
        { label: 'Pick a tip', ts: 0, ms: 0, ok: true, objective: true, selfHeal: true },
        { label: 'Pick a tip', ts: 500, ms: 0, ok: true, objective: true },
      ],
    });
    expect(model.steps.map((s) => [s.kind, s.status, s.number])).toEqual([['step', 'recovered', 1], ['step', 'ok', 2]]);
  });

  test('the run ends where its last folded dispatch does, and the scrubber reaches that frame', () => {
    const model = build({
      trace: [
        { label: 'Go', ts: 0, ms: 0, ok: true, objective: true },
        {
          label: 'batch', ts: 100, ms: 50, ok: true, tool: 'batch',
          children: [
            { label: 'tapOn', ts: 120, ms: 100, screenshotFile: 'a.webp', shotEndTs: 230 },
            { label: 'never ran', ts: null, ms: null },
            { label: 'assert', ts: 400, ms: 100, screenshotFile: 'b.webp', shotEndTs: 520 },
          ],
        },
      ],
    }, null, ['a.webp', 'b.webp']);
    expect(model.steps[0].endMs).toBe(520);
    expect(screenAt(model, 600)?.file).toBe('b.webp');
  });

  test('folded dispatches from an older payload, with no timestamps of their own, show their screens in the order they ran', () => {
    const model = build({
      trace: [
        { label: 'Go', ts: 0, ms: 0, ok: true, objective: true },
        {
          label: 'batch', ts: 100, ms: 1000, ok: true, tool: 'batch',
          children: [
            { label: 'tapOn', ms: 600, screenshotFile: 'a.webp' },
            { label: 'assert', ms: 100, screenshotFile: 'b.webp' },
          ],
        },
      ],
    }, null, ['a.webp', 'b.webp']);
    expect(screenAt(model, 600)?.file).toBe('a.webp');
    expect(screenAt(model, 1100)?.file).toBe('b.webp');
  });

  test('a run with notes but no objectives still gets one band', () => {
    const model = build({ trace: [] }, [stream('analytics.feed', [{ t: 50, d: '{"name":"hello"}' }])]);
    expect(model.steps).toHaveLength(1);
    expect(model.lanes[0].bands[0].map((n) => n.label)).toEqual(['hello']);
  });

  test('bandAt picks the last step that started at or before the moment', () => {
    const model = build({ trace: TRACE });
    expect([bandAt(model, 999), bandAt(model, 1000), bandAt(model, 2999), bandAt(model, 3000), bandAt(model, 9999)]).toEqual([0, 0, 0, 1, 1]);
  });
});

describe('failure diagnosis', () => {
  const failing = {
    meta: { status: 'failed', error: 'Element not found for selector: "^Pay$" after 5000ms' },
    trace: [
      { label: 'Open', ts: 0, ms: 0, ok: true, objective: true },
      { label: 'Pay', ts: 1000, ms: 0, ok: false, objective: true, err: 'Element not found for selector: "^Pay$" after 5000ms' },
    ],
  };

  test('a passed run has no diagnosis', () => {
    expect(build({ ...failing, meta: { status: 'passed' } }).diagnosis).toBeUndefined();
  });

  test('a failed objective is named with the plain cause', () => {
    const { problems } = build(failing).diagnosis!;
    expect(problems).toHaveLength(1);
    expect(problems[0].step?.title).toBe('Pay');
    expect(problems[0].step?.bandIndex).toBe(1);
    expect(problems[0].summary).toContain('not found');
  });

  test('a failed run whose objectives passed is anchored on the first failed tool row', () => {
    const model = build({
      meta: { status: 'failed', error: 'boom' },
      trace: [
        { label: 'Open', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'tapOn', ts: 100, ms: 10, ok: false, err: 'boom' },
      ],
    });
    expect(model.steps[0].status).toBe('failed');
    expect(model.diagnosis?.problems[0].summary).toBe('boom');
  });

  test('a self-heal that succeeded is not failed by a later step failing', () => {
    const model = build({
      meta: { status: 'failed' },
      trace: [
        { label: 'Pick a tip', ts: 0, ms: 0, ok: true, objective: true, selfHeal: true, selfHealError: 'recording missed' },
        { label: 'Pay', ts: 500, ms: 0, ok: false, objective: true, err: 'declined' },
      ],
    });
    expect(model.steps.map((s) => s.status)).toEqual(['recovered', 'failed']);
    expect(model.diagnosis?.problems.map((p) => p.summary)).toEqual(['declined']);
  });

  test('a self-heal that failed with no retry is the failed step', () => {
    const model = build({
      meta: { status: 'failed' },
      trace: [{ label: 'Pick a tip', ts: 0, ms: 0, ok: false, objective: true, selfHeal: true, selfHealError: 'nope' }],
    });
    expect(model.steps[0].status).toBe('failed');
  });

  test('end-of-run failure rows after a clean teardown belong to the trail, not the teardown', () => {
    const model = build({
      meta: { status: 'failed', error: 'boom' },
      trace: [
        { label: 'Open', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'Teardown', ts: 100, ms: 0, ok: true, objective: true, teardown: true },
        { label: 'Failure screenshot', ts: 200, ms: 0, ok: false, terminal: true, endOfRun: true },
        { label: 'Error', ts: 200, ms: 0, ok: false, terminal: true, endOfRun: true, err: 'boom' },
      ],
    });
    expect(model.steps.map((s) => [s.kind, s.status])).toEqual([['step', 'failed'], ['teardown', 'ok']]);
    expect(model.diagnosis?.problems[0].step).toMatchObject({ kind: 'step', title: 'Open' });
  });

  test('a failed teardown does not displace the trail step whose tool failed', () => {
    const model = build({
      meta: { status: 'failed', error: 'boom' },
      trace: [
        { label: 'Pay', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'tapOn', ts: 100, ms: 10, ok: false, err: 'boom' },
        { label: 'Teardown', ts: 200, ms: 0, ok: false, objective: true, teardown: true, err: 'cleanup failed' },
        { label: 'clearData', ts: 300, ms: 10, ok: false, err: 'cleanup failed' },
      ],
    });
    expect(model.steps.map((s) => [s.kind, s.status])).toEqual([['step', 'failed'], ['teardown', 'failed']]);
    expect(model.diagnosis?.problems.map((p) => [p.step?.title, p.summary])).toEqual([['Pay', 'boom']]);
  });

  test('a run that failed only in teardown is diagnosed there', () => {
    const model = build({
      meta: { status: 'failed' },
      trace: [
        { label: 'Pay', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'Teardown', ts: 200, ms: 0, ok: false, objective: true, teardown: true, err: 'cleanup failed' },
      ],
    });
    expect(model.diagnosis?.problems.map((p) => [p.step?.kind, p.summary])).toEqual([['teardown', 'cleanup failed']]);
  });

  test('a crash after passing steps is the diagnosis, not a tolerated tool error before it', () => {
    const model = build({
      meta: { status: 'failed', failureCode: 'app_crashed', error: 'The app crashed' },
      trace: [
        { label: 'Pay', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'tapOn', ts: 100, ms: 10, ok: false, err: 'tolerated' },
      ],
    });
    expect(model.steps[0].status).toBe('ok');
    expect(model.diagnosis?.problems.map((p) => [p.step?.kind, p.summary])).toEqual([['end', 'The app crashed']]);
  });

  test('a crash is the diagnosis even when only teardown failed', () => {
    const model = build({
      meta: { status: 'failed', failureCode: 'app_crashed', error: 'The app crashed' },
      trace: [
        { label: 'Pay', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'Teardown', ts: 200, ms: 0, ok: false, objective: true, teardown: true, err: 'cleanup failed' },
      ],
    });
    expect(model.diagnosis?.problems.map((p) => [p.step?.kind, p.summary])).toEqual([['end', 'The app crashed']]);
  });

  test('a failed run that shows no failed step still explains itself', () => {
    const model = build({ meta: { status: 'failed' }, trace: [{ label: 'Open', ts: 0, ms: 0, ok: true, objective: true }] });
    expect(model.diagnosis?.problems[0].summary).toBe('All steps passed, but the run failed');
    expect(model.diagnosis?.problems[0].step?.kind).toBe('end');
  });

  test('only the last few distinct problems are kept and the rest are counted', () => {
    const trace = Array.from({ length: 5 }, (_, index) => ({ label: `Step ${index}`, ts: index * 100, ms: 0, ok: false, objective: true, err: `failure ${index}` }));
    const { problems, hidden } = build({ meta: { status: 'failed' }, trace }).diagnosis!;
    expect(problems.map((p) => p.summary)).toEqual(['failure 2', 'failure 3', 'failure 4']);
    expect(hidden).toBe(2);
  });
});

describe('describeError', () => {
  test('unwraps the nested call and names the tool that failed', () => {
    const result = describeError("Host-local tool execution failed: client.callTool('tapOn') failed: Element not found for selector: \"^Go$\" after 3000ms");
    expect(result.via).toBe('tapOn');
    expect(result.summary).toBe('"Go" not found');
  });

  test('reads an agent call limit', () => {
    expect(describeError('Max LLM calls limit of 1 reached').summary).toBe('Gave up after 1 LLM call');
    expect(describeError('Max LLM calls limit reached (30)').summary).toBe('Gave up after 30 LLM calls');
  });

  test('falls back to the first sentence', () => {
    expect(describeError('Something odd happened. More detail follows.').summary).toBe('Something odd happened');
  });

  test('long text is cut at a word break', () => {
    const { summary } = describeError(`${'word '.repeat(80)}end`);
    expect(summary.length).toBeLessThanOrEqual(200);
    expect(summary.endsWith('…')).toBe(true);
  });
});

describe('lane identity', () => {
  test('known streams get friendly names', () => {
    expect(laneIdentity('memory')).toEqual({ id: 'memory', name: 'Memory' });
    expect(laneIdentity('com.acme.plugin.visible-strings')).toEqual({ id: 'visible-strings', name: 'Visible strings' });
    expect(laneIdentity('network.phone')).toEqual({ id: 'network.phone', name: 'Network (phone)' });
    expect(laneIdentity('acme.plugin.network')).toEqual({ id: 'network', name: 'Network' });
  });

  test('any other stream is named by its last segment, in words', () => {
    expect(laneIdentity('acme.plugin.pageViews')).toEqual({ id: 'acme.plugin.pageViews', name: 'Page views' });
    expect(laneIdentity('push_notifications')).toEqual({ id: 'push_notifications', name: 'Push notifications' });
  });
});

describe('tools and LLM lanes', () => {
  test('a tap reads as a verb and the selector text it targets', () => {
    const model = build({
      trace: [
        { label: 'Go', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'tapOnElementBySelector', ts: 10, ms: 20, ok: true, args: '- tapOnElementBySelector:\n    nodeSelector:\n      androidAccessibility:\n        textRegex: "^Save$"' },
      ],
    });
    const [tap] = model.lanes.find((lane) => lane.id === 'tools')!.bands[0];
    expect(tap.label).toBe('tap');
    expect(tap.detail).toBe('Save');
    expect(tap.body).toContain('nodeSelector');
  });

  test('an unknown tool reads as spaced words', () => {
    const model = build({
      trace: [
        { label: 'Go', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'android_sendBroadcastIntent', ts: 10, ms: 1, ok: true },
      ],
    });
    expect(model.lanes[0].bands[0][0].label).toBe('send broadcast intent');
  });

  test('a failed tool row is marked as an error', () => {
    const model = build({ trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }, { label: 'tapOn', ts: 10, ms: 1, ok: false, err: 'bad' }] });
    expect(model.lanes[0].bands[0][0].tone).toBe('error');
  });

  test('model rows go to the LLM lane with their cost and tokens', () => {
    const model = build({
      trace: [
        { label: 'Go', ts: 0, ms: 0, ok: true, objective: true },
        { label: 'agent step', ts: 10, ms: 400, ok: true, llm: 0 },
      ],
      llm: [{ model: 'm-1', inputTokens: 1200, outputTokens: 30, totalCost: 0.0123, response: [{ tool: 'tapOn', args: '{"selector":{"text":"Ok"}}' }] }],
    });
    const [call] = model.lanes.find((lane) => lane.id === 'llm')!.bands[0];
    expect(call.label).toBe('tap');
    expect(call.fields.find((field) => field.k === 'Tokens')?.v).toBe('1,200 in · 30 out');
    expect(call.fields.find((field) => field.k === 'Cost')?.v).toBe('$0.0123');
  });

  test('terminal rows are not drawn as tool notes', () => {
    const model = build({ trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }, { label: 'end', ts: 5, ms: 0, ok: true, terminal: true }] });
    expect(model.lanes.find((lane) => lane.id === 'tools')).toBeUndefined();
  });
});

describe('readArgsYaml', () => {
  test('reads nested maps, lists and quoted scalars', () => {
    expect(readArgsYaml('- tapOn:\n    selector:\n      text: "Hi there"\n    index: 2\n    flags:\n      - a\n      - b')).toEqual([
      { tapOn: { selector: { text: 'Hi there' }, index: 2, flags: ['a', 'b'] } },
    ]);
  });

  test('refuses what it cannot read', () => {
    expect(() => readArgsYaml('- tapOn: |\n    text')).toThrow();
  });
});

describe('event stream lanes', () => {
  const base = { trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }] };

  test('JSON events become notes labeled by their name, scalars become fields', () => {
    const model = build(base, [stream('acme.plugin.pageViews', [{ t: 100, d: '{"name":"Home","screen":"main","nested":{"a":1}}' }])]);
    const lane = model.lanes.find((l) => l.id === 'acme.plugin.pageViews')!;
    const [event] = lane.bands[0];
    expect(lane.name).toBe('Page views');
    expect(event.label).toBe('Home');
    expect(event.fields).toEqual([{ k: 'screen', v: 'main' }]);
    expect(event.body).toContain('"a": 1');
  });

  test('table-shaped events are labeled from their column items', () => {
    const model = build(base, [stream('acme.table', [{ t: 5, d: '{"columnItems":{"Event":"Tapped","Detail":"x"}}' }])]);
    expect(model.lanes[0].bands[0][0].label).toBe('Tapped');
  });

  test('plain text events keep their text', () => {
    const model = build(base, [stream('acme.log', [{ t: 5, d: 'just words' }])]);
    expect(model.lanes[0].bands[0][0].label).toBe('just words');
  });

  test('events on a device clock are not placed', () => {
    const events = { name: 'acme.log', events: [{ t: 5, d: 'a', clock: 'device' }, { t: 6, d: 'b' }] };
    expect(build(base, [events]).lanes[0].bands[0].map((n) => n.label)).toEqual(['b']);
  });

  test('a stream that carries formatted rows uses them as notes', () => {
    const model = build(base, [{ name: 'acme.rows', events: [], rows: [{ t: 7, label: 'Row one', badges: [{ text: 'ok', tone: 'ok' }], fields: [{ k: 'A', v: 'b' }], tone: 'error' }] }]);
    const [row] = model.lanes[0].bands[0];
    expect(row).toMatchObject({ label: 'Row one', tone: 'error', badges: [{ text: 'ok', tone: 'ok' }], fields: [{ k: 'A', v: 'b' }] });
  });

  test('a stream with no placeable events adds no lane', () => {
    expect(build(base, [stream('acme.empty', [])]).lanes).toEqual([]);
  });

  test('two streams that share a lane id share the lane', () => {
    const model = build(base, [stream('a.network', []), stream('x.plugin.memory', [])]);
    expect(model.lanes).toEqual([]);
    const merged = build(base, [stream('one.plugin.log', [{ t: 1, d: 'a' }]), stream('one.plugin.log', [{ t: 2, d: 'b' }])]);
    expect(merged.lanes).toHaveLength(1);
    expect(merged.lanes[0].total).toBe(2);
  });
});

describe('network lane', () => {
  const base = { trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }] };
  const flat = (t: number, value: unknown) => ({ t, d: JSON.stringify(value) });

  test('a request and its response become one note at the request time', () => {
    const model = build(base, [
      stream('network', [
        flat(100, { id: 'r1', method: 'GET', url: 'https://api.example.test/v1/items?x=1' }),
        flat(180, { id: 'r1', phase: 'RESPONSE_END', statusCode: 200, durationMs: 80 }),
      ]),
    ]);
    const [call] = model.lanes[0].bands[0];
    expect(model.lanes[0].total).toBe(1);
    expect(call.t).toBe(100);
    expect(call.label).toBe('GET /v1/items?x=1');
    expect(call.badges).toEqual([{ text: '200', tone: 'ok' }, { text: '80ms' }]);
  });

  test('an error status marks the call', () => {
    const model = build(base, [
      stream('network', [flat(1, { id: 'r', method: 'POST', url: 'https://h.test/a' }), flat(2, { id: 'r', phase: 'RESPONSE_END', statusCode: 503 })]),
    ]);
    expect(model.lanes[0].bands[0][0].tone).toBe('error');
    expect(model.lanes[0].bands[0][0].badges[0]).toEqual({ text: '503', tone: 'error' });
  });

  test('a reused id opens a second call', () => {
    const model = build(base, [
      stream('network', [
        flat(1, { id: 'r', method: 'GET', url: 'https://h.test/a' }),
        flat(2, { id: 'r', phase: 'RESPONSE_END', statusCode: 200 }),
        flat(3, { id: 'r', method: 'GET', url: 'https://h.test/b' }),
      ]),
    ]);
    expect(model.lanes[0].bands[0].map((n) => n.label)).toEqual(['GET /a', 'GET /b']);
  });

  test('enum-wrapped events are read too', () => {
    const model = build(base, [
      stream('network.phone', [
        flat(1, { request: { _0: { id: 'q', method: 'GET', url: 'https://h.test/z' } } }),
        flat(2, { response: { _0: { id: 'q', statusCode: 404 } } }),
      ]),
    ]);
    expect(model.lanes[0].id).toBe('network.phone');
    expect(model.lanes[0].bands[0][0].badges[0].text).toBe('404');
    expect(model.lanes[0].bands[0][0].fields).toContainEqual({ k: 'Device', v: 'phone' });
  });

  test('a stream that is not HTTP falls back to generic events', () => {
    const model = build(base, [stream('network', [flat(1, { hello: 'world' })])]);
    expect(model.lanes[0].bands[0]).toHaveLength(1);
  });
});

describe('memory lane', () => {
  const base = { trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }] };
  const reading = (t: number, row: Record<string, unknown>) => ({ t, d: JSON.stringify(row) });

  test('a tool before and after fold into one reading with the change', () => {
    const model = build(base, [
      stream('memory', [
        reading(10, { reason: 'before_tool', tool: 'tapOn', traceId: 'x', heapUsedKb: 50 * 1024 }),
        reading(20, { reason: 'after_tool', tool: 'tapOn', traceId: 'x', heapUsedKb: 60 * 1024 }),
      ]),
    ]);
    const [fold] = model.lanes[0].bands[0];
    expect(model.lanes[0].bands[0]).toHaveLength(1);
    expect(fold.t).toBe(20);
    expect(fold.label).toBe('60.0 MB');
    expect(fold.fields).toContainEqual({ k: 'Change', v: '+10.0 MB' });
    // The bar is drawn against a shared 64 MB-step scale.
    expect(fold.meter).toBeCloseTo(60 / 64, 5);
  });

  test('an exported report keeps the readings as formatted rows and still pairs them', () => {
    // The report export stores a formatted stream as rows whose raw payload is the original event.
    const row = (t: number, data: Record<string, unknown>) => ({ t, label: 'Heap', raw: [data] });
    const model = build(base, [{
      name: 'memory',
      events: [],
      rows: [
        row(10, { reason: 'before_tool', tool: 'tapOn', traceId: 'x', heapUsedKb: 50 * 1024 }),
        row(20, { reason: 'after_tool', tool: 'tapOn', traceId: 'x', heapUsedKb: 60 * 1024 }),
      ],
    }]);
    const [fold] = model.lanes[0].bands[0];
    expect(model.lanes[0].bands[0]).toHaveLength(1);
    expect(fold.label).toBe('60.0 MB');
    expect(fold.fields).toContainEqual({ k: 'Change', v: '+10.0 MB' });
    expect(fold.meter).toBeCloseTo(60 / 64, 5);
  });

  test('the app coming back is kept even when its memory could not be read', () => {
    const model = build(base, [
      stream('memory', [
        reading(10, { reason: 'first', pid: 1, heapUsedKb: 50 * 1024 }),
        reading(20, { reason: 'process_died' }),
        reading(30, { reason: 'process_started', pid: 2 }),
      ]),
    ]);
    const notes = model.lanes[0].bands[0];
    expect(notes.map((n) => n.label)).toEqual(['50.0 MB', 'App not running', 'App started']);
    expect(notes[2].fields).toContainEqual({ k: 'Pid', v: '2' });
  });

  test('reads that carried no memory figure collapse into one muted row naming the app', () => {
    // As a sampler watching an app other than the one the trail ran reports it: reads, no figures.
    const model = build(base, [
      stream('memory', [
        reading(5, { reason: 'first', appId: 'com.example.other', source: 'simulator', readMs: 3000 }),
        reading(10, { reason: 'before_tool', tool: 'tapOn', traceId: 'x', appId: 'com.example.other' }),
        reading(20, { reason: 'after_tool', tool: 'tapOn', traceId: 'x', appId: 'com.example.other', readMs: 2000 }),
      ]),
    ]);
    const notes = model.lanes[0].bands[0];
    expect(notes).toHaveLength(1);
    // At the first read around a tool, not the sampler's first, which can precede the run.
    expect(notes[0]).toMatchObject({ t: 10, label: 'No readings', tone: 'muted' });
    expect(notes[0].fields).toContainEqual({ k: 'Reads', v: '3' });
    expect(notes[0].fields).toContainEqual({ k: 'App', v: 'com.example.other' });
    expect(notes.some((n) => n.label === 'tapOn')).toBe(false);
  });

  test('a tool with no figure on either side is left out beside readings that have one', () => {
    const model = build(base, [
      stream('memory', [
        reading(5, { reason: 'first', footprintKb: 40 * 1024 }),
        reading(20, { reason: 'after_tool', tool: 'tapOn', traceId: 'x' }),
      ]),
    ]);
    expect(model.lanes[0].bands[0].map((n) => n.label)).toEqual(['40.0 MB']);
  });

  test('a dead process reads as an error with an empty bar', () => {
    const model = build(base, [stream('memory', [reading(5, { reason: 'process_died' })])]);
    expect(model.lanes[0].bands[0][0]).toMatchObject({ label: 'App not running', tone: 'error', meter: 0 });
  });
});

describe('capture notices lane', () => {
  const base = { trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }] };

  test('a burst of notices folds into one that lists the streams', () => {
    const events = ['a', 'b', 'c'].map((name) => ({ t: 5, d: JSON.stringify({ stream: `acme.plugin.${name}` }) }));
    const model = build(base, [stream('capture-notices', events)]);
    const [folded] = model.lanes[0].bands[0];
    expect(model.lanes[0].bands[0]).toHaveLength(1);
    expect(folded.label).toBe('3 streams not captured');
    expect(folded.entries?.map((entry) => entry.k)).toEqual(['a', 'b', 'c']);
  });

  test('a single notice keeps its reason and a safe link', () => {
    const model = build(base, [stream('capture-notices', [{ t: 5, d: JSON.stringify({ stream: 'acme.plugin.logs', message: 'off', documentation: 'https://docs.example.test/x' }) }])]);
    const [one] = model.lanes[0].bands[0];
    expect(one.label).toBe('Not captured');
    expect(one.fields).toContainEqual({ k: 'Reason', v: 'off' });
    expect(one.fields).toContainEqual({ k: 'Guide', v: 'https://docs.example.test/x', href: 'https://docs.example.test/x' });
  });
});

describe('visible strings lane', () => {
  const base = { trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }] };
  const screen = (timestamp: string, strings: unknown[], extra: Record<string, unknown> = {}) => ({ timestamp, deviceWidth: 100, deviceHeight: 200, strings, ...extra });

  test('a row whose capture saved a still but no screenshot still shows that screen', () => {
    const model = build({ trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }, { label: 'tapOn', ts: 100, ms: 50, ok: true, captureIds: ['cap1'] }] }, null, ['cap1.webp']);
    expect(model.shots.map((shot) => shot.file)).toEqual(['cap1.webp']);
  });

  test('a capture names its image as the Strings tab reads it, and keeps its own shape', () => {
    const at = '1970-01-01T00:00:00.500Z';
    const legacy = build({ ...base, visibleStrings: [screen(at, [], { captureId: 'old.png' })] }, null, ['old.png']);
    expect(legacy.shots.map((shot) => [shot.file, shot.aspect])).toEqual([['old.png', 0.5]]);
    const byUrl = build({ ...base, visibleStrings: [screen(at, [], { captureId: 'c1', captureUrl: 'shots/u.webp' })] }, null, ['u.webp']);
    expect(byUrl.shots.map((shot) => shot.file)).toEqual(['u.webp']);
    const still = build({ ...base, visibleStrings: [screen(at, [], { captureId: 'c2' })] }, null, ['c2.webp']);
    expect(still.shots.map((shot) => shot.file)).toEqual(['c2.webp']);
  });

  test('a capture\'s screenshot no trace row took is on the screen clock', () => {
    const model = build({ ...base, visibleStrings: [screen('1970-01-01T00:00:00.500Z', [], { screenshot: 'llm.webp' })] }, null, ['llm.webp']);
    expect(model.shots.map((shot) => shot.file)).toEqual(['llm.webp']);
    expect(screenAt(model, 600)?.file).toBe('llm.webp');
  });

  test('lists drawn strings, then accessibility-only ones, then off-screen ones', () => {
    const model = build({
      ...base,
      visibleStrings: [
        screen('1970-01-01T00:00:00.100Z', [
          { text: 'Total', source: 'text', bounds: [10, 60, 40, 80] },
          { text: 'Spoken only', source: 'contentDescription', bounds: [10, 90, 40, 110] },
          { text: 'Far below', source: 'text', visible: false, bounds: [10, 500, 40, 520] },
        ]),
      ],
    });
    const [captured] = model.lanes.find((lane) => lane.id === 'visible-strings')!.bands[0];
    expect(captured.label).toBe('1 string');
    expect(captured.entries?.map((entry) => [entry.k, entry.group])).toEqual([
      ['Total', undefined],
      ['Spoken only', 'Accessibility only'],
      ['Far below', 'Off screen'],
    ]);
    expect(captured.entries?.[0].boxes?.[0]).toEqual({ left: 0.1, top: 0.3, width: 0.3, height: 0.1 });
  });

  test('a web capture lists its strings but draws no box, since its bounds count from the top of the page', () => {
    const strings = [{ text: 'Checkout', source: 'text', bounds: [10, 1260, 40, 1280] }];
    const model = build({ ...base, meta: { platform: 'web' }, visibleStrings: [screen('1970-01-01T00:00:00.100Z', strings)] });
    const [captured] = model.lanes.find((lane) => lane.id === 'visible-strings')!.bands[0];
    expect(captured.entries?.map((entry) => entry.k)).toEqual(['Checkout']);
    expect(captured.entries?.[0].boxes ?? []).toEqual([]);
  });

  test('in a run that pairs a phone with a browser, each capture is read by its own device', () => {
    const trace = [
      { label: 'Go', ts: 0, ms: 0, ok: true, objective: true, device: 'phone' },
      { label: 'tapOnElement', ts: 50, ms: 10, ok: true, device: 'phone', screenshotFile: 'phone.webp' },
      { label: 'web_click', ts: 150, ms: 10, ok: true, device: 'browser', screenshotFile: 'page.webp' },
    ];
    // Both strings sit in the top 54 units: a phone's status bar, but a page's own header.
    const strings = [{ text: 'Top', source: 'text', bounds: [10, 10, 40, 30] }, { text: 'Body', source: 'text', bounds: [10, 100, 40, 120] }];
    const model = build({
      trace,
      meta: { platform: 'android' },
      visibleStrings: [
        screen('1970-01-01T00:00:00.100Z', strings, { screenshot: 'phone.webp' }),
        screen('1970-01-01T00:00:00.200Z', strings, { screenshot: 'page.webp' }),
      ],
    }, null, ['phone.webp', 'page.webp']);
    const [phone, page] = model.lanes.find((lane) => lane.id === 'visible-strings')!.bands[0];
    expect(phone.entries?.map((entry) => entry.k)).toEqual(['Body']);
    expect(phone.entries?.[0].boxes?.length).toBe(1);
    expect(page.entries?.map((entry) => entry.k)).toEqual(['Top', 'Body']);
    expect(page.entries?.every((entry) => (entry.boxes ?? []).length === 0)).toBe(true);
  });

  test('consecutive identical captures fold into one note with a count', () => {
    const strings = [{ text: 'Hello', source: 'text', bounds: [10, 60, 60, 80] }];
    const model = build({
      ...base,
      visibleStrings: [screen('1970-01-01T00:00:00.100Z', strings), screen('1970-01-01T00:00:00.200Z', strings)],
    });
    const notes = model.lanes[0].bands[0];
    expect(notes).toHaveLength(1);
    expect(notes[0].badges).toEqual([{ text: '×2' }]);
  });

  test('a capture whose state or hint changed keeps its own note, so the new value can be found', () => {
    const wifi = (state: string) => [
      { text: 'Wi-Fi', source: 'text', ref: 'r1', bounds: [10, 60, 60, 80] },
      { text: state, source: 'stateDescription', ref: 'r1' },
    ];
    const model = build({
      ...base,
      visibleStrings: [screen('1970-01-01T00:00:00.100Z', wifi('On')), screen('1970-01-01T00:00:00.200Z', wifi('Off'))],
    });
    expect(model.lanes[0].bands[0].map((note) => note.entries?.[0].v)).toEqual(['On', 'Off']);
  });

  test('a capture that may have been cut short keeps its strings and says it may be incomplete', () => {
    const strings = [{ text: 'Hello', source: 'text', bounds: [10, 60, 60, 80] }];
    const model = build({ ...base, visibleStrings: [screen('1970-01-01T00:00:00.100Z', strings, { partialCapture: true })] });
    const [note] = model.lanes[0].bands[0];
    expect(note.label).toBe('1 string');
    expect(note.entries?.map((entry) => entry.k)).toEqual(['Hello']);
    expect(note.badges).toEqual([{ text: 'Partial' }]);
    expect(note.fields).toContainEqual({ k: 'Capture', v: 'Partial: some strings on screen may be missing' });
  });

  test('an empty field counts its placeholder as a string on screen, and a filled one keeps it as a hint', () => {
    const empty = [{ text: 'Enter amount', source: 'hint', ref: 'f1', bounds: [10, 100, 200, 140] }];
    const filled = [
      { text: '12.00', source: 'text', ref: 'f1', bounds: [10, 100, 200, 140] },
      { text: 'Enter amount', source: 'hint', ref: 'f1', bounds: [10, 100, 200, 140] },
    ];
    const model = build({
      ...base,
      visibleStrings: [screen('1970-01-01T00:00:00.100Z', empty), screen('1970-01-01T00:00:00.200Z', filled)],
    });
    const [first, second] = model.lanes[0].bands[0];
    expect(first.label).toBe('1 string');
    expect(first.entries?.map((entry) => entry.k)).toEqual(['Enter amount']);
    expect(second.entries).toEqual([expect.objectContaining({ k: '12.00', v: 'Enter amount' })]);
  });

  test('a capture without a hierarchy is marked as an absence', () => {
    const model = build({ ...base, visibleStrings: [screen('1970-01-01T00:00:00.100Z', [])] });
    expect(model.lanes[0].bands[0][0]).toMatchObject({ label: 'No hierarchy', tone: 'muted' });
  });

  test('on iOS without the AXe driver, labels filed as content descriptions are the drawn text', () => {
    const strings = [
      { text: 'General', source: 'contentDescription', bounds: [10, 60, 60, 80] },
      { text: 'About', source: 'contentDescription', bounds: [10, 100, 60, 120] },
    ];
    const ios = build({ ...base, meta: { platform: 'ios' }, visibleStrings: [screen('1970-01-01T00:00:00.100Z', strings)] });
    const [captured] = ios.lanes[0].bands[0];
    expect(captured.label).toBe('2 strings');
    expect(captured.entries?.map((entry) => [entry.k, entry.group, entry.badge])).toEqual([['General', undefined, undefined], ['About', undefined, undefined]]);
    // Android keeps an icon's description apart from the text it draws, so there it stays spoken-only.
    const android = build({ ...base, meta: { platform: 'android' }, visibleStrings: [screen('1970-01-01T00:00:00.100Z', strings)] });
    expect(android.lanes[0].bands[0][0].label).toBe('0 strings');
  });

  test('the whole-screen root and status bar are not counted as strings', () => {
    const model = build({
      ...base,
      meta: { platform: 'android' },
      visibleStrings: [
        screen('1970-01-01T00:00:00.100Z', [
          { text: 'App', source: 'text', bounds: [0, 0, 100, 200] },
          { text: '9:41', source: 'text', bounds: [0, 0, 40, 40] },
          { text: 'Real', source: 'text', bounds: [10, 100, 60, 120] },
        ]),
      ],
    });
    expect(model.lanes[0].bands[0][0].entries?.map((entry) => entry.k)).toEqual(['Real']);
  });
});

describe('status bar text', () => {
  const base = { trace: [{ label: 'Go', ts: 0, ms: 0, ok: true, objective: true }] };
  const strings = [
    { text: 'Menu', source: 'text', bounds: [0, 0, 40, 40] },
    { text: 'Real', source: 'text', bounds: [10, 100, 60, 120] },
  ];
  const captured = (platform: string) => build({
    ...base,
    meta: { platform },
    visibleStrings: [{ timestamp: '1970-01-01T00:00:00.100Z', strings, deviceWidth: 100, deviceHeight: 200 }],
  }).lanes[0].bands[0][0].entries?.map((entry) => entry.k);

  test('a phone drops the top strip, where its status bar draws', () => {
    expect(captured('ios')).toEqual(['Real']);
  });

  test('a web page keeps the text at its top', () => {
    expect(captured('web')).toEqual(['Menu', 'Real']);
  });
});

describe('foldBursts', () => {
  test('three or more notes at one instant fold into one that names its members', () => {
    const folded = foldBursts([note(1, 'a'), note(1, 'b'), note(1, 'c'), note(2, 'd')], 'any');
    expect(folded.map((n) => n.label)).toEqual(['3 events', 'd']);
    expect(folded[0].members).toEqual(['a', 'b', 'c']);
  });

  test('two at one instant are left alone', () => {
    expect(foldBursts([note(1), note(1)], 'any')).toHaveLength(2);
  });

  test('by label, only runs of the same label fold', () => {
    const folded = foldBursts([note(1, 'x'), note(1, 'x'), note(1, 'x'), note(1, 'y')], 'label');
    expect(folded.map((n) => [n.label, n.detail])).toEqual([['x', '×3'], ['y', undefined]]);
  });

  test('a failure is never folded, so its own card keeps its details', () => {
    const failed = note(1, 'b', { tone: 'error', fields: [{ k: 'Status', v: '503' }] });
    const folded = foldBursts([note(1, 'a'), failed, note(1, 'c'), note(1, 'd'), note(1, 'e')], 'any');
    expect(folded.map((n) => n.label)).toEqual(['a', 'b', '3 events']);
    expect(folded[1]).toBe(failed);
  });

  test('none keeps every note', () => {
    expect(foldBursts([note(1), note(1), note(1)], 'none')).toHaveLength(3);
  });
});

describe('layoutBand', () => {
  test('notes sit at their moment in the band', () => {
    const { offsets } = layoutBand(step(0, 1000), [[note(0), note(500), note(1000)]], 200, METRICS);
    // usable = 200 - 8 - 20 = 172
    expect(offsets[0]).toEqual([4, 90, 176]);
  });

  test('notes that would overlap are pushed down and the band grows to fit', () => {
    const { offsets, height } = layoutBand(step(0, 1000), [[note(0), note(1), note(2), note(3)]], 40, METRICS);
    expect(offsets[0].every((top, index) => index === 0 || top - offsets[0][index - 1] >= METRICS.noteHeight / 2)).toBe(true);
    expect(height).toBeGreaterThanOrEqual(offsets[0][3] + METRICS.noteHeight + METRICS.padding);
  });

  test('notes at the same moment in different lanes share one top', () => {
    const { offsets } = layoutBand(step(0, 1000), [[note(300)], [note(300)]], 200, METRICS);
    expect(offsets[0][0]).toBe(offsets[1][0]);
  });

  test('a later moment in any lane sits below the rows an earlier one took', () => {
    const { offsets } = layoutBand(step(0, 1000), [[note(100), note(101)], [note(102)]], 60, METRICS);
    expect(offsets[1][0]).toBeGreaterThan(offsets[0][0]);
  });

  test('an instant with several notes in one lane stacks them', () => {
    const { offsets } = layoutBand(step(0, 1000), [[note(0), note(0)]], 100, METRICS);
    expect(offsets[0][1] - offsets[0][0]).toBe(METRICS.noteHeight);
  });
});

describe('bandClock and bandY', () => {
  const s = step(0, 1000);
  const columns = [[note(0), note(500), note(1000)]];
  const layout = layoutBand(s, columns, 200, METRICS);

  test('a y at a note reads back that note\'s moment', () => {
    const clock = bandClock(s, columns, layout, METRICS);
    const mid = layout.offsets[0][1] + METRICS.noteHeight / 2;
    expect(clock(mid)).toBeCloseTo(500, 3);
  });

  test('bandY is the inverse of bandClock', () => {
    const clock = bandClock(s, columns, layout, METRICS);
    const y = bandY(s, columns, layout, METRICS);
    for (const t of [100, 250, 500, 800]) expect(clock(y(t))).toBeCloseTo(t, 3);
  });

  test('moments outside the band clamp to its edges', () => {
    const clock = bandClock(s, columns, layout, METRICS);
    expect(clock(-50)).toBe(0);
    expect(clock(100_000)).toBe(1000);
  });

  test('a pushed-down stretch still follows the clock of the notes drawn there', () => {
    const dense = [[note(0), note(1), note(2), note(3), note(1000)]];
    const l = layoutBand(s, dense, 60, METRICS);
    const clock = bandClock(s, dense, l, METRICS);
    let previous = -Infinity;
    for (let y = 0; y <= l.height; y += 5) {
      const t = clock(y);
      expect(t).toBeGreaterThanOrEqual(previous);
      previous = t;
    }
  });
});

describe('noteWindow', () => {
  test('keeps the notes whose rows touch the window', () => {
    const tops = [0, 20, 40, 60, 80, 100];
    expect(noteWindow(tops, 45, 85, 20)).toEqual([2, 5]);
  });
});

describe('lane arrangement', () => {
  const lanes = ['zeta', 'network', 'llm', 'tools', 'alpha'].map((id) => ({ id, name: id }));

  test('with no saved order, the defaults come first in their order', () => {
    expect(arrangeLanes(lanes, emptyPreference()).map((lane) => lane.id)).toEqual(['tools', 'llm', 'network', 'alpha', 'zeta']);
  });

  test('a saved order wins, and unplaced lanes follow it', () => {
    const preference = { ...emptyPreference(), order: ['alpha', 'tools'] };
    expect(arrangeLanes(lanes, preference).map((lane) => lane.id)).toEqual(['alpha', 'tools', 'llm', 'network', 'zeta']);
  });

  test('a device network lane takes the network lane\'s place', () => {
    const arranged = arrangeLanes([{ id: 'x', name: 'x' }, { id: 'network.phone', name: 'Network (phone)' }, { id: 'tools', name: 'Tools' }], emptyPreference());
    expect(arranged.map((lane) => lane.id)).toEqual(['tools', 'network.phone', 'x']);
  });

  test('visibility defaults to tools, llm and network until the reader chooses', () => {
    const preference = { ...emptyPreference(), visible: { alpha: true, tools: false } };
    expect(isLaneVisible('llm', preference)).toBe(true);
    expect(isLaneVisible('tools', preference)).toBe(false);
    expect(isLaneVisible('alpha', preference)).toBe(true);
    expect(isLaneVisible('zeta', preference)).toBe(false);
    expect(isLaneVisible('network.phone', preference)).toBe(true);
  });

  test('a run with none of the defaults opens on its first few lanes', () => {
    expect(defaultLaneIds([{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }])).toEqual(['a', 'b', 'c']);
    expect(defaultLaneIds([{ id: 'a' }, { id: 'tools' }])).toEqual(['tools']);
  });
});

describe('find', () => {
  const lanes = [
    { id: 'tools', name: 'Tools', total: 2, bands: [[note(10, 'tap', { detail: 'Save' })], [note(30, 'assert', { fields: [{ k: 'Target', v: 'Total due' }] })]] },
    { id: 'x', name: 'X', total: 2, bands: [[note(5, 'saved the draft', { badges: [{ text: 'ok' }] })], [note(40, 'other', { body: '{"a":"SAVE"}' })]] },
  ];

  test('matches text anywhere a note shows it, ignoring case', () => {
    expect(searchNotes(lanes, 'save')).toEqual([
      { band: 0, lane: 1, note: 0 },
      { band: 0, lane: 0, note: 0 },
      { band: 1, lane: 1, note: 0 },
    ]);
  });

  test('matches in field values and walks band by band, then by time', () => {
    expect(searchNotes(lanes, 'total due')).toEqual([{ band: 1, lane: 0, note: 0 }]);
  });

  test('a blank query matches nothing', () => {
    expect(searchNotes(lanes, '   ')).toEqual([]);
  });

  test('a note\'s card entries are searched too', () => {
    const entryLane = [{ id: 'v', name: 'V', total: 1, bands: [[note(1, '2 strings', { entries: [{ k: 'Checkout', v: '' }] })]] }];
    expect(searchNotes(entryLane, 'checkout')).toHaveLength(1);
  });
});

describe('moments', () => {
  const lanes = [
    { id: 'a', name: 'A', total: 2, bands: [[note(3), note(1)], [note(7)]] },
    { id: 'b', name: 'B', total: 1, bands: [[note(3)], []] },
  ];

  test('lists each distinct moment once, ascending', () => {
    expect(momentsOf(lanes)).toEqual([1, 3, 7]);
  });

  test('steps to the next and previous moment and stops at the ends', () => {
    const moments = momentsOf(lanes);
    expect(stepMoment(moments, 3, 1)).toBe(7);
    expect(stepMoment(moments, 3, -1)).toBe(1);
    expect(stepMoment(moments, 7, 1)).toBe(7);
    expect(stepMoment(moments, 1, -1)).toBe(1);
    expect(stepMoment(moments, 4, -1)).toBe(3);
    expect(stepMoment([], 4, 1)).toBe(4);
  });
});

describe('placement', () => {
  test('a card opens after its note when it fits there', () => {
    expect(cardBeside({ left: 100, right: 200 }, 300, { left: 0, right: 800 }, 8)).toEqual({ left: 208, width: 300 });
  });

  test('a card opens before its note when only that side fits', () => {
    expect(cardBeside({ left: 500, right: 780 }, 300, { left: 0, right: 800 }, 8)).toEqual({ left: 192, width: 300 });
  });

  test('a card too wide for either side narrows to the roomier one', () => {
    const placed = cardBeside({ left: 100, right: 200 }, 900, { left: 0, right: 800 }, 8);
    expect(placed.width).toBe(800 - 8 - 208);
    expect(placed.left).toBe(208);
  });

  test('the preview is centred on the pointer but kept inside the scroller', () => {
    expect(previewTop(300, 200, 40, 800)).toBe(200);
    expect(previewTop(0, 200, 40, 800)).toBe(40);
    expect(previewTop(790, 200, 40, 800)).toBe(600);
  });

  test('a screen sits on the bigger side of a card and is hidden when there is no room', () => {
    const view = { left: 0, right: 1000 };
    const placed = screenBeside({ left: 100, width: 300 }, view, 400, 0.5, 8, 'after')!;
    expect(placed.left).toBe(408);
    expect(placed.width).toBe(200);
    expect(placed.height).toBe(400);
    expect(screenBeside({ left: 0, width: 1000 }, view, 400, 0.5, 8, 'after')).toBeUndefined();
  });
});

describe('model shape', () => {
  test('model start is the earliest timestamp seen, even a stream event before the first step', () => {
    const model: StreamsModel = build({ trace: TRACE }, [stream('acme.log', [{ t: 400, d: 'early' }])]);
    expect(model.startMs).toBe(400);
    expect(model.steps[0].startMs).toBe(400);
  });

  test('events past the last step belong to the last band', () => {
    const model = build({ trace: TRACE }, [stream('acme.log', [{ t: 99_000, d: 'late' }])]);
    const lane = model.lanes.find((l) => l.id === 'acme.log')!;
    expect(lane.bands[0]).toEqual([]);
    expect(lane.bands[1].map((n) => n.label)).toEqual(['late']);
  });
});
