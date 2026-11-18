#!/bin/sh
# release-notes-desktop.sh — writes the release notes (markdown) of a desktop release to stdout.
#
#   sh .github/scripts/release-notes-desktop.sh TAG [PREV_TAG] [END_REF]
#
# Tags: desktop-vX.Y.0 is a full release, desktop-vX.Y.Z with Z > 0 is a nightly build.
# A full release lists everything since the previous full release (so it includes the nightlies in between); a nightly
# lists the changes since the previous desktop tag. DIST=<folder with the release files> makes the install table show
# the real file names; without it the table shows the patterns (a local preview). The changes are the commit
# subjects grouped by conventional-commit type (feat, fix, docs, ...).
set -eu

TAG=$1
END=${3:-$TAG}
VER=${TAG#desktop-v}
PATCH=${VER##*.}
REPO=${GITHUB_REPOSITORY:-mrkuko/ChaosNoteViewer}

if [ "$PATCH" = 0 ]; then KIND=full; else KIND=nightly; fi

# previous tag: for a full release the previous full release, for a nightly any previous desktop tag
PREV=${2:-}
if [ -z "$PREV" ]; then
    for t in $(git tag --list 'desktop-v[0-9]*' --sort=-creatordate); do     # version sort gets suffixes wrong
        [ "$t" = "$TAG" ] && continue
        git merge-base --is-ancestor "$t" "$END" 2>/dev/null || continue
        if [ "$KIND" = full ]; then
            case $t in *.0) PREV=$t; break ;; esac
        else
            PREV=$t; break
        fi
    done
fi
RANGE=${PREV:+$PREV..}$END

# the real name of a release file (first match of a glob in $DIST), or the glob itself without $DIST
asset() {
    if [ -n "${DIST:-}" ]; then
        for f in "$DIST"/$1; do [ -e "$f" ] && { basename "$f"; return; }; done
    fi
    echo "$1"
}
WIN=$(asset 'ChaosNoteViewer-*-windows-x64.zip')
LIN=$(asset 'ChaosNoteViewer-*-linux-x86_64.AppImage')
ARM=$(asset 'ChaosNoteViewer-*-linux-aarch64.AppImage')
PLA=$(asset 'ChaosNoteViewer-plasmoid-*.plasmoid')

echo "Your notes with deadlines on your desktop: a tray app for Linux and Windows and a KDE Plasma 6 widget, with"
echo "encrypted sync through your own Firebase project."
echo
if [ "$KIND" = nightly ]; then
    echo "> **Nightly build.** Fresh from development, it may be rough around the edges."
    echo "> Full releases are the \`desktop-vX.Y.0\` ones."
    echo
fi

cat <<E2
## Downloads

| System | File |
|---|---|
| Windows 10/11 (64-bit) | \`$WIN\` |
| Linux, any distribution (x86_64, glibc 2.35+) | \`$LIN\` |
| Linux, any distribution (ARM64, glibc 2.39+) | \`$ARM\` |
| KDE Plasma 6 widget (panel or desktop) | \`$PLA\` |

Checksums are in \`SHA256SUMS\`. Everything works without an account; to sync, the app asks for your own Firebase
project on first start, see the [README](https://github.com/$REPO#sync-with-your-own-firebase-project).

## Install

**Windows:** unzip the file anywhere and run \`chaosnoteviewer.exe\`; the notes icon appears in the notification area.
The app is not signed, so Windows may show "Windows protected your PC": More info, Run anyway.

**Linux tray app:** make the AppImage executable and run it:

\`\`\`sh
chmod +x $LIN
./$LIN
\`\`\`

It needs FUSE 2 (\`libfuse2\`); without it, run it with \`--appimage-extract-and-run\`.

**KDE Plasma widget:** the \`.plasmoid\` file is the whole widget. Either

- right-click the desktop or a panel, **Add or Manage Widgets**, **Get New**, **Install Widget From Local File...**,
  choose \`$PLA\`; or
- in a terminal: \`kpackagetool6 -t Plasma/Applet -i $PLA\`

Then find **Chaos Note Viewer** in Add or Manage Widgets and drag it onto a panel or the desktop.
To update an installed widget: \`kpackagetool6 -t Plasma/Applet -u $PLA\`, then log out and in again (or run
\`plasmashell --replace &\`). To remove it: \`kpackagetool6 -t Plasma/Applet -r org.kde.plasma.chaosnoteviewer\`.

## What's new

E2

# commit subjects since PREV (two messages typed into one subject are split; the version bump
# commits "chore: release vX.Y.Z" / "chore: bump version ..." are left out)
LOG=$(git log --no-merges --pretty='%s' "$RANGE" |
      sed 's/ \(feat\|fix\|docs\|build\|ci\|chore\|refactor\|perf\|test\|style\)\([(:]\)/\n\1\2/g' |
      sed 's/[;,] *$//' |
      grep -vE '^chore(\([^)]*\))?: (release|bump version)' || true)

# One section. Grouping: first the commits without a scope together with the scopes that occur only
# once in this section ("- **Scope:** text"); then every scope that occurs two or more times becomes
# one bold bullet with its commits as a nested list, in the order the scopes first appear.
section() {   # title, regex of types
    out=$(printf '%s\n' "$LOG" | sed -n -E "s/^($2)(\\(([^)]*)\\))?!?: (.+)\$/\\3\\t\\4/p" |
          awk -F'\t' '
            function cap(s) { return toupper(substr(s, 1, 1)) substr(s, 2) }
            { n++; sc[n] = $1; tx[n] = $2
              if ($1 != "") { if (!($1 in cnt)) order[++no] = $1; cnt[$1]++ } }
            END {
              for (i = 1; i <= n; i++)
                if (sc[i] == "") printf "- %s\n", tx[i]
                else if (cnt[sc[i]] < 2) printf "- **%s:** %s\n", cap(sc[i]), tx[i]
              for (k = 1; k <= no; k++) {
                s = order[k]
                if (cnt[s] < 2) continue
                printf "- **%s**\n", cap(s)
                for (i = 1; i <= n; i++) if (sc[i] == s) printf "  - %s\n", tx[i]
              }
            }')
    [ -n "$out" ] && printf '### %s\n\n%s\n\n' "$1" "$out"
    return 0
}
section "New" "feat"
section "Fixes" "fix"
section "Performance" "perf"
section "Documentation" "docs"
section "Build and packaging" "build|ci"
section "Maintenance" "chore|refactor|test|style"
other=$(printf '%s\n' "$LOG" | grep -vE '^(feat|fix|perf|docs|build|ci|chore|refactor|test|style)(\(|:|!)' | grep -v '^$' | sed 's/^/- /' || true)
[ -n "$other" ] && printf '### Other\n\n%s\n\n' "$other"

if [ -n "$PREV" ]; then
    echo "**Full changelog:** https://github.com/$REPO/compare/$PREV...$TAG"
else
    echo "First release."
fi
