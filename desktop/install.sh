#!/usr/bin/env bash
# Installs (or upgrades) the plasmoid.
#
# The app logic (notes, account, encryption, sync) is shared with the other desktop build and lives in
# shared/. The plasmoid package can only contain files inside plasmoid/ (QML may not read other
# folders at runtime), so this script generates shared/Migrations.js (from ../core/storage/migrations/)
# and shared/FirebaseConfig.js (from firebase.local.json), then copies everything in shared/ into
# plasmoid/contents/ui/. Those copies and the generated files are not committed.
# Run this after every change in shared/ or core/storage/migrations/ and instead of calling
# kpackagetool6 by hand.
set -euo pipefail
cd "$(dirname "$0")"

./generate.sh

# copy the shared files into the package, dropping copies of files that no longer exist in shared/
for f in plasmoid/contents/ui/*.js; do
    [ -e "shared/$(basename "$f")" ] || rm -f "$f"
done
cp shared/*.js shared/*.qml plasmoid/contents/ui/

if kpackagetool6 -t Plasma/Applet -l 2>/dev/null | grep -q "org.kde.plasma.chaosnoteviewer"; then
    kpackagetool6 -t Plasma/Applet -u plasmoid
else
    kpackagetool6 -t Plasma/Applet -i plasmoid
fi
echo "Done. If the widget did not refresh: plasmashell --replace &"
