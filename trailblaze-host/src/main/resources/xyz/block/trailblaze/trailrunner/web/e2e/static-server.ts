// Tiny static file server for the Playwright smoke test — serves the trailrunner web/ directory so
// the bundle is reachable and same-origin `/rpc/...` requests resolve to a real origin (which the
// test then intercepts). Not used in production; the daemon serves these files at runtime.
import { join, normalize } from "path";

const root = join(import.meta.dir, "..");
const port = Number(process.env.PORT ?? 4321);

Bun.serve({
  port,
  async fetch(req) {
    const url = new URL(req.url);
    const path = normalize(url.pathname === "/" ? "/e2e/smoke.html" : url.pathname);
    const file = Bun.file(join(root, path));
    if (await file.exists()) return new Response(file);
    return new Response("not found", { status: 404 });
  },
});

console.log(`[static-server] serving ${root} on http://localhost:${port}`);
