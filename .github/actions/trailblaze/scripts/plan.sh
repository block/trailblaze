#!/usr/bin/env bash
# Turns a run request (YAML or JSON) into the job matrix: one entry per run per shard.
#
#   defaults: { mode: replay }
#   runs:
#     - name: android-phone
#       device: android               # android | ios | web
#       android: { api: 34, profile: pixel_6 }
#       app: app-debug.apk
#       trails: [trails/]
#       shards: 2
#     - name: iphone
#       device: ios
#       ios: { simulator: iPhone 17 Pro }
#       trails: [trails/]
#
# Optional on any run: `mode` (replay | self-heal | ai), `args` (extra `trailblaze run` flags),
# `config-dir` (the workspace's trails/config, when it isn't found from the trails), `setup` (a
# shell command run on the device's runner before the trails) and `runs-on`.
#
# A shard gets whole trail directories, so a trail's platform files always stay together.
set -euo pipefail
shopt -s nullglob
shopt -s globstar 2> /dev/null || true  # bash 4+, so `**` matches nested directories

fail() { echo "::error title=Trailblaze request::$*"; exit 1; }
[ -f "$REQUEST" ] || fail "No request file at $REQUEST."

runs=$(yq -o=json '.' "$REQUEST" | jq -c '(.defaults // {}) as $d | [(.runs // [])[] | $d * .]')
[ "$(jq length <<< "$runs")" -gt 0 ] || fail "$REQUEST has no runs."

# Prints the directories that directly hold a trail under one request path.
trail_dirs() {
  local p=$1 m
  if [ -f "$p" ]; then
    echo "$p"
  elif [ -d "$p" ]; then
    find "$p" -type f \( -name '*.trail.yaml' -o -name trail.yaml -o -name blaze.yaml \) \
      -not -path '*/config/trailmaps/*' -exec dirname {} \;
  else
    for m in $p; do trail_dirs "$m"; done
  fi
}

# Drops a directory whose parent is also listed: the CLI already runs it through the parent.
outermost() {
  local last=""
  sort -u | while read -r d; do
    d="${d%/}"
    if [ -n "$last" ] && [[ "$d/" == "$last/"* ]]; then continue; fi
    echo "$d"
    last="$d"
  done
}

entries="[]"
names=" "
for i in $(seq 0 $(($(jq length <<< "$runs") - 1))); do
  run=$(jq -c ".[$i]" <<< "$runs")
  name=$(jq -r '.name // ""' <<< "$run")
  device=$(jq -r '.device // ""' <<< "$run")
  [[ "$name" =~ ^[a-z0-9][a-z0-9-]*$ ]] || fail "Run $((i + 1)) needs a name of lowercase letters, digits and dashes."
  [[ "$names" != *" $name "* ]] || fail "Two runs are named $name."
  names+="$name "
  case "$device" in
    android | web) runner=ubuntu-latest ;;
    ios) runner=macos-26 ;;
    *) fail "Run $name: device must be android, ios or web." ;;
  esac
  case "$(jq -r '.mode // "replay"' <<< "$run")" in
    replay) args="--use-recorded-steps" ;;
    self-heal) args="--use-recorded-steps --self-heal" ;;
    ai) args="--no-use-recorded-steps" ;;
    *) fail "Run $name: mode must be replay, self-heal or ai." ;;
  esac
  extra=$(jq -r '.args // ""' <<< "$run")
  [ -z "$extra" ] || args+=" $extra"

  units=()
  while read -r u; do units+=("$u"); done < <(
    jq -r '.trails // [] | if type == "string" then [.] else . end | .[]' <<< "$run" \
      | while read -r p; do trail_dirs "$p"; done | outermost
  )
  [ "${#units[@]}" -gt 0 ] || fail "Run $name: no trails found under $(jq -c '.trails' <<< "$run")."
  shards=$(jq -r '.shards // 1' <<< "$run")
  [[ "$shards" =~ ^[1-9][0-9]*$ ]] || fail "Run $name: shards must be a positive number."
  [ "$shards" -le "${#units[@]}" ] || shards=${#units[@]}

  for s in $(seq 1 "$shards"); do
    trails=""
    for j in "${!units[@]}"; do
      if [ $((j % shards + 1)) = "$s" ]; then trails+="${units[$j]} "; fi
    done
    label="$name"
    [ "$shards" = 1 ] || label="$name ($s/$shards)"
    id="$name"
    [ "$shards" = 1 ] || id="$name-$s"
    entries=$(jq -c --argjson run "$run" --arg id "$id" --arg label "$label" --arg runner "$runner" \
      --arg trails "${trails% }" --arg args "$args" \
      '. + [{id: $id, label: $label, runner: ($run["runs-on"] // $runner), device: $run.device,
        trails: $trails, args: $args, app: ($run.app // ""),
        "android-api-level": (($run.android.api // 34) | tostring),
        "android-profile": ($run.android.profile // "pixel_6"),
        "ios-simulator": ($run.ios.simulator // "iPhone 17 Pro"),
        "config-dir": ($run["config-dir"] // ""), setup: ($run.setup // "")}]' <<< "$entries")
  done
done

[ "$(jq length <<< "$entries")" -le 256 ] || fail "The request makes $(jq length <<< "$entries") jobs; GitHub allows 256."
jq -r '.[] | "\(.label)\t\(.runner)\t\(.trails)"' <<< "$entries" | column -t -s $'\t'
echo "matrix=$(jq -c '{include: .}' <<< "$entries")" >> "$GITHUB_OUTPUT"
