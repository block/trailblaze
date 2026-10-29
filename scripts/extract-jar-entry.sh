# shellcheck shell=bash
# Sourced, not executed: reads one entry out of a JAR without Java.
#
# A JAR is a zip, so unzip reads it; minimal Linux images often lack unzip but carry bsdtar or
# python3, which read zips too. install.sh carries its own copy of extract_jar_entry because it
# runs piped from curl, with no sibling files to source.

# True when at least one supported extractor is on PATH. Installers check this before removing
# an existing install, so a host that cannot extract keeps what it had.
jar_extractor_available() {
  command -v unzip > /dev/null 2>&1 || command -v bsdtar > /dev/null 2>&1 ||
    command -v python3 > /dev/null 2>&1
}

# extract_jar_entry <jar> <entry> <out>: writes the entry to <out>; non-zero if the JAR lacks it,
# no extractor is available, or the result is empty.
extract_jar_entry() {
  local jar="$1" entry="$2" out="$3" rc=0
  if command -v unzip > /dev/null 2>&1; then
    # Status 1 is only a warning (bytes prepended to the archive, say); the entry was still written.
    unzip -p "$jar" "$entry" > "$out" 2> /dev/null || rc=$?
    [ "$rc" -le 1 ] || return 1
  elif command -v bsdtar > /dev/null 2>&1; then
    bsdtar -xOf "$jar" "$entry" > "$out" 2> /dev/null || return 1
  elif command -v python3 > /dev/null 2>&1; then
    python3 -c 'import sys, zipfile; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))' \
      "$jar" "$entry" > "$out" 2> /dev/null || return 1
  else
    return 1
  fi
  [ -s "$out" ]
}
