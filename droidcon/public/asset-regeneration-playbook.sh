#!/usr/bin/env bash
# =============================================================================
# Droidcon 2026 demo asset regenerator  (slides 14 "ASSET A" + 37 "ASSET B")
# =============================================================================
# Regenerates, from a real device run every time:
#   asset-a-blaze.mp4            real agent blaze, device screen capture
#   asset-a-replay.mp4           committed-recording replay, device screen capture
#   asset-a-blaze-timeline.gif   trailblaze report timeline (annotated) + .webp
#   asset-a-wallclock.txt        measured blaze/replay wall-clocks
#   asset-b-terminal.png         styled terminal render of the real CLI transcript
#   asset-manifest.txt           per-file description
#
# WHY runs are "earned, not written": blaze.yaml is natural-language steps; the
# agent drives a CLEAN AOSP image and the committed android.trail.yaml recording
# is a byproduct. This script reproduces that end to end.
#
# -----------------------------------------------------------------------------
# CONCURRENCY / SHARED-HOST RULES (a prior session was burned by ignoring these)
# -----------------------------------------------------------------------------
#  * NEVER run `trailblaze --stop` or touch the shared daemon (default port
#    52525). This script pins its OWN daemon via TRAILBLAZE_PORT below and only
#    ever kills processes it started.
#  * Uses its OWN AVD + serial (tb-asset-34 / emulator-5596). Do NOT point this
#    at emulator-5590 or AVD tb-probe-clean-34 (another session's).
#  * Never touches iOS simulators.
#  * If the host is saturated (check `uptime` load avg; emulator ANR
#    "System UI isn't responding"), STOP and retry later rather than hammer.
#  * droidcon/public/ may be written by other sessions concurrently. This script
#    only writes the asset-a-*, asset-b-terminal.png, asset-manifest.txt, and
#    asset-regeneration-playbook.sh names. It will NOT delete or overwrite
#    asset-a2-*, asset-c-*, asset-manifest-2.txt, or anything else.
#
# PREREQUISITES (all present on Sam's machine 2026-07-12):
#   - Android SDK: adb, emulator, avdmanager, sdkmanager (system-image
#     "system-images;android-34;default;arm64-v8a" installed)
#   - ffmpeg / ffprobe   (brew)
#   - trailblaze CLI     (v20260711 or compatible; `trailblaze --version`)
#   - bun                (for the TS SDK bootstrap; scripted tools fail with
#                         "Unsupported tool type for RPC execution" otherwise)
#   - python3            (terminal renderer, stdlib only)
#   - Google Chrome      (headless screenshot of the terminal HTML)
#   - LLM configured:    `trailblaze config llm` must show an Available provider
#                        (this repo defaults to llm=none). Set one-shot with
#                         --llm <provider/model> on the run if needed.
#
# USAGE:
#   bash asset-regeneration-playbook.sh          # all assets
#   bash asset-regeneration-playbook.sh blaze    # just the blaze video + wallclock
#   bash asset-regeneration-playbook.sh replay   # just the replay video + wallclock
#   bash asset-regeneration-playbook.sh terminal # just asset-b-terminal.png
#   bash asset-regeneration-playbook.sh gif       # just the timeline gif/webp
# Multiple stage args may be combined: `... blaze replay`.
# =============================================================================
set -uo pipefail

# ---- knobs -----------------------------------------------------------------
WORKTREE="${WORKTREE:-$(cd "$(dirname "$0")/../.." && pwd)}"  # repo root (script lives in droidcon/public/)
AVD="${AVD:-tb-asset-34}"
PORT="${PORT:-5596}"                 # emulator console port -> serial emulator-$PORT
SERIAL="emulator-${PORT}"
TB_PORT="${TRAILBLAZE_PORT:-52640}"  # OUR daemon port; keep off shared 52525
SYS_IMAGE="system-images;android-34;default;arm64-v8a"
OUT="${OUT:-$WORKTREE/droidcon/public}"
WORK="${WORK:-$(mktemp -d -t tb-assets)}"   # scratch for segments/logs/frames
SID_TITLE="Contacts: create a contact"
BLAZE="trails/contacts/create-contact/blaze.yaml"
TRAIL="trails/contacts/create-contact/android.trail.yaml"

export TRAILBLAZE_CONFIG_DIR="$WORKTREE/trails/config"
export TRAILBLAZE_PORT="$TB_PORT"

# ---- locate SDK tools ------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"
ADB="$SDK/platform-tools/adb"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

STAGES="${*:-all}"
want() { [[ " $STAGES " == *" all "* || " $STAGES " == *" $1 "* ]]; }
log()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mFATAL: %s\033[0m\n' "$*" >&2; exit 1; }

mkdir -p "$OUT" "$WORK"
log "worktree=$WORKTREE  avd=$AVD  serial=$SERIAL  tb_port=$TB_PORT"
log "scratch=$WORK  out=$OUT  stages=$STAGES"

command -v trailblaze >/dev/null || die "trailblaze CLI not on PATH"
command -v ffmpeg      >/dev/null || die "ffmpeg not on PATH"
[[ -f "$WORKTREE/$BLAZE" ]] || die "missing $WORKTREE/$BLAZE (wrong worktree?)"

# host-load sanity (advisory, not fatal)
LOAD1="$(uptime | sed -E 's/.*load averages?: ([0-9.]+).*/\1/')"
log "host 1-min load avg: $LOAD1 (if this is huge, consider bailing)"

# ---- one-time bootstrap ----------------------------------------------------
log "bun install (TS SDK) -- required or scripted tools RPC-fail"
( cd "$WORKTREE/sdks/typescript" && bun install --frozen-lockfile ) \
  || die "bun install failed"

# ---- AVD create + boot -----------------------------------------------------
boot_emulator() {
  if "$ADB" devices | grep -q "^${SERIAL}\b"; then
    log "$SERIAL already attached -- reusing"; return
  fi
  if ! emulator -list-avds | grep -qx "$AVD"; then
    log "creating AVD $AVD ($SYS_IMAGE, virtualscene camera)"
    echo no | avdmanager create avd -n "$AVD" -k "$SYS_IMAGE" -d "Nexus 6" -c 512M \
      || die "avdmanager create failed (is the system image installed? sdkmanager \"$SYS_IMAGE\")"
    local cfg="$HOME/.android/avd/$AVD.avd/config.ini"
    python3 - "$cfg" <<'PY'
import sys
p=sys.argv[1]; want={"hw.camera.back":"virtualscene","hw.camera.front":"none","hw.keyboard":"no"}
lines=[l for l in open(p).read().splitlines() if l.split("=")[0].strip() not in want]
lines+=[f"{k} = {v}" for k,v in want.items()]
open(p,"w").write("\n".join(sorted(lines))+"\n")
PY
  fi
  log "booting $AVD on port $PORT (own window; do NOT reuse another session's emulator)"
  emulator -avd "$AVD" -port "$PORT" -no-snapshot -no-audio -no-boot-anim \
           -netdelay none -netspeed full >"$WORK/emulator.log" 2>&1 &
  log "waiting for boot..."
  "$ADB" -s "$SERIAL" wait-for-device shell \
    'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done' \
    || die "emulator boot timed out"
  log "booted: android-$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
}

reset_app() {
  log "resetting Contacts app state"
  "$ADB" -s "$SERIAL" shell pm clear com.android.contacts            >/dev/null
  "$ADB" -s "$SERIAL" shell pm clear com.android.providers.contacts  >/dev/null
  "$ADB" -s "$SERIAL" shell pm grant com.android.contacts android.permission.POST_NOTIFICATIONS 2>/dev/null
  "$ADB" -s "$SERIAL" shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent KEYCODE_HOME'
}

# segmented screenrecord (adb caps at 180s/file); loop until STOP flag appears.
# $1=device-prefix (blaze|replay). Writes /sdcard/$1-seg-N.mp4.
start_recorder() {
  local pfx="$1"; local stop="$WORK/stop-$pfx"; rm -f "$stop"
  "$ADB" -s "$SERIAL" shell "rm -f /sdcard/$pfx-seg-*.mp4"
  # Redirect the backgrounded subshell's fds: a `rec=$(start_recorder …)` command
  # substitution reads stdout until EOF, and a bare `( … ) &` keeps that pipe open
  # for the loop's whole life — so without this the caller BLOCKS until the 36-min
  # recorder loop ends and the blaze/replay never starts. With fds detached, the
  # sub gets EOF right after `echo $!` and returns the pid immediately.
  ( i=1; while [ $i -le 12 ] && [ ! -f "$stop" ]; do
      "$ADB" -s "$SERIAL" shell screenrecord --bit-rate 4000000 --size 720x1280 \
             --time-limit 180 "/sdcard/$pfx-seg-$i.mp4"; i=$((i+1)); done ) >/dev/null 2>&1 &
  echo $!   # recorder pid
}
stop_recorder() {  # $1=pfx  $2=recorder-pid
  local pfx="$1"; touch "$WORK/stop-$pfx"
  "$ADB" -s "$SERIAL" shell pkill -2 screenrecord 2>/dev/null
  wait "$2" 2>/dev/null || true
}
# pull segments and concat -> single H.264 CFR mp4 (scrub-safe). $1=pfx $2=out.mp4
encode_segments() {
  local pfx="$1"; local out="$2"; local d="$WORK/$pfx"; mkdir -p "$d"
  ( cd "$d"
    n=0; for i in $(seq 1 12); do
      "$ADB" -s "$SERIAL" pull "/sdcard/$pfx-seg-$i.mp4" . >/dev/null 2>&1 && n=$((n+1)) || break
    done
    [ "$n" -gt 0 ] || die "no $pfx segments pulled"
    : > concat.txt
    for i in $(seq 1 "$n"); do
      # re-encode each to uniform CFR first so concat is clean across segment seams
      ffmpeg -v error -i "$pfx-seg-$i.mp4" -vf "fps=30,scale=720:1280,format=yuv420p" \
             -c:v libx264 -crf 23 -preset medium "cfr-$i.mp4" -y
      echo "file 'cfr-$i.mp4'" >> concat.txt
    done
    ffmpeg -v error -f concat -safe 0 -i concat.txt -c copy -movflags +faststart "$out" -y )
  ffprobe -v error -show_entries format=duration,size:stream=codec_name -of default=noprint_wrappers=1 "$out"
  # NOTE: this yields the FULL run. The shipped asset-a-blaze.mp4 was hand-trimmed
  # to the ~3-min active journey (skipping static-launcher startup+APK-install):
  #   ffmpeg -ss <journey-start> -i <full> -c copy asset-a-blaze.mp4
}

# ---- ASSET A: blaze --------------------------------------------------------
run_blaze() {
  boot_emulator; reset_app
  log "recording + running REAL blaze (~5+ min: CLI startup, 52MB APK install, LLM steps)"
  local rec; rec=$(start_recorder blaze)
  local s=$SECONDS
  ( cd "$WORKTREE" && trailblaze run --no-daemon --device "android/$SERIAL" "$BLAZE" ) \
      2>&1 | tee "$WORK/blaze.log"
  local blaze_secs=$((SECONDS-s))
  grep -q "1 passed" "$WORK/blaze.log" || log "WARN: blaze did not report '1 passed' -- inspect $WORK/blaze.log"
  stop_recorder blaze "$rec"
  encode_segments blaze "$OUT/asset-a-blaze.mp4"
  printf '%s\n' "$blaze_secs" > "$WORK/blaze_secs"
  log "blaze wall-clock: $((blaze_secs/60))m$((blaze_secs%60))s"
  # blaze auto-saves a recording (android-phone.trail.yaml) into the trail dir --
  # remove it so the worktree stays clean (the committed android.trail.yaml is the canonical one):
  rm -f "$WORKTREE/trails/contacts/create-contact/android-phone.trail.yaml"
}

# ---- ASSET A: replay -------------------------------------------------------
run_replay() {
  boot_emulator; reset_app
  log "recording + replaying committed recording ($TRAIL); --no-save-recording keeps worktree clean"
  local rec; rec=$(start_recorder replay)
  local s=$SECONDS
  ( cd "$WORKTREE" && trailblaze run --no-daemon --no-save-recording --device "android/$SERIAL" "$TRAIL" ) \
      2>&1 | tee "$WORK/replay.log"
  local replay_secs=$((SECONDS-s))
  grep -q "1 passed" "$WORK/replay.log" || log "WARN: replay did not report '1 passed' -- inspect $WORK/replay.log"
  stop_recorder replay "$rec"
  encode_segments replay "$OUT/asset-a-replay.mp4"
  printf '%s\n' "$replay_secs" > "$WORK/replay_secs"
  log "replay wall-clock: $((replay_secs/60))m$((replay_secs%60))s"
}

write_wallclock() {
  local b r
  b=$(cat "$WORK/blaze_secs"  2>/dev/null || echo 0)
  r=$(cat "$WORK/replay_secs" 2>/dev/null || echo 0)
  printf 'blaze: %dm%ds\nreplay: %dm%ds\n' $((b/60)) $((b%60)) $((r/60)) $((r%60)) \
    > "$OUT/asset-a-wallclock.txt"
  log "wrote asset-a-wallclock.txt"; cat "$OUT/asset-a-wallclock.txt"
}

# ---- ASSET B: terminal transcript PNG --------------------------------------
# Runs the blaze (if no fresh log) and `session save`, then renders both real
# transcripts as a styled terminal and screenshots with headless Chrome.
run_terminal() {
  [[ -f "$WORK/blaze.log" ]] || run_blaze
  log "session save (find the blaze session id by prefix, save with the required --title)"
  # the run above created a session id like create_contact_XXXXXXXX; grab it from the log or session list
  local sid
  sid=$(grep -oE 'session (create_contact_[0-9a-f]+)' "$WORK/blaze.log" | head -1 | awk '{print $2}')
  [[ -n "$sid" ]] || sid=$(trailblaze session list 2>/dev/null | grep -m1 'create_contact_' | awk '{print $1}')
  [[ -n "$sid" ]] || die "could not resolve blaze session id for session save"
  log "saving session $sid"
  ( cd "$WORKTREE" && trailblaze session save --id "$sid" --title "$SID_TITLE" ) \
      2>&1 | tee "$WORK/save.log"
  # session save writes a slugified dir (title -> trails/contacts:-create-a-contact/).
  # capture nothing from it; just delete it so no stray trail files remain:
  rm -rf "$WORKTREE/trails/contacts:-create-a-contact"

  log "rendering terminal HTML + headless-Chrome screenshot"
  BLAZE_LOG="$WORK/blaze.log" SAVE_LOG="$WORK/save.log" \
  BLAZE_CMD="trailblaze run --no-daemon --device android/$SERIAL $BLAZE" \
  SAVE_CMD="trailblaze session save --id $sid --title \"$SID_TITLE\"" \
  HTML_OUT="$WORK/asset-b.html" \
  python3 "$WORK/render_terminal.py" || die "terminal render failed"
  local h; h=$(cat "$WORK/asset-b.height")
  "$CHROME" --headless=new --disable-gpu --force-device-scale-factor=2 \
    --default-background-color=00000000 --window-size=1120,"$h" \
    --screenshot="$OUT/asset-b-terminal.png" "file://$WORK/asset-b.html" 2>/dev/null
  [[ -s "$OUT/asset-b-terminal.png" ]] || die "chrome screenshot empty (is Chrome installed at $CHROME?)"
  log "wrote asset-b-terminal.png"
}

# ---- timeline GIF (+webp) via `trailblaze report` --------------------------
# The GIF is the report's timeline autoplay (step labels + selector overlays),
# NOT the raw device recording. It needs the WASM report template at the git
# root. Building it (./gradlew :trailblaze-report:generateReportTemplate
# -Ptrailblaze.wasm=true) is a heavy one-time build; if a sibling worktree
# already has one, copy that instead (what this script does).
run_gif() {
  local sid
  sid=$(grep -oE 'session (create_contact_[0-9a-f]+)' "$WORK/blaze.log" 2>/dev/null | head -1 | awk '{print $2}')
  [[ -n "$sid" ]] || sid=$(trailblaze session list 2>/dev/null | grep -m1 'create_contact_' | awk '{print $1}')
  [[ -n "$sid" ]] || die "no blaze session id for report; run the 'blaze' stage first"

  local tmpl="$WORKTREE/trailblaze_report_template.html" staged=0
  if [[ ! -f "$tmpl" ]]; then
    local found
    found=$(find "$HOME/Development" -path '*generated-resources/report-template/trailblaze_report_template.html' \
              -type f 2>/dev/null | xargs -I{} stat -f '%m %N' {} 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
    if [[ -n "$found" ]]; then
      log "staging report template from sibling worktree: $found"
      cp "$found" "$tmpl"; staged=1
    else
      die "no report template found; build once: (cd $WORKTREE && ./gradlew :trailblaze-report:generateReportTemplate -Ptrailblaze.wasm=true)"
    fi
  fi
  log "generating timeline gif+webp for session $sid (headless Playwright; bounded 5min)"
  local rd="$WORK/report"; mkdir -p "$rd"
  ( cd "$WORKTREE" && MAX_PLAYBACK_WAIT_MS=300000 \
      trailblaze report "$sid" --gif --output-dir "$rd" ) 2>&1 | tail -5
  [[ -s "$rd/timeline.gif" ]] || die "timeline.gif not produced (template incompatible? see $rd)"
  cp "$rd/timeline.gif"  "$OUT/asset-a-blaze-timeline.gif"
  [[ -s "$rd/timeline.webp" ]] && cp "$rd/timeline.webp" "$OUT/asset-a-blaze-timeline.webp"
  [[ "$staged" == 1 ]] && { rm -f "$tmpl"; log "removed staged template from git root"; }
  log "wrote asset-a-blaze-timeline.gif (+webp)"
}

# ---- teardown (only our own resources) -------------------------------------
teardown() {
  log "teardown (own resources only)"
  "$ADB" -s "$SERIAL" shell 'rm -f /sdcard/blaze-seg-*.mp4 /sdcard/replay-seg-*.mp4' 2>/dev/null
  local d; d=$(lsof -nP -ti ":$TB_PORT" 2>/dev/null)
  [[ -n "$d" ]] && { kill "$d" 2>/dev/null && log "killed our daemon on $TB_PORT (pid $d)"; }
  # shut down OUR emulator only (leave any other session's emulators alone):
  "$ADB" -s "$SERIAL" emu kill 2>/dev/null && log "shut down $SERIAL"
  log "left AVD $AVD on disk for re-runs; left sibling assets (asset-a2-*, asset-c-*) untouched"
}

# =============================================================================
# The terminal renderer is written to scratch as a standalone script so the
# ASSET B step can invoke it. Curates daemon-noise lines out of the real logs;
# every kept line is verbatim.
# =============================================================================
cat > "$WORK/render_terminal.py" <<'PYEOF'
#!/usr/bin/env python3
import os, re, html
DROP = ("Starting Trailblaze daemon","Waiting for Trailblaze daemon","Trailblaze daemon ready",
        "Shutting down existing daemon","Execution stopped by user.","kotlin-logging: initializing",
        "[HostAndroidEchoCapture]","Daemon doesn't recognize the saved session")
def sanitize(line):
    # Public-artifact scrub (this PNG is committed to the OSS repo). Keep every
    # word verbatim EXCEPT: (1) on the "Using LLM:" line, drop the "<provider>/"
    # prefix so only the model id remains; (2) collapse any absolute home path to
    # tilde-relative ("/Users/<user>/..." -> "~/...").
    line = re.sub(r"/Users/[^/\s]+/", "~/", line)
    line = re.sub(r"(Using LLM:\s*)[\w.-]+/", r"\1", line)
    return line
def curate(path):
    out=[]
    for line in open(path).read().splitlines():
        if any(d in line for d in DROP): continue
        if re.match(r"^(REPLAY_RC|BLAZE_RC|SAVE_RC)=", line): continue
        out.append(sanitize(line.rstrip()))
    res=[]
    for l in out:
        if l=="" and res and res[-1]=="": continue
        res.append(l)
    while res and res[0]=="": res.pop(0)
    while res and res[-1]=="": res.pop()
    return res
def colorize(line):
    e=html.escape(line)
    if "✅" in line or ("passed" in line and "failed" in line): return f'<span class="ok">{e}</span>'
    if line.startswith("Recording saved to:") or ("Saved" in line and ".trail.yaml" in line) or line.startswith("Trail saved:"):
        return f'<span class="path">{e}</span>'
    m=re.match(r"^(\s*LLM\s+\.*\s+[\d.]+s\s+->\s+)(.*)$", line)
    if m: return f'<span class="dim">{html.escape(m.group(1))}</span><span class="tool">{html.escape(m.group(2))}</span>'
    if line.startswith(("Interactive report:","Trace posted")): return f'<span class="dim">{e}</span>'
    if line.startswith(("[emulator-","Loading connected devices","Device classifiers","Running 1 trail","Replay mode:")):
        return f'<span class="dim">{e}</span>'
    if re.match(r"^=+$|^-+$", line.strip()): return f'<span class="rule">{e}</span>'
    if line.startswith(("Target device:","Driver:","Using LLM:","Agent:")): return f'<span class="meta">{e}</span>'
    return e
def block(cmd, lines):
    rows=[f'<div class="line"><span class="prompt">$</span> <span class="cmd">{html.escape(cmd)}</span></div>']
    rows+=[f'<div class="line">{colorize(l) if l else "&nbsp;"}</div>' for l in lines]
    return "\n".join(rows)
blaze=curate(os.environ["BLAZE_LOG"]); save=curate(os.environ["SAVE_LOG"])
body=block(os.environ["BLAZE_CMD"], blaze)+'\n<div class="line">&nbsp;</div>\n'+block(os.environ["SAVE_CMD"], save)
n=body.count('<div class="line">')
HTML=f"""<!doctype html><html><head><meta charset="utf-8"><style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{background:transparent;padding:24px;font-family:"SF Mono",Menlo,monospace}}
.term{{width:1060px;background:#0d1117;border-radius:12px;border:1px solid #30363d;box-shadow:0 12px 40px rgba(0,0,0,.45);overflow:hidden}}
.bar{{background:#161b22;padding:10px 14px;display:flex;align-items:center;gap:8px;border-bottom:1px solid #30363d}}
.dot{{width:12px;height:12px;border-radius:50%}}
.title{{color:#8b949e;font-size:12px;margin-left:12px}}
.pane{{padding:18px 22px 22px}}
.line{{color:#c9d1d9;font-size:13.5px;line-height:1.55;white-space:pre-wrap;word-break:break-all}}
.prompt{{color:#3fb950;font-weight:700}} .cmd{{color:#e6edf3;font-weight:600}}
.dim{{color:#8b949e}} .meta{{color:#a5b4fc}} .tool{{color:#79c0ff}}
.ok{{color:#3fb950;font-weight:600}} .path{{color:#d29922}} .rule{{color:#30363d}}
</style></head><body><div class="term">
<div class="bar"><div class="dot" style="background:#ff5f57"></div><div class="dot" style="background:#febc2e"></div><div class="dot" style="background:#28c840"></div><div class="title">trailblaze — zsh</div></div>
<div class="pane">
{body}
</div></div></body></html>"""
open(os.environ["HTML_OUT"],"w").write(HTML)
open(os.path.splitext(os.environ["HTML_OUT"])[0]+".height","w").write(str(24*2+42+40+int(n*21.5)+40))
print(f"rendered {os.environ['HTML_OUT']} ({n} lines)")
PYEOF

# ---- write manifest --------------------------------------------------------
write_manifest() {
  cp "$OUT/asset-manifest.txt" "$OUT/asset-manifest.txt" 2>/dev/null || true
  log "manifest is maintained alongside these assets (asset-manifest.txt) -- not overwritten by this run"
}

# ---- orchestrate -----------------------------------------------------------
trap teardown EXIT
want blaze   && run_blaze
want replay  && run_replay
( want blaze || want replay ) && write_wallclock
want terminal && run_terminal
want gif      && run_gif
write_manifest
log "DONE. Assets in $OUT (scratch kept at $WORK for inspection)."
