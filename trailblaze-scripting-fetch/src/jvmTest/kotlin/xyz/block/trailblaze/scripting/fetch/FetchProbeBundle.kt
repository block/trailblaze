package xyz.block.trailblaze.scripting.fetch

/**
 * The shared inline JS bundle the fetch tests drive: one `fetchProbe` tool that issues a `fetch` and
 * reports the observable bits of the `Response` back as JSON — or the thrown error message when
 * `fetch` rejects. Lets a test assert against a tool handler's view of `fetch`, which is the
 * contract authors actually depend on.
 *
 * Shared (rather than copied per test class) because both fetch test classes need the same probe and
 * a divergent copy would silently test a different surface.
 */
internal object FetchProbeBundle {

  /** Registers `fetchProbe`, leaving `globalThis.__trailblazeTools` open for a test to extend. */
  val SOURCE: String =
    """
    const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
    tools["fetchProbe"] = {
      name: "fetchProbe",
      spec: {},
      handler: async (args) => {
        try {
          const res = await fetch(args.url, args.init || undefined);
          const bodyText = await res.text();
          let jsonHello = null;
          try { jsonHello = JSON.parse(bodyText).hello; } catch (e) {}
          return { content: [{ type: "text", text: JSON.stringify({
            status: res.status,
            ok: res.ok,
            contentType: res.headers.get("content-type"),
            body: bodyText,
            jsonHello: jsonHello,
          }) }] };
        } catch (e) {
          return { content: [{ type: "text", text: JSON.stringify({
            error: String((e && e.message) || e),
          }) }] };
        }
      },
    };
    """.trimIndent()
}
