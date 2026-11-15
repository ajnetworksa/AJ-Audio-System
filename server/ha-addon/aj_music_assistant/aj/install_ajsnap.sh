#!/bin/sh
# Copies our providers into the Music Assistant package inside the image and applies patches/*.patch.
# Used by both server/docker/Dockerfile and the HA add-on Dockerfile (D-008: one patch set, two packagings).
set -e
SRC=${1:-/tmp/aj}
PKG=$(find / -type d -path "*/site-packages/music_assistant" 2>/dev/null | head -1)
[ -n "$PKG" ] || { echo "music_assistant package not found"; exit 1; }
echo "music_assistant at $PKG"
rm -rf "$PKG/providers/ajsnap"
cp -r "$SRC/ajsnap" "$PKG/providers/ajsnap"
for p in "$SRC"/patches/*.patch; do
  [ -f "$p" ] || continue
  echo "applying $p"; (cd "$PKG/.." && patch -p1 --forward < "$p")
done
PY=$(ls /app/venv/bin/python3 2>/dev/null || command -v python3)
"$PY" -m py_compile "$PKG/providers/ajsnap/aj_api.py"
echo "ajsnap installed"
