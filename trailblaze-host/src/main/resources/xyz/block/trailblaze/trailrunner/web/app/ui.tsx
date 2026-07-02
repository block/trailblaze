// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function listNavKeyDown(e, { index, count, set }) {
  if ((e.key !== 'ArrowDown' && e.key !== 'ArrowUp') || count === 0) return;
  const t = e.target;
  if (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable || (t.closest && t.closest('.CodeMirror'))) return;
  e.preventDefault();
  const cur = index == null || index < 0 ? (e.key === 'ArrowDown' ? -1 : count) : index;
  const next = e.key === 'ArrowDown' ? Math.min(cur + 1, count - 1) : Math.max(cur - 1, 0);
  if (next === index) return;
  set(next);
  const container = e.currentTarget;
  requestAnimationFrame(() => {
    const el = container.querySelectorAll('[data-navrow]')[next];
    if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
  });
}

// Prop bundle that turns a non-button element (a clickable row / card / span) into a real button for
// the keyboard, matching native <button> activation. The `:where([role="button"]):focus-visible`
// rule in app.css then supplies the focus ring for free, so these stop being mouse-only dead ends.
// Spread it LAST so it owns role/tabIndex/onClick/onKeyDown/onKeyUp:
//   <div {...clickable(() => open(id))} className="…" style={…}>
// Native semantics: Enter activates on keydown, Space on key-UP (suppressed on keydown to avoid page
// scroll), and auto-repeat (e.repeat) is ignored — so holding the key can't fire repeated destructive
// actions (delete/reorder), which a naive keydown-on-Space handler would.
function clickable(fn, { role = 'button', tabIndex = 0 } = {}) {
  return {
    role, tabIndex, onClick: fn,
    onKeyDown: (e) => {
      if (e.repeat) return;
      if (e.key === 'Enter') { e.preventDefault(); fn(e); }
      else if (e.key === ' ') { e.preventDefault(); }
    },
    onKeyUp: (e) => { if (e.key === ' ') { e.preventDefault(); fn(e); } },
  };
}

function useLucide() {}

function Ico({ n, s = 18, c, style, cls, spin }) {
  const ref = React.useRef(null);
  React.useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const L = window.lucide;
    el.textContent = '';
    if (!L) return;
    const key = String(n).split(/[-_]/).filter(Boolean).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join('');
    const node = (L.icons && L.icons[key]) || L[key];
    if (!node) return;
    const svg = L.createElement(node);
    svg.setAttribute('width', String(s));
    svg.setAttribute('height', String(s));
    svg.setAttribute('stroke-width', '1.6');
    el.appendChild(svg);
  }, [n, s]);
  return <span ref={ref} className={(cls ? cls + ' ' : '') + (spin ? 'tb-spin' : '')} aria-hidden="true" style={{ width: s, height: s, color: c, display: 'inline-flex', flex: '0 0 auto', lineHeight: 0, ...style }}></span>;
}
function Dot({ c, s = 8, cls }) { return <span className={'tb-dot ' + (cls || '')} style={{ width: s, height: s, background: c }}></span>; }

// Glyphs for device platforms: an Apple mark for iOS, a standing robot for Android (reads as "a
// robot", clearer than the lying-down logo), and a globe for web — the one platform-icon set used
// app-wide. Drawn as OUTLINE strokes (fill: none) so they share the app's lucide line-icon language
// and match the flame's weight beside them, instead of reading as heavier filled solids. Two sizes
// from the same glyph: `fit` (boxed default, padded inside a chip) and `bareFit` (no chip, fills the
// box — for dense rows like the sidebar's variant markers). The single color `c` tints stroke + chip.
//
// Stroke width is set to `2 / scale` so the geometry-scaling transform cancels out — every glyph
// lands on ~2 user-units of stroke regardless of size, exactly like a lucide icon.
const PLATFORM_GLYPH = {
  ios: { d: 'M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701', fit: 0.54, bareFit: 0.8 },
  android: { robot: true, fit: 0.66, bareFit: 0.96 },
  web: { d: 'M12 2.5a9.5 9.5 0 1 0 0 19 9.5 9.5 0 0 0 0-19M2.5 12h19M12 2.5c2.6 2.5 4 6 4 9.5s-1.4 7-4 9.5c-2.6-2.5-4-6-4-9.5s1.4-7 4-9.5', fit: 0.74, bareFit: 1 },
};
// The Android bugdroid, OUTLINE style: a domed (semicircular) head with two outward-splayed antennae
// and two dot eyes, a rounded body, two side arms, two legs. Eyes are filled dots; every other shape
// inherits the parent group's stroke (fill: none). Arms + legs are simple strokes (rounded caps from
// the parent group) — NOT outlined rects: a rect barely wider than the stroke renders as a solid bar
// at ~2x the leg weight, which reads as too-thick arms. A bare stroke matches the legs/antennae exactly.
function RobotGlyph({ c }) {
  return (
    <g>
      <path d="M8.6 4.9 7 2M15.4 4.9 17 2" />
      <path d="M5 11a7 7 0 0 1 14 0Z" />
      <circle cx="9.6" cy="8" r="0.95" fill={c} stroke="none" />
      <circle cx="14.4" cy="8" r="0.95" fill={c} stroke="none" />
      <rect x="5" y="12.6" width="14" height="7.6" rx="2.4" />
      <path d="M3.45 13.4v4.6M20.55 13.4v4.6" />
      <path d="M9 20.3v2.4M15 20.3v2.4" />
    </g>
  );
}
function PlatformGlyph({ platform, s = 16, c = 'currentColor', title, style, bare = false }) {
  const key = (platform || '').toLowerCase();
  const norm = key === 'ipad' || key === 'iphone' || key === 'apple' ? 'ios' : (PLATFORM_GLYPH[key] ? key : 'web');
  const g = PLATFORM_GLYPH[norm];
  const scale = bare ? (g.bareFit || g.fit) : g.fit;
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" role={title ? 'img' : undefined} aria-hidden={title ? undefined : 'true'}
      style={{ flex: '0 0 auto', display: 'inline-block', ...style }}>
      {title && <title>{title}</title>}
      {!bare && <rect x="0.75" y="0.75" width="22.5" height="22.5" rx="6.5" fill={c} fillOpacity="0.12" stroke={c} strokeOpacity="0.3" strokeWidth="1" />}
      <g transform={`translate(12 12) scale(${scale}) translate(-12 -12)`} fill="none" stroke={c} strokeWidth={2 / scale} strokeLinejoin="round" strokeLinecap="round">
        {g.robot ? <RobotGlyph c={c} /> : <path d={g.d} />}
      </g>
    </svg>
  );
}

function Chip({ tone, children, ico, style }) {
  return <span className={'tb-chip ' + (tone || '')} style={style}>{ico && <Ico n={ico} s={12} />}{children}</span>;
}
const STATUS = {
  passed: ['green', 'var(--tb-pass)', 'Passed'], failed: ['red', 'var(--tb-fail)', 'Failed'],
  healed: ['amber', 'var(--tb-amber)', 'Self-healed'], cancelled: ['', 'var(--text-subtle)', 'Cancelled'],
  running: ['blue', 'var(--tb-running)', 'Running'], timeout: ['amber', 'var(--tb-amber)', 'Timeout'],
  unknown: ['', 'var(--text-subtle)', 'Unknown'],
};
function StatusChip({ s }) { const [tone, , label] = STATUS[s] || STATUS.passed; return <Chip tone={tone}>{label}</Chip>; }

function Btn({ kind, sm, ico, spin, children, onClick, style, ...rest }) {
  return <button className={'tb-btn ' + (kind || '') + (sm ? ' sm' : '')} onClick={onClick} style={style} {...rest}>{ico && <Ico n={ico} s={sm ? 14 : 15} spin={spin} />}{children}</button>;
}
function Switch({ on, onClick }) { return <div className={'tb-switch ' + (on ? 'on' : '')} onClick={onClick}></div>; }

function Select({ label, value, onChange, options, children, title, full, style, compact, subtle, ico, multi, searchable, hoverPanel, ...rest }) {
  useLucide();
  const [open, setOpen] = React.useState(false);
  const [active, setActive] = React.useState(-1);
  const [rect, setRect] = React.useState(null);
  const [query, setQuery] = React.useState('');
  const ctrlRef = React.useRef(null);
  const popRef = React.useRef(null);
  const items = React.useMemo(() => {
    // Object options pass through any rich fields (ico/badges/meta/desc) so the popup can render
    // a scannable table row; array options stay the simple [value, label, short] triple.
    if (options) return options.map((o) => (Array.isArray(o) ? { value: o[0], label: o[1], short: o[2] } : { ...o, value: o.value, label: o.label != null ? o.label : o.value, short: o.short }));
    return React.Children.toArray(children).filter((c) => c && c.type === 'option').map((c) => ({ value: c.props.value, label: c.props.children }));
  }, [options, children]);
  // Any rich item (icon, badges, secondary meta, or description) switches the popup into table rows
  // and widens it so the extra columns have room.
  const anyRich = React.useMemo(() => items.some((it) => it.ico || it.badges || it.meta || it.desc), [items]);
  // When searchable, the popup carries a filter box; the visible (filtered) list drives both the
  // rendered options and the keyboard nav so highlighting/Enter line up with what's shown. Search
  // spans the primary label, value, badge text, and any string meta/description.
  const visibleItems = React.useMemo(() => {
    if (!searchable || !query.trim()) return items;
    const q = query.trim().toLowerCase();
    const hay = (it) => [it.label, it.value, typeof it.meta === 'string' ? it.meta : '', typeof it.desc === 'string' ? it.desc : '', ...((it.badges || []).map((b) => b.text))].filter(Boolean).join(' ').toLowerCase();
    return items.filter((it) => hay(it).includes(q));
  }, [items, searchable, query]);
  React.useEffect(() => { if (!open) setQuery(''); }, [open]);
  React.useEffect(() => { setActive(-1); }, [query]);
  const multiVals = multi ? (Array.isArray(value) ? value : []) : null;
  const current = items.find((it) => String(it.value) === String(value));
  React.useEffect(() => { setActive((a) => (a >= items.length ? -1 : a)); }, [items.length]);
  const isPicked = (v) => (multi
    ? (multiVals.length === 0 ? String(v) === String(items[0] && items[0].value) : multiVals.includes(v))
    : String(v) === String(value));
  const choose = (v) => {
    if (multi) {
      // First option ("All …") clears; others toggle. Stay open so several can be picked.
      const next = String(v) === String(items[0] && items[0].value)
        ? []
        : (multiVals.includes(v) ? multiVals.filter((x) => x !== v) : [...multiVals, v]);
      if (onChange) onChange({ target: { value: next } });
      return;
    }
    setOpen(false); setActive(-1); if (onChange) onChange({ target: { value: v } });
  };
  const toggle = () => { if (ctrlRef.current) setRect(ctrlRef.current.getBoundingClientRect()); setOpen((o) => !o); };
  React.useEffect(() => {
    if (!open) return;
    const onDoc = (e) => {
      if (ctrlRef.current && ctrlRef.current.contains(e.target)) return;
      if (popRef.current && popRef.current.contains(e.target)) return;
      setOpen(false);
    };
    const onKey = (e) => { if (e.key === 'Escape') { setOpen(false); setActive(-1); } };
    const onReflow = (e) => {
      const t = e && e.target;
      if (t && t.nodeType === 1 && popRef.current && popRef.current.contains(t)) return;
      setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    window.addEventListener('resize', onReflow);
    window.addEventListener('scroll', onReflow, true);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', onReflow);
      window.removeEventListener('scroll', onReflow, true);
    };
  }, [open]);
  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) { toggle(); return; }
      const n = visibleItems.length, d = e.key === 'ArrowDown' ? 1 : -1;
      setActive((i) => (n ? (i < 0 ? 0 : (i + d + n) % n) : -1));
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      if (!open) toggle();
      else if (active >= 0 && visibleItems[active]) choose(visibleItems[active].value);
    }
  };
  const popStyle = (() => {
    if (!rect) return {};
    const vh = window.innerHeight, gap = 4, margin = 8, want = anyRich ? 400 : 280;
    const below = vh - rect.bottom, above = rect.top;
    const flipUp = below < Math.min(want, 180) + margin && above > below;
    return {
      position: 'fixed', left: rect.left, minWidth: anyRich ? Math.max(rect.width, 320) : rect.width,
      maxWidth: Math.max(rect.width, Math.min(anyRich ? 540 : 440, window.innerWidth - rect.left - margin)),
      maxHeight: Math.min(want, (flipUp ? above : below) - margin),
      ...(flipUp ? { bottom: vh - rect.top + gap } : { top: rect.bottom + gap }),
    };
  })();
  // When a hoverPanel is supplied, measure the rendered popup so the docs panel can sit flush beside it.
  // Depend on whether a panel EXISTS (a boolean), not the hoverPanel function identity — callers pass an
  // inline (id)=>… that's a new function every render, which would otherwise re-run this effect and
  // re-setState in a loop. Only setState when the rect actually changed so we never loop.
  const hasHoverPanel = !!hoverPanel;
  const [popRect, setPopRect] = React.useState(null);
  React.useLayoutEffect(() => {
    if (open && hasHoverPanel && popRef.current) {
      const r = popRef.current.getBoundingClientRect();
      setPopRect((prev) => (prev && prev.left === r.left && prev.top === r.top && prev.width === r.width && prev.height === r.height ? prev : { left: r.left, top: r.top, width: r.width, height: r.height }));
    } else setPopRect((prev) => (prev == null ? prev : null));
  }, [open, hasHoverPanel, visibleItems.length, query]);
  const nonDefault = compact && items.length > 0 && (multi ? multiVals.length > 0 : String(value) !== String(items[0].value));
  const currentLabel = multi
    ? (multiVals.length === 0 ? (items[0] ? items[0].label : '') : items.filter((it) => multiVals.includes(it.value)).map((it) => it.label).join(', '))
    : (current ? (current.short != null ? current.short : current.label) : (value || ''));
  const chipLabel = multi && multiVals.length > 0 ? `${label} (${multiVals.length})` : label;
  return (
    <div className={'tb-select' + (full ? ' full' : '')} style={style}>
      {compact ? (() => {
        // An active (non-default) non-subtle filter chip becomes self-describing: it shows the chosen
        // option's own glyph (a platform mark, a target app icon) + its label, instead of the generic
        // icon + filter name + a bare "active" dot. The dot stays for multi-selects (where the chip
        // can't name every pick) and the subtle inline pickers keep their existing label behavior.
        const activeItem = !subtle && nonDefault && !multi ? current : null;
        const lead = activeItem && activeItem.glyph
          ? <span style={{ display: 'inline-flex', flex: '0 0 auto', lineHeight: 0 }}>{activeItem.glyph}</span>
          : ((ico || !subtle) ? <Ico n={ico || 'sliders-horizontal'} s={13} /> : null);
        const text = activeItem ? (activeItem.short != null ? activeItem.short : activeItem.label) : chipLabel;
        return (
          <button type="button" ref={ctrlRef} className={'tb-select-chip' + (subtle ? ' subtle' : '') + (activeItem ? ' active' : '')} aria-haspopup="listbox" aria-expanded={open}
            title={(title ? title + ' - ' : '') + (label ? label + ': ' : '') + currentLabel}
            onClick={toggle} onKeyDown={onKeyDown} {...rest}>
            {lead}
            <span>{text}</span>
            {nonDefault && !subtle && multi && <span className="tb-chip-dot" />}
          </button>
        );
      })() : (
        <button type="button" ref={ctrlRef} className="tb-select-control" title={title} aria-haspopup="listbox" aria-expanded={open}
          onClick={toggle} onKeyDown={onKeyDown} {...rest}>
          {label && <span className="tb-select-label">{label}</span>}
          <span className="tb-select-value">{currentLabel}</span>
          <span className="tb-select-chev"><Ico n="chevron-down" s={14} /></span>
        </button>
      )}
      {open && rect && ReactDOM.createPortal(
        <div ref={popRef} className="tb-select-pop" role="listbox" style={popStyle}>
          {searchable && (
            <div style={{ position: 'sticky', top: 0, zIndex: 1, background: 'var(--bg-elevated, #1a1a1c)', padding: 6, borderBottom: '1px solid var(--tb-hairline)' }}>
              <input autoFocus value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search…" spellCheck={false}
                onKeyDown={(e) => {
                  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); const n = visibleItems.length, d = e.key === 'ArrowDown' ? 1 : -1; setActive((i) => (n ? (i < 0 ? 0 : (i + d + n) % n) : -1)); }
                  else if (e.key === 'Enter') { e.preventDefault(); const pick = active >= 0 ? visibleItems[active] : (visibleItems.length === 1 ? visibleItems[0] : null); if (pick) choose(pick.value); }
                  else if (e.key === 'Escape') { e.preventDefault(); setOpen(false); }
                }}
                style={{ width: '100%', boxSizing: 'border-box', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 7, outline: 'none', color: 'var(--text-standard)', padding: '6px 9px', fontSize: 12.5 }} />
            </div>
          )}
          {visibleItems.length === 0 && (
            <div className="tb-sub" style={{ padding: '9px 12px', fontSize: 12 }}>No matches.</div>
          )}
          {visibleItems.map((it, i) => {
            const sel = isPicked(it.value);
            if (it.ico || it.badges || it.meta || it.desc) {
              return (
                <OptionRow key={String(it.value)} dense ico={it.ico} icoColor={it.icoColor}
                  name={it.label} badges={it.badges || []} meta={it.meta} desc={it.desc} trailing={it.trailing}
                  selected={sel} active={i === active}
                  onMouseEnter={() => setActive(i)} onClick={() => choose(it.value)} />
              );
            }
            return (
              <div key={String(it.value)} role="option" aria-selected={sel}
                className={'tb-select-opt' + (sel ? ' sel' : '') + (i === active ? ' active' : '')}
                onMouseEnter={() => setActive(i)} onClick={() => choose(it.value)}>
                <Ico n="check" s={13} cls="tb-select-tick" style={{ opacity: sel ? 1 : 0 }} />
                {it.glyph && <span style={{ display: 'inline-flex', flex: '0 0 auto', lineHeight: 0 }}>{it.glyph}</span>}
                <span>{it.label}</span>
              </div>
            );
          })}
        </div>,
        document.body,
      )}
      {/* Docs side-panel: when a hoverPanel is provided, the hovered/active option's docs render in a
          read-only popup beside the dropdown — to its left when there's room, else to its right. */}
      {open && rect && hoverPanel && active >= 0 && visibleItems[active] && (() => {
        const content = hoverPanel(visibleItems[active].value);
        if (!content) return null;
        const DW = 300, gp = 8, m = 8;
        const fitsLeft = rect.left - gp - DW >= m;
        const left = fitsLeft ? rect.left - gp - DW : ((popRect ? popRect.left + popRect.width : rect.left + 340) + gp);
        const top = popRect ? popRect.top : rect.bottom + 4;
        const maxHeight = popRect ? popRect.height : Math.min(400, window.innerHeight - (rect.bottom + 4) - m);
        return ReactDOM.createPortal(
          // Allow pointer events so long docs scroll; stop mousedown so interacting doesn't close the select.
          <div className="tb-card" onMouseDown={(e) => e.stopPropagation()} style={{ position: 'fixed', left, top, width: DW, maxHeight, overflow: 'auto', zIndex: 9999, padding: '11px 13px', background: 'var(--bg-elevated)', boxShadow: '0 18px 50px rgba(0,0,0,.55)' }}>
            {content}
          </div>,
          document.body,
        );
      })()}
    </div>
  );
}

// A scannable "table row" for option lists — the one grammar shared by the rich Select popup
// (trailhead picker, proposal tool dropdown) and the full-catalog tool palette, so they all read
// identically: an icon column for wayfinding, a dominant name plus secondary badge chips, an
// optional mono meta line (a selector strategy, a param signature) and a clamped description, with
// a trailing slot (arg count) and a check when selected. Visual hierarchy (ch07): name dominant,
// badges/meta subordinate, description quietest.
function OptionRow({ ico, icoColor, name, badges = [], meta, desc, trailing, selected, active, dense, onClick, onMouseEnter, onKeyDown, tabIndex }) {
  useLucide();
  return (
    <div role="option" aria-selected={!!selected} tabIndex={tabIndex}
      className={'tb-opt-row' + (selected ? ' sel' : '') + (active ? ' active' : '') + (dense ? ' dense' : '')}
      onClick={onClick} onMouseEnter={onMouseEnter} onKeyDown={onKeyDown}>
      <span className="tb-opt-ico"><Ico n={ico || 'wrench'} s={15} c={icoColor || 'var(--text-subtle-variant)'} /></span>
      <div className="tb-opt-main">
        <div className="tb-opt-line">
          <span className="tb-opt-name tb-mono">{name}</span>
          {(badges || []).map((b, i) => <span key={i} className={'tb-chip' + (b.tone ? ' ' + b.tone : '')}>{b.text}</span>)}
        </div>
        {meta != null && meta !== '' && <div className="tb-opt-meta">{meta}</div>}
        {desc && <div className="tb-opt-desc">{desc}</div>}
      </div>
      {(trailing != null || selected) && (
        <span className="tb-opt-trail">
          {trailing}
          {selected && <Ico n="check" s={14} cls="tb-opt-check" />}
        </span>
      )}
    </div>
  );
}

// ── Run controls: subtle pickers for the model + agent that drive AI runs/recordings ──────────
// Both edit GLOBAL persisted settings (the same ones the Settings screen + run dispatch read), so
// they read consistently everywhere they're shown and a run/recording uses whatever is selected.
// Styled `compact subtle` to match the low-chrome reference picker. Render nothing until the
// daemon reports live LLM settings.

// Picks the LLM model (provider+model carried as one "provider/model" value). Collapsed chip shows
// just the model id; the dropdown rows add "· Provider".
function ModelPicker() {
  const settings = TB.useSettings();
  const s = settings.data;
  if (!s || s.available === false || !s.llm) return null;
  const llm = s.llm;
  const cur = (llm.provider && llm.model) ? llm.provider + '/' + llm.model : '';
  const providerName = (id) => {
    const p = (llm.availableProviders || []).find((x) => x.id === id);
    return (p && p.display) || id;
  };
  const options = (() => {
    const list = (llm.availableModels || []).map((m) => [m.provider + '/' + m.id, m.id + '  ·  ' + providerName(m.provider)]);
    if (cur && !list.some(([v]) => v === cur)) list.unshift([cur, llm.model + '  ·  ' + providerName(llm.provider)]);
    return list;
  })();
  return (
    <Select compact subtle value={cur} options={options} label={llm.model || 'model'} title="Model driving AI runs"
      onChange={(e) => {
        const v = e.target.value;
        const slash = v.indexOf('/');
        if (slash < 0) return;
        TB.updateSetting({ llmProvider: v.slice(0, slash), llmModel: v.slice(slash + 1) }).then(() => settings.reload());
      }} />
  );
}

// Picks the agent implementation (TRAILBLAZE_RUNNER / MULTI_AGENT_V3 / KOOG_STRATEGY_GRAPH) that owns
// the run loop. Collapsed chip shows the friendly agent name; persists to the global agent setting.
function AgentPicker() {
  const settings = TB.useSettings();
  const s = settings.data;
  if (!s || s.available === false || !s.llm) return null;
  const agents = (s.llm.availableAgents || []);
  if (agents.length === 0) return null;
  const cur = s.llm.agent || '';
  const display = (agents.find((a) => a.id === cur) || {}).display || cur || 'agent';
  return (
    <Select compact subtle value={cur} options={agents.map((a) => [a.id, a.display])} label={display} title="Agent driving the run"
      onChange={(e) => TB.updateSetting({ agent: e.target.value }).then(() => settings.reload())} />
  );
}

// The model + agent pickers as one unit, for run/recording surfaces. `showAgent={false}` for
// model-only contexts (e.g. step proposal, which doesn't run the agent loop).
function RunControls({ showAgent = true }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <ModelPicker />
      {showAgent && <AgentPicker />}
    </span>
  );
}

// The shared header for every left list-rail (Tools, Toolsets, Waypoints, Shortcuts,
// Trailheads, Devices, Trails, Active, Completed). One source of truth for the rail
// header's padding, title size, leading-icon size, and right-action alignment so they
// all look identical. `help` is an inline node placed after the title (e.g. a HelpDot);
// `right` holds action buttons; `sub` is an optional one-line description under the title.
function RailHeader({ ico, iconColor, title, help, right, sub }) {
  return (
    <div style={{ padding: '16px 14px 8px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, minHeight: 30 }}>
        <h2 className="tb-h2" style={{ fontSize: 16, display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          {ico && <Ico n={ico} s={17} c={iconColor || 'var(--text-subtle-variant)'} style={{ flex: '0 0 auto' }} />}
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{title}</span>
          {help}
        </h2>
        {right ? <div style={{ display: 'flex', alignItems: 'center', flex: '0 0 auto' }}>{right}</div> : null}
      </div>
      {sub ? <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 3, lineHeight: 1.4 }}>{sub}</div> : null}
    </div>
  );
}

function ScreenHead({ eyebrow, title, sub, right, ico, iconColor }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
      <div style={{ flex: '1 1 auto', minWidth: 0 }}>
        {eyebrow && <div className="tb-eyebrow" style={{ marginBottom: 7 }}>{eyebrow}</div>}
        <h1 className="tb-h1" style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>{ico && <Ico n={ico} s={21} c={iconColor || 'var(--text-subtle-variant)'} style={{ flex: '0 0 auto' }} />}<span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>{title}</span></h1>
        {sub && <div className="tb-sub" style={{ marginTop: 6 }}>{sub}</div>}
      </div>
      {right}
    </div>
  );
}

// Shared header for every detail/primary view (Tools detail, Trails detail,
// Blaze) so they read as one app. Order: context line → title row (optional
// leading control + title + inline badges) → meta rows; primary action(s)
// right-aligned, top-aligned. Callers render their own tb-tabs right after.
// `eyebrow` is the small uppercase label (Blaze). The title is always the
// regular sans tb-h1 at 20px — identifiers (tool ids) included — so every
// detail view reads the same; monospace is reserved for the context line and
// inline code, never the title.
function DetailHeader({ context, eyebrow, title, badges, meta, right, leading }) {
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16 }}>
        <div style={{ flex: '1 1 auto', minWidth: 0 }}>
          {eyebrow && <div className="tb-eyebrow" style={{ marginBottom: 7 }}>{eyebrow}</div>}
          {context && <div className="tb-mono tb-sub" data-selectable style={{ fontSize: 11.5, marginBottom: 4, overflow: 'hidden', textOverflow: 'ellipsis' }}>{context}</div>}
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', minWidth: 0 }}>
            {leading}
            <h1 className="tb-h1" data-selectable style={{ fontSize: 20, margin: 0, minWidth: 0 }}>{title}</h1>
            {badges}
          </div>
          {meta && <div style={{ display: 'flex', flexDirection: 'column', gap: 5, fontSize: 11.5, marginTop: 10 }}>{meta}</div>}
        </div>
        {right && <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 8 }}>{right}</div>}
      </div>
    </div>
  );
}

function useStickyState(key, initial) {
  const [v, setV] = React.useState(() => {
    try { const raw = window.localStorage.getItem(key); return raw == null ? initial : JSON.parse(raw); } catch (_) { return initial; }
  });
  React.useEffect(() => {
    try { window.localStorage.setItem(key, JSON.stringify(v)); } catch (_) {}
  }, [key, v]);
  return [v, setV];
}

function useResizableWidth(key, initial, min, max) {
  const clamp = (v) => Math.min(max, Math.max(min, v));
  const [w, setW] = React.useState(() => {
    const v = parseInt(window.localStorage.getItem(key) || '', 10);
    return Number.isFinite(v) ? clamp(v) : initial;
  });
  const wRef = React.useRef(w); wRef.current = w;
  const cleanupRef = React.useRef(null);
  const startDrag = (e) => {
    e.preventDefault();
    const startX = e.clientX, startW = wRef.current;
    const onMove = (ev) => setW(clamp(startW + (ev.clientX - startX)));
    const cleanup = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      document.body.style.cursor = '';
      cleanupRef.current = null;
    };
    const onUp = () => { cleanup(); window.localStorage.setItem(key, String(wRef.current)); };
    cleanupRef.current = cleanup;
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    document.body.style.cursor = 'col-resize';
  };
  React.useEffect(() => () => { if (cleanupRef.current) cleanupRef.current(); }, []);
  return [w, startDrag];
}

function Splitter({ onDown }) {
  return <div className="tb-colsplit" data-testid="col-splitter" onMouseDown={onDown} title="Drag to resize" />;
}

function applyTheme(mode) {
  const m = mode || 'system';
  const prefersLight = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches;
  const dark = m === 'dark' || (m === 'system' && !prefersLight);
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
}
function useThemeController() {
  React.useEffect(() => {
    const read = () => { try { return JSON.parse(window.localStorage.getItem('tb-theme')) || 'system'; } catch (_) { return 'system'; } };
    applyTheme(read());
    const mq = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)');
    if (!mq) return;
    const onChange = () => { if (read() === 'system') applyTheme('system'); };
    mq.addEventListener ? mq.addEventListener('change', onChange) : mq.addListener(onChange);
    return () => { mq.removeEventListener ? mq.removeEventListener('change', onChange) : mq.removeListener(onChange); };
  }, []);
}

const SUPPORTS_HIGHLIGHT = typeof CSS !== 'undefined' && !!CSS.highlights && typeof Highlight !== 'undefined';

function SearchableText({ text = '', language, fontSize = 12, minHeight, background = '#0a0a0a' }) {
  const [q, setQ] = React.useState('');
  const [active, setActive] = React.useState(0);
  const codeRef = React.useRef(null);
  const wrapRef = React.useRef(null);
  const query = q;

  const hlId = React.useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const findName = 'tb-find-' + hlId;
  const activeName = 'tb-find-active-' + hlId;
  React.useEffect(() => {
    if (!SUPPORTS_HIGHLIGHT) return;
    const style = document.createElement('style');
    style.textContent = '::highlight(' + findName + '){background-color:rgba(245,176,65,.32)} ::highlight(' + activeName + '){background-color:#f5b041;color:#000}';
    document.head.appendChild(style);
    return () => style.remove();
  }, []);

  const matches = React.useMemo(() => {
    if (!query) return [];
    const out = []; const hay = text.toLowerCase(); const needle = query.toLowerCase();
    let i = hay.indexOf(needle);
    while (i !== -1 && out.length < 5000) { out.push(i); i = hay.indexOf(needle, i + needle.length); }
    return out;
  }, [text, query]);
  React.useEffect(() => { setActive(0); }, [query, text]);

  React.useEffect(() => {
    if (!language || !codeRef.current || !window.hljs) return;
    if (!SUPPORTS_HIGHLIGHT && query) return;
    const el = codeRef.current;
    el.textContent = text;
    delete el.dataset.highlighted;
    try { window.hljs.highlightElement(el); } catch (e) {}
  }, [text, language, query]);

  React.useEffect(() => {
    if (!SUPPORTS_HIGHLIGHT) return;
    const clear = () => { try { CSS.highlights.delete(findName); CSS.highlights.delete(activeName); } catch (e) {} };
    const root = codeRef.current;
    if (!root || !query || !matches.length) { clear(); return; }
    if (root.textContent !== text) { clear(); return; }
    const n = query.length;
    const segs = []; let acc = 0;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    for (let nd = walker.nextNode(); nd; nd = walker.nextNode()) { segs.push({ node: nd, start: acc, len: nd.nodeValue.length }); acc += nd.nodeValue.length; }
    const locate = (off) => {
      let lo = 0, hi = segs.length - 1, ans = segs.length - 1;
      while (lo <= hi) { const mid = (lo + hi) >> 1; if (segs[mid].start + segs[mid].len >= off) { ans = mid; hi = mid - 1; } else lo = mid + 1; }
      const s = segs[ans]; return s ? { node: s.node, o: Math.min(s.len, Math.max(0, off - s.start)) } : null;
    };
    const rangeAt = (start, length) => {
      const s = locate(start), e = locate(start + length);
      if (!s || !e) return null;
      try { const r = document.createRange(); r.setStart(s.node, s.o); r.setEnd(e.node, e.o); return r; } catch (err) { return null; }
    };
    const all = new Highlight(); const act = new Highlight(); let activeRange = null;
    matches.forEach((m, mi) => { const r = rangeAt(m, n); if (!r) return; if (mi === active) { act.add(r); activeRange = r; } else all.add(r); });
    CSS.highlights.set(findName, all);
    CSS.highlights.set(activeName, act);
    if (activeRange && wrapRef.current) {
      const rect = activeRange.getBoundingClientRect();
      const wrap = wrapRef.current; const wr = wrap.getBoundingClientRect();
      if (rect.height) wrap.scrollTo({ top: wrap.scrollTop + (rect.top - wr.top) - wr.height / 2 + rect.height / 2, behavior: 'smooth' });
    }
    return clear;
  }, [matches, active, query, text, language]);

  React.useEffect(() => {
    if (SUPPORTS_HIGHLIGHT || !query || !wrapRef.current) return;
    const el = wrapRef.current.querySelector('mark[data-active="1"]');
    if (el && el.scrollIntoView) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [active, query, matches.length]);

  const step = (delta) => { if (matches.length) setActive((a) => (a + delta + matches.length) % matches.length); };
  const onKey = (e) => {
    if (e.key === 'Enter') { e.preventDefault(); step(e.shiftKey ? -1 : 1); }
    else if (e.key === 'Escape') { e.preventDefault(); setQ(''); }
  };

  React.useEffect(() => {
    const onDocKey = (e) => {
      if ((e.key === 'g' || e.key === 'G') && (e.metaKey || e.ctrlKey)) {
        if (!q || !matches.length) return;
        if (!wrapRef.current || wrapRef.current.offsetParent === null) return;
        e.preventDefault();
        step(e.shiftKey ? -1 : 1);
      }
    };
    document.addEventListener('keydown', onDocKey);
    return () => document.removeEventListener('keydown', onDocKey);
  }, [q, matches.length]);

  // The block background is a fixed dark (#0a0a0a, terminal-style for code/errors), so pin the text
  // to a fixed light too — otherwise in light mode it inherits the dark theme text color and renders
  // dark-on-dark (unreadable). Matches the HelpCard snippet convention (#0a0a0a bg + #cbd5e1 text).
  const codeStyle = { fontFamily: 'var(--font-mono)', fontSize, lineHeight: 1.6, color: '#cbd5e1', background: 'transparent', padding: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', display: 'block' };
  let body;
  if (SUPPORTS_HIGHLIGHT) {
    body = <code ref={codeRef} data-selectable className={language ? 'language-' + language : 'tb-mono'} style={codeStyle}>{text}</code>;
  } else if (query && matches.length) {
    const n = query.length; const segs = []; let pos = 0;
    matches.forEach((m, mi) => {
      if (m > pos) segs.push(text.slice(pos, m));
      const on = mi === active;
      segs.push(<mark key={'m' + mi} data-active={on ? '1' : '0'} style={{ background: on ? '#f5b041' : 'rgba(245,176,65,.32)', color: on ? '#000' : 'inherit', borderRadius: 2, padding: '0 1px' }}>{text.slice(m, m + n)}</mark>);
      pos = m + n;
    });
    if (pos < text.length) segs.push(text.slice(pos));
    body = <code className="tb-mono" style={codeStyle}>{segs}</code>;
  } else if (query) {
    body = <code className="tb-mono" style={codeStyle}>{text}</code>;
  } else {
    body = <code ref={codeRef} data-selectable className={language ? 'language-' + language : 'tb-mono'} style={codeStyle}>{text}</code>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, flex: '0 0 auto' }}>
        <div className="tb-input" style={{ flex: 1, minWidth: 0 }}>
          <Ico n="search" s={14} />
          <input value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={onKey} placeholder="Find in text…" spellCheck={false}
            style={{ background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-standard)', width: '100%', fontSize: 12.5 }} />
        </div>
        {q && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, flex: '0 0 auto' }}>
            <span className="tb-mono tb-sub" style={{ fontSize: 11, minWidth: 56, textAlign: 'right' }}>{matches.length ? (active + 1) + ' / ' + matches.length : 'no matches'}</span>
            <button className="tb-btn sm" disabled={!matches.length} onClick={() => step(-1)} title="Previous (⇧⏎ · ⌘⇧G)" style={{ width: 28, padding: 0, justifyContent: 'center' }}><Ico n="chevron-up" s={15} /></button>
            <button className="tb-btn sm" disabled={!matches.length} onClick={() => step(1)} title="Next (⏎ · ⌘G)" style={{ width: 28, padding: 0, justifyContent: 'center' }}><Ico n="chevron-down" s={15} /></button>
            <button className="tb-btn sm" onClick={() => setQ('')} title="Clear (Esc)" style={{ width: 28, padding: 0, justifyContent: 'center' }}><Ico n="x" s={14} /></button>
          </div>
        )}
      </div>
      <pre ref={wrapRef} style={{ margin: 0, flex: 1, minHeight: minHeight || 0, overflow: 'auto', background, border: '1px solid var(--tb-hairline)', borderRadius: 10, padding: '12px 14px' }}>{body}</pre>
    </div>
  );
}

// The real app icon for a target, served by the daemon from the bundled
// app_icon_<id>.png. Falls back to a generic glyph if the target has no icon or it fails
// to load. Kept the same box size as the fallback so layouts don't shift.
function AppIcon({ target, size = 18, radius = 5, fallback = 'package', fallbackColor }) {
  const [failed, setFailed] = React.useState(false);
  React.useEffect(() => { setFailed(false); }, [target]);
  if (!target || failed) {
    return <Ico n={fallback} s={size} c={fallbackColor || 'var(--text-subtle-variant)'} style={{ flex: '0 0 auto' }} />;
  }
  return (
    <img src={`/trailrunner/api/app-icon/${encodeURIComponent(target)}`} alt="" width={size} height={size}
      onError={() => setFailed(true)}
      style={{ flex: '0 0 auto', width: size, height: size, borderRadius: radius, objectFit: 'cover', display: 'block' }} />
  );
}

// The "?" help affordance. Two modes:
//  • Given `children` (the help content): self-contained — clicking drops a popover anchored
//    directly under the button, sliding in from beneath it (ease-in-out). Stays adjacent to the
//    button instead of flying to a far-right drawer. Portaled + fixed-positioned so it never clips.
//  • Legacy (`onClick` only): just a button that calls onClick (kept for callers not yet migrated).
// `align`: which button edge the popover hangs from — 'right' (default, for top-right buttons,
// extends left) or 'left' (extends right). Clamped on-screen either way.
function HelpButton({ onClick, title, sub, align = 'right', children }) {
  useLucide();
  const btnRef = React.useRef(null);
  const [open, setOpen] = React.useState(false);
  const [shown, setShown] = React.useState(false);
  const [rect, setRect] = React.useState(null);
  const reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const openPop = () => { if (btnRef.current) setRect(btnRef.current.getBoundingClientRect()); setOpen(true); };
  const close = React.useCallback(() => { setShown(false); window.setTimeout(() => setOpen(false), reduce ? 0 : 170); }, [reduce]);
  React.useEffect(() => {
    if (!open) { setShown(false); return; }
    const r = requestAnimationFrame(() => setShown(true));
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    // Re-anchor on resize, but do NOT close on scroll: the popover content scrolls internally, and
    // that scroll must not dismiss it. It stays open until an outside click, the "?" toggle, or Esc.
    const onResize = () => { if (btnRef.current) setRect(btnRef.current.getBoundingClientRect()); };
    window.addEventListener('keydown', onKey);
    window.addEventListener('resize', onResize);
    return () => { cancelAnimationFrame(r); window.removeEventListener('keydown', onKey); window.removeEventListener('resize', onResize); };
  }, [open, close]);
  // Legacy mode — no inline content, defer to the caller's handler.
  if (!children) {
    return (
      <button className="tb-btn ghost sm" style={{ padding: 6 }} title={title || 'How this works'} onClick={onClick} data-testid="help-button">
        <Ico n="circle-help" s={16} />
      </button>
    );
  }
  const W = 400, gap = 8, margin = 10;
  const pos = rect ? (() => {
    const top = rect.bottom + gap;
    let left = align === 'left' ? rect.left : rect.right - W;
    left = Math.max(margin, Math.min(left, window.innerWidth - W - margin));
    return { left, top, width: W, maxHeight: Math.min(560, window.innerHeight - top - margin) };
  })() : {};
  return (
    <span style={{ display: 'inline-flex' }}>
      <button ref={btnRef} className="tb-btn ghost sm" style={{ padding: 6 }} title={title || 'How this works'}
        onClick={(e) => { e.stopPropagation(); open ? close() : openPop(); }} data-testid="help-button">
        <Ico n="circle-help" s={16} />
      </button>
      {open && rect && ReactDOM.createPortal(
        <React.Fragment>
          <div style={{ position: 'fixed', inset: 0, zIndex: 80 }} onClick={close} onContextMenu={(e) => { e.preventDefault(); close(); }} />
          <div className="tb-card" onClick={(e) => e.stopPropagation()}
            style={{ position: 'fixed', ...pos, zIndex: 81, background: 'var(--bg-elevated)', boxShadow: '0 18px 50px rgba(0,0,0,.5)', display: 'flex', flexDirection: 'column', minHeight: 0, padding: 0, overflow: 'hidden',
              transform: shown ? 'translateY(0)' : 'translateY(-8px)', opacity: shown ? 1 : 0,
              transition: reduce ? 'none' : 'transform 190ms ease-in-out, opacity 190ms ease-in-out', transformOrigin: 'top', willChange: 'transform, opacity' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '13px 14px', borderBottom: '1px solid var(--tb-hairline)', flex: '0 0 auto' }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--text-standard)' }}>{title}</div>
                {sub && <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.55, marginTop: 4 }}>{sub}</div>}
              </div>
              <span role="button" onClick={close} title="Close" style={{ cursor: 'pointer', color: 'var(--text-subtle)', flex: '0 0 auto' }}><Ico n="x" s={16} /></span>
            </div>
            <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 12, display: 'flex', flexDirection: 'column', gap: 10 }}>{children}</div>
          </div>
        </React.Fragment>,
        document.body,
      )}
    </span>
  );
}

// A compact, single-topic help popover anchored to a small "?" next to a title.
// Use instead of HelpOverlay when one term needs one explanation in place — no modal.
function HelpDot({ ico, color, title, tag, foot, align = 'left', children }) {
  useLucide();
  const [open, setOpen] = React.useState(false);
  React.useEffect(() => {
    if (!open) return;
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
  return (
    <span style={{ position: 'relative', display: 'inline-flex', alignItems: 'center' }}>
      <button className="tb-btn ghost sm" style={{ padding: 3, height: 'auto', minHeight: 0, lineHeight: 0 }}
        title="What's this?" aria-label="What's this?" onClick={(e) => { e.stopPropagation(); setOpen((v) => !v); }}>
        <Ico n="circle-help" s={14} c="var(--text-subtle)" />
      </button>
      {open && (
        <React.Fragment>
          <div style={{ position: 'fixed', inset: 0, zIndex: 70 }} onClick={(e) => { e.stopPropagation(); setOpen(false); }} onContextMenu={(e) => { e.preventDefault(); setOpen(false); }} />
          <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ position: 'absolute', top: 'calc(100% + 8px)', [align]: 0, zIndex: 71, width: 320, maxWidth: '80vw', padding: 14, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)', cursor: 'default', whiteSpace: 'normal', fontWeight: 400 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              {ico && <Ico n={ico} s={16} c={color || 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />}
              <span style={{ fontWeight: 600, fontSize: 13.5, flex: 1, color: 'var(--text-standard)' }}>{title}</span>
              {tag && <Chip>{tag}</Chip>}
            </div>
            <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.6 }}>{children}</div>
            {foot && <div className="tb-mono tb-sub" style={{ fontSize: 10.5, marginTop: 10 }}>{foot}</div>}
          </div>
        </React.Fragment>
      )}
    </span>
  );
}

function HelpCard({ ico, color, title, tag, tagTone, when, snippet, foot, children }) {
  const c = color || 'var(--text-subtle)';
  return (
    <div className="tb-card" style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 10, background: 'var(--bg-standard)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        {ico && <Ico n={ico} s={17} c={c} />}
        <span style={{ fontWeight: 600, fontSize: 14, flex: 1 }}>{title}</span>
        {tag && <Chip tone={tagTone || ''}>{tag}</Chip>}
      </div>
      <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.6 }}>{children}</div>
      {when && <div style={{ fontSize: 12, lineHeight: 1.6 }}><span style={{ color: c, fontWeight: 600 }}>When: </span><span className="tb-sub">{when}</span></div>}
      {snippet && (
        <pre data-selectable style={{ margin: 0, background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '10px 12px', overflowX: 'auto', fontSize: 11, lineHeight: 1.55 }}>
          <code className="tb-mono" style={{ color: '#cbd5e1', whiteSpace: 'pre' }}>{snippet}</code>
        </pre>
      )}
      {foot && <div className="tb-mono tb-sub" style={{ fontSize: 10.5, marginTop: 'auto' }}>{foot}</div>}
    </div>
  );
}

// A documentation panel that slides in from the right as a full-height drawer. Cards
// stack vertically at a readable width (long lines were the problem with the old
// centered full-width modal). `minCardWidth` is accepted for backwards-compat but unused
// now that cards are a single column.
function HelpOverlay({ title, sub, onClose, minCardWidth, children }) {
  useLucide();
  React.useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return (
    <div onClick={onClose} data-testid="help-overlay"
      style={{ position: 'fixed', inset: 0, zIndex: 80, background: 'rgba(0,0,0,.55)', display: 'flex', justifyContent: 'flex-end' }}>
      <div onClick={(e) => e.stopPropagation()} className="tb-help-drawer"
        style={{ width: 'min(480px, 94vw)', height: '100%', background: 'var(--bg-subtle)', borderLeft: '1px solid var(--tb-hairline)', boxShadow: '-24px 0 60px rgba(0,0,0,.5)', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, padding: '18px 20px', borderBottom: '1px solid var(--tb-hairline)', flex: '0 0 auto' }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <h2 className="tb-h2" style={{ fontSize: 18, margin: 0 }}>{title}</h2>
            {sub && <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.6, marginTop: 6 }}>{sub}</div>}
          </div>
          <Btn sm ico="x" onClick={onClose}>Close</Btn>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          {children}
        </div>
      </div>
    </div>
  );
}

// Hover tooltip styled like the help popovers (elevated card + soft shadow), shown immediately on
// hover/focus with none of the native `title` delay. Portaled + fixed so it never clips, clamped to
// the viewport, and flips above the anchor when there's no room below. `tip` is a string or node;
// when empty, the children render bare (no wrapper behavior). Replaces native `title` attributes.
function HoverTip({ tip, children, gap = 8, maxWidth = 340, place = 'bottom', style }) {
  const ref = React.useRef(null);
  const tipRef = React.useRef(null);
  const [anchor, setAnchor] = React.useState(null);
  const [pos, setPos] = React.useState(null);
  const reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const show = () => { if (ref.current) setAnchor(ref.current.getBoundingClientRect()); };
  const hide = () => { setAnchor(null); setPos(null); };
  React.useLayoutEffect(() => {
    if (!anchor || !tipRef.current) return;
    const t = tipRef.current.getBoundingClientRect();
    const margin = 10;
    let left = anchor.left + anchor.width / 2 - t.width / 2;
    left = Math.max(margin, Math.min(left, window.innerWidth - t.width - margin));
    let top = place === 'top' ? anchor.top - t.height - gap : anchor.bottom + gap;
    if (place !== 'top' && top + t.height > window.innerHeight - margin) top = anchor.top - t.height - gap;
    top = Math.max(margin, top);
    setPos({ left, top });
  }, [anchor, tip, place, gap]);
  if (tip == null || tip === '') return <React.Fragment>{children}</React.Fragment>;
  return (
    <span ref={ref} onMouseEnter={show} onMouseLeave={hide} onFocus={show} onBlur={hide}
      style={{ display: 'inline-flex', ...style }}>
      {children}
      {anchor && ReactDOM.createPortal(
        <div ref={tipRef} role="tooltip" className="tb-card"
          style={{ position: 'fixed', top: pos ? pos.top : anchor.bottom + gap, left: pos ? pos.left : anchor.left,
            zIndex: 90, maxWidth, padding: '9px 12px', background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)',
            color: 'var(--text-standard)', fontSize: 12.5, fontWeight: 400, lineHeight: 1.55, whiteSpace: 'normal', pointerEvents: 'none',
            opacity: pos ? 1 : 0, transition: reduce ? 'none' : 'opacity 100ms ease-out' }}>
          {tip}
        </div>,
        document.body,
      )}
    </span>
  );
}

// The "scoped by your target" footer banner shown at the bottom of a list rail (Trails, Tools,
// and the Trailmaps component pages). Makes it obvious the list is filtered by the active target
// picked in the target picker, and offers a one-click escape back to the full list. Rendered only
// when `label` is set (i.e. a target is actually scoping the list).
function TargetScopeBanner({ label, platform, onShowAll }) {
  useLucide();
  if (!label) return null;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px', borderTop: '1px solid var(--tb-hairline)', background: 'rgba(0,224,19,.06)' }}>
      <Ico n="crosshair" s={13} c="var(--tb-pass)" style={{ flex: '0 0 auto' }} />
      <span className="tb-sub" style={{ fontSize: 11.5, flex: 1, minWidth: 0, lineHeight: 1.4 }}>
        Filtered to <span style={{ color: 'var(--tb-pass)', fontWeight: 600 }}>{label}</span>{platform ? <> · <span style={{ color: 'var(--text-standard)', fontWeight: 600 }}>{platform}</span></> : null} from your target
      </span>
      {onShowAll && <button className="tb-btn ghost sm" style={{ padding: '2px 8px', fontSize: 11, flex: '0 0 auto' }} title="Show everything (ignore the target filter)" onClick={onShowAll}>Show all</button>}
    </div>
  );
}

Object.assign(window, { listNavKeyDown, clickable, useLucide, Ico, AppIcon, Dot, Chip, StatusChip, STATUS, Btn, Switch, Select, OptionRow, ScreenHead, RailHeader, useResizableWidth, useStickyState, applyTheme, useThemeController, Splitter, SearchableText, HelpButton, HelpDot, HelpCard, HelpOverlay, HoverTip, DetailHeader, TargetScopeBanner });
