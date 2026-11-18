#!/bin/sh
# make-plasmoid.sh VERSION [OUTDIR]
#
# Builds the Plasma widget as an installable package, OUTDIR/ChaosNoteViewer-plasmoid-VERSION.plasmoid (default
# OUTDIR: dist). It is what install.sh installs, but as one file: the shared app logic is copied into the package
# (a widget can only read files inside itself), the generated files are made without the author's own Firebase
# key (firebase.local.json is ignored), and the version in metadata.json is the release's. Nothing in the
# working tree is changed (the generated, git-ignored files in shared/ are made again the normal way at the end).
#
#   kpackagetool6 -t Plasma/Applet -i ChaosNoteViewer-plasmoid-VERSION.plasmoid     (-u to upgrade)
set -eu
cd "$(dirname "$0")/.."

VERSION=${1:?usage: make-plasmoid.sh VERSION [OUTDIR]}
OUT=${2:-dist}

CHAOS_NO_LOCAL_CONFIG=1 python3 -I generate.py

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -r plasmoid "$stage/plasmoid"
# generated and copied files of an earlier install.sh run are not part of the package source
rm -f "$stage"/plasmoid/contents/ui/*.js "$stage"/plasmoid/contents/ui/AppController.qml
rm -rf "$stage/plasmoid/contents/ui/icons"
cp shared/*.js shared/*.qml "$stage/plasmoid/contents/ui/"
cp -r shared/icons "$stage/plasmoid/contents/ui/icons"

sed -i "s/\"Version\": *\"[^\"]*\"/\"Version\": \"$VERSION\"/" "$stage/plasmoid/metadata.json"
grep -q "\"Version\": \"$VERSION\"" "$stage/plasmoid/metadata.json" || { echo "could not set the version in metadata.json" >&2; exit 1; }

mkdir -p "$OUT"
file="$(cd "$OUT" && pwd)/ChaosNoteViewer-plasmoid-$VERSION.plasmoid"
rm -f "$file"
(cd "$stage/plasmoid" && zip -qr -X "$file" .)

# the generated files in shared/ go back to what a normal build makes (with firebase.local.json, if there is one)
python3 -I generate.py >/dev/null
echo "$file"
