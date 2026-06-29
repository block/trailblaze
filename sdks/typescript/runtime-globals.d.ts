// Curated ambient declarations for runtime globals an author can reference from a
// `.ts` tool file without a per-trailmap `globals.d.ts` shim. Appended to
// `dist/index.d.ts` by `bundleTrailblazeSdkDts` — see [TrailblazeSdkDtsBundlePlugin].
//
// Scope. Authors target two runtimes through the same `.ts` source: the host-side
// subprocess (bun / Node) and the on-device QuickJS bundle. The host has everything
// declared here natively; on-device gets a smaller, framework-installed subset
// (`console.*` and `AbortController` are real shims in
// `trailblaze-bundle-prelude.js`; `URL`, `setTimeout` are NOT — using them from an
// on-device-eligible tool will fail at runtime). `fetch` is the special case: it's a
// real binding wherever the framework installs the OkHttp-backed engine extension —
// the host in-process QuickJS daemon installs it, and on-device is opt-in (off by
// default), so an on-device-eligible tool must not assume `fetch` unless its runtime
// opted in (see `:trailblaze-scripting-fetch`). The type system declares the union —
// the framework's Phase-D host-only marker (and the `.js`-on-device hard error)
// handles routing.
//
// What's deliberately NOT declared: `document`, `window`, `navigator`,
// `localStorage`, and the rest of the DOM lib. Declaring them would tempt authors
// into code that fails on every runtime we actually ship.
//
// The list mirrors what the bundled MCP SDK uses internally plus the most common
// author requests (URL hostname parsing per CodeQL, basic fetch, timer-based
// retries). Expand carefully — every entry here is a future ABI promise.

declare global {
  // ---- URL / URLSearchParams ----

  interface URLSearchParams {
    append(name: string, value: string): void;
    delete(name: string): void;
    get(name: string): string | null;
    getAll(name: string): string[];
    has(name: string): boolean;
    set(name: string, value: string): void;
    sort(): void;
    toString(): string;
    forEach(
      callback: (value: string, key: string, parent: URLSearchParams) => void,
    ): void;
    keys(): IterableIterator<string>;
    values(): IterableIterator<string>;
    entries(): IterableIterator<[string, string]>;
    [Symbol.iterator](): IterableIterator<[string, string]>;
  }
  var URLSearchParams: {
    prototype: URLSearchParams;
    new (
      init?: string | Record<string, string> | string[][] | URLSearchParams,
    ): URLSearchParams;
  };

  interface URL {
    hash: string;
    host: string;
    hostname: string;
    href: string;
    readonly origin: string;
    password: string;
    pathname: string;
    port: string;
    protocol: string;
    search: string;
    readonly searchParams: URLSearchParams;
    username: string;
    toString(): string;
    toJSON(): string;
  }
  var URL: {
    prototype: URL;
    new (input: string | URL, base?: string | URL): URL;
    canParse(input: string | URL, base?: string | URL): boolean;
  };

  // ---- AbortController / AbortSignal ----
  //
  // Real on-device today (shimmed by `trailblaze-bundle-prelude.js`). The shim covers
  // the constructor, `.signal.aborted`, `.signal.reason`, `addEventListener("abort",
  // …)`, `removeEventListener`, `dispatchEvent`, `throwIfAborted`; it does NOT cover
  // the static `AbortSignal.timeout` / `AbortSignal.any` / `AbortSignal.abort`. The
  // typed surface declares them for host-side parity — host bun / Node has them.

  interface Event {
    readonly type: string;
    readonly target: unknown;
    readonly currentTarget: unknown;
  }
  type AbortSignalEventMap = { abort: Event };
  interface AbortSignal {
    readonly aborted: boolean;
    readonly reason: unknown;
    throwIfAborted(): void;
    onabort: ((this: AbortSignal, ev: Event) => unknown) | null;
    addEventListener<K extends keyof AbortSignalEventMap>(
      type: K,
      listener: (this: AbortSignal, ev: AbortSignalEventMap[K]) => unknown,
    ): void;
    removeEventListener<K extends keyof AbortSignalEventMap>(
      type: K,
      listener: (this: AbortSignal, ev: AbortSignalEventMap[K]) => unknown,
    ): void;
    dispatchEvent(event: Event): boolean;
  }
  // No `new (): AbortSignal` overload — `AbortSignal` is NOT constructible at runtime
  // in bun / Node (it throws an "Illegal constructor" TypeError). Authors who need a
  // standalone signal should use the static `AbortSignal.abort()` / `.timeout()` /
  // `.any()` factories or read `signal` off an `AbortController`. Declaring `new ()`
  // here would let `.ts` code type-check and then explode on first execution.
  var AbortSignal: {
    prototype: AbortSignal;
    abort(reason?: unknown): AbortSignal;
    timeout(milliseconds: number): AbortSignal;
    any(signals: AbortSignal[]): AbortSignal;
  };
  interface AbortController {
    readonly signal: AbortSignal;
    abort(reason?: unknown): void;
  }
  var AbortController: {
    prototype: AbortController;
    new (): AbortController;
  };

  // ---- console ----
  //
  // Real on-device (the prelude routes each method through Kotlin → logcat). On
  // host, bun's / Node's built-in `console`. The declared surface is the lowest
  // common denominator — five level methods, all variadic.

  interface Console {
    log(...args: unknown[]): void;
    info(...args: unknown[]): void;
    warn(...args: unknown[]): void;
    error(...args: unknown[]): void;
    debug(...args: unknown[]): void;
  }
  var console: Console;

  // ---- Timers ----
  //
  // Host-only at runtime — QuickJS has no `setTimeout`. The on-device hard-error for
  // `.js` tools combined with Phase-D host-only routing keeps this from biting; the
  // type declaration is for authors writing host-side retry / debounce code.
  //
  // **Why `TimeoutHandle` instead of `number`.** Browsers and bun return `number`;
  // Node returns a `Timeout` object. Declaring the return as `number` (or as
  // `Timeout`) over-promises one runtime and breaks the other — author code that
  // does `if (handle > 0) …` would type-check against `number` but crash on Node,
  // and author code that touches `.unref()` would type-check against `Timeout` but
  // crash on bun. The opaque interface here is the lowest common denominator: it
  // accepts both runtime values, and the only thing TypeScript will let an author
  // do with one is pass it back to `clearTimeout` — which is the only thing both
  // runtimes actually guarantee.

  // Nominal-brand opaque interface. The `?: never` property defeats structural
  // assignability from the runtime values (`number` on bun, `Timeout` on Node,
  // neither carries this property), so an honest author can't accidentally
  // narrow `TimeoutHandle` to a usable shape. An adversarial author who writes
  // `const fake: TimeoutHandle = { __trailblazeTimeoutHandleBrand: undefined }`
  // CAN bypass it — the point isn't sandboxing, it's preventing the accidental
  // `if (handle > 0)` foot-gun.
  interface TimeoutHandle {
    readonly __trailblazeTimeoutHandleBrand?: never;
  }

  function setTimeout(
    handler: (...args: unknown[]) => unknown,
    timeout?: number,
    ...args: unknown[]
  ): TimeoutHandle;
  function clearTimeout(handle: TimeoutHandle | undefined): void;

  // ---- DOMException ----
  //
  // Used by AbortController-flavored failures and a handful of fetch error paths.
  // Both host (bun / Node) and the on-device shim throw real Error subclasses, so
  // authors mostly read `.name` / `.message`; the declared shape is intentionally
  // minimal.

  interface DOMException extends Error {
    readonly name: string;
    readonly message: string;
    readonly code: number;
  }
  var DOMException: {
    prototype: DOMException;
    new (message?: string, name?: string): DOMException;
  };

  // ---- fetch / Request / Response / Headers ----
  //
  // Backed by a real OkHttp client on the host: the in-process QuickJS daemon installs
  // an `OkHttpFetchExtension` (`:trailblaze-scripting-fetch`) that binds `globalThis.fetch`
  // before a tool bundle evaluates. On-device this is opt-in (off by default); the host
  // subprocess (bun / Node) has `fetch` natively. The host binding is unrestricted by default
  // (reaches any host, like the `ctx.tools.exec` + curl it replaces — keeping a recorded run
  // replay-deterministic is the author's responsibility); a deployment can opt into a host
  // allow-list. A tool that needs a proxy uses `ctx.tools.exec` + curl or a `runtime: subprocess`
  // tool (the binding deliberately has no proxy option). The shapes below cover the
  // subset of WHATWG fetch most tool authors need: GET / POST with a JSON body,
  // headers, an `AbortSignal`, and `.text()` / `.json()` / `.arrayBuffer()` on the
  // response. The streaming surface (`body`, `ReadableStream`) is declared loosely
  // so authors who reach for it get autocomplete without us pulling in the full
  // DOM lib.
  //
  // **Why hand-authored instead of pulled from `@types/node`.** `@types/node` is a
  // devDependency of `@trailblaze/scripting` itself but is NOT visible to trailmap
  // authors — the per-trailmap `tsconfig.json` has `lib: ["ES2022"]` only, with the
  // SDK declaration bundle as the `paths`-mapped source of `@trailblaze/scripting`.
  // Pulling fetch types from `@types/node` would either force every trailmap to install
  // it (bloating the trailmap-typing setup) or require the bundle to inline them via
  // dts-bundle-generator's `--external-inlines` (which works but conflates host-only
  // runtime types with the bundle's main mission of MCP-SDK + zod inlines). The
  // hand-authored declarations are a curated subset deliberately kept narrower than
  // upstream; if WHATWG fetch drifts in a way that breaks an author's code, the fix
  // is to update this file rather than chase the upstream type churn.

  type HeadersInit = Headers | string[][] | Record<string, string>;
  type BodyInit =
    | string
    | ArrayBuffer
    | ArrayBufferView
    | URLSearchParams
    | ReadableStream<Uint8Array>;

  interface Headers {
    append(name: string, value: string): void;
    delete(name: string): void;
    get(name: string): string | null;
    has(name: string): boolean;
    set(name: string, value: string): void;
    forEach(
      callback: (value: string, key: string, parent: Headers) => void,
    ): void;
    keys(): IterableIterator<string>;
    values(): IterableIterator<string>;
    entries(): IterableIterator<[string, string]>;
    [Symbol.iterator](): IterableIterator<[string, string]>;
  }
  var Headers: {
    prototype: Headers;
    new (init?: HeadersInit): Headers;
  };

  interface ReadableStream<R = unknown> {
    getReader(): unknown;
    cancel(reason?: unknown): Promise<void>;
    [Symbol.asyncIterator](): AsyncIterableIterator<R>;
  }

  interface RequestInit {
    method?: string;
    headers?: HeadersInit;
    body?: BodyInit | null;
    // WHATWG fetch normalizes a missing signal to a never-aborted signal; the
    // resulting `Request.signal` is always non-null. Declaring `signal: AbortSignal
    // | null` here would falsely encourage author code like `req.signal === null`,
    // which type-checks but never matches at runtime.
    signal?: AbortSignal;
    redirect?: "follow" | "error" | "manual";
    credentials?: "omit" | "same-origin" | "include";
    cache?: string;
    referrer?: string;
    integrity?: string;
    keepalive?: boolean;
    mode?: string;
  }
  interface Request {
    readonly method: string;
    readonly url: string;
    readonly headers: Headers;
    readonly body: ReadableStream<Uint8Array> | null;
    readonly bodyUsed: boolean;
    readonly signal: AbortSignal;
    clone(): Request;
    text(): Promise<string>;
    json(): Promise<unknown>;
    arrayBuffer(): Promise<ArrayBuffer>;
  }
  var Request: {
    prototype: Request;
    new (input: string | URL | Request, init?: RequestInit): Request;
  };

  interface ResponseInit {
    status?: number;
    statusText?: string;
    headers?: HeadersInit;
  }
  interface Response {
    readonly status: number;
    readonly statusText: string;
    readonly ok: boolean;
    readonly headers: Headers;
    readonly url: string;
    readonly redirected: boolean;
    readonly type: string;
    readonly body: ReadableStream<Uint8Array> | null;
    readonly bodyUsed: boolean;
    clone(): Response;
    text(): Promise<string>;
    json(): Promise<unknown>;
    arrayBuffer(): Promise<ArrayBuffer>;
  }
  var Response: {
    prototype: Response;
    new (body?: BodyInit | null, init?: ResponseInit): Response;
    error(): Response;
    json(data: unknown, init?: ResponseInit): Response;
    redirect(url: string | URL, status?: number): Response;
  };

  function fetch(
    input: string | URL | Request,
    init?: RequestInit,
  ): Promise<Response>;
}

export {};
