// Shared design tokens for every standalone Trailblaze report.
//
// Components consume semantic roles, not palette values:
//   mark    small status dots and icons; tuned for crisp recognition
//   text    readable semantic copy
//   surface quiet state fills
//   border  state boundaries
// Typography primarily uses body and emphasis weights. Strong is reserved for compact navigation
// labels that need to remain legible beside dense report content.
//
// Light mode starts from GitHub Primer's neutral and functional palette. Trailblaze owns the
// outcome marks: they are deliberately clearer and more saturated than Primer's text colors.
// Dark mode implements the same semantic API with independently tuned values.
export const REPORT_DESIGN_TOKENS_CSS = `
:root {
  color-scheme: light;
  --neutral-1: #f6f8fa; --neutral-2: #ffffff; --neutral-3: #f6f8fa; --neutral-4: #eff2f5;
  --neutral-5: #d1d9e0b3; --neutral-6: #d1d9e0; --neutral-7: #afb8c1; --neutral-8: #818b98;
  --neutral-9: #6e7781; --neutral-10: #59636e; --neutral-11: #59636e; --neutral-12: #1f2328;
  --accent-1: #ffffff; --accent-2: #f6f8fa; --accent-3: #ddf4ff; --accent-4: #b6e3ff;
  --accent-5: #80ccff; --accent-6: #54aeff; --accent-7: #218bff; --accent-8: #0969da;
  --accent-9: #0969da; --accent-10: #0860ca; --accent-11: #0969da; --accent-12: #0a3069;
  --cyan-3: #ddf4ff; --cyan-9: #0969da; --cyan-11: #0969da;
  --violet-3: #fbefff; --violet-9: #8250df; --violet-11: #8250df;
  --forest-3: #dafbe1; --forest-9: #2da44e; --forest-11: #116329;
  --error-3: #ffebe9; --error-9: #ff818266; --error-11: #d1242f;
  --success-3: #dafbe1; --success-9: #4ac26b66; --success-11: #1a7f37;
  --warning-3: #fff8c5; --warning-9: #d4a72c66; --warning-11: #9a6700;
  --info-3: #ddf4ff; --info-9: #54aeff66; --info-11: #0969da;

  --status-passed-mark: #2da44e;
  --status-passed-text: var(--success-11);
  --status-passed-surface: var(--success-3);
  --status-passed-border: var(--success-9);
  --status-self-healed-mark: #d97706;
  --status-self-healed-text: var(--warning-11);
  --status-self-healed-surface: var(--warning-3);
  --status-self-healed-border: var(--warning-9);
  --status-failed-mark: #cf222e;
  --status-failed-text: var(--error-11);
  --status-failed-surface: var(--error-3);
  --status-failed-border: var(--error-9);

  --bg: var(--neutral-1); --bg2: var(--neutral-2); --bg3: var(--neutral-3); --raised: var(--neutral-2);
  --header: var(--neutral-1); --button-hover: var(--neutral-4);
  --control-counter-surface: var(--neutral-4); --control-counter-text: var(--neutral-12);
  --step-token-surface: var(--neutral-6); --step-token-text: var(--neutral-12);
  --line: var(--neutral-5); --line2: var(--neutral-6);
  --txt: var(--neutral-12); --sub: var(--neutral-11); --sub2: var(--neutral-12);
  --pass: var(--status-passed-mark); --fail: var(--status-failed-mark); --amber: var(--status-self-healed-mark);
  --run: var(--accent-11); --purple: var(--violet-11); --ai: var(--violet-9); --ai-surface: var(--violet-3);
  --trail-mark: var(--forest-9); --trail-text: var(--forest-11); --trail-surface: var(--forest-3);
  --event: var(--cyan-11); --focus: var(--accent-9); --player-line: var(--neutral-6);
  --danger-surface: var(--status-failed-surface); --danger-border: var(--status-failed-border); --danger-text: var(--status-failed-text);
  --warning-surface: var(--status-self-healed-surface); --warning-border: var(--status-self-healed-border); --warning-text: var(--status-self-healed-text);
  --success-surface: var(--status-passed-surface); --success-border: var(--status-passed-border); --success-text: var(--status-passed-text);
  --accent-surface: var(--accent-3); --violet-surface: var(--violet-3); --code-surface: var(--neutral-2); --code-text: var(--neutral-12);
  --r-sm: 4px; --r-md: 6px; --r-lg: 8px;
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px; --space-5: 24px; --space-6: 32px;
  --type-micro: 10px; --type-caption: 11px; --type-small: 12px; --type-body: 14px; --type-title: 24px;
  --font-weight-body: 400; --font-weight-emphasis: 500; --font-weight-strong: 500;
  --page-x: var(--space-6); --page-y: var(--space-5); --content-wide: 1120px; --content-reading: 720px; --control-height: 32px;
  --shadow-raised: 0 0 0 1px #d1d9e040, 0 6px 12px -3px #25292e0a, 0 6px 18px #25292e1f;
  --shadow-device: 0 1px 2px #25292e0a, 0 8px 20px #25292e12;
}
[data-theme="dark"] {
  color-scheme: dark;
  --neutral-1: #121313; --neutral-2: #19191a; --neutral-3: #212224; --neutral-4: #282a2d;
  --neutral-5: #303236; --neutral-6: #393c41; --neutral-7: #454950; --neutral-8: #5b6169;
  --neutral-9: #7a818b; --neutral-10: #8c939d; --neutral-11: #b3b8be; --neutral-12: #e5e8ec;
  --accent-1: #111315; --accent-2: #171a1e; --accent-3: #1c232d; --accent-4: #202b3a;
  --accent-5: #243348; --accent-6: #293c59; --accent-7: #324a6d; --accent-8: #43618e;
  --accent-9: #5a81bb; --accent-10: #6e94cb; --accent-11: #a0b9de; --accent-12: #dae9ff;
  --cyan-3: #1b2428; --cyan-9: #86bdd6; --cyan-11: #9dbdcc;
  --violet-3: #302647; --violet-9: #6457ac; --violet-11: #b4b0e8;
  --forest-3: #17291c; --forest-9: #3fb950; --forest-11: #7ee787;
  --error-3: #2d1d1c; --error-9: #c56c65; --error-11: #e0a7a1;
  --success-3: #1a261a; --success-9: #84cc86; --success-11: #9bc49b;
  --warning-3: #262219; --warning-9: #ceb47e; --warning-11: #c5b696;
  --info-3: #1b2329; --info-9: #7aabce; --info-11: #9fbcd1;

  --status-passed-mark: #3fb950;
  --status-passed-text: #76e99a;
  --status-passed-surface: var(--success-3);
  --status-passed-border: #3fb950;
  --status-self-healed-mark: #f0b429;
  --status-self-healed-text: #ffd27a;
  --status-self-healed-surface: var(--warning-3);
  --status-self-healed-border: #f0b429;
  --status-failed-mark: #f85149;
  --status-failed-text: #ff969d;
  --status-failed-surface: var(--error-3);
  --status-failed-border: #f85149;

  --bg: var(--neutral-1); --bg2: var(--neutral-2); --bg3: var(--neutral-3); --raised: var(--neutral-2);
  --header: var(--neutral-1); --button-hover: var(--neutral-4);
  --control-counter-surface: var(--neutral-4); --control-counter-text: var(--neutral-12);
  --step-token-surface: var(--neutral-6); --step-token-text: var(--neutral-12);
  --line: var(--neutral-4); --line2: var(--neutral-6);
  --txt: var(--neutral-12); --sub: var(--neutral-11); --sub2: var(--neutral-12);
  --pass: var(--status-passed-mark); --fail: var(--status-failed-mark); --amber: var(--status-self-healed-mark);
  --run: #6aa6ff; --purple: #b08cff; --ai: #c29aff; --ai-surface: var(--violet-3);
  --trail-mark: var(--forest-9); --trail-text: var(--forest-11); --trail-surface: var(--forest-3);
  --event: #5ed3ff; --focus: #91bdff; --player-line: var(--neutral-6);
  --danger-surface: var(--status-failed-surface); --danger-border: var(--status-failed-border); --danger-text: var(--status-failed-text);
  --warning-surface: var(--status-self-healed-surface); --warning-border: var(--status-self-healed-border); --warning-text: var(--status-self-healed-text);
  --success-surface: var(--status-passed-surface); --success-border: var(--status-passed-border); --success-text: var(--status-passed-text);
  --accent-surface: var(--accent-3); --violet-surface: var(--violet-3); --code-surface: var(--neutral-1); --code-text: var(--neutral-12);
  --shadow-raised: 0 18px 48px color-mix(in srgb,var(--accent-1) 76%,transparent), 0 2px 8px color-mix(in srgb,var(--accent-1) 82%,transparent);
  --shadow-device: 0 1px 3px rgba(0,0,0,.32), 0 8px 20px rgba(0,0,0,.22);
}`;
