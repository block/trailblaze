// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function PhonePreview({ trace, step, setStep, sessionId, cur, hasHierarchy, width, playing, setPlaying, clockRef, span }) {
  const [zoom, setZoom] = React.useState(false);
  const [imgFailed, setImgFailed] = React.useState(false);
  // View hierarchy is now drawn as bounding-box overlays ON the screenshot (was a separate tree modal).
  const [showHier, setShowHier] = React.useState(false);
  const [hierFilter, setHierFilter] = React.useState('');
  const imgRef = React.useRef(null);
  const [box, setBox] = React.useState(null); // measured rendered <img> size, so the overlay aligns to it
  const [nat, setNat] = React.useState(null); // screenshot's natural px size = the device coordinate space bounds live in
  const n = trace.length;
  const idx = Math.max(0, trace.findIndex((t) => t.i === step));
  let file = null;
  for (let k = idx; k >= 0; k--) { if (trace[k] && trace[k].screenshotFile) { file = trace[k].screenshotFile; break; } }
  const src = (file && sessionId) ? `/static/${encodeURIComponent(sessionId)}/${encodeURIComponent(file)}` : null;
  React.useEffect(() => { setImgFailed(false); }, [src]);
  const showImg = !!src && !imgFailed;
  const goTo = (i) => { const c = Math.min(n - 1, Math.max(0, i)); if (trace[c]) setStep(trace[c].i); };
  const FRAME_MAX_W = (width || 256) - 16;
  // The screenshot is sized by maxWidth/maxHeight:contain, so its rendered box is dynamic. Measure it
  // (and track resizes) so the absolutely-positioned overlay can match it exactly.
  const measure = React.useCallback(() => { const el = imgRef.current; if (el && el.clientWidth) setBox({ w: el.clientWidth, h: el.clientHeight }); }, []);
  React.useEffect(() => {
    if (!showImg) { setBox(null); return; }
    const el = imgRef.current; if (!el) return;
    measure();
    // ResizeObserver isn't guaranteed in every embedded/older browser — fall back to window resize.
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null;
    if (ro) ro.observe(el);
    window.addEventListener('resize', measure);
    return () => { if (ro) ro.disconnect(); window.removeEventListener('resize', measure); };
  }, [showImg, src, width, measure]);
  const overlayOn = showHier && hasHierarchy && showImg && box && cur && cur.viewHierarchy;
  return (
    <>
      <div style={{ flex: 1, minHeight: 0, display: 'flex', justifyContent: 'center', alignItems: 'center', overflow: 'hidden', position: 'relative' }}>
        {showImg ? (
          <img ref={imgRef} src={src} onLoad={(e) => { const el = e.currentTarget; if (el.naturalWidth) setNat({ w: el.naturalWidth, h: el.naturalHeight }); measure(); }} onClick={() => { if (!overlayOn) setZoom(true); }} alt={'Step ' + (idx + 1)} onError={() => setImgFailed(true)}
            style={{ maxWidth: '100%', maxHeight: '100%', width: 'auto', height: 'auto', objectFit: 'contain', display: 'block', borderRadius: 4, border: '1px solid var(--tb-hairline-stronger)', background: '#000', boxShadow: '0 8px 30px rgba(0,0,0,.45)', cursor: overlayOn ? 'default' : 'zoom-in' }} />
        ) : (
          <div style={{ width: '100%', maxWidth: FRAME_MAX_W, aspectRatio: '1 / 2', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-subtle)', fontSize: 12, textAlign: 'center', padding: 20, borderRadius: 4, border: '1px dashed var(--tb-hairline-strong)', background: 'var(--bg-standard)' }}>
            {imgFailed ? 'Screenshot unavailable for this step (not captured).' : 'No screenshot captured before this step.'}
          </div>
        )}
        {overlayOn && (
          <div style={{ position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%, -50%)', width: box.w, height: box.h, pointerEvents: 'none', borderRadius: 4, overflow: 'hidden' }}>
            <HierarchyOverlay tree={cur.viewHierarchy} query={hierFilter} vw={nat && nat.w} vh={nat && nat.h} />
          </div>
        )}
      </div>
      <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
        <button className="tb-btn sm" onClick={() => setPlaying((p) => !p)} title={playing ? 'Pause' : 'Play'}><Ico n={playing ? 'pause' : 'play'} s={15} /></button>
        <button className="tb-btn sm" onClick={() => { setPlaying(false); goTo(idx - 1); }} disabled={idx <= 0} title="Previous step"><Ico n="chevron-left" s={15} /></button>
        <button className="tb-btn sm" onClick={() => { setPlaying(false); goTo(idx + 1); }} disabled={idx >= n - 1} title="Next step"><Ico n="chevron-right" s={15} /></button>
        <div style={{ flex: 1, minWidth: 0, textAlign: 'right', lineHeight: 1.2 }}>
          <div className="tb-mono" style={{ fontSize: 12.5, color: 'var(--text-standard)' }}>
            <span ref={clockRef}>{fmtClock(0)}</span> <span className="tb-sub">/ {fmtClock(span || 0)}</span>
          </div>
          <div className="tb-sub" style={{ fontSize: 10 }}>Step {idx + 1} / {n}</div>
        </div>
      </div>
      <button data-testid="view-hierarchy-btn" className={'tb-btn sm' + (overlayOn ? ' primary' : '')} disabled={!hasHierarchy} onClick={() => setShowHier((v) => !v)} style={{ marginTop: 10, flexShrink: 0, justifyContent: 'center', opacity: hasHierarchy ? 1 : 0.5 }}>
        <Ico n="list-tree" s={14} />{!hasHierarchy ? 'No hierarchy' : showHier ? 'Hide hierarchy' : 'View hierarchy'}
      </button>
      {showHier && hasHierarchy && (
        <div className="tb-input" style={{ marginTop: 8, flexShrink: 0 }}>
          <Ico n="search" s={13} />
          <input placeholder="Filter overlays by class, text, or id…" value={hierFilter} onChange={(e) => setHierFilter(e.target.value)}
            style={{ background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-standard)', width: '100%', fontSize: 12 }} />
        </div>
      )}
      {zoom && src && (
        <div onClick={() => setZoom(false)} style={{ position: 'fixed', inset: 0, zIndex: 9999, background: 'rgba(0,0,0,.85)', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'zoom-out' }}>
          <img src={src} style={{ maxWidth: '92vw', maxHeight: '92vh', borderRadius: 10, border: '1px solid var(--tb-hairline-stronger)' }} alt="Step screenshot (full)" />
        </div>
      )}
    </>
  );
}

// Flatten the hierarchy tree to a list of nodes that carry usable bounds (in device pixels).
function flattenHier(root) {
  const out = [];
  const walk = (node, depth) => {
    if (!node || typeof node !== 'object') return;
    const b = node.bounds;
    if (b && typeof b.right === 'number' && typeof b.bottom === 'number') out.push({ node, depth });
    (node.children || []).forEach((c) => walk(c, depth + 1));
  };
  walk(root, 0);
  return out;
}

// Draws one outlined box per hierarchy node over the screenshot. Positions are percentages of the
// device viewport (max right/bottom across all nodes), so they scale with the rendered image without
// needing the screenshot's pixel resolution. Hover highlights a box + labels it; the filter dims
// non-matching nodes (reusing hierNodeMatches). Parent boxes paint first, so the deepest (smallest)
// node sits on top and is the one you hover.
function HierarchyOverlay({ tree, query, vw, vh }) {
  const nodes = React.useMemo(() => flattenHier(tree), [tree]);
  const [hover, setHover] = React.useState(-1);
  // Prefer the screenshot's natural px (bounds live in that same device space); fall back to the
  // widest/tallest node bounds when the natural size hasn't loaded yet.
  const ext = React.useMemo(() => {
    if (vw > 0 && vh > 0) return { vw, vh };
    let mr = 0; let mb = 0;
    nodes.forEach(({ node }) => { const b = node.bounds; if (b.right > mr) mr = b.right; if (b.bottom > mb) mb = b.bottom; });
    return { vw: mr || 1, vh: mb || 1 };
  }, [nodes, vw, vh]);
  const q = (query || '').trim().toLowerCase();
  return (
    <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
      {nodes.map(({ node }, i) => {
        const b = node.bounds;
        const match = !q || hierNodeMatches(node, q);
        // Declutter (ch07: ornamentation sparingly): by default draw only labeled/interactive nodes —
        // leaves, or anything with user-facing text / contentDescription. A filter query reveals the
        // full matching tree, structural containers included.
        const dd = node.driverDetail || {};
        const primary = !!(dd.text || dd.contentDescription || node.text || node.contentDescription) || !(node.children && node.children.length);
        if (q ? !match : !primary) return null;
        const isHover = hover === i;
        const left = (b.left / ext.vw) * 100;
        const top = (b.top / ext.vh) * 100;
        const bw = ((b.right - b.left) / ext.vw) * 100;
        const bh = ((b.bottom - b.top) / ext.vh) * 100;
        const info = hierNodeInfo(node);
        return (
          <div key={i}
            onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover((x) => (x === i ? -1 : x))}
            style={{
              position: 'absolute', left: left + '%', top: top + '%', width: bw + '%', height: bh + '%',
              boxSizing: 'border-box', pointerEvents: 'auto', cursor: 'crosshair',
              border: '1px solid ' + (isHover ? 'rgba(94,155,255,1)' : match ? 'rgba(94,155,255,.34)' : 'rgba(140,140,140,.12)'),
              background: isHover ? 'rgba(94,155,255,.2)' : 'transparent',
              opacity: q && !match ? 0.25 : 1,
              zIndex: isHover ? 5 : 1,
            }}>
            {isHover && (info.type || info.text) && (
              <span className="tb-mono" style={{ position: 'absolute', left: -1, top: bh > 7 ? 1 : '100%', whiteSpace: 'nowrap', fontSize: 10, lineHeight: 1.35, padding: '2px 6px', background: 'rgba(10,12,18,.94)', color: '#cfe0ff', border: '1px solid rgba(94,155,255,.55)', borderRadius: 4, pointerEvents: 'none', zIndex: 6, maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {info.type}{info.text ? ' · “' + info.text + '”' : ''}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

function hierNodeInfo(n) {
  const dd = n.driverDetail || {};
  const typ = dd.className || dd.class || n.className || n.role || n.type || 'node';
  const txt = dd.text || dd.contentDescription || dd.resourceId || dd.label || dd.value ||
    n.text || n.contentDescription || n.resourceId || '';
  return { type: String(typ).split('.').pop(), text: String(txt || '') };
}
function hierNodeMatches(n, q) {
  const { type, text } = hierNodeInfo(n);
  return (type + ' ' + text + ' ' + JSON.stringify(n.driverDetail || {})).toLowerCase().includes(q);
}
Object.assign(window, { PhonePreview, hierNodeInfo, hierNodeMatches, flattenHier, HierarchyOverlay });
