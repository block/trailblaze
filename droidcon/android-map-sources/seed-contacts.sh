#!/usr/bin/env bash
# Seed the trail-themed contacts the Android contacts map was built against.
# The committed example screenshots, the search/list waypoints, and the
# multi-select shortcut (dragTo ref a286 at 886,788) all assume this exact
# contact set on a 1440x2560 device (AVD profile "Nexus 6", android-34).
#
# Usage: ./seed-contacts.sh [adb-serial]   (defaults to the only device)
#
# android-34 `content` tool gotchas this script works around:
#  - NEVER bind an empty string (--bind col:s:) — it crashes the tool.
#    Bind aggregation_mode:i:0 instead and leave the account NULL (local contact).
#  - `content insert` prints NOTHING on success; the new _id is fetched with
#    a query sorted by _id DESC.
#  - Projection separator is `:`, not `,`.
set -euo pipefail

SERIAL="${1:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

CONTACTS=(
  "Amber Alpine"
  "Bruno Basecamp"
  "Casey Trailblaze"
  "Dakota Ridge"
  "Harper Summit"
  "Jordan Peak"
  "Morgan Trailhead"
  "Sage Canyon"
)

for NAME in "${CONTACTS[@]}"; do
  "${ADB[@]}" shell content insert \
    --uri content://com.android.contacts/raw_contacts \
    --bind aggregation_mode:i:0
  RID=$("${ADB[@]}" shell content query \
    --uri content://com.android.contacts/raw_contacts \
    --projection _id --sort '"_id DESC"' | head -1 | tr -dc '0-9')
  "${ADB[@]}" shell content insert \
    --uri content://com.android.contacts/data \
    --bind raw_contact_id:i:"$RID" \
    --bind mimetype:s:vnd.android.cursor.item/name \
    --bind data1:s:"'$NAME'"
  if [ "$NAME" = "Casey Trailblaze" ]; then
    # Casey is the star: the detail/share/ringtone/link trails all open her card.
    "${ADB[@]}" shell content insert \
      --uri content://com.android.contacts/data \
      --bind raw_contact_id:i:"$RID" \
      --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
      --bind data1:s:555-0134 \
      --bind data2:i:2
  fi
  echo "seeded: $NAME (raw_contact _id=$RID)"
done

echo "Done. Open the Contacts app once so the provider aggregates display names."
