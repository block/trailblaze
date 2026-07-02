function RunYamlScreen({ go, initialYaml, initialName }) {
  useLucide();
  const devices = TB.useDevices();
  const [gt] = TB.useGlobalTarget();
  const fileRef = React.useRef(null);
  const [fileName, setFileName] = React.useState('');
  const [yaml, setYaml] = React.useState('');
  const [deviceId, setDeviceId] = React.useState('');
  const [validation, setValidation] = React.useState(null);
  const [validationStatus, setValidationStatus] = React.useState('idle');
  const [running, setRunning] = React.useState(false);
  const [error, setError] = React.useState(null);
  const appliedInitialRef = React.useRef(null);

  const deviceList = devices.data || [];
  const connectedDevices = deviceList.filter((d) => d.connected !== false);
  const gtDeviceIdsKey = ((gt && gt.deviceIds) || []).join(',');
  React.useEffect(() => {
    const preferred = ((gt && gt.deviceIds) || []).find((id) => connectedDevices.some((d) => d.id === id));
    const fallback = (connectedDevices[0] || deviceList[0] || {}).id || '';
    const next = preferred || fallback;
    if (!deviceId && next) setDeviceId(next);
    if (deviceId && deviceList.length && !deviceList.some((d) => d.id === deviceId)) setDeviceId(next);
    if (deviceId && next && next !== deviceId && connectedDevices.length && !connectedDevices.some((d) => d.id === deviceId)) setDeviceId(next);
  }, [devices.data, gtDeviceIdsKey, deviceId]);

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

  const parsed = React.useMemo(() => parseTrailYaml(yaml || ''), [yaml]);
  const cfg = parsed.ok ? (parsed.config || {}) : {};
  const selected = deviceList.find((d) => d.id === deviceId) || null;
  const validationAllowsRun = validationStatus === 'valid' || validationStatus === 'unavailable';
  const selectedOffline = selected && selected.connected === false;
  const canRun = yaml.trim() && selected && !selectedOffline && !running && validationAllowsRun;

  const applyYaml = (name, text) => {
    setFileName(name || '');
    setYaml(text || '');
    setValidation(null);
    setValidationStatus((text || '').trim() ? 'pending' : 'idle');
    setError(null);
  };

  React.useEffect(() => {
    if (!initialYaml) return;
    const key = String(initialName || '') + '\n' + String(initialYaml);
    if (appliedInitialRef.current === key) return;
    appliedInitialRef.current = key;
    applyYaml(initialName || 'Sample YAML', initialYaml);
  }, [initialYaml, initialName]);

  const onFile = async (e) => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    const text = await file.text();
    applyYaml(file.name, text);
    e.target.value = '';
  };

  const run = async () => {
    if (!canRun) return;
    setRunning(true);
    setError(null);
    try {
      const tbId = selected.trailblazeDeviceId || {
        instanceId: selected.id,
        trailblazeDevicePlatform: String(selected.platform || '').toUpperCase(),
      };
      if (selected.connected === false) {
        setError('Select a connected device before running YAML.');
        return;
      }
      const connected = selected.platform === 'web' ? true : await TB.connectDevice(tbId);
      if (!connected) {
        setError('Could not connect to the selected device.');
        return;
      }
      const resp = await TB.dispatchRun(tbId, yaml);
      if (!resp || resp.ok === false || resp.success === false) {
        setError((resp && resp.error) || 'Could not start the run.');
        return;
      }
      TB.recordPendingRun({
        title: cfg.title || fileName || 'Pasted YAML',
        target: cfg.target,
        device: selected.name || selected.id,
      });
      go('active', { followLive: Date.now() });
    } catch (e) {
      setError((e && e.message) || 'Could not start the run.');
    } finally {
      setRunning(false);
    }
  };

  const deviceOptions = deviceList.map((d) => [
    d.id,
    `${d.name || d.id}${d.connected === false ? '  · offline' : ''}`,
  ]);

  return (
    <div className="tb-in" style={{ padding: '28px 32px 60px', overflowY: 'auto', height: '100%' }}>
      <ScreenHead ico="braces" title="Run YAML" sub="Paste or load a trail YAML definition and run it immediately on a connected device." />
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
            <textarea value={yaml} onChange={(e) => applyYaml(fileName, e.target.value)} spellCheck={false}
              placeholder={'- config:\n    title: "Ad hoc run"\n- prompts:\n  - step: "Open the app"'}
              style={{ width: '100%', minHeight: 420, display: 'block', resize: 'vertical', border: 'none', outline: 'none', background: '#0a0a0a', color: '#d7dee8', padding: '14px 16px', fontFamily: 'var(--font-mono)', fontSize: 12.5, lineHeight: 1.55, tabSize: 2 }} />
          </div>
        </div>

        <div style={{ display: 'grid', gap: 12, minWidth: 0 }}>
          <div className="tb-card pad">
            <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Device</div>
            {deviceList.length === 0 ? (
              <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5 }}>No devices connected. Open Devices to connect one.</div>
            ) : (
              <Select full value={deviceId || ''} onChange={(e) => setDeviceId(e.target.value)} options={deviceOptions} />
            )}
            {selected && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10 }}>
                <PlatformGlyph platform={selected.platform} s={15} c="var(--text-subtle)" />
                <span className="tb-mono tb-sub" style={{ fontSize: 11.5 }}>{selected.platform || 'device'} · {selected.id}</span>
                <span style={{ flex: 1 }} />
                <Chip tone={selected.connected === false ? 'red' : 'green'}>{selected.connected === false ? 'Offline' : 'Connected'}</Chip>
              </div>
            )}
          </div>

          <ImportConfigSummary yaml={yaml} />
          <ValidationPanel yaml={yaml} validation={validation} status={validationStatus} />

          {error && <div role="alert" style={{ color: 'var(--tb-danger-text)', fontSize: 12.5, lineHeight: 1.5 }}>{error}</div>}
          {!error && selectedOffline && <div role="alert" style={{ color: 'var(--tb-danger-text)', fontSize: 12.5, lineHeight: 1.5 }}>Select a connected device before running YAML.</div>}
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
            <Btn kind="ghost" ico="download" onClick={() => go('import')}>Import instead</Btn>
            <Btn kind="primary" ico={running ? 'loader-2' : 'play'} spin={running} onClick={run} disabled={!canRun}>
              {running ? 'Starting…' : 'Run YAML'}
            </Btn>
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { RunYamlScreen });
