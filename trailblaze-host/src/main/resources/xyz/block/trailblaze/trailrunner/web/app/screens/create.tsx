// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// Blaze → Create. An objective → proposed steps → a new draft blaze (which you then edit &
// record in Drafts). The shared `ModelPicker` (from ui.jsx) shows/edits the model that drives step
// proposal; the agent picker is intentionally omitted here since proposing is a direct LLM call,
// not an agent-loop run (the agent picker shows up on the Record/Run surfaces instead).
function CreateScreen({ go }) {
  useLucide();
  const [gt] = TB.useGlobalTarget();
  const devices = TB.useDevices();
  const [objective, setObjective] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState(null);

  const target = (gt && gt.target) || null;
  const label = (gt && (gt.label || gt.target)) || 'No target selected';
  // Platform comes from the first selected device, if any (used to bias the proposer).
  const firstDevice = gt && (gt.deviceIds || []).length
    ? (devices.data || []).find((d) => d.id === gt.deviceIds[0])
    : null;
  const platform = firstDevice ? firstDevice.platform : null;
  const can = objective.trim().length > 0 && !busy;

  async function create() {
    if (!can) return;
    const obj = objective.trim();
    setBusy(true); setErr(null);
    const r = await TB.proposeSteps(obj, { target, platform });
    if (r.error || !r.steps || r.steps.length === 0) {
      setBusy(false);
      setErr(r.error || 'The model did not return any steps. Try rephrasing the objective.');
      return;
    }
    const title = obj.length > 56 ? obj.slice(0, 53) + '…' : obj;
    // No destination here — it's chosen later at "Finalize and Save" in the draft detail.
    const yaml = TB.buildBlazeYaml(title, target, platform, obj, r.steps);
    const created = await TB.createDraft(title, yaml);
    setBusy(false);
    if (!created.success) { setErr(created.error || 'Could not create the draft.'); return; }
    go('drafts', { sel: created.id });
  }

  return (
    <div style={{ padding: '30px 40px 60px', maxWidth: 900, margin: '0 auto', overflowY: 'auto', height: '100%' }}>
      <RailHeader ico="sparkles" iconColor="var(--tb-ai)" title="Create a blaze"
        sub="Describe the goal in plain language. Trail Runner proposes the steps, you refine them, then record on your devices." />

      <div style={{ marginTop: 20, border: '1px solid var(--tb-hairline)', borderRadius: 12, background: 'var(--bg-subtle)', padding: 14 }}>
        <textarea value={objective} onChange={(e) => setObjective(e.target.value)}
          placeholder="e.g. Sign in, then send $5 to the first person in my contacts"
          onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); create(); } }}
          style={{ width: '100%', minHeight: 76, resize: 'vertical', background: 'transparent', border: 'none', outline: 'none',
            color: 'var(--text-standard)', font: 'inherit', fontSize: 16, lineHeight: 1.5 }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, borderTop: '1px solid var(--tb-hairline)', paddingTop: 12, marginTop: 8, flexWrap: 'wrap' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--text-subtle)' }}>
            <AppIcon target={target} size={16} fallbackColor="var(--text-subtle-variant)" />
            {label}
          </span>
          <span style={{ flex: 1 }} />
          <ModelPicker />
          <Btn kind="primary" ico={busy ? 'loader-2' : 'arrow-right'} spin={busy} onClick={create} disabled={!can}>
            {busy ? 'Proposing…' : 'Create blaze'}
          </Btn>
        </div>
      </div>

      {err && <div style={{ marginTop: 12, color: 'var(--tb-danger-text)', fontSize: 13 }}>{err}</div>}

      <p className="tb-sub" style={{ marginTop: 22, fontSize: 12.5, maxWidth: 640 }}>
        Trail Runner drafts the steps from your prompt. You refine them, then record on your devices in Drafts.
      </p>
    </div>
  );
}
