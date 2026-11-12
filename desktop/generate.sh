#!/usr/bin/env bash
# Thin wrapper kept for install.sh (Plasma widget). The real work is in generate.py, which CMake
# also runs directly, so the Windows build needs no bash.
set -euo pipefail
exec python3 -I "$(dirname "$0")/generate.py"
