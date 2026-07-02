function normalizeImportPath(path) {
  return String(path || '')
    .trim()
    .replace(/\\/g, '/')
    .replace(/^\/+|\/+$/g, '')
    .replace(/\.trail\.ya?ml$/i, '')
    .replace(/\/+/g, '/');
}

function importPathError(path) {
  if (/^\s*[\\/]/.test(String(path || ''))) return 'Use a workspace-relative path, not an absolute path.';
  const normalized = normalizeImportPath(path);
  if (!normalized) return 'Enter a workspace-relative path.';
  const segments = normalized.split('/');
  if (segments.some((seg) => !seg || seg === '.' || seg === '..')) {
    return 'Use a relative path inside the workspace; "." and ".." are not allowed.';
  }
  if (segments.some((seg) => /[\u0000-\u001f\\]/.test(seg))) {
    return 'Path segments cannot contain control characters or backslashes.';
  }
  return null;
}

function slugifyImportName(value, fallback = 'imported-trail') {
  const slug = String(value || '')
    .toLowerCase()
    .replace(/\.trail\.ya?ml$/i, '')
    .replace(/[^a-z0-9._/-]+/g, '-')
    .replace(/[-_]{2,}/g, '-')
    .replace(/^[-./]+|[-./]+$/g, '');
  return slug || fallback;
}

function suggestedImportPath(fileName, yaml) {
  const parsed = parseTrailYaml(yaml || '');
  const cfg = parsed.ok ? (parsed.config || {}) : {};
  const title = cfg.title || cfg.name || fileName || 'imported trail';
  const stem = slugifyImportName(fileName || title);
  const target = slugifyImportName(cfg.target || '', '').replace(/\//g, '-');
  const platform = slugifyImportName(cfg.platform || '', '').replace(/\//g, '-');
  if (target && platform && cfg.title) return `${target}/${slugifyImportName(cfg.title)}/${platform}`;
  if (target && cfg.title) return `${target}/${slugifyImportName(cfg.title)}`;
  return `imported/${stem}`;
}

function trailValidationStatus(res) {
  if (!res) return 'unavailable';
  if (res.valid === true) return 'valid';
  if (res.valid === false) return 'invalid';
  return res.unavailable ? 'unavailable' : 'invalid';
}

function resolveImportedTrailId(savedPath, roots, fallbackRelPath) {
  const cleanRel = normalizeImportPath(fallbackRelPath);
  const allRoots = [roots && roots.primary, ...((roots && roots.extras) || [])].filter(Boolean);
  const saved = String(savedPath || '').replace(/\\/g, '/');
  for (let i = 0; i < allRoots.length; i++) {
    const root = String(allRoots[i]).replace(/\\/g, '/').replace(/\/+$/, '');
    if (!root || !(saved === root || saved.startsWith(root + '/'))) continue;
    const rel = saved.slice(root.length + 1).replace(/\.trail\.yaml$/, '');
    return `${i}/${normalizeImportPath(rel)}`;
  }
  return `0/${cleanRel}`;
}

function ImportConfigSummary({ yaml }) {
  const parsed = React.useMemo(() => parseTrailYaml(yaml || ''), [yaml]);
  if (!yaml.trim()) {
    return (
      <div className="tb-card pad" style={{ color: 'var(--text-subtle)', fontSize: 12.5 }}>
        Choose a file or paste trail YAML to preview its config.
      </div>
    );
  }
  if (!parsed.ok) {
    return (
      <div className="tb-card pad" style={{ color: 'var(--tb-danger-text)', fontSize: 12.5, lineHeight: 1.5 }}>
        Could not parse YAML: {parsed.error}
      </div>
    );
  }
  const cfg = parsed.config || {};
  const rows = flattenObject(cfg, { joinArray: (a) => a.join(', ') });
  return (
    <div className="tb-card pad">
      <div className="tb-eyebrow" style={{ marginBottom: 10 }}>Detected config</div>
      {rows.length === 0 ? (
        <div className="tb-sub" style={{ fontSize: 12.5 }}>No config block found.</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '7px 12px', alignItems: 'baseline' }}>
          {rows.slice(0, 10).map(({ path, value }) => (
            <React.Fragment key={path}>
              <span className="tb-sub" style={{ fontSize: 11.5 }}>{path}</span>
              <span className="tb-mono" style={{ fontSize: 12.5, color: 'var(--text-standard)', wordBreak: 'break-word' }}>{value}</span>
            </React.Fragment>
          ))}
          {rows.length > 10 && (
            <React.Fragment>
              <span className="tb-sub" style={{ fontSize: 11.5 }}>more</span>
              <span className="tb-sub" style={{ fontSize: 12.5 }}>{rows.length - 10} additional key{rows.length - 10 === 1 ? '' : 's'}</span>
            </React.Fragment>
          )}
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
        <Chip tone={parsed.prompts.length ? 'green' : ''}>{parsed.prompts.length} step{parsed.prompts.length === 1 ? '' : 's'}</Chip>
        <Chip tone={fileToolCount(parsed) ? 'blue' : ''}>{fileToolCount(parsed)} recorded tool{fileToolCount(parsed) === 1 ? '' : 's'}</Chip>
      </div>
    </div>
  );
}

function ValidationPanel({ yaml, validation, status }) {
  if (!yaml.trim()) return null;
  if (status === 'pending') {
    return <div className="tb-card pad" style={{ fontSize: 12.5 }}><Ico n="loader-2" s={14} spin /> Validating YAML…</div>;
  }
  if (status === 'unavailable') {
    return (
      <div className="tb-card pad" style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>
        Validation is unavailable. You can still review the YAML before importing.
      </div>
    );
  }
  if (validation.valid === true) {
    return (
      <div className="tb-card pad" style={{ fontSize: 12.5, color: 'var(--tb-pass)', display: 'flex', alignItems: 'center', gap: 8 }}>
        <Ico n="check-circle-2" s={15} c="var(--tb-pass)" /> YAML is valid.
      </div>
    );
  }
  const errors = validation.errors || [];
  return (
    <div className="tb-card pad" style={{ fontSize: 12.5, color: 'var(--tb-danger-text)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 600, marginBottom: errors.length ? 8 : 0 }}>
        <Ico n="x-circle" s={15} c="var(--tb-danger-text)" /> YAML has validation errors.
      </div>
      {errors.map((e, i) => (
        <div key={i} className="tb-mono" style={{ fontSize: 11.5, lineHeight: 1.5, marginTop: 4 }}>
          {e.line ? `line ${e.line}: ` : ''}{e.message || String(e)}
        </div>
      ))}
    </div>
  );
}

function ImportScreen({ go }) {
  useLucide();
  const trails = TB.useTrails();
  const roots = TB.useTrailRoots();
  const fileRef = React.useRef(null);
  const [fileName, setFileName] = React.useState('');
  const [yaml, setYaml] = React.useState('');
  const [dest, setDest] = React.useState('');
  const [validation, setValidation] = React.useState(null);
  const [validationStatus, setValidationStatus] = React.useState('idle');
  const [saving, setSaving] = React.useState(false);
  const [error, setError] = React.useState(null);

  React.useEffect(() => {
    const body = yaml.trim();
    setValidation(null);
    if (!body) { setValidationStatus('idle'); return; }
    let cancelled = false;
    setValidationStatus('pending');
    const t = setTimeout(() => {
      TB.validateTrail(body).then((res) => {
        if (cancelled) return;
        setValidation(res);
        setValidationStatus(trailValidationStatus(res));
      }).catch(() => {
        if (!cancelled) {
          setValidation(null);
          setValidationStatus('unavailable');
        }
      });
    }, 250);
    return () => { cancelled = true; clearTimeout(t); };
  }, [yaml]);

  const applyYaml = (name, text) => {
    setFileName(name || '');
    setYaml(text || '');
    setValidation(null);
    setValidationStatus((text || '').trim() ? 'pending' : 'idle');
    setDest(suggestedImportPath(name, text || ''));
    setError(null);
  };

  const onFile = async (e) => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    const text = await file.text();
    applyYaml(file.name, text);
    e.target.value = '';
  };

  const normalizedDest = normalizeImportPath(dest);
  const existing = React.useMemo(() => {
    const want = normalizedDest + '.trail.yaml';
    return (trails.data || []).some((t) => normalizeImportPath(t.path || t.id) === normalizedDest || normalizeImportPath(t.path || t.id) === normalizeImportPath(want));
  }, [trails.data, normalizedDest]);
  const destError = importPathError(dest);
  const validationAllowsImport = validationStatus === 'valid' || validationStatus === 'unavailable';
  const canImport = yaml.trim() && normalizedDest && !destError && !existing && !saving && validationAllowsImport;

  const doImport = async () => {
    if (!canImport) return;
    setSaving(true); setError(null);
    const res = await TB.createTrail(normalizedDest, yaml);
    setSaving(false);
    if (!res || !res.success) {
      setError((res && res.error) || 'Could not import that trail.');
      return;
    }
    window.dispatchEvent(new CustomEvent('tb:workspace-changed'));
    go('trails', { sel: resolveImportedTrailId(res.savedPath, roots.data, normalizedDest), mode: 'edit' });
  };

  return (
    <div className="tb-in" style={{ padding: '28px 32px 60px', overflowY: 'auto', height: '100%' }}>
      <ScreenHead ico="download" title="Import trail" sub="Bring an existing .trail.yaml file into this workspace." />
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1.45fr) minmax(280px, .8fr)', gap: 18, alignItems: 'start', marginTop: 18 }}>
        <div style={{ minWidth: 0 }}>
          <div className="tb-card" style={{ padding: 16, marginBottom: 14 }}>
            <input ref={fileRef} type="file" accept=".yaml,.yml,.trail.yaml,text/yaml,text/plain" onChange={onFile} style={{ display: 'none' }} />
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <Btn ico="file-up" onClick={() => fileRef.current && fileRef.current.click()}>Choose YAML</Btn>
              {fileName && <Chip tone="blue">{fileName}</Chip>}
              <span className="tb-sub" style={{ fontSize: 12.5 }}>or paste YAML below</span>
            </div>
          </div>

          <div className="tb-card" style={{ overflow: 'hidden' }}>
            <div style={{ padding: '11px 14px', borderBottom: '1px solid var(--tb-hairline)', display: 'flex', alignItems: 'center', gap: 10 }}>
              <Ico n="braces" s={15} c="var(--text-subtle)" />
              <span style={{ fontSize: 13.5, fontWeight: 600 }}>Trail YAML</span>
              <span style={{ flex: 1 }} />
              <Btn sm kind="ghost" ico="trash-2" onClick={() => applyYaml('', '')} disabled={!yaml.trim()}>Clear</Btn>
            </div>
            <textarea value={yaml} onChange={(e) => {
              const next = e.target.value;
              setValidation(null);
              setValidationStatus(next.trim() ? 'pending' : 'idle');
              setYaml(next);
              if (!dest.trim()) setDest(suggestedImportPath(fileName, next));
            }} spellCheck={false}
              placeholder={'- config:\n    title: "Imported trail"\n- prompts:\n  - step: "Open the app"'}
              style={{ width: '100%', minHeight: 360, display: 'block', resize: 'vertical', border: 'none', outline: 'none', background: '#0a0a0a', color: '#d7dee8', padding: '14px 16px', fontFamily: 'var(--font-mono)', fontSize: 12.5, lineHeight: 1.55, tabSize: 2 }} />
          </div>
        </div>

        <div style={{ display: 'grid', gap: 12, minWidth: 0 }}>
          <div className="tb-card pad">
            <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Destination</div>
            <label style={{ display: 'grid', gap: 6, fontSize: 12.5 }}>
              <span className="tb-sub">Workspace-relative path</span>
              <input value={dest} onChange={(e) => setDest(e.target.value)} spellCheck={false}
                placeholder="imported/login"
                style={{ width: '100%', boxSizing: 'border-box', background: 'var(--bg-subtle)', color: 'var(--text-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '8px 10px', fontFamily: 'var(--font-mono)', fontSize: 12.5 }} />
            </label>
            <div className="tb-mono tb-sub" style={{ marginTop: 8, fontSize: 11.5, wordBreak: 'break-word' }}>
              {normalizedDest ? normalizedDest + '.trail.yaml' : 'Enter a path to import.'}
            </div>
            {destError && <div role="alert" style={{ marginTop: 8, color: 'var(--tb-danger-text)', fontSize: 12 }}>{destError}</div>}
            {existing && <div role="alert" style={{ marginTop: 8, color: 'var(--tb-danger-text)', fontSize: 12 }}>A trail already exists at this path.</div>}
          </div>

          <ImportConfigSummary yaml={yaml} />
          <ValidationPanel yaml={yaml} validation={validation} status={validationStatus} />

          {error && <div role="alert" style={{ color: 'var(--tb-danger-text)', fontSize: 12.5, lineHeight: 1.5 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
            <Btn kind="ghost" ico="list" onClick={() => go('trails')}>Browse trails</Btn>
            <Btn kind="primary" ico={saving ? 'loader-2' : 'download'} spin={saving} onClick={doImport} disabled={!canImport}>
              {saving ? 'Importing…' : 'Import trail'}
            </Btn>
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  ImportScreen, ImportConfigSummary, ValidationPanel,
  normalizeImportPath, slugifyImportName, suggestedImportPath, trailValidationStatus, resolveImportedTrailId,
});
