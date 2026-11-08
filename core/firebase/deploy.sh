#!/usr/bin/env bash
# Deploys Firestore rules and indexes to the Firebase project.
# Usage: ./deploy.sh [project-id]      (default: chaosnoteviewer)
set -euo pipefail
cd "$(dirname "$0")"
project="${1:-chaosnoteviewer}"

[ -d node_modules ] || npm ci

# log in only if there is no usable login yet (opens the browser)
npx firebase projects:list >/dev/null 2>&1 || npx firebase login

[ -f .firebaserc ] || printf '{ "projects": { "default": "%s" } }\n' "$project" > .firebaserc

npx firebase deploy --project "$project" --only firestore:rules,firestore:indexes
