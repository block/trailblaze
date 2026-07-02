// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function shQuote(v) {
  const s = String(v);
  if (s === '') return "''";
  if (/^[A-Za-z0-9_./:@=+,-]+$/.test(s)) return s;
  return "'" + s.replace(/'/g, "'\\''") + "'";
}

function buildRunCommand(cfg) {
  const parts = ['trailblaze', 'run'];
  parts.push(cfg.trailPath || cfg.trailId || '<trail>');

  if (cfg.devicePlatform) {
    parts.push('--device', cfg.deviceId ? `${cfg.devicePlatform}/${cfg.deviceId}` : cfg.devicePlatform);
  }

  if (cfg.selfHeal) parts.push('--self-heal');
  if (cfg.useRecordedSteps === 'replay') parts.push('--use-recorded-steps');
  else if (cfg.useRecordedSteps === 'ai') parts.push('--no-use-recorded-steps');
  if (cfg.agent && cfg.agent !== 'TRAILBLAZE_RUNNER') parts.push('--agent', cfg.agent);
  if (cfg.maxLlmCalls !== '' && String(cfg.maxLlmCalls) !== '50') parts.push('--max-llm-calls', String(cfg.maxLlmCalls));
  if ((cfg.llm || '').trim()) parts.push('--llm', shQuote(cfg.llm.trim()));
  if (cfg.verbose) parts.push('--verbose');
  if (cfg.devicePlatform === 'web' && !cfg.headless) parts.push('--no-headless');
  if (!cfg.captureVideo) parts.push('--no-capture-video');
  if (cfg.captureLogcat) parts.push('--capture-logcat');
  if (cfg.captureNetwork) parts.push('--capture-network');
  if (cfg.captureIosLogs) parts.push('--capture-ios-logs');
  if (cfg.captureAnalytics) parts.push('--capture-analytics');
  if (!cfg.saveRecording) parts.push('--no-save-recording');
  if (cfg.noReport) parts.push('--no-report');
  if (cfg.markdown) parts.push('--markdown');
  if (cfg.noLogging) parts.push('--no-logging');
  if ((cfg.tags || '').trim()) parts.push('--tags', shQuote(cfg.tags.trim()));

  return parts.join(' ');
}

function applyYamlOverrides(yaml, ov) {
  if (!yaml) return yaml || '';
  const lines = yaml.split('\n');
  const indentOf = (s) => s.match(/^(\s*)/)[1].length;
  let inConfig = false, configIndent = 0, childIndent = -1;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const cfgMatch = line.match(/^(\s*)(?:-\s+)?config:\s*$/);
    if (cfgMatch) { inConfig = true; configIndent = cfgMatch[1].length; childIndent = -1; continue; }
    if (inConfig) {
      if (line.trim() === '') continue;
      const ind = indentOf(line);
      if (ind <= configIndent) { inConfig = false; continue; }
      if (childIndent < 0) childIndent = ind;
      if (ind !== childIndent) continue;
      const m = line.match(/^(\s+)(target|platform|driver):[ \t]*(.*)$/);
      if (m && ov[m[2]] != null && ov[m[2]] !== '') lines[i] = `${m[1]}${m[2]}: ${ov[m[2]]}`;
    }
  }
  return lines.join('\n');
}

Object.assign(window, { shQuote, buildRunCommand, applyYamlOverrides });
