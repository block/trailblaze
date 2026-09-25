// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// The build-time transpile strips types regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function shQuote(v) {
  const s = String(v);
  if (s === '') return "''";
  if (/^[A-Za-z0-9_./:@=+,-]+$/.test(s)) return s;
  return "'" + s.replace(/'/g, "'\\''") + "'";
}

function seedRunAgent(currentAgent, effectiveAgent, touched) {
  return !touched && effectiveAgent ? effectiveAgent : currentAgent;
}

function runAgentOption(agent) {
  return agent || null;
}

/**
 * `seedRunAgent` for the memory toggle, where `null` means "not known yet". The dialog sends this
 * value as a per-run override and the daemon reads a null override as "use the saved setting", so
 * an unseeded dialog has to stay null: seeding a bare `false` makes a Run clicked before settings
 * arrive silently disable capture even though Settings has it on.
 */
function seedCaptureMemory(current, saved, touched) {
  if (touched || saved == null) return current;
  return !!saved;
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
  if (cfg.agent) parts.push('--agent', cfg.agent);
  if (cfg.maxLlmCalls !== '' && String(cfg.maxLlmCalls) !== String(DEFAULT_MAX_LLM_CALLS)) parts.push('--max-llm-calls', String(cfg.maxLlmCalls));
  if ((cfg.llm || '').trim()) parts.push('--llm', shQuote(cfg.llm.trim()));
  if (cfg.verbose) parts.push('--verbose');
  if (cfg.devicePlatform === 'web' && !cfg.headless) parts.push('--no-headless');
  if (cfg.captureVideo) parts.push('--capture-video');
  if (cfg.captureLogcat) parts.push('--capture-logcat');
  if (cfg.captureNetwork) parts.push('--capture-network');
  if (cfg.captureIosLogs) parts.push('--capture-ios-logs');
  // Memory capture is ON by default in the CLI, so omitting the flag does not mean "off" the way it
  // does for video and network — the negative spelling is what carries the toggle. Only an explicit
  // `false` spells it: a null/absent value is "no override", which no CLI flag can express, and
  // printing `--no-capture-memory` for it would show a command that turns capture off for a run
  // that actually defers to the saved setting.
  if (cfg.captureMemory === false) parts.push('--no-capture-memory');
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

Object.assign(window, { shQuote, seedRunAgent, runAgentOption, seedCaptureMemory, buildRunCommand, applyYamlOverrides });
